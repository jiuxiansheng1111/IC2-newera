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

function Draw-Ingot([string]$name, [string]$alloy) {
    $bmp = New-Canvas
    $palette = Get-Palette $alloy
    $outline=C $palette[0]; $dark=C $palette[1]; $mid=C $palette[3]; $light=C $palette[6]; $shine=C $palette[7]
    $rows = @(@(10,21),@(8,23),@(6,25),@(5,26),@(4,27),@(5,26),@(5,26),@(5,26),@(6,25),@(7,24),@(9,22),@(11,20))
    for ($i=0; $i -lt $rows.Count; $i++) { $y=9+$i; Fill $bmp $rows[$i][0] $y ($rows[$i][1]-$rows[$i][0]+1) 1 $outline }
    for ($i=1; $i -lt ($rows.Count-1); $i++) {
        $y=9+$i; Fill $bmp ($rows[$i][0]+1) $y ($rows[$i][1]-$rows[$i][0]-1) 1 $dark
        if ($i -le 3) { Fill $bmp ($rows[$i][0]+2) $y ($rows[$i][1]-$rows[$i][0]-4) 1 $light }
        elseif ($i -le 7) { Fill $bmp ($rows[$i][0]+2) $y ($rows[$i][1]-$rows[$i][0]-4) 1 $mid }
    }
    Fill $bmp 8 11 2 7 $shine
    Fill $bmp 10 17 5 1 $light
    Fill $bmp 22 15 2 5 $outline
    if ($alloy -eq 'carbon_steel') { Fill $bmp 17 18 4 2 (C '#1B2328'); Put $bmp 18 19 (C '#66747C') }
    Save-Canvas $bmp (Join-Path $itemTextureRoot ($name + '.png'))
}

function Draw-Charge([string]$name, [string]$kind) {
    $bmp = New-Canvas
    $base = if ($kind -eq 'carbon') { '#20282D' } elseif ($kind -eq 'bronze') { '#996433' } else { '#39434A' }
    $light = if ($kind -eq 'carbon') { '#707B80' } elseif ($kind -eq 'bronze') { '#E1B46B' } else { '#A9B5BA' }
    $shade = if ($kind -eq 'bronze') { '#53351F' } else { '#20272C' }
    # A small weighing dish and a controlled heap of granular additive.
    Fill $bmp 5 21 22 3 (C $shade)
    Fill $bmp 7 19 18 2 (C $base)
    Fill $bmp 9 17 14 2 (C $base)
    Fill $bmp 12 15 8 2 (C $light)
    Fill $bmp 14 13 4 2 (C $light)
    Fill $bmp 7 24 18 2 (C '#59656B')
    Fill $bmp 9 26 14 1 (C '#20272B')
    foreach($p in @(@(9,19),@(13,17),@(20,18),@(23,20),@(17,15))){ Put $bmp $p[0] $p[1] (C $light) }
    if ($kind -eq 'carbon') { Fill $bmp 13 18 3 2 (C '#111719'); Put $bmp 18 18 (C '#101518') }
    Save-Canvas $bmp (Join-Path $itemTextureRoot ($name + '.png'))
}

function Draw-CarbonAdditivePacket([string]$name) {
    $bmp = New-Canvas
    # A sealed measured packet of graphite powder; visually distinct from the
    # open foundry dish used for a full steel batch charge.
    Fill $bmp 8 7 17 2 (C '#283137')
    Fill $bmp 6 9 21 15 (C '#394348')
    Fill $bmp 8 10 17 12 (C '#C0B18A')
    Fill $bmp 8 10 17 2 (C '#E4D6B0')
    Fill $bmp 9 12 15 8 (C '#A99A75')
    Fill $bmp 10 14 13 4 (C '#252B2C')
    Fill $bmp 12 13 8 1 (C '#161C1E')
    Fill $bmp 11 18 10 2 (C '#414746')
    Fill $bmp 7 10 1 13 (C '#4A504A')
    Fill $bmp 25 10 1 13 (C '#55584D')
    # Batch seam and a tiny graphite spill make the small packet readable.
    Fill $bmp 11 21 11 1 (C '#E4D6B0')
    Fill $bmp 13 22 2 1 (C '#2A3030')
    Fill $bmp 17 22 2 1 (C '#2A3030')
    Put $bmp 7 25 (C '#6B6250')
    Put $bmp 25 25 (C '#6B6250')
    Save-Canvas $bmp (Join-Path $itemTextureRoot ($name + '.png'))
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

Draw-Ingot 'tempered_steel_ingot' 'tempered_steel'
Draw-Ingot 'aluminium_bronze_ingot' 'aluminium_bronze'
Draw-Ingot 'carbon_steel_billet' 'carbon_steel'
Draw-Charge 'carbon_steel_charge' 'carbon'
Draw-Charge 'aluminium_bronze_charge' 'bronze'
Draw-CarbonAdditivePacket 'carbon_additive'
foreach ($id in @('tempered_steel_ingot','aluminium_bronze_ingot','carbon_steel_billet')) { Write-Model $id 'minecraft:item/generated' $id }
foreach ($id in @('carbon_steel_charge','aluminium_bronze_charge','carbon_additive')) { Write-Model $id 'minecraft:item/generated' $id }

Write-Host 'Generated 12 recolored equipment icons, 4 armor layers, and 6 alloy material icons.'
