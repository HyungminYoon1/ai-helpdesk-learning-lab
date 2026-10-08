#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$RecreateDatabase,
    [switch]$RestartApplication,
    [switch]$RecreateApplicationWithSettings
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false

$repoRoot = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $repoRoot 'compose.yaml'
$projectName = 'helpdesk-week8-' + [guid]::NewGuid().ToString('N')
$composePrefix = @('compose', '--project-directory', $repoRoot, '-f', $composePath, '-p', $projectName)
$clients = [System.Collections.Generic.List[System.Net.Http.HttpClient]]::new()
$observedSessionSecrets = [System.Collections.Generic.List[string]]::new()
$environmentNames = @(
    'HELPDESK_DB_ADMIN_PASSWORD', 'HELPDESK_DB_APP_PASSWORD',
    'HELPDESK_LOCAL_USER_USERNAME', 'HELPDESK_LOCAL_USER_PASSWORD',
    'HELPDESK_LOCAL_AGENT_USERNAME', 'HELPDESK_LOCAL_AGENT_PASSWORD',
    'HELPDESK_LOCAL_IMAGE', 'HELPDESK_LOCAL_CORS_ALLOWED_ORIGIN'
)
$previousEnvironment = @{}
$composeStarted = $false
$phase = 'PREPARE'
$cleanupSucceeded = $true
$environmentRestored = $true
$report = [ordered]@{
    evidence = 'LOCAL_COMPOSE_HTTP_POSTGRES'
    project = $projectName
    browserE2e = $false
    aiWorkerEnabled = $false
    outcome = 'NOT_COMPLETED'
}

function Assert-Check {
    param([bool]$Condition, [string]$Code)
    if (-not $Condition) { throw $Code }
}

function Invoke-DockerSafe {
    param([string[]]$DockerArgs)
    # 전체 Compose 설정, Env, Cookie, Token 또는 실패 출력은 Console로 내보내지 않는다.
    $captured = @(& docker @DockerArgs 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'DOCKER_COMMAND_FAILED' }
    return (($captured | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

function Invoke-ComposeSafe {
    param([string[]]$ComposeArgs)
    return Invoke-DockerSafe -DockerArgs ($composePrefix + $ComposeArgs)
}

function Invoke-SqlScalar {
    param([string]$Sql)
    return Invoke-ComposeSafe -ComposeArgs @(
        'exec', '-T', 'db', 'psql', '-X', '-U', 'postgres', '-d', 'helpdesk_local',
        '-tA', '-v', 'ON_ERROR_STOP=1', '-c', $Sql
    )
}

function New-LocalHttpClient {
    param([switch]$WithoutCookies)
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.UseCookies = -not $WithoutCookies
    $handler.CookieContainer = [System.Net.CookieContainer]::new()
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.BaseAddress = [uri]$script:baseUrl
    $client.Timeout = [timespan]::FromSeconds(10)
    $clients.Add($client)
    return [pscustomobject]@{ Client = $client; Cookies = $handler.CookieContainer }
}

function Invoke-HttpSafe {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Method,
        [string]$Path,
        [System.Net.Http.HttpContent]$Content = $null,
        [object]$Csrf = $null,
        [hashtable]$Headers = @{}
    )
    $requestUri = [uri]::new([uri]$script:baseUrl, $Path)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $requestUri)
    $response = $null
    try {
        if ($null -ne $Content) { $request.Content = $Content }
        if ($null -ne $Csrf) {
            [void]$request.Headers.TryAddWithoutValidation([string]$Csrf.headerName, [string]$Csrf.token)
        }
        foreach ($header in $Headers.GetEnumerator()) {
            [void]$request.Headers.TryAddWithoutValidation([string]$header.Key, [string]$header.Value)
        }
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $allowedOrigin = $null
        if ($response.Headers.Contains('Access-Control-Allow-Origin')) {
            $allowedOrigin = [string]::Join(',', $response.Headers.GetValues('Access-Control-Allow-Origin'))
        }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            LocationExists = $null -ne $response.Headers.Location
            AllowedOrigin = $allowedOrigin
        }
    }
    catch { throw 'HTTP_REQUEST_FAILED' }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Wait-LocalAppReady {
    param([System.Net.Http.HttpClient]$Client)
    $binding = Invoke-ComposeSafe -ComposeArgs @('port', 'app', '8080')
    Assert-Check ($binding -match '^127\.0\.0\.1:\d+$') 'APP_NOT_LOOPBACK_ONLY'
    $script:baseUrl = 'http://' + $binding + '/'
    $ready = $false
    $deadline = [datetime]::UtcNow.AddSeconds(120)
    while ([datetime]::UtcNow -lt $deadline) {
        try {
            $page = Invoke-HttpSafe -Client $Client -Method GET -Path '/login'
            if ($page.Status -eq 200) { $ready = $true; break }
        }
        catch { }
        Start-Sleep -Milliseconds 500
    }
    Assert-Check $ready 'APP_HTTP_READY_TIMEOUT'
}

function Invoke-LocalPreflight {
    param([System.Net.Http.HttpClient]$Client, [string]$Origin)
    # Cookie를 전송하지 않는 전용 HttpClient에서 서버의 CORS 응답을 확인한다.
    return Invoke-HttpSafe -Client $Client -Method OPTIONS -Path '/api/tickets' -Headers @{
        'Origin' = $Origin
        'Access-Control-Request-Method' = 'POST'
        'Access-Control-Request-Headers' = 'content-type,x-csrf-token'
    }
}

function Login-Local {
    param([object]$Session, [string]$Username, [string]$Password)
    $page = Invoke-HttpSafe -Client $Session.Client -Method GET -Path '/login'
    Assert-Check ($page.Status -eq 200) 'LOGIN_PAGE_FAILED'
    $csrfMatch = [regex]::Match($page.Body, '<input\b(?=[^>]*\bname="_csrf")(?=[^>]*\bvalue="([^"]+)")[^>]*>')
    Assert-Check $csrfMatch.Success 'LOGIN_CSRF_NOT_FOUND'
    $form = [System.Collections.Generic.Dictionary[string,string]]::new()
    $form.Add('username', $Username)
    $form.Add('password', $Password)
    $form.Add('_csrf', [System.Net.WebUtility]::HtmlDecode($csrfMatch.Groups[1].Value))
    $observedSessionSecrets.Add($form['_csrf'])
    $result = Invoke-HttpSafe -Client $Session.Client -Method POST -Path '/login' -Content ([System.Net.Http.FormUrlEncodedContent]::new($form))
    Assert-Check ($result.Status -eq 302 -and $result.LocationExists) 'LOGIN_RESPONSE_FAILED'
    $csrfResponse = Invoke-HttpSafe -Client $Session.Client -Method GET -Path '/api/csrf'
    Assert-Check ($csrfResponse.Status -eq 200) 'LOGIN_SESSION_NOT_RESTORED'
    $csrf = $csrfResponse.Body | ConvertFrom-Json
    Assert-Check ($csrf.headerName -eq 'X-CSRF-TOKEN' -and -not [string]::IsNullOrWhiteSpace($csrf.token)) 'CSRF_RESPONSE_INVALID'
    Assert-Check ($null -ne $Session.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID']) 'SESSION_COOKIE_NOT_FOUND'
    $observedSessionSecrets.Add([string]$csrf.token)
    $observedSessionSecrets.Add($Session.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value)
    return $csrf
}

function New-TicketContent {
    $payload = @{ title = 'Compose 저장 확인'; body = '로그인 링크가 만료되어 문의합니다. 합성 학습 데이터입니다.' } | ConvertTo-Json -Compress
    return [System.Net.Http.StringContent]::new($payload, [System.Text.Encoding]::UTF8, 'application/json')
}

function Get-DatabaseIdentity {
    $id = Invoke-ComposeSafe -ComposeArgs @('ps', '-q', 'db')
    Assert-Check ($id -match '^[0-9a-f]{64}$') 'DB_CONTAINER_NOT_FOUND'
    $mountsJson = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{json .Mounts}}', $id)
    $mounts = @($mountsJson | ConvertFrom-Json)
    $volume = @($mounts | Where-Object { $_.Type -eq 'volume' -and $_.Destination -eq '/var/lib/postgresql/data' })
    Assert-Check ($volume.Count -eq 1 -and $volume[0].Name -eq ($projectName + '_postgres-data')) 'UNEXPECTED_DB_VOLUME'
    return [pscustomobject]@{ ContainerId = $id; VolumeName = $volume[0].Name }
}

function Get-AppExecutionIdentity {
    $id = Invoke-ComposeSafe -ComposeArgs @('ps', '-q', 'app')
    Assert-Check ($id -match '^[0-9a-f]{64}$') 'APP_CONTAINER_NOT_FOUND'
    $startedAt = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.StartedAt}}', $id)
    $running = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.Running}}', $id)
    Assert-Check ($running -eq 'true' -and -not [string]::IsNullOrWhiteSpace($startedAt)) 'APP_NOT_RUNNING'
    return [pscustomobject]@{ ContainerId = $id; StartedAt = $startedAt }
}

function Assert-StoredRows {
    param([long]$TicketId)
    $sql = @"
SELECT (SELECT count(*) FROM tickets) = 1
   AND (SELECT count(*) FROM ticket_messages) = 1
   AND (SELECT count(*) FROM ai_suggestion_jobs) = 1
   AND (SELECT count(*) FROM ai_suggestion_attempts) = 0
   AND (SELECT count(*) FROM ticket_suggestions) = 0
   AND (SELECT count(*) FROM ticket_suggestion_categories) = 0
   AND EXISTS (
       SELECT 1 FROM tickets t
       JOIN ticket_messages m ON m.ticket_id = t.id
       JOIN ai_suggestion_jobs j ON j.input_message_id = m.id
       WHERE t.id = $TicketId AND t.title = 'Compose 저장 확인' AND t.status = 'OPEN'
         AND m.body = '로그인 링크가 만료되어 문의합니다. 합성 학습 데이터입니다.'
         AND m.author_username = 'week8-user'
         AND j.status = 'PENDING' AND j.current_attempt = 0 AND j.reserved_generation_count = 0
   );
"@
    Assert-Check ((Invoke-SqlScalar -Sql $sql) -eq 't') 'STORED_ROWS_MISMATCH'
}

try {
    foreach ($name in $environmentNames) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    foreach ($name in @('HELPDESK_DB_ADMIN_PASSWORD', 'HELPDESK_DB_APP_PASSWORD', 'HELPDESK_LOCAL_USER_PASSWORD', 'HELPDESK_LOCAL_AGENT_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'), 'Process')
    }
    [Environment]::SetEnvironmentVariable('HELPDESK_LOCAL_USER_USERNAME', 'week8-user', 'Process')
    [Environment]::SetEnvironmentVariable('HELPDESK_LOCAL_AGENT_USERNAME', 'week8-agent', 'Process')
    [Environment]::SetEnvironmentVariable('HELPDESK_LOCAL_IMAGE', 'helpdesk:week8-build-baseline', 'Process')
    [Environment]::SetEnvironmentVariable('HELPDESK_LOCAL_CORS_ALLOWED_ORIGIN', 'http://127.0.0.1:4173', 'Process')
    $null = Invoke-DockerSafe -DockerArgs @('image', 'inspect', '--format', '{{.Id}}', 'helpdesk:week8-build-baseline')
    $null = Invoke-ComposeSafe -ComposeArgs @('config', '--quiet')
    $phase = 'COMPOSE_START'
    $composeStarted = $true
    $null = Invoke-ComposeSafe -ComposeArgs @('up', '-d', '--no-build')
    $dbIdentity = Get-DatabaseIdentity
    $report.databaseVolume = $dbIdentity.VolumeName
    $binding = Invoke-ComposeSafe -ComposeArgs @('port', 'app', '8080')
    Assert-Check ($binding -match '^127\.0\.0\.1:\d+$') 'APP_NOT_LOOPBACK_ONLY'
    $script:baseUrl = 'http://' + $binding + '/'
    $report.baseUrl = $script:baseUrl
    $anonymous = New-LocalHttpClient
    $phase = 'APP_READY'
    Wait-LocalAppReady -Client $anonymous.Client
    $phase = 'MIGRATION_AND_ROLE'
    Assert-Check ((Invoke-SqlScalar 'SELECT count(*) = 7 AND bool_and(success) AND max(version::integer) = 7 FROM flyway_schema_history;') -eq 't') 'MIGRATION_NOT_APPLIED'
    Assert-Check ((Invoke-SqlScalar "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname = 'helpdesk_app';") -eq 't') 'APP_DB_ROLE_TOO_PRIVILEGED'
    Assert-Check ((Invoke-SqlScalar "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE usename = 'helpdesk_app' AND datname = 'helpdesk_local');") -eq 't') 'APP_DB_CONNECTION_NOT_FOUND'
    $dbPorts = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{json .HostConfig.PortBindings}}', $dbIdentity.ContainerId)
    Assert-Check ($dbPorts -in @('{}', 'null')) 'DB_HOST_PORT_PUBLISHED'
    $report.successfulMigrations = 7
    $report.appDatabaseSuperuser = $false
    $report.databaseHostPortPublished = $false
    $phase = 'HTTP_RECEIPT'
    $anonymousResult = Invoke-HttpSafe -Client $anonymous.Client -Method GET -Path '/api/tickets/1'
    Assert-Check ($anonymousResult.Status -eq 401 -and -not $anonymousResult.LocationExists) 'ANONYMOUS_CONTRACT_FAILED'
    $user = New-LocalHttpClient
    $csrf = Login-Local -Session $user -Username $env:HELPDESK_LOCAL_USER_USERNAME -Password $env:HELPDESK_LOCAL_USER_PASSWORD
    $blocked = Invoke-HttpSafe -Client $user.Client -Method POST -Path '/api/tickets' -Content (New-TicketContent)
    Assert-Check ($blocked.Status -eq 403) 'MISSING_CSRF_NOT_BLOCKED'
    Assert-Check ((Invoke-SqlScalar 'SELECT (SELECT count(*) FROM tickets) + (SELECT count(*) FROM ticket_messages) + (SELECT count(*) FROM ai_suggestion_jobs);') -eq '0') 'BLOCKED_REQUEST_STORED_ROWS'
    $receipt = Invoke-HttpSafe -Client $user.Client -Method POST -Path '/api/tickets' -Content (New-TicketContent) -Csrf $csrf
    Assert-Check ($receipt.Status -eq 201 -and $receipt.LocationExists) 'RECEIPT_CONTRACT_FAILED'
    $ticket = $receipt.Body | ConvertFrom-Json
    Assert-Check ($ticket.id -gt 0 -and $ticket.title -eq 'Compose 저장 확인' -and $ticket.status -eq 'OPEN') 'RECEIPT_BODY_INVALID'
    Assert-StoredRows -TicketId $ticket.id
    $report.ticketId = $ticket.id
    $report.anonymousStatus = $anonymousResult.Status
    $report.missingCsrfStatus = $blocked.Status
    $report.receiptStatus = $receipt.Status
    $report.originalAndAuthenticatedAuthorPreserved = $true
    $report.jobStatus = 'PENDING'
    $report.reservedGenerationCount = 0
    $report.suggestionCount = 0
    $phase = 'HTTP_ROLE_QUERY'
    $userQuery = Invoke-HttpSafe -Client $user.Client -Method GET -Path ("/api/tickets/" + $ticket.id)
    Assert-Check ($userQuery.Status -eq 403) 'USER_QUERY_NOT_BLOCKED'
    $agent = New-LocalHttpClient
    $null = Login-Local -Session $agent -Username $env:HELPDESK_LOCAL_AGENT_USERNAME -Password $env:HELPDESK_LOCAL_AGENT_PASSWORD
    $agentQuery = Invoke-HttpSafe -Client $agent.Client -Method GET -Path ("/api/tickets/" + $ticket.id)
    Assert-Check ($agentQuery.Status -eq 200 -and ($agentQuery.Body | ConvertFrom-Json).title -eq $ticket.title) 'AGENT_QUERY_FAILED'
    $report.userQueryStatus = $userQuery.Status
    $report.agentQueryStatus = $agentQuery.Status
    $report.sessionCookieObserved = $true
    $report.csrfHeaderUsed = $true
    $report.databaseContainerRecreated = $false
    if ($RecreateDatabase) {
        $phase = 'DATABASE_RECREATE'
        $appExecution = Get-AppExecutionIdentity
        # 이 실행이 만든 DB Container만 교체한다. Volume 삭제나 기존 Project 조작은 하지 않는다.
        $null = Invoke-ComposeSafe -ComposeArgs @('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'db')
        $newIdentity = Get-DatabaseIdentity
        Assert-Check ($newIdentity.ContainerId -ne $dbIdentity.ContainerId) 'DB_CONTAINER_NOT_REPLACED'
        Assert-Check ($newIdentity.VolumeName -eq $dbIdentity.VolumeName) 'DB_VOLUME_CHANGED'
        Assert-StoredRows -TicketId $ticket.id
        Assert-Check ((Invoke-SqlScalar 'SELECT count(*) = 7 AND bool_and(success) AND max(version::integer) = 7 FROM flyway_schema_history;') -eq 't') 'MIGRATION_HISTORY_CHANGED'
        $reconnected = $false
        $deadline = [datetime]::UtcNow.AddSeconds(60)
        while ([datetime]::UtcNow -lt $deadline) {
            try {
                $query = Invoke-HttpSafe -Client $agent.Client -Method GET -Path ("/api/tickets/" + $ticket.id)
                if ($query.Status -eq 200 -and ($query.Body | ConvertFrom-Json).title -eq $ticket.title) {
                    $reconnected = $true
                    break
                }
            }
            catch { }
            Start-Sleep -Milliseconds 500
        }
        Assert-Check $reconnected 'APP_DID_NOT_RECONNECT'
        $newAppExecution = Get-AppExecutionIdentity
        Assert-Check ($newAppExecution.ContainerId -eq $appExecution.ContainerId -and $newAppExecution.StartedAt -eq $appExecution.StartedAt) 'APP_RESTARTED_DURING_DB_RECREATE'
        $report.databaseContainerRecreated = $true
        $report.sameVolumeAndRowsPreserved = $true
        $report.appProcessNotRestarted = $true
        $report.agentQueryAfterDatabaseRecreateStatus = $query.Status
        $report.appSessionSurvivedDatabaseRecreation = $true
    }
    $report.applicationRestarted = $false
    $report.applicationContainerRecreated = $false
    if ($RestartApplication) {
        $phase = 'APPLICATION_RESTART'
        $appBeforeRestart = Get-AppExecutionIdentity
        $dbBeforeRestart = Get-DatabaseIdentity
        $dbStartedBefore = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.StartedAt}}', $dbBeforeRestart.ContainerId)
        $oldAgentSessionId = $agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value
        # 이 실행에서 만든 같은 App Container만 중지·시작한다. DB와 Volume은 유지한다.
        $null = Invoke-ComposeSafe -ComposeArgs @('stop', '--timeout', '20', 'app')
        $null = Invoke-ComposeSafe -ComposeArgs @('start', 'app')
        Wait-LocalAppReady -Client $anonymous.Client
        $report.baseUrlAfterAppRestart = $script:baseUrl
        $appAfterRestart = Get-AppExecutionIdentity
        Assert-Check ($appAfterRestart.ContainerId -eq $appBeforeRestart.ContainerId) 'APP_CONTAINER_REPLACED'
        Assert-Check ($appAfterRestart.StartedAt -ne $appBeforeRestart.StartedAt) 'APP_EXECUTION_NOT_RESTARTED'
        $dbAfterRestart = Get-DatabaseIdentity
        $dbStartedAfter = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.StartedAt}}', $dbAfterRestart.ContainerId)
        Assert-Check ($dbAfterRestart.ContainerId -eq $dbBeforeRestart.ContainerId -and $dbStartedAfter -eq $dbStartedBefore) 'DB_RESTARTED_WITH_APP'
        Assert-Check ($dbAfterRestart.VolumeName -eq $dbBeforeRestart.VolumeName) 'DB_VOLUME_CHANGED_WITH_APP'
        Assert-StoredRows -TicketId $ticket.id
        Assert-Check ((Invoke-SqlScalar 'SELECT count(*) = 7 AND bool_and(success) AND max(version::integer) = 7 FROM flyway_schema_history;') -eq 't') 'MIGRATION_HISTORY_CHANGED_AFTER_APP_RESTART'
        Assert-Check ($agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value -eq $oldAgentSessionId) 'CLIENT_OLD_COOKIE_NOT_PRESERVED'
        # Cookie 자동 전송을 켠 같은 HttpClient로 재로그인 전의 보호된 GET을 보낸다.
        $oldSessionQuery = Invoke-HttpSafe -Client $agent.Client -Method GET -Path ("/api/tickets/" + $ticket.id)
        Assert-Check ($oldSessionQuery.Status -eq 401 -and -not $oldSessionQuery.LocationExists) 'OLD_SESSION_STILL_AUTHENTICATED'
        $null = Login-Local -Session $agent -Username $env:HELPDESK_LOCAL_AGENT_USERNAME -Password $env:HELPDESK_LOCAL_AGENT_PASSWORD
        Assert-Check ($agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value -ne $oldAgentSessionId) 'RELOGIN_SESSION_ID_NOT_CHANGED'
        $reloginQuery = Invoke-HttpSafe -Client $agent.Client -Method GET -Path ("/api/tickets/" + $ticket.id)
        Assert-Check ($reloginQuery.Status -eq 200 -and ($reloginQuery.Body | ConvertFrom-Json).id -eq $ticket.id -and ($reloginQuery.Body | ConvertFrom-Json).title -eq $ticket.title) 'RELOGIN_TICKET_QUERY_FAILED'
        Assert-StoredRows -TicketId $ticket.id
        $report.applicationRestarted = $true
        $report.sameAppContainerNewExecution = $true
        $report.databaseProcessUnchangedDuringAppRestart = $true
        $report.sameVolumeAndRowsAfterAppRestart = $true
        $report.oldSessionCookiePreservedInClientBeforeQuery = $true
        $report.oldSessionQueryStatus = $oldSessionQuery.Status
        $report.agentQueryAfterReloginStatus = $reloginQuery.Status
    }
    $report.applicationSettingsChanged = $false
    if ($RecreateApplicationWithSettings) {
        $phase = 'APPLICATION_SETTINGS_BASELINE'
        $initialOrigin = 'http://127.0.0.1:4173'
        $changedOrigin = 'http://127.0.0.1:5173'
        $preflight = New-LocalHttpClient -WithoutCookies
        $oldAllowed = Invoke-LocalPreflight -Client $preflight.Client -Origin $initialOrigin
        $newRejected = Invoke-LocalPreflight -Client $preflight.Client -Origin $changedOrigin
        Assert-Check ($oldAllowed.Status -eq 200 -and $oldAllowed.AllowedOrigin -eq $initialOrigin) 'BASELINE_CORS_NOT_ALLOWED'
        Assert-Check ($newRejected.Status -eq 403 -and $null -eq $newRejected.AllowedOrigin) 'BASELINE_CORS_NOT_REJECTED'
        $appBeforeSettings = Get-AppExecutionIdentity
        $imageBeforeSettings = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.Image}}', $appBeforeSettings.ContainerId)
        $dbBeforeSettings = Get-DatabaseIdentity
        $dbStartedBeforeSettings = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.StartedAt}}', $dbBeforeSettings.ContainerId)
        $oldAgentSessionId = $agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value
        $phase = 'APPLICATION_SETTINGS_STOP_START'
        [Environment]::SetEnvironmentVariable('HELPDESK_LOCAL_CORS_ALLOWED_ORIGIN', $changedOrigin, 'Process')
        $null = Invoke-ComposeSafe -ComposeArgs @('stop', '--timeout', '20', 'app')
        $null = Invoke-ComposeSafe -ComposeArgs @('start', 'app')
        Wait-LocalAppReady -Client $anonymous.Client
        $appAfterStopStart = Get-AppExecutionIdentity
        Assert-Check ($appAfterStopStart.ContainerId -eq $appBeforeSettings.ContainerId -and $appAfterStopStart.StartedAt -ne $appBeforeSettings.StartedAt) 'SETTINGS_STOP_START_IDENTITY_FAILED'
        $oldStillAllowed = Invoke-LocalPreflight -Client $preflight.Client -Origin $initialOrigin
        $newStillRejected = Invoke-LocalPreflight -Client $preflight.Client -Origin $changedOrigin
        Assert-Check ($oldStillAllowed.Status -eq 200 -and $oldStillAllowed.AllowedOrigin -eq $initialOrigin) 'OLD_SETTING_NOT_RETAINED'
        Assert-Check ($newStillRejected.Status -eq 403 -and $null -eq $newStillRejected.AllowedOrigin) 'SETTING_CHANGED_WITHOUT_RECREATE'
        $phase = 'APPLICATION_SETTINGS_RECREATE'
        # 이번 Project의 App만 재생성한다. 같은 Image·DB·Volume을 사용하고 Build하지 않는다.
        $null = Invoke-ComposeSafe -ComposeArgs @('up', '-d', '--no-deps', '--no-build', '--force-recreate', 'app')
        Wait-LocalAppReady -Client $anonymous.Client
        $appAfterSettings = Get-AppExecutionIdentity
        $imageAfterSettings = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.Image}}', $appAfterSettings.ContainerId)
        Assert-Check ($appAfterSettings.ContainerId -ne $appBeforeSettings.ContainerId) 'SETTINGS_APP_NOT_RECREATED'
        Assert-Check ($imageAfterSettings -eq $imageBeforeSettings) 'SETTINGS_APP_IMAGE_CHANGED'
        $newAllowed = Invoke-LocalPreflight -Client $preflight.Client -Origin $changedOrigin
        $oldRejected = Invoke-LocalPreflight -Client $preflight.Client -Origin $initialOrigin
        Assert-Check ($newAllowed.Status -eq 200 -and $newAllowed.AllowedOrigin -eq $changedOrigin) 'NEW_CORS_SETTING_NOT_APPLIED'
        Assert-Check ($oldRejected.Status -eq 403 -and $null -eq $oldRejected.AllowedOrigin) 'OLD_CORS_SETTING_STILL_ALLOWED'
        $dbAfterSettings = Get-DatabaseIdentity
        $dbStartedAfterSettings = Invoke-DockerSafe -DockerArgs @('inspect', '--format', '{{.State.StartedAt}}', $dbAfterSettings.ContainerId)
        Assert-Check ($dbAfterSettings.ContainerId -eq $dbBeforeSettings.ContainerId -and $dbStartedAfterSettings -eq $dbStartedBeforeSettings -and $dbAfterSettings.VolumeName -eq $dbBeforeSettings.VolumeName) 'SETTINGS_CHANGED_DATABASE_EXECUTION'
        Assert-StoredRows -TicketId $ticket.id
        Assert-Check ((Invoke-SqlScalar 'SELECT count(*) = 7 AND bool_and(success) AND max(version::integer) = 7 FROM flyway_schema_history;') -eq 't') 'SETTINGS_CHANGED_MIGRATION_HISTORY'
        Assert-Check ($agent.Cookies.GetCookies([uri]$script:baseUrl)['JSESSIONID'].Value -eq $oldAgentSessionId) 'SETTINGS_CLIENT_OLD_COOKIE_NOT_PRESERVED'
        $oldSessionQuery = Invoke-HttpSafe -Client $agent.Client -Method GET -Path ("/api/tickets/" + $ticket.id)
        Assert-Check ($oldSessionQuery.Status -eq 401 -and -not $oldSessionQuery.LocationExists) 'SETTINGS_OLD_SESSION_AUTHENTICATED'
        $null = Login-Local -Session $agent -Username $env:HELPDESK_LOCAL_AGENT_USERNAME -Password $env:HELPDESK_LOCAL_AGENT_PASSWORD
        $queryAfterSettings = Invoke-HttpSafe -Client $agent.Client -Method GET -Path ("/api/tickets/" + $ticket.id)
        Assert-Check ($queryAfterSettings.Status -eq 200 -and ($queryAfterSettings.Body | ConvertFrom-Json).id -eq $ticket.id) 'SETTINGS_TICKET_QUERY_FAILED'
        $report.applicationSettingsChanged = $true
        $report.applicationRestarted = $true
        $report.applicationContainerRecreated = $true
        $report.oldSettingRetainedAfterStopStart = $true
        $report.newSettingAppliedAfterRecreate = $true
        $report.sameImageAfterSettingChange = $true
        $report.databaseProcessUnchangedDuringSettingsChange = $true
        $report.sameVolumeAndRowsAfterSettingsChange = $true
        $report.preflightOldOriginBeforeStatus = $oldAllowed.Status
        $report.preflightNewOriginBeforeStatus = $newRejected.Status
        $report.preflightOldOriginAfterStopStartStatus = $oldStillAllowed.Status
        $report.preflightNewOriginAfterStopStartStatus = $newStillRejected.Status
        $report.preflightOldOriginAfterRecreateStatus = $oldRejected.Status
        $report.preflightNewOriginAfterRecreateStatus = $newAllowed.Status
        $report.oldSessionQueryAfterSettingsChangeStatus = $oldSessionQuery.Status
        $report.agentQueryAfterSettingsChangeStatus = $queryAfterSettings.Status
    }
    $phase = 'LOG_SECRET_CHECK'
    $logs = Invoke-ComposeSafe -ComposeArgs @('logs', '--no-color', '--no-log-prefix')
    foreach ($name in @('HELPDESK_DB_ADMIN_PASSWORD', 'HELPDESK_DB_APP_PASSWORD', 'HELPDESK_LOCAL_USER_PASSWORD', 'HELPDESK_LOCAL_AGENT_PASSWORD')) {
        Assert-Check (-not $logs.Contains([Environment]::GetEnvironmentVariable($name, 'Process'))) 'GENERATED_PASSWORD_FOUND_IN_LOG'
    }
    foreach ($sessionSecret in $observedSessionSecrets) {
        Assert-Check (-not $logs.Contains($sessionSecret)) 'SESSION_SECRET_FOUND_IN_LOG'
    }
    $report.generatedSecretValueMatchesInLogs = 0
    $report.outcome = 'PASS'
}
catch {
    # 예외 Message에는 Response나 설정값이 포함될 수 있으므로 고정 코드만 출력한다.
    $report.outcome = 'FAIL'
    $report.failedPhase = $phase
    $report.failureCode = 'VERIFICATION_FAILED'
}
finally {
    foreach ($client in $clients) { $client.Dispose() }
    if ($composeStarted) {
        try { $null = Invoke-ComposeSafe -ComposeArgs @('stop', '--timeout', '20') }
        catch { $cleanupSucceeded = $false }
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        if (-not [string]::Equals([Environment]::GetEnvironmentVariable($name, 'Process'), $previousEnvironment[$name], [StringComparison]::Ordinal)) {
            $environmentRestored = $false
        }
    }
}

$report.environmentRestored = $environmentRestored
$report.containersStopped = $composeStarted -and $cleanupSucceeded
$report.volumeDeleted = $false
$report | ConvertTo-Json -Depth 5
if ($report.outcome -ne 'PASS' -or -not $cleanupSucceeded -or -not $environmentRestored) { exit 1 }
