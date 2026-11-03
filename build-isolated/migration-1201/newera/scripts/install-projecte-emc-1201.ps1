[CmdletBinding()]
param(
    [switch] $Apply
)

$ErrorActionPreference = 'Stop'

# This installer is deliberately pinned to the user's isolated 1.20.1 instance.
$instanceRoot = Join-Path $env:USERPROFILE 'Desktop\.minecraft\versions\1.20.1-Forge_47.4.28'
$projecteJar = Join-Path $instanceRoot 'mods\projecte-1201-PE1.0.1.jar'
$manifestPath = Join-Path $PSScriptRoot 'projecte-emc-1201-blacklist.json'
$balancePath = Join-Path $PSScriptRoot 'projecte-emc-1201-balance.json'
$configPath = Join-Path $instanceRoot 'config\ProjectE\custom_emc.json'

if (-not (Test-Path -LiteralPath $instanceRoot -PathType Container)) {
    throw "Pinned Minecraft instance is missing: $instanceRoot"
}
if (-not (Test-Path -LiteralPath $projecteJar -PathType Leaf)) {
    throw "Expected ProjectE 1.0.1 JAR is missing: $projecteJar"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "EMC blacklist resource list is missing: $manifestPath"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($projecteJar)
try {
    $modMetadata = $archive.GetEntry('META-INF/mods.toml')
    if ($null -eq $modMetadata) {
        throw 'ProjectE JAR has no META-INF/mods.toml.'
    }
    $reader = [System.IO.StreamReader]::new($modMetadata.Open())
    try {
        $modsToml = $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
    if ($modsToml -notmatch '(?m)^\s*modId\s*=\s*"projecte"\s*$' -or
        $modsToml -notmatch '(?m)^\s*version\s*=\s*"1\.0\.1"\s*$' -or
        $modsToml -notmatch '(?m)^\s*versionRange\s*=\s*"\[1\.20\.1\]"\s*$') {
        throw 'The pinned ProjectE JAR metadata is not ProjectE 1.0.1 for Minecraft 1.20.1.'
    }
} finally {
    $archive.Dispose()
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
if ($manifest.minecraftInstance -ne '1.20.1-Forge_47.4.28' -or
    $manifest.projecteVersionJar -ne 'projecte-1201-PE1.0.1.jar') {
    throw 'The EMC item list is not pinned to the expected 1.20.1 ProjectE instance.'
}
$itemIds = @($manifest.itemIds | Sort-Object -Unique)
if ($itemIds.Count -eq 0 -or $itemIds.Count -ne @($manifest.itemIds).Count) {
    throw 'The EMC item list is empty or contains duplicate IDs.'
}
foreach ($itemId in $itemIds) {
    if ($itemId -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$') {
        throw "Invalid item ID in EMC item list: $itemId"
    }
}

$balance = Get-Content -LiteralPath $balancePath -Raw | ConvertFrom-Json -AsHashtable
if ($balance.minecraftInstance -ne $manifest.minecraftInstance -or
    $balance.projecteVersionJar -ne $manifest.projecteVersionJar -or
    $balance.values -isnot [System.Collections.IDictionary] -or $balance.values.Count -eq 0) {
    throw 'The positive EMC balance manifest does not match the pinned ProjectE instance.'
}
foreach ($itemId in $balance.values.Keys) {
    if ($itemId -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$' -or $itemId -in $itemIds -or
        $balance.values[$itemId] -isnot [long] -and $balance.values[$itemId] -isnot [int] -or
        $balance.values[$itemId] -le 0) {
        throw "Invalid or conflicting positive EMC override: $itemId"
    }
}
$managedIds = @($itemIds) + @($balance.values.Keys)

$hadExistingFile = Test-Path -LiteralPath $configPath -PathType Leaf
if ($hadExistingFile) {
    try {
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json -AsHashtable
    } catch {
        throw "Existing ProjectE custom_emc.json is invalid; it was left untouched. $($_.Exception.Message)"
    }
    if ($null -eq $config -or -not ($config -is [System.Collections.IDictionary]) -or
        -not $config.Contains('entries') -or $config.entries -isnot [System.Collections.IList]) {
        throw 'Existing ProjectE custom_emc.json has an unexpected format; it was left untouched.'
    }
} else {
    $config = [ordered]@{
        __comment = 'Use the in-game commands to edit this file'
        entries = @()
    }
}

$unrelatedEntries = @()
foreach ($entry in @($config.entries)) {
    if ($null -eq $entry -or -not ($entry -is [System.Collections.IDictionary]) -or
        -not $entry.Contains('item') -or -not $entry.Contains('emc') -or
        $entry.item -isnot [string]) {
        throw 'Existing custom EMC entries contain an unsupported record; no files were changed.'
    }
    if ($entry.item -notin $managedIds) {
        $unrelatedEntries += $entry
    }
}

$managedEntries = @($itemIds | ForEach-Object {
    [ordered]@{ item = $_; emc = 0 }
})
$managedEntries += @($balance.values.Keys | Sort-Object | ForEach-Object {
    [ordered]@{ item = $_; emc = $balance.values[$_] }
})
$config.entries = @($unrelatedEntries + $managedEntries)
$json = ConvertTo-Json -InputObject $config -Depth 12

Write-Output "Target instance: $instanceRoot"
Write-Output "ProjectE config: $configPath"
Write-Output "Managed controlled intermediates: $($itemIds.Count) (EMC 0)"
Write-Output "Managed positive EMC overrides: $($balance.values | ConvertTo-Json -Compress)"
Write-Output "Unrelated existing entries preserved: $($unrelatedEntries.Count)"
if (-not $Apply) {
    Write-Output 'Preview only. Pass -Apply after Minecraft is closed to back up and merge this config.'
    return
}

# Preview is safe while Java is active; applying requires all Java runtimes to
# be closed. Do not inspect command lines or stop processes.
$javaProcesses = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
if ($javaProcesses.Count -gt 0) {
    $names = ($javaProcesses | ForEach-Object { "$($_.ProcessName) (PID $($_.Id))" }) -join ', '
    throw "Java is running ($names). Close Minecraft and other Java applications, then retry. No files were changed."
}

$configDirectory = Split-Path -Parent $configPath
New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
if ($hadExistingFile) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $backupPath = "$configPath.bak.$stamp"
    Copy-Item -LiteralPath $configPath -Destination $backupPath
    Write-Output "Backed up existing config: $backupPath"
} else {
    Write-Output 'No previous custom_emc.json existed; the new file will contain only managed entries.'
}

$tempPath = "$configPath.tmp.$([guid]::NewGuid().ToString('N'))"
try {
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($tempPath, $json + [Environment]::NewLine, $encoding)
    if ($hadExistingFile) {
        # PowerShell can coerce a null backup argument into an invalid empty path.
        [System.IO.File]::Replace($tempPath, $configPath, $backupPath)
    } else {
        [System.IO.File]::Move($tempPath, $configPath)
    }
} finally {
    if (Test-Path -LiteralPath $tempPath) {
        Remove-Item -LiteralPath $tempPath -Force
    }
}

Write-Output 'ProjectE EMC overrides merged. Start the pinned instance and reload EMC mappings to apply.'
