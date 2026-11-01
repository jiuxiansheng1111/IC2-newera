"""Archived original blade geometry and editable MCP inputs. Python/Pillow, no imports from mods.
Retired 2026-10-19: writes only art/arsenal/blades; never emits game assets.
All runtime group meshes use handle origin (0,0,0), +Y blade, 1/128 native scale.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,math,base64,random
ROOT=Path(__file__).resolve().parents[1];ART=ROOT/'art/arsenal/blades'
COLORS={'night_arc':{'shell':(24,34,42),'edge':(105,139,148),'metal':(43,61,71),'grip':(18,29,33),'accent':(59,171,180),'light':(101,243,239),'rivet':(182,202,201)},
'star_pulse':{'shell':(93,101,120),'edge':(226,233,241),'metal':(161,175,194),'grip':(52,54,78),'accent':(109,108,197),'light':(174,165,255),'rivet':(243,237,247)}}

def texture(name):
 image=Image.new('RGBA',(256,256));draw=ImageDraw.Draw(image);regions={}
 for index,(material,color) in enumerate(COLORS[name].items()):
  x=index%4*64;y=index//4*64;regions[material]=(x,y,32,32);rng=random.Random(name+material)
  for py in range(y,y+32):
   for px in range(x,x+32):
    n=rng.randrange(-7,8);image.putpixel((px,py),tuple(max(0,min(255,c+n)) for c in color)+(255,))
  if material not in ('light','grip'):
   draw.rectangle((x,y,x+31,y+31),outline=tuple(max(0,c-22) for c in color)+(255,))
   draw.line((x+1,y+1,x+30,y+1),fill=tuple(min(255,c+24) for c in color)+(255,))
  if material=='grip':
   for k in range(-32,64,8):draw.line((x+max(0,k),y+max(0,-k),x+min(31,k+31),y+min(31,31-k)),fill=COLORS[name]['accent']+(255,),width=2)
  if material=='light':
   for k in range(3,30,7):draw.line((x+1,y+k,x+30,y+k),fill=(229,255,255,255))
 return image,regions

def box(name,lo,hi,material):
 x0,y0,z0=lo;x1,y1,z1=hi
 return {'name':name,'vertices':[[x0,y0,z0],[x1,y0,z0],[x1,y1,z0],[x0,y1,z0],[x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]],
 'faces':[[0,3,2,1],[4,5,6,7],[0,4,7,3],[1,2,6,5],[0,1,5,4],[3,7,6,2]],'materials':[material]*6}

def model(name):
 night=name=='night_arc';parts=[];glow=[];sheath=[];sheath_glow=[]
 width=5 if night else 6.2
 # Four-facet tapered blade: a black curved single edge versus a broader silver double edge.
 sections=[(12,0,width,1.25),(28,0,width,1.2),(68,1.5 if night else 0,width-.2,1.1),(106,4.0 if night else .5,width-.8,.95),(130,7 if night else 1,width-2.1,.65),(140,8 if night else 1.5,.12,.12)]
 vertices=[]
 for y,c,w,t in sections:vertices.extend([[c-w,y,0],[c-w*.2,y,t],[c+w,y,.3 if night else 0],[c-w*.2,y,-t]])
 faces=[];materials=[]
 for j in range(len(sections)-1):
  for k in range(4):faces.append([j*4+k,j*4+(k+1)%4,(j+1)*4+(k+1)%4,(j+1)*4+k]);materials.append('edge' if k in (0,3) else 'metal')
 faces.extend([[3,2,1,0],list(range(len(vertices)-4,len(vertices)))]);materials.extend(['metal','edge'])
 parts.append({'name':'tapered_curved_blade' if night else 'faceted_silver_blade','vertices':vertices,'faces':faces,'materials':materials})
 parts.extend([box('guard_center',[-5,7,-3],[5,12,3],'shell'),box('grip_core',[-2.8,-23,-2.8],[2.8,7,2.8],'grip'),box('pommel',[-3.8,-26,-3.5],[3.8,-22,3.5],'edge')])
 # Stepped open-frame technological guard and a distinct offset energy socket.
 for side in (-1,1):
  x=side*9
  parts.append(box('guard_wing_'+str(side),[min(side*4,x),7,-2.3],[max(side*4,x)+1,10,2.3],'metal'))
  parts.append(box('guard_fin_'+str(side),[x-1,9,-2],[x+1,15,2],'shell'))
  glow.append(box('guard_emitter_'+str(side),[x-.6,10,-2.2],[x+.6,14,2.2],'light'))
 for y in range(-20,6,5):parts.append(box('grip_binding_'+str(y),[-3.05,y,-3.05],[3.05,y+1,3.05],'accent'))
 parts.append(box('blade_mount',[-3.8,12,-1.6],[3.8,20,1.6],'shell'))
 for y0,y1,c in [(24,43,0),(47,65,.6),(69,88,1.6 if night else 0),(92,109,3.8 if night else .4),(113,125,6.1 if night else .7)]:
  for side in (-1,1):glow.append(box('energy_inlay_'+str(y0)+'_'+str(side),[c-.6,y0,side*1.32-.10],[c+.6,y1,side*1.32+.10],'light'))
 for side in (-1,1):
  parts.append(box('mount_rivet_'+str(side),[side*2-.5,15,-1.85],[side*2+.5,16,-1.6],'rivet'))
 # Scabbard is its own native group and never borrows third-party geometry.
 sw=7 if night else 8
 sheath.extend([box('scabbard_main',[-sw,13,-3.3],[sw,143,3.3],'shell'),box('scabbard_throat',[-sw-1,11,-4],[sw+1,17,4],'edge'),box('scabbard_end',[-sw+1,143,-3],[sw-1,148,3],'metal')])
 for y in (30,61,92,123):
  sheath.append(box('scabbard_band_'+str(y),[-sw-.3,y,-3.6],[sw+.3,y+2,3.6],'metal'))
 for side in (-1,1):
  sheath_glow.append(box('scabbard_energy_'+str(side),[-.7,24,side*3.4-.1],[.7,134,side*3.4+.1],'light'))
  sheath.append(box('retention_latch_'+str(side),[side*(sw-1)-.8,18,-4.2],[side*(sw-1)+.8,23,-3.4],'rivet'))
 damaged=[p for p in parts if p['name']!='tapered_curved_blade' and p['name']!='faceted_silver_blade']
 damaged_blade=json.loads(json.dumps(parts[0]))
 for vertex in damaged_blade['vertices']:
  if vertex[1]>96:vertex[1]=96+(vertex[0]%2)*2 # a jagged but closed shortened original mesh
 # Use only sections through 106, trimming last two rings avoids overlapping coincident break surfaces.
 damaged_blade['vertices']=damaged_blade['vertices'][:16];damaged_blade['faces']=damaged_blade['faces'][:12]+[list(range(12,16)),[3,2,1,0]];damaged_blade['materials']=damaged_blade['materials'][:12]+['edge','metal'];damaged.append(damaged_blade)
 damaged_glow=[p for p in glow if max(v[1] for v in p['vertices'])<=96]
 hilt=[p for p in parts if p['name'] not in ('tapered_curved_blade','faceted_silver_blade','blade_mount')]
 groups={'blade':parts,'blade_luminous':glow,'blade_damaged':damaged,'blade_damaged_luminous':damaged_glow,'sheath':sheath,'sheath_luminous':sheath_glow,
 'item_blade':sheath+sheath_glow+hilt,'item_bladens':parts+glow,'item_damaged':damaged+damaged_glow}
 return groups

def uv_face(region,n):
 x,y,w,h=region;coords=[[x+1,y+1],[x+w-1,y+1],[x+w-1,y+h-1],[x+1,y+h-1]]
 return coords[:n]

def obj(groups,regions):
 lines=['# IC2 New Era original technology blade. Authored by scripts/generate-tech-blades.py','# Units: native SlashBlade 1/128; origin: grip; blade axis: +Y. No MTL or third-party assets.'];index=1
 for group,parts in groups.items():
  lines.append('g '+group)
  for part in parts:
   for face,material in zip(part['faces'],part['materials']):
    points=[part['vertices'][i] for i in face];a,b,c=points[:3];u=[b[i]-a[i] for i in range(3)];v=[c[i]-a[i] for i in range(3)];normal=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];length=math.sqrt(sum(n*n for n in normal));normal=[n/length for n in normal]
    for point in points:lines.append('v '+' '.join(f'{c:.5f}' for c in point))
    for x,y in uv_face(regions[material],len(face)):lines.append(f'vt {x/256:.6f} {1-y/256:.6f}')
    for _ in face:lines.append('vn '+' '.join(f'{n:.6f}' for n in normal))
    lines.append('f '+' '.join(f'{index+i}/{index+i}/{index+i}' for i in range(len(face))));index+=len(face)
 return '\n'.join(lines)+'\n'

def operations(name,parts,png,regions):
 ops=[{'op':'add_texture','name':name,'source':'data:image/png;base64,'+base64.b64encode(png.read_bytes()).decode(),'width':256,'height':256}]
 for part in parts:
  faces=[]
  for face,material in zip(part['faces'],part['materials']):faces.append({'vertices':face,'uv':uv_face(regions[material],len(face)),'texture':name})
  ops.append({'op':'add_mesh','name':part['name'],'vertices':part['vertices'],'faces':faces,'origin':[0,0,0],'texture':name})
 return ops

def icon(name):
 image=Image.new('RGBA',(16,16));d=ImageDraw.Draw(image);c=COLORS[name]
 d.line((2,14,12,4),fill=c['grip']+(255,),width=3);d.line((2,14,12,4),fill=c['accent']+(255,),width=1)
 d.polygon([(5,8),(11,1),(14,0),(15,2),(8,10)],fill=c['metal']+(255,));d.line((6,8,12,1,14,1),fill=c['edge']+(255,));d.line((8,7,12,3),fill=c['light']+(255,));d.line((4,7,8,11),fill=c['shell']+(255,),width=3);d.point((6,9),fill=c['light']+(255,));return image

def main():
 ART.mkdir(parents=True,exist_ok=True)
 for name in ('night_arc','star_pulse'):
  image,regions=texture(name);png=ART/(name+'.png');image.save(png)
  groups=model(name);encoded=obj(groups,regions);(ART/(name+'.obj')).write_bytes(encoded.encode('utf-8'))
  (ART/(name+'.mesh.json')).write_bytes((json.dumps(groups,indent=2)+'\n').encode('utf-8'))
  for suffix,parts in [('',groups['blade']+groups['blade_luminous']),('-sheath',groups['sheath']+groups['sheath_luminous'])]:
   (ART/(name+suffix+'.mcp-operations.json')).write_bytes(json.dumps(operations(name,parts,png,regions)).encode('utf-8'))
  icon(name).save(ART/(name+'-icon.png'));print(name,len(groups),'native groups',sum(len(p['faces']) for p in groups['blade']),'blade faces')
if __name__=='__main__':main()
