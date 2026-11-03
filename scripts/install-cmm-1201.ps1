[CmdletBinding()]
param([switch] $Apply)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$work = Join-Path $projectRoot 'build-isolated/player-packaging/20261103-r62-balanced-terrain'
$lock = Get-Content -LiteralPath (Join-Path $projectRoot 'scripts/world-content-lock-1201.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$instance = Join-Path $env:USERPROFILE 'Desktop/.minecraft/versions/1.20.1-Forge_47.4.28'
$optionsPath = Join-Path $instance 'options.txt'
if (-not (Test-Path -LiteralPath (Join-Path $instance '1.20.1-Forge_47.4.28.json'))) { throw 'Target Forge profile is missing.' }
foreach ($pack in $lock.resourcepacks) {
    $source = Join-Path $work ('sources/' + $pack.file)
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $pack.sha256) { throw 'Resource archive hash mismatch.' }
}
$options = [IO.File]::ReadAllText($optionsPath)
$matches = [regex]::Matches($options, '(?m)^resourcePacks:([^\r\n]*)')
if ($matches.Count -ne 1) { throw 'Expected exactly one resourcePacks setting.' }
$names = @($lock.resourcepacks | ForEach-Object { 'file/' + $_.file })
$active = @($matches[0].Groups[1].Value | ConvertFrom-Json)
$selected = @($active | Where-Object { $_ -notin $names }) + $names
if ('file/Soartex Grove 1.0.4.zip' -notin $selected) { throw 'Install/select the existing Grove pack first.' }
$line = 'resourcePacks:' + (ConvertTo-Json -InputObject @($selected) -Compress)
$updated = [regex]::Replace($options, '(?m)^resourcePacks:[^\r\n]*', $line)
Write-Output $line
if (-not $Apply) { return }
if (@(Get-Process -Name java,javaw -ErrorAction SilentlyContinue).Count -gt 0) { throw 'Java is running. Close Minecraft before installing.' }
foreach ($target in @($instance, (Join-Path $instance 'resourcepacks'), $optionsPath)) {
    if ((Get-Item -LiteralPath $target).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse target rejected.' }
}
$backup = Join-Path $work ('client-backup-cmm-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backup | Out-Null
Copy-Item -LiteralPath $optionsPath -Destination (Join-Path $backup 'options.txt')
foreach ($pack in $lock.resourcepacks) {
    $destination = Join-Path $instance ('resourcepacks/' + $pack.file)
    if (Test-Path -LiteralPath $destination) {
        if ((Get-Item -LiteralPath $destination).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse file rejected.' }
        Copy-Item -LiteralPath $destination -Destination (Join-Path $backup $pack.file)
    }
    Copy-Item -LiteralPath (Join-Path $work ('sources/' + $pack.file)) -Destination $destination -Force
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $pack.sha256) { throw 'Installed resource hash differs.' }
}
$temp = $optionsPath + '.cmm-' + [guid]::NewGuid().ToString('N') + '.tmp'
[IO.File]::WriteAllText($temp, $updated, [Text.UTF8Encoding]::new($false))
[IO.File]::Replace($temp, $optionsPath, (Join-Path $backup 'options-replaced.txt'))
Write-Output ('Installed CMM above Grove. Backup: ' + $backup)
