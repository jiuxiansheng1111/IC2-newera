[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GameDirectory,
    [switch]$SyncSingingConfiguration
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$gameRoot = (Resolve-Path -LiteralPath $GameDirectory).Path
$source = Join-Path $projectRoot 'tools\robot_voice'
$destination = [IO.Path]::GetFullPath((Join-Path $gameRoot 'tools\robot_voice'))
if (-not $destination.StartsWith($gameRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Voice runtime destination escaped the selected game directory.'
}
# Never inspect launcher command lines: they can contain login credentials.
$gameProcesses = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
if ($gameProcesses.Count) { throw 'Java is still running. Close Minecraft and let Java builds finish before installing the voice runtime.' }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$files = @()
foreach ($file in Get-ChildItem -LiteralPath $source -File) {
    if ($file.Extension -in @('.py', '.ps1') -or $file.Name -eq 'README.md') {
        $target = Join-Path $destination $file.Name
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        $files += [pscustomobject]@{name=$file.Name;sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash}
    }
}
$configPath = Join-Path $destination 'ic2newera-voice-runtime.json'
$createdConfig = $false
$singingUpdated = $false
function Resolve-VoiceAsset([string]$value) {
    if (-not $value) { return '' }
    if (-not [IO.Path]::IsPathRooted($value)) { $value = Join-Path $source $value }
    return [IO.Path]::GetFullPath($value)
}
function Resolve-SingingAssets($singing) {
    foreach ($field in @('python', 'ffprobe', 'model_cache')) {
        if ($singing.PSObject.Properties.Name -contains $field) {
            $singing.$field = Resolve-VoiceAsset ([string]$singing.$field)
        }
    }
    return $singing
}
if (-not (Test-Path -LiteralPath $configPath)) {
    # Only the explicit, non-secret voice asset configuration is copied; no LLM files are opened.
    $sourceConfig = Join-Path $source 'ic2newera-voice-runtime.json'
    if (Test-Path -LiteralPath $sourceConfig) {
        $data = Get-Content -LiteralPath $sourceConfig -Raw -Encoding UTF8 | ConvertFrom-Json
        $data.ffmpeg = Resolve-VoiceAsset ([string]$data.ffmpeg)
        if ($data.PSObject.Properties.Name -contains 'singing') {
            $data.singing = Resolve-SingingAssets $data.singing
        }
        foreach ($field in @('root','python','gpt_weights','sovits_weights','bert_base_path','cnhuhbert_base_path','reference_audio','japanese_frontend_package')) {
            if ($data.base_tts.PSObject.Properties.Name -contains $field) {
                $data.base_tts.$field = Resolve-VoiceAsset ([string]$data.base_tts.$field)
            }
        }
        foreach ($field in @('root','python')) { $data.rvc.$field = Resolve-VoiceAsset ([string]$data.rvc.$field) }
        foreach ($character in $data.characters.PSObject.Properties) {
            foreach ($field in @('model','index')) { $character.Value.$field = Resolve-VoiceAsset ([string]$character.Value.$field) }
        }
        # Share the same GPU lease across the local client and the host's manually started worker.
        $sourceLease = if ($data.PSObject.Properties.Name -contains 'gpu_lock_file') {
            [string]$data.gpu_lock_file
        } else { 'runtime/gpu.lock' }
        if (-not [IO.Path]::IsPathRooted($sourceLease)) { $sourceLease = Join-Path $source $sourceLease }
        $data | Add-Member -NotePropertyName gpu_lock_file -NotePropertyValue ([IO.Path]::GetFullPath($sourceLease)) -Force
        $data | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $configPath -Encoding UTF8
        $createdConfig = $true
    }
}
if ($SyncSingingConfiguration -and -not $createdConfig -and (Test-Path -LiteralPath $configPath)) {
    # An explicit local voice-only migration; never replace existing TTS/RVC or LLM settings.
    $sourceConfig = Join-Path $source 'ic2newera-voice-runtime.json'
    if (-not (Test-Path -LiteralPath $sourceConfig)) { throw 'Local source voice configuration is missing.' }
    $sourceData = Get-Content -LiteralPath $sourceConfig -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($sourceData.PSObject.Properties.Name -notcontains 'singing') { throw 'Local singing configuration is missing.' }
    $currentData = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $backupDirectory = Join-Path $projectRoot ('build\deployment\voice-config-backups\' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    Copy-Item -LiteralPath $configPath -Destination (Join-Path $backupDirectory 'ic2newera-voice-runtime.json')
    $currentData | Add-Member -NotePropertyName singing -NotePropertyValue (Resolve-SingingAssets $sourceData.singing) -Force
    $currentData | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $configPath -Encoding UTF8
    $singingUpdated = $true
}
$receipt = [pscustomobject]@{
    installedAt=(Get-Date -Format o); gameDirectory=$gameRoot; runtimeDirectory=$destination
    configurationCreated=$createdConfig; existingConfigurationPreserved=(-not $createdConfig -and -not $singingUpdated)
    singingConfigurationUpdated=$singingUpdated; existingTtsRvcConfigurationPreserved=(-not $createdConfig)
    configurationPresent=(Test-Path -LiteralPath $configPath); files=$files
    llmConfigurationModified=$false; gameStarted=$false
}
$receiptDirectory = Join-Path $projectRoot 'build\deployment'
New-Item -ItemType Directory -Path $receiptDirectory -Force | Out-Null
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $receiptDirectory 'voice-runtime-installation.json') -Encoding UTF8
Write-Host ('Installed local optional voice runtime: ' + $files.Count + ' code files; singing configuration updated: ' + $singingUpdated + '; existing TTS/RVC settings preserved: ' + (-not $createdConfig) + '.')
