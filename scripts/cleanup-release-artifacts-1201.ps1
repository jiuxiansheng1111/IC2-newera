[CmdletBinding()]
param([switch] $Apply)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$dist = (Resolve-Path -LiteralPath (Join-Path $root 'dist')).Path.TrimEnd('\')
$cache = Join-Path $root 'build-isolated/player-packaging/20261103-r61-landscape/release-inputs'
$retainedBuildInputs = @(
    'IC2-New-Era-Windows-1.20.1-r60-Complete.mrpack',
    'IC2-New-Era-Server-1.20.1-r60-Voice.zip'
)
$archiveNames = @($retainedBuildInputs) + @($retainedBuildInputs | ForEach-Object { $_ + '.sha256' })
$old = @(Get-ChildItem -LiteralPath $dist -File | Where-Object {
    $_.Name -match '^IC2-New-Era-(Windows|Server)-1\.20\.1-r60-' -or
    $_.Name -match '^IC2-New-Era-Linux-Fixed-Voice-r48-20261028\.zip(?:\.sha256)?$'
})
foreach ($file in $old) {
    $resolved = (Resolve-Path -LiteralPath $file.FullName).Path
    if (-not $resolved.StartsWith($dist + '\', [StringComparison]::OrdinalIgnoreCase) -or
        ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Cleanup target is outside the intended dist folder or is a link.'
    }
}
Write-Output ('Old public deliverables to remove: ' + ($old.Name -join ', '))
Write-Output 'Two pinned r60 build inputs are retained in the private build cache for r61 reproduction; current r61 packages and standalone current JARs stay in dist.'
if (-not $Apply) { Write-Output 'Preview only. Pass -Apply after the r61 server starts successfully.'; return }
if (-not (Test-Path -LiteralPath (Join-Path $dist 'IC2-New-Era-Windows-1.20.1-r61-Grove.mrpack')) -or
    -not (Test-Path -LiteralPath (Join-Path $dist 'IC2-New-Era-Server-1.20.1-r61-UltraTerra-Voice.zip'))) {
    throw 'Current r61 packages must exist before removing previous releases.'
}
New-Item -ItemType Directory -Path $cache -Force | Out-Null
$deleted = @()
$archived = @()
foreach ($file in $old) {
    if ($file.Name -in $archiveNames) {
        $destination = [IO.Path]::GetFullPath((Join-Path $cache $file.Name))
        if (-not $destination.StartsWith([IO.Path]::GetFullPath($cache).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Build input backup path is invalid.'
        }
        if (Test-Path -LiteralPath $destination) { throw 'Existing build input backup must be preserved.' }
        Move-Item -LiteralPath $file.FullName -Destination $destination
        $archived += $file.Name
    } else {
        Remove-Item -LiteralPath $file.FullName -Force
        $deleted += $file.Name
    }
}
$receipt = [ordered]@{
    completedAt = (Get-Date).ToString('o')
    removedFromDist = @($old.Name)
    deletedObsoletePackages = $deleted
    retainedPrivateBuildInputs = $archived
    currentRevision = 'r61'
}
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path (Split-Path -Parent $cache) 'release-cleanup.json') -Encoding utf8
$receipt | ConvertTo-Json -Depth 5
