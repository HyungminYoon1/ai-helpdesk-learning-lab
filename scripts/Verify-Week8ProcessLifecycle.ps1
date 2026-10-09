param(
    [string]$Image = 'helpdesk:week8-observation'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$experimentRoot = Split-Path -Parent $PSScriptRoot
$testClasses = Join-Path $experimentRoot 'target/test-classes'
$probeClass = Join-Path $testClasses 'experiment/helpdesk/process/ContainerLifecycleExperiment.class'
if (-not (Test-Path -LiteralPath $probeClass -PathType Leaf)) {
    throw 'LIFECYCLE_PROBE_NOT_COMPILED'
}
$imageId = & docker image inspect --format '{{.Id}}' $Image 2>$null
if ($LASTEXITCODE -ne 0) { throw 'LIFECYCLE_IMAGE_NOT_FOUND' }

$ownedContainers = [System.Collections.Generic.List[string]]::new()
$caseResults = [System.Collections.Generic.List[object]]::new()
$cleanupComplete = $true
$httpClient = [System.Net.Http.HttpClient]::new()
$httpClient.Timeout = [TimeSpan]::FromSeconds(45)
try {
    foreach ($terminationMode in @('SIGTERM', 'SIGTERM_TIMEOUT', 'SIGKILL')) {
        $containerName = 'helpdesk-lifecycle-' + [Guid]::NewGuid().ToString('N')
        $ownedContainers.Add($containerName)
        $runOutput = & docker run --detach --name $containerName --read-only `
            --tmpfs '/tmp:rw,nosuid,size=64m' `
            --mount "type=bind,source=$testClasses,target=/opt/probe,readonly" `
            --publish '127.0.0.1::8080' --entrypoint java $Image `
            '-Dloader.main=experiment.helpdesk.process.ContainerLifecycleExperiment' `
            '-Dloader.path=/opt/probe' '-cp' '/app/helpdesk.jar' `
            'org.springframework.boot.loader.launch.PropertiesLauncher' 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'LIFECYCLE_CONTAINER_START_FAILED' }
        $actualImageId = & docker inspect --format '{{.Image}}' $containerName 2>$null
        if ($LASTEXITCODE -ne 0 -or $actualImageId -ne $imageId) {
            throw 'LIFECYCLE_IMAGE_CHANGED'
        }
        $hostPort = & docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' $containerName 2>$null
        if ($LASTEXITCODE -ne 0 -or $hostPort -notmatch '^\d+$') {
            throw 'LIFECYCLE_PORT_LOOKUP_FAILED'
        }
        $baseUri = "http://127.0.0.1:$hostPort"
        $ready = $false
        $startupTimer = [System.Diagnostics.Stopwatch]::StartNew()
        while ($startupTimer.Elapsed.TotalSeconds -lt 45) {
            try {
                $healthResponse = $httpClient.GetAsync("$baseUri/actuator/health").GetAwaiter().GetResult()
                try { $ready = [int]$healthResponse.StatusCode -eq 200 }
                finally { $healthResponse.Dispose() }
            } catch { $ready = $false }
            if ($ready) { break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $ready) { throw 'LIFECYCLE_HEALTH_TIMEOUT' }

        $processStatus = @(& docker exec $containerName sh -c 'while IFS= read -r line; do case "$line" in Name:*|State:*|Pid:*|PPid:*|Threads:*|VmRSS:*) printf "%s\n" "$line";; esac; done < /proc/1/status' 2>$null)
        if ($LASTEXITCODE -ne 0 -or -not ($processStatus -match '^Name:\s+java$') `
            -or -not ($processStatus -match '^Pid:\s+1$')) {
            throw 'LIFECYCLE_JAVA_PID_ONE_NOT_CONFIRMED'
        }

        $requestTask = $httpClient.GetAsync("$baseUri/__experiment/slow")
        $requestStarted = $false
        $markerTimer = [System.Diagnostics.Stopwatch]::StartNew()
        while ($markerTimer.Elapsed.TotalSeconds -lt 10) {
            $probeLogs = @(& docker logs $containerName 2>&1)
            $requestStarted = [bool]($probeLogs -match '^LIFECYCLE_PROBE_REQUEST_STARTED$')
            if ($requestStarted) { break }
            Start-Sleep -Milliseconds 50
        }
        if (-not $requestStarted) { throw 'LIFECYCLE_REQUEST_NOT_STARTED' }
        if ($requestTask.IsCompleted) { throw 'LIFECYCLE_REQUEST_ALREADY_COMPLETED' }

        $shutdownTimer = [System.Diagnostics.Stopwatch]::StartNew()
        if ($terminationMode -eq 'SIGTERM') {
            $signalOutput = & docker stop --time 30 $containerName 2>&1
        } elseif ($terminationMode -eq 'SIGTERM_TIMEOUT') {
            $signalOutput = & docker stop --time 1 $containerName 2>&1
        } else {
            $signalOutput = & docker kill --signal KILL $containerName 2>&1
        }
        if ($LASTEXITCODE -ne 0) { throw 'LIFECYCLE_SIGNAL_FAILED' }
        $waitOutput = & docker wait $containerName 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'LIFECYCLE_WAIT_FAILED' }
        $shutdownTimer.Stop()
        $stateJson = & docker inspect --format '{{json .State}}' $containerName 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'LIFECYCLE_STATE_LOOKUP_FAILED' }
        $state = $stateJson | ConvertFrom-Json
        $responseStatus = $null
        $syntheticRequestCompleted = $false
        try {
            $slowResponse = $requestTask.GetAwaiter().GetResult()
            try {
                $responseStatus = [int]$slowResponse.StatusCode
                $slowBody = $slowResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $syntheticRequestCompleted = $responseStatus -eq 200 `
                    -and $slowBody -eq 'SYNTHETIC_REQUEST_COMPLETED'
            } finally { $slowResponse.Dispose() }
        } catch { $syntheticRequestCompleted = $false }

        # OpenJDK on Linux exits with 128 + SIGTERM (15), even after shutdown hooks finish.
        $expected = if ($terminationMode -eq 'SIGTERM') {
            $state.ExitCode -eq 143 -and $syntheticRequestCompleted
        } else {
            $state.ExitCode -eq 137 -and -not $syntheticRequestCompleted -and $null -eq $responseStatus
        }
        $caseResults.Add([pscustomobject]@{
            terminationMode = $terminationMode
            initialSignal = if ($terminationMode -eq 'SIGKILL') { 'SIGKILL' } else { 'SIGTERM' }
            javaPidOne = $true
            processStatus = $processStatus
            requestStartedBeforeSignal = $requestStarted
            requestInFlightAtSignal = $true
            requestCompleted = $syntheticRequestCompleted
            httpStatus = $responseStatus
            exitCode = $state.ExitCode
            oomKilled = $state.OOMKilled
            shutdownMs = [Math]::Round($shutdownTimer.Elapsed.TotalMilliseconds)
            passed = $expected -and -not $state.OOMKilled
        })
    }
} finally {
    $httpClient.Dispose()
    foreach ($ownedContainer in $ownedContainers) {
        $running = & docker inspect --format '{{.State.Running}}' $ownedContainer 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        if ($running -eq 'true') {
            $cleanupStop = & docker stop --time 5 $ownedContainer 2>&1
        }
        $cleanupRemove = & docker rm $ownedContainer 2>&1
        if ($LASTEXITCODE -ne 0) { $cleanupComplete = $false }
    }
}

$report = [pscustomobject]@{
    evidence = 'LOCAL_CONTAINER_PROCESS_LIFECYCLE'
    image = $Image
    sameImage = $true
    cases = $caseResults.ToArray()
    providerCalls = 0
    postgresUsed = $false
    browserE2e = $false
    testOnlyEndpointMountedAtRuntime = $true
    experimentContainersRemoved = $cleanupComplete
}
$reportDirectory = Join-Path $experimentRoot 'target/week8-process-lifecycle'
[System.IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
$reportPath = Join-Path $reportDirectory 'results.json'
[System.IO.File]::WriteAllText($reportPath, (($report | ConvertTo-Json -Depth 6) -replace "`r`n", "`n") + "`n", [System.Text.UTF8Encoding]::new($false))
$report | ConvertTo-Json -Depth 6
if ($caseResults.Count -ne 3 -or -not $cleanupComplete -or ($caseResults | Where-Object { -not $_.passed })) {
    throw 'LIFECYCLE_EXPECTATION_FAILED'
}
