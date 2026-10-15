#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Endpoint,
    [string]$Database = 'helpdesk',
    [string]$Username = 'helpdesk_app',
    [switch]$CopyToClipboard
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskProcess = $null
$taskStage = 'NODE_DISCOVERY'

try {
    $taskNode = (Get-Command node -CommandType Application | Select-Object -First 1).Source
    $taskStage = 'GENERATOR_CONFIGURATION'
    $taskStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $taskStartInfo.FileName = $taskNode
    $taskStartInfo.WorkingDirectory = Split-Path -Parent $PSScriptRoot
    $taskStartInfo.UseShellExecute = $false
    $taskStartInfo.CreateNoWindow = $true
    $taskStartInfo.RedirectStandardOutput = $true
    $taskStartInfo.RedirectStandardError = $true
    foreach ($taskArgument in @(
        (Join-Path $PSScriptRoot 'cloudshell-rds-connect.mjs'),
        '--endpoint', $Endpoint, '--database', $Database, '--username', $Username
    )) {
        $taskStartInfo.ArgumentList.Add($taskArgument)
    }

    # The generator needs executable discovery/temp paths, not any project credentials.
    $taskEnvironment = @{}
    foreach ($taskName in @('PATH', 'PATHEXT', 'SystemRoot', 'TEMP', 'TMP')) {
        if ($taskStartInfo.Environment.ContainsKey($taskName)) {
            $taskEnvironment[$taskName] = $taskStartInfo.Environment[$taskName]
        }
    }
    $taskStartInfo.Environment.Clear()
    foreach ($taskName in $taskEnvironment.Keys) {
        $taskStartInfo.Environment[$taskName] = $taskEnvironment[$taskName]
    }

    $taskProcess = [System.Diagnostics.Process]::new()
    $taskProcess.StartInfo = $taskStartInfo
    $taskStage = 'GENERATOR_START'
    if (-not $taskProcess.Start()) { throw 'GENERATOR_START_FAILED' }
    $taskOutput = $taskProcess.StandardOutput.ReadToEndAsync()
    $taskError = $taskProcess.StandardError.ReadToEndAsync()
    if (-not $taskProcess.WaitForExit(20000)) {
        $taskProcess.Kill($true)
        $taskProcess.WaitForExit()
        throw 'GENERATOR_TIMEOUT'
    }
    $taskStage = 'GENERATOR_RESULT'
    $taskCommand = $taskOutput.GetAwaiter().GetResult().Replace("`r`n", "`n")
    $null = $taskError.GetAwaiter().GetResult()
    if ($taskProcess.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($taskCommand)) {
        throw 'GENERATOR_REJECTED'
    }

    if ($CopyToClipboard) {
        $taskStage = 'CLIPBOARD_COPY'
        Set-Clipboard -Value $taskCommand
        Write-Output 'CLOUDSHELL_CONNECT_COMMAND_COPIED'
    } else {
        [Console]::Out.Write($taskCommand)
    }
} catch {
    # Do not serialize child output, argument values, environment or exception objects.
    [Console]::Error.WriteLine('CLOUDSHELL_CONNECT_COMMAND_FAILED')
    [Console]::Error.WriteLine("stage=$taskStage; type=$($_.Exception.GetType().Name); line=$($_.InvocationInfo.ScriptLineNumber)")
    exit 1
} finally {
    if ($null -ne $taskProcess) { $taskProcess.Dispose() }
}
