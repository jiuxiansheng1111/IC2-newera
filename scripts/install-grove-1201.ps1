[CmdletBinding()]
param([switch] $Apply)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$source = Join-Path $projectRoot 'build-isolated/player-packaging/20261103-r61-landscape/sources/Grove.zip'
$expected = '33CF7B9E47580A9138123F0377B4BBB4A031757E1B389E00485ECFD743547EAB'
$instance = Join-Path $env:USERPROFILE 'Desktop/.minecraft/versions/1.20.1-Forge_47.4.28'
$optionsPath = Join-Path $instance 'options.txt'
$filename = 'Soartex Grove 1.0.4.zip'
$destination = Join-Path $instance "resourcepacks/$filename"
if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $expected) {
    throw 'The official Grove 1.0.4 archive does not match the verified download.'
}
if (-not (Test-Path -LiteralPath (Join-Path $instance '1.20.1-Forge_47.4.28.json'))) {
    throw 'The isolated 1.20.1 Forge profile is missing.'
}
$options = [IO.File]::ReadAllText($optionsPath)
if ([regex]::Matches($options, '(?m)^resourcePacks:.*$').Count -ne 1) {
    throw 'Expected one resourcePacks setting; options were left unchanged.'
}
$selected = 'resourcePacks:["vanilla","file/' + $filename + '"]'
$updated = [regex]::Replace($options, '(?m)^resourcePacks:[^\r\n]*', $selected)
Write-Output "Grove: $source -> $destination"
Write-Output $selected
if (-not $Apply) { Write-Output 'Preview only. Use -Apply to install.'; return }
if (@(Get-Process -Name java,javaw -ErrorAction SilentlyContinue).Count -gt 0) {
    throw 'Java is running. No Minecraft files were changed.'
}
$backup = Join-Path $projectRoot ('build-isolated/player-packaging/20261103-r61-landscape/client-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backup | Out-Null
Copy-Item -LiteralPath $optionsPath -Destination (Join-Path $backup 'options.txt')
New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
if (Test-Path -LiteralPath $destination) {
    Copy-Item -LiteralPath $destination -Destination (Join-Path $backup $filename)
}
Copy-Item -LiteralPath $source -Destination $destination -Force
if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $expected) {
    throw 'Installed resource pack checksum differs. Original options are backed up.'
}
$temp = "$optionsPath.grove-$([guid]::NewGuid().ToString('N')).tmp"
[IO.File]::WriteAllText($temp, $updated, [Text.UTF8Encoding]::new($false))
[IO.File]::Replace($temp, $optionsPath, (Join-Path $backup 'options-replaced.txt'))
Write-Output "Installed and selected Grove. Backup: $backup"
