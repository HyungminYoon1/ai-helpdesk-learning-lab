param(
    [string]$RuntimeImage = 'helpdesk:week8-rds-tls-preflight',
    [ValidateRange(0.25, 2.0)][double]$CpuLimit = 1.0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runId = [Guid]::NewGuid().ToString('N')
$artifacts = Join-Path $repoRoot "target/week8-linux-process-$runId"
$dependencies = Join-Path $artifacts 'dependencies'
$mainClasses = Join-Path $repoRoot 'target/classes'
$testClasses = Join-Path $repoRoot 'target/test-classes'
$cpuArgument = $CpuLimit.ToString('0.##', [Globalization.CultureInfo]::InvariantCulture)
$containerId = $null
$sourceCommit = $null
$workingTreeDirty = $true
$transportSha256 = $null
$imageId = $null
$suite = $null
$exitCode = $null
$oomKilled = $null
$failureCode = $null
$cleanupComplete = $false
$elapsed = [Diagnostics.Stopwatch]::new()
[IO.Directory]::CreateDirectory($artifacts) | Out-Null

Push-Location -LiteralPath $repoRoot
try {
    $sourceCommit = (& git rev-parse HEAD).Trim()
    $workingTreeDirty = [bool](& git status --porcelain)
    $transportSha256 = (Get-FileHash -LiteralPath 'src/main/java/lab/helpdesk/ai/input/ProcessAiPrivacyScanner.java' -Algorithm SHA256).Hash.ToLowerInvariant()
    $imageId = & docker image inspect --format '{{.Id}}' $RuntimeImage 2>$null
    if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:[a-f0-9]{64}$') { throw 'LINUX_PROCESS_RUNTIME_IMAGE_REQUIRED' }
    $osType = & docker image inspect --format '{{.Os}}' $imageId 2>$null
    if ($LASTEXITCODE -ne 0 -or $osType -ne 'linux') { throw 'LINUX_PROCESS_LINUX_IMAGE_REQUIRED' }

    # Compile current code and copy only public test JARs, never the caller's .m2 settings or secrets.
    $mavenOutput = @(& .\mvnw.cmd -B -ntp -DskipTests test-compile dependency:copy-dependencies `
        '-DincludeScope=test' "-DoutputDirectory=$dependencies" 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'LINUX_PROCESS_TEST_COMPILATION_FAILED' }
    $mavenOutput = $null

    $created = @(& docker create --name "helpdesk-linux-process-$runId" `
        --label 'lab.helpdesk.experiment=linux-process-transport' `
        --read-only --network none --user '10001:10001' --workdir /tmp `
        --cap-drop ALL --security-opt no-new-privileges --pids-limit 128 `
        --memory 512m --memory-swap 512m --cpus $cpuArgument `
        --tmpfs '/tmp:rw,nosuid,nodev,size=32m' `
        --mount "type=bind,source=$mainClasses,target=/opt/helpdesk/main,readonly" `
        --mount "type=bind,source=$testClasses,target=/opt/helpdesk/tests,readonly" `
        --mount "type=bind,source=$dependencies,target=/opt/helpdesk/dependencies,readonly" `
        --entrypoint java $imageId '-Xms32m' '-Xmx128m' '-XX:ActiveProcessorCount=1' `
        '-XX:+UseSerialGC' '-XX:-UsePerfData' '-Dfile.encoding=UTF-8' `
        '-cp' '/opt/helpdesk/main:/opt/helpdesk/tests:/opt/helpdesk/dependencies/*' `
        'experiment.helpdesk.process.LinuxProcessTransportSuite' 2>$null)
    if ($LASTEXITCODE -ne 0 -or $created.Count -ne 1 -or $created[0] -notmatch '^[a-f0-9]{64}$') {
        throw 'LINUX_PROCESS_CONTAINER_CREATE_FAILED'
    }
    $containerId = [string]$created[0]
    $constraints = (& docker inspect --format '{{.HostConfig.Memory}} {{.HostConfig.MemorySwap}} {{.HostConfig.NanoCpus}} {{.HostConfig.PidsLimit}} {{.HostConfig.ReadonlyRootfs}} {{.HostConfig.NetworkMode}} {{.Config.User}}' $containerId).Split(' ')
    if ($LASTEXITCODE -ne 0 -or $constraints.Count -ne 7 -or $constraints[0] -ne '536870912' `
        -or $constraints[1] -ne '536870912' -or [long]$constraints[2] -ne [long]($CpuLimit * 1e9) `
        -or $constraints[3] -ne '128' -or $constraints[4] -ne 'true' `
        -or $constraints[5] -ne 'none' -or $constraints[6] -ne '10001:10001') {
        throw 'LINUX_PROCESS_CONSTRAINTS_NOT_APPLIED'
    }
    $elapsed.Start()
    $startOutput = & docker start $containerId 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'LINUX_PROCESS_CONTAINER_START_FAILED' }
    do {
        $running = & docker inspect --format '{{.State.Running}}' $containerId 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'LINUX_PROCESS_STATE_UNAVAILABLE' }
        if ($running -eq 'false') { break }
        if ($elapsed.Elapsed.TotalSeconds -ge 120) { throw 'LINUX_PROCESS_OUTER_TIMEOUT' }
        Start-Sleep -Milliseconds 200
    } while ($true)
    $elapsed.Stop()
    $exitCode = [int](& docker inspect --format '{{.State.ExitCode}}' $containerId)
    $oomKilled = (& docker inspect --format '{{.State.OOMKilled}}' $containerId) -eq 'true'
    $logs = @(& docker logs $containerId 2>&1)
    $resultLines = @($logs | Where-Object { $_ -is [string] -and $_.StartsWith('LINUX_PROCESS_TEST_RESULT ') })
    if ($resultLines.Count -ne 1) { throw 'LINUX_PROCESS_SAFE_RESULT_MISSING' }
    $suite = $resultLines[0].Substring('LINUX_PROCESS_TEST_RESULT '.Length) | ConvertFrom-Json
    if ($exitCode -ne 0 -or $oomKilled -or -not $suite.passed) { throw 'LINUX_PROCESS_SUITE_FAILED' }
} catch {
    $failureCode = if ($_.Exception.Message -match '^LINUX_PROCESS_[A-Z_]+$') {
        $_.Exception.Message
    } else { 'LINUX_PROCESS_PROBE_FAILED' }
} finally {
    if ($null -ne $containerId) {
        $running = & docker inspect --format '{{.State.Running}}' $containerId 2>$null
        if ($LASTEXITCODE -eq 0 -and $running -eq 'true') {
            $stopOutput = & docker kill --signal KILL $containerId 2>&1
            $waitOutput = & docker wait $containerId 2>&1
        }
        # This exact ID was created above; no image, volume or unrelated container is removed.
        $removeOutput = & docker rm $containerId 2>&1
        $cleanupComplete = $LASTEXITCODE -eq 0
    } else { $cleanupComplete = $true }
    Pop-Location
}

$report = [ordered]@{
    evidence = 'LOCAL_LINUX_PROCESS_TRANSPORT'
    sourceCommit = $sourceCommit
    workingTreeDirty = $workingTreeDirty
    transportSha256 = $transportSha256
    runtimeImageId = $imageId
    cpuLimit = $CpuLimit
    memoryLimitMiB = 512
    network = 'none'
    user = '10001:10001'
    exitCode = $exitCode
    oomKilled = $oomKilled
    elapsedMs = [Math]::Round($elapsed.Elapsed.TotalMilliseconds)
    suite = $suite
    providerCalls = 0
    postgresUsed = $false
    springApplicationStarted = $false
    experimentContainerRemoved = $cleanupComplete
    failureCode = $failureCode
}
$json = ($report | ConvertTo-Json -Depth 6) -replace "`r`n", "`n"
[IO.File]::WriteAllText((Join-Path $artifacts 'results.json'), $json + "`n", [Text.UTF8Encoding]::new($false))
$json
if ($null -ne $failureCode -or -not $cleanupComplete) { throw 'LINUX_PROCESS_VERIFICATION_FAILED' }
