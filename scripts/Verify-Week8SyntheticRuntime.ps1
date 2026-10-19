#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^[a-z0-9][a-z0-9/_.:-]+$')]
    [string] $Image = 'helpdesk:week8-synthetic-9f2074b',
    [ValidateSet(512, 1024)] [int] $MemoryMiB = 512,
    [ValidateSet(0.25, 0.5)] [double] $CpuLimit = 0.25
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$runId = [guid]::NewGuid().ToString('N')
$project = 'week8-synthetic-' + $runId.Substring(0, 12)
$outputRoot = Join-Path $repo 'target/week8-synthetic-runtime'
$runPath = Join-Path $outputRoot $runId
$secretsPath = Join-Path $runPath 'secrets'
$classesPath = Join-Path $runPath 'classes'
$settings = @{}
$syntheticValues = [System.Collections.Generic.List[string]]::new()
$ownedIds = [System.Collections.Generic.List[string]]::new()
$utf8 = [System.Text.UTF8Encoding]::new($false)
$compose = @('compose', '-f', 'compose.synthetic-runtime-probe.yaml', '-p', $project)
$stage = 'PREREQUISITES'
$cleanupPassed = $true
$passed = $false
$report = [ordered]@{ evidence = 'LOCAL_SYNTHETIC_AUTOMATIC_RUNTIME_RESOURCES'; realAiCalled = $false;
    awsChanged = $false; browserE2e = $false; childOnlyEnvironment = $true; volumeDeleted = $false }

function Invoke-ProbeDocker {
    param([string[]] $Arguments, [int] $TimeoutSeconds = 60)
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = 'docker'
    $start.WorkingDirectory = $repo
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('--context', 'desktop-linux') + $Arguments) { $start.ArgumentList.Add($argument) }
    foreach ($name in @('OPENAI_API_KEY', 'HELPDESK_OPENAI_API_KEY', 'HELPDESK_AI_PROVIDER_KEY',
            'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'COMPOSE_ENV_FILES')) {
        [void] $start.Environment.Remove($name)
    }
    $start.Environment['COMPOSE_DISABLE_ENV_FILE'] = 'true'
    foreach ($entry in $settings.GetEnumerator()) { $start.Environment[$entry.Key] = $entry.Value }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void] $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            throw 'PROBE_DOCKER_TIMEOUT'
        }
        return [pscustomobject]@{ ExitCode = $process.ExitCode;
            Output = $stdout.GetAwaiter().GetResult().Trim(); ErrorOutput = $stderr.GetAwaiter().GetResult().Trim() }
    } finally { $process.Dispose() }
}

function Docker-Value {
    param([string[]] $Arguments)
    $execution = Invoke-ProbeDocker -Arguments $Arguments
    if ($execution.ExitCode -ne 0) { throw 'PROBE_DOCKER_FAILED' }
    return $execution.Output
}

function Require-Probe {
    param([bool] $Condition)
    if (-not $Condition) { throw 'PROBE_ASSERTION_FAILED' }
}

function Synthetic-Value { return [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N') }

try {
    Require-Probe ($CpuLimit -ne 0.5 -or $MemoryMiB -eq 1024)
    Require-Probe ((Docker-Value @('info', '--format', '{{.OSType}} {{.OperatingSystem}}')) -match '^linux Docker Desktop')
    $imageId = Docker-Value @('image', 'inspect', '--format', '{{.Id}}', $Image)
    Require-Probe ($imageId -match '^sha256:[a-f0-9]{64}$')
    $report.imageId = $imageId
    $report.imageRevision = Docker-Value @('image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', $Image)
    [void] [System.IO.Directory]::CreateDirectory($secretsPath)
    [void] [System.IO.Directory]::CreateDirectory($classesPath)
    foreach ($relative in @('experiment/helpdesk/runtime', 'lab/helpdesk/ai/provider')) {
        [void] [System.IO.Directory]::CreateDirectory((Join-Path $classesPath $relative))
    }
    $launcherClasses = @(Get-ChildItem -LiteralPath (Join-Path $repo 'target/test-classes/experiment/helpdesk/runtime') -Filter 'SyntheticRuntimeResourceExperiment*.class' -File)
    Require-Probe ($launcherClasses.Count -ge 3)
    foreach ($file in $launcherClasses) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $classesPath 'experiment/helpdesk/runtime') }
    Copy-Item -LiteralPath (Join-Path $repo 'target/test-classes/lab/helpdesk/ai/provider/SyntheticRuntimeProviderBridge.class') -Destination (Join-Path $classesPath 'lab/helpdesk/ai/provider')
    $fixtures = @{
        'spring.datasource.password' = Synthetic-Value
        'helpdesk.deployment.user.username' = 'synthetic-runtime-user'
        'helpdesk.deployment.user.password' = Synthetic-Value
        'helpdesk.deployment.agent.username' = 'synthetic-runtime-agent'
        'helpdesk.deployment.agent.password' = Synthetic-Value
        'helpdesk.ai.provider.key' = Synthetic-Value
    }
    foreach ($entry in $fixtures.GetEnumerator()) {
        [System.IO.File]::WriteAllText((Join-Path $secretsPath $entry.Key), $entry.Value, $utf8)
        $syntheticValues.Add($entry.Value)
    }
    $settings['HELPDESK_RUNTIME_PROBE_IMAGE'] = $Image
    $settings['HELPDESK_RUNTIME_PROBE_DB_ADMIN'] = Synthetic-Value
    $settings['HELPDESK_RUNTIME_PROBE_DB_APP'] = $fixtures['spring.datasource.password']
    $settings['HELPDESK_RUNTIME_PROBE_CPUS'] = $CpuLimit.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $settings['HELPDESK_RUNTIME_PROBE_MEMORY'] = [string] $MemoryMiB
    $settings['HELPDESK_RUNTIME_PROBE_CLASSES'] = $classesPath
    $settings['HELPDESK_RUNTIME_PROBE_SECRETS'] = $secretsPath
    $null = Docker-Value ($compose + @('config', '--quiet'))
    $stage = 'CREATE_AND_VERIFY_ISOLATION'
    $null = Docker-Value ($compose + @('create', '--no-build'))
    foreach ($service in @('db', 'app')) {
        $id = Docker-Value ($compose + @('ps', '--all', '-q', $service))
        Require-Probe ($id -match '^[a-f0-9]{64}$')
        $ownedIds.Add($id)
        Require-Probe ((Docker-Value @('inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', $id)) -eq $project)
        $ports = Docker-Value @('inspect', '--format', '{{json .HostConfig.PortBindings}}', $id)
        Require-Probe ($ports -in @('null', '{}'))
        $networks = (Docker-Value @('inspect', '--format', '{{json .NetworkSettings.Networks}}', $id)) | ConvertFrom-Json
        Require-Probe (@($networks.PSObject.Properties).Count -eq 1 -and
            @($networks.PSObject.Properties)[0].Name -eq ($project + '_probe'))
    }
    Require-Probe ((Docker-Value @('network', 'inspect', '--format', '{{.Internal}} {{index .Labels "com.docker.compose.project"}}',
            ($project + '_probe'))) -eq ('true ' + $project))
    $appId = $ownedIds[1]
    $limits = (Docker-Value @('inspect', '--format', '{{.HostConfig.Memory}} {{.HostConfig.MemorySwap}} {{.HostConfig.NanoCpus}}', $appId)) -split ' '
    Require-Probe ([long]$limits[0] -eq $MemoryMiB * 1MB -and [long]$limits[1] -eq $MemoryMiB * 1MB -and
        [long]$limits[2] -eq [long]($CpuLimit * 1e9))
    Require-Probe ((Docker-Value @('inspect', '--format', '{{.Config.User}} {{.HostConfig.ReadonlyRootfs}} {{.HostConfig.PidsLimit}}',
            $appId)) -eq '10001:10001 true 128')
    Require-Probe ((Docker-Value @('inspect', '--format', '{{.Image}}', $appId)) -eq $imageId)
    $report.internalNetworkVerified = $true
    $report.publishedPorts = 0
    $report.cpuLimit = $CpuLimit
    $report.memoryLimitMiB = $MemoryMiB
    $report.databaseVolume = $project + '_postgres-data'
    $stage = 'START_DATABASE'
    $dbStart = Invoke-ProbeDocker -Arguments ($compose + @('up', '-d', '--no-build', '--wait', '--wait-timeout', '90', 'db')) -TimeoutSeconds 120
    Require-Probe ($dbStart.ExitCode -eq 0)
    $stage = 'RUN_AUTOMATIC_WORKER'
    $null = Docker-Value ($compose + @('start', 'app'))
    $wait = Invoke-ProbeDocker -Arguments @('wait', $appId) -TimeoutSeconds 240
    $state = (Docker-Value @('inspect', '--format', '{{.State.ExitCode}} {{.State.OOMKilled}}', $appId)) -split ' '
    $report.appExitCode = [int]$state[0]
    $report.appOomKilled = $state[1] -eq 'true'
    $logs = Invoke-ProbeDocker -Arguments @('logs', $appId)
    foreach ($value in $syntheticValues) {
        Require-Probe (-not $logs.Output.Contains($value) -and -not $logs.ErrorOutput.Contains($value))
    }
    $prefix = 'WEEK8_SYNTHETIC_RUNTIME_RESULT:'
    $lines = @($logs.Output -split '\r?\n' | Where-Object { $_.StartsWith($prefix) })
    Require-Probe ($lines.Count -eq 1)
    $probe = $lines[0].Substring($prefix.Length) | ConvertFrom-Json
    $report.probe = $probe
    Require-Probe ($wait.ExitCode -eq 0 -and $wait.Output -eq '0' -and $state[0] -eq '0' -and $state[1] -eq 'false' -and
        $probe.outcome -eq 'PASS' -and $probe.realAiCalled -eq $false -and $probe.mainClassesFromImageJar -eq $true)
    Require-Probe ($probe.beforeJobs.'memory.max' -eq $MemoryMiB * 1MB -and $probe.beforeJobs.'memory.swap.max' -eq 0)
    $passed = $true
} catch {
    $allowed = @('PROBE_DOCKER_TIMEOUT', 'PROBE_DOCKER_FAILED', 'PROBE_ASSERTION_FAILED')
    $report.failureCode = if ($allowed -contains $_.Exception.Message) { $_.Exception.Message } else { 'PROBE_RUNNER_FAILED' }
} finally {
    # Only this UUID Compose project. Never prune or delete unrelated containers/volumes.
    try {
        $remaining = Invoke-ProbeDocker -Arguments @('ps', '-aq', '--filter', ('label=com.docker.compose.project=' + $project))
        if ($remaining.ExitCode -eq 0) {
            foreach ($id in @($remaining.Output -split '\r?\n' | Where-Object { $_ -match '^[a-f0-9]+$' })) {
                if ((Docker-Value @('inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', $id)) -ne $project) {
                    throw 'PROBE_ASSERTION_FAILED'
                }
                $null = Docker-Value @('rm', '-f', $id)
            }
        } else { $cleanupPassed = $false }
        $network = Invoke-ProbeDocker -Arguments @('network', 'inspect', '--format', '{{index .Labels "com.docker.compose.project"}}', ($project + '_probe'))
        if ($network.ExitCode -eq 0 -and $network.Output -eq $project) { $null = Docker-Value @('network', 'rm', ($project + '_probe')) }
        if (Test-Path -LiteralPath $secretsPath) {
            $resolvedSecrets = (Resolve-Path -LiteralPath $secretsPath).Path
            $resolvedRun = (Resolve-Path -LiteralPath $runPath).Path
            Require-Probe ([System.IO.Path]::GetDirectoryName($resolvedSecrets) -eq $resolvedRun -and
                [System.IO.Path]::GetFileName($resolvedSecrets) -eq 'secrets' -and
                [System.IO.Path]::GetDirectoryName($resolvedRun) -eq (Resolve-Path -LiteralPath $outputRoot).Path -and
                [System.IO.Path]::GetFileName($resolvedRun) -eq $runId)
            Remove-Item -LiteralPath $resolvedSecrets -Recurse -Force
        }
    } catch { $cleanupPassed = $false }
}
$report.stage = $stage
$report.cleanupPassed = $cleanupPassed
$report.outcome = if ($passed -and $cleanupPassed) { 'PASS' } else { 'FAIL' }
[void] [System.IO.Directory]::CreateDirectory($runPath)
$json = $report | ConvertTo-Json -Depth 9
[System.IO.File]::WriteAllText((Join-Path $runPath 'results.json'), $json + "`n", $utf8)
Write-Output $json
if (-not $passed -or -not $cleanupPassed) { exit 1 }
