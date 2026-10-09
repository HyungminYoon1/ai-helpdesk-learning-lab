#Requires -Version 7.0
[CmdletBinding()]
param(
    [string] $Image = 'helpdesk:week8-secret-probe'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$fixtureRoot = Join-Path $repoPath 'target/week8-secret-probe'
$runId = [Guid]::NewGuid().ToString('N')
$runPath = Join-Path $fixtureRoot $runId
$project = 'week8-secret-probe-' + $runId.Substring(0, 12)
$ownedContainers = [System.Collections.Generic.List[string]]::new()
$syntheticValues = [System.Collections.Generic.List[string]]::new()
$caseResults = [System.Collections.Generic.List[object]]::new()
$utf8 = [System.Text.UTF8Encoding]::new($false)
$childSettings = @{}
$stage = 'PREREQUISITES'
$passed = $false
$cleanupPassed = $true
$failureCode = $null

function Invoke-ProbeDocker {
    param([string[]] $DockerArguments)
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = 'docker'
    $start.WorkingDirectory = $repoPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $DockerArguments) { $start.ArgumentList.Add($argument) }
    # Child-only changes. Never read or alter the user's actual key values.
    foreach ($name in @('OPENAI_API_KEY', 'HELPDESK_OPENAI_API_KEY', 'HELPDESK_AI_PROVIDER_KEY',
            'COMPOSE_ENV_FILES')) { [void] $start.Environment.Remove($name) }
    $start.Environment['COMPOSE_DISABLE_ENV_FILE'] = 'true'
    foreach ($entry in $childSettings.GetEnumerator()) { $start.Environment[$entry.Key] = $entry.Value }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void] $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(60000)) {
            $process.Kill($true)
            throw [System.InvalidOperationException]::new('PROBE_DOCKER_TIMEOUT')
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $stdout.GetAwaiter().GetResult()
            ErrorOutput = $stderr.GetAwaiter().GetResult()
        }
    } finally { $process.Dispose() }
}

function Read-ProbeContainer {
    param([string] $Name)
    $inspection = Invoke-ProbeDocker -DockerArguments @('inspect', $Name)
    if ($inspection.ExitCode -ne 0) { throw [System.InvalidOperationException]::new('PROBE_INSPECT_FAILED') }
    $containers = @($inspection.Output | ConvertFrom-Json)
    $container = $containers[0]
    if ($container.Name.TrimStart('/') -ne $Name -or
        $container.Config.Labels.'com.docker.compose.project' -ne $project) {
        throw [System.InvalidOperationException]::new('PROBE_OWNERSHIP_FAILED')
    }
    return $container
}

try {
    if ($Image -notmatch '^[a-z0-9][a-z0-9/_.:-]+$') {
        throw [System.InvalidOperationException]::new('PROBE_IMAGE_INVALID')
    }
    $probeClass = Join-Path $repoPath 'target/test-classes/experiment/helpdesk/secret/ComposeSecretMountExperiment.class'
    if (-not (Test-Path -LiteralPath $probeClass -PathType Leaf)) {
        throw [System.InvalidOperationException]::new('PROBE_TEST_COMPILE_REQUIRED')
    }
    $imageInspection = Invoke-ProbeDocker -DockerArguments @('image', 'inspect', $Image)
    if ($imageInspection.ExitCode -ne 0) {
        throw [System.InvalidOperationException]::new('PROBE_IMAGE_BUILD_REQUIRED')
    }
    $images = @($imageInspection.Output | ConvertFrom-Json)
    $expectedImageId = $images[0].Id
    [void] [System.IO.Directory]::CreateDirectory($runPath)
    $childSettings['HELPDESK_SECRET_PROBE_IMAGE'] = $Image
    $cases = @(
        @{ Name = 'value-a'; Mode = 'PRESENT'; Service = 'probe'; Enabled = 'true'; Value = [Guid]::NewGuid().ToString() },
        @{ Name = 'value-b'; Mode = 'PRESENT'; Service = 'probe'; Enabled = 'true'; Value = [Guid]::NewGuid().ToString() },
        @{ Name = 'blank'; Mode = 'BLANK'; Service = 'probe'; Enabled = 'true'; Value = " `n`t" },
        @{ Name = 'missing'; Mode = 'MISSING'; Service = 'probe-no-secret'; Enabled = 'true'; Value = [Guid]::NewGuid().ToString() },
        @{ Name = 'disabled'; Mode = 'DISABLED'; Service = 'probe-no-secret'; Enabled = 'false'; Value = [Guid]::NewGuid().ToString() }
    )
    if ($cases[0].Value -eq $cases[1].Value) { throw [System.InvalidOperationException]::new('PROBE_FIXTURE_INVALID') }
    foreach ($case in $cases) {
        $stage = $case.Name
        $casePath = Join-Path $runPath $case.Name
        [void] [System.IO.Directory]::CreateDirectory($casePath)
        $keyFile = Join-Path $casePath 'helpdesk.ai.provider.key'
        [System.IO.File]::WriteAllText($keyFile, $case.Value, $utf8)
        $digest = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($utf8.GetBytes($case.Value))).ToLowerInvariant()
        [System.IO.File]::WriteAllText((Join-Path $casePath 'key.sha256'), $digest, $utf8)
        if ($case.Mode -eq 'PRESENT') { $syntheticValues.Add($case.Value) }
        $childSettings['HELPDESK_SECRET_PROBE_KEY_FILE'] = $keyFile
        $childSettings['HELPDESK_SECRET_PROBE_EXPECTATION_DIR'] = $casePath
        $childSettings['HELPDESK_SECRET_PROBE_CASE'] = $case.Mode
        $childSettings['HELPDESK_SECRET_PROBE_ENABLED'] = $case.Enabled
        $name = $project + '-' + $case.Name
        $ownedContainers.Add($name)
        $execution = Invoke-ProbeDocker -DockerArguments @('compose', '-f', 'compose.secret-probe.yaml',
            '-p', $project, 'run', '-T', '--no-deps', '--name', $name, $case.Service)
        foreach ($value in $syntheticValues) {
            if ($execution.Output.Contains($value) -or $execution.ErrorOutput.Contains($value)) {
                throw [System.InvalidOperationException]::new('PROBE_VALUE_LOGGED')
            }
        }
        $prefix = 'WEEK8_SECRET_PROBE_RESULT:'
        $lines = @($execution.Output -split '\r?\n' | Where-Object { $_.StartsWith($prefix) })
        if ($lines.Count -ne 1) { throw [System.InvalidOperationException]::new('PROBE_RESULT_MISSING') }
        $probe = $lines[0].Substring($prefix.Length) | ConvertFrom-Json
        $container = Read-ProbeContainer -Name $name
        $keyMounts = @($container.Mounts | Where-Object { $_.Destination -eq '/run/secrets/helpdesk.ai.provider.key' })
        $mountedAsExpected = if ($case.Service -eq 'probe') {
            $keyMounts.Count -eq 1 -and -not $keyMounts[0].RW
        } else { $keyMounts.Count -eq 0 }
        $noPublishedPorts = $null -eq $container.HostConfig.PortBindings -or
            @($container.HostConfig.PortBindings.PSObject.Properties).Count -eq 0
        $isolated = $container.HostConfig.NetworkMode -eq 'none' -and $container.HostConfig.ReadonlyRootfs -and
            $container.Config.User -eq '10001:10001' -and $noPublishedPorts
        $sameImage = $container.Image -eq $expectedImageId
        if ($execution.ExitCode -ne 0 -or $probe.outcome -ne 'PASS' -or
            -not $mountedAsExpected -or -not $isolated -or -not $sameImage) {
            throw [System.InvalidOperationException]::new('PROBE_ASSERTION_FAILED')
        }
        $caseResults.Add([pscustomobject]@{
            name = $case.Name
            secretGrantAsExpected = $mountedAsExpected
            sameImage = $sameImage
            isolated = $isolated
            probe = $probe
        })
    }
    $passed = $caseResults.Count -eq 5
} catch {
    # Fixed codes only. Arbitrary CLI/exception text can include secrets or payloads.
    $allowed = @('PROBE_DOCKER_TIMEOUT', 'PROBE_INSPECT_FAILED', 'PROBE_OWNERSHIP_FAILED',
        'PROBE_IMAGE_INVALID', 'PROBE_TEST_COMPILE_REQUIRED', 'PROBE_IMAGE_BUILD_REQUIRED',
        'PROBE_FIXTURE_INVALID', 'PROBE_VALUE_LOGGED', 'PROBE_RESULT_MISSING', 'PROBE_ASSERTION_FAILED')
    $failureCode = if ($allowed -contains $_.Exception.Message) { $_.Exception.Message } else { 'PROBE_RUNNER_FAILED' }
} finally {
    foreach ($name in $ownedContainers) {
        try {
            $inspection = Invoke-ProbeDocker -DockerArguments @('inspect', $name)
            if ($inspection.ExitCode -eq 0) {
                $container = Read-ProbeContainer -Name $name
                $removed = Invoke-ProbeDocker -DockerArguments @('rm', '-f', $container.Id)
                if ($removed.ExitCode -ne 0) { $cleanupPassed = $false }
            }
        } catch { $cleanupPassed = $false }
    }
    if (Test-Path -LiteralPath $runPath) {
        # Only the owned UUID directory below this repository's target is removable.
        $resolvedRoot = (Resolve-Path -LiteralPath $fixtureRoot).Path
        $resolvedRun = (Resolve-Path -LiteralPath $runPath).Path
        if ([System.IO.Path]::GetDirectoryName($resolvedRun) -eq $resolvedRoot -and
            [System.IO.Path]::GetFileName($resolvedRun) -eq $runId) {
            try { Remove-Item -LiteralPath $resolvedRun -Recurse -Force } catch { $cleanupPassed = $false }
        } else { $cleanupPassed = $false }
    }
}

$report = [ordered]@{
    evidence = 'SYNTHETIC_COMPOSE_SECRET_MOUNT_PROVIDER_WIRING'
    outcome = if ($passed -and $cleanupPassed) { 'PASS' } else { 'FAIL' }
    stage = $stage
    failureCode = $failureCode
    casesPassed = $caseResults.Count
    cases = $caseResults.ToArray()
    differentSyntheticValues = $caseResults.Count -eq 5
    sameImageForAllCases = $caseResults.Count -eq 5
    realCredentialsUsed = $false
    providerHttpCalls = if ($passed) { 0 } else { $null }
    databaseUsed = $false
    cleanupPassed = $cleanupPassed
}
[void] [System.IO.Directory]::CreateDirectory($fixtureRoot)
$reportPath = Join-Path $fixtureRoot ($project + '-report.json')
$reportJson = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($reportPath, $reportJson + "`n", $utf8)
Write-Output $reportJson
if (-not $passed -or -not $cleanupPassed) { exit 1 }
