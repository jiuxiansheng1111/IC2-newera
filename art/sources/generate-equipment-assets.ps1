param(
    [Parameter(Mandatory = $true)]
    [string]$SourceClientJar,
    [string]$ProjectRoot
)

# Recolors only the vanilla 1.19.2 iron equipment pixels. The source jar is supplied by the caller;
# no machine-specific client jar path is stored in this project.
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path }
if (-not (Test-Path -LiteralPath $SourceClientJar)) { throw "Minecraft client jar not found: $SourceClientJar" }
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem

$assetRoot = Join-Path $ProjectRoot 'src/main/resources/assets/ic2newera'
$itemTextureRoot = Join-Path $assetRoot 'textures/item'
$armorTextureRoot = Join-Path $assetRoot 'textures/models/armor'
$itemModelRoot = Join-Path $assetRoot 'models/item'
$blockTextureRoot = Join-Path $assetRoot 'textures/block'
$blockModelRoot = Join-Path $assetRoot 'models/block'
$blockStateRoot = Join-Path $assetRoot 'blockstates'
New-Item -ItemType Directory -Force -Path $itemTextureRoot, $armorTextureRoot, $itemModelRoot, $blockTextureRoot, $blockModelRoot, $blockStateRoot | Out-Null

function C([string]$hex, [int]$alpha = 255) {
    $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
    [System.Drawing.Color]::FromArgb($alpha, $c.R, $c.G, $c.B)
}

function Put($bitmap, [int]$x, [int]$y, [System.Drawing.Color]$color) {
    if ($x -ge 0 -and $x -lt $bitmap.Width -and $y -ge 0 -and $y -lt $bitmap.Height) { $bitmap.SetPixel($x, $y, $color) }
}

function Fill($bitmap, [int]$x, [int]$y, [int]$w, [int]$h, [System.Drawing.Color]$color) {
    for ($py = $y; $py -lt ($y + $h); $py++) { for ($px = $x; $px -lt ($x + $w); $px++) { Put $bitmap $px $py $color } }
}

function New-Canvas([int]$width = 32, [int]$height = 32) {
    New-Object System.Drawing.Bitmap $width, $height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

function Save-Canvas($bitmap, [string]$path) {
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

function Get-Palette([string]$alloy) {
    if ($alloy -eq 'tempered_steel') {
        return @('#27313A','#36424B','#4A5962','#64747D','#829198','#A5B0B5','#CBD2D4','#F0F2EF')
    }
    return @('#3E2716','#573719','#765021','#94652A','#B17A30','#D2A142','#E9C66B','#FFF0A4')
}

function Recolor-Png([System.IO.Compression.ZipArchiveEntry]$entry, [string]$target, [string[]]$palette) {
    $stream = $entry.Open()
    try {
        $source = [System.Drawing.Bitmap]::new($stream)
        try {
            $out = New-Canvas $source.Width $source.Height
            for ($y = 0; $y -lt $source.Height; $y++) {
                for ($x = 0; $x -lt $source.Width; $x++) {
                    $old = $source.GetPixel($x, $y)
                    if ($old.A -eq 0) { Put $out $x $y ([System.Drawing.Color]::FromArgb(0,0,0,0)); continue }
                    $gray = [int][Math]::Round(0.2126 * $old.R + 0.7152 * $old.G + 0.0722 * $old.B)
                    $position = $gray / 255.0 * ($palette.Count - 1)
                    $index = [Math]::Min($palette.Count - 2, [Math]::Floor($position))
                    $blend = $position - $index
                    $a = C $palette[$index]; $b = C $palette[$index + 1]
                    $r = [int][Math]::Round($a.R + ($b.R - $a.R) * $blend)
                    $g = [int][Math]::Round($a.G + ($b.G - $a.G) * $blend)
                    $blue = [int][Math]::Round($a.B + ($b.B - $a.B) * $blend)
                    Put $out $x $y ([System.Drawing.Color]::FromArgb($old.A, $r, $g, $blue))
                }
            }
            Save-Canvas $out $target
        } finally { $source.Dispose() }
    } finally { $stream.Dispose() }
}

function Write-Model([string]$name, [string]$parent, [string]$texture) {
    $json = @{ parent = $parent; textures = @{ layer0 = "ic2newera:item/$texture" } } | ConvertTo-Json -Depth 5
    Set-Content -LiteralPath (Join-Path $itemModelRoot ($name + '.json')) -Value $json -Encoding utf8
}

$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $SourceClientJar))
try {
    $alloys = @('tempered_steel','aluminium_bronze')
    $equipment = @('helmet','chestplate','leggings','boots','sword','axe')
    foreach ($alloy in $alloys) {
        $palette = Get-Palette $alloy
        foreach ($layer in @(1,2)) {
            $entry = $archive.GetEntry("assets/minecraft/textures/models/armor/iron_layer_$layer.png")
            if (-not $entry) { throw "Missing vanilla iron armor layer $layer in source client jar" }
            Recolor-Png $entry (Join-Path $armorTextureRoot "${alloy}_layer_$layer.png") $palette
        }
        foreach ($piece in $equipment) {
            $entry = $archive.GetEntry("assets/minecraft/textures/item/iron_$piece.png")
            if (-not $entry) { throw "Missing vanilla iron item icon iron_$piece.png in source client jar" }
            $id = "${alloy}_$piece"
            Recolor-Png $entry (Join-Path $itemTextureRoot ($id + '.png')) $palette
            $parent = if ($piece -in @('sword','axe')) { 'minecraft:item/handheld' } else { 'minecraft:item/generated' }
            Write-Model $id $parent $id
        }
    }
} finally { $archive.Dispose() }

# Material icons are drawn from original pixel geometry by the shared 16px generator.
& python (Join-Path $PSScriptRoot 'material_pixel_style.py')
if ($LASTEXITCODE -ne 0) { throw 'Material icon generation failed.' }
Write-Host 'Generated equipment icons and armor layers; regenerated current metal material icons.'
