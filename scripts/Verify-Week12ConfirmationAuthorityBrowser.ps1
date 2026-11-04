#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][long]$TicketId,
    [Parameter(Mandatory)][long]$InquiryId,
    [Parameter(Mandatory)][long]$ToAgentId,
    [Parameter(Mandatory)][string]$RunDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
if ((Test-Path Env:OPENAI_API_KEY) -or (Test-Path Env:HELPDESK_OPENAI_API_KEY)) { throw 'BROWSER_AI_KEY_NOT_ALLOWED' }
if ($TicketId -le 0 -or $InquiryId -le 0 -or $ToAgentId -le 0) { throw 'SYNTHETIC_IDS_REQUIRED' }
$base="http://127.0.0.1:$Port"
$session="week12-authority-$([Guid]::NewGuid().ToString('N'))"
$stage='OPEN'
$opened=$false
function Invoke-Cli([string[]]$Arguments){
    $raw=& npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session --raw @Arguments 2>&1
    if($LASTEXITCODE -ne 0 -or ($raw -join "`n") -match '### Error'){throw 'BROWSER_STEP_FAILED'}
    return ($raw -join "`n")
}
function Run-Code([string]$Code){return Invoke-Cli @('run-code',"async (page) => { $($Code -replace '\r?\n',' ') }")}
Push-Location -LiteralPath $RunDirectory
try{
    [void](Invoke-Cli @('open',"$base/login"));$opened=$true
    [void](Invoke-Cli @('snapshot'))
    $stage='LOGIN'
    $name=[Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_AGENT_NAME','Process')
    $secret=[Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_SECRET','Process')
    if(-not $name -or -not $secret){throw 'FIXTURE_REQUIRED'}
    $nameJson=ConvertTo-Json -InputObject $name -Compress
    $secretJson=ConvertTo-Json -InputObject $secret -Compress
    [void](Run-Code "await page.locator('input[name=username]').fill($nameJson); await page.locator('input[name=password]').fill($secretJson); await page.locator('button[type=submit]').click(); return true;")
    [void](Invoke-Cli @('goto',"$base/conversation.html?ticketId=$TicketId"))
    [void](Invoke-Cli @('snapshot'))
    $stage='HANDOFF_AND_REPLAY'
    $phaseOneCode=@'
await page.waitForFunction(()=>!document.querySelector('#confirmation-target').disabled);
const posts=[],statuses=[];
let csrfObserved=true;
const path='/api/tickets/TICKET_ID/customer-message-confirmations';
await page.route('**'+path,async route=>{
    posts.push(route.request().postDataJSON());
    const headers=await route.request().allHeaders();csrfObserved=csrfObserved && typeof headers['x-csrf-token']==='string' && headers['x-csrf-token'].length>0;
    const upstream=await route.fetch();statuses.push(upstream.status());
    if(posts.length===1){if(upstream.status()!==201)throw new Error('FIRST_COMMIT_REQUIRED');await route.abort('failed');return;}
    await route.fulfill({response:upstream});
});
await page.locator('#confirmation-target').selectOption('INQUIRY_ID');
await page.locator('#confirmation-send').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-status').textContent.includes('저장 여부 미확인') && !document.querySelector('#confirmation-send').disabled);
const transfer=await page.evaluate(async()=>{
    const csrf=await(await fetch('/api/csrf')).json();
    const r=await fetch('/api/tickets/TICKET_ID/assignment/transfers',{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify({clientRequestId:crypto.randomUUID(),expectedAssignmentVersion:1,toAgentId:TO_AGENT_ID,reason:'Synthetic authority handoff.'})});
    return {status:r.status,data:await r.json()};
});
if(transfer.status!==201 || transfer.data.assignment.version!==2)throw new Error('HANDOFF_FAILED');
await page.locator('#confirmation-refresh').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-current').textContent.includes('v2') && !document.querySelector('#confirmation-refresh').disabled);
if(!(await page.locator('#confirmation-current').textContent()).includes('현재 배정의 확인 기록 없음') || await page.locator('#confirmation-send').textContent()!=='같은 제출 재확인')throw new Error('PENDING_SCOPE_CHANGED');
await page.locator('#confirmation-send').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-result').textContent.includes('v1') && !document.querySelector('#confirmation-refresh').disabled);
if(posts.length!==2 || statuses[1]!==200 || JSON.stringify(posts[0])!==JSON.stringify(posts[1]) || !(await page.locator('#confirmation-current').textContent()).includes('현재 배정의 확인 기록 없음'))throw new Error('OLD_RESULT_BECAME_CURRENT_CONFIRMATION');
await page.evaluate(value=>{window.__authoritySubmission=value;},posts[0]);
const newWrite=await page.evaluate(async()=>{const csrf=await(await fetch('/api/csrf')).json();const r=await fetch('/api/tickets/TICKET_ID/customer-message-confirmations',{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify({...window.__authoritySubmission,clientRequestId:crypto.randomUUID()})});return {status:r.status,data:await r.json()};});
if(newWrite.status!==403 || newWrite.data.code!=='NOT_ASSIGNEE')throw new Error('NON_ASSIGNEE_NEW_WRITE_ALLOWED');
await page.screenshot({path:HANDOFF_SCREENSHOT,fullPage:true});
return {sameSubmission:true,handoffStateSeparated:true,newWriteDenied:true,upstreamStatuses:statuses,csrfHeaderObserved:csrfObserved};
'@
    $phaseOneCode=$phaseOneCode.Replace('TICKET_ID',[string]$TicketId).Replace('INQUIRY_ID',[string]$InquiryId).Replace('TO_AGENT_ID',[string]$ToAgentId)
    $phaseOneCode=$phaseOneCode.Replace('HANDOFF_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'historical-confirmation-current-handoff.png') -Compress))
    $phaseOne=Run-Code $phaseOneCode | ConvertFrom-Json
    $stage='WAITING_FOR_COMMITTED_REVOCATION'
    [IO.File]::WriteAllText((Join-Path $RunDirectory 'ready-for-revocation'),"READY`n",[Text.UTF8Encoding]::new($false))
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    while(-not (Test-Path -LiteralPath (Join-Path $RunDirectory 'revocation-committed'))){
        if([DateTime]::UtcNow -ge $deadline){throw 'REVOCATION_GATE_TIMEOUT'}
        Start-Sleep -Milliseconds 100
    }
    $stage='REVOKED_SESSION_REPLAY_AND_READ'
    $phaseTwoCode=@'
const denied=await page.evaluate(async()=>{
    const tokenResponse=await fetch('/api/csrf');const csrf=await tokenResponse.json();
    const r=await fetch('/api/tickets/TICKET_ID/customer-message-confirmations',{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(window.__authoritySubmission)});
    return {csrfStatus:tokenResponse.status,status:r.status,data:await r.json()};
});
if(denied.csrfStatus!==200 || denied.status!==404 || denied.data.code!=='SUPPORT_NOT_FOUND')throw new Error('REVOKED_REPLAY_DISCLOSED_RESULT');
const received=page.waitForResponse(r=>r.url().endsWith('/customer-message-confirmations/current'));
await page.locator('#confirmation-refresh').click();
if((await received).status()!==404)throw new Error('REVOKED_CURRENT_READ_ALLOWED');
await page.waitForFunction(()=>document.querySelector('#confirmation-status').textContent.includes('조회 권한이 없습니다') && !document.querySelector('#confirmation-refresh').disabled);
if(!(await page.locator('#confirmation-send').isDisabled()) || await page.locator('#confirmation-current').textContent()!=='현재 확인 상태 미조회')throw new Error('REVOKED_VIEW_STALE');
await page.screenshot({path:REVOKED_SCREENSHOT,fullPage:true});
return {replayStatus:denied.status,revokedReplayDenied:true,sessionStillAuthenticated:true,revokedReadDenied:true};
'@
    $phaseTwoCode=$phaseTwoCode.Replace('TICKET_ID',[string]$TicketId).Replace('REVOKED_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'revoked-confirmation-read.png') -Compress))
    $phaseTwo=Run-Code $phaseTwoCode | ConvertFrom-Json
    [ordered]@{evidence='LOCAL_CONFIRMATION_AUTHORITY_BROWSER_POSTGRES';completed=$true;
        upstreamStatuses=@($phaseOne.upstreamStatuses)+@($phaseTwo.replayStatus);sameSubmission=$phaseOne.sameSubmission;
        handoffStateSeparated=$phaseOne.handoffStateSeparated;newWriteDenied=$phaseOne.newWriteDenied;csrfHeaderObserved=$phaseOne.csrfHeaderObserved;
        revokedReplayDenied=$phaseTwo.revokedReplayDenied;sessionStillAuthenticated=$phaseTwo.sessionStillAuthenticated;revokedReadDenied=$phaseTwo.revokedReadDenied} | ConvertTo-Json -Compress
}catch{
    [ordered]@{evidence='LOCAL_CONFIRMATION_AUTHORITY_BROWSER_POSTGRES';completed=$false;stage=$stage;failureCode='BROWSER_STEP_FAILED'} | ConvertTo-Json -Compress
    exit 1
}finally{
    if($opened){try{[void](Invoke-Cli @('close'))}catch{}}
    Pop-Location
}
