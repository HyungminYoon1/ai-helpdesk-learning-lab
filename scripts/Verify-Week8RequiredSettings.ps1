#Requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Validate interpolation only. Never print resolved settings or start Containers.
$taskRepoPath = Split-Path -Parent $PSScriptRoot
$taskComposePath = Join-Path $taskRepoPath 'compose.yaml'
$requiredSettings = [ordered]@{
    HELPDESK_DB_ADMIN_PASSWORD = 'DB admin password is required'
    HELPDESK_DB_APP_PASSWORD = 'DB app password is required'
    HELPDESK_LOCAL_USER_USERNAME = 'Local USER username is required'
    HELPDESK_LOCAL_USER_PASSWORD = 'Local USER password is required'
    HELPDESK_LOCAL_AGENT_USERNAME = 'Local AGENT username is required'
    HELPDESK_LOCAL_AGENT_PASSWORD = 'Local AGENT password is required'
}
$syntheticSettings = @{
    HELPDESK_DB_ADMIN_PASSWORD = [Guid]::NewGuid().ToString('N')
    HELPDESK_DB_APP_PASSWORD = [Guid]::NewGuid().ToString('N')
    HELPDESK_LOCAL_USER_USERNAME = 'week8-settings-user'
    HELPDESK_LOCAL_USER_PASSWORD = [Guid]::NewGuid().ToString('N')
    HELPDESK_LOCAL_AGENT_USERNAME = 'week8-settings-agent'
    HELPDESK_LOCAL_AGENT_PASSWORD = [Guid]::NewGuid().ToString('N')
}

function Invoke-ComposeConfig {
    param(
        [string]$VariableName,
        [ValidateSet('Baseline', 'Absent', 'Empty')][string]$Case
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = (Get-Command docker -CommandType Application | Select-Object -First 1).Source
    $startInfo.WorkingDirectory = $taskRepoPath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @(
        'compose', '--project-directory', $taskRepoPath,
        '-f', $taskComposePath, '-p', 'helpdesk-week8-settings-check', 'config', '--quiet'
    )) {
        $startInfo.ArgumentList.Add($argument)
    }

    # Child-only overrides leave the caller's credentials unchanged.
    $startInfo.Environment['COMPOSE_DISABLE_ENV_FILE'] = '1'
    [void]$startInfo.Environment.Remove('COMPOSE_ENV_FILES')
    [void]$startInfo.Environment.Remove('OPENAI_API_KEY')
    [void]$startInfo.Environment.Remove('HELPDESK_OPENAI_API_KEY')
    foreach ($name in $syntheticSettings.Keys) {
        $startInfo.Environment[$name] = $syntheticSettings[$name]
    }
    if ($Case -eq 'Absent') {
        [void]$startInfo.Environment.Remove($VariableName)
    } elseif ($Case -eq 'Empty') {
        $startInfo.Environment[$VariableName] = ''
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw 'CONFIG_PROCESS_START_FAILED' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(45000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw 'CONFIG_PROCESS_TIMEOUT'
        }
        # Capture privately; even failure output must not expose environment values.
        $null = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        return [pscustomobject]@{ ExitCode = $process.ExitCode; ErrorText = $stderr }
    } finally {
        $process.Dispose()
    }
}

$verificationStage = 'BASELINE'
try {
    if (-not (Test-Path -LiteralPath $taskComposePath -PathType Leaf)) {
        throw 'COMPOSE_FILE_NOT_FOUND'
    }
    $baseline = Invoke-ComposeConfig -Case Baseline
    if ($baseline.ExitCode -ne 0) { throw 'BASELINE_CONFIG_REJECTED' }

    $cases = @(
        foreach ($name in $requiredSettings.Keys) {
            foreach ($case in @('Absent', 'Empty')) {
                $verificationStage = "$name/$case"
                $result = Invoke-ComposeConfig -VariableName $name -Case $case
                if ($result.ExitCode -eq 0 -or
                    -not $result.ErrorText.Contains($name) -or
                    -not $result.ErrorText.Contains($requiredSettings[$name])) {
                    throw 'REQUIRED_SETTING_REJECTION_NOT_CONFIRMED'
                }
                [pscustomobject]@{ variable = $name; input = $case; rejected = $true }
            }
        }
    )
    [ordered]@{
        evidence = 'LOCAL_COMPOSE_REQUIRED_SETTINGS'
        outcome = 'PASS'
        baselineAccepted = $true
        requiredVariableCount = $requiredSettings.Count
        absentCasesRejected = @($cases | Where-Object input -EQ 'Absent').Count
        emptyCasesRejected = @($cases | Where-Object input -EQ 'Empty').Count
        containersStarted = $false
        externalAiCalled = $false
        parentEnvironmentModified = $false
        cases = $cases
    } | ConvertTo-Json -Depth 4
} catch {
    # Do not serialize exceptions or raw Compose output.
    [ordered]@{
        evidence = 'LOCAL_COMPOSE_REQUIRED_SETTINGS'
        outcome = 'FAIL'
        stage = $verificationStage
        exceptionType = $_.Exception.GetType().Name
        scriptLine = $_.InvocationInfo.ScriptLineNumber
    } | ConvertTo-Json
    exit 1
}
