"""Original arsenal pixels: Python/Pillow, no network or mod asset extraction.
Ingot silhouettes use the project's verified Minecraft 1.19.2 reference.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import importlib.util,json,random
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'src/main/resources/assets/ic2newera';ART=ROOT/'art/arsenal'
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
style=module('pixel_style',ROOT/'art/sources/material_pixel_style.py')
frontier=module('frontier_pixels',ROOT/'scripts/generate-frontier-assets.py')
PALETTES={
'cobalt':('#202B39','#526980','#92B6C9','#DCEFF4'),
'molybdenum':('#272B2D','#626E74','#B0B9B4','#F0EBDC'),
 'tantalum':('#2A2531','#6F5D76','#B0A0B0','#EFE2EB'),
 'magnetic_armor':('#142A33','#2B5665','#669CAF','#B7E1DF'),
 'resonance_armor':('#292B41','#646A8E','#BEC6DE','#FAEDFF')}
ICONS={}
def write(path,data):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes((json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
def item(name,image,parent='minecraft:item/generated'):
 image.save(ASSETS/'textures/item'/f'{name}.png');ICONS[name]=image
 write(ASSETS/'models/item'/f'{name}.json',{'parent':parent,'textures':{'layer0':f'ic2newera:item/{name}'}})
def component(name):
 image=Image.new('RGBA',(16,16));d=ImageDraw.Draw(image)
 dark='#1C2C38';metal='#7395A2';light='#D9E4E1';copper='#E2A45C';cyan='#56DCE3';violet='#B7A2F6'
 if name=='magnetic_coil':
  d.rectangle((2,2,13,13),fill=dark);d.rectangle((3,3,12,12),fill=metal);d.rectangle((5,4,10,11),fill=dark)
  for x in (3,5,7,9,11):d.line((x,3,x,5),fill=copper);d.line((x,10,x,12),fill=copper)
  d.line((3,6,3,9),fill=copper);d.line((12,6,12,9),fill=copper);d.point((6,7),fill=cyan);d.point((9,7),fill=cyan)
 elif name=='heat_resistant_plate':
  d.polygon([(3,2),(13,2),(14,12),(11,14),(1,12)],fill=dark);d.polygon([(4,3),(12,3),(13,11),(10,13),(2,11)],fill=metal)
  for y in (5,8,11):d.line((4,y,11,y),fill='#BAC0B4')
  for pt in [(4,4),(11,4),(4,10),(11,10)]:d.point(pt,fill=copper)
 elif name=='pulse_capacitor':
  for x in (5,10):d.rectangle((x,0,x+1,3),fill=light)
  d.rectangle((3,3,12,13),fill=dark);d.rectangle((4,4,11,12),fill=metal);d.rectangle((5,5,10,10),fill='#254F67')
  d.polygon([(8,4),(6,8),(8,8),(7,11),(10,7),(8,7)],fill=cyan);d.line((5,12,10,12),fill=copper)
 elif name=='resonance_core':
  d.polygon([(7,0),(13,4),(15,10),(10,15),(3,14),(0,8),(2,3)],fill=dark)
  d.polygon([(7,2),(11,5),(12,10),(9,13),(4,11),(3,6)],fill=metal)
  d.polygon([(7,3),(10,7),(8,12),(5,9),(5,6)],fill=violet);d.line((7,4,8,7,7,10),fill=light)
  for pt in [(2,7),(11,4),(10,12)]:d.point(pt,fill=cyan)
 elif name=='blade_stabilizer':
  d.polygon([(2,2),(5,1),(8,5),(12,2),(15,4),(11,8),(13,12),(10,15),(7,10),(3,13),(0,10),(4,6)],fill=dark)
  d.line((3,3,11,12),fill=metal,width=3);d.line((2,10,12,4),fill=metal,width=3)
  d.rectangle((6,6,9,9),fill=copper);d.rectangle((7,7,8,8),fill=cyan)
 else:
  d.rectangle((1,5,14,10),fill=dark);d.rectangle((2,6,13,9),fill=metal)
  d.ellipse((4,2,11,13),fill=dark);d.ellipse((5,3,10,12),fill=light);d.ellipse((6,5,9,10),fill='#3C6574');d.point((7,7),fill=cyan)
  for x in (2,12):d.line((x,6,x,9),fill=copper)
 return image

def armor_icon(name,kind):
 image=frontier.equipment(kind,PALETTES[name]);d=ImageDraw.Draw(image)
 if name=='magnetic_armor':
  if kind=='helmet':d.line((5,5,10,5),fill='#5EE6E7');d.point((3,4),fill='#E5AD58');d.point((12,4),fill='#E5AD58')
  if kind=='chestplate':
   for x in (5,9):d.ellipse((x,7,x+2,10),outline='#E5AD58');d.point((x+1,8),fill='#45DCE2')
  if kind=='leggings':d.line((4,5,4,11),fill='#E5AD58');d.line((11,5,11,11),fill='#E5AD58')
 else:
  if kind=='helmet':d.polygon([(7,1),(8,0),(9,1),(8,3)],fill='#B395F3');d.line((5,5,10,5),fill='#7A8EE8')
  if kind=='chestplate':d.polygon([(7,6),(9,8),(7,11),(5,8)],fill='#A389E8');d.point((7,8),fill='#E2EEFF')
  if kind=='leggings':d.point((4,7),fill='#C8A3FF');d.point((11,7),fill='#C8A3FF')
 return image

def armor_texture(name,layer):
 image=frontier.armor(PALETTES[name],layer);d=ImageDraw.Draw(image)
 if name=='magnetic_armor':
  d.line((9,9,14,9),fill='#5EE6E7');d.line((21,22,26,22),fill='#E5AD58')
  d.ellipse((21,23,26,28),outline='#E5AD58');d.rectangle((23,25,24,26),fill='#5EE6E7')
  for x in (5,45):d.line((x,23,x,29),fill='#E5AD58')
 else:
  d.line((9,9,14,9),fill='#B09BF3');d.polygon([(23,22),(26,25),(23,29),(21,25)],fill='#B09BF3');d.point((23,25),fill='#EAF6FF')
  for x in (5,45):d.point((x,24),fill='#B09BF3');d.point((x+1,27),fill='#D9DBFF')
 return image

def main():
 ART.mkdir(parents=True,exist_ok=True)
 for path in ('textures/item','textures/block','textures/models/armor'):(ASSETS/path).mkdir(parents=True,exist_ok=True)
 for name in ('cobalt','molybdenum','tantalum'):
  palette=PALETTES[name];p=list(map(style.rgba,palette))
  item(name+'_ingot',style.draw_ingot(palette),'minecraft:item/iron_ingot');item(name+'_dust',style.draw_dust(palette));item('impure_'+name+'_dust',style.draw_dust(palette,impure=True))
  raw=Image.new('RGBA',(16,16));d=ImageDraw.Draw(raw);d.polygon([(4,1),(10,2),(14,6),(12,12),(6,14),(1,10),(2,4)],fill=p[0]);d.polygon([(5,3),(9,3),(12,6),(10,11),(6,12),(3,9),(4,5)],fill=p[1]);d.polygon([(5,4),(9,4),(11,6),(8,9),(4,8)],fill=p[2]);d.line((6,4,9,4,10,5),fill=p[3]);item('raw_'+name,raw)
  # Existing helper authors new stone grain pixels rather than copying Ad Astra textures.
  frontier.ore(name+'_ore',(112,115,113) if name!='tantalum' else (122,66,53),p);ICONS[name+'_ore']=frontier.ICONS[name+'_ore']
 for name in ('magnetic_coil','heat_resistant_plate','pulse_capacitor','resonance_core','blade_stabilizer','servo_armor_joint'):item(name,component(name))
 shard=Image.new('RGBA',(16,16));d=ImageDraw.Draw(shard);d.polygon([(8,0),(13,6),(10,13),(5,15),(2,9),(4,3)],fill='#393349');d.polygon([(8,1),(11,6),(9,11),(5,14),(5,5)],fill='#9B8BCF');d.polygon([(8,1),(9,6),(7,10),(5,14),(5,5)],fill='#D7D6F7');d.line((8,2,7,6,6,10),fill='#F4F4FF');item('resonance_shard',shard)
 cluster=Image.new('RGBA',(16,16));d=ImageDraw.Draw(cluster)
 for x,y,w,h,color in [(0,7,5,8,'#7E6BAF'),(6,0,5,15,'#B19CDE'),(11,5,4,10,'#8B9ACB')]:
  d.polygon([(x+w//2,y),(x+w-1,y+4),(x+w-1,y+h),(x,y+h),(x,y+4)],fill='#3A3651');d.polygon([(x+w//2,y+1),(x+w-2,y+4),(x+w-2,y+h-1),(x+1,y+h-1),(x+1,y+4)],fill=color);d.line((x+w//2,y+2,x+w//2,y+h-2),fill='#E4E7FF')
 cluster.save(ASSETS/'textures/block/resonance_cluster.png');ICONS['resonance_cluster']=cluster
 write(ASSETS/'models/block/resonance_cluster.json',{'parent':'minecraft:block/cross','render_type':'minecraft:cutout','textures':{'cross':'ic2newera:block/resonance_cluster'}})
 write(ASSETS/'models/item/resonance_cluster.json',{'parent':'minecraft:item/generated','textures':{'layer0':'ic2newera:block/resonance_cluster'}})
 write(ASSETS/'blockstates/resonance_cluster.json',{'variants':{f'facing={face}':dict(model='ic2newera:block/resonance_cluster',**rotation) for face,rotation in [('up',{}),('down',{'x':180}),('north',{'x':90}),('south',{'x':90,'y':180}),('west',{'x':90,'y':270}),('east',{'x':90,'y':90})]}})
 for name in ('magnetic_armor','resonance_armor'):
  for kind in ('helmet','chestplate','leggings','boots'):item(name+'_'+kind,armor_icon(name,kind))
  for layer in (1,2):armor_texture(name,layer).save(ASSETS/'textures/models/armor'/f'{name}_layer_{layer}.png')
 cols=8;cell=112;canvas=Image.new('RGB',(cols*cell,((len(ICONS)+cols-1)//cols)*130),(27,33,42));d=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',10)
 for i,(name,image) in enumerate(ICONS.items()):
  x=i%cols*cell;y=i//cols*130;s=image.resize((80,80),Image.Resampling.NEAREST);canvas.paste(s,(x+16,y+2),s)
  chunks=name.split('_');text='_'.join(chunks[:2])+'\n'+'_'.join(chunks[2:]) if len(chunks)>2 else name;d.multiline_text((x+3,y+86),text,font=font,fill='#DFEAF2')
 canvas.save(ART/'catalog.png')
 sheet=Image.new('RGBA',(512,256),(27,33,42,255))
 for i,name in enumerate(('magnetic_armor','resonance_armor')):
  for layer in (1,2):sheet.alpha_composite(armor_texture(name,layer).resize((256,128),Image.Resampling.NEAREST),((layer-1)*256,i*128))
 sheet.convert('RGB').save(ART/'armor-uv.png');print('Generated',len(ICONS),'arsenal icons and 4 armor texture layers.')
if __name__=='__main__':main()
