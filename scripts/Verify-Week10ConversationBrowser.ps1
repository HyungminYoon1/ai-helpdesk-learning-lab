#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateRange(1, 65535)][int]$Port,
    [Parameter(Mandatory)][string]$RunDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
if ((Test-Path Env:OPENAI_API_KEY) -or (Test-Path Env:HELPDESK_OPENAI_API_KEY)) { throw 'BROWSER_AI_KEY_NOT_ALLOWED' }
$base = "http://127.0.0.1:$Port"
$session = "week10-$([Guid]::NewGuid().ToString('N'))"
$stage = 'OPEN'
$opened = $false

function Invoke-Cli([string[]]$Arguments) {
    $raw = & npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session --raw @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -or ($raw -join "`n") -match '### Error') { throw 'BROWSER_STEP_FAILED' }
    return ($raw -join "`n")
}
function Run-Code([string]$Code) {
    return Invoke-Cli @('run-code', "async (page) => { $($Code -replace '\r?\n', ' ') }")
}
function Login([string]$Role) {
    [void](Invoke-Cli @('goto', "$base/login"))
    [void](Invoke-Cli @('snapshot'))
    $name = [System.Environment]::GetEnvironmentVariable("HELPDESK_BROWSER_${Role}_NAME", 'Process')
    $secret = [System.Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_SECRET', 'Process')
    if (-not $name -or -not $secret) { throw 'FIXTURE_REQUIRED' }
    $nameJson = ConvertTo-Json -InputObject $name -Compress
    $secretJson = ConvertTo-Json -InputObject $secret -Compress
    [void](Run-Code "await page.locator('input[name=username]').fill($nameJson); await page.locator('input[name=password]').fill($secretJson); await page.locator('button[type=submit]').click(); if (await page.evaluate(async () => (await fetch('/api/csrf')).status) !== 200) throw new Error('LOGIN_FAILED'); return true;")
}
function Logout {
    [void](Run-Code "await page.evaluate(async () => { const csrf = await (await fetch('/api/csrf')).json(); await fetch('/logout', {method:'POST',headers:{[csrf.headerName]:csrf.token}}); }); return true;")
}
function Open-Conversation([long]$TicketId, [int]$ExpectedStatus) {
    [void](Invoke-Cli @('goto', "$base/conversation.html"))
    [void](Invoke-Cli @('snapshot'))
    [void](Run-Code "await page.locator('#conversation-ticket-id').fill('$TicketId'); const received = page.waitForResponse(r => r.url().includes('/api/tickets/$TicketId/messages')); await page.locator('#conversation-read button').click(); if ((await received).status() !== $ExpectedStatus) throw new Error('QUERY_FAILED'); return true;")
}
function Open-Assignment([long]$TicketId) {
    [void](Invoke-Cli @('goto', "$base/assignments.html"))
    [void](Invoke-Cli @('snapshot'))
    [void](Run-Code "await page.locator('#assignment-ticket-id').fill('$TicketId'); const received=page.waitForResponse(r=>r.url().endsWith('/api/tickets/$TicketId/assignment')); await page.locator('#assignment-read button').click(); if ((await received).status() !== 200) throw new Error('ASSIGNMENT_READ_FAILED'); return true;")
}
function Transfer([long]$TicketId,[long]$To,[long]$Version) {
    Open-Assignment $TicketId
    [void](Run-Code "await page.waitForFunction((id)=>[...document.querySelector('#transfer-agent').options].some(o=>o.value===id),'$To'); await page.locator('#transfer-agent').selectOption('$To'); await page.locator('#transfer-reason').fill('<img src=x onerror=window.__assignment_xss=true> Synthetic handoff.'); const received=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith('/assignment/transfers')); await page.locator('#transfer-send').click(); const response=await received; if (response.status()!==201 || (await response.json()).assignment.version!==$Version) throw new Error('TRANSFER_FAILED'); await page.waitForFunction(()=>document.querySelectorAll('#assignment-events li').length===$Version); if (await page.locator('#assignment-events img').count()!==0) throw new Error('ASSIGNMENT_RENDER_FAILED'); return true;")
}
function Publish-Extra([long]$TicketId,[string]$Body,[int]$Count) {
    Open-Conversation $TicketId 200
    [void](Run-Code "await page.waitForFunction(()=>!document.querySelector('#official-reply').hidden); await page.locator('#reply-body').fill('$Body'); const received=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith('/replies')); await page.locator('#reply-send').click(); if ((await received).status()!==201) throw new Error('EXTRA_REPLY_FAILED'); await page.waitForFunction(()=>document.querySelectorAll('#conversation-messages li').length===$Count); return true;")
}

Push-Location -LiteralPath $RunDirectory
try {
    [void](Invoke-Cli @('open', "$base/conversation.html"))
    $opened = $true
    [void](Invoke-Cli @('snapshot'))
    $stage = 'ANONYMOUS'
    [void](Run-Code "if (await page.evaluate(async () => (await fetch('/api/tickets/1/messages')).status) !== 401) throw new Error('ANONYMOUS_FAILED'); return true;")
    $stage = 'USER_RECEIPT'
    Login 'A'
    [void](Invoke-Cli @('goto', "$base/tickets.html"))
    [void](Invoke-Cli @('snapshot'))
    $created = Run-Code @'
await page.locator('#ticket-title-input').fill('Conversation Browser synthetic');
await page.locator('#ticket-body-input').fill('Original inquiry preserved.');
const received = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/tickets'));
await page.locator('#create-button').click();
const response = await received;
if (response.status() !== 201) throw new Error('RECEIPT_FAILED');
return {ticketId: (await response.json()).id};
'@
    $ticketId = [long]($created | ConvertFrom-Json).ticketId
    Open-Conversation $ticketId 200
    [void](Run-Code "await page.waitForFunction(() => document.querySelectorAll('#conversation-messages li').length === 1); if (!(await page.locator('#official-reply').isHidden())) throw new Error('ROLE_FAILED'); return true;")
    Logout
    $stage = 'AGENT_REPLY'
    Login 'AGENT'
    Open-Assignment $ticketId
    [void](Run-Code "await page.locator('#assignment-claim').waitFor({state:'visible'}); const received=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith('/assignment/claim')); await page.locator('#assignment-claim').click(); if ((await received).status()!==201) throw new Error('CLAIM_FAILED'); return true;")
    Open-Conversation $ticketId 200
    $replyCode = @'
await page.waitForFunction(() => !document.querySelector('#official-reply').hidden);
const missing = await page.evaluate(async () => (await fetch('/api/tickets/TICKET_ID/replies', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({body:'not stored',clientRequestId:crypto.randomUUID()})})).status);
if (missing !== 403) throw new Error('CSRF_FAILED');
await page.locator('#reply-body').fill('<img src=x onerror=window.__conversation_xss=true>fixed reply');
const received = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/replies'));
await page.locator('#reply-send').click();
const response = await received;
if (response.status() !== 201) throw new Error('REPLY_FAILED');
const headers = await response.request().allHeaders();
await page.waitForFunction(() => document.querySelectorAll('#conversation-messages li').length === 2 && !document.querySelector('#reply-send').disabled);
if (await page.locator('#conversation-messages img').count() !== 0 || await page.evaluate(() => window.__conversation_xss === true)) throw new Error('RENDER_FAILED');
return {replyId:(await response.json()).id, sessionCookieObserved:(headers.cookie ?? '').includes('JSESSIONID='), csrfHeaderObserved:Object.keys(headers).includes('x-csrf-token')};
'@
    $reply = Run-Code $replyCode.Replace('TICKET_ID', [string]$ticketId)
    $replyEvidence = $reply | ConvertFrom-Json
    if (-not $replyEvidence.sessionCookieObserved -or -not $replyEvidence.csrfHeaderObserved) { throw 'HEADER_EVIDENCE_MISSING' }
    $firstReplyId = [long]$replyEvidence.replyId
    $stage = 'CORRECTION'
    [void](Run-Code "await page.locator('#reply-correction').selectOption('$firstReplyId'); await page.locator('#reply-body').fill('Corrected guidance.'); const received = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/replies')); await page.locator('#reply-send').click(); const response = await received; if (response.status() !== 201 || (await response.json()).correctsMessageId !== $firstReplyId) throw new Error('CORRECTION_FAILED'); await page.waitForFunction(() => document.querySelectorAll('#conversation-messages li').length === 3 && !document.querySelector('#reply-send').disabled); return true;")
    $screenshotJson = ConvertTo-Json -InputObject (Join-Path $RunDirectory 'conversation-agent.png') -Compress
    [void](Run-Code "await page.screenshot({path:$screenshotJson,fullPage:true}); return true;")
    $stage = 'HANDOFF_TO_AGENT_B'
    $agentBId = [long][System.Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_AGENT_B_ID', 'Process')
    $supervisorId = [long][System.Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_SUPERVISOR_ID', 'Process')
    Transfer $ticketId $agentBId 2
    Open-Conversation $ticketId 200
    [void](Run-Code "await page.waitForFunction(()=>document.querySelector('#official-reply').hidden); return true;")
    Logout
    Login 'AGENT_B'
    Publish-Extra $ticketId 'Agent B guidance.' 4
    Logout
    $stage = 'SUPERVISOR_EXPLICIT_TAKEOVER'
    Login 'SUPERVISOR'
    Open-Conversation $ticketId 200
    [void](Run-Code "await page.waitForFunction(()=>document.querySelector('#official-reply').hidden); return true;")
    Transfer $ticketId $supervisorId 3
    $assignmentScreenshot = ConvertTo-Json -InputObject (Join-Path $RunDirectory 'assignment-history.png') -Compress
    [void](Run-Code "await page.screenshot({path:$assignmentScreenshot,fullPage:true}); return true;")
    Publish-Extra $ticketId 'Supervisor guidance after takeover.' 5
    Logout
    $stage = 'OTHER_USER_DENIED'
    Login 'B'
    Open-Conversation $ticketId 404
    [void](Run-Code "await page.waitForFunction(() => document.querySelectorAll('#conversation-messages li').length === 0); if (!(await page.locator('#official-reply').isHidden())) throw new Error('OWNERSHIP_FAILED'); return true;")
    Logout
    $stage = 'OWNER_RELOGIN'
    Login 'A'
    Open-Conversation $ticketId 200
    [void](Run-Code "await page.waitForFunction(() => document.querySelectorAll('#conversation-messages li').length === 5); if (!(await page.locator('#official-reply').isHidden())) throw new Error('ROLE_FAILED'); if (await page.locator('#conversation-messages img').count() !== 0) throw new Error('RENDER_FAILED'); return true;")
    [ordered]@{evidence='LOCAL_CONVERSATION_BROWSER_POSTGRES';completed=$true;ticketId=$ticketId;replyId=$firstReplyId;
        originalPreserved=$true;ownerIsolation=$true;correctionVisible=$true;textOnlyRendering=$true;
        sessionCookieObserved=$true;csrfHeaderObserved=$true;reloginVerified=$true} | ConvertTo-Json -Compress
} catch {
    [ordered]@{evidence='LOCAL_CONVERSATION_BROWSER_POSTGRES';completed=$false;stage=$stage;failureCode='BROWSER_STEP_FAILED'} | ConvertTo-Json -Compress
    exit 1
} finally {
    if ($opened) { try { [void](Invoke-Cli @('close')) } catch { } }
    Pop-Location
}
