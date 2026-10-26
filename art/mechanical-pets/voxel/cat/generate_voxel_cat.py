from pathlib import Path
from PIL import Image, ImageDraw
import json
OUT=Path(__file__).resolve().parent
ATLAS=OUT/'cat_voxel_atlas.png'
COLORS={'coat':(194,137,68),'coat_light':(222,177,104),'stripe':(91,55,32),'white':(247,245,234),'cream':(232,226,210),'pink':(185,101,91),'eye':(75,47,31),'iris':(166,110,55),'pupil':(34,24,19),'glint':(255,246,219),'brass':(161,110,52),'mouth':(76,47,36),'flame_orange':(230,112,36),'flame_yellow':(255,218,74),'wing_edge':(216,218,211)}
slots={n:(i%8*8,i//8*8) for i,n in enumerate(COLORS)}
im=Image.new('RGB',(64,64),(0,0,0));d=ImageDraw.Draw(im)
for n,(x,y) in slots.items():d.rectangle((x,y,x+7,y+7),fill=COLORS[n])
# A crisp 16x16 frontal face swatch, drawn in pixels only (no gradients).
fx,fy=0,32
d.rectangle((fx,fy,fx+15,fy+15),fill=COLORS['coat_light'])
# Sparse short forehead bars, compact deep-brown eyes, one catchlight, and a small cream muzzle.
for x in (4,8,12):
 d.line((fx+x,fy+0,fx+x,fy+2),fill=COLORS['stripe'])
for x in (3,10):
 d.rectangle((fx+x,fy+4,fx+x+2,fy+7),fill=COLORS['pupil'])
 d.point((fx+x,fy+4),fill=COLORS['glint'])
# Clean cream lower cheeks and small pink triangular nose.
d.rectangle((fx+3,fy+10,fx+12,fy+13),fill=COLORS['white'])
d.point((fx+7,fy+9),fill=COLORS['pink']);d.point((fx+8,fy+9),fill=COLORS['pink']);d.point((fx+7,fy+10),fill=COLORS['pink'])
d.line((fx+6,fy+14,fx+9,fy+14),fill=COLORS['mouth'])
# Broken mackerel tabby side/back tiles: crisp discrete pixels, no gradient or noise.
sx,sy=32,32
d.rectangle((sx,sy,sx+15,sy+15),fill=COLORS['coat'])
for col,ys in ((3,(2,3,4,8,9,10,13)),(8,(1,2,6,7,8,12,13)),(13,(3,4,5,9,10,11,14))):
 for yy in ys:d.point((sx+col,sy+yy),fill=COLORS['stripe'])
 # one pixel softened edge gives a stepped coat band, still hard palette color
 for yy in ys[1:-1]:
  if yy%2==0:d.point((sx+col+1,sy+yy),fill=COLORS['coat_light'])
bx,by=48,32
d.rectangle((bx,by,bx+15,by+15),fill=COLORS['coat_light'])
for row in (3,7,11):
 for xx in range(2,14):
  if xx not in (4,5,10):d.point((bx+xx,by+row),fill=COLORS['stripe'])
# ear face swatch
ex,ey=16,32
d.rectangle((ex,ey,ex+7,ey+7),fill=COLORS['coat'])
for yy,x1,x2 in ((1,3,4),(2,2,5),(3,2,5),(4,2,5),(5,3,4)):
 d.line((ex+x1,ey+yy,ex+x2,ey+yy),fill=COLORS['pink'])
im.save(ATLAS)
faces={n:{side:{'uv':[x,y,x+8,y+8],'texture':'cat_voxel_atlas'} for side in ('north','south','east','west','up','down')} for n,(x,y) in slots.items()}
face_uv={'uv':[0,32,16,48],'texture':'cat_voxel_atlas'}
flank_uv={'uv':[32,32,48,48],'texture':'cat_voxel_atlas'}
back_uv={'uv':[48,32,64,48],'texture':'cat_voxel_atlas'}
ear_uv={'uv':[16,32,24,40],'texture':'cat_voxel_atlas'}
ops=[]
# Integer world coordinates and bone origins only. Model bounds: x -3..3, y 0..11, z -7..8.
origins={'body':[0,0,0],'head':[0,8,-5],'jaw':[0,6,-6],'ear_l':[-2,10,-5],'ear_r':[2,10,-5],'tail':[0,4,4],'tail_flame':[0,8,7],'wing_l':[-2,5,0],'wing_r':[2,5,0],'leg_fl':[-1,3,-2],'leg_fr':[1,3,-2],'leg_bl':[-1,3,3],'leg_br':[1,3,3]}
parents={'body':None,'head':'body','jaw':'head','ear_l':'head','ear_r':'head','tail':'body','tail_flame':'tail','wing_l':'body','wing_r':'body','leg_fl':'body','leg_fr':'body','leg_bl':'body','leg_br':'body'}
for n,o in origins.items():ops.append({'op':'add_group','name':n,'origin':o,'parent':parents[n]})
def cube(name,frm,to,parent,color,front=None,face_overrides=None):
 assert all(isinstance(v,int) for v in frm+to), (name,frm,to)
 assert all(a<b for a,b in zip(frm,to)),(name,frm,to)
 assert all(b-a<=16 for a,b in zip(frm,to)), (name,frm,to)
 f={k:dict(v) for k,v in faces[color].items()}
 if front is not None:f['north']=dict(front)
 if face_overrides is not None:
  for side,uv in face_overrides.items():f[side]=dict(uv)
 ops.append({'op':'add_cube','name':name,'from':frm,'to':to,'origin':origins[parent],'rotation':[0,0,0],'parent':parent,'box_uv':False,'faces':f})
# Compact feline torso and chest bib.
cube('torso',[-2,3,-3],[2,6,5],'body','coat',face_overrides={'east':flank_uv,'west':flank_uv,'up':back_uv})
cube('white_chest',[-1,4,-4],[1,6,-3],'body','white')
cube('white_belly',[-1,3,0],[1,4,4],'body','white')
# Six-wide compact head; face artwork is a north-face atlas region, not floating geometry.
cube('head',[-3,6,-7],[3,10,-3],'head','coat_light',face_uv)
# Cuboid ears with inner-pink pixels included in the front-face atlas swatch.
cube('ear_l',[-3,10,-6],[-1,11,-4],'ear_l','coat',ear_uv)
cube('ear_r',[1,10,-6],[3,11,-4],'ear_r','coat',ear_uv)
# Positive-volume lower jaw sits flush below the head's north face; it hinges at the rear mouth corner.
cube('mouth_interior',[-1,5,-6],[1,6,-5],'head','mouth')
cube('lower_jaw',[-1,5,-7],[1,6,-5],'jaw','cream')
# Four short sturdy legs and two-unit paws, all on the integer floor.
for bone,x,z in (('leg_fl',-1,-2),('leg_fr',1,-2),('leg_bl',-1,3),('leg_br',1,3)):
 xmin,xmax=(x-1,x) if x<0 else (x,x+1)
 cube(bone+'_leg',[xmin,1,z-1],[xmax,4,z+1],bone,'coat')
 cube(bone+'_paw',[xmin,0,z-1],[xmax,1,z+1],bone,'white')
# Three overlapping stair-step tail blocks in a short raised feline hook.
cube('tail_base',[-1,4,4],[1,6,6],'tail','coat')
cube('tail_middle',[-1,5,5],[1,7,7],'tail','coat_light')
cube('tail_tip',[-1,6,6],[1,8,8],'tail','coat')
# Small square collar and tag; entirely attached to the chest.
cube('collar_band',[-1,6,-4],[1,7,-3],'body','brass')
cube('collar_tag',[0,5,-5],[1,6,-4],'body','brass')
# Cute tail-tip flame built from three stepped positive-volume blocks; it stays inside z<=8.
cube('tail_flame_outer',[-1,7,7],[1,9,8],'tail_flame','flame_orange')
cube('tail_flame_tip',[0,9,7],[1,10,8],'tail_flame','flame_orange')
cube('tail_flame_core',[0,7,7],[1,8,8],'tail_flame','flame_yellow')
# Small cream/gold feather-like side wings, each assembled from two cuboids.
# One-unit-thick angel wings. Their YZ projection covers 20 of the torso's 24
# side cells (83.3%): two full rows and one stepped lower row.
cube('wing_l_base',[-3,5,-3],[-2,6,4],'wing_l','white')
cube('wing_l_feather_front',[-3,4,-3],[-2,5,0],'wing_l','wing_edge')
cube('wing_l_feather_mid',[-3,4,0],[-2,5,4],'wing_l','white')
cube('wing_l_feather_rear',[-3,3,-2],[-2,4,1],'wing_l','white')
cube('wing_l_outer_front',[-3,3,1],[-2,4,4],'wing_l','wing_edge')
cube('wing_l_outer_rear',[-3,6,-3],[-2,7,-1],'wing_l','wing_edge')
cube('wing_r_base',[2,5,-3],[3,6,4],'wing_r','white')
cube('wing_r_feather_front',[2,4,-3],[3,5,0],'wing_r','wing_edge')
cube('wing_r_feather_mid',[2,4,0],[3,5,4],'wing_r','white')
cube('wing_r_feather_rear',[2,3,-2],[3,4,1],'wing_r','white')
cube('wing_r_outer_front',[2,3,1],[3,4,4],'wing_r','wing_edge')
cube('wing_r_outer_rear',[2,6,-3],[3,7,-1],'wing_r','wing_edge')
# A jaw block pivots at the mouth's rear edge. Negative X opens downward on Y-up BB.
def anim(name,length,loop,keys):
 ops.append({'op':'add_animation','name':name,'length':length,'loop':loop,'snapping':20})
 for bone,ch,t,v in keys:ops.append({'op':'set_keyframe','animation':name,'bone':bone,'channel':ch,'time':t,'value':v,'interpolation':'catmullrom'})
anim('idle',2,'loop',[('tail','rotation',0,[0,0,-4]),('tail','rotation',1,[0,0,4]),('tail','rotation',2,[0,0,-4]),('ear_l','rotation',0,[0,0,-2]),('ear_r','rotation',0,[0,0,2]),('wing_l','rotation',0,[0,0,0]),('wing_l','rotation',1,[0,0,8]),('wing_l','rotation',2,[0,0,0]),('wing_r','rotation',0,[0,0,0]),('wing_r','rotation',1,[0,0,-8]),('wing_r','rotation',2,[0,0,0])])
anim('yawn',2.4,'once',[('jaw','rotation',0,[0,0,0]),('jaw','rotation',.8,[-24,0,0]),('jaw','rotation',1.25,[-32,0,0]),('jaw','rotation',2.1,[0,0,0]),('head','rotation',.6,[8,0,0]),('head','rotation',1.8,[0,0,0])])
anim('pancake',1,'once',[('body','scale',0,[1,1,1]),('body','scale',1,[1,.36,1]),('leg_fl','rotation',1,[0,0,-18]),('leg_fr','rotation',1,[0,0,18]),('leg_bl','rotation',1,[0,0,18]),('leg_br','rotation',1,[0,0,-18]),('tail','rotation',1,[0,0,16])])
anim('restore',1.4,'once',[('body','scale',0,[1,.36,1]),('body','scale',.65,[1,.76,1]),('body','scale',1.4,[1,1,1]),('leg_fl','rotation',.65,[0,0,-12]),('leg_fl','rotation',1.4,[0,0,0]),('leg_fr','rotation',.65,[0,0,12]),('leg_fr','rotation',1.4,[0,0,0]),('tail','rotation',.65,[0,0,14]),('tail','rotation',1.4,[0,0,0])])
(OUT/'build-voxel-ops.json').write_text(json.dumps(ops,separators=(',',':')),encoding='utf-8')
idx={'model':'cat.bbmodel','style':'minecraft voxel cuboids','texture':'cat_voxel_atlas.png','resolution':[64,64],'cube_count':sum(o['op']=='add_cube' for o in ops),'groups':parents,'bounds':{'x':[-3,3],'y':[0,11],'z':[-7,8]},'coordinates':'integer model units; all static rotations zero','front':'-Z','foot_y':0,'wing_side_projection':{'torso_y':[3,6],'torso_z':[-3,5],'torso_cells':24,'left_wing_intersection_cells':20,'right_wing_intersection_cells':20,'coverage_percent_each':83.33},'notes':'Only positive-volume cuboids. Pixel face atlas carries the face details; no spheres, cylinders, meshes, or floating planes.'}
(OUT/'geometry-index.json').write_text(json.dumps(idx,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('ops',len(ops),'cubes',idx['cube_count'],'all coordinates integer')


