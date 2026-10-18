"""Original cuboid Anon head, visually informed by the supplied reference.

R9 non-head geometry frozen. No source mesh, UV, texture, or animation imported.
Actual edits/renders use the installed upstream Blockbench MCP headless tools.
"""
from pathlib import Path
import argparse,base64,hashlib,importlib.util,io,json,math,shutil
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'art/robot-mcp-workspace/characters/anon-reference-r14'
BASE=WORK.parent/'anon-proportion-face-r9/anon-proportion-face-r9.bbmodel'
OLD=WORK.parent/'anon-r9-likeness-r13/anon-r9-likeness-r13.bbmodel'
MODEL='anon-reference-r14.bbmodel'
BASE_SHA='e1b95c692c2892d69df1314def89d5132f732cb50b3e6d71cfbde70bc655b80b'
BODY={'body','arm_left','arm_right','leg_left','leg_right','skirt'}
COLORS={'skin':'F9E7DB','skin_side':'F0DACE','nose':'E9CDBF',
 'hair':'F2B7C7','hair_light':'F6C0CE','hair_low':'E7A7BD','hair_deep':'DD9CB4',
 'lash':'443640','white':'FFF7EF','rim':'4E596E','upper':'65758D','iris':'94A5BC',
 'lower':'CBD9E7','pupil':'374252','shine':'FFFDFC','soft':'A8B5C5','brow':'44363F','mouth':'BD8A94',
 'iris_mid':'7E90AA','iris_bright':'B1C4D9','shine_soft':'E4EAF2'}
spec=importlib.util.spec_from_file_location('r14_mcp_helper',ROOT/'art/sources/robot_layered_head.py')
h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h);h.WORK=WORK

def read(p):return json.loads(p.read_text(encoding='utf8'))
def save(n,d):
 p=WORK/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def edit(file,ops,label):
 for start in range(0,len(ops),450):
  key=label+'-'+str(start//450)
  info=h.mcp('bbmodel_info',{'file':file},key+'-before')
  h.mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':ops[start:start+450]},key)
def parents(d):
 names={g['uuid']:g['name'] for g in d['groups']};out={}
 def walk(ns,parent=None):
  for n in ns:
   if isinstance(n,str):out[n]=parent
   else:walk(n.get('children',[]),names[n['uuid']])
 walk(d['outliner']);return out

def cube(name,center,size,color,parent,rotation=None):
 idx=list(COLORS).index(color);u=(idx%16)*4;v=(idx//16)*4;uv=[u+.5,v+.5,u+3.5,v+3.5]
 return {'op':'add_cube','name':'r14_'+name,'from':[round(c-s/2,6) for c,s in zip(center,size)],
 'to':[round(c+s/2,6) for c,s in zip(center,size)],'origin':center,'rotation':rotation or [0,0,0],
 'parent':parent,'texture':8,'box_uv':False,
 'faces':{f:{'texture':8,'uv':uv} for f in ['north','south','east','west','up','down']}}

def segment(name,a,b,width,thickness,parent,color='hair',normal=(0,0,-1),overlap=.12):
 delta=[b[i]-a[i] for i in range(3)];length=math.sqrt(sum(v*v for v in delta));yy=h.unit(delta)
 xx=h.unit(h.cross(yy,list(normal)));zz=h.unit(h.cross(xx,yy));ry=math.asin(max(-1,min(1,-xx[2])))
 if abs(math.cos(ry))>1e-7:rx,rz=math.atan2(yy[2],zz[2]),math.atan2(xx[1],xx[0])
 else:rx,rz=0,math.atan2(-yy[0],yy[1])
 return cube(name,[(a[i]+b[i])/2 for i in range(3)],[width,length+overlap,thickness],color,parent,
             [math.degrees(rx),math.degrees(ry),math.degrees(rz)])

ROUTES=[]
def curve(name,controls,width,thick,parent,color='hair',n=7,end=.14,normal=(0,0,-1)):
 points=[]
 for i in range(n+1):
  t=i/n;points.append([sum(controls[k][j]*[ (1-t)**3,3*(1-t)**2*t,3*(1-t)*t*t,t**3][k] for k in range(4)) for j in range(3)])
 ROUTES.append({'name':name,'controls':controls,'width':width,'end_width':end,'parent':parent})
 ops=[]
 for i in range(n):
  t=(i+.5)/n;w=width*(1-.12*t) if t<.65 else width*.922+(end-width*.922)*((t-.65)/.35)
  ops.append(segment(name+'_'+str(i),points[i],points[i+1],max(end,w),thick,parent,color,normal,.10))
 return ops

def skin_ops():
 p='face_skin_structure';ops=[]
 # One plane assembled from a central face and two oblique cheek volumes.
 # Their front surfaces are exactly coplanar: no skin stair-step layers.
 ops.append(cube('face_upper',[0,24.80,-1.3],[5.8,1.8,3.8],'skin',p))
 ops.append(cube('face_center',[0,23.50,-1.3],[1.6,4.4,3.8],'skin',p))
 for s in [-1,1]:
  for j,(aa,bb) in enumerate([([2.9,23.9],[2.4,22.4]),([2.4,22.4],[.8,21.3])]):
   a=[aa[0],aa[1],-1.3];b=[bb[0],bb[1],-1.3]
   yy=h.unit([b[i]-a[i] for i in range(3)]);xx=h.unit(h.cross(yy,[0,0,-1]));width=2.7
   a=[a[i]-xx[i]*width/2 for i in range(3)];b=[b[i]-xx[i]*width/2 for i in range(3)]
   a[0]*=s;b[0]*=s
   ops.append(segment(f'cheek_edge_{s}_{j}',a,b,width,3.8,p,'skin',overlap=0))
 # Recessed skull and connector; neither protrudes below the face or into the eyes.
 ops.append(cube('covered_hair_core',[0,25.0,.15],[5.25,4.2,4.5],'hair','crown_layers'))
 return ops

def eye_grid(sign):
 grid=[];step=.075
 for row in range(23):
  y=.825-row*step;line=[]
  for col in range(30):
   x=-1.0875+col*step;top=.69*math.sqrt(max(0,1-(x/1.05)**2))+.085*x
   low=-.65*math.sqrt(max(0,1-(x/1.05)**2))+.085*x;c=None
   if abs(x)<=1.05 and low<=y<=top:
    c='white';q=((x-.025)/.65)**2+((y+.015)/.72)**2
    if q<=1:
     c='upper' if y>.40 else 'iris_mid' if y>.18 else 'iris' if y>-.10 else 'iris_bright' if y>-.36 else 'lower'
     if q>.90:c='rim' if y>-.12 else 'soft'
     if ((x-.015)/.14)**2+((y-.045)/.18)**2<1:c='pupil'
     light_x=-sign*x
     if ((light_x-.29)/.18)**2+((y-.35)/.17)**2<1:c='shine_soft'
     if ((light_x-.27)/.09)**2+((y-.38)/.09)**2<1:c='shine'
     if ((light_x+.12)/.27)**2+((y+.43)/.16)**2<1:c='shine_soft'
    if top-y<.13:c='lash'
    if y-low<.045 and abs(x)>.55:c='soft'
   line.append(c)
  grid.append(line)
 return grid

def feature_ops():
 ops=[]
 for side,s in [('left',1),('right',-1)]:
  cx=1.43*s;grid=eye_grid(s)
  for row,line in enumerate(grid):
   col=0
   while col<len(line):
    color=line[col];end=col+1
    while end<len(line) and line[end]==color:end+=1
    if color:
     xx=-1.0875+(col+end-1)*.0375
     ops.append(cube(f'eye_{side}_{row}_{col}',[cx+s*xx,23.94+.825-row*.075,-3.212],[(end-col)*.075,.075,.028],color,'eye_geometry_'+side))
    col=end
  for j,(dx,dy,w,angle) in enumerate([(1.01,.23,.20,23),(1.075,.30,.14,26)]):
   ops.append(cube(f'lash_{side}_{j}',[cx+s*dx,23.94+dy,-3.215],[w,.065,.03],'lash','eye_geometry_'+side,[0,0,s*angle]))
  for j,(dx,dy,w,angle) in enumerate([(-.29,0,.25,9),(-.06,.035,.24,4),(.17,.03,.24,-6),(.365,-.01,.18,-13)]):
   ops.append(cube(f'brow_{side}_{j}',[cx+s*dx,25.0+dy,-3.218],[w,.042,.03],'brow','brow_geometry',[0,0,s*angle]))
 ops.append(cube('mouth_single_rectangle',[0,22.27,-3.218],[.57,.055,.03],'mouth','smile_geometry'))
 ops.append(cube('nose_hint',[0,22.94,-3.214],[.075,.105,.028],'nose','face_skin_structure'))
 return ops

def hair_ops():
 ops=[];ROUTES.clear()
 ops.append(cube('fringe_underlayer',[0,26.40,-2.27],[5.7,2.10,1.30],'hair','side_swept_fringe',[20,0,0]))
 # Aligned ribbons form the crown; no random tiny surface tiles.
 for j in range(11):
  x=(j-5)*.68;fall=1.45*(abs(x)/3.75)**2;pts=[]
  for deg in [-65,-43,-21,1,23,45,67,83]:
   a=math.radians(deg);pts.append([x,25.22+3.35*math.cos(a)-fall,3.10*math.sin(a)])
  for i in range(len(pts)-1):
   ops.append(segment(f'crown_{j}_{i}',pts[i],pts[i+1],.80,.42,'crown_layers','hair',normal=(0,1,0),overlap=.13))
 # Broad asymmetrical fringe bundles, independently authored from visual landmarks.
 bangs=[
  ('part_outer',[[-2.85,27.35,-2.5],[-3.18,26.4,-3.1],[-3.1,25.2,-3.48],[-2.72,24.84,-3.42]],.98),
  ('part_short',[[-2.20,28.0,-2.55],[-2.35,27.2,-3.4],[-2.48,25.8,-3.65],[-2.05,25.20,-3.52]],1.18),
  ('sweep_inner',[[-1.38,28.35,-2.45],[-1.33,26.9,-3.55],[-.9,25.9,-3.72],[-.34,24.87,-3.48]],1.26),
  ('sweep_main',[[-.40,28.53,-2.3],[-.40,26.9,-3.61],[.58,25.7,-3.83],[1.18,24.77,-3.49]],1.38),
  ('sweep_outer',[[.68,28.40,-2.20],[.72,26.8,-3.56],[1.84,25.8,-3.7],[2.33,25.05,-3.43]],1.4),
  ('temple_sweep',[[1.85,28.00,-1.9],[2.20,26.8,-3.05],[2.9,25.7,-3.2],[3.0,24.55,-2.95]],1.24),
  ('outer_cap',[[2.85,27.4,-1.30],[3.35,26.8,-2.1],[3.5,25.3,-2.55],[3.31,24.10,-2.65]],.96)]
 for j,(name,pts,w) in enumerate(bangs):ops+=curve(name,pts,w,.25,'side_swept_fringe','hair_light',n=6+j%3,end=.12)
 for s in [-1,1]:
  def pts(v):return [[s*x,y,z] for x,y,z in v]
  ops+=curve('cheek_main_'+str(s),pts([[3.15,26.5,-2.6],[3.45,25.1,-3.0],[3.18,23.2,-3.35],[2.32,21.87,-3.18]]),.87,.34,'cheek_and_temple_locks','hair_light',9,.12)
  ops+=curve('cheek_outer_'+str(s),pts([[3.55,26.0,-1.65],[3.98,24.5,-2.18],[3.71,22.45,-2.8],[2.98,21.43,-2.75]]),.96,.42,'cheek_and_temple_locks','hair',9,.15)
  ops+=curve('cheek_inner_'+str(s),pts([[2.89,25.7,-2.7],[3.0,24.65,-3.22],[2.94,23.1,-3.38],[2.56,22.25,-3.38]]),.46,.18,'cheek_and_temple_locks','hair_light',7,.08)
  for k in range(6):
   z=-1.72+k*.88;root_y=25.22+3.35*math.sqrt(max(0,1-(z/3.10)**2))-1.12
   ops+=curve(f'side_drape_{s}_{k}',pts([[3.32,root_y,z],[3.83,24.1,z-.08],[4.55,17.9,z-.20],[4.12+.07*k,13.1+.24*(k%3),z-.24]]),1.04,.66,'side_long_layers','hair' if k<4 else 'hair_low',11,.40,normal=(s,0,0))
 # Overlapping large back strands and tucked inner rear layer cover the entire skull.
 for j in range(11):
  x=(j-5)*.66;endx=x*1.27;z=3.16-.55*(abs(x)/3.3)**2
  ops+=curve(f'back_drape_{j}',[[x,26.65,z],[x*1.10,23.4,z+.28],[endx,17.9,z+.52],[endx,13.15+.32*(j%3),z+.15]],.91,.65,'rear_long_layers','hair_low' if j%3==0 else 'hair',12,.36,normal=(0,0,1))
 return ops

def freeze():
 a,b=read(BASE),read(WORK/MODEL);pa,pb=parents(a),parents(b)
 ea={e['uuid']:e for e in a['elements'] if pa[e['uuid']] in BODY};eb={e['uuid']:e for e in b['elements'] if pb[e['uuid']] in BODY}
 def gt(d):
  def walk(ns):return [{**{k:v for k,v in n.items() if k!='children'},'children':walk(n.get('children',[]))} for n in ns if not isinstance(n,str)]
  return walk(d['outliner'])
 data={'base_sha256':sha(BASE),'candidate_sha256':sha(WORK/MODEL),'frozen_body_count':len(ea),
  'body_and_skirt_equal':ea==eb,'groups_equal':a['groups']==b['groups'],'hierarchy_equal':gt(a)==gt(b),
  'old_textures_equal':a['textures']==b['textures'][:8],'resolution_equal':a['resolution']==b['resolution'],
  'cube_count':len(b['elements']),'positive_cubes_only':all(e.get('type','cube')=='cube' and all(v>u for u,v in zip(e['from'],e['to'])) for e in b['elements']),
  'mouth_count':sum(pb[e['uuid']]=='smile_geometry' for e in b['elements'])}
 for k in ['body_and_skirt_equal','groups_equal','hierarchy_equal','old_textures_equal','resolution_equal','positive_cubes_only']:
  if not data[k]:raise SystemExit('Resource invariant failed: '+k)
 if data['base_sha256']!=BASE_SHA or data['mouth_count']!=1:raise SystemExit('Original R9 or single mouth invariant failed')
 save('frozen-r9-body.json',data);return data

def head_ops():
 ops=skin_ops()+feature_ops()+hair_ops()
 for o in ops:
  for key in ['from','to','origin']:o[key][1]=round(o[key][1]-.6,6)
  if o['parent'] in {'crown_layers','side_swept_fringe','cheek_and_temple_locks','rear_long_layers','side_long_layers'}:
   o['from'][0],o['to'][0]=-o['to'][0],-o['from'][0];o['origin'][0]*=-1
   o['rotation'][1]*=-1;o['rotation'][2]*=-1
 return ops

def build():
 if sha(BASE)!=BASE_SHA:raise SystemExit('R9 baseline changed')
 WORK.mkdir(parents=True,exist_ok=True)
 if (WORK/MODEL).exists() and sha(WORK/MODEL)!=BASE_SHA:raise SystemExit('Candidate already exists; use revise after archiving')
 if not (WORK/MODEL).exists():shutil.copy2(BASE,WORK/MODEL)
 d=read(BASE);pp=parents(d)
 ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] not in BODY]
 im=Image.new('RGBA',(64,64),(0,0,0,0));draw=ImageDraw.Draw(im)
 for i,c in enumerate(COLORS.values()):
  u=(i%16)*4;v=(i//16)*4;draw.rectangle((u,v,u+3,v+3),fill='#'+c)
 im.save(WORK/'original-r14-palette.png');b=io.BytesIO();im.save(b,format='PNG')
 ops.append({'op':'add_texture','name':'original-r14-palette','width':64,'height':64,'source':'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()})
 ops+=head_ops();ops.append({'op':'set_model_properties','name':'anon-reference-r14'})
 edit(MODEL,ops,'r14-root-build')
 save('authored-hair-routes.json',ROUTES);print(json.dumps(freeze(),ensure_ascii=False))

def revise():
 idx=1
 while (WORK/f'pass-{idx}').exists():idx+=1
 arc=WORK/f'pass-{idx}';arc.mkdir()
 for p in [WORK/MODEL,*WORK.glob('*.png'),WORK/'authored-hair-routes.json']:
  if p.exists():shutil.copy2(p,arc/p.name)
 d=read(WORK/MODEL);pp=parents(d)
 ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] not in BODY]
 im=Image.new('RGBA',(64,64),(0,0,0,0));draw=ImageDraw.Draw(im)
 for i,c in enumerate(COLORS.values()):
  u=(i%16)*4;v=(i//16)*4;draw.rectangle((u,v,u+3,v+3),fill='#'+c)
 im.save(WORK/'original-r14-palette.png');buf=io.BytesIO();im.save(buf,format='PNG')
 ops.append({'op':'update_texture','target':'original-r14-palette','width':64,'height':64,'source':'data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()})
 ops+=head_ops()
 edit(MODEL,ops,f'r14-revise-{idx}')
 save('authored-hair-routes.json',ROUTES);print(json.dumps(freeze(),ensure_ascii=False))

def frame(label,source):
 file=label+'-head-inspection.bbmodel';shutil.copy2(source,WORK/file);d=read(WORK/file);pp=parents(d)
 ops=[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'}]
 # Trim only long hair below the head in the inspection copy. Full model unchanged.
 ops += [{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] in {'rear_long_layers','side_long_layers'} and e.get('origin',[0,100,0])[1]<20.15]
 for j,(lo,hi) in enumerate((([-5.5,19.7,-4.8],[-5.48,19.72,-4.78]),([5.48,29.98,4.98],[5.5,30,5]))):
  ops.append({'op':'add_cube','name':'inspection_only_'+str(j),'from':lo,'to':hi,'parent':'head','texture':8,'box_uv':False,
   'faces':{f:{'texture':8,'uv':[60,60,61,61]} for f in ['north','south','east','west','up','down']}})
 info=h.mcp('bbmodel_info',{'file':file},label+'-frame-before')
 h.mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':ops},label+'-frame')
 return file

def preview():
 freeze();file=frame('r14',WORK/MODEL)
 for view in ['front','three-quarter','left','right','back']:
  h.render(file,view,'r14-head-'+view+'.png',900,'r14-head-'+view,'studio')
 for view in ['front','three-quarter']:
  h.render(MODEL,view,'r14-full-'+view+'.png',900,'r14-full-'+view,'studio')
 h.render(file,'front','r14-head-flat.png',900,'r14-head-flat','flat')
 h.render(MODEL,'front','r14-normal-256.png',256,'r14-normal','studio')

def finish():
 d=freeze();info=h.mcp('bbmodel_info',{'file':MODEL},'r14-final-info')
 v=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r14-final-static')
 d.update({'counts':info['counts'],'static':v['summary'],'self_test':False,
  'warning_categories':{g['id']:len(g['violations']) for g in v['gates']},'status':'candidate pending human likeness review',
  'author':'root personally; original cuboids and palette, no third-party asset copied'})
 save('facts.json',d)
 for view in ['left','right','back']:h.render(MODEL,view,'r14-full-'+view+'.png',900,'r14-full-'+view,'studio')
 old=frame('r13',OLD)
 for view in ['front','three-quarter']:
  h.render(old,view,'r13-head-'+view+'.png',900,'r13-comparison-'+view,'studio')
  im=Image.new('RGB',(1800,954),'#202329');draw=ImageDraw.Draw(im);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',25)
  for x,label,title in [(0,'r13','R13 原头部'),(900,'r14','R14 参考重做 / R9 身体保留')]:
   draw.text((x+22,12),title,font=font,fill='white');im.paste(Image.open(WORK/f'{label}-head-{view}.png').convert('RGB'),(x,54))
  im.save(WORK/f'r13-r14-{view}-comparison.png')
 print(json.dumps(d,ensure_ascii=False))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('stage',choices=['build','revise','preview','finish','freeze']);globals()[p.parse_args().stage]()
