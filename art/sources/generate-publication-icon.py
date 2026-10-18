"""Render the project's original circuit tile as SVG and PNG for distribution pages."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[2]
out = root / 'art/preview'
out.mkdir(parents=True, exist_ok=True)
svg = '''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
<rect width="512" height="512" rx="70" fill="#13232e"/>
<rect x="44" y="44" width="424" height="424" rx="42" fill="none" stroke="#78929e" stroke-width="10"/>
<path d="M44 150H120V220H175 M468 362H392V292H337 M150 44V118H220 M362 468V394H292" fill="none" stroke="#44d9cf" stroke-width="14"/>
<g fill="#ffbd59"><circle cx="120" cy="150" r="17"/><circle cx="392" cy="362" r="17"/><circle cx="220" cy="118" r="17"/><circle cx="292" cy="394" r="17"/></g>
<text x="256" y="322" text-anchor="middle" font-family="sans-serif" font-weight="bold" font-size="168" fill="#eef6f7">NE</text>
</svg>'''
(out/'publication-icon.svg').write_text(svg, encoding='utf-8')
image=Image.new('RGB',(512,512),'#13232e'); draw=ImageDraw.Draw(image)
draw.rounded_rectangle((44,44,468,468),radius=42,outline='#78929e',width=10)
for points in (((44,150),(120,150),(120,220),(175,220)),((468,362),(392,362),(392,292),(337,292)),((150,44),(150,118),(220,118)),((362,468),(362,394),(292,394))):
    draw.line(points,fill='#44d9cf',width=14,joint='curve')
for x,y in ((120,150),(392,362),(220,118),(292,394)):
    draw.ellipse((x-17,y-17,x+17,y+17),fill='#ffbd59')
try: font=ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf',168)
except OSError: font=ImageFont.truetype('DejaVuSans-Bold.ttf',168)
draw.text((256,256),'NE',font=font,fill='#eef6f7',anchor='mm')
image.save(out/'publication-icon.png')
print('Rendered original 512x512 publication icon.')
