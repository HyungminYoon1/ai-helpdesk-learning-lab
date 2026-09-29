#requires -Version 7

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$containerName = "helpdesk-week6-$runId"
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) "helpdesk-week6-$runId"
$apiPort = 18081
$crossOriginPort = 18082
$databasePort = 15432
$apiBase = "http://127.0.0.1:$apiPort"
$crossOriginBase = "http://127.0.0.1:$crossOriginPort"
$containerId = $null
$appLauncher = $null
$appJavaPid = $null
$crossOriginServer = $null
$browserSessions = [System.Collections.Generic.List[string]]::new()

function Assert-PortFree([int]$port) {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $port is already in use"
    }
}

function Wait-HttpOk([string]$url) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $url -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Local server did not become ready"
}

function Invoke-Cli([string]$session, [string[]]$cliArguments) {
    Push-Location -LiteralPath $scratch
    try {
        $result = & npx --yes --package '@playwright/cli@0.1.21' playwright-cli --session $session @cliArguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            $errorType = 'Unknown'
            if (($result -join "`n") -match '\b(TimeoutError|ReferenceError|TypeError|TargetClosedError|SyntaxError)\b') {
                $errorType = $Matches[1]
            }
            throw "Browser step failed: $($cliArguments[0]) ($errorType)"
        }
        return ($result -join "`n")
    } finally {
        Pop-Location
    }
}

function Run-BrowserCode([string]$session, [string]$code) {
    $singleLineCode = $code -replace '\r?\n', ' '
    $browserFunction = "async (page) => { $singleLineCode }"
    [void](Invoke-Cli $session @('run-code', $browserFunction))
}

function Start-App {
    $stdout = Join-Path $scratch "spring-$([Guid]::NewGuid().ToString('N')).out.log"
    $stderr = Join-Path $scratch "spring-$([Guid]::NewGuid().ToString('N')).err.log"
    $script:appLauncher = Start-Process `
        -FilePath (Join-Path $repoRoot 'mvnw.cmd') `
        -ArgumentList @('spring-boot:run', '-Dspring-boot.run.profiles=postgres,local-browser') `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru

    Wait-HttpOk "$apiBase/tickets.html"
    $listener = Get-NetTCPConnection -LocalPort $apiPort -State Listen -ErrorAction Stop |
        Select-Object -First 1
    $script:appJavaPid = $listener.OwningProcess
}

function Stop-App {
    if ($script:appJavaPid) {
        $javaProcess = Get-Process -Id $script:appJavaPid -ErrorAction SilentlyContinue
        if ($javaProcess -and $javaProcess.ProcessName -eq 'java') {
            Stop-Process -Id $script:appJavaPid -Force
        }
        $script:appJavaPid = $null
    }
    if ($script:appLauncher -and -not $script:appLauncher.HasExited) {
        Stop-Process -Id $script:appLauncher.Id -Force
    }
    $script:appLauncher = $null
}

function Query-Database([string]$sql) {
    $result = & docker exec $containerId psql -U postgres -d postgres -tAc $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL query failed'
    }
    return ($result | Select-Object -Last 1).ToString().Trim()
}

function Login-Browser([string]$session, [string]$role) {
    $browserSessions.Add($session)
    [void](Invoke-Cli $session @('open', "$apiBase/login"))
    if ($role -eq 'AGENT') {
        $username = $env:HELPDESK_LOCAL_AGENT_USERNAME
        $password = $env:HELPDESK_LOCAL_AGENT_PASSWORD
    } else {
        $username = $env:HELPDESK_LOCAL_USER_USERNAME
        $password = $env:HELPDESK_LOCAL_USER_PASSWORD
    }

    # These credentials exist only for this disposable local E2E run; never print the CLI input.
    $code = "await page.locator('input[name=username]').fill('$username'); await page.locator('input[name=password]').fill('$password'); await page.locator('button[type=submit]').click();"
    Run-BrowserCode $session $code
    Run-BrowserCode $session "const status = await page.evaluate(async () => (await fetch('/api/csrf', { credentials: 'same-origin' })).status); if (status !== 200) throw new Error('LOGIN_FAILED');"
    Write-Output "$role LOGIN_SESSION=PASS"
}

function Remove-ScratchFiles {
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
    $target = [System.IO.Path]::GetFullPath($scratch)
    if (-not $target.StartsWith($tempRoot + '\', [System.StringComparison]::OrdinalIgnoreCase) -or
        -not ([System.IO.Path]::GetFileName($target)).StartsWith('helpdesk-week6-')) {
        throw 'Scratch path verification failed'
    }
    if (-not (Test-Path -LiteralPath $target)) {
        return
    }

    foreach ($file in Get-ChildItem -LiteralPath $target -Recurse -File) {
        if (-not $file.FullName.StartsWith($target + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Scratch file escaped target directory'
        }
        Remove-Item -LiteralPath $file.FullName -Force
    }
    foreach ($directory in (Get-ChildItem -LiteralPath $target -Recurse -Directory |
        Sort-Object FullName -Descending)) {
        if (-not $directory.FullName.StartsWith($target + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Scratch directory escaped target directory'
        }
        Remove-Item -LiteralPath $directory.FullName
    }
    Remove-Item -LiteralPath $target
}

Assert-PortFree $apiPort
Assert-PortFree $crossOriginPort
Assert-PortFree $databasePort
[void](New-Item -ItemType Directory -Path $scratch)

$temporaryEnvironmentNames = @(
    'POSTGRES_PASSWORD',
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'SERVER_PORT',
    'HELPDESK_LOCAL_USER_USERNAME',
    'HELPDESK_LOCAL_USER_PASSWORD',
    'HELPDESK_LOCAL_AGENT_USERNAME',
    'HELPDESK_LOCAL_AGENT_PASSWORD',
    'HELPDESK_LOCAL_CORS_ALLOWED_ORIGIN',
    'HELPDESK_CROSS_ORIGIN_PORT'
)
$originalEnvironment = @{}
foreach ($name in $temporaryEnvironmentNames) {
    $originalEnvironment[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process')
}

$env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$databasePort/postgres"
$env:SPRING_DATASOURCE_USERNAME = 'postgres'
$env:SPRING_DATASOURCE_PASSWORD = $env:POSTGRES_PASSWORD
$env:SERVER_PORT = [string]$apiPort
$env:HELPDESK_LOCAL_USER_USERNAME = "user-$runId"
$env:HELPDESK_LOCAL_USER_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:HELPDESK_LOCAL_AGENT_USERNAME = "agent-$runId"
$env:HELPDESK_LOCAL_AGENT_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:HELPDESK_LOCAL_CORS_ALLOWED_ORIGIN = $crossOriginBase
$env:HELPDESK_CROSS_ORIGIN_PORT = [string]$crossOriginPort

try {
    $dockerOutput = & docker run --rm -d --name $containerName `
        -e POSTGRES_PASSWORD `
        -p "127.0.0.1:${databasePort}:5432" `
        postgres:17.6-alpine 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'Disposable PostgreSQL container did not start'
    }
    $containerId = $dockerOutput | Where-Object { $_ -match '^[0-9a-f]{64}$' } |
        Select-Object -First 1
    if (-not $containerId) {
        throw 'Disposable PostgreSQL container ID is missing'
    }

    $databaseReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $containerId pg_isready -U postgres 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $databaseReady = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $databaseReady) {
        throw 'Disposable PostgreSQL did not become ready'
    }

    $node = (Get-Command node -ErrorAction Stop).Source
    $crossOriginServer = Start-Process `
        -FilePath $node `
        -ArgumentList (Join-Path $repoRoot 'src/test/js/cross-origin-probe.mjs') `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -PassThru
    Wait-HttpOk "$crossOriginBase/"

    Start-App
    $firstAppPid = $appJavaPid
    if ((Query-Database "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true") -ne '1') {
        throw 'Flyway V1 was not applied'
    }
    Write-Output 'EMPTY_DATABASE_MIGRATION=PASS'

    $agentSession = "week6-agent-$runId"
    Login-Browser $agentSession 'AGENT'
    [void](Invoke-Cli $agentSession @('goto', "$apiBase/tickets.html"))
    Run-BrowserCode $agentSession "await page.locator('#ticket-title-input').fill('<b>Week6</b>'); await page.locator('#create-button').click(); await page.waitForFunction(() => document.querySelector('#ui-status')?.textContent === 'Ticket을 생성했습니다.');"
    $ticketId = Query-Database "SELECT id FROM tickets WHERE title = '<b>Week6</b>' ORDER BY id DESC LIMIT 1"
    if ($ticketId -notmatch '^[1-9][0-9]*$' -or (Query-Database 'SELECT COUNT(*) FROM tickets') -ne '1') {
        throw 'Browser creation did not produce exactly one PostgreSQL row'
    }
    Run-BrowserCode $agentSession "const safe = await page.evaluate(() => document.querySelector('#ticket-title').textContent === '<b>Week6</b>' && document.querySelector('#ticket-detail b') === null); if (!safe) throw new Error('TEXT_CONTENT_FAILED');"
    Run-BrowserCode $agentSession "await page.locator('#ticket-list button[data-action=open] span').click(); await page.waitForFunction(() => document.querySelector('#ui-status')?.textContent === 'Ticket을 조회했습니다.');"
    Write-Output 'BROWSER_SESSION_CSRF_POSTGRES_CREATE_READ_AND_SAFE_DOM=PASS'

    [void](Invoke-Cli $agentSession @('goto', "$crossOriginBase/"))
    $crossOriginCode = @'
const cdp = await page.context().newCDPSession(page);
await cdp.send('Network.enable');
const methods = [];
cdp.on('Network.requestWillBeSent', event => {
    if (event.request.url.endsWith('/api/tickets')) methods.push(event.request.method);
});
const result = await page.evaluate(async () => {
    const csrfResponse = await fetch('http://127.0.0.1:18081/api/csrf', { credentials: 'include' });
    if (csrfResponse.status !== 200) return csrfResponse.status;
    const csrf = await csrfResponse.json();
    const response = await fetch('http://127.0.0.1:18081/api/tickets', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ title: 'Cross-Origin Week6' })
    });
    return response.status;
});
await cdp.detach();
return methods.join(',') + ':' + result;
'@
    $crossSingleLineCode = $crossOriginCode -replace '\r?\n', ' '
    $crossOutput = Invoke-Cli $agentSession @('run-code', "async (page) => { $crossSingleLineCode }")
    if ($crossOutput -notmatch '([A-Z,]*):([0-9]{3})') {
        throw 'Cross-origin POST returned no readable method/status evidence'
    }
    $crossMethods = $Matches[1]
    $crossStatus = $Matches[2]
    Write-Output "CROSS_ORIGIN_POST_STATUS=$crossStatus"
    if ($crossStatus -ne '201') {
        throw 'Cross-origin POST did not return 201'
    }
    if (($crossMethods -split ',') -notcontains 'OPTIONS' -or
        ($crossMethods -split ',') -notcontains 'POST') {
        throw 'Cross-origin OPTIONS and POST were not both observed'
    }
    Write-Output 'CROSS_ORIGIN_METHODS_PRESENT=OPTIONS,POST'
    if ((Query-Database 'SELECT COUNT(*) FROM tickets') -ne '2') {
        throw 'Cross-origin POST did not produce a PostgreSQL row'
    }
    Write-Output 'CREDENTIAL_CORS_PREFLIGHT_POST_AND_POSTGRES_ROW=PASS'

    [void](Invoke-Cli $agentSession @('close'))
    Stop-App
    Start-App
    if ($appJavaPid -eq $firstAppPid) {
        throw 'Java process was not recreated'
    }
    if ((Query-Database 'SELECT COUNT(*) FROM tickets') -ne '2') {
        throw 'PostgreSQL rows did not survive Application process restart'
    }

    $restartSession = "week6-restart-$runId"
    Login-Browser $restartSession 'AGENT'
    [void](Invoke-Cli $restartSession @('goto', "$apiBase/tickets.html"))
    Run-BrowserCode $restartSession "await page.locator('#ticket-id-input').fill('$ticketId'); await page.locator('#read-button').click(); await page.waitForFunction(() => document.querySelector('#ui-status')?.textContent === 'Ticket을 조회했습니다.');"
    Write-Output 'NEW_JVM_PROCESS_SAME_POSTGRES_ROW=PASS'

    $userSession = "week6-user-$runId"
    Login-Browser $userSession 'USER'
    [void](Invoke-Cli $userSession @('goto', "$apiBase/tickets.html"))
    Run-BrowserCode $userSession "await page.locator('#ticket-id-input').fill('$ticketId'); await page.locator('#read-button').click(); await page.waitForFunction(() => document.querySelector('#ui-status')?.textContent === '이 작업에 대한 권한이 없습니다.');"
    Run-BrowserCode $userSession @'
const status = await page.evaluate(async () => (await fetch('/api/tickets', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'No CSRF Probe' })
})).status);
if (status !== 403) throw new Error('MISSING_CSRF_WAS_NOT_REJECTED');
'@
    if ((Query-Database 'SELECT COUNT(*) FROM tickets') -ne '2') {
        throw 'Rejected POST changed PostgreSQL row count'
    }
    Write-Output 'USER_READ_403_AND_MISSING_CSRF_POST_403=PASS'

    $anonymousSession = "week6-anonymous-$runId"
    $browserSessions.Add($anonymousSession)
    [void](Invoke-Cli $anonymousSession @('open', "$apiBase/tickets.html"))
    Run-BrowserCode $anonymousSession "await page.locator('#ticket-id-input').fill('$ticketId'); await page.locator('#read-button').click(); await page.waitForFunction(() => document.querySelector('#ui-status')?.textContent === '로그인이 필요합니다.');"
    Write-Output 'ANONYMOUS_READ_401=PASS'
    Write-Output 'WEEK6_BROWSER_E2E=PASS'
} finally {
    try {
        foreach ($session in $browserSessions) {
            try { [void](Invoke-Cli $session @('close')) } catch { }
        }
        Stop-App
        if ($crossOriginServer -and -not $crossOriginServer.HasExited) {
            Stop-Process -Id $crossOriginServer.Id -Force
        }
        if ($containerId) {
            & docker stop $containerId 2>&1 | Out-Null
        }
        Remove-ScratchFiles
    } finally {
        foreach ($name in $temporaryEnvironmentNames) {
            [System.Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
        }
    }
}
