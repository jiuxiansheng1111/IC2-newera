[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$VersionDirectory,
    [string]$GradleCacheDirectory = (Join-Path $env:USERPROFILE '.gradle')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime-mods.json') -Raw | ConvertFrom-Json
$versionPath = (Resolve-Path -LiteralPath $VersionDirectory).Path.TrimEnd('\')
$profileName = Split-Path -Leaf $versionPath
$profileFile = Join-Path $versionPath ($profileName + '.json')
$profile = Get-Content -LiteralPath $profileFile -Raw | ConvertFrom-Json
$requiredLoader = 'net.minecraftforge:fmlloader:' + $manifest.minecraft + '-' + $manifest.forge
if ($profile.mainClass -ne 'cpw.mods.bootstraplauncher.BootstrapLauncher' -or
    @($profile.libraries | Where-Object { $_.name -eq $requiredLoader }).Count -ne 1) {
    throw 'The selected profile must be Minecraft 1.19.2 with Forge 43.5.0.'
}
$isolationFile = Join-Path $versionPath 'PCL\Setup.ini'
if (-not (Select-String -LiteralPath $isolationFile -Pattern '^VersionArgumentIndieV2:True\s*$' -Quiet)) {
    throw 'This installer requires the selected PCL profile to use version isolation.'
}
$runningGame = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^(java|javaw)\.exe$' -and $_.CommandLine -and
    $_.CommandLine.Replace('/', '\').IndexOf($versionPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
})
if ($runningGame.Count -gt 0) { throw 'Save and close this Minecraft instance before installing mods.' }

function Get-DeclaredModIds([string]$JarPath) {
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry('META-INF/mods.toml')
        if ($null -eq $entry) { return }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $toml = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $inMods = $false
        foreach ($line in ($toml -split '\r?\n')) {
            if ($line -match '^\s*\[\[mods\]\]\s*(?:#.*)?$') { $inMods = $true }
            elseif ($line -match '^\s*\[') { $inMods = $false }
            elseif ($inMods -and $line -match '^\s*modId\s*=\s*"([^"]+)"') { $Matches[1] }
        }
    } finally { $archive.Dispose() }
}

$modsPath = [IO.Path]::GetFullPath((Join-Path $versionPath 'mods'))
if (-not $modsPath.StartsWith($versionPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The mods directory must be inside the selected version directory.'
}
$plan = @()
foreach ($mod in $manifest.mods) {
    if ($mod.fileName -notmatch '^[A-Za-z0-9._-]+\.jar$' -or $mod.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'Invalid file name or SHA-256 in runtime-mods.json.'
    }
    if ($mod.PSObject.Properties.Name -contains 'projectFile') {
        $source = [IO.Path]::GetFullPath((Join-Path $projectDirectory $mod.projectFile))
        if (-not $source.StartsWith($projectDirectory + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Addon archive must stay inside the project.'
        }
    } else {
        $cachePath = Join-Path (Join-Path $GradleCacheDirectory 'caches\modules-2\files-2.1') $mod.cacheDirectory
        $candidates = @(Get-ChildItem -LiteralPath $cachePath -Recurse -File -Filter $mod.cacheFileName)
        if ($candidates.Count -ne 1) { throw ('Expected one original cached JAR for ' + $mod.modId) }
        $source = $candidates[0].FullName
    }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $mod.sha256) {
        throw ('Source SHA-256 differs for ' + $mod.modId)
    }
    if (@(Get-DeclaredModIds $source) -notcontains $mod.modId) {
        throw ('Source does not declare expected mod ID: ' + $mod.modId)
    }
    $plan += [pscustomobject]@{ modId = $mod.modId; source = $source; destination = (Join-Path $modsPath $mod.fileName); sha256 = $mod.sha256 }
}
if (Test-Path -LiteralPath $modsPath) {
    foreach ($existing in (Get-ChildItem -LiteralPath $modsPath -File -Filter '*.jar')) {
        foreach ($id in @(Get-DeclaredModIds $existing.FullName)) {
            $expected = @($plan | Where-Object { $_.modId -eq $id })
            if ($expected.Count -gt 0 -and $existing.FullName -ne $expected[0].destination) {
                throw ('Another JAR already provides ' + $id + ': ' + $existing.Name + '. Resolve the duplicate before installing.')
            }
        }
    }
}

$deploymentPath = Join-Path $projectDirectory 'build\deployment'
$backupPath = Join-Path $deploymentPath ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $modsPath -Force | Out-Null
New-Item -ItemType Directory -Path $deploymentPath -Force | Out-Null
$copied = 0
foreach ($entry in $plan) {
    if (Test-Path -LiteralPath $entry.destination) {
        if ((Get-FileHash -LiteralPath $entry.destination -Algorithm SHA256).Hash -eq $entry.sha256) { continue }
        New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
        Copy-Item -LiteralPath $entry.destination -Destination (Join-Path $backupPath (Split-Path -Leaf $entry.destination))
    }
    Copy-Item -LiteralPath $entry.source -Destination $entry.destination -Force
    if ((Get-FileHash -LiteralPath $entry.destination -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw ('Installed SHA-256 differs for ' + $entry.modId)
    }
    $copied++
}
$receiptFile = Join-Path $deploymentPath 'pcl-installation.json'
[pscustomobject]@{
    installedAt = (Get-Date).ToString('o')
    versionDirectory = $versionPath
    minecraft = $manifest.minecraft
    forge = $manifest.forge
    mods = @($plan | Select-Object modId, destination, sha256)
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptFile -Encoding UTF8
[pscustomobject]@{ copied = $copied; total = $plan.Count; modsDirectory = $modsPath; receipt = $receiptFile } | ConvertTo-Json
& (Join-Path $PSScriptRoot 'install-voice-runtime.ps1') -GameDirectory $versionPath
