"""Original Anon cuboid study: broad joined hair masses, shallow face and pixel eyes.

Yoruno is observed for construction only. No third-party geometry/UV/image is used.
R9 non-head objects, hierarchy, pivots and original textures remain exact.
"""
from pathlib import Path
import argparse,base64,hashlib,importlib.util,io,json,math,shutil
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'art/robot-mcp-workspace/characters/anon-yoruno-method-r15'
BASE=WORK.parent/'anon-proportion-face-r9/anon-proportion-face-r9.bbmodel'
MODEL='anon-yoruno-method-r15.bbmodel'
BODY={'body','arm_left','arm_right','leg_left','leg_right','skirt'}
BASE_SHA='e1b95c692c2892d69df1314def89d5132f732cb50b3e6d71cfbde70bc655b80b'
spec=importlib.util.spec_from_file_location('r15_helper',ROOT/'art/sources/robot_anon_reference_r14.py')
old=importlib.util.module_from_spec(spec);spec.loader.exec_module(old)
h=old.h;h.WORK=WORK;old.WORK=WORK
COLORS={'skin':'FAE7D8','hair':'F3B7C9','hair_inner':'E9A8BF','hair_shade':'DEA0B7',
 'lash':'40343C','white':'FFF7EF','rim':'546279','iris_dark':'63768F','iris':'8A9FB8',
 'iris_light':'ADC6DC','iris_low':'CEDFEA','pupil':'374A61','shine':'FFFDFC','shine_soft':'DBE8F2',
 'mouth':'BD8991','brow':'3A3036'}

def save(n,d):
 p=WORK/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def cube(name,center,size,color,parent,rot=None):
 idx=list(COLORS).index(color);u=idx%16*4;v=idx//16*4
 return {'op':'add_cube','name':'r15_'+name,'from':[round(c-s/2,6) for c,s in zip(center,size)],
 'to':[round(c+s/2,6) for c,s in zip(center,size)],'origin':center,'rotation':rot or [0,0,0],
 'parent':parent,'texture':8,'box_uv':False,'faces':{f:{'texture':8,'uv':[u+.5,v+.5,u+3.5,v+3.5]} for f in ['north','south','east','west','up','down']}}
def plank(name,a,b,width,depth,parent,color='hair',normal=(0,0,-1),overlap=.035):
 delta=[b[i]-a[i] for i in range(3)];length=math.sqrt(sum(v*v for v in delta));yy=h.unit(delta)
 xx=h.unit(h.cross(yy,list(normal)));zz=h.unit(h.cross(xx,yy));ry=math.asin(max(-1,min(1,-xx[2])))
 if abs(math.cos(ry))>1e-7:rx,rz=math.atan2(yy[2],zz[2]),math.atan2(xx[1],xx[0])
 else:rx,rz=0,math.atan2(-yy[0],yy[1])
 return cube(name,[(a[i]+b[i])/2 for i in range(3)],[width,length+overlap,depth],color,parent,[math.degrees(rx),math.degrees(ry),math.degrees(rz)])

ROUTES=[]
def lock(name,points,widths,depth,parent,color='hair',normal=(0,0,-1)):
 ROUTES.append({'name':name,'joints':points,'segment_widths':widths,'purpose':parent})
 return [plank(name+'_'+str(i),a,b,widths[i],depth,parent,color,normal,overlap=.14 if parent=='rear_long_layers' else .035) for i,(a,b) in enumerate(zip(points,points[1:]))]

def flat_lock(name,top,bottom,centers,width,parent,z=-3.31,tilt=34,depth=.23):
 """A single planar lock with a pixel-stepped silhouette, no exposed segment joints.

 Rectangular rows share exactly the same facing plane and rotation origin.
 Width changes affect silhouette only; they do not create overlapping shingles.
 """
 count=math.ceil((top-bottom)/.12);step=(top-bottom)/count;ops=[]
 for i in range(count):
  t=(i+.5)/count;cx=(1-t)**2*centers[0]+2*t*(1-t)*centers[1]+t*t*centers[2]
  w=width*(1-.10*t) if t<.68 else width*.932*(1-(t-.68)/.32)**.75
  lo=round((cx-w/2)/.06)*.06;hi=round((cx+w/2)/.06)*.06
  if hi-lo<.06:hi=lo+.06
  o=cube(name+'_'+str(i),[(lo+hi)/2,top-(i+.5)*step,z],[hi-lo,step+.0001,depth],'hair',parent,[tilt,0,0])
  o['origin']=[0,24.3 if tilt>10 else 25.0,z];ops.append(o)
 ROUTES.append({'name':name,'method':'coplanar cuboid silhouette','y_range':[bottom,top],'centers':centers,'width':width})
 return ops

def face():
 ops=[];p='face_skin_structure'
 # Broad uninterrupted face with short, shallow jaw pieces; all faces coplanar.
 ops.append(cube('continuous_face',[0,23.20,-2.78],[5.55,3.30,.56],'skin',p))
 ops.append(cube('jaw_center',[0,21.27,-2.78],[1.42,.55,.56],'skin',p))
 ops.append(cube('jaw_internal_bridge',[0,21.55,-2.78],[2.30,.40,.56],'skin',p))
 for s in [-1,1]:
  ops.append(cube('shallow_jaw_'+str(s),[s*1.65,21.40,-2.78],[2.22,.42,.56],'skin',p,[0,0,s*12]))
 ops.append(cube('hidden_skull',[0,24.60,.58],[5.65,4.60,4.10],'hair_inner','crown_layers'))
 for o in ops:
  if o['parent']==p:
   for key in ['from','to','origin']:o[key][1]-=.285
 return ops

def eyes():
 ops=[]
 # Hand-drawn original pixel iris; explicit grid, not the previous ellipse algorithm.
 grid=['  dddddddd  ',' dddddddddd ',' rrddddSSdr ',' rriiiiSSir ',' rriippiiir ',' rriippiiir ',
       ' rliippiiir ',' rliippiiir ',' rliiiiilir ','  lllliill  ','  lLWWLLlL  ','   LWWLLL   ','    LLLL    ']
 cols={'d':'iris_dark','r':'rim','i':'iris','l':'iris_light','L':'iris_low','p':'pupil','S':'shine','W':'shine_soft'}
 for s in [-1,1]:
  side='left' if s<0 else 'right';p='eye_geometry_'+side;cx=s*1.40;cy=22.98
  ops.append(cube('eye_white_'+side,[cx,cy,-3.074],[1.69,1.14,.024],'white',p))
  ops.append(cube('eye_white_lower_'+side,[cx-s*.03,cy-.58,-3.074],[1.38,.12,.024],'white',p))
  for row,line in enumerate(grid):
   col=0
   while col<len(line):
    c=line[col];end=col+1
    while end<len(line) and line[end]==c:end+=1
    if c!=' ':
     ops.append(cube(f'iris_{side}_{row}_{col}',[cx+(col+end-12)*.052,cy+.516-row*.086,-3.092],[(end-col)*.104,.086,.023],cols[c],p))
    col=end
  ops.append(cube('upper_lash_'+side,[cx,cy+.62,-3.105],[1.05,.14,.033],'lash',p))
  ops.append(cube('upper_inner_'+side,[cx-s*.64,cy+.55,-3.105],[.26,.12,.033],'lash',p))
  ops.append(cube('upper_outer_'+side,[cx+s*.64,cy+.56,-3.105],[.30,.14,.033],'lash',p))
  ops.append(cube('outer_lash_'+side,[cx+s*.85,cy+.45,-3.105],[.20,.24,.033],'lash',p))
  ops.append(cube('outer_lash_tip_'+side,[cx+s*.98,cy+.52,-3.105],[.14,.09,.033],'lash',p))
  ops.append(cube('brow_'+side,[cx-s*.10,24.04,-3.09],[.57,.055,.025],'brow','brow_geometry'))
 ops.append(cube('mouth_single_rectangle',[0,21.58,-3.09],[.48,.048,.025],'mouth','smile_geometry'))
 for o in ops:
  for key in ['from','to','origin']:o[key][1]-=.285
 return ops

def hair():
 ROUTES.clear();ops=[];p='crown_layers'
 # A broad transverse dome, not many segmented longitudinal ribbons.
 arc=[[-3.63,25.25,.1],[-3.30,26.25,.1],[-2.55,27.08,.1],[-1.35,27.60,.1],
      [0,27.76,.1],[1.35,27.60,.1],[2.55,27.08,.1],[3.30,26.25,.1],[3.63,25.25,.1]]
 for i,(a,b) in enumerate(zip(arc,arc[1:])):ops.append(plank('crown_arch_'+str(i),a,b,.30,3.1,p,normal=(0,0,-1),overlap=.07))
 # Front/rear turn-down panels meet the broad cap; front joints hidden by fringe roots.
 for i,(x,y,width) in enumerate([(-2.9,26.70,1.3),(-1.5,27.35,1.6),(0,27.55,1.6),(1.5,27.35,1.6),(2.9,26.70,1.3)]):
  for side in [1]:
   ops+=lock(f'cap_turn_{i}_{side}',[[x,y,side*1.40],[x*1.02,y-1.62,side*3.05]],
             [width+.12],.40,p,normal=(0,0,-1 if side<0 else 1))
 # Seven individually planned main locks. Coordinates use front-screen X, mirrored at output.
 # Continuous planar silhouettes; all internal rectangular joins are coplanar.
 bangs=[
  ('short_outer',27.10,23.73,[-2.70,-2.99,-2.43],1.12,-3.24),
  ('short_inner',27.90,24.40,[-1.82,-2.13,-1.35],1.36,-3.30),
  ('sweep_inner',28.20,23.93,[-.83,-.86,.27],1.45,-3.34),
  ('sweep_main',28.20,23.90,[.31,.19,1.57],1.63,-3.34),
  ('sweep_outer',27.91,24.10,[1.40,1.61,2.70],1.38,-3.30),
  ('temple_left',26.87,23.32,[-3.10,-3.35,-2.92],.95,-2.98),
  ('temple_right',27.20,23.25,[2.54,3.27,3.13],1.00,-3.00)]
 for name,top,bottom,centers,w,z in bangs:ops+=flat_lock(name,top,bottom,centers,w,'side_swept_fringe',z+.085,34,.40)
 # Three connected cheek locks per side, not small fragments applied to the skin.
 for s in [-1,1]:
  def pt(items):return [[s*x,y,z] for x,y,z in items]
  ops+=flat_lock('cheek_main_'+str(s),25.10,21.25,[s*3.02,s*3.10,s*2.31],.86,'cheek_and_temple_locks',-2.925,2,.90)
  ops+=flat_lock('cheek_outer_'+str(s),25.11,21.00,[s*3.50,s*3.72,s*2.94],.94,'cheek_and_temple_locks',-2.405,1,1.10)
  ops.append(cube('side_cover_'+str(s),[s*3.35,24.02,.30],[.45,3.55,4.55],'hair','crown_layers'))
  ops+=lock('temple_rear_'+str(s),pt([[3.47,26.03,-1.05],[3.90,24.1,-1.62],[3.83,21.75,-2.16]]),[.87,.86],.42,'side_long_layers',normal=(s,0,-.4))
  for k,(z,end,wide) in enumerate([(-1.25,13.3,1.15),(-.15,12.7,1.28),(1.05,13.05,1.34),(2.15,13.50,1.18)]):
   ops+=lock(f'side_long_{s}_{k}',pt([[3.46,25.2,z],[3.86,21.60,z+.20],[4.32,16.60,z+.15],[4.05,end,z-.10],[3.75,end-.50,z-.17]]),
             [wide,wide+.05,wide*.91,wide*.49],.50,'side_long_layers','hair' if k<2 else 'hair_inner',normal=(s,0,0))
 # Five broad rear masses, with varied inflection positions and small tips.
 for j,(x,z,end,width) in enumerate([(-2.60,2.75,13.25,1.48),(-1.35,3.08,12.6,1.57),(0,3.17,12.95,1.59),(1.35,3.08,13.30,1.57),(2.60,2.75,12.85,1.48)]):
  ops+=lock('back_mass_'+str(j),[[x,25.90,z],[x*1.09,21.4+(j%2)*.6,z+.23],[x*1.19,16.35-(j%2)*.3,z+.35],[x*1.15,end,z+.10],[x*1.08,end-.55,z-.04]],
            [width,width+.08,width*1.04,width*.53],.55,'rear_long_layers','hair_inner',normal=(0,0,1))
 return ops

def head_ops():
 ops=face()+eyes()+hair()
 # Head framing coordinates were authored as seen from front, Blockbench front reverses X.
 for o in ops:
  o['from'][0],o['to'][0]=-o['to'][0],-o['from'][0];o['origin'][0]*=-1
  o['rotation'][1]*=-1;o['rotation'][2]*=-1
 return ops

def palette():
 # Exact palette swatches form a code-native model atlas; no reference bitmap is sampled.
 im=Image.new('RGBA',(64,64),(0,0,0,0));d=ImageDraw.Draw(im)
 for i,c in enumerate(COLORS.values()):d.rectangle(((i%16)*4,(i//16)*4,(i%16)*4+3,(i//16)*4+3),fill='#'+c)
 im.save(WORK/'original-r15-palette.png');b=io.BytesIO();im.save(b,format='PNG')
 return 'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()

def build():
 WORK.mkdir(parents=True,exist_ok=True)
 if old.sha(BASE)!=BASE_SHA:raise SystemExit('R9 baseline mismatch')
 if (WORK/MODEL).exists():raise SystemExit('Candidate exists, use revise')
 shutil.copy2(BASE,WORK/MODEL);d=old.read(BASE);pp=old.parents(d)
 ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] not in BODY]
 ops.append({'op':'add_texture','name':'original-r15-palette','width':64,'height':64,'source':palette()})
 ops+=head_ops();ops.append({'op':'set_model_properties','name':'anon-yoruno-method-r15'})
 old.edit(MODEL,ops,'r15-personal-build');save('authored-hair-layout.json',ROUTES);freeze()

def freeze():
 old.MODEL=MODEL;return old.freeze()

def revise():
 i=1
 while (WORK/f'pass-{i}').exists():i+=1
 arc=WORK/f'pass-{i}';arc.mkdir()
 for p in [WORK/MODEL,*WORK.glob('*.png'),WORK/'authored-hair-layout.json']:
  if p.exists():shutil.copy2(p,arc/p.name)
 d=old.read(WORK/MODEL);pp=old.parents(d)
 ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] not in BODY]
 ops.append({'op':'update_texture','target':'original-r15-palette','width':64,'height':64,'source':palette()})
 ops+=head_ops();old.edit(MODEL,ops,f'r15-personal-revise-{i}');save('authored-hair-layout.json',ROUTES);freeze()

def preview():
 freeze();f=old.frame('r15',WORK/MODEL)
 for view in ['front','three-quarter','left','back']:h.render(f,view,'r15-head-'+view+'.png',900,'r15-head-'+view,'studio')
 for view in ['front','three-quarter']:h.render(MODEL,view,'r15-full-'+view+'.png',900,'r15-full-'+view,'studio')
 h.render(MODEL,'front','r15-normal-256.png',256,'r15-normal','studio')

def finish():
 d=freeze();info=h.mcp('bbmodel_info',{'file':MODEL},'r15-final-info')
 v=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r15-final-static')
 d.update({'counts':info['counts'],'static':v['summary'],'self_test':False,'warning_categories':{g['id']:len(g['violations']) for g in v['gates']},
 'status':'static original candidate; pending human review','reference_assets_copied':False})
 save('facts.json',d)
 for view in ['left','right','back']:h.render(MODEL,view,'r15-full-'+view+'.png',900,'r15-full-'+view,'studio')
 h.render('r15-head-inspection.bbmodel','right','r15-head-right.png',900,'r15-head-right','studio')
 h.render('r15-head-inspection.bbmodel','front','r15-head-flat.png',900,'r15-head-flat','flat')
 print(json.dumps(d,ensure_ascii=False))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('stage',choices=['build','revise','preview','finish','freeze']);globals()[p.parse_args().stage]()
