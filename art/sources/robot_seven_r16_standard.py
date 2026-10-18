"""Seven original cuboid character studies at Anon R16's production scale.

No reference image pixels or third-party model data enter the generated assets.
Model geometry edits and renders go through the installed upstream Blockbench MCP.
"""
from pathlib import Path
import argparse, base64, copy, hashlib, importlib.util, io, json, math, shutil
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'art/robot-mcp-workspace/characters'
BASE=OUT/'anon-body-balance-r16/anon-body-balance-r16.bbmodel'
BASE_SHA='b64ef1cf9ca3c9202d501a1fe8a284b536e99e77790bd98f9159451a1e86a4ec'
sp=importlib.util.spec_from_file_location('seven_face',ROOT/'art/sources/robot_anon_yoruno_method_r15.py')
face=importlib.util.module_from_spec(sp);sp.loader.exec_module(face)
helper=face.old;h=helper.h
FACES=['north','south','east','west','up','down']
SPECS={
 'tomori':dict(name='高松灯',hair='8C818A',inside='7C737F',eye='B47D89',length=20.65,eye_h=1.06,eye_w=.98,face_w=1.00,blue='427690',brow=4,
  note='灰棕短波波头、错落短刘海、粉灰圆眼；蓝白棒球外套、橄榄绿短裤、米色鞋'),
 'soyo':dict(name='长崎爽世',hair='C5A18B',inside='B08D7B',eye='7399B7',length=13.8,eye_h=.94,eye_w=1.02,face_w=.98,blue='386C88',brow=2,
  note='浅棕中分长发和波浪发尾、柔和蓝眼；不对称蓝白肩部与金边外裙、白色长底裙'),
 'taki':dict(name='椎名立希',hair='564150',inside='43333F',eye='9F759E',length=12.5,eye_h=.82,eye_w=1.02,face_w=.96,blue='386887',brow=-3,
  note='深紫棕直长发、较齐刘海、收窄紫眼；白长袖/肩部、蓝胸衣、黑腰带和白裙边'),
 'rana':dict(name='要乐奈',hair='E0E1DA',inside='C6CBC7',eye='799FBC',length=20.55,eye_h=.94,eye_w=1.02,face_w=1.00,blue='3F7E98',brow=0,
  note='银白包头短发、正面左金右蓝异色瞳；白T恤、左侧蓝长外套、右侧格纹袜、高黑靴'),
 'sakiko':dict(name='丰川祥子',hair='A1B7D1',inside='8CA1BF',eye='B6A474',length=12.05,eye_h=.96,eye_w=.97,face_w=.97,blue='AAA6BC',brow=-1,
  note='蓝灰侧分长发、两侧编发与黑结、金灰眼；灰紫校服外套、白衬衫和深领带、绿格裙、白袜'),
 'uika':dict(name='三角初华',hair='EFE0B6',inside='E1C9B9',eye='A28DBA',length=20.45,eye_h=1.02,eye_w=.98,face_w=.98,blue='34333F',brow=1,
  note='奶金齐颈发、淡粉内层、紫眼；黑色上衣、白金领饰、白腰封双扣、黑长裙'),
 'umiri':dict(name='八幡海铃',hair='65666F',inside='535660',eye='78A9A8',length=19.55,eye_h=.80,eye_w=1.03,face_w=.96,blue='713C49',brow=-2,
  note='灰黑偏分及肩发、外翻发尾、平缓青眼；暗红披肩、黑收腰外衣、浅色门襟和袖口、长靴'),
}
KEY='';WORK=None;MODEL='';PAL={};SW={}

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(n,d):
 p=WORK/n;p.parent.mkdir(exist_ok=True,parents=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def blend(a,b,t):return ''.join(f'{round(int(a[i:i+2],16)*(1-t)+int(b[i:i+2],16)*t):02X}' for i in (0,2,4))

def configure(key):
 global KEY,WORK,MODEL,PAL,SW
 KEY=key;WORK=OUT/f'{key}-r16-standard';MODEL=f'{key}-r16-standard.bbmodel';s=SPECS[key]
 PAL=dict(skin='FAE7D8',hair=s['hair'],hair_inner=s['inside'],hair_shade=blend(s['hair'],'303040',.16),
  lash='40343C',white='FFF7EF',rim=blend(s['eye'],'303045',.42),iris_dark=blend(s['eye'],'404055',.28),
  iris=s['eye'],iris_light=blend(s['eye'],'FFFFFF',.30),iris_low=blend(s['eye'],'FFFFFF',.52),pupil='3B3B52',
  shine='FFFDFC',shine_soft='E4E8EF',mouth='BB8991',brow='3A3036',
  blue=s['blue'],blue_dark=blend(s['blue'],'172837',.30),cloth='F2F0E9',cloth_shade='D7D6CE',gold='D3BF78',
  green='78816D',green_dark='56664F',black='2E303A',sole='242630',trim='677280',pink='DABAC4',
  red='713743',cream='D9D4C1',navy='3F4C67',stocking='A79F9C',silver='C9C8C3')
 if key=='rana':
  for n in ['rim','iris_dark','iris','iris_light','iris_low','pupil','shine','shine_soft']:
   PAL['alt_'+n]=dict(rim='8C784D',iris_dark='AB935A',iris='CCB573',iris_light='DFCB92',iris_low='EEE0B2',pupil='6B674B',shine='FFFDFC',shine_soft='F6ECD0')[n]
 SW={k:[i%16*4+.5,i//16*4+.5,i%16*4+3.5,i//16*4+3.5] for i,k in enumerate(PAL)}
 h.WORK=WORK;helper.WORK=WORK;face.cube=box

def box(name,center,size,color,parent,rot=None):
 if KEY=='rana' and 'iris_left' in name and 'alt_'+color in PAL:color='alt_'+color
 return dict(op='add_cube',name=KEY+'_'+name,parent=parent,
  **{'from':[round(c-s/2,6) for c,s in zip(center,size)],'to':[round(c+s/2,6) for c,s in zip(center,size)]},
  origin=list(center),rotation=list(rot) if rot else [0,0,0],texture=0,box_uv=False,
  faces={f:dict(texture=0,uv=SW[color]) for f in FACES})

def seg(name,a,b,w,d,color,parent,normal=(0,0,-1),overlap=.12):
 return face.plank(name,a,b,w,d,parent,color,normal,overlap)

def route(name,points,widths,depth,parent,color='hair',normal=(0,0,-1)):
 return [seg(name+'_'+str(i),a,b,widths[i],depth,color,parent,normal,.13) for i,(a,b) in enumerate(zip(points,points[1:]))]

def flat(name,top,bottom,cx,w,parent='side_swept_fringe',z=-3.22,tilt=34,depth=.40):
 if KEY not in ['taki','rana','uika'] or parent!='side_swept_fringe':
  return face.flat_lock(name,top,bottom,cx,w,parent,z,tilt,depth)
 count=math.ceil((top-bottom)/.12);step=(top-bottom)/count;ops=[]
 for i in range(count):
  t=(i+.5)/count;x=(1-t)**2*cx[0]+2*t*(1-t)*cx[1]+t*t*cx[2]
  width=w*(1-.10*t) if t<.84 else max(.18,w*.916*(1-(t-.84)/.16)**.75)
  lo=round((x-width/2)/.06)*.06;hi=max(lo+.06,round((x+width/2)/.06)*.06)
  o=box(name+'_'+str(i),[(lo+hi)/2,top-(i+.5)*step,z],[hi-lo,step+.0001,depth],'hair',parent,[tilt,0,0])
  o['origin']=[0,24.3,z];ops.append(o)
 return ops

def make_atlas():
 im=Image.new('RGBA',(64,64));d=ImageDraw.Draw(im)
 for n,uv in SW.items():u,v=uv[:2];d.rectangle((int(u),int(v),int(u)+3,int(v)+3),fill='#'+PAL[n])
 # Original, hard-edged pattern areas for uniform plaid and netting.
 d.rectangle((0,24,15,39),fill='#'+PAL['green'])
 for a in (1,7,13):
  d.rectangle((a,24,a+1,39),fill='#'+PAL['green_dark']);d.rectangle((0,24+a,15,25+a),fill='#'+PAL['green_dark'])
 for a in (4,12):
  d.line((a,24,a,39),fill='#'+PAL['gold']);d.line((0,24+a,15,24+a),fill='#'+PAL['gold'])
 d.rectangle((16,24,31,39),fill='#'+PAL['skin'])
 for y in range(16):
  for x in range(16):
   if (x+y)%8==0 or (x-y)%8==0:d.point((x+16,y+24),fill='#'+PAL['black'])
 d.rectangle((32,24,47,39),fill='#'+PAL['black'])
 for y in (1,5,9,13):
  d.line((38,24+y,41,26+y),fill='#'+PAL['trim']);d.line((41,24+y,38,26+y),fill='#'+PAL['trim'])
 im.save(WORK/'original-atlas-64.png');save('palette.json',PAL)
 b=io.BytesIO();im.save(b,format='PNG');return 'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()

def head():
 s=SPECS[KEY];ops=face.face()+face.eyes()
 for o in ops:
  if o['parent'].startswith('eye_geometry'):
   cx=-1.4 if o['parent'].endswith('left') else 1.4;cy=22.695
   for k in ['from','to','origin']:
    o[k][0]=cx+(o[k][0]-cx)*s['eye_w'];o[k][1]=cy+(o[k][1]-cy)*s['eye_h']
  if o['parent']=='brow_geometry':
   sign=-1 if o['origin'][0]<0 else 1;o['rotation'][2]=sign*s['brow']
  if o['parent']=='smile_geometry':
   for k in ['from','to','origin']:o[k][0]*=.86
  if o['parent']!='crown_layers':
   for k in ['from','to','origin']:o[k][0]*=s['face_w']
 if KEY=='soyo':
  for i,(lo,hi,width) in enumerate([(24.4,25.2,5.35),(25.2,26.0,3.40),(26.0,27.4,1.80)]):
   o=box('forehead_under_part_'+str(i),[0,(lo+hi)/2,-2.86],[width,hi-lo+.0001,.42],'skin','face_skin_structure',[34,0,0])
   o['origin']=[0,24.3,-3.22];ops.append(o)
 ops+=hair()
 return ops

def hair():
 s=SPECS[KEY];ops=[];p='crown_layers'
 arc=[[-3.63,25.25,.1],[-3.30,26.25,.1],[-2.55,27.08,.1],[-1.35,27.60,.1],
      [0,27.76,.1],[1.35,27.60,.1],[2.55,27.08,.1],[3.30,26.25,.1],[3.63,25.25,.1]]
 for i,(a,b) in enumerate(zip(arc,arc[1:])):ops.append(seg('crown_arch_'+str(i),a,b,.30,3.1,'hair',p,overlap=.07))
 for i,(x,y,w) in enumerate([(-2.9,26.7,1.3),(-1.5,27.35,1.6),(0,27.55,1.6),(1.5,27.35,1.6),(2.9,26.7,1.3)]):
  for side in [1]:ops+=route(f'cap_turn_{i}_{side}',[[x,y,side*1.4],[x*1.02,y-1.62,side*3.05]],[w+.12],.4,p,normal=(0,0,side))
 if KEY=='soyo':
  bangs=[(27.8,24.25,[-.20,-.55,-2.65],1.65),(27.8,24.15,[.20,.55,2.70],1.65),
         (27.15,23.45,[-1.75,-2.55,-3.03],1.25),(27.15,23.40,[1.80,2.65,3.00],1.25)]
 elif KEY in ['taki','uika','rana']:
  b={'taki':[24.15,24.08,23.88,24.02,24.13,24.02,24.30],
     'uika':[24.18,23.90,23.82,23.96,23.84,24.03,24.28],
     'rana':[24.28,24.02,23.88,24.00,23.89,24.10,24.35]}[KEY]
  bangs=[(27.3+.7*(1-abs(i-3)/3),b[i],[(i-3)*.89,(i-3)*.95,(i-3)*.94+.12],1.12) for i in range(7)]
 elif KEY=='tomori':
  bangs=[(27.4,24.25,[-2.5,-2.85,-2.53],1.1),(27.95,23.72,[-1.6,-1.7,-1.35],1.23),
   (28.1,23.55,[-.5,-.70,-.1],1.24),(28.05,24.10,[.55,.65,.98],1.21),
   (27.85,24.33,[1.55,1.95,2.4],1.3),(27.15,23.45,[2.6,3.05,3.06],1.01)]
 elif KEY=='sakiko':
  bangs=[(27.4,24.32,[-2.6,-2.8,-2.38],1.15),(27.95,24.25,[-1.7,-1.7,-1.08],1.4),
   (28.15,24.08,[-.65,-.5,.70],1.40),(28.05,24.22,[.6,1.12,2.12],1.4),
   (27.65,24.40,[1.78,2.4,2.88],1.33),(27,23.4,[2.8,3.2,3.15],.95)]
 else:
  bangs=[(27.4,24.55,[-2.7,-2.75,-2.14],1.1),(27.98,24.52,[-1.8,-1.90,-1.12],1.30),
   (28.12,24.20,[-.8,-.8,.5],1.40),(28.05,23.85,[.3,.7,1.85],1.45),
   (27.65,23.65,[1.4,2.25,2.85],1.34),(27.0,23.28,[2.7,3.2,3.12],.96)]
 for i,(top,bot,c,w) in enumerate(bangs):ops+=flat('fringe_'+str(i),top,bot,c,w)
 long=s['length']<16
 for sign in [-1,1]:
  def pt(points):return [[sign*x,y,z] for x,y,z in points]
  cheekend=21.20 if KEY!='umiri' else 20.85
  ops+=flat('cheek_main_'+str(sign),25.10,cheekend,[sign*3.02,sign*3.10,sign*2.45],.79,'cheek_and_temple_locks',-2.925,2,.90)
  ops+=flat('cheek_outer_'+str(sign),25.10,21.00,[sign*3.50,sign*3.71,sign*3.04],.90,'cheek_and_temple_locks',-2.405,1,1.10)
  ops.append(box('side_cover_'+str(sign),[sign*3.35,24.02,.30],[.45,3.55,4.55],'hair',p))
  for k,z in enumerate([-1.25,-.15,1.05,2.15]):
   end=s['length']+[.28,-.16,.12,.38][k];w=[1.15,1.28,1.34,1.18][k]
   if long:
    points=pt([[3.46,25.2,z],[3.80,21.7,z+.20],[4.16,17.10,z+.22],[3.98,end,z-.04],[3.75,end-.52,z-.14]])
    if KEY=='soyo':
     points=pt([[3.46,25.2,z],[3.77,21.2,z+.2],[4.2,17.8,z+.30],[3.85,15.4,z+.07],[4.24,end,z-.05],[3.8,end-.55,z-.22]])
     ws=[w,w+.02,w*.95,w*.82,w*.42]
    else:ws=[w,w+.03,w*.92,w*.48]
   else:
    tip=4.25 if KEY=='umiri' else 3.28
    points=pt([[3.46,25.2,z],[3.83,23.10,z+.12],[3.72,end+.65,z+.02],[tip,end-.20,z-.15]])
    ws=[w,w*.94,w*.49]
   color='hair' if k<2 else 'hair_inner'
   pieces=route(f'side_{sign}_{k}',points,ws,.52,'side_long_layers',color,(sign,0,0))
   if KEY=='uika' and k>1:pieces[-1]['faces']={f:dict(texture=0,uv=SW['pink']) for f in FACES}
   ops+=pieces
  if KEY=='sakiko':
   # Layered, paired braid blocks only on the side, never on the continuous face.
   for j in range(5):
    ops.append(box(f'braid_{sign}_{j}',[sign*(3.78+(j%2)*.10),24.7-j*.48,-2.18],[.53,.68,.65],'hair_inner','hair_clips',[0,0,sign*(24 if j%2 else -24)]))
   for j,sg in enumerate([-1,1]):ops.append(box(f'ribbon_{sign}_{j}',[sign*4.05,22.1,-1.42+sg*.35],[.38,.78,.62],'black','hair_clips',[sg*25,0,0]))
 for j,(x,z,w) in enumerate([(-2.6,2.75,1.48),(-1.35,3.08,1.57),(0,3.17,1.59),(1.35,3.08,1.57),(2.6,2.75,1.48)]):
  end=s['length']+[.2,-.25,0,.35,-.10][j]
  if long:
   pts=[[x,25.9,z],[x*1.08,21.6,z+.23],[x*1.13,17.4,z+.33],[x*1.08,end,z+.08],[x,end-.5,z-.1]];ws=[w,w+.08,w*1.02,w*.52]
   if KEY=='soyo':pts[2][0]*=1.09;pts[3][0]*=.93
  else:
   pts=[[x,25.9,z],[x*1.08,23.45,z+.25],[x*1.05,end+.65,z+.12],[x*(1.15 if KEY=='umiri' else .94),end-.3,z-.22]];ws=[w,w+.06,w*.62]
  ops+=route('back_'+str(j),pts,ws,.58,'rear_long_layers','hair_inner',(0,0,1))
 return ops

def torso(color='blue',bottom=14.4,front=None):
 ops=[box('torso',[0,(bottom+20.4)/2,0],[5.0,20.4-bottom,2.92],color,'body')]
 for s in [-1,1]:
  ops.append(seg('torso_side_'+str(s),[s*2.68,20.18,0],[s*2.35,bottom+.1,0],.58,2.96,color,'body'))
  ops.append(seg('sloped_shoulder_'+str(s),[s*1.82,20.40,0],[s*3.62,19.98,0],.63,2.88,color,'body'))
 ops.append(box('collar',[0,20.4,0],[3.80,.62,3.12],color,'body'))
 if front:ops.append(box('front_inner',[0,17.35,-1.485],[2.25,5.45,.065],front,'body'))
 return ops

def arms(colors,long=False,gloves=False,sleeveless=False):
 ops=[]
 for side,s in [('left',-1),('right',1)]:
  p='arm_'+side;c=colors[0 if s<0 else 1];shoulder='skin' if sleeveless else c
  ops.append(box('shoulder_'+side,[s*3.88,19.66,0],[1.88,1.36,2.62] if not sleeveless else [1.65,1.36,1.95],shoulder,p,[0,0,-s*12]))
  ops.append(box('sleeve_top_'+side,[s*4.0,18.78,0],[1.76,1.12,2.32] if not sleeveless else [1.65,1.12,1.85],shoulder,p,[0,0,s*3]))
  armcolor=c if long else 'skin';width=1.81 if long else 1.67;depth=2.00 if long else 1.76
  ops.append(seg('upper_arm_'+side,[s*4.0,18.31,0],[s*4.08,15.65,-.03],width,depth,armcolor,p))
  ops.append(seg('forearm_'+side,[s*4.08,15.71,-.03],[s*4.20,13.28,-.10],width-.13,depth-.12,armcolor,p))
  if long:ops.append(box('cuff_'+side,[s*4.20,13.34,-.10],[1.77,.48,1.94],'blue' if KEY=='tomori' else c,p,[0,0,s*3]))
  ops.append(box('hand_'+side,[s*4.22,12.87,-.10],[1.40,.94,1.53],'black' if gloves else 'skin',p,[0,0,s*3]))
 return ops

def legs(boot=3.66,legcolor='skin',sock=None,shoes='black'):
 ops=[]
 for side,s in [('left',-1),('right',1)]:
  p='leg_'+side;x=s*1.53
  ops.append(box('thigh_'+side,[x,10.78,0],[2.22,5.28,2.24],legcolor,p))
  low=max(boot,1.55)
  if low<8.2:ops.append(box('shin_'+side,[x,(8.25+low)/2,-.02],[1.96,8.25-low+.08,2.0],legcolor,p))
  if sock:
   color,top=sock
   ops.append(box('sock_'+side,[x,(top+boot)/2,-.02],[1.99,top-boot+.04,2.03],color,p))
  ops.append(box('boot_shaft_'+side,[x,(boot+.5)/2,-.03],[2.09,boot-.5,2.18],shoes,p))
  if boot>3.0:
   ops[-1]['faces']['north']['uv']=[32,24,48,40]
  ops.append(box('shoe_toe_'+side,[x,.88,-1.15],[2.08,.88,1.08],shoes,p))
  ops.append(box('sole_'+side,[x,.23,-.31],[2.18,.46,2.88],'cloth_shade' if shoes=='cream' else 'sole',p))
  if KEY=='rana' and s>0:
   # Procedural net pattern wraps the visible leg; it is not a copied image.
   for o in ops:
    if o['name'].endswith(('thigh_'+side,'shin_'+side)):
     o['faces']={f:dict(texture=0,uv=[16,24,32,40]) for f in FACES}
   ops.append(box('net_top_band',[x,10.45,0],[2.25,.40,2.27],'black',p))
  if KEY=='tomori':
   for y in [3.00,3.18]:ops.append(box('sock_stripe_'+side+str(y),[x,y,-1.045],[1.99,.07,.035],'cloth_shade',p))
   for y in [.85,1.08,1.29]:ops.append(box('laces_'+side+str(y),[x,y,-1.18],[.76,.06,1.13],'cloth_shade',p))
 return ops

def skirt_ring(name,top,bottom,radius,color='blue',topradius=2.65,depth=2.60,n=16,trim=None,pattern=False,select=None,offset=0,topdepth=1.55):
 ops=[]
 for i in range(n):
  a=2*math.pi*i/n;ss,cc=math.sin(a),math.cos(a)
  if select and not select(ss,cc):continue
  by=bottom(ss,cc) if callable(bottom) else bottom
  col=color(ss,cc) if callable(color) else color
  p0=[ss*topradius,top,-cc*topdepth];p1=[ss*(radius+offset),by,-cc*(depth+offset)]
  w=(2*math.pi/n)*math.sqrt(((radius+offset)*cc)**2+((depth+offset)*ss)**2)*1.16
  op=seg(name+'_'+str(i),p0,p1,w,.32,col,'skirt',(ss,0,-cc))
  if pattern:op['faces']={f:dict(texture=0,uv=[0,24,16,40]) for f in FACES}
  ops.append(op)
  if trim:
   # Each hem strip follows the same tangent as its panel.
   op2=box(name+'_trim_'+str(i),p1,[w,.12,.38],trim,'skirt',op['rotation'])
   ops.append(op2)
 return ops

def belt(color='black',y=14.3,width=5.4,height=.52):
 return [box('waist_belt',[0,y,0],[width,height,3.08],color,'body'),
         box('belt_buckle',[0,y,-1.585],[.68,height+.10,.09],'silver','body'),
         box('buckle_inner',[0,y,-1.645],[.39,max(.16,height-.10),.045],color,'body')]

def outfit():
 ops=[]
 def add(*a,**k):ops.append(box(*a,**k))
 if KEY=='tomori':
  ops+=torso('blue',13.3,'cloth')+arms(['cloth','cloth'],True)+legs(1.65,sock=('green_dark',3.35),shoes='cream')
  # Open jacket fronts extend to the upper shorts; white inner shirt remains visible.
  for s in [-1,1]:
   add('jacket_front_'+str(s),[s*1.98,16.75,-1.53],[1.28,6.90,.16],'blue','body')
   add('jacket_edge_'+str(s),[s*1.30,16.75,-1.635],[.12,6.95,.06],'blue_dark','body')
   for y in [14.4,15.5,16.6,17.7,18.8]:add('button_'+str(s)+str(y),[s*1.52,y,-1.66],[.13,.13,.07],'silver','body')
   add('jacket_hem_'+str(s),[s*1.98,13.45,-1.65],[1.28,.20,.07],'cloth_shade','body')
   add('shorts_'+str(s),[s*1.53,12.15,0],[2.48,3.20,2.85],'green','skirt')
   add('shorts_hem_'+str(s),[s*1.53,10.59,0],[2.50,.18,2.89],'green_dark','skirt')
  ops+=belt('gold',14.2,5.15,.45)
 elif KEY=='sakiko':
  ops+=torso('blue',13.9,'cloth')+arms(['blue','blue'],True)+legs(1.55,sock=('cloth',5.80))
  for s in [-1,1]:
   ops.append(seg('lapel_'+str(s),[s*1.9,20.10,-1.53],[s*.80,17.5,-1.56],.54,.10,'cloth_shade','body'))
   add('pocket_'+str(s),[s*1.73,15.1,-1.52],[.92,.18,.09],'cloth_shade','body')
   add('shirt_collar_'+str(s),[s*.48,20.06,-1.60],[.79,.60,.10],'cloth','body',[0,0,s*20])
  add('tie',[0,18.95,-1.62],[.42,2.12,.12],'navy','body')
  for y in [16.8,15.6]:add('gold_button_'+str(y),[.61,y,-1.64],[.15,.15,.08],'gold','body')
  ops+=skirt_ring('plaid_skirt',14.0,9.0,4.15,'green',depth=2.42,pattern=True)
 elif KEY=='soyo':
  ops+=torso('blue',14.2)+arms(['blue','cloth'])+legs(2.0,sock=('stocking',3.55))
  add('white_right_panel',[1.15,17.55,-1.50],[2.28,5.80,.10],'cloth','body')
  add('white_chest',[0,20.18,-1.56],[2.1,.78,.10],'cloth','body')
  for s in [-1,1]:add('collar_tip_'+str(s),[s*.48,20.28,-1.61],[.78,.48,.10],'cloth','body',[0,0,s*22])
  for i in range(3):ops.append(seg('left_ruffle_'+str(i),[-.45-i*.9,19.9,-1.61],[-1.1-i*.95,18.9,-1.70],1.03,.18,'blue','body'))
  ops+=belt('blue_dark',14.35)
  ops+=skirt_ring('white_under',14,lambda x,z:7.2 if z>.2 else 5.1,4.9,'cloth',topradius=2.35,topdepth=1.35,depth=2.68)
  ops+=skirt_ring('blue_over',14.2,10.1,4.30,'blue',topradius=2.80,topdepth=1.73,depth=2.60,trim='gold',select=lambda x,z:x<.25,offset=.15)
  add('waist_flower',[1.30,14.05,-1.82],[.36,.36,.13],'gold','skirt',[0,0,45])
  ops.append(seg('flower_chain',[1.3,13.8,-1.85],[1.3,11.25,-2.22],.08,.08,'gold','skirt'))
 elif KEY=='taki':
  ops+=torso('cloth',14.1)+arms(['cloth','cloth'],True)+legs(3.35,legcolor='stocking')
  add('blue_bodice',[0,16.95,-1.54],[5.08,4.72,.20],'blue','body')
  for s in [-1,1]:add('bodice_top_'+str(s),[s*1.28,19.05,-1.68],[2.45,.55,.17],'blue','body',[0,0,s*5])
  ops+=belt('black',14.4)
  ops+=skirt_ring('white_under',14.0,7.9,4.25,'cloth',topradius=2.35,topdepth=1.33,depth=2.48,n=20)
  ops+=skirt_ring('blue_over',14.15,lambda x,z:8.65 if x<0 else 9.2,4.05,'blue',topradius=2.80,topdepth=1.74,depth=2.52,offset=.12)
  ops.append(seg('front_gold_edge',[.15,14.0,-1.75],[.30,8.55,-2.82],.12,.07,'gold','skirt'))
  ops.append(seg('side_tail',[-2.7,13.8,1.45],[-3.90,5.85,2.0],1.67,.34,'blue','skirt',(-1,0,0)))
 elif KEY=='rana':
  ops+=torso('cloth',12.7)+arms(['blue','cloth'])+legs(5.7)
  add('left_coat_front',[-1.55,17.5,-1.54],[2.53,5.92,.22],'blue','body')
  ops.append(seg('coat_lapel',[-2.20,20.3,-1.62],[-1.30,18.6,-1.69],.55,.16,'blue_dark','body'))
  for s in [-1,1]:add('shorts_'+str(s),[s*1.53,11.75,0],[2.39,3.06,2.75],'black','skirt')
  ops+=belt('gold',14.15,5.4,.36)
  ops+=skirt_ring('left_long_coat',14.0,lambda x,z:5.9 if z>.0 else 6.6,4.05,'blue',depth=2.53,select=lambda x,z:x<-.05,trim='blue_dark')
  for y in [13.1,11.65,10.2,8.8,7.3]:add('coat_button_'+str(y),[-1.16,y,-(1.7+(14-y)*.10)],[.14,.14,.09],'silver','skirt')
  for y in [10.9,8.5]:ops.append(seg('coat_zip_'+str(y),[-3.0,y+.25,-2.1],[-1.9,y-.20,-2.13],.14,.08,'black','skirt'))
  add('choker',[0,20.55,-1.67],[1.65,.25,.12],'black','body')
  add('choker_tag',[0,20.33,-1.74],[.30,.30,.09],'silver','body')
 elif KEY=='uika':
  ops+=torso('black',14.2)+arms(['skin','skin'],sleeveless=True)+legs(3.1)
  add('white_belt',[0,14.35,0],[5.45,1.25,3.17],'cloth','body')
  for y in [14.05,14.62]:
   add('belt_gold_'+str(y),[.15,y,-1.65],[.88,.35,.10],'gold','body')
   add('belt_hole_'+str(y),[.15,y,-1.72],[.57,.15,.05],'cloth','body')
  add('white_tie',[0,19.3,-1.54],[.62,1.92,.13],'cloth','body')
  for s in [-1,1]:ops.append(seg('gold_neck_'+str(s),[s*.75,19.0,-1.59],[0,18.45,-1.62],.15,.07,'gold','body'))
  ops+=skirt_ring('long_skirt',13.9,4.9,5.1,'black',depth=2.85)
  # Broad contrasting wrap panel uses flat original cloth tones rather than a copied gradient.
  ops.append(seg('diagonal_skirt_panel',[.70,13.7,-1.76],[3.52,5.1,-2.89],1.85,.17,'trim','skirt'))
  for a,b in [([-.1,13.45,-1.75],[-1.5,12.7,-1.9]),([-1.5,12.7,-1.9],[-2.9,12.38,-1.9])]:ops.append(seg('gold_chain_'+str(len(ops)),a,b,.07,.07,'gold','skirt'))
 elif KEY=='umiri':
  ops+=torso('black',13.7)+arms(['cream','cream'],True,gloves=True)+legs(8.25)
  add('light_placket',[0,18.20,-1.52],[1.22,4.75,.10],'cream','body')
  for y in [16.5,17.2,17.9,18.6,19.3]:add('placket_button_'+str(y),[0,y,-1.59],[.13,.13,.09],'black','body')
  for s in [-1,1]:
   ops.append(seg('vest_lapel_'+str(s),[s*1.3,20.2,-1.62],[s*.68,16.1,-1.66],.42,.10,'gold','body'))
   add('cape_shoulder_'+str(s),[s*3.7,19.4,0],[2.14,1.85,3.1],'red','body',[0,0,-s*15])
   ops.append(seg('cape_front_'+str(s),[s*1.9,20.40,-1.65],[s*3.50,17.9,-1.72],1.62,.22,'red','body'))
   ops.append(seg('cape_back_'+str(s),[s*1.4,20.15,1.66],[s*2.82,17.60,2.08],3.03,.27,'red','body',(0,0,1)))
   add('cream_trousers_'+str(s),[s*1.53,10.9,0],[2.32,5.1,2.35],'cream','skirt')
   add('boot_cuff_'+str(s),[s*1.53,8.1,-.03],[2.24,.48,2.30],'black','leg_'+('left' if s<0 else 'right'))
  ops+=skirt_ring('coat_tails',13.75,7.2,3.25,'black',depth=2.05,select=lambda x,z:z<.65)
  ops+=belt('black',13.9,5.25,.4)
 return ops

def groups():
 ops=[dict(op='add_group',name='root',origin=[0,0,0],parent=None),dict(op='add_group',name='body',origin=[0,13.7,0],parent='root'),
      dict(op='add_group',name='head',origin=[0,20.15,0],parent='body')]
 for side,s in [('left',-1),('right',1)]:
  ops.append(dict(op='add_group',name='arm_'+side,origin=[s*3.4,20.1,0],parent='body'))
  ops.append(dict(op='add_group',name='leg_'+side,origin=[s*1.53,13.4,0],parent='body'))
 ops.append(dict(op='add_group',name='skirt',origin=[0,13.7,0],parent='body'))
 for name in ['crown_layers','side_swept_fringe','cheek_and_temple_locks','rear_long_layers','side_long_layers','hair_clips',
              'face_skin_structure','eye_geometry_left','eye_geometry_right','brow_geometry','smile_geometry']:
  ops.append(dict(op='add_group',name=name,origin=[0,20.15,0],parent='head'))
 return ops

def model_ops():
 ops=groups()+head()+outfit()
 # Coordinates above are authored from a front view. Blockbench north view mirrors X.
 for o in ops:
  if o['op']=='add_cube':
   o['from'][0],o['to'][0]=-o['to'][0],-o['from'][0];o['origin'][0]*=-1;o['rotation'][1]*=-1;o['rotation'][2]*=-1
  elif o['op']=='add_group':o['origin'][0]*=-1
 return ops

def build(revise=False):
 if sha(BASE)!=BASE_SHA:raise SystemExit('Anon R16 changed')
 WORK.mkdir(parents=True,exist_ok=True)
 old=OUT/f'{KEY}-r1'/f'{KEY}-tutorial-lowpoly-r1.bbmodel'
 if not revise:
  if (WORK/MODEL).exists():raise SystemExit('Candidate exists; use revise')
  save('baseline.json',{'anon_r16':BASE_SHA,'original_r1':sha(old)})
  (WORK/'plan-before-modeling.md').write_text(f"# {SPECS[KEY]['name']}：R16 标准重做\n\n{SPECS[KEY]['note']}\n\n依据 ../../../robot-block-design/prompt-seven-r16-standard.md；主智能体亲自建模。静态 Generic 候选，游戏/动画未验证。\n",encoding='utf8')
  h.mcp('bbmodel_create',{'file':MODEL,'format':'free','name':KEY+'-r16-standard','box_uv':False,'resolution':{'width':64,'height':64},'overwrite':False},'create')
  ops=[dict(op='add_texture',name='original-atlas-64',width=64,height=64,source=make_atlas())]+model_ops()
  helper.edit(MODEL,ops,'build')
 else:
  i=1
  while (WORK/f'pass-{i}').exists():i+=1
  arc=WORK/f'pass-{i}';arc.mkdir()
  for p in [WORK/MODEL,*WORK.glob('*.png')]:shutil.copy2(p,arc/p.name)
  ops=[dict(op='remove_node',target='root'),dict(op='update_texture',target='original-atlas-64',width=64,height=64,source=make_atlas())]+model_ops()
  helper.edit(MODEL,ops,'revise-'+str(i))
 save('model-operations.json',ops)
 audit()

def audit():
 d=json.loads((WORK/MODEL).read_text(encoding='utf8'));old=OUT/f'{KEY}-r1'/f'{KEY}-tutorial-lowpoly-r1.bbmodel'
 baseline=json.loads((WORK/'baseline.json').read_text(encoding='utf8'))
 valid=all(e.get('type','cube')=='cube' and all(t>f for f,t in zip(e['from'],e['to'])) for e in d['elements'])
 if not valid:raise SystemExit('Non-positive or non-cuboid geometry')
 if sha(BASE)!=baseline['anon_r16'] or sha(old)!=baseline['original_r1']:raise SystemExit('Original model modified')
 pp=helper.parents(d)
 f={'character':KEY,'name':SPECS[KEY]['name'],'model_sha256':sha(WORK/MODEL),'original_r1_unchanged':True,'anon_r16_unchanged':True,
    'positive_cuboids_only':valid,'mouth_count':sum(pp[e['uuid']]=='smile_geometry' for e in d['elements']),
    'count':len(d['elements']),'textures':len(d['textures']),'animations':len(d.get('animations',[])),
    'third_party_assets_copied':False,'user_acceptance':'pending','game_validation':'not performed','self_test':False}
 if f['mouth_count']!=1:raise SystemExit('Mouth must remain one rectangle')
 save('audit.json',f);return f

def preview():
 audit()
 for v in ['front','three-quarter']:h.render(MODEL,v,'full-'+v+'.png',800,'preview-'+v,'studio')

def head_frame():
 f='head-inspection.bbmodel';shutil.copy2(WORK/MODEL,WORK/f);d=json.loads((WORK/f).read_text(encoding='utf8'));pp=helper.parents(d)
 ops=[dict(op='update_node',target='head',parent=None),dict(op='remove_node',target='root')]
 ops += [dict(op='remove_node',target=e['uuid']) for e in d['elements'] if pp[e['uuid']] in {'rear_long_layers','side_long_layers'} and e['origin'][1]<20.15]
 for i,(a,b) in enumerate([([-5.5,19.4,-4.8],[-5.48,19.42,-4.78]),([5.48,29.98,4.98],[5.5,30,5])]):
  ops.append(dict(op='add_cube',name='inspection_only_'+str(i),parent='head',**{'from':a,'to':b},texture=0,box_uv=False,faces={x:dict(texture=0,uv=[60,60,61,61]) for x in FACES}))
 helper.edit(f,ops,'head-frame');return f

def finish():
 f=audit();inf=h.mcp('bbmodel_info',{'file':MODEL},'final-info')
 v=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'final-static')
 f.update(counts=inf['counts'],static=v['summary'],warning_categories={g['id']:len(g['violations']) for g in v['gates']})
 save('facts.json',f)
 for view in ['left','right','back']:h.render(MODEL,view,'full-'+view+'.png',800,'final-'+view,'studio')
 h.render(MODEL,'front','normal-256.png',256,'final-normal','studio')
 frame=head_frame();h.render(frame,'front','head-front.png',800,'final-head','studio')
 font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
 im=Image.new('RGB',(1600,860),'#202329');dr=ImageDraw.Draw(im)
 for x,p,title in [(0,OUT/f'{KEY}-r1'/f'{KEY}-front-512.png','旧 R1'),(800,WORK/'full-front.png','R16 标准重做')]:
  im.paste(Image.open(p).convert('RGB').resize((800,800),Image.Resampling.NEAREST),(x,60));dr.text((x+20,16),SPECS[KEY]['name']+' / '+title,font=font,fill='white')
 im.save(WORK/'before-after.png')
 im=Image.new('RGB',(1800,660),'#202329');dr=ImageDraw.Draw(im)
 for i,(view,title) in enumerate([('front','正面'),('left','侧面'),('back','背面')]):
  im.paste(Image.open(WORK/f'full-{view}.png').convert('RGB').resize((600,600)),(600*i,60));dr.text((600*i+20,16),title,font=font,fill='white')
 im.save(WORK/'three-views.png')
 print(json.dumps(f,ensure_ascii=False),flush=True)

def launch():h.mcp('blockbench_launch',{'file':MODEL,'wait_for_mcp_ms':0},'desktop-launch')

def overview():
 folder=OUT/'seven-r16-standard';folder.mkdir(exist_ok=True)
 font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
 provenance={}
 for name,view in [('eight-overview','full-front.png'),('eight-three-quarter','full-three-quarter.png'),('eight-heads','head-front.png'),('eight-normal','normal-256.png')]:
  rows=[(SPECS[k]['name'],OUT/f'{k}-r16-standard'/view) for k in SPECS]
  anon_view={'eight-overview':'r16-full-front.png','eight-three-quarter':'r16-full-three-quarter.png','eight-normal':'r16-normal-256.png'}
  if name!='eight-heads':rows.insert(0,('千早爱音 R16',BASE.parent/anon_view[name]))
  else:rows.insert(0,('千早爱音 R16 保留头部',BASE.parent.parent/'anon-yoruno-method-r15/r15-head-front.png'))
  cell=256 if name=='eight-normal' else 500
  im=Image.new('RGB',(cell*4,(cell+55)*2),'#202329');dr=ImageDraw.Draw(im)
  for i,(title,p) in enumerate(rows):
   x=i%4*cell;y=i//4*(cell+55);im.paste(Image.open(p).convert('RGB').resize((cell,cell)),(x,y+55));dr.text((x+12,y+15),title,font=font,fill='white')
  im.save(folder/(name+'.png'))
  provenance[name]={'operation':'actual render images arranged and uniformly resized; labels outside image areas',
   'cell':cell,'sources':[{'label':title,'file':p.relative_to(ROOT).as_posix(),'sha256':sha(p)} for title,p in rows]}
 (folder/'overview-provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+'\n',encoding='utf8')

def document():
 folder=OUT/'seven-r16-standard';folder.mkdir(exist_ok=True)
 review={
  'tomori':'不齐的短刘海、圆一些的粉灰眼；棒球外套白袖、开襟内衫、绿短裤与米色鞋。后脑完整，发梢沿颈侧分层收口。',
  'soyo':'中央先聚拢后分开的棕色长刘海、蓝眼、侧发波浪；蓝白不对称上衣与金边外裙。修掉额前盖片、上额露壳及内裙穿出腰口的问题。',
  'taki':'深紫直长发、较齐且缩短尖端的刘海、收窄紫眼；蓝胸衣、白长袖、黑腰带、白裙边与单侧裙尾。',
  'rana':'银白短发、正面左金右蓝异色瞳；左侧蓝长外套、白T、黑短裤、另一侧网袜与高靴。修正裙片和裙边旋转共用导致的翘片。',
  'sakiko':'蓝灰侧分长发、可见侧编发与黑结、金灰眼；保留用户指定灰紫校服、领带、绿格裙、白袜和黑鞋。没有换成网页舞台服。',
  'uika':'奶金短发、淡粉后侧发梢、紫眼；黑色无袖上衣、白领饰金边、双扣白腰封和长裙。粉色已从整片侧后发收回到发梢。',
  'umiri':'灰黑侧分及肩发、外翻发尾、较平缓青眼；暗红披肩、黑外衣、浅色门襟/袖子/裤装与长靴。补出旧稿省略的服装分层。'}
 facts=[];table=['| 角色 | 工程与逐人说明 | 方块 | 静态警告 |','| --- | --- | ---: | ---: |']
 for key in SPECS:
  configure(key);a=audit();f=json.loads((WORK/'facts.json').read_text(encoding='utf8'))
  if f['model_sha256']!=a['model_sha256']:raise SystemExit('Stale static result: '+key)
  for required in ['full-front.png','full-three-quarter.png','full-left.png','full-right.png','full-back.png','normal-256.png','head-front.png','three-views.png','before-after.png','desktop-full.png']:
   if not (WORK/required).is_file():raise SystemExit('Missing delivery image: '+key+'/'+required)
  d=json.loads((WORK/MODEL).read_text(encoding='utf8'))
  if not all(math.isfinite(x) and 0<=x<=64 for e in d['elements'] for face_data in e['faces'].values() for x in face_data['uv']):raise SystemExit('Atlas UV out of range')
  f.update(desktop_opened_and_observed=True,visual_views_observed=['front','three-quarter','left','right','back','head-front','normal-256'],
           atlas_uv_in_bounds=True,model_file=MODEL,desktop_evidence='desktop-full.png',source='art/sources/robot_seven_r16_standard.py')
  save('delivery.json',f);facts.append(f)
  c=f['warning_categories'];model=MODEL
  txt=f'''# {SPECS[key]['name']}：按爱音 R16 标准重做

2026-10-17 主智能体亲自制作、看实际模型图并在本地 Blockbench 打开。新静态外观候选，等待用户审美验收。

{review[key]}

- [完整可编辑工程]({model}) / [原创 64×64 atlas](original-atlas-64.png)
- [正面](full-front.png) / [前侧](full-three-quarter.png) / [左侧](full-left.png) / [右侧](full-right.png) / [背面](full-back.png)
- [头部近景](head-front.png) / [256px 常规尺寸](normal-256.png) / [新旧正面对照](before-after.png)
- [真实桌面完整工程截图](desktop-full.png) / [静态记录](facts.json) / [资源核对](delivery.json)

![正侧背三视图](three-views.png)

{f['count']} 个正厚 cube、19 组、1 张原创贴图、0 mesh、0 动画；一条矩形嘴。静态检查 self_test=false：0 错误、{f['static']['warnings']} 警告，其中浮离 {c['floating']}、相交 {c['interpenetration']}、非镜像 {c['mirror']}。UV 均位于 64×64 atlas 内。警告没有隐藏或批量豁免。

原 R1 和爱音 R16 哈希未变，见 baseline.json。当前 SHA256：`{f['model_sha256']}`。

与爱音 R16 统一的是制作方法、脸/身尺度、薄像素五官和有厚度方块发束，不是复制相同衣服。侧背冠顶与主发束仍保留明显方块折线；后续可继续细调。眉眼神态相似程度仍由用户验收，未做相似度打分。

这是 Generic/free 静态美术文件；可以在桌面 Blockbench 编辑，不等于已经转换、加载到 Minecraft。没有验证 Forge 导出、实体渲染、动画、动态穿插或性能。没有运行测试、自检测试、Java 构建、游戏或安装。

来源依据：[用户提供的角色设定页](https://www.jjdao.com/news/20240562783.html)与本聊天演出服/祥子校服参考。图片只供观察，模型/UV/atlas 全部原创，不复制第三方资产。

源码：[统一制作脚本](../../../sources/robot_seven_r16_standard.py)；[制作前标准及逐轮修订](../../../robot-block-design/prompt-seven-r16-standard.md)。中间候选在 pass-* 中，仅为历史对照；当前文件以本页链接为准。
'''
  (WORK/'README.md').write_text(txt,encoding='utf8')
  table.append(f"| {SPECS[key]['name']} | [{key}](../{key}-r16-standard/README.md) | {f['count']} | {f['static']['warnings']} |")
 total=sum(f['count'] for f in facts);warnings=sum(f['static']['warnings'] for f in facts)
 (folder/'delivery-manifest.json').write_text(json.dumps({'characters':facts,'total_cubes':total,'total_warnings':warnings,'all_error_count':0,'anon_r16_sha256_unchanged':sha(BASE),'human_acceptance':'pending','game_validation':'not performed'},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
 text='''# 七人 R16 标准重做交付

用户认可爱音身体调整后，要求其余七人按该标准重做，并指定在本地 Blockbench 导入。2026-10-17 主智能体亲自完成七个新静态候选，先写标准、生成实际模型、逐轮看图修正，七人完整工程均已在桌面版打开。爱音 R16 与原七人 R1 原件未覆盖。

[八人正面总览](eight-overview.png) · [八人前侧总览](eight-three-quarter.png) · [八人头部近景](eight-heads.png) · [256px 原尺寸总览](eight-normal.png) · [桌面七人标签](desktop-seven-tabs.png)

![实际模型总览](eight-overview.png)

## 工程

'''+ '\n'.join(table)+f'''

爱音参照：[R16 原件及交接](../anon-body-balance-r16/README.md)。七人合计 {total} cube、133 组、7 张原创 atlas、0 mesh、0 动画。所有新模型均为正厚长方体，单矩形嘴，原件哈希核对一致。

## 检查范围与剩余问题

亲自看了七人的实际正面、前侧、侧面、背面、头部近景、256px 小图，并逐一打开桌面工程。不是原图美化后冒充模型；总览只统一缩放排列真实渲染，来源和哈希见 [overview-provenance.json](overview-provenance.json)。头部检查副本裁去了下方长发和身体，仅用于放大五官，完整长发以全身工程/三视图为准。

七人静态总计 0 错误、{warnings} 警告。没有运行 self_test 或项目测试。贴面眼睛/虹膜的浮离、冠顶与后发/裙层的重叠、同向眼睛高光及非对称服饰的镜像提醒均保留。抽看立希记录，确有冠顶/后发相交 0.78 单位，不能把全部警告称为误报。Generic 的 block_limits 检查未启用；0 错误不能证明 Minecraft 格式兼容或动态无穿插。

仍可改善：冠顶和侧脑的方块折线、宽后发主束、部分衣服装饰的简化、角色特有表情。当前只交付新外观候选，没有 99 分/相似度评分，也未替用户认可。这轮未改 Java、构建、安装、存档和游戏，不恢复旧心跳。

## 复现与参考

制作源：`art/sources/robot_seven_r16_standard.py`；标准：`art/robot-block-design/prompt-seven-r16-standard.md`。使用本机已存在的原作者 Blockbench MCP headless stdio 编辑并渲染，随后本地桌面实开；不声称桌面 HTTP MCP 已连接。

`preview <角色ID>` 渲染正面/前侧；`finish <角色ID>` 生成其余视图与静态记录；`overview` 排版总览；`audit` 只核对资源；`launch <角色ID>` 本地打开。`revise` 会先归档候选后重建，不用于覆盖旧 R1 或爱音原件。用户手工修改新模型后，先另存再重建。

参考：[用户原角色设定页](https://www.jjdao.com/news/20240562783.html)和聊天附件；读取的网页图仅在本机临时目录用于观察，没有作为贴图或工程资产移植。每个人的发型、五官颜色和服装修改说明在上表链接中。
'''
 (folder/'README.md').write_text(text,encoding='utf8')
 print(json.dumps({'characters':len(facts),'cubes':total,'errors':0,'warnings':warnings,'readme':str(folder/'README.md')},ensure_ascii=True))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('stage',choices=['build','revise','preview','finish','launch','audit','overview','document']);p.add_argument('characters',nargs='*');a=p.parse_args()
 if a.stage in ['overview','document']:globals()[a.stage]()
 else:
  for key in (a.characters or list(SPECS)):
   configure(key)
   if a.stage in ['build','revise']:build(a.stage=='revise')
   else:globals()[a.stage]()
