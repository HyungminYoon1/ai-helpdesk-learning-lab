#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][long]$TicketId,
    [Parameter(Mandatory)][long]$InquiryId,
    [Parameter(Mandatory)][long]$FollowupId,
    [Parameter(Mandatory)][string]$RunDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
if ((Test-Path Env:OPENAI_API_KEY) -or (Test-Path Env:HELPDESK_OPENAI_API_KEY)) { throw 'BROWSER_AI_KEY_NOT_ALLOWED' }
if ($TicketId -le 0 -or $InquiryId -le 0 -or $FollowupId -le $InquiryId) { throw 'SYNTHETIC_IDS_REQUIRED' }
$base = "http://127.0.0.1:$Port"
$session = "week12-recovery-$([Guid]::NewGuid().ToString('N'))"
$stage = 'OPEN'
$opened = $false
function Invoke-Cli([string[]]$Arguments) {
    $raw = & npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session --raw @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -or ($raw -join "`n") -match '### Error') { throw 'BROWSER_STEP_FAILED' }
    return ($raw -join "`n")
}
function Run-Code([string]$Code) { return Invoke-Cli @('run-code', "async (page) => { $($Code -replace '\r?\n', ' ') }") }
Push-Location -LiteralPath $RunDirectory
try {
    [void](Invoke-Cli @('open', "$base/login")); $opened = $true
    [void](Invoke-Cli @('snapshot'))
    $stage = 'LOGIN'
    $name = [Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_AGENT_NAME','Process')
    $secret = [Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_SECRET','Process')
    if (-not $name -or -not $secret) { throw 'FIXTURE_REQUIRED' }
    $nameJson = ConvertTo-Json -InputObject $name -Compress
    $secretJson = ConvertTo-Json -InputObject $secret -Compress
    [void](Run-Code "await page.locator('input[name=username]').fill($nameJson); await page.locator('input[name=password]').fill($secretJson); await page.locator('button[type=submit]').click(); return true;")
    [void](Invoke-Cli @('goto', "$base/conversation.html?ticketId=$TicketId"))
    [void](Invoke-Cli @('snapshot'))
    $stage = 'RESPONSE_LOSS_AND_READ_RECOVERY'
    $code = @'
await page.waitForFunction(()=>!document.querySelector('#confirmation-target').disabled);
const posts=[],statuses=[];
let lost=0,failedReads=0,dropRead=false,csrfObserved=true;
const postPath='/api/tickets/TICKET_ID/customer-message-confirmations';
await page.route('**'+postPath,async route=>{
    if(route.request().method()!=='POST')throw new Error('UNEXPECTED_METHOD');
    posts.push(route.request().postDataJSON());
    const headers=await route.request().allHeaders();
    csrfObserved=csrfObserved && typeof headers['x-csrf-token']==='string' && headers['x-csrf-token'].length>0;
    const upstream=await route.fetch();
    statuses.push(upstream.status());
    if(posts.length===1){if(upstream.status()!==201)throw new Error('COMMITTED_RESPONSE_REQUIRED');lost++;await route.abort('failed');return;}
    if(posts.length===3)dropRead=true;
    await route.fulfill({response:upstream});
});
await page.route('**'+postPath+'/current',async route=>{
    if(dropRead){dropRead=false;failedReads++;await route.abort('failed');return;}
    await route.continue();
});
await page.locator('#confirmation-target').selectOption('INQUIRY_ID');
await page.locator('#confirmation-send').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-status').textContent.includes('저장 여부 미확인') && !document.querySelector('#confirmation-send').disabled);
if(lost!==1 || posts.length!==1 || await page.locator('#confirmation-target').isEnabled())throw new Error('UNKNOWN_SUBMISSION_NOT_RETAINED');
await page.screenshot({path:UNKNOWN_SCREENSHOT,fullPage:true});
await page.locator('#confirmation-refresh').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-current').textContent.includes('#INQUIRY_ID까지 확인') && !document.querySelector('#confirmation-refresh').disabled);
if(await page.locator('#confirmation-send').textContent()!=='같은 제출 재확인')throw new Error('REFRESH_DROPPED_PENDING_REQUEST');
await page.locator('#confirmation-send').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-result').textContent.includes('#INQUIRY_ID까지') && !document.querySelector('#confirmation-refresh').disabled);
if(posts.length!==2 || statuses[1]!==200 || JSON.stringify(posts[0])!==JSON.stringify(posts[1]))throw new Error('REPLAY_NOT_IDENTICAL');
if(!(await page.locator('#confirmation-current').innerText()).includes('확인 필요'))throw new Error('LATER_MESSAGE_AUTO_CONFIRMED');
await page.locator('#confirmation-target').selectOption('FOLLOWUP_ID');
await page.locator('#confirmation-send').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-status').textContent.includes('확인 저장은 완료') && !document.querySelector('#confirmation-refresh').disabled);
if(posts.length!==3 || failedReads!==1 || statuses[2]!==201 || !(await page.locator('#confirmation-send').isDisabled()))throw new Error('SAVED_READ_FAILURE_NOT_SEPARATE');
await page.screenshot({path:SAVED_SCREENSHOT,fullPage:true});
await page.locator('#confirmation-refresh').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-current').textContent.includes('추가 확인할 글 없음') && !document.querySelector('#confirmation-refresh').disabled);
if(posts.length!==3 || posts[2].clientRequestId===posts[0].clientRequestId || posts[2].expectedAssignmentVersion!==1 || posts[2].throughMessageId!==FOLLOWUP_ID)throw new Error('READ_RETRIED_A_WRITE');
await page.screenshot({path:RECOVERED_SCREENSHOT,fullPage:true});
return {confirmationPosts:posts.length,lostCommittedResponses:lost,failedCurrentReads:failedReads,upstreamStatuses:statuses,
    sameSubmission:JSON.stringify(posts[0])===JSON.stringify(posts[1]),newSubmissionDistinct:posts[2].clientRequestId!==posts[0].clientRequestId,
    postSaveReadOnlyRetry:posts.length===3,csrfHeaderObserved:csrfObserved,sessionCookieObserved:(await page.context().cookies()).some(cookie=>cookie.name==='JSESSIONID' && cookie.httpOnly)};
'@
    $code = $code.Replace('TICKET_ID',[string]$TicketId).Replace('INQUIRY_ID',[string]$InquiryId).Replace('FOLLOWUP_ID',[string]$FollowupId)
    $code = $code.Replace('UNKNOWN_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'unknown-confirmation.png') -Compress))
    $code = $code.Replace('SAVED_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'saved-refresh-failed.png') -Compress))
    $code = $code.Replace('RECOVERED_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'recovered-confirmation.png') -Compress))
    $result = Run-Code $code | ConvertFrom-Json
    [ordered]@{evidence='LOCAL_CONFIRMATION_RECOVERY_BROWSER_POSTGRES';completed=$true;
        confirmationPosts=$result.confirmationPosts;lostCommittedResponses=$result.lostCommittedResponses;failedCurrentReads=$result.failedCurrentReads;
        upstreamStatuses=$result.upstreamStatuses;sameSubmission=$result.sameSubmission;newSubmissionDistinct=$result.newSubmissionDistinct;
        postSaveReadOnlyRetry=$result.postSaveReadOnlyRetry;sessionCookieObserved=$result.sessionCookieObserved;csrfHeaderObserved=$result.csrfHeaderObserved} | ConvertTo-Json -Compress
} catch {
    [ordered]@{evidence='LOCAL_CONFIRMATION_RECOVERY_BROWSER_POSTGRES';completed=$false;stage=$stage;failureCode='BROWSER_STEP_FAILED'} | ConvertTo-Json -Compress
    exit 1
} finally {
    if ($opened) { try { [void](Invoke-Cli @('close')) } catch { } }
    Pop-Location
}
