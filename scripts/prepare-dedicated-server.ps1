[CmdletBinding()]
param(
    [string]$ClientModsDirectory = (Join-Path $env:USERPROFILE 'Desktop\.minecraft\versions\1.19.2-Forge_43.5.0\mods'),
    [string]$ForgeInstallerPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$projectDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
$outputDirectory = [IO.Path]::GetFullPath((Join-Path $projectDirectory 'build-isolated\dedicated-server-20261018'))
$allowedRoot = [IO.Path]::GetFullPath((Join-Path $projectDirectory 'build-isolated')).TrimEnd('\') + '\'
if (-not $outputDirectory.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output path escaped the project build-isolated directory.'
}

$ClientModsDirectory = (Resolve-Path -LiteralPath $ClientModsDirectory).Path
if (-not (Test-Path -LiteralPath $ClientModsDirectory -PathType Container)) {
    throw "Client mods directory does not exist: $ClientModsDirectory"
}

$minecraftVersion = '1.19.2'
$forgeVersion = '43.5.0'
$forgeCoordinate = "$minecraftVersion-$forgeVersion"
$installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/$forgeCoordinate/forge-$forgeCoordinate-installer.jar"
$installerSha1 = '0a45a1d96388403c5f16556df81165ba4d96987a'
$requiredServerMods = @('ic2newera', 'ic2', 'carbonconfig', 'create')
$optionalSharedMods = @('projecte', 'curios', 'theoneprobe')
$knownClientOnlyMods = @('jei')
$selectedModIds = @($requiredServerMods + $optionalSharedMods)

function Read-ModMetadata([string]$jarPath) {
    $archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $archive.GetEntry('META-INF/mods.toml')
        if ($null -eq $entry) { throw "No META-INF/mods.toml in $jarPath" }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $toml = $reader.ReadToEnd() } finally { $reader.Dispose() }

        $declared = [System.Collections.Generic.List[string]]::new()
        foreach ($match in [regex]::Matches($toml, '(?ms)^\[\[mods\]\](?<body>.*?)(?=^\[|\z)')) {
            $id = [regex]::Match($match.Groups['body'].Value, '(?m)^\s*modId\s*=\s*"([^"]+)"')
            if ($id.Success) { $declared.Add($id.Groups[1].Value) }
        }
        if ($declared.Count -eq 0) { throw "No declared mod ID in $jarPath" }

        $dependencies = [System.Collections.Generic.List[object]]::new()
        foreach ($match in [regex]::Matches($toml, '(?ms)^\[\[dependencies\.(?<parent>[^\]]+)\]\]\s*(?<body>.*?)(?=^\[|\z)')) {
            $body = $match.Groups['body'].Value
            $id = [regex]::Match($body, '(?m)^\s*modId\s*=\s*"([^"]+)"')
            if (-not $id.Success) { continue }
            $mandatory = [regex]::Match($body, '(?m)^\s*mandatory\s*=\s*(true|false)')
            $side = [regex]::Match($body, '(?m)^\s*side\s*=\s*"([A-Z]+)"')
            $dependencies.Add([pscustomobject]@{
                parent = $match.Groups['parent'].Value
                modId = $id.Groups[1].Value
                mandatory = $mandatory.Success -and $mandatory.Groups[1].Value -eq 'true'
                side = if ($side.Success) { $side.Groups[1].Value } else { 'BOTH' }
            })
        }
        return [pscustomobject]@{ modIds = @($declared); dependencies = @($dependencies) }
    } finally { $archive.Dispose() }
}

$available = @{}
$unclassified = [System.Collections.Generic.List[string]]::new()
foreach ($jar in Get-ChildItem -LiteralPath $ClientModsDirectory -File -Filter '*.jar') {
    $metadata = Read-ModMetadata $jar.FullName
    foreach ($id in $metadata.modIds) {
        if ($available.ContainsKey($id)) { throw "Duplicate mod ID '$id' in client mods folder." }
        $available[$id] = [pscustomobject]@{ file = $jar; metadata = $metadata }
        if ($id -notin $selectedModIds -and $id -notin $knownClientOnlyMods) { $unclassified.Add($id) }
    }
}
if ($unclassified.Count -gt 0) {
    throw ('Unclassified installed mod IDs require manual server-side review: ' + (($unclassified | Sort-Object -Unique) -join ', '))
}
foreach ($id in $requiredServerMods) {
    if (-not $available.ContainsKey($id)) { throw "Required server mod '$id' was not found in $ClientModsDirectory" }
}

$selected = @{}
foreach ($id in $selectedModIds) { if ($available.ContainsKey($id)) { $selected[$id] = $available[$id] } }
foreach ($entry in $selected.GetEnumerator()) {
    foreach ($dependency in $entry.Value.metadata.dependencies) {
        if ($dependency.parent -notin $entry.Value.metadata.modIds -or -not $dependency.mandatory -or $dependency.side -eq 'CLIENT') { continue }
        if ($dependency.modId -in @('minecraft', 'forge')) { continue }
        if (-not $selected.ContainsKey($dependency.modId)) {
            throw "Missing mandatory server dependency '$($dependency.modId)' required by '$($entry.Key)' (side $($dependency.side))."
        }
    }
}

$java = Get-Command java -ErrorAction Stop
$javaVersionText = (& $java.Source -version 2>&1 | Out-String)
if ($javaVersionText -notmatch 'version "17(?:\.|"|\+)') {
    throw "Java 17 is required for this target. Current java -version output: $javaVersionText"
}

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$toolsDirectory = Join-Path $outputDirectory 'tools'
New-Item -ItemType Directory -Path $toolsDirectory -Force | Out-Null
if ([string]::IsNullOrWhiteSpace($ForgeInstallerPath)) {
    $ForgeInstallerPath = Join-Path $toolsDirectory "forge-$forgeCoordinate-installer.jar"
}
$ForgeInstallerPath = [IO.Path]::GetFullPath($ForgeInstallerPath)
if (-not (Test-Path -LiteralPath $ForgeInstallerPath -PathType Leaf)) {
    if ($ForgeInstallerPath -ne [IO.Path]::GetFullPath((Join-Path $toolsDirectory "forge-$forgeCoordinate-installer.jar"))) {
        throw "Specified Forge installer does not exist: $ForgeInstallerPath"
    }
    Invoke-WebRequest -Uri $installerUrl -OutFile $ForgeInstallerPath -UseBasicParsing
}
$actualInstallerSha1 = (Get-FileHash -LiteralPath $ForgeInstallerPath -Algorithm SHA1).Hash.ToLowerInvariant()
if ($actualInstallerSha1 -ne $installerSha1) { throw "Forge installer SHA-1 mismatch. Expected $installerSha1, got $actualInstallerSha1" }

$forgeArgs = Join-Path $outputDirectory "libraries\net\minecraftforge\forge\$forgeCoordinate\win_args.txt"
$forgeRun = Join-Path $outputDirectory 'run.bat'
if (-not (Test-Path -LiteralPath $forgeArgs -PathType Leaf) -or -not (Test-Path -LiteralPath $forgeRun -PathType Leaf)) {
    Push-Location $outputDirectory
    try {
        & $java.Source -jar $ForgeInstallerPath --installServer
        if ($LASTEXITCODE -ne 0) { throw "Forge server installer failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $forgeArgs -PathType Leaf) -or -not (Test-Path -LiteralPath $forgeRun -PathType Leaf)) {
    throw 'Forge installer exited without producing run.bat and the expected Forge win_args.txt.'
}

$modsOutput = Join-Path $outputDirectory 'mods'
New-Item -ItemType Directory -Path $modsOutput -Force | Out-Null
$copyAudit = [System.Collections.Generic.List[object]]::new()
foreach ($id in ($selected.Keys | Sort-Object)) {
    $source = $selected[$id].file
    $destination = Join-Path $modsOutput $source.Name
    $sourceHash = (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash
    if (Test-Path -LiteralPath $destination) {
        $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
        if ($destinationHash -ne $sourceHash) {
            $backupDirectory = Join-Path $outputDirectory ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
            New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
            Copy-Item -LiteralPath $destination -Destination (Join-Path $backupDirectory $source.Name)
        } else { $copyAudit.Add([pscustomobject]@{ modId = $id; fileName = $source.Name; sha256 = $sourceHash; result = 'unchanged' }); continue }
    }
    Copy-Item -LiteralPath $source.FullName -Destination $destination -Force
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) { throw "Copied JAR hash mismatch for '$id'." }
    $copyAudit.Add([pscustomobject]@{ modId = $id; fileName = $source.Name; sha256 = $sourceHash; result = 'copied' })
}

$unexpectedOutput = [System.Collections.Generic.List[string]]::new()
foreach ($jar in Get-ChildItem -LiteralPath $modsOutput -File -Filter '*.jar') {
    $metadata = Read-ModMetadata $jar.FullName
    if (@($metadata.modIds | Where-Object { $_ -in $selectedModIds }).Count -eq 0) { $unexpectedOutput.Add($jar.Name) }
}
if ($unexpectedOutput.Count -gt 0) { throw ('Unexpected/unclassified JARs already exist in server mods folder: ' + ($unexpectedOutput -join ', ')) }

$jvmArgsPath = Join-Path $outputDirectory 'user_jvm_args.txt'
if (-not (Test-Path -LiteralPath $jvmArgsPath)) { Set-Content -LiteralPath $jvmArgsPath -Value '' -Encoding ASCII }
$jvmLines = @(Get-Content -LiteralPath $jvmArgsPath)
$appendJvmArgs = [System.Collections.Generic.List[string]]::new()
foreach ($managedArg in @('-Xms2G', '-Xmx6G', '-XX:+UseG1GC')) {
    $optionPrefix = switch -Regex ($managedArg) { '^-Xms' { '-Xms' } '^-Xmx' { '-Xmx' } default { $managedArg } }
    $hasOption = $false
    foreach ($line in $jvmLines) {
        if ($line.Trim() -and -not $line.Trim().StartsWith('#') -and $line.Trim().StartsWith($optionPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            $hasOption = $true; break
        }
    }
    if (-not $hasOption) { $appendJvmArgs.Add($managedArg); $jvmLines += $managedArg }
}
if ($appendJvmArgs.Count -gt 0) {
    $existingJvmText = [IO.File]::ReadAllText($jvmArgsPath)
    $separator = if ($existingJvmText.Length -gt 0 -and -not $existingJvmText.EndsWith("`n") -and -not $existingJvmText.EndsWith("`r")) { [Environment]::NewLine } else { '' }
    [IO.File]::AppendAllText($jvmArgsPath, $separator + ($appendJvmArgs -join [Environment]::NewLine) + [Environment]::NewLine, [Text.Encoding]::ASCII)
}
$eulaPath = Join-Path $outputDirectory 'eula.txt'
if (-not (Test-Path -LiteralPath $eulaPath)) { Set-Content -LiteralPath $eulaPath -Value 'eula=false' -Encoding ASCII }
$eulaState = if (Select-String -LiteralPath $eulaPath -Pattern '^\s*eula\s*=\s*true\s*$' -Quiet) { 'pre-existing true preserved; no acceptance written by this script' } else { 'false or not accepted; no acceptance written by this script' }

$propertiesPath = Join-Path $outputDirectory 'server.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    @(
        'motd=IC2 New Era test server',
        'server-ip=',
        'server-port=25565',
        'online-mode=true',
        'white-list=true',
        'enforce-whitelist=true',
        'enforce-secure-profile=true',
        'enable-rcon=false',
        'enable-query=false',
        'enable-command-block=false',
        'max-players=8',
        'view-distance=8',
        'simulation-distance=6',
        'spawn-protection=16',
        'level-name=world'
    ) | Set-Content -LiteralPath $propertiesPath -Encoding ASCII
}

$launcherPath = Join-Path $outputDirectory 'start-server.bat'
$launcher = @'
@echo off
setlocal
cd /d "%~dp0"
if not exist "eula.txt" (
  echo EULA file is missing. Read Minecraft's EULA, then create eula.txt yourself if you agree.
  pause
  exit /b 2
)
findstr /R /I /C:"^[ ]*eula[ ]*=[ ]*true[ ]*$" "eula.txt" >nul
if errorlevel 1 (
  echo EULA has not been accepted. Read the Minecraft EULA and manually set eula=true only if you agree.
  pause
  exit /b 2
)
set "JAVA_VERSION="
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /I "version"') do set "JAVA_VERSION=%%~V"
if not "%JAVA_VERSION:~0,2%"=="17" (
  echo Java 17 is required. Install/select a Java 17 runtime, then retry.
  pause
  exit /b 3
)
call "run.bat" nogui
pause
'@
Set-Content -LiteralPath $launcherPath -Value $launcher -Encoding ASCII

$voiceSourceDirectory = Join-Path $projectDirectory 'tools\robot_voice'
$voiceOutputDirectory = Join-Path $outputDirectory 'tools\robot_voice'
New-Item -ItemType Directory -Path $voiceOutputDirectory -Force | Out-Null
foreach ($sourceFile in Get-ChildItem -LiteralPath $voiceSourceDirectory -File) {
    if ($sourceFile.Extension -in @('.py', '.ps1') -or $sourceFile.Name -in @('README.md', 'ic2newera-voice-runtime.example.json')) {
        Copy-Item -LiteralPath $sourceFile.FullName -Destination (Join-Path $voiceOutputDirectory $sourceFile.Name) -Force
    }
}
$voiceConfigPath = Join-Path $voiceOutputDirectory 'ic2newera-voice-runtime.json'
if (-not (Test-Path -LiteralPath $voiceConfigPath)) {
    Copy-Item -LiteralPath (Join-Path $voiceOutputDirectory 'ic2newera-voice-runtime.example.json') -Destination $voiceConfigPath
}

$audit = [pscustomobject]@{
    preparedAt = (Get-Date).ToString('o')
    minecraft = $minecraftVersion
    forge = $forgeVersion
    javaMajorRequired = 17
    sourceModsDirectory = $ClientModsDirectory
    officialForgeInstallerUrl = $installerUrl
    officialInstallerSha1 = $actualInstallerSha1
    officialInstallerSha256 = (Get-FileHash -LiteralPath $ForgeInstallerPath -Algorithm SHA256).Hash
    selectedMods = @($copyAudit | Sort-Object modId)
    excludedClientOnlyMods = @($knownClientOnlyMods | Where-Object { $available.ContainsKey($_) } | ForEach-Object {
        [pscustomobject]@{ modId = $_; fileName = $available[$_].file.Name; reason = 'Client-only optional integration; excluded from dedicated server.' }
    })
    eulaState = $eulaState
    serverWasLaunched = $false
    launchFlagScope = 'This preparation script does not launch Minecraft. Runtime acceptance is recorded separately.'
    standaloneVoiceTools = $voiceOutputDirectory
    worldDirectoryCreated = (Test-Path -LiteralPath (Join-Path $outputDirectory 'world'))
}
$audit | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputDirectory 'server-preparation-audit.json') -Encoding UTF8

[pscustomobject]@{
    outputDirectory = $outputDirectory
    minecraft = $minecraftVersion
    forge = $forgeVersion
    javaMajorRequired = 17
    includedModIds = @($copyAudit.modId | Sort-Object)
    excludedClientOnlyModIds = @($knownClientOnlyMods | Where-Object { $available.ContainsKey($_) })
    eulaState = $eulaState
    serverWasLaunched = $false
} | ConvertTo-Json -Depth 5
