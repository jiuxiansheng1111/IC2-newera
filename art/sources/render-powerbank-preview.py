"""Preview the actual power-bank texture layers without changing game assets."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[2]
textures = root / 'src/main/resources/assets/ic2newera/textures/item'
result = Image.new('RGB', (640, 208), '#17242c')
draw = ImageDraw.Draw(result)
font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 20)
for index, (name, label) in enumerate([
    ('power_bank', '初级'), ('intermediate_power_bank', '中级'),
    ('advanced_power_bank', '高级'), ('nuclear_power_bank', '核能')
]):
    icon = Image.open(textures / (name + '.png')).convert('RGBA')
    if name == 'nuclear_power_bank':
        icon = Image.alpha_composite(icon, Image.open(textures / (name + '_emissive.png')).convert('RGBA'))
    icon = icon.resize((128, 128), Image.Resampling.NEAREST)
    result.paste(icon, (index * 160 + 16, 16), icon)
    draw.text((index * 160 + 60, 166), label, fill='#e1f0eb', font=font)
target = root / 'art/preview/powerbanks-20261018.png'
target.parent.mkdir(parents=True, exist_ok=True)
result.save(target)
print(target)
