#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][string]$RunDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
if ((Test-Path Env:OPENAI_API_KEY) -or (Test-Path Env:HELPDESK_OPENAI_API_KEY)) { throw 'BROWSER_AI_KEY_NOT_ALLOWED' }
$base = "http://127.0.0.1:$Port"
$session = "pilot-receipt-$([Guid]::NewGuid().ToString('N'))"
$stage = 'OPEN'
$opened = $false

function Invoke-Browser([string[]]$Arguments) {
    $raw = & npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session --raw @Arguments 2>&1
    $response = $raw -join "`n"
    if ($LASTEXITCODE -ne 0 -or $response -match '### Error') {
        if ($response -match 'Error:\s*(RECEIPT_[A-Z_]+)') { throw $Matches[1] }
        if ($response -match 'SyntaxError|Unexpected token') { throw 'RECEIPT_BROWSER_SYNTAX_ERROR' }
        if ($response -match 'TimeoutError|Timeout.*exceeded') { throw 'RECEIPT_BROWSER_TIMEOUT' }
        if ($response -match 'ReferenceError') { throw 'RECEIPT_BROWSER_REFERENCE_ERROR' }
        if ($response -match 'TypeError') { throw 'RECEIPT_BROWSER_TYPE_ERROR' }
        if ($response -match 'route.fetch') { throw 'RECEIPT_BROWSER_ROUTE_FETCH_FAILED' }
        throw 'RECEIPT_BROWSER_COMMAND_FAILED'
    }
    return $response
}

Push-Location -LiteralPath $RunDirectory
try {
    [void](Invoke-Browser @('open', "$base/login"))
    $opened = $true
    [void](Invoke-Browser @('snapshot'))
    $stage = 'FIXTURES'
    if (-not $env:HELPDESK_RECEIPT_BROWSER_CUSTOMER -or -not $env:HELPDESK_RECEIPT_BROWSER_OTHER -or -not $env:HELPDESK_RECEIPT_BROWSER_PASSWORD) {
        throw 'RECEIPT_FIXTURES_REQUIRED'
    }
    $fixture = @{
        firstCustomer=$env:HELPDESK_RECEIPT_BROWSER_CUSTOMER
        secondCustomer=$env:HELPDESK_RECEIPT_BROWSER_OTHER
        fixturePassword=$env:HELPDESK_RECEIPT_BROWSER_PASSWORD
        origin=$base
    } | ConvertTo-Json -Compress
    [void](Invoke-Browser @('run-code', "async (page) => { page.helpdeskReceiptFixture = $fixture; return true; }"))
    $stage = 'RECEIPT_FLOW'
    $code = @'
let phase = 'SETUP';
try {
    const fixture = page.helpdeskReceiptFixture;
    delete page.helpdeskReceiptFixture;
    if (!fixture) { throw new Error('RECEIPT_FIXTURES_REQUIRED'); }
    const {firstCustomer, secondCustomer, fixturePassword, origin} = fixture;
    const login = async (tab, username) => {
        await tab.goto(origin + '/login');
        await tab.locator('input[name=username]').fill(username);
        await tab.locator('input[name=password]').fill(fixturePassword);
        const posted = tab.waitForResponse(response => response.url() === origin + '/login'
            && response.request().method() === 'POST');
        await tab.locator('button[type=submit]').click();
        const result = await posted;
        if (result.status() !== 302 || result.headers().location?.includes('/login?error')) {
            throw new Error('RECEIPT_LOGIN_REJECTED');
        }
        await tab.waitForLoadState('domcontentloaded');
    };
    const logout = async tab => {
        await tab.goto(origin + '/tickets.html');
        const signedOut = await tab.evaluate(async () => {
            const csrfResponse = await fetch('/api/csrf');
            if (!csrfResponse.ok) { return false; }
            const csrf = await csrfResponse.json();
            await fetch('/logout', {method:'POST', headers:{[csrf.headerName]:csrf.token}, redirect:'manual'});
            return (await fetch('/api/csrf')).status === 401;
        });
        if (!signedOut) { throw new Error('RECEIPT_LOGOUT_FAILED'); }
    };
    const waitForStatus = text => page.waitForFunction(
        expected => document.querySelector('#ui-status').textContent === expected, text);
    const enterInquiry = async (title, content) => {
        await page.locator('#ticket-title-input').fill(title);
        await page.locator('#ticket-body-input').fill(content);
        await page.locator('#create-button').click();
    };

    phase = 'ORIGINAL_LOGIN';
    await login(page, firstCustomer);
    await page.goto(origin + '/tickets.html');
    const requests = [];
    await page.route('**/api/tickets', async route => {
        const request = route.request();
        if (request.method() !== 'POST') { await route.continue(); return; }

        const response = await route.fetch({maxRetries: 0});
        const ticket = response.ok() ? await response.json() : {};
        requests.push({
            payload: request.postDataJSON(), status: response.status(), id: ticket.id,
            location: response.headers().location, csrf: (await request.allHeaders())['x-csrf-token']
        });
        if (requests.length === 1 || requests.length === 3) {
            await route.abort('failed');
            return;
        }
        await route.fulfill({response});
    });

    phase = 'FIRST_INQUIRY';
    await enterInquiry('접수 확인용 합성 문의', '  합성 문의 원문\n공백을 보존합니다.  ');
    await waitForStatus('문의가 접수됐는지 확인하지 못했습니다.');
    if (requests.length !== 1 || requests[0].status !== 201
            || !(await page.locator('#create-button').isDisabled())
            || !(await page.locator('#confirm-receipt-button').isVisible())) {
        throw new Error('RECEIPT_FIRST_COMMIT_REQUIRED');
    }
    await page.screenshot({path:'unknown-receipt.png', fullPage:true});
    phase = 'CONFIRMATION';
    await page.locator('#confirm-receipt-button').click();
    await waitForStatus('기존 문의가 접수된 것을 확인했습니다.');
    const first = requests[0];
    const replay = requests[1];
    const sameSubmission = JSON.stringify(first.payload) === JSON.stringify(replay?.payload);
    const sameLocation = first.location && first.location === replay?.location;
    const sameSessionCsrf = Boolean(first.csrf) && first.csrf === replay?.csrf;
    if (requests.length !== 2 || replay?.status !== 200 || first.id !== replay.id
            || !sameSubmission || !sameLocation || !sameSessionCsrf
            || Number(await page.locator('#ticket-id').textContent()) !== first.id
            || await page.locator('#ticket-list li').count() !== 1) {
        throw new Error('RECEIPT_REPLAY_MISMATCH');
    }
    await page.screenshot({path:'confirmed-receipt.png', fullPage:true});

    phase = 'SECOND_INQUIRY';
    await enterInquiry('세션 전환용 합성 문의', '  재로그인 확인용 합성 문의  ');
    await waitForStatus('문의가 접수됐는지 확인하지 못했습니다.');
    const fresh = requests[2];
    if (requests.length !== 3 || fresh?.status !== 201 || fresh.id === first.id
            || fresh.payload.clientRequestId === first.payload.clientRequestId) {
        throw new Error('RECEIPT_FRESH_SUBMISSION_REQUIRED');
    }
    const switched = await page.context().newPage();
    try {
        phase = 'ORIGINAL_CUSTOMER_LOGOUT';
        await logout(switched);
        phase = 'SECOND_CUSTOMER_LOGIN';
        await login(switched, secondCustomer);
        phase = 'SESSION_SWITCH';
        await page.locator('#confirm-receipt-button').click();
        await waitForStatus('이전 접수 결과는 내 문의에서 확인해 주세요.');
        if (requests.length !== 3 || await page.locator('#confirm-receipt-button').isVisible()) {
            throw new Error('RECEIPT_POST_AFTER_SESSION_SWITCH');
        }

        phase = 'SECOND_CUSTOMER_LIST';
        await page.locator('#receipt-lookup a').click();
        await page.waitForFunction(() => document.querySelector('#my-tickets-status')?.textContent === '등록한 문의가 없습니다.');
        phase = 'SECOND_CUSTOMER_LOGOUT';
        await logout(switched);
        phase = 'ORIGINAL_CUSTOMER_RELOGIN';
        await login(switched, firstCustomer);
        phase = 'RELOAD';
        await page.goto(origin + '/tickets.html');
        await page.reload();
        if (requests.length !== 3 || await page.locator('#confirm-receipt-button').isVisible()) {
            throw new Error('RECEIPT_POST_AFTER_RELOAD');
        }
        phase = 'ORIGINAL_CUSTOMER_LIST';
        await page.getByRole('link', {name:'내 문의 목록과 추가 문의'}).click();
        await page.waitForFunction(() => document.querySelectorAll('#my-tickets li').length === 2);
        const links = await page.locator('#my-tickets li a').evaluateAll(
            items => items.map(item => Number(new URL(item.href).searchParams.get('ticketId'))));
        const ownerListMatches = links.includes(first.id) && links.includes(fresh.id);
        if (!ownerListMatches || requests.length !== 3) {
            throw new Error('RECEIPT_OWNER_LIST_MISMATCH');
        }
        return {statuses:requests.map(request => request.status), confirmedId:first.id, freshId:fresh.id,
            sameSubmission, sameLocation:Boolean(sameLocation), sameSessionCsrf, ownerListMatches,
            sessionSwitchNoPost:true, reloadNoPost:true};
    } finally {
        await switched.close();
    }
} catch (error) {
    if (typeof error?.message === 'string' && /^RECEIPT_[A-Z_]+$/.test(error.message)) {
        throw error;
    }
    throw new Error('RECEIPT_' + phase + (error?.name === 'TimeoutError' ? '_TIMEOUT' : '_FAILED'));
}
'@
    $codeFile = Join-Path $RunDirectory 'receipt-flow.js'
    [IO.File]::WriteAllText($codeFile, "async (page) => {`n$code`n}", [Text.UTF8Encoding]::new($false))
    $result = Invoke-Browser @('run-code', '--filename', $codeFile) | ConvertFrom-Json
    [ordered]@{
        evidence='PILOT_RECEIPT_BROWSER_POSTGRES'
        completed=$true
        statuses=$result.statuses
        confirmedId=$result.confirmedId
        freshId=$result.freshId
        sameSubmission=$result.sameSubmission
        sameLocation=$result.sameLocation
        sameSessionCsrf=$result.sameSessionCsrf
        sessionSwitchNoPost=$result.sessionSwitchNoPost
        reloadNoPost=$result.reloadNoPost
        ownerListMatches=$result.ownerListMatches
    } | ConvertTo-Json -Compress
} catch {
    $failureCode = if ($_.Exception.Message -match '^RECEIPT_[A-Z_]+$') { $_.Exception.Message } else { 'RECEIPT_BROWSER_STEP_FAILED' }
    [ordered]@{evidence='PILOT_RECEIPT_BROWSER_POSTGRES';completed=$false;stage=$stage;failureCode=$failureCode} | ConvertTo-Json -Compress
    exit 1
} finally {
    if ($opened) { try { [void](Invoke-Browser @('close')) } catch { } }
    Pop-Location
}
