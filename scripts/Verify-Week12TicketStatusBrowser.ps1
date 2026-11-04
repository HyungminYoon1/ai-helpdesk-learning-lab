#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][long]$TicketId,
    [Parameter(Mandatory)][long]$InquiryId,
    [Parameter(Mandatory)][long]$ReplyId,
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$CredentialPort,
    [Parameter(Mandatory)][string]$RunDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
if((Test-Path Env:OPENAI_API_KEY) -or (Test-Path Env:HELPDESK_OPENAI_API_KEY)){throw 'BROWSER_AI_KEY_NOT_ALLOWED'}
if($TicketId -le 0 -or $InquiryId -le 0 -or $ReplyId -le 0){throw 'SYNTHETIC_IDS_REQUIRED'}
$base="http://127.0.0.1:$Port"
$session="week12-status-$([Guid]::NewGuid().ToString('N'))"
$stage='LOGIN';$failureCode='BROWSER_STEP_FAILED';$opened=$false
function Invoke-Cli([string[]]$Arguments){
    $raw=& npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session --raw @Arguments 2>&1
    if($LASTEXITCODE -ne 0 -or ($raw -join "`n") -match '### Error'){
        $joined=$raw -join "`n"
        $script:failureCode=if($joined -match 'process is not defined'){'CALLBACK_ENV_UNAVAILABLE'}elseif($joined -match 'FIXTURE_REQUIRED'){'FIXTURE_REQUIRED'}elseif($joined -match 'TimeoutError|Timeout [0-9]+ms'){'BROWSER_TIMEOUT'}elseif($joined -match 'Error: ([A-Z][A-Z0-9_]{4,60})'){$Matches[1]}else{'BROWSER_STEP_FAILED'}
        throw 'BROWSER_STEP_FAILED'
    }
    return ($raw -join "`n")
}
function Run-Code([string]$Code){return Invoke-Cli @('run-code',"async (page) => { $($Code -replace '\r?\n',' ') }")}
function Wait-Gate([string]$Name){
    $deadline=[DateTime]::UtcNow.AddSeconds(90)
    while(-not (Test-Path -LiteralPath (Join-Path $RunDirectory $Name))){if([DateTime]::UtcNow -ge $deadline){throw 'FIXTURE_GATE_TIMEOUT'};Start-Sleep -Milliseconds 100}
}
function Signal([string]$Name){[IO.File]::WriteAllText((Join-Path $RunDirectory $Name),"READY`n",[Text.UTF8Encoding]::new($false))}
function Expand-Code([string]$Code){
    return $Code.Replace('TICKET_ID',[string]$TicketId).Replace('INQUIRY_ID',[string]$InquiryId).Replace('REPLY_ID',[string]$ReplyId)
}
Push-Location -LiteralPath $RunDirectory
try{
    [void](Invoke-Cli @('open',"$base/login"));$opened=$true
    [void](Invoke-Cli @('snapshot'))
    $stage='LOGIN_CALLBACK'
    # A one-use loopback fixture supplies synthetic values in memory, without code interpolation or logging.
    [void](Run-Code "const response=await page.request.post('http://127.0.0.1:$CredentialPort/agent'); if(!response.ok())throw new Error('FIXTURE_REQUIRED'); const login=await response.json(); await page.locator('input[name=username]').fill(login.username); await page.locator('input[name=password]').fill(login.password); await page.locator('button[type=submit]').click(); return true;")
    [void](Invoke-Cli @('goto',"$base/conversation.html?ticketId=$TicketId"))
    [void](Invoke-Cli @('snapshot'))
    $stage='START_AND_GET_ONLY_RECOVERY'
    $first=@'
const path='/api/tickets/TICKET_ID/status-changes',read='/api/tickets/TICKET_ID/status';
const posts=[],statuses=[];let csrf=true,failRead=false,failedRead=false;
await page.evaluate(()=>{window.__ticketStatusEvidence={posts:[],statuses:[],csrf:true};});
await page.route('**'+read,async route=>{if(failRead){failRead=false;failedRead=true;await route.fulfill({status:503,contentType:'application/json',body:'{}'});}else await route.continue();});
await page.route('**'+path,async route=>{
    const command=route.request().postDataJSON();posts.push(command);
    const headers=await route.request().allHeaders();csrf=csrf && typeof headers['x-csrf-token']==='string' && headers['x-csrf-token'].length>0;
    const upstream=await route.fetch();statuses.push(upstream.status());
    if(posts.length===1)failRead=true;
    const lost=posts.length===3 ? await upstream.json() : null;
    await page.evaluate(value=>{window.__ticketStatusEvidence=value;},{posts,statuses,csrf,lost});
    if(posts.length===3){if(upstream.status()!==201)throw new Error('COMMIT_REQUIRED');await route.abort('failed');}else await route.fulfill({response:upstream});
});
await page.locator('#status-panel').waitFor({state:'visible'});
await page.locator('#status-action').selectOption('START');await page.locator('#status-reason').fill('Synthetic start.');await page.locator('#status-send').click();
await page.waitForFunction(()=>document.querySelector('#status-saved').textContent.includes('IN_PROGRESS') && !document.querySelector('#status-refresh').disabled);
if(!failedRead || posts.length!==1)throw new Error('GET_FAILURE_REQUIRED');
await page.locator('#status-refresh').click();
await page.waitForFunction(()=>document.querySelector('#status-current').textContent.includes('IN_PROGRESS') && !document.querySelector('#status-refresh').disabled);
if(posts.length!==1)throw new Error('GET_RECOVERY_POSTED');
await page.locator('#status-action').selectOption('RESOLVE');await page.locator('#status-reason').fill('Synthetic resolution.');
await page.locator('#status-reply').selectOption('REPLY_ID');await page.locator('#status-through').selectOption('INQUIRY_ID');
return {getOnlyRecovery:true};
'@
    $firstResult=Run-Code (Expand-Code $first) | ConvertFrom-Json
    Signal 'draft-selected';Wait-Gate 'new-message-committed'
    $late=[long][IO.File]::ReadAllText((Join-Path $RunDirectory 'new-message-committed'),[Text.Encoding]::UTF8)
    if($late -le $InquiryId){throw 'LATE_MESSAGE_REQUIRED'}
    $stage='REJECT_AND_LOSE_RESOLUTION_RESPONSE'
    $second=@'
const response=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith('/status-changes'));
await page.locator('#status-send').click();const rejected=await response;
if(rejected.status()!==409 || (await rejected.json()).code!=='NEW_CUSTOMER_MESSAGE')throw new Error('STALE_RANGE_NOT_REJECTED');
await page.locator('#status-reset').waitFor({state:'visible'});await page.waitForFunction(()=>!document.querySelector('#status-reset').disabled);
await page.screenshot({path:'stale-resolution-rejected.png',fullPage:true});
await page.locator('#status-reset').click();await page.waitForFunction(()=>!document.querySelector('#status-refresh').disabled);
if(await page.locator('#status-reason').inputValue()!=='' || await page.locator('#status-action').inputValue()!=='' || await page.locator('#status-through').inputValue()!=='' || (await page.evaluate(()=>window.__ticketStatusEvidence.posts.length))!==2)throw new Error('RESET_NOT_GET_ONLY');
await page.locator('#conversation-read button').click();await page.waitForFunction(()=>!document.querySelector('#status-refresh').disabled && document.querySelector('#status-panel').hidden===false);
await page.locator('#status-action').selectOption('RESOLVE');await page.locator('#status-reason').fill('Synthetic reviewed resolution.');
await page.locator('#status-reply').selectOption('REPLY_ID');await page.locator('#status-through').selectOption('LATE_ID');await page.locator('#status-send').click();
await page.waitForFunction(()=>document.querySelector('#status-reason').readOnly && !document.querySelector('#status-send').disabled);
const evidence=await page.evaluate(()=>window.__ticketStatusEvidence);
if(evidence.posts.length!==3 || evidence.statuses[2]!==201 || evidence.posts[1].clientRequestId===evidence.posts[2].clientRequestId)throw new Error('UNKNOWN_RESOLUTION_REQUIRED');
await page.screenshot({path:'unknown-resolution.png',fullPage:true});
return {staleRangeRejected:true,explicitReset:true};
'@
    $secondResult=Run-Code ((Expand-Code $second).Replace('LATE_ID',[string]$late)) | ConvertFrom-Json
    Signal 'unknown-resolve-ready';Wait-Gate 'state-changed'
    $stage='HISTORICAL_REPLAY_AND_NEW_START'
    $third=@'
await page.locator('#status-refresh').click();
await page.waitForFunction(()=>document.querySelector('#status-current').textContent.includes('OPEN') && document.querySelector('#status-current').textContent.includes('v3') && !document.querySelector('#status-refresh').disabled);
if(await page.locator('#status-reason').inputValue()!=='Synthetic reviewed resolution.' || await page.locator('#status-through').inputValue()!=='LATE_ID' || await page.locator('#status-reason').getAttribute('readonly')===null)throw new Error('PENDING_COMMAND_CHANGED');
const response=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith('/status-changes'));
await page.locator('#status-send').click();const replay=await response;const replayData=await replay.json();
const evidence=await page.evaluate(()=>window.__ticketStatusEvidence);
if(replay.status()!==200 || JSON.stringify(evidence.posts[2])!==JSON.stringify(evidence.posts[3]) || replayData.change.statusVersion!==2 || replayData.change.assignmentVersion!==1 || replayData.current.status!=='OPEN' || replayData.current.statusVersion!==3 || replayData.current.assignmentVersion!==3)throw new Error('ORIGINAL_EVENT_NOT_REPLAYED');
await page.waitForFunction(()=>document.querySelector('#status-saved').textContent.includes('RESOLVED') && document.querySelector('#status-saved').textContent.includes('v2') && document.querySelector('#status-current').textContent.includes('OPEN') && !document.querySelector('#status-refresh').disabled);
if((await page.locator('#conversation-heading').textContent()).match(/\b(OPEN|IN_PROGRESS|RESOLVED)\b/))throw new Error('STALE_HEADING_STATUS');
await page.screenshot({path:'history-and-current.png',fullPage:true});
await page.locator('#status-action').selectOption('START');await page.locator('#status-reason').fill('Synthetic second start.');await page.locator('#status-send').click();
await page.waitForFunction(()=>document.querySelector('#status-current').textContent.includes('IN_PROGRESS') && document.querySelector('#status-current').textContent.includes('v4') && !document.querySelector('#status-refresh').disabled);
const final=await page.evaluate(()=>window.__ticketStatusEvidence);await page.unroute('**/api/tickets/TICKET_ID/status-changes');await page.unroute('**/api/tickets/TICKET_ID/status');
return {upstreamStatuses:final.statuses,postCount:final.posts.length,csrfHeaderObserved:final.csrf,sameSubmissionReplayed:true,historySeparateFromCurrent:true};
'@
    $thirdResult=Run-Code ((Expand-Code $third).Replace('LATE_ID',[string]$late)) | ConvertFrom-Json
    Signal 'staff-replayed-ready';Wait-Gate 'second-resolution-ready'
    $stage='OWNER_REOPEN'
    [void](Run-Code "await page.context().clearCookies(); await page.goto('$base/login'); return true;")
    [void](Invoke-Cli @('snapshot'))
    [void](Run-Code "const response=await page.request.post('http://127.0.0.1:$CredentialPort/customer'); if(!response.ok())throw new Error('FIXTURE_REQUIRED'); const login=await response.json(); await page.locator('input[name=username]').fill(login.username); await page.locator('input[name=password]').fill(login.password); await page.locator('button[type=submit]').click(); return true;")
    [void](Invoke-Cli @('goto',"$base/conversation.html?ticketId=$TicketId&statusView=customer"))
    [void](Invoke-Cli @('snapshot'))
    $fourth=@'
await page.locator('#status-panel').waitFor({state:'visible'});await page.waitForFunction(()=>document.querySelector('#status-current').textContent.includes('RESOLVED') && !document.querySelector('#status-refresh').disabled);
const path='/api/tickets/TICKET_ID/customer-status/reopen';let command,headers;
await page.route('**'+path,async route=>{command=route.request().postDataJSON();headers=await route.request().allHeaders();await route.continue();});
if(!(await page.locator('#status-action').isHidden()))throw new Error('OWNER_STAFF_FORM_EXPOSED');
await page.locator('#status-reason').fill('Synthetic customer reopen.');const response=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith(path));
await page.locator('#status-send').click();const received=await response;const data=await received.json();
if(received.status()!==201 || Object.keys(command).sort().join(',')!=='clientRequestId,expectedStatusVersion,reason' || Object.keys(data.current).sort().join(',')!=='canReopen,status,statusVersion' || Object.keys(data.change).sort().join(',')!=='fromStatus,id,reason,recordedAt,statusVersion,toStatus')throw new Error('OWNER_PROJECTION_CHANGED');
await page.waitForFunction(()=>document.querySelector('#status-current').textContent.includes('OPEN') && document.querySelector('#status-current').textContent.includes('v6') && !document.querySelector('#status-refresh').disabled);
await page.screenshot({path:'customer-reopened.png',fullPage:true});
return {status:received.status(),ownerProjection:true,csrfHeaderObserved:typeof headers['x-csrf-token']==='string' && headers['x-csrf-token'].length>0};
'@
    $fourthResult=Run-Code (Expand-Code $fourth) | ConvertFrom-Json
    [ordered]@{evidence='LOCAL_TICKET_STATUS_BROWSER_POSTGRES';completed=$true;postCount=$thirdResult.postCount+1;
        upstreamStatuses=@($thirdResult.upstreamStatuses)+@($fourthResult.status);getOnlyRecovery=$firstResult.getOnlyRecovery;
        staleRangeRejected=$secondResult.staleRangeRejected;explicitReset=$secondResult.explicitReset;
        sameSubmissionReplayed=$thirdResult.sameSubmissionReplayed;historySeparateFromCurrent=$thirdResult.historySeparateFromCurrent;
        ownerProjection=$fourthResult.ownerProjection;csrfHeaderObserved=$thirdResult.csrfHeaderObserved -and $fourthResult.csrfHeaderObserved} | ConvertTo-Json -Compress
}catch{
    [ordered]@{evidence='LOCAL_TICKET_STATUS_BROWSER_POSTGRES';completed=$false;stage=$stage;failureCode=$failureCode} | ConvertTo-Json -Compress
    exit 1
}finally{
    if($opened){try{[void](Invoke-Cli @('close'))}catch{}}
    Pop-Location
}
