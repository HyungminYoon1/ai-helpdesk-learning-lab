# Dot-sourced only by Verify-Week8Compose.ps1 with its private synthetic Compose project.
# No AWS commands, external AI calls, real credentials, or production database addresses.

function Get-PrepareBusinessSnapshot {
    $queries = foreach ($table in @('tickets', 'ticket_messages', 'ai_suggestion_jobs',
        'ai_suggestion_attempts', 'ticket_suggestions', 'ticket_suggestion_categories')) {
        "SELECT '${table}:' || md5(COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text)::text, '[]')) FROM $table r"
    }
    return Invoke-SqlScalar -Sql (($queries -join ' UNION ALL ') + ' ORDER BY 1;')
}

function Get-PrepareMigrationSnapshot {
    param([switch]$V7Only)
    $filter = if ($V7Only) { "WHERE version <> '8'" } else { '' }
    return Invoke-SqlScalar -Sql "SELECT md5(jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text) FROM (SELECT * FROM flyway_schema_history $filter) h;"
}

function Add-PrepareTerminalFixture {
    param(
        [ValidateSet('SUCCEEDED', 'FAILED')][string]$State,
        [ValidateSet('PROVIDER_REFUSED', 'PRIVACY_SCAN_TIMEOUT', 'PRIVACY_SCAN_INVALID_RESULT')][string]$Failure,
        [string]$Title
    )
    $safeTitle = $Title.Replace("'", "''")
    $failureSql = if ($State -eq 'SUCCEEDED') { 'NULL' } else { "'$Failure'" }
    # All writes are synthetic and atomic; no Provider result is claimed.
    $sql = @"
WITH t AS (
    INSERT INTO tickets (title, status) VALUES ('$safeTitle', 'OPEN') RETURNING id
), m AS (
    INSERT INTO ticket_messages (ticket_id, body, author_username)
    SELECT id, '로그인 링크가 만료되어 문의합니다. 합성 학습 데이터입니다.', 'week8-user' FROM t RETURNING id
), j AS (
    INSERT INTO ai_suggestion_jobs (input_message_id, status, current_attempt, reserved_generation_count,
        first_started_at, processing_deadline_at, finished_at, last_failure_code)
    SELECT id, '$State', 1, 1, clock_timestamp(), clock_timestamp() + INTERVAL '5 minutes',
        clock_timestamp(), $failureSql FROM m RETURNING id
), a AS (
    INSERT INTO ai_suggestion_attempts (job_id, attempt_number, request_kind)
    SELECT id, 1, 'INITIAL' FROM j
), s AS (
    INSERT INTO ticket_suggestions (job_id, summary, priority)
    SELECT id, 'Synthetic preserved summary', 'NORMAL' FROM j WHERE '$State' = 'SUCCEEDED' RETURNING id
)
INSERT INTO ticket_suggestion_categories (suggestion_id, category)
SELECT s.id, c.category FROM s CROSS JOIN (VALUES ('ACCOUNT'), ('BILLING')) AS c(category);
"@
    $null = Invoke-SqlScalar -Sql $sql
    $id = Invoke-SqlScalar -Sql "SELECT id FROM tickets WHERE title = '$safeTitle';"
    Assert-Check ($id -match '^[1-9][0-9]*$') 'PREPARE_FIXTURE_RECEIPT_NOT_FOUND'
    return [long]$id
}

function Assert-PrepareRead {
    param([object]$Agent, [long]$TicketId, [string]$State, [object]$Failure)
    $read = Invoke-HttpSafe -Client $Agent.Client -Method GET -Path "/api/tickets/$TicketId/ai-suggestion"
    Assert-Check ($read.Status -eq 200) 'PREPARE_AI_QUERY_HTTP_FAILED'
    $result = $read.Body | ConvertFrom-Json
    Assert-Check ($result.ticketId -eq $TicketId -and $result.job.status -eq $State -and
        $result.job.failureCode -ceq $Failure) 'PREPARE_AI_QUERY_BODY_INVALID'
    if ($State -eq 'SUCCEEDED') {
        Assert-Check ($result.suggestion.summary -ceq 'Synthetic preserved summary' -and
            $result.suggestion.priority -eq 'NORMAL' -and $result.suggestion.reviewStatus -eq 'PENDING_REVIEW' -and
            (@($result.suggestion.categories | Sort-Object) -join ',') -ceq 'ACCOUNT,BILLING') 'PREPARE_STORED_SUGGESTION_CHANGED'
    } else {
        Assert-Check ($null -eq $result.suggestion) 'PREPARE_UNEXPECTED_SUGGESTION'
    }
    $ticketRead = Invoke-HttpSafe -Client $Agent.Client -Method GET -Path "/api/tickets/$TicketId"
    Assert-Check ($ticketRead.Status -eq 200 -and ($ticketRead.Body | ConvertFrom-Json).status -eq 'OPEN') 'PREPARE_TICKET_CHANGED'
}

function Invoke-PrepareCompatibility {
    param([object]$Anonymous, [object]$User, [object]$Agent, [long]$PendingTicketId)
    $fixture = Join-Path $repoRoot 'src/test/resources/rollback-compatibility/V8__allow_privacy_scan_failures.sql'
    Assert-Check ((Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash -eq
        '69A192342EA0BC806E32D0DFCBC104A05F0927551B7423D5B3FA64DFF24089F1') 'PREPARE_V8_FIXTURE_CHANGED'
    Assert-Check (@(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'src/main/resources/db/migration') -Filter '*.sql').Count -eq 7) 'PREPARE_PRODUCTION_MIGRATIONS_CHANGED'
    $dbBefore = Get-DatabaseIdentity
    $dbStartedBefore = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.StartedAt}}', $dbBefore.ContainerId)
    $appBefore = Get-AppExecutionIdentity
    $imageBefore = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.Image}}', $appBefore.ContainerId)
    $oldSession = $Agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value
    $successId = Add-PrepareTerminalFixture -State SUCCEEDED -Title 'Prepare preserved success'
    $failureId = Add-PrepareTerminalFixture -State FAILED -Failure PROVIDER_REFUSED -Title 'Prepare preserved failure'
    foreach ($code in @('PRIVACY_SCAN_TIMEOUT', 'PRIVACY_SCAN_INVALID_RESULT')) {
        # SQLSTATE only: an expected V7 CHECK rejection must not print SQL, rows or credentials.
        $rejectArgs = $composePrefix + @('exec', '-T', 'db', 'psql', '-X', '-U', 'postgres', '-d', 'helpdesk_local',
            '-tA', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', '-c',
            "UPDATE ai_suggestion_jobs SET last_failure_code = '$code' WHERE status = 'FAILED';")
        $captured = @(& docker @rejectArgs 2>&1)
        Assert-Check ($LASTEXITCODE -ne 0 -and ($captured -join "`n") -match '\b23514\b') 'PREPARE_V7_ACCEPTED_NEW_FAILURE'
    }
    $v7Rows = Get-PrepareBusinessSnapshot
    $v7History = Get-PrepareMigrationSnapshot
    Assert-PrepareRead -Agent $Agent -TicketId $PendingTicketId -State PENDING -Failure $null
    Assert-PrepareRead -Agent $Agent -TicketId $successId -State SUCCEEDED -Failure $null
    Assert-PrepareRead -Agent $Agent -TicketId $failureId -State FAILED -Failure PROVIDER_REFUSED
    Assert-Check ((Get-PrepareBusinessSnapshot) -ceq $v7Rows) 'PREPARE_V7_READ_CHANGED_ROWS'

    # Same Image, temporary external V8 mount, then remove it for P's normal startup.
    $null = Invoke-ComposeSafe -ComposeArgs @('stop', '--timeout', '20', 'app')
    $migrationPrefix = $composePrefix + @('-f', (Join-Path $repoRoot 'compose.rollback-fixture.yaml'))
    $null = Invoke-DockerSafe -DockerArgs ($migrationPrefix + @('up', '-d', '--no-deps', '--no-build', '--force-recreate', 'app'))
    Wait-LocalAppReady -Client $Anonymous.Client
    Assert-Check ((Invoke-SqlScalar "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success;") -ceq '1,2,3,4,5,6,7,8') 'PREPARE_V8_MIGRATION_NOT_APPLIED'
    Assert-Check ((Get-PrepareBusinessSnapshot) -ceq $v7Rows -and (Get-PrepareMigrationSnapshot -V7Only) -ceq $v7History) 'PREPARE_V8_REWROTE_EXISTING_DATA'
    $null = Invoke-ComposeSafe -ComposeArgs @('stop', '--timeout', '20', 'app')
    $timeoutId = Add-PrepareTerminalFixture -State FAILED -Failure PRIVACY_SCAN_TIMEOUT -Title 'Prepare privacy timeout'
    $invalidId = Add-PrepareTerminalFixture -State FAILED -Failure PRIVACY_SCAN_INVALID_RESULT -Title 'Prepare privacy invalid'
    $v8Rows = Get-PrepareBusinessSnapshot
    $v8History = Get-PrepareMigrationSnapshot
    $null = Invoke-ComposeSafe -ComposeArgs @('up', '-d', '--no-deps', '--no-build', '--force-recreate', 'app')
    Wait-LocalAppReady -Client $Anonymous.Client
    $appAfter = Get-AppExecutionIdentity
    $imageAfter = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.Image}}', $appAfter.ContainerId)
    $mounts = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{json .Mounts}}', $appAfter.ContainerId)
    Assert-Check ($appAfter.ContainerId -ne $appBefore.ContainerId -and $imageBefore -ceq $imageAfter -and
        -not $mounts.Contains('/rollback-test-migrations')) 'PREPARE_NORMAL_IMAGE_NOT_RECREATED'
    Assert-Check ((Get-PrepareMigrationSnapshot) -ceq $v8History) 'PREPARE_STARTUP_REWROTE_MIGRATION_HISTORY'
    Assert-Check ($Agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value -ceq $oldSession) 'PREPARE_OLD_CLIENT_COOKIE_CHANGED'
    $oldQuery = Invoke-HttpSafe -Client $Agent.Client -Method GET -Path "/api/tickets/$PendingTicketId/ai-suggestion"
    Assert-Check ($oldQuery.Status -eq 401) 'PREPARE_OLD_JVM_SESSION_SURVIVED'
    $null = Login-Local -Session $Agent -Username $env:HELPDESK_LOCAL_AGENT_USERNAME -Password $env:HELPDESK_LOCAL_AGENT_PASSWORD
    Assert-Check ($Agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value -cne $oldSession) 'PREPARE_RELOGIN_SESSION_UNCHANGED'
    $newCsrf = Login-Local -Session $User -Username $env:HELPDESK_LOCAL_USER_USERNAME -Password $env:HELPDESK_LOCAL_USER_PASSWORD
    $anonymousRead = Invoke-HttpSafe -Client $Anonymous.Client -Method GET -Path "/api/tickets/$timeoutId/ai-suggestion"
    $userRead = Invoke-HttpSafe -Client $User.Client -Method GET -Path "/api/tickets/$timeoutId/ai-suggestion"
    $noCsrf = Invoke-HttpSafe -Client $User.Client -Method POST -Path '/api/tickets' -Content (New-TicketContent)
    Assert-Check ($anonymousRead.Status -eq 401 -and $userRead.Status -eq 403 -and $noCsrf.Status -eq 403) 'PREPARE_V8_SECURITY_CHANGED'
    Assert-PrepareRead -Agent $Agent -TicketId $PendingTicketId -State PENDING -Failure $null
    Assert-PrepareRead -Agent $Agent -TicketId $successId -State SUCCEEDED -Failure $null
    Assert-PrepareRead -Agent $Agent -TicketId $failureId -State FAILED -Failure PROVIDER_REFUSED
    Assert-PrepareRead -Agent $Agent -TicketId $timeoutId -State FAILED -Failure PRIVACY_SCAN_TIMEOUT
    Assert-PrepareRead -Agent $Agent -TicketId $invalidId -State FAILED -Failure PRIVACY_SCAN_INVALID_RESULT
    $ui = Invoke-HttpSafe -Client $Agent.Client -Method GET -Path '/ai-suggestion-ui.mjs'
    Assert-Check ($ui.Status -eq 200 -and $ui.Body.Contains('"PRIVACY_SCAN_TIMEOUT"') -and
        $ui.Body.Contains('"PRIVACY_SCAN_INVALID_RESULT"')) 'PREPARE_CONTAINER_UI_CODES_MISSING'
    $resources = Invoke-ResourceProbe -Agent $Agent -TicketId $PendingTicketId
    Assert-Check ((Get-PrepareBusinessSnapshot) -ceq $v8Rows) 'PREPARE_V8_READ_CHANGED_ROWS'
    Assert-Check ((Invoke-SqlScalar "SELECT count(*) = 5 AND bool_and(status = 'OPEN') FROM tickets;") -eq 't') 'PREPARE_EXISTING_TICKETS_NOT_PRESERVED'
    Assert-Check ((Invoke-SqlScalar "SELECT count(*) = 5 AND bool_and(body = '로그인 링크가 만료되어 문의합니다. 합성 학습 데이터입니다.' AND author_username = 'week8-user') FROM ticket_messages;") -eq 't') 'PREPARE_ORIGINAL_AUTHOR_NOT_PRESERVED'
    Assert-Check ((Invoke-SqlScalar 'SELECT (SELECT count(*) FROM ai_suggestion_attempts) = 4 AND (SELECT count(*) FROM ticket_suggestions) = 1 AND (SELECT count(*) FROM ticket_suggestion_categories) = 2;') -eq 't') 'PREPARE_RESULT_COUNTS_CHANGED'
    $added = Invoke-HttpSafe -Client $User.Client -Method POST -Path '/api/tickets' -Content (New-TicketContent) -Csrf $newCsrf
    $addedId = ($added.Body | ConvertFrom-Json).id
    Assert-Check ($added.Status -eq 201 -and $added.LocationExists -and $addedId -gt $invalidId) 'PREPARE_V8_NEW_RECEIPT_FAILED'
    Assert-PrepareRead -Agent $Agent -TicketId $addedId -State PENDING -Failure $null
    Assert-Check ((Invoke-SqlScalar 'SELECT (SELECT count(*) FROM tickets) = 6 AND (SELECT count(*) FROM ticket_messages) = 6 AND (SELECT count(*) FROM ai_suggestion_jobs) = 6 AND (SELECT count(*) FROM ai_suggestion_attempts) = 4 AND (SELECT count(*) FROM ticket_suggestions) = 1 AND (SELECT count(*) FROM ticket_suggestion_categories) = 2;') -eq 't') 'PREPARE_FINAL_COUNTS_INVALID'
    Assert-Check ((Invoke-SqlScalar "SELECT count(*) = 1 AND bool_and(j.current_attempt = 0 AND j.reserved_generation_count = 0) FROM ai_suggestion_jobs j JOIN ticket_messages m ON m.id = j.input_message_id WHERE m.ticket_id = $addedId;") -eq 't') 'PREPARE_V8_NEW_JOB_NOT_PENDING'
    Assert-Check ((Get-PrepareMigrationSnapshot) -ceq $v8History) 'PREPARE_FINAL_MIGRATION_HISTORY_CHANGED'
    $dbAfter = Get-DatabaseIdentity
    $dbStartedAfter = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.StartedAt}}', $dbAfter.ContainerId)
    Assert-Check ($dbAfter.ContainerId -ceq $dbBefore.ContainerId -and $dbAfter.VolumeName -ceq $dbBefore.VolumeName -and
        $dbStartedAfter -ceq $dbStartedBefore) 'PREPARE_DATABASE_PROCESS_CHANGED'
    $logs = Invoke-ComposeSafe -ComposeArgs @('logs', '--no-color', '--no-log-prefix', 'app')
    Assert-Check ($logs -match 'newer than the latest available migration') 'PREPARE_FUTURE_MIGRATION_WARNING_NOT_OBSERVED'
    return [ordered]@{
        outcome = 'PASS'; productionMigrationCount = 7; testOnlyV8Applied = $true
        normalPStartupOnV8 = $true; testMigrationMountRemoved = $true
        futureMigrationWarningObserved = $true; migrationValidationDisabled = $false
        sameImageAcrossV7AndV8 = $true; sameDatabaseProcessAndVolume = $true
        actualContainerAndJvmRecreated = $true; oldSessionStatus = $oldQuery.Status; reloginStatus = 200
        newFailureCodes = @('PRIVACY_SCAN_TIMEOUT', 'PRIVACY_SCAN_INVALID_RESULT')
        newFailureQueryStatus = 200; newFailureJobStatus = 'FAILED'; newFailureSuggestion = $null
        businessTableHashesUnchangedByMigrationAndReads = $true; v1ThroughV7HistoryPreserved = $true
        existingTicketCount = 5; finalTicketCount = 6; finalMessageCount = 6; finalJobCount = 6
        fixtureReservationRows = 4; fixtureSuggestionRows = 1; fixtureCategoryRows = 2
        providerResultsAreSqlFixtures = $true; liveAiCalls = 0; newReceiptStatusOnV8 = $added.Status
        anonymousQueryStatus = $anonymousRead.Status; userQueryStatus = $userRead.Status; missingCsrfStatus = $noCsrf.Status
        finalResources = $resources; browserE2e = $false; awsAccessed = $false
    }
}
