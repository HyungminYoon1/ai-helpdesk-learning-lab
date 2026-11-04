#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
    [Parameter(Mandatory)][long]$TicketId,
    [Parameter(Mandatory)][long]$InquiryId,
    [Parameter(Mandatory)][string]$RunDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
if((Test-Path Env:OPENAI_API_KEY) -or (Test-Path Env:HELPDESK_OPENAI_API_KEY)){throw 'BROWSER_AI_KEY_NOT_ALLOWED'}
if($TicketId -le 0 -or $InquiryId -le 0){throw 'SYNTHETIC_IDS_REQUIRED'}
$base="http://127.0.0.1:$Port"
$session="week12-reselection-$([Guid]::NewGuid().ToString('N'))"
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
    $stage='LOGIN_AND_SELECT'
    $name=[Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_AGENT_NAME','Process')
    $secret=[Environment]::GetEnvironmentVariable('HELPDESK_BROWSER_SECRET','Process')
    if(-not $name -or -not $secret){throw 'FIXTURE_REQUIRED'}
    $nameJson=ConvertTo-Json -InputObject $name -Compress
    $secretJson=ConvertTo-Json -InputObject $secret -Compress
    [void](Run-Code "await page.locator('input[name=username]').fill($nameJson); await page.locator('input[name=password]').fill($secretJson); await page.locator('button[type=submit]').click(); return true;")
    [void](Invoke-Cli @('goto',"$base/conversation.html?ticketId=$TicketId"))
    [void](Invoke-Cli @('snapshot'))
    [void](Run-Code "await page.waitForFunction(()=>!document.querySelector('#confirmation-target').disabled); await page.locator('#confirmation-target').selectOption('$InquiryId'); if(!(await page.locator('#confirmation-selection').textContent()).includes('v1'))throw new Error('INITIAL_SELECTION_FAILED'); return true;")
    [IO.File]::WriteAllText((Join-Path $RunDirectory 'selection-ready'),"READY`n",[Text.UTF8Encoding]::new($false))
    $stage='WAITING_FOR_ASSIGNMENT_3'
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    while(-not (Test-Path -LiteralPath (Join-Path $RunDirectory 'assignment-updated'))){
        if([DateTime]::UtcNow -ge $deadline){throw 'ASSIGNMENT_GATE_TIMEOUT'}
        Start-Sleep -Milliseconds 100
    }
    $stage='REJECTION_AND_EXPLICIT_RESELECTION'
    $code=@'
const path='/api/tickets/TICKET_ID/customer-message-confirmations';
const posts=[];
page.on('request',request=>{if(request.method()==='POST' && request.url().endsWith(path))posts.push(request.postDataJSON());});
const rejected=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith(path));
await page.locator('#confirmation-send').click();
const first=await rejected;
if(first.status()!==409 || (await first.json()).code!=='ASSIGNMENT_VERSION_CONFLICT')throw new Error('TYPED_REJECTION_REQUIRED');
await page.locator('#confirmation-reselect').waitFor({state:'visible'});
await page.waitForFunction(()=>!document.querySelector('#confirmation-reselect').disabled);
if(posts.length!==1 || posts[0].expectedAssignmentVersion!==1)throw new Error('OLD_SELECTION_CHANGED');
await page.locator('#confirmation-refresh').click();
await page.waitForFunction(()=>document.querySelector('#confirmation-current').textContent.includes('v3') && !document.querySelector('#confirmation-refresh').disabled);
if(!(await page.locator('#confirmation-selection').textContent()).includes('v1') || !(await page.locator('#confirmation-target').isDisabled()) || !(await page.locator('#confirmation-send').isDisabled()))throw new Error('REFRESH_SILENTLY_UPDATED_SELECTION');
await page.screenshot({path:REJECTED_SCREENSHOT,fullPage:true});
await page.locator('#confirmation-reselect').click();
await page.waitForFunction(()=>!document.querySelector('#confirmation-target').disabled && document.querySelector('#confirmation-current').textContent.includes('v3'));
if(posts.length!==1 || await page.locator('#confirmation-target').inputValue()!=='' || !(await page.locator('#confirmation-send').isDisabled()) || !(await page.locator('#confirmation-reselect').isHidden()))throw new Error('RESET_SENT_OR_SELECTED_A_COMMAND');
await page.screenshot({path:EMPTY_SCREENSHOT,fullPage:true});
await page.locator('#confirmation-target').selectOption('INQUIRY_ID');
await page.evaluate(value=>{window.__reselectionOldRequest=value;},posts[0]);
return {firstStatus:first.status(),oldVersionRetained:posts[0].expectedAssignmentVersion===1,resetReadOnly:true,emptySelectionRequired:true};
'@
    $code=$code.Replace('TICKET_ID',[string]$TicketId).Replace('INQUIRY_ID',[string]$InquiryId)
    $code=$code.Replace('REJECTED_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'rejected-confirmation-selection.png') -Compress))
    $code=$code.Replace('EMPTY_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'empty-confirmation-reselection.png') -Compress))
    $selected=Run-Code $code | ConvertFrom-Json
    [IO.File]::WriteAllText((Join-Path $RunDirectory 'fresh-target-selected'),"READY`n",[Text.UTF8Encoding]::new($false))
    $stage='WAITING_FOR_NEW_CUSTOMER_MESSAGE'
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    while(-not (Test-Path -LiteralPath (Join-Path $RunDirectory 'customer-message-committed'))){
        if([DateTime]::UtcNow -ge $deadline){throw 'CUSTOMER_MESSAGE_GATE_TIMEOUT'}
        Start-Sleep -Milliseconds 100
    }
    $latestId=[long](Get-Content -LiteralPath (Join-Path $RunDirectory 'customer-message-committed') -Encoding UTF8 -Raw)
    if($latestId -le $InquiryId){throw 'NEW_CUSTOMER_MESSAGE_REQUIRED'}
    $stage='CONFIRM_SELECTED_TARGET_WITH_NEW_CUSTOMER_MESSAGE'
    $code=@'
const path='/api/tickets/TICKET_ID/customer-message-confirmations';
const posts=[];
page.on('request',request=>{if(request.method()==='POST' && request.url().endsWith(path))posts.push(request.postDataJSON());});
const saved=page.waitForResponse(r=>r.request().method()==='POST' && r.url().endsWith(path));
await page.locator('#confirmation-send').click();
const second=await saved;
const result=await second.json();
if(second.status()!==201 || result.confirmation.assignmentVersion!==3 || result.confirmation.throughMessageId!==INQUIRY_ID)throw new Error('NEW_ASSIGNMENT_NOT_SAVED');
if(result.currentConfirmation.effectiveThroughMessageId!==INQUIRY_ID || result.currentConfirmation.latestCustomerMessageId!==LATEST_ID || !result.currentConfirmation.needsConfirmation)throw new Error('NEW_MESSAGE_WAS_SILENTLY_CONFIRMED');
await page.waitForFunction(()=>document.querySelector('#confirmation-current').textContent.includes('최신 고객 메시지 #LATEST_ID / 확인 필요') && !document.querySelector('#confirmation-refresh').disabled);
const old=await page.evaluate(()=>window.__reselectionOldRequest);
if(posts.length!==1 || posts[0].expectedAssignmentVersion!==3 || old.clientRequestId===posts[0].clientRequestId || posts[0].throughMessageId!==INQUIRY_ID)throw new Error('RESELECTION_REUSED_OLD_COMMAND');
await page.screenshot({path:SAVED_SCREENSHOT,fullPage:true});
await page.locator('#conversation-read button').click();
await page.waitForFunction(()=>[...document.querySelector('#confirmation-target').options].some(option=>option.value==='LATEST_ID') && !document.querySelector('#confirmation-target').disabled);
if(await page.locator('#confirmation-target').inputValue()!=='' || !(await page.locator('#confirmation-send').isDisabled()) || posts.length!==1)throw new Error('NEW_CUSTOMER_TARGET_AUTOMATICALLY_SELECTED');
await page.screenshot({path:LATE_SCREENSHOT,fullPage:true});
return {postCount:posts.length,status:second.status(),uuidChanged:old.clientRequestId!==posts[0].clientRequestId,
    lateMessageStillNeedsConfirmation:true,newTargetNotAutoSelected:true,
    csrfHeaderObserved:Object.keys(await second.request().allHeaders()).includes('x-csrf-token')};
'@
    $code=$code.Replace('TICKET_ID',[string]$TicketId).Replace('INQUIRY_ID',[string]$InquiryId).Replace('LATEST_ID',[string]$latestId)
    $code=$code.Replace('SAVED_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'new-assignment-confirmation.png') -Compress))
    $code=$code.Replace('LATE_SCREENSHOT',(ConvertTo-Json -InputObject (Join-Path $RunDirectory 'new-customer-after-selection.png') -Compress))
    $result=Run-Code $code | ConvertFrom-Json
    [ordered]@{evidence='LOCAL_CONFIRMATION_RESELECTION_BROWSER_POSTGRES';completed=$true;postCount=$result.postCount+1;statuses=@($selected.firstStatus,$result.status);
        oldVersionRetained=$selected.oldVersionRetained;uuidChanged=$result.uuidChanged;resetReadOnly=$selected.resetReadOnly;
        emptySelectionRequired=$selected.emptySelectionRequired;csrfHeaderObserved=$result.csrfHeaderObserved;
        lateMessageStillNeedsConfirmation=$result.lateMessageStillNeedsConfirmation;newTargetNotAutoSelected=$result.newTargetNotAutoSelected} | ConvertTo-Json -Compress
}catch{
    [ordered]@{evidence='LOCAL_CONFIRMATION_RESELECTION_BROWSER_POSTGRES';completed=$false;stage=$stage;failureCode='BROWSER_STEP_FAILED'} | ConvertTo-Json -Compress
    exit 1
}finally{
    if($opened){try{[void](Invoke-Cli @('close'))}catch{}}
    Pop-Location
}
