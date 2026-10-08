param([Parameter(Mandatory = $true)][string] $OutputPath)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$gameAssets = Join-Path $projectRoot 'src/main/resources/assets'
$catalog = (Get-Content -LiteralPath (Join-Path $projectRoot 'art/model-catalog.json') -Raw -Encoding UTF8 |
        ConvertFrom-Json -AsHashtable).models

function Read-Model([string] $assetsRoot, [string] $relativePath, [int] $depth = 0) {
    if ($depth -gt 16) { throw 'Model parent chain is too deep' }
    $path = Join-Path $assetsRoot $relativePath
    $child = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
    $combined = @{}
    if ($child.parent -and $child.parent.StartsWith('ic2newera:')) {
        $parentPath = 'ic2newera/models/' + $child.parent.Split(':', 2)[1] + '.json'
        $combined = Read-Model $assetsRoot $parentPath ($depth + 1)
    }
    foreach ($key in $child.Keys) {
        if (($key -eq 'textures' -or $key -eq 'display') -and $combined.ContainsKey($key)) {
            foreach ($entry in $child[$key].Keys) { $combined[$key][$entry] = $child[$key][$entry] }
        } else { $combined[$key] = $child[$key] }
    }
    return $combined
}

$models = [Collections.Generic.List[object]]::new()
$textures = [ordered]@{}
foreach ($entry in $catalog) {
    $assetsRoot = Join-Path $projectRoot $entry.assetRoot
    $model = Read-Model $assetsRoot $entry.path
    $generated = $model.parent -in @('minecraft:item/generated', 'item/generated', 'minecraft:item/handheld', 'item/handheld')
    if ((-not $model.elements -and -not $generated) -or -not $model.textures) { throw "No geometry/textures for $($entry.id)" }
    foreach ($textureRef in $model.textures.Values) {
        if ($textureRef.StartsWith('#')) { continue }
        $parts = $textureRef.Split(':', 2)
        if ($parts.Count -ne 2) { throw "Unqualified texture: $textureRef" }
        $texturePath = Join-Path $assetsRoot ($parts[0] + '/textures/' + $parts[1] + '.png')
        if (-not (Test-Path -LiteralPath $texturePath -PathType Leaf)) { throw "Missing texture for $($entry.id): $textureRef" }
        $encoded = 'data:image/png;base64,' + [Convert]::ToBase64String([IO.File]::ReadAllBytes($texturePath))
        if ($textures.Contains($textureRef) -and $textures[$textureRef] -ne $encoded) { throw "Conflicting texture: $textureRef" }
        $textures[$textureRef] = $encoded
    }
    $models.Add([ordered]@{id=$entry.id;name=$entry.name;category=$entry.category;status=$entry.status;description=$entry.description;model=$model})
}

$data = [ordered]@{models=@($models.ToArray());textures=$textures} | ConvertTo-Json -Depth 64 -Compress
$data = $data.Replace('<', '\u003c')
$template = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'model-gallery.fragment.html') -Raw -Encoding UTF8
$fragment = $template.Replace('MODEL_CATALOG_JSON', $data)
$absoluteOutput = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $absoluteOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
[IO.File]::WriteAllText($absoluteOutput, $fragment, [Text.UTF8Encoding]::new($false))
[PSCustomObject]@{Models=$models.Count; Textures=$textures.Count; Bytes=(Get-Item -LiteralPath $absoluteOutput).Length; Path=$absoluteOutput}
