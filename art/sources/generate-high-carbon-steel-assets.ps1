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

foreach ($piece in @('helmet','chestplate','leggings','boots','sword','axe')) {
    Recolor (Join-Path $itemRoot ("tempered_steel_$piece.png")) (Join-Path $itemRoot ("tempered_high_carbon_steel_$piece.png"))
}
foreach ($layer in @(1,2)) {
    Recolor (Join-Path $armorRoot ("tempered_steel_layer_$layer.png")) (Join-Path $armorRoot ("tempered_high_carbon_steel_layer_$layer.png"))
}
& python (Join-Path $PSScriptRoot 'material_pixel_style.py')
if ($LASTEXITCODE -ne 0) { throw 'Material icon generation failed.' }
Write-Host 'Generated high-carbon equipment icons and armor layers; regenerated current material icons.'
