param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetRoot = Join-Path $ProjectRoot 'src/main/resources/assets/ic2newera'
$itemRoot = Join-Path $assetRoot 'textures/item'
$armorRoot = Join-Path $assetRoot 'textures/models/armor'
New-Item -ItemType Directory -Force -Path $itemRoot, $armorRoot | Out-Null

# Recolor project-authored tempered-steel assets so the new grade keeps the same
# equipment silhouettes and UV layout without importing external art.
$palette = @('#11191F','#202A31','#34414A','#4C5B65','#6A7A83','#91A0A7','#BAC5C9','#E3E9EA')
function Get-Color([string]$hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }
function Recolor([string]$sourcePath, [string]$targetPath) {
    $src = [System.Drawing.Bitmap]::new($sourcePath)
    try {
        $dst = [System.Drawing.Bitmap]::new($src.Width,$src.Height,[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        for ($y=0; $y -lt $src.Height; $y++) { for ($x=0; $x -lt $src.Width; $x++) {
            $old=$src.GetPixel($x,$y)
            if ($old.A -eq 0) { $dst.SetPixel($x,$y,[System.Drawing.Color]::FromArgb(0,0,0,0)); continue }
            $gray=0.2126*$old.R+0.7152*$old.G+0.0722*$old.B
            $pos=$gray/255.0*($palette.Count-1); $idx=[Math]::Min($palette.Count-2,[Math]::Floor($pos)); $blend=$pos-$idx
            $a=Get-Color $palette[$idx]; $b=Get-Color $palette[$idx+1]
            $r=[int][Math]::Round($a.R+($b.R-$a.R)*$blend); $g=[int][Math]::Round($a.G+($b.G-$a.G)*$blend); $bl=[int][Math]::Round($a.B+($b.B-$a.B)*$blend)
            $dst.SetPixel($x,$y,[System.Drawing.Color]::FromArgb($old.A,$r,$g,$bl))
        }}
        $dst.Save($targetPath,[System.Drawing.Imaging.ImageFormat]::Png); $dst.Dispose()
    } finally { $src.Dispose() }
}

function Add-TemperingOxide([string]$targetPath) {
    $bmp = [System.Drawing.Bitmap]::new($targetPath)
    $memory = [System.IO.MemoryStream]::new()
    try {
        # A small straw-to-blue tempering band on the cooled high-carbon bar.
        # These pixel marks are not applied to the untreated steel ingot.
        $marks = @(
            @{x=9;y=14;c='#B97937'}, @{x=10;y=13;c='#D39A54'},
            @{x=11;y=12;c='#7695A4'}, @{x=12;y=12;c='#9DB7C0'},
            @{x=13;y=11;c='#526B7A'}, @{x=14;y=11;c='#8DA7B2'},
            @{x=15;y=10;c='#3C5566'}, @{x=16;y=10;c='#718B98'},
            @{x=17;y=10;c='#283943'}
        )
        foreach ($mark in $marks) {
            if ($bmp.GetPixel($mark.x,$mark.y).A -gt 0) {
                $color = [System.Drawing.ColorTranslator]::FromHtml($mark.c)
                $bmp.SetPixel($mark.x,$mark.y,$color)
            }
        }
        $bmp.Save($memory,[System.Drawing.Imaging.ImageFormat]::Png)
        $bytes = $memory.ToArray()
    } finally { $bmp.Dispose(); $memory.Dispose() }
    [System.IO.File]::WriteAllBytes($targetPath,$bytes)
}

foreach ($name in @('charge','billet','ingot')) {
    $sourceName = if ($name -eq 'charge') { 'carbon_steel_charge' } elseif ($name -eq 'billet') { 'carbon_steel_billet' } else { 'tempered_steel_ingot' }
    $targetName = if ($name -eq 'ingot') { 'tempered_high_carbon_steel_ingot' } else { "high_carbon_steel_$name" }
    Recolor (Join-Path $itemRoot ($sourceName+'.png')) (Join-Path $itemRoot ($targetName+'.png'))
    if ($name -eq 'ingot') { Add-TemperingOxide (Join-Path $itemRoot ($targetName+'.png')) }
}
foreach ($piece in @('helmet','chestplate','leggings','boots','sword','axe')) {
    Recolor (Join-Path $itemRoot ("tempered_steel_$piece.png")) (Join-Path $itemRoot ("tempered_high_carbon_steel_$piece.png"))
}
foreach ($layer in @(1,2)) {
    Recolor (Join-Path $armorRoot ("tempered_steel_layer_$layer.png")) (Join-Path $armorRoot ("tempered_high_carbon_steel_layer_$layer.png"))
}
Write-Host 'Generated original high-carbon steel charge, billet, ingot, six equipment icons, and two armor layers from project-authored assets.'
