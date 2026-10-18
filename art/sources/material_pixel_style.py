#!/usr/bin/env python3
"""Recolour the verified vanilla iron silhouette; author related powder piles."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/ic2newera'
IRON_REFERENCE = json.loads((ROOT/'art/reference/vanilla-iron-ingot-1.19.2.json').read_text(encoding='utf-8'))
# outline, side/shadow, face, highlight
INGOTS = {
    'nickel_ingot': ('#273A32', '#809384', '#C4D1C1', '#F0EFD6'),
    'chromium_ingot': ('#303D48', '#929DAA', '#D6E2EC', '#FAFCFF'),
    'titanium_ingot': ('#1D3341', '#44768E', '#8EC3D0', '#D5F0EB'),
    'tungsten_ingot': ('#29283B', '#5E5879', '#A08CB1', '#F0D183'),
    'stainless_steel_ingot': ('#28323A', '#71838E', '#B8C6C7', '#EEF3E8'),
    'invar_ingot': ('#33352C', '#77775A', '#B4AD7A', '#E7D59B'),
    'nichrome_ingot': ('#452D2C', '#A65C4B', '#E39C71', '#FFE0A0'),
    'tool_steel_ingot': ('#202A33', '#4B5D6A', '#8799A0', '#D4E0DF'),
    'titanium_alloy_ingot': ('#183642', '#3D8091', '#84C5CE', '#E0FFF0'),
    'tungsten_carbide_ingot': ('#2A2637', '#605675', '#A28B91', '#E9C878'),
    'aluminium_bronze_ingot': ('#4D301C', '#9B642A', '#D3A449', '#FFF0A4'),
    'tempered_steel_ingot': ('#41494D', '#76858C', '#B9C7CC', '#F1F5F5'),
    'tempered_high_carbon_steel_ingot': ('#171E26', '#3F4B57', '#778998', '#C5D1D9'),
}
DUSTS = {
    'nickel_dust': ('#304037', '#7E9381', '#B8C8A7', '#E8E8C9'),
    'chromium_dust': ('#37424A', '#939CA7', '#D3DEE5', '#F4FAFF'),
    'titanium_dust': ('#203441', '#477E93', '#79B7C5', '#C8E8E1'),
    'tungsten_dust': ('#302D41', '#665D7F', '#A58DB1', '#E8C875'),
    'impure_nickel_dust': ('#28352E', '#718372', '#AFB99B', '#E0DFBF'),
    'impure_chromium_dust': ('#383638', '#7D8187', '#B5B6BB', '#D9DADD'),
    'impure_titanium_dust': ('#1D303D', '#3C7083', '#6FA9B5', '#B8D8D4'),
    'impure_tungsten_dust': ('#282638', '#554F6D', '#8A789C', '#D9B968'),
    'quartz_dust': ('#615D68', '#ADA6B2', '#E0D9E3', '#FFF9FF'),
    'zinc_dust': ('#36404A', '#698594', '#ACC7D2', '#E4F4F7'),
    'low_carbon_steel_dust': INGOTS['tempered_steel_ingot'],
    'high_carbon_steel_dust': INGOTS['tempered_high_carbon_steel_ingot'],
}


def rgba(value):
    return tuple(int(value[i:i+2], 16) for i in (1, 3, 5)) + (255,)


def draw_ingot(palette):
    """Keep every vanilla alpha/brightness pixel in place; only recolour it."""
    colors = list(map(rgba, palette))
    anchors = (53, 114, 216, 255)
    shades = []
    for level in IRON_REFERENCE['gray_levels']:
        segment = next((i for i in range(3) if level <= anchors[i+1]), 2)
        fraction = (level-anchors[segment])/(anchors[segment+1]-anchors[segment])
        shades.append(tuple(round(colors[segment][channel]*(1-fraction)+
                                  colors[segment+1][channel]*fraction) for channel in range(3))+(255,))
    image = Image.new('RGBA', (16, 16))
    for y,row in enumerate(IRON_REFERENCE['shade_rows']):
        for x,pixel in enumerate(row):
            if pixel != '.': image.putpixel((x,y),shades[int(pixel)])
    return image


def draw_dust(palette, impure=False, seed=0, coarse=False):
    """One peaked pile shape, with readable grains and optional impurity flecks."""
    outline, shadow, body, light = map(rgba, palette)
    image = Image.new('RGBA', (16,16))
    draw = ImageDraw.Draw(image)
    rows = {3:(7,8),4:(6,9),5:(5,10),6:(3,11),7:(2,12),8:(2,13),
            9:(2,13),10:(3,12),11:(4,11),12:(6,9),13:(7,8)}
    for y,(left,right) in rows.items():
        draw.line((left,y,right,y),fill=outline)
        if right-left>1:
            draw.line((left+1,y,right-1,y),fill=shadow if y>10 else body)
    for x,y in ((7,5),(6,6),(8,6),(4,7),(5,8),(9,7),(10,8),(7,9),(4,9),(9,10),(7,11)):
        draw.point((x,y),fill=light)
    for x,y in ((6,7),(7,7),(8,8),(6,9),(10,9),(5,10),(8,10)):
        draw.point((x,y),fill=shadow)
    if coarse:
        draw.rectangle((6,8,7,9),fill=outline)
        draw.line((9,6,10,7),fill=shadow)
    if impure:
        # Dark inclusions remain visible for the mandatory washing stage.
        draw.rectangle((4,7,5,8),fill=rgba('#201E1A'))
        draw.rectangle((8,9,9,10),fill=rgba('#302B24'))
        draw.point((10,7),fill=rgba('#201E1A'))
        draw.point((6,10),fill=outline)
    return image


def generate():
    locale=json.loads((ASSETS/'lang/zh_cn.json').read_text(encoding='utf-8-sig'))
    icons={}
    for item_id,palette in {**INGOTS,**DUSTS}.items():
        img=draw_ingot(palette) if item_id in INGOTS else draw_dust(
            palette, impure=item_id.startswith('impure_'), coarse=item_id=='high_carbon_steel_dust')
        img.save(ASSETS/'textures/item'/f'{item_id}.png')
        model=ASSETS/'models/item'/f'{item_id}.json'
        model.write_bytes((json.dumps({'parent':'minecraft:item/iron_ingot' if item_id in INGOTS else 'minecraft:item/generated',
            'textures':{'layer0':f'ic2newera:item/{item_id}'}},indent=2)+'\n').encode('utf-8'))
        icons[item_id]=img
    catalog_path=ROOT/'art/model-catalog.json'
    catalog=json.loads(catalog_path.read_text(encoding='utf-8-sig'))
    catalog['models']=[entry for entry in catalog['models'] if entry['id'] not in
                       ('carbon_steel_billet','high_carbon_steel_billet')]
    entries={entry['id']:entry for entry in catalog['models']}
    for item_id in icons:
        entry=entries.get(item_id)
        if entry is None:
            entry={'id':item_id, 'category':'金属粉末' if item_id in DUSTS else '金属锭',
                   'assetRoot':'src/main/resources/assets', 'path':f'ic2newera/models/item/{item_id}.json'}
            catalog['models'].append(entry)
        entry.update(name=locale[f'item.ic2newera.{item_id}'],status='源码资源已实现，待实机验收',
                     description='原创16×16像素粉堆。' if item_id in DUSTS else '继承1.19.2原版铁锭模型；16×16贴图逐像素保留原版轮廓，仅改材质色板。')
    for entry in catalog['models']:
        if entry['id'].startswith('tempered_'):
            entry['name']=locale.get('item.ic2newera.'+entry['id'],entry['name'])
    catalog_path.write_bytes((json.dumps(catalog,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
    font_path=Path('C:/Windows/Fonts/msyh.ttc')
    font=ImageFont.truetype(str(font_path),18) if font_path.exists() else ImageFont.load_default()
    def preview(item_ids,path):
        columns=4; cell=180; rows=(len(item_ids)+columns-1)//columns
        canvas=Image.new('RGB',(columns*cell,rows*190),(38,42,48))
        painter=ImageDraw.Draw(canvas)
        for i,item_id in enumerate(item_ids):
            x=(i%columns)*cell; y=(i//columns)*190
            painter.rectangle((x+20,y+12,x+159,y+151),fill=(67,72,80))
            canvas.paste(icons[item_id].resize((128,128),Image.Resampling.NEAREST),(x+26,y+18),
                         icons[item_id].resize((128,128),Image.Resampling.NEAREST))
            title='原版铁锭（基准）' if item_id=='vanilla_iron_reference' else locale[f'item.ic2newera.{item_id}']
            width=painter.textlength(title,font=font)
            painter.text((x+(cell-width)/2,y+158),title,font=font,fill=(235,239,244))
        path.parent.mkdir(parents=True,exist_ok=True); canvas.save(path)
    preview(['low_carbon_steel_dust','tempered_steel_ingot','high_carbon_steel_dust',
             'tempered_high_carbon_steel_ingot'],ROOT/'art/preview/carbon-steel-four-items.png')
    preview(list(DUSTS),ROOT/'art/preview/current-dust-styles.png')
    preview(list(INGOTS),ROOT/'art/preview/current-ingot-styles.png')
    icons['vanilla_iron_reference']=draw_ingot(('#353535','#727272','#D8D8D8','#FFFFFF'))
    preview(['vanilla_iron_reference','tempered_steel_ingot','tempered_high_carbon_steel_ingot',
             'aluminium_bronze_ingot'],ROOT/'art/preview/vanilla-ingot-proportion-comparison.png')
    print(f'Generated {len(INGOTS)} ingots and {len(DUSTS)} dust icons.')


if __name__=='__main__':
    generate()
