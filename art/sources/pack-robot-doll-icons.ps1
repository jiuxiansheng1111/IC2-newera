$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$manifest = Get-Content -LiteralPath (Join-Path $project 'art/robot-dolls/icon-generation.json') -Raw | ConvertFrom-Json
$destination = Join-Path $project 'src/main/resources/assets/ic2newera/textures/item'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$preview = [Drawing.Bitmap]::new(640, 350, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
$canvas = [Drawing.Graphics]::FromImage($preview)
$canvas.Clear([Drawing.Color]::FromArgb(255, 31, 38, 49))
$canvas.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$font = [Drawing.Font]::new('Microsoft YaHei', 10)
$names = @{anon='千早爱音';soyo='长崎爽世';rana='要乐奈';tomori='高松灯';taki='椎名立希';umiri='八幡海铃';uika='三角初华';sakiko='丰川祥子'}
$index = 0
foreach ($entry in $manifest.icons) {
    $source = Join-Path $project $entry.source_icon
    if (-not (Test-Path -LiteralPath $source)) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $source) -Force | Out-Null
        Copy-Item -LiteralPath $entry.generated_path -Destination $source
    }
    $image = [Drawing.Image]::FromFile($source)
    $packed = [Drawing.Bitmap]::new(64,64,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($packed)
    $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($image,[Drawing.Rectangle]::new(0,0,64,64))
    $packed.Save((Join-Path $destination ($entry.id + '_robot_doll.png')),[Drawing.Imaging.ImageFormat]::Png)
    $x = ($index % 4) * 160 + 16
    $y = [Math]::Floor($index / 4) * 175
    $canvas.DrawImage($image,[Drawing.Rectangle]::new($x,$y+4,128,128))
    $canvas.DrawString($names[$entry.id],$font,[Drawing.Brushes]::White,$x+28,$y+138)
    $graphics.Dispose(); $packed.Dispose(); $image.Dispose(); $index++
}
$preview.Save((Join-Path $project 'art/robot-dolls/eight-dolls-preview.png'),[Drawing.Imaging.ImageFormat]::Png)
$canvas.Dispose(); $preview.Dispose(); $font.Dispose()
# Native pixel schematic for the reusable blueprint; no reference photo is used here.
$blueprint = [Drawing.Bitmap]::new(32,32,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [Drawing.Graphics]::FromImage($blueprint)
$g.FillRectangle([Drawing.Brushes]::MidnightBlue,5,3,23,27)
$g.FillRectangle([Drawing.Brushes]::SteelBlue,4,2,22,26)
$g.FillRectangle([Drawing.Brushes]::LightCyan,6,4,18,1)
$pen = [Drawing.Pen]::new([Drawing.Color]::LightCyan,1)
$g.DrawRectangle($pen,11,8,8,7); $g.DrawRectangle($pen,12,17,6,5)
$g.DrawLine($pen,13,23,13,25); $g.DrawLine($pen,17,23,17,25)
$g.DrawLine($pen,9,18,9,21); $g.DrawLine($pen,21,18,21,21)
$g.FillRectangle([Drawing.Brushes]::LightCyan,13,11,1,1)
$g.FillRectangle([Drawing.Brushes]::LightCyan,17,11,1,1)
$blueprint.Save((Join-Path $destination 'robot_blueprint.png'),[Drawing.Imaging.ImageFormat]::Png)
$pen.Dispose(); $g.Dispose(); $blueprint.Dispose()
Write-Output 'Packed 8 transparent 64x64 inventory icons; source PNGs, prompts and preview retained.'
