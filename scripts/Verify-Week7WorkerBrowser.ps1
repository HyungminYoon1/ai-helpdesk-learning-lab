#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateSet('Receipt', 'Read')][string]$Mode,
    [Parameter(Mandatory)][ValidateRange(1, 65535)][int]$Port,
    [Parameter(Mandatory)][string]$RunDirectory,
    [long]$TicketId = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
# Java supplies disposable login credentials only. No AI key reaches the Browser launcher.
if (Test-Path Env:HELPDESK_OPENAI_API_KEY) { throw 'BROWSER_ENVIRONMENT_CONTAINS_AI_KEY' }
if (Test-Path Env:OPENAI_API_KEY) { throw 'BROWSER_ENVIRONMENT_CONTAINS_AI_KEY' }
$base = "http://127.0.0.1:$Port"
$session = "week7-$([Guid]::NewGuid().ToString('N'))"
$opened = $false
$stage = 'OPEN'
$failureKind = 'UNKNOWN'

function Invoke-Cli([string[]]$Arguments) {
    $output = & npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -match '### Error') {
        foreach ($known in @('SyntaxError', 'TimeoutError', 'TypeError', 'ReferenceError', 'LOGIN_FAILED', 'SECURITY_CONTROL_FAILED', 'RECEIPT_FAILED')) {
            if (($output -join "`n") -match ('\b' + $known + '\b')) { $script:failureKind = $known; break }
        }
        throw 'BROWSER_STEP_FAILED'
    }
    return ($output -join "`n")
}

function Run-Code([string]$Code) {
    return Invoke-Cli @('run-code', "async (page) => { $($Code -replace '\r?\n', ' ') }")
}

function Login([string]$Role) {
    [void](Invoke-Cli @('goto', "$base/login"))
    [void](Invoke-Cli @('snapshot'))
    $username = [System.Environment]::GetEnvironmentVariable("HELPDESK_BROWSER_${Role}_NAME", 'Process')
    $password = [System.Environment]::GetEnvironmentVariable("HELPDESK_BROWSER_${Role}_PASSWORD", 'Process')
    if (-not $username -or -not $password) { throw 'BROWSER_FIXTURE_CREDENTIAL_REQUIRED' }
    $nameJson = ConvertTo-Json -InputObject $username -Compress
    $passwordJson = ConvertTo-Json -InputObject $password -Compress
    [void](Run-Code "await page.locator('input[name=username]').fill($nameJson); await page.locator('input[name=password]').fill($passwordJson); await page.locator('button[type=submit]').click(); const status = await page.evaluate(async () => (await fetch('/api/csrf')).status); if (status !== 200) throw new Error('LOGIN_FAILED');")
}

Push-Location -LiteralPath $RunDirectory
try {
    [void](Invoke-Cli @('open', "$base/ai-suggestions.html"))
    $opened = $true
    [void](Invoke-Cli @('snapshot'))
    if ($Mode -eq 'Receipt') {
        $stage = 'ANONYMOUS_READ'
        [void](Run-Code "await page.locator('#ai-ticket-id-input').fill('1'); const received = page.waitForResponse(response => response.url().endsWith('/ai-suggestion')); await page.locator('#ai-query-form button').click(); if ((await received).status() !== 401) throw new Error('ANONYMOUS_READ_FAILED'); await page.waitForFunction(() => document.querySelector('#ai-ui-status').textContent === '로그인이 필요합니다.');")
        $stage = 'USER_LOGIN'
        Login 'USER'
        [void](Invoke-Cli @('goto', "$base/tickets.html"))
        [void](Invoke-Cli @('snapshot'))
        $titleJson = ConvertTo-Json -InputObject $env:HELPDESK_BROWSER_TITLE -Compress
        $bodyJson = ConvertTo-Json -InputObject $env:HELPDESK_BROWSER_BODY -Compress
        $code = @'
let sentRequest;
page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/tickets')) sentRequest = request;
});
const controls = await page.evaluate(async ({title, body}) => {
    const userQueryStatus = (await fetch('/api/tickets/1/ai-suggestion')).status;
    const missing = await fetch('/api/tickets', {method: 'POST', credentials: 'same-origin',
        headers: {'Content-Type': 'application/json'}, body: JSON.stringify({title, body})});
    if (missing.status !== 403 || userQueryStatus !== 403) throw new Error('SECURITY_CONTROL_FAILED');
    return {missingCsrfStatus: missing.status, userQueryStatus};
}, {title: TITLE_VALUE, body: BODY_VALUE});
await page.locator('#ticket-title-input').fill(TITLE_VALUE);
await page.locator('#ticket-body-input').fill(BODY_VALUE);
const csrfReceived = page.waitForResponse(response => response.url().endsWith('/api/csrf'));
const ticketReceived = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/tickets'));
await page.locator('#create-button').click();
const csrfResponse = await csrfReceived;
if (csrfResponse.status() !== 200) throw new Error('CSRF_READ_FAILED');
const csrf = await csrfResponse.json();
const received = await ticketReceived;
if (received.status() !== 201) throw new Error('RECEIPT_FAILED');
const ticket = await received.json();
await page.waitForFunction(() => document.querySelector('#ui-status').textContent === 'Ticket을 생성했습니다.');
const result = {...controls, ticketId: ticket.id, receiptStatus: received.status()};
const headers = await sentRequest.allHeaders();
result.sessionCookieObserved = (headers.cookie ?? '').includes('JSESSIONID=');
result.csrfHeaderObserved = Object.hasOwn(headers, csrf.headerName.toLowerCase());
result.anonymousQueryStatus = 401;
return result;
'@
        # Observe the actual request headers in memory; never serialize Cookie or Token values.
        $code = $code.Replace('TITLE_VALUE', $titleJson).Replace('BODY_VALUE', $bodyJson)
        $stage = 'RECEIPT_POST'
        $raw = Run-Code $code
        $safe = [ordered]@{}
        foreach ($field in @('ticketId', 'receiptStatus', 'missingCsrfStatus', 'userQueryStatus', 'anonymousQueryStatus')) {
            if ($raw -notmatch ('"' + $field + '"\s*:\s*([0-9]+)')) { throw 'BROWSER_RECEIPT_EVIDENCE_MISSING' }
            $safe[$field] = [long]$Matches[1]
        }
        foreach ($field in @('sessionCookieObserved', 'csrfHeaderObserved')) {
            $safe[$field] = $raw -match ('"' + $field + '"\s*:\s*true')
        }
    } else {
        $stage = 'AGENT_LOGIN'
        Login 'AGENT'
        [void](Invoke-Cli @('goto', "$base/ai-suggestions.html"))
        [void](Invoke-Cli @('snapshot'))
        $screenshotJson = ConvertTo-Json -InputObject (Join-Path $RunDirectory 'agent-suggestion.png') -Compress
        $code = @'
await page.locator('#ai-ticket-id-input').fill('TICKET_ID');
const received = page.waitForResponse(response => response.url().endsWith('/ai-suggestion'));
await page.locator('#ai-query-form button').click();
const response = await received;
if (response.status() !== 200) throw new Error('AGENT_READ_FAILED');
const body = await response.json();
await page.waitForFunction(() => document.querySelector('#ai-ui-status').textContent === 'AI 제안 생성 완료·담당자 검토 대기');
const signature = await page.evaluate(body => {
    if (document.querySelector('#ai-summary').textContent !== body.suggestion.summary
        || document.querySelector('#ai-categories').textContent !== body.suggestion.categories.join(', ')
        || document.querySelector('#ai-priority').textContent !== body.suggestion.priority
        || document.querySelector('#ai-job-status').textContent !== 'SUCCEEDED'
        || document.querySelector('#ai-review-status').textContent !== 'PENDING_REVIEW — 담당자 검토 대기') {
        throw new Error('UI_RESPONSE_MISMATCH');
    }
    return JSON.stringify([body.ticketId, body.job.id, body.suggestion.id, body.suggestion.summary,
        [...body.suggestion.categories].sort(), body.suggestion.priority, body.suggestion.reviewStatus]);
}, body);
const digest = await page.evaluate(async signature => {
    const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(signature));
    return [...new Uint8Array(hash)].map(value => value.toString(16).padStart(2, '0')).join('');
}, signature);
await page.screenshot({path: SCREENSHOT_PATH, fullPage: true});
return {agentQueryStatus: response.status(), uiResponseDigest: digest};
'@
        $stage = 'AGENT_READ'
        $raw = Run-Code ($code.Replace('TICKET_ID', [string]$TicketId).Replace('SCREENSHOT_PATH', $screenshotJson))
        if ($raw -notmatch '"uiResponseDigest"\s*:\s*"([a-f0-9]{64})"') { throw 'BROWSER_READ_EVIDENCE_MISSING' }
        $safe = [ordered]@{agentQueryStatus = 200; uiResponseDigest = $Matches[1]}
    }
    Write-Output ('HELPDESK_BROWSER_EVIDENCE ' + ($safe | ConvertTo-Json -Compress))
} catch {
    Write-Output "HELPDESK_BROWSER_STEP_FAILED $stage $failureKind"
    exit 1
} finally {
    if ($opened) { try { [void](Invoke-Cli @('close')) } catch { } }
    Pop-Location
}
