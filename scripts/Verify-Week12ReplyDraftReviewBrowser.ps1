#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][long]$TicketId,
    [Parameter(Mandatory)][long]$OriginalReplyId,
    [Parameter(Mandatory)][string]$RunDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
if((Test-Path Env:OPENAI_API_KEY) -or (Test-Path Env:HELPDESK_OPENAI_API_KEY)){throw 'BROWSER_AI_KEY_NOT_ALLOWED'}
if($TicketId -le 0 -or $OriginalReplyId -le 0){throw 'SYNTHETIC_IDS_REQUIRED'}
$base="http://127.0.0.1:$Port"
$session="week12-reply-review-$([Guid]::NewGuid().ToString('N'))"
$stage='OPEN'
$failureCode='BROWSER_STEP_FAILED'
$opened=$false
function Invoke-Cli([string[]]$Arguments){
    $raw=& npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session --raw @Arguments 2>&1
    if($LASTEXITCODE -ne 0 -or ($raw -join "`n") -match '### Error'){throw 'BROWSER_STEP_FAILED'}
    return ($raw -join "`n")
}
function Run-Code([string]$Code){return Invoke-Cli @('run-code',"async (page) => { $($Code -replace '\r?\n',' ') }")}
function Wait-Gate([string]$Name){
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    while(-not (Test-Path -LiteralPath (Join-Path $RunDirectory $Name))){
        if([DateTime]::UtcNow -ge $deadline){throw 'ASSIGNMENT_GATE_TIMEOUT'}
        Start-Sleep -Milliseconds 100
    }
}
Push-Location -LiteralPath $RunDirectory
try{
    [void](Invoke-Cli @('open',"$base/login"));$opened=$true
    [void](Invoke-Cli @('snapshot'))
    $name=[Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_AGENT_NAME','Process')
    $secret=[Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_SECRET','Process')
    if(-not $name -or -not $secret){throw 'FIXTURE_REQUIRED'}
    $nameJson=ConvertTo-Json -InputObject $name -Compress
    $secretJson=ConvertTo-Json -InputObject $secret -Compress
    [void](Run-Code "await page.locator('input[name=username]').fill($nameJson); await page.locator('input[name=password]').fill($secretJson); await page.locator('button[type=submit]').click(); return true;")
    [void](Invoke-Cli @('goto',"$base/conversation.html?ticketId=$TicketId"))
    [void](Invoke-Cli @('snapshot'))
    $stage='DRAFT_SELECTED'
    [void](Run-Code "await page.locator('#official-reply').waitFor({state:'visible'}); await page.locator('#reply-body').fill('Synthetic old draft.'); await page.locator('#reply-correction').selectOption('$OriginalReplyId'); return true;")
    [IO.File]::WriteAllText((Join-Path $RunDirectory 'draft-ready'),"READY`n",[Text.UTF8Encoding]::new($false))
    Wait-Gate 'assignment-3-committed'
    $stage='TYPED_REJECTION_AND_NEW_DRAFT'
    $phaseOneCode=@'
let step='REJECT_OLD_DRAFT';
try {
const path='/api/tickets/TICKET_ID/replies';
const posts=[],statuses=[];let csrfObserved=true;
await page.route('**'+path,async route=>{
    posts.push(route.request().postDataJSON());
    const headers=await route.request().allHeaders();csrfObserved=csrfObserved && typeof headers['x-csrf-token']==='string' && headers['x-csrf-token'].length>0;
    const upstream=await route.fetch();statuses.push(upstream.status());
    if(posts.length===3){
        if(upstream.status()!==201)throw new Error('COMMIT_BEFORE_RESPONSE_LOSS_REQUIRED');
        await page.evaluate(value=>{window.__replyReviewLostResult=value;},await upstream.json());
        await route.abort('failed');return;
    }
    await route.fulfill({response:upstream});
});
const rejected=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith(path));
await page.locator('#reply-send').click();
const first=await rejected;
if(first.status()!==409 || (await first.json()).code!=='ASSIGNMENT_VERSION_CONFLICT')throw new Error('TYPED_REJECTION_REQUIRED');
await page.locator('#reply-review').waitFor({state:'visible'});
await page.waitForFunction(()=>!document.querySelector('#reply-review').disabled);
await page.screenshot({path:REJECTED_SCREENSHOT,fullPage:true});
step='REFRESH_REJECTED_DRAFT';
await page.locator('#conversation-read button').click();
await page.locator('#official-reply').waitFor({state:'visible'});
if(await page.locator('#reply-body').inputValue()!=='Synthetic old draft.' || await page.locator('#reply-correction').inputValue()!=='ORIGINAL_REPLY_ID' || !(await page.locator('#reply-send').isDisabled()))throw new Error('REFRESH_CHANGED_REJECTED_DRAFT');
step='RESET_REJECTED_DRAFT';
await page.locator('#reply-review').click();
await page.locator('#official-reply').waitFor({state:'visible'});
if(posts.length!==1 || await page.locator('#reply-body').inputValue()!=='' || await page.locator('#reply-correction').inputValue()!=='' || !(await page.locator('#reply-review').isHidden()))throw new Error('RESET_NOT_EXPLICIT_AND_EMPTY');
await page.screenshot({path:EMPTY_SCREENSHOT,fullPage:true});
step='SAVE_REVIEWED_DRAFT';
await page.locator('#reply-body').fill('Synthetic reviewed reply.');
await page.locator('#reply-send').click();
await page.waitForFunction(()=>document.querySelector('#conversation-status').textContent.includes('공식 답변을 저장') && !document.querySelector('#reply-send').disabled);
if(posts.length!==2 || posts[0].expectedAssignmentVersion!==1 || posts[1].expectedAssignmentVersion!==3 || posts[0].clientRequestId===posts[1].clientRequestId || posts[1].correctsMessageId!==null || statuses[1]!==201)throw new Error('NEW_DRAFT_REUSED_OLD_SUBMISSION');
step='LOSE_CORRECTION_RESPONSE';
await page.locator('#reply-body').fill('Synthetic saved correction.');
await page.locator('#reply-correction').selectOption('ORIGINAL_REPLY_ID');
await page.locator('#reply-send').click();
await page.waitForFunction(()=>document.querySelector('#conversation-status').textContent.includes('저장 여부를 확인하지 못했습니다') && !document.querySelector('#reply-send').disabled);
if(posts.length!==3 || statuses[2]!==201 || !(await page.locator('#reply-review').isHidden()))throw new Error('UNKNOWN_SUBMISSION_DISCARDED');
await page.evaluate(value=>{window.__replyReviewSubmission=value;},posts[2]);
return {upstreamStatuses:statuses,postCount:posts.length,staleRejected:true,explicitBlankDraft:true,newUuid:true,csrfHeaderObserved:csrfObserved};
}catch(error){
    const known=['TYPED_REJECTION_REQUIRED','REFRESH_CHANGED_REJECTED_DRAFT','RESET_NOT_EXPLICIT_AND_EMPTY','NEW_DRAFT_REUSED_OLD_SUBMISSION','UNKNOWN_SUBMISSION_DISCARDED'];
    await page.screenshot({path:FAILED_SCREENSHOT,fullPage:true});
    return {failed:true,step,failureCode:known.includes(error.message)?error.message:error.name==='TimeoutError'?'BROWSER_TIMEOUT':'BROWSER_STEP_FAILED'};
}
'@
    $phaseOneCode=$phaseOneCode.Replace('TICKET_ID',[string]$TicketId).Replace('ORIGINAL_REPLY_ID',[string]$OriginalReplyId)
    $phaseOneCode=$phaseOneCode.Replace('REJECTED_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'rejected-reply-draft.png') -Compress))
    $phaseOneCode=$phaseOneCode.Replace('EMPTY_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'empty-reviewed-reply-draft.png') -Compress))
    $phaseOneCode=$phaseOneCode.Replace('FAILED_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'failed-reply-draft-step.png') -Compress))
    $phaseOne=Run-Code $phaseOneCode | ConvertFrom-Json
    if($phaseOne.PSObject.Properties.Name -contains 'failed'){$stage=$phaseOne.step;$failureCode=$phaseOne.failureCode;throw 'BROWSER_STEP_FAILED'}
    [IO.File]::WriteAllText((Join-Path $RunDirectory 'unknown-reply-ready'),"READY`n",[Text.UTF8Encoding]::new($false))
    $stage='WAITING_FOR_ASSIGNMENT_5'
    Wait-Gate 'assignment-5-committed'
    $stage='UNKNOWN_CORRECTION_REPLAY'
    $phaseTwoCode=@'
await page.locator('#conversation-read button').click();
await page.locator('#official-reply').waitFor({state:'visible'});
if(!(await page.locator('#reply-review').isHidden()) || await page.locator('#reply-body').inputValue()!=='Synthetic saved correction.' || await page.locator('#reply-correction').inputValue()!=='ORIGINAL_REPLY_ID' || !(await page.locator('#reply-correction').isDisabled()) || !(await page.locator('#reply-draft-status').textContent()).includes('미확인'))throw new Error('UNKNOWN_CORRECTION_CHANGED_AFTER_HANDOFF');
await page.screenshot({path:UNKNOWN_SCREENSHOT,fullPage:true});
const received=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith('/api/tickets/TICKET_ID/replies'));
await page.locator('#reply-send').click();
const replay=await received;
const original=await page.evaluate(()=>({submission:window.__replyReviewSubmission,result:window.__replyReviewLostResult}));
if(replay.status()!==200 || JSON.stringify(replay.request().postDataJSON())!==JSON.stringify(original.submission) || (await replay.json()).id!==original.result.id)throw new Error('ORIGINAL_REPLY_NOT_REPLAYED');
await page.waitForFunction(()=>document.querySelector('#conversation-status').textContent.includes('공식 답변을 저장') && !document.querySelector('#reply-send').disabled);
if(await page.locator('#reply-body').inputValue()!=='' || await page.locator('#reply-correction').inputValue()!=='')throw new Error('SAVED_DRAFT_NOT_CLEARED');
await page.screenshot({path:REPLAY_SCREENSHOT,fullPage:true});
return {replayStatus:replay.status(),originalSubmissionReplayed:true,correctionPreserved:true};
'@
    $phaseTwoCode=$phaseTwoCode.Replace('TICKET_ID',[string]$TicketId).Replace('ORIGINAL_REPLY_ID',[string]$OriginalReplyId)
    $phaseTwoCode=$phaseTwoCode.Replace('UNKNOWN_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'unknown-reply-after-reassignment.png') -Compress))
    $phaseTwoCode=$phaseTwoCode.Replace('REPLAY_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'reply-reconfirmed-without-duplicate.png') -Compress))
    $phaseTwo=Run-Code $phaseTwoCode | ConvertFrom-Json
    [ordered]@{evidence='LOCAL_REPLY_DRAFT_REVIEW_BROWSER_POSTGRES';completed=$true;postCount=$phaseOne.postCount+1;
        upstreamStatuses=@($phaseOne.upstreamStatuses)+@($phaseTwo.replayStatus);staleRejected=$phaseOne.staleRejected;
        explicitBlankDraft=$phaseOne.explicitBlankDraft;newUuid=$phaseOne.newUuid;csrfHeaderObserved=$phaseOne.csrfHeaderObserved;
        originalSubmissionReplayed=$phaseTwo.originalSubmissionReplayed;correctionPreserved=$phaseTwo.correctionPreserved} | ConvertTo-Json -Compress
}catch{
    [ordered]@{evidence='LOCAL_REPLY_DRAFT_REVIEW_BROWSER_POSTGRES';completed=$false;stage=$stage;failureCode=$failureCode} | ConvertTo-Json -Compress
    exit 1
}finally{
    if($opened){try{[void](Invoke-Cli @('close'))}catch{}}
    Pop-Location
}
