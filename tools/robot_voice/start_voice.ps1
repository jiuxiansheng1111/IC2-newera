# Starts only this optional HTTP coordinator. Models load on an explicit /voice request.
[CmdletBinding()]
param(
    [string]$Config = '',
    [switch]$CheckOnly,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'
$voiceToolsRoot = [IO.Path]::GetFullPath($PSScriptRoot)
if (-not $Config) { $Config = Join-Path $voiceToolsRoot 'ic2newera-voice-runtime.json' }
$Config = [IO.Path]::GetFullPath($Config)
$voiceConfiguration = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
$voicePythonPath = [string]$voiceConfiguration.base_tts.python
if (-not [IO.Path]::IsPathRooted($voicePythonPath)) { $voicePythonPath = Join-Path (Split-Path -Parent $Config) $voicePythonPath }
$voicePythonPath = [IO.Path]::GetFullPath($voicePythonPath)
$voiceServerPath = Join-Path $voiceToolsRoot 'server.py'
if (-not (Test-Path -LiteralPath $voicePythonPath -PathType Leaf)) { throw 'Configured existing voice Python environment is missing.' }

# This reads dedicated configuration/files/resources only. No Torch, model, listener or QQ import.
& $voicePythonPath -X utf8 $voiceServerPath --config $Config --check-config
if ($LASTEXITCODE -ne 0) { throw 'Independent voice configuration could not be parsed.' }
if ($CheckOnly) { return }

$voicePort = [int]$voiceConfiguration.port
$voiceSocket = [Net.Sockets.TcpClient]::new()
try {
    if ($voiceSocket.ConnectAsync('127.0.0.1', $voicePort).Wait(800) -and $voiceSocket.Connected) {
        throw "Port $voicePort already has a listener; it was left unchanged."
    }
} catch [System.Management.Automation.MethodInvocationException] {
    # A refused local TCP connection means no listener was found.
} finally { $voiceSocket.Dispose() }

if ($Foreground) {
    & $voicePythonPath -X utf8 $voiceServerPath --config $Config
    return
}
$voiceArguments = @('-X', 'utf8', $voiceServerPath, '--config', $Config)
$voiceQuotedArguments = ($voiceArguments | ForEach-Object {
    if ($_ -match '"') { throw 'Voice launch arguments contain an invalid quote.' }
    '"' + $_ + '"'
}) -join ' '
$voiceLogRoot = Join-Path $voiceToolsRoot 'runtime'
New-Item -ItemType Directory -Path $voiceLogRoot -Force | Out-Null
$voiceProcess = Start-Process -FilePath $voicePythonPath -ArgumentList $voiceQuotedArguments `
    -WorkingDirectory $voiceToolsRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $voiceLogRoot 'http.stdout.log') `
    -RedirectStandardError (Join-Path $voiceLogRoot 'http.stderr.log')
$voiceProcess.Id | Set-Content -LiteralPath (Join-Path $voiceLogRoot 'http.pid') -Encoding ASCII
Write-Host "Independent optional voice coordinator started (PID $($voiceProcess.Id))."
Write-Host "Check http://127.0.0.1:$voicePort/health. Starting a process does not verify model inference."
Write-Host 'Existing QQ, AstrBot, LLM configurations and processes were left unchanged.'
