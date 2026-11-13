[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)][string]$VersionDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$projectDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
$migrationDirectory = (Resolve-Path -LiteralPath (Join-Path $projectDirectory '..')).Path.TrimEnd('\')
$dependencyDirectory = Join-Path $migrationDirectory 'dependencies'
$runtimeLockPath = Join-Path $dependencyDirectory 'runtime-lock-1201.json'
if (-not (Test-Path -LiteralPath $runtimeLockPath -PathType Leaf)) {
    throw 'The locked 1.20.1 runtime manifest is missing.'
}
$runtimeLock = Get-Content -LiteralPath $runtimeLockPath -Raw | ConvertFrom-Json
if ($runtimeLock.target.minecraft -ne '1.20.1' -or $runtimeLock.target.forge -ne '47.4.28') {
    throw 'The dependency lock is not for Minecraft 1.20.1 / Forge 47.4.28.'
}
if (@($runtimeLock.runtimeMods).Count -ne 15) {
    throw 'The runtime lock must declare exactly 15 runtime mod JARs.'
}
$enhancementLockPath = Join-Path $projectDirectory '..\..\..\scripts\player-enhancements-lock-1201.json'
$enhancementLock = Get-Content -LiteralPath $enhancementLockPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($enhancementLock.target.minecraft -ne '1.20.1' -or $enhancementLock.target.forge -ne '47.4.28') {
    throw 'The enhancement lock is not for Minecraft 1.20.1 / Forge 47.4.28.'
}
$lockedMods = @($runtimeLock.runtimeMods) + @($enhancementLock.mods)
$retiredSpecs = @()
if ($enhancementLock.PSObject.Properties.Name -contains 'retiredMods') {
    $retiredSpecs = @($enhancementLock.retiredMods)
}
$retiredById = @{}
foreach ($retired in $retiredSpecs) {
    if ($retired.modId -notmatch '^[a-z0-9_]+$' -or
        $retired.file -notmatch '^[A-Za-z0-9._+-]+\.jar$' -or
        $retired.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'Invalid retired mod ID, exact previous JAR name, or SHA-256 in player-enhancements-lock-1201.json.'
    }
    if ($retiredById.ContainsKey($retired.modId) -or
        @($lockedMods | Where-Object { $_.modId -eq $retired.modId }).Count -gt 0) {
        throw 'The enhancement lock contains a duplicate or still-active retired mod ID.'
    }
    $retiredById[$retired.modId] = $retired
}

try {
    $null = Resolve-Path -LiteralPath $VersionDirectory
    $requestedVersionPath = [IO.Path]::GetFullPath($VersionDirectory).TrimEnd('\')
}
catch { throw 'Select an existing PCL version directory for the isolated Minecraft 1.20.1 profile.' }
$pathCursor = $requestedVersionPath
$pathRoot = [IO.Path]::GetPathRoot($requestedVersionPath)
while ($pathCursor) {
    $pathItem = Get-Item -LiteralPath $pathCursor -Force
    if (($pathItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'The selected version path contains a junction or symbolic link; select its real PCL directory directly.'
    }
    if ([string]::Equals($pathCursor, $pathRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
    $parentDirectory = [IO.Directory]::GetParent($pathCursor)
    if ($null -eq $parentDirectory) { break }
    $pathCursor = $parentDirectory.FullName
}
$versionPath = $requestedVersionPath
$versionPath = [IO.Path]::GetFullPath($versionPath)
$profileName = Split-Path -Leaf $versionPath
$profileFile = Join-Path $versionPath ($profileName + '.json')
if (-not (Test-Path -LiteralPath $profileFile -PathType Leaf)) {
    throw 'The selected directory does not contain its matching PCL version JSON.'
}
$profile = Get-Content -LiteralPath $profileFile -Raw | ConvertFrom-Json
$requiredLoader = 'net.minecraftforge:fmlloader:1.20.1-47.4.28'
$declaredBase = if ($profile.PSObject.Properties.Name -contains 'inheritsFrom' -and $profile.inheritsFrom) {
    [string]$profile.inheritsFrom
} elseif ($profile.PSObject.Properties.Name -contains 'id' -and $profile.id) {
    [string]$profile.id
} else {
    $profileName
}
if ($profile.mainClass -ne 'cpw.mods.bootstraplauncher.BootstrapLauncher' -or
    $declaredBase -notmatch '(^|[^0-9])1\.20\.1([^0-9]|$)' -or
    @($profile.libraries | Where-Object { $_.name -eq $requiredLoader }).Count -ne 1) {
    throw 'The selected profile must be Minecraft 1.20.1 with Forge 47.4.28; Minecraft 1.19.2 is rejected.'
}
$isolationFile = Join-Path $versionPath 'PCL\Setup.ini'
if (-not (Test-Path -LiteralPath $isolationFile -PathType Leaf) -or
    -not (Select-String -LiteralPath $isolationFile -Pattern '^VersionArgumentIndieV2:True\s*$' -Quiet)) {
    throw 'The selected PCL profile must use version isolation.'
}

# Process names only; never inspect Java command lines, which may contain tokens.
$runningJava = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
if ($runningJava.Count -gt 0) {
    throw 'Java is still running. Close Minecraft and wait for Java builds to finish before installing.'
}

function Get-DeclaredModIds([string]$JarPath) {
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        return @(Get-ArchiveModIds $archive 0)
    } finally { $archive.Dispose() }
}

function Get-ArchiveModIds([IO.Compression.ZipArchive]$archive, [int]$depth) {
        if ($depth -gt 6) { throw 'JarJar metadata recursion exceeds the supported depth.' }
        $ids = @()
        $entry = $archive.GetEntry('META-INF/mods.toml')
        if ($null -ne $entry) {
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $toml = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $inMods = $false
        foreach ($line in ($toml -split '\r?\n')) {
            if ($line -match '^\s*\[\[mods\]\]\s*(?:#.*)?$') { $inMods = $true }
            elseif ($line -match '^\s*\[') { $inMods = $false }
            elseif ($inMods -and $line -match '^\s*modId\s*=\s*"([^"]+)"') { $ids += $Matches[1] }
        }
        }
        foreach ($nested in $archive.Entries) {
            if ($nested.FullName -notlike 'META-INF/jarjar/*.jar') { continue }
            $memory = [IO.MemoryStream]::new()
            $stream = $nested.Open()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            $memory.Position = 0
            $child = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read)
            try { $ids += @(Get-ArchiveModIds $child ($depth + 1)) }
            finally { $child.Dispose(); $memory.Dispose() }
        }
        return $ids
}

function Assert-NoReparseWriteTargets([string]$VersionPath, [string]$ModsPath, [object[]]$Entries) {
    $versionItem = Get-Item -LiteralPath $VersionPath -Force
    if (($versionItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'The selected version directory is a junction or symbolic link; refusing to write through it.'
    }
    $modsItem = Get-Item -LiteralPath $ModsPath -Force -ErrorAction SilentlyContinue
    if ($null -ne $modsItem) {
        if (($modsItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'The target mods directory is a junction or symbolic link; refusing to write through it.'
        }
        if (-not $modsItem.PSIsContainer) { throw 'The target mods path exists but is not a directory.' }
    }
    foreach ($entry in $Entries) {
        if (-not $entry.destination.StartsWith($ModsPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'A planned JAR destination is outside the selected mods directory.'
        }
        $targetItem = Get-Item -LiteralPath $entry.destination -Force -ErrorAction SilentlyContinue
        if ($null -ne $targetItem) {
            if (($targetItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw ('A planned destination is a junction or symbolic link: ' + $entry.fileName)
            }
            if ($targetItem.PSIsContainer) {
                throw ('A planned JAR destination is a directory: ' + $entry.fileName)
            }
        }
    }
}

$modsPath = [IO.Path]::GetFullPath((Join-Path $versionPath 'mods'))
if (-not $modsPath.StartsWith($versionPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The mods directory must remain inside the selected version directory.'
}
if ($null -ne ($modsItem = Get-Item -LiteralPath $modsPath -Force -ErrorAction SilentlyContinue)) {
    if (($modsItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'The selected profile mods directory is a junction or symbolic link.'
    }
    if (-not $modsItem.PSIsContainer) { throw 'The target mods path exists but is not a directory.' }
}
$plan = @()
$seenIds = @{}
$seenFiles = @{}
foreach ($mod in $lockedMods) {
    if ($mod.modId -notmatch '^[a-z0-9_]+$' -or $mod.file -notmatch '^[A-Za-z0-9._+-]+\.jar$' -or
        $mod.sha256 -notmatch '^[A-Fa-f0-9]{64}$' -or [int64]$mod.sizeBytes -le 0) {
        throw 'Invalid mod ID, file name, size, or SHA-256 in runtime-lock-1201.json.'
    }
    if ($seenIds.ContainsKey($mod.modId) -or $seenFiles.ContainsKey($mod.file)) {
        throw 'The runtime lock contains duplicate mod IDs or JAR file names.'
    }
    $seenIds[$mod.modId] = $true
    $seenFiles[$mod.file] = $true
    $source = [IO.Path]::GetFullPath((Join-Path $dependencyDirectory $mod.file))
    if (-not $source.StartsWith($dependencyDirectory + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw ('The locked source JAR is missing or outside dependencies: ' + $mod.modId)
    }
    $sourceFile = Get-Item -LiteralPath $source
    if ($sourceFile.Length -ne [int64]$mod.sizeBytes -or
        (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $mod.sha256) {
        throw ('Locked source size or SHA-256 differs for ' + $mod.modId)
    }
    if (@(Get-DeclaredModIds $source) -notcontains $mod.modId) {
        throw ('Locked source does not declare expected mod ID: ' + $mod.modId)
    }
    # Keep the upstream download/hash pinned separately from our language-only JAR.
    # An explicit override prevents later installs from silently dropping zh_cn.
    $installSha = $mod.sha256
    $installSize = [int64]$mod.sizeBytes
    if ($mod.PSObject.Properties.Name -contains 'localOverride') {
        $overlay = $mod.localOverride
        if ($overlay.relativePath -notmatch '^localized/[A-Za-z0-9._+-]+\.jar$' -or
            $overlay.sha256 -notmatch '^[A-Fa-f0-9]{64}$' -or [int64]$overlay.sizeBytes -le 0) {
            throw ('Invalid localization override: ' + $mod.modId)
        }
        $localizedRoot = [IO.Path]::GetFullPath((Join-Path $dependencyDirectory 'localized'))
        $localizedSource = [IO.Path]::GetFullPath((Join-Path $dependencyDirectory $overlay.relativePath))
        if (-not $localizedSource.StartsWith($localizedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $localizedSource -PathType Leaf)) {
            throw ('Build the missing pinned localization override: ' + $mod.modId)
        }
        if ((Get-Item -LiteralPath $localizedSource).Length -ne [int64]$overlay.sizeBytes -or
            (Get-FileHash -LiteralPath $localizedSource -Algorithm SHA256).Hash -ne $overlay.sha256 -or
            @(Get-DeclaredModIds $localizedSource) -notcontains $mod.modId) {
            throw ('Localization override failed integrity verification: ' + $mod.modId)
        }
        $source = $localizedSource
        $installSha = $overlay.sha256
        $installSize = [int64]$overlay.sizeBytes
    }
    $plan += [pscustomobject]@{
        modId = $mod.modId
        source = $source
        fileName = $mod.file
        destination = Join-Path $modsPath $mod.file
        sha256 = $installSha
        sizeBytes = $installSize
    }
}

$projectJarsPath = Join-Path $projectDirectory 'build\libs'
$projectJars = @(Get-ChildItem -LiteralPath $projectJarsPath -File -Filter '*-all.jar')
if ($projectJars.Count -ne 1) {
    throw 'Expected exactly one full New Era JAR (*-all.jar) in newera/build/libs; build the project first.'
}
$projectJar = $projectJars[0]
if (@(Get-DeclaredModIds $projectJar.FullName) -notcontains 'ic2newera') {
    throw 'The full project JAR does not declare mod ID ic2newera.'
}
$projectHash = (Get-FileHash -LiteralPath $projectJar.FullName -Algorithm SHA256).Hash
$plan += [pscustomobject]@{
    modId = 'ic2newera'
    source = $projectJar.FullName
    fileName = $projectJar.Name
    destination = Join-Path $modsPath $projectJar.Name
    sha256 = $projectHash
    sizeBytes = $projectJar.Length
}

$replacedVersions = @()
$retiredVersions = @()
$knownPreviousHashes = @{
    'create' = 'A18763BE04F6A7921D2E3BC5E9150B0BB5BC89B5EF45A607E93939972021F932'
    'ad_astra' = '62D1A8A23075A983526A8030A7F2DAAF118087048E514F3B34541572C03DFCDF'
    'yes_steve_model' = '25B5E902B96F4C298690208F8B433CBC31737C23F87590354DBD86F00207BC8F'
}
if (Test-Path -LiteralPath $modsPath -PathType Container) {
    foreach ($existing in (Get-ChildItem -LiteralPath $modsPath -File -Filter '*.jar')) {
        $existingIds = @(Get-DeclaredModIds $existing.FullName)
        foreach ($retiredId in $retiredById.Keys) {
            if ($retiredId -notin $existingIds) { continue }
            $retired = $retiredById[$retiredId]
            if ($existing.Name -ne $retired.file -or
                (Get-FileHash -LiteralPath $existing.FullName -Algorithm SHA256).Hash -ne $retired.sha256.ToUpperInvariant()) {
                throw ('A retired mod ID is present, but its JAR does not match the exact previous file and hash: ' + $retiredId)
            }
            if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
                -not $existing.FullName.StartsWith($modsPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'The previous retired dependency is outside the selected real mods directory.'
            }
            $retiredVersions += $existing.FullName
        }
        foreach ($id in $existingIds) {
            $expected = @($plan | Where-Object { $_.modId -eq $id })
            if ($expected.Count -gt 0 -and
                -not [string]::Equals($existing.FullName, $expected[0].destination, [StringComparison]::OrdinalIgnoreCase)) {
                if (-not $knownPreviousHashes.ContainsKey($id) -or
                    (Get-FileHash -LiteralPath $existing.FullName -Algorithm SHA256).Hash -ne $knownPreviousHashes[$id]) {
                    throw ('An unrecognized JAR already provides ' + $id + ': ' + $existing.Name)
                }
                if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
                    -not $existing.FullName.StartsWith($modsPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'The previous dependency is outside the selected real mods directory.'
                }
                $replacedVersions += $existing.FullName
            }
        }
    }
}

$deploymentPath = Join-Path $projectDirectory 'build\deployment'
$backupPath = Join-Path $deploymentPath ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$bookSource = (Resolve-Path -LiteralPath (Join-Path $projectDirectory '..\..\..\scripts\field-guide\config\ftbquests\quests')).Path
$bookFiles = @(Get-ChildItem -LiteralPath $bookSource -Filter '*.snbt' -Recurse -File)
$bookPath = Join-Path $versionPath 'config\ftbquests\quests'
if ($bookFiles.Count -lt 3 -or !(Test-Path -LiteralPath (Join-Path $bookSource 'data.snbt'))) { throw 'The generated field guide is incomplete.' }
$fieldworkSource = (Resolve-Path -LiteralPath (Join-Path $projectDirectory 'tacz\ic2newera_fieldwork')).Path
$fieldworkFiles = @(Get-ChildItem -LiteralPath $fieldworkSource -Filter '*.json' -Recurse -File)
if ($fieldworkFiles.Count -lt 5 -or !(Test-Path -LiteralPath (Join-Path $fieldworkSource 'gunpack.meta.json'))) { throw 'The TaCZ fieldwork pack is incomplete.' }
function Assert-ProfilePath([string]$target) {
    $probe = [IO.Path]::GetFullPath($target)
    if (!$probe.StartsWith($versionPath+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Configuration is outside the selected profile.' }
    while ($probe.Length -gt $versionPath.Length) {
        if (Test-Path -LiteralPath $probe) {
            $part = Get-Item -LiteralPath $probe -Force
            if ($part.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing to follow a redirected configuration path.' }
        }
        $probe = [IO.Path]::GetDirectoryName($probe)
    }
}
$dataFiles = @(
    foreach ($file in $bookFiles) {
        [pscustomobject]@{ source=$file.FullName; relative='config\ftbquests\quests\'+$file.FullName.Substring($bookSource.Length+1) }
    }
    foreach ($file in $fieldworkFiles) {
        [pscustomobject]@{ source=$file.FullName; relative='tacz\ic2newera_fieldwork\'+$file.FullName.Substring($fieldworkSource.Length+1) }
    }
)
foreach ($file in $dataFiles) { Assert-ProfilePath (Join-Path $versionPath $file.relative) }
$terrainConfigs = @('config\ultraterraforged\modpack.json','config\ultraterraforged\modpack-presets\ic2-new-era-balanced-1201.json')
foreach ($rel in $terrainConfigs) { Assert-ProfilePath (Join-Path $versionPath $rel) }
$planPreview = @($plan | Select-Object modId, fileName, sha256, sizeBytes)
if (-not $PSCmdlet.ShouldProcess($versionPath, "Install $($lockedMods.Count) locked mods and the full New Era JAR; remove $($retiredVersions.Count) exact retired mod JARs")) {
    [pscustomobject]@{ planned = $plan.Count; modsDirectory = $modsPath; plan = $planPreview } | ConvertTo-Json -Depth 4
    return
}

Assert-NoReparseWriteTargets -VersionPath $versionPath -ModsPath $modsPath -Entries $plan
$runningJava = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
if ($runningJava.Count -gt 0) {
    throw 'Java started during preflight. Close Minecraft and wait for Java builds to finish before installing.'
}

New-Item -ItemType Directory -Path $modsPath -Force | Out-Null
New-Item -ItemType Directory -Path $deploymentPath -Force | Out-Null
Assert-NoReparseWriteTargets -VersionPath $versionPath -ModsPath $modsPath -Entries $plan
$runningJava = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
if ($runningJava.Count -gt 0) {
    throw 'Java started before file copying. Close Minecraft and wait for Java builds to finish before installing.'
}
# Only exact recorded dependency hashes may be replaced under a new filename.
# Move the originals into this project's deployment backup before any new JAR is copied.
if ($replacedVersions.Count -gt 0) {
    New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
    foreach ($previousPath in ($replacedVersions | Select-Object -Unique)) {
        $resolvedPrevious = (Resolve-Path -LiteralPath $previousPath).Path
        $backupTarget = [IO.Path]::GetFullPath((Join-Path $backupPath (Split-Path -Leaf $previousPath)))
        if (-not $resolvedPrevious.StartsWith($modsPath + '\', [StringComparison]::OrdinalIgnoreCase) -or
            -not $backupTarget.StartsWith($projectDirectory + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Dependency backup path escaped its intended directory.'
        }
        Move-Item -LiteralPath $resolvedPrevious -Destination $backupTarget
    }
}
if ($retiredVersions.Count -gt 0) {
    New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
    foreach ($previousPath in ($retiredVersions | Select-Object -Unique)) {
        $resolvedPrevious = (Resolve-Path -LiteralPath $previousPath).Path
        $retiredName = Split-Path -Leaf $previousPath
        $retiredSpec = @($retiredSpecs | Where-Object { $_.file -eq $retiredName })[0]
        $previousItem = Get-Item -LiteralPath $resolvedPrevious -Force
        if (-not $resolvedPrevious.StartsWith($modsPath + '\', [StringComparison]::OrdinalIgnoreCase) -or
            ($previousItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $previousItem.PSIsContainer -or
            (Get-FileHash -LiteralPath $resolvedPrevious -Algorithm SHA256).Hash -ne $retiredSpec.sha256.ToUpperInvariant()) {
            throw 'Retired JAR changed after preflight; refusing to remove it.'
        }
        $backupTarget = [IO.Path]::GetFullPath((Join-Path $backupPath $retiredName))
        if (-not $backupTarget.StartsWith($projectDirectory + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Retired dependency backup path escaped the project.'
        }
        Move-Item -LiteralPath $resolvedPrevious -Destination $backupTarget
    }
}
$copied = 0
foreach ($entry in $plan) {
    if (Test-Path -LiteralPath $entry.destination -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $entry.destination -Algorithm SHA256).Hash -eq $entry.sha256) { continue }
        New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
        Copy-Item -LiteralPath $entry.destination -Destination (Join-Path $backupPath $entry.fileName)
    }
    Copy-Item -LiteralPath $entry.source -Destination $entry.destination -Force
    if ((Get-Item -LiteralPath $entry.destination).Length -ne $entry.sizeBytes -or
        (Get-FileHash -LiteralPath $entry.destination -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw ('Installed file size or SHA-256 differs for ' + $entry.modId)
    }
    $copied++
}

foreach ($file in $dataFiles) {
    $relative = $file.relative
    $destination = Join-Path $versionPath $relative
    Assert-ProfilePath $destination
    $expected = (Get-FileHash -LiteralPath $file.source).Hash
    if ((Test-Path -LiteralPath $destination) -and (Get-FileHash -LiteralPath $destination).Hash -ne $expected) {
        $prior = Join-Path $backupPath $relative
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($prior)) | Out-Null
        Copy-Item -LiteralPath $destination -Destination $prior
    }
    if (!(Test-Path -LiteralPath $destination) -or (Get-FileHash -LiteralPath $destination).Hash -ne $expected) {
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
        Copy-Item -LiteralPath $file.source -Destination $destination -Force
    }
    if ((Get-FileHash -LiteralPath $destination).Hash -ne $expected) { throw "Installed fieldwork data differs for $relative" }
}
foreach ($rel in $terrainConfigs) {
    $oldConfig = Join-Path $versionPath $rel
    if (Test-Path -LiteralPath $oldConfig -PathType Leaf) {
        $prior = [IO.Path]::GetFullPath((Join-Path $backupPath $rel))
        if (!$prior.StartsWith($projectDirectory+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Config backup is outside this project.' }
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($prior)) | Out-Null
        Move-Item -LiteralPath $oldConfig -Destination $prior
    }
}
$receiptFile = Join-Path $deploymentPath 'pcl-installation-1201.json'
[pscustomobject]@{
    installedAt = (Get-Date).ToString('o')
    versionDirectory = $versionPath
    minecraft = $runtimeLock.target.minecraft
    forge = $runtimeLock.target.forge
    replacedPreviousVersions = $replacedVersions
    removedRetiredMods = $retiredVersions
    fieldGuideFiles = @($bookFiles | ForEach-Object { $_.FullName.Substring($bookSource.Length+1) })
    fieldworkDataFiles = @($dataFiles | ForEach-Object { $_.relative })
    previousVersionBackup = $backupPath
    mods = @($plan | Select-Object modId, destination, sizeBytes, sha256)
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptFile -Encoding UTF8
[pscustomobject]@{ copied = $copied; total = $plan.Count; modsDirectory = $modsPath; receipt = $receiptFile } | ConvertTo-Json
