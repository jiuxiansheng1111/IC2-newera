"""Reproduce frontier assets. Uses only the checked-in vanilla iron reference and project dust shape.
No network, game or third-party mod asset input. Python 3 + Pillow.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import importlib.util
import json
import random
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/ic2newera'
ART=ROOT/'art/frontier-equipment'
spec=importlib.util.spec_from_file_location('material_style',ROOT/'art/sources/material_pixel_style.py')
style=importlib.util.module_from_spec(spec);spec.loader.exec_module(style)
PALETTES={
 'vanadium':('#283132','#5C7773','#9AB4A6','#E8EDD4'),
 'niobium':('#302B3D','#6D637E','#B5A1BC','#F2E1E0'),
 'vanadium_steel':('#243331','#477D72','#87C4A4','#E5F4D5'),
 'niobium_titanium':('#2C2B3E','#65657F','#B5B8D6','#F1E6F5')}
ICONS={}
def write_json(path,value):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def item(name,image,parent='minecraft:item/generated'):
 (ASSETS/'textures/item').mkdir(parents=True,exist_ok=True)
 image.save(ASSETS/'textures/item'/f'{name}.png');ICONS[name]=image
 write_json(ASSETS/'models/item'/f'{name}.json',{'parent':parent,'textures':{'layer0':f'ic2newera:item/{name}'}})
def equipment(kind,palette):
 image=Image.new('RGBA',(16,16));d=ImageDraw.Draw(image)
 outline,shade,metal,light=map(style.rgba,palette)
 grip=(67,58,43,255);grip_light=(127,109,69,255);accent=(224,163,59,255)
 def poly(points,color):d.polygon(points,fill=color)
 def line(points,color,width=1):d.line(points,fill=color,width=width)
 if kind in ('sword','pickaxe','axe','shovel','hoe'):
  line([(3,13),(12,4)],outline,3);line([(3,13),(12,4)],grip,1)
  for x,y in [(3,13),(5,11),(7,9)]:d.point((x,y),fill=grip_light)
  if kind=='sword':
   poly([(5,9),(5,7),(11,1),(14,0),(15,3),(9,9),(7,10)],outline)
   poly([(6,7),(12,1),(14,1),(14,3),(8,9)],metal);line([(6,7),(12,1),(14,1)],light)
   line([(4,7),(8,11)],outline,3);line([(4,7),(8,11)],accent)
   d.point((10,4),fill=shade);d.point((9,5),fill=shade)
  elif kind=='pickaxe':
   poly([(3,1),(8,0),(12,2),(15,6),(15,9),(13,7),(11,5),(7,3),(3,3)],outline)
   line([(4,1),(8,1),(12,3),(14,6)],metal,2);line([(4,1),(8,1),(11,2)],light)
   d.rectangle((8,3,10,5),fill=shade);d.point((9,4),fill=accent)
  elif kind=='axe':
   poly([(7,1),(12,0),(15,2),(15,6),(12,9),(10,7),(8,4),(6,3)],outline)
   poly([(9,2),(13,1),(14,2),(14,6),(12,8),(11,6)],metal)
   line([(13,1),(14,2),(14,6),(12,8)],light);d.rectangle((8,2,10,4),fill=shade);d.point((9,3),fill=accent)
  elif kind=='shovel':
   poly([(9,0),(13,0),(15,2),(15,6),(12,8),(8,4),(8,1)],outline)
   poly([(10,1),(13,1),(14,2),(14,5),(12,7),(9,4),(9,2)],metal)
   line([(10,1),(13,1),(14,2)],light);line([(10,3),(12,5)],shade)
  else:
   poly([(3,1),(10,0),(13,2),(13,5),(10,5),(9,3),(3,3)],outline)
   line([(4,1),(9,1),(12,3)],metal,2);line([(4,1),(9,1)],light);d.point((10,3),fill=accent)
 else:
  if kind=='helmet':
   poly([(3,3),(5,1),(11,1),(13,3),(13,12),(10,12),(10,7),(6,7),(6,12),(3,12)],outline)
   poly([(4,4),(6,2),(10,2),(12,4),(12,10),(11,10),(11,6),(5,6),(5,10),(4,10)],metal)
   line([(6,2),(10,2),(11,3)],light);line([(5,5),(10,5)],shade);d.point((4,9),fill=accent);d.point((12,9),fill=accent)
  elif kind=='chestplate':
   poly([(2,2),(5,1),(6,4),(9,4),(10,1),(13,2),(15,6),(12,8),(12,14),(3,14),(3,8),(0,6)],outline)
   poly([(2,3),(4,2),(5,5),(10,5),(11,2),(13,3),(14,5),(11,7),(11,13),(4,13),(4,7),(1,5)],metal)
   line([(4,7),(11,7)],shade);line([(7,8),(7,12)],shade);line([(5,9),(10,9)],accent)
   for pos in [(3,4),(12,4),(5,12),(10,12)]:d.point(pos,fill=light)
  elif kind=='leggings':
   d.rectangle((3,1,12,5),fill=outline);d.rectangle((3,5,6,14),fill=outline);d.rectangle((9,5,12,14),fill=outline)
   d.rectangle((4,2,11,4),fill=metal);d.rectangle((4,5,5,13),fill=metal);d.rectangle((10,5,11,13),fill=metal)
   line([(4,8),(5,8)],accent);line([(10,8),(11,8)],accent);d.rectangle((7,2,8,3),fill=light)
  else:
   for x in (1,9):
    d.rectangle((x+1,3,x+4,10),fill=outline);d.rectangle((x,9,x+5,13),fill=outline)
    d.rectangle((x+2,4,x+3,9),fill=metal);d.rectangle((x+1,10,x+4,11),fill=metal)
    line([(x+1,12),(x+4,12)],shade);d.point((x+2,8),fill=accent);d.point((x+2,4),fill=light)
 return image

def armor(palette,layer):
 image=Image.new('RGBA',(64,32));d=ImageDraw.Draw(image)
 outline,shade,metal,light=map(style.rgba,palette);accent=(222,163,57,255)
 def box(u,v,dx,dy,dz):
  faces=[(u+dz,v,dx,dz),(u+dz+dx,v,dx,dz),(u,v+dz,dz,dy),(u+dz,v+dz,dx,dy),(u+dz+dx,v+dz,dz,dy),(u+2*dz+dx,v+dz,dx,dy)]
  for index,(x,y,w,h) in enumerate(faces):
   d.rectangle((x,y,x+w-1,y+h-1),fill=outline)
   if w>2 and h>2:d.rectangle((x+1,y+1,x+w-2,y+h-2),fill=metal if index in (0,3) else shade)
   if h>4 and w>2:
    d.line((x+1,y+2,x+w-2,y+2),fill=light)
    d.line((x+1,y+h-3,x+w-2,y+h-3),fill=accent)
    d.point((x+1,y+h-2),fill=light)
 box(0,0,8,8,8);box(16,16,8,12,4);box(40,16,4,12,4);box(0,16,4,12,4)
 if layer==1:
  # Solid forehead and cheek plates, open eye slot; shoulders and torso remain industrial panels.
  d.rectangle((9,10,14,13),fill=(0,0,0,0));d.line((9,9,14,9),fill=accent)
  d.rectangle((22,23,25,26),fill=shade);d.point((23,24),fill=light)
 else:
  d.rectangle((20,20,27,22),fill=outline);d.rectangle((23,20,24,21),fill=accent)
 return image

def ore(name,base,metal):
 rng=random.Random(name);image=Image.new('RGBA',(16,16));d=ImageDraw.Draw(image)
 for y in range(16):
  for x in range(16):
   n=rng.randrange(-15,16);image.putpixel((x,y),tuple(max(0,min(255,c+n)) for c in base)+(255,))
 for x,y in [(2,3),(10,2),(7,8),(13,12),(3,13)]:
  d.polygon([(x,y),(x+2,y-1),(x+3,y+1),(x+1,y+2)],fill=metal[1]);d.point((x+1,y),fill=metal[3]);d.point((x+2,y+1),fill=metal[2])
 image.save(ASSETS/'textures/block'/f'{name}.png');ICONS[name]=image
 write_json(ASSETS/'blockstates'/f'{name}.json',{'variants':{'':{'model':f'ic2newera:block/{name}'}}})
 write_json(ASSETS/'models/block'/f'{name}.json',{'parent':'minecraft:block/cube_all','textures':{'all':f'ic2newera:block/{name}'}})
 write_json(ASSETS/'models/item'/f'{name}.json',{'parent':f'ic2newera:block/{name}'})

def main():
 ART.mkdir(parents=True,exist_ok=True)
 for path in ('textures/block','textures/models/armor'): (ASSETS/path).mkdir(parents=True,exist_ok=True)
 for name,palette in PALETTES.items():
  item(name+'_ingot',style.draw_ingot(palette),'minecraft:item/iron_ingot')
  item(name+'_dust',style.draw_dust(palette))
  if name in ('vanadium','niobium'):
   item('impure_'+name+'_dust',style.draw_dust(palette,impure=True))
   # Rough fragment silhouette, independent from ingot and pointed dust.
   image=Image.new('RGBA',(16,16));d=ImageDraw.Draw(image);p=list(map(style.rgba,palette))
   d.polygon([(3,3),(7,1),(11,2),(14,6),(13,11),(9,14),(3,12),(1,7)],fill=p[0])
   d.polygon([(4,4),(7,2),(10,3),(12,6),(11,10),(8,12),(4,10),(3,7)],fill=p[1])
   d.polygon([(5,4),(8,3),(11,6),(9,8),(5,9)],fill=p[2]);d.line((6,4,8,3,10,5),fill=p[3])
   item('raw_'+name,image)
  else:
   for kind in ('sword','pickaxe','axe','shovel','hoe','helmet','chestplate','leggings','boots'):
    item(name+'_'+kind,equipment(kind,palette),'minecraft:item/handheld' if kind in ('sword','pickaxe','axe','shovel','hoe') else 'minecraft:item/generated')
   for layer in (1,2):armor(palette,layer).save(ASSETS/'textures/models/armor'/f'{name}_layer_{layer}.png')
 ore('vanadium_ore',(116,119,113),list(map(style.rgba,PALETTES['vanadium'])))
 ore('niobium_ore',(123,67,53),list(map(style.rgba,PALETTES['niobium'])))
 for name in ('patrol_drone','industrial_sentinel'):
  write_json(ASSETS/'models/item'/f'{name}_spawn_egg.json',{'parent':'minecraft:item/template_spawn_egg'})
 columns=8;cell=110;rows=(len(ICONS)+columns-1)//columns
 preview=Image.new('RGB',(columns*cell,rows*130),(31,37,43));d=ImageDraw.Draw(preview)
 font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',10)
 for index,(name,image) in enumerate(ICONS.items()):
  x=index%columns*cell;y=index//columns*130
  scaled=image.resize((80,80),Image.Resampling.NEAREST);preview.paste(scaled,(x+15,y+5),scaled)
  words=name.replace('niobium_titanium','NbTi').replace('vanadium_steel','VSteel').split('_')
  label='_'.join(words[:2])+'\n'+'_'.join(words[2:]) if len(words)>2 else '_'.join(words)
  d.multiline_text((x+3,y+89),label,font=font,fill=(224,232,237))
 preview.save(ART/'catalog.png')
 layers=Image.new('RGBA',(512,256),(31,37,43,255))
 for i,name in enumerate(('vanadium_steel','niobium_titanium')):
  for layer in (1,2):layers.alpha_composite(armor(PALETTES[name],layer).resize((256,128),Image.Resampling.NEAREST),((layer-1)*256,i*128))
 layers.convert('RGB').save(ART/'armor-uv.png')
 print('Generated',len(ICONS),'material, ore and equipment icons; four armor layers; two spawn egg models.')
if __name__=='__main__':main()
