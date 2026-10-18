"""Original body-only revision; the complete R15 head/hair is immutable.

Edits and renders use the installed upstream Blockbench MCP with revisions.
This is a Generic art candidate, not a Minecraft-ready entity or animation.
"""
from pathlib import Path
import argparse, copy, hashlib, importlib.util, json, math, shutil
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'art/robot-mcp-workspace/characters/anon-body-balance-r16'
BASE = WORK.parent / 'anon-yoruno-method-r15/anon-yoruno-method-r15.bbmodel'
MODEL = 'anon-body-balance-r16.bbmodel'
BASE_SHA = '3f6f4b5e0efd5800f24556b53058f2e7274bf52e775bbd40a6000cced144b261'
BODY = {'body','arm_left','arm_right','leg_left','leg_right','skirt'}
spec = importlib.util.spec_from_file_location('r16_helper', ROOT/'art/sources/robot_anon_reference_r14.py')
helper = importlib.util.module_from_spec(spec); spec.loader.exec_module(helper)
h = helper.h; h.WORK = WORK; helper.WORK = WORK
UV = {'skin':[.5,16.5,3.5,19.5], 'blue':[16.5,16.5,19.5,19.5],
      'darkblue':[20.5,16.5,23.5,19.5], 'white':[24.5,16.5,27.5,19.5],
      'lowwhite':[28.5,16.5,31.5,19.5], 'yellow':[32.5,16.5,35.5,19.5],
      'boot':[36.5,16.5,39.5,19.5], 'sole':[40.5,16.5,43.5,19.5]}

def read(p): return json.loads(p.read_text(encoding='utf8'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def save(n,d): (WORK/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def box(name,center,size,color,parent,rotation=None):
    return {'op':'add_cube','name':'r16_'+name,'parent':parent,
            'from':[round(c-s/2,6) for c,s in zip(center,size)],
            'to':[round(c+s/2,6) for c,s in zip(center,size)],
            'origin':center,'rotation':rotation or [0,0,0], 'texture':0,'box_uv':False,
            'faces':{f:{'texture':0,'uv':UV[color]} for f in ['north','south','east','west','up','down']}}

def segment(name,a,b,width,depth,color,parent,normal=(0,0,-1),overlap=.12):
    op=h.segment('r16_'+name,a,b,width,depth,list(normal),parent,'hair',overlap)
    op.update(texture=0,faces={f:{'texture':0,'uv':UV[color]} for f in ['north','south','east','west','up','down']})
    return op

def body_ops(doc):
    ops=[]
    def add(*a,**k): ops.append(box(*a,**k))
    add('shirt_core',[0,17.85,0],[5.0,5.10,2.92],'blue','body')
    ops[-1]['faces']['north']=copy.deepcopy(next(e for e in doc['elements'] if e['name']=='r9_shirt_core')['faces']['north'])
    for side,s in [('left',1),('right',-1)]:
        ops.append(segment('shirt_taper_'+side,[s*2.68,20.18,0],[s*2.35,15.38,0],.58,2.96,'blue','body'))
        ops.append(segment('shoulder_slope_'+side,[s*1.82,20.40,0],[s*3.62,19.98,0],.63,2.88,'blue','body'))
    add('shirt_hem',[0,15.38,0],[5.28,.18,3.00],'darkblue','body')
    add('midriff',[0,14.82,0],[4.82,1.17,2.74],'skin','body')
    add('collar_yoke',[0,20.40,0],[3.80,.62,3.12],'blue','body')
    for side,s in [('left',-1),('right',1)]:
        add('bow_'+side,[s*.47,19.78,-1.55],[.74,.40,.10],'yellow','body',[0,0,-s*8])
    add('bow_knot',[0,19.78,-1.565],[.28,.36,.12],'yellow','body')
    add('bow_tail',[0,18.87,-1.51],[.40,1.48,.08],'yellow','body')
    for side,s in [('left',1),('right',-1)]:
        p='arm_'+side
        add('sleeve_shoulder_'+side,[s*3.88,19.66,0],[1.88,1.36,2.62],'blue',p,[0,0,-s*12])
        add('sleeve_cuff_'+side,[s*4.00,18.78,0],[1.76,1.12,2.32],'blue',p,[0,0,s*3])
        add('sleeve_edge_'+side,[s*4.03,18.25,0],[1.79,.14,2.36],'darkblue',p,[0,0,s*3])
        ops.append(segment('upper_arm_'+side,[s*4.00,18.31,0],[s*4.08,15.65,-.03],1.67,1.76,'skin',p))
        ops.append(segment('forearm_'+side,[s*4.08,15.71,-.03],[s*4.20,13.28,-.10],1.49,1.58,'skin',p))
        add('wrist_hand_'+side,[s*4.22,12.87,-.10],[1.40,.94,1.53],'skin',p,[0,0,s*3])
        p='leg_'+side; x=s*1.53
        add('thigh_'+side,[x,10.78,0],[2.22,5.28,2.24],'skin',p)
        add('shin_'+side,[x,5.91,-.02],[1.96,4.66,2.00],'skin',p)
        add('boot_upper_'+side,[x,2.86,-.03],[2.09,1.60,2.18],'boot',p)
        ops[-1]['faces']['north']=copy.deepcopy(next(e for e in doc['elements'] if e['name']=='r9_boot_upper_'+side)['faces']['north'])
        add('boot_ankle_'+side,[x,1.29,-.03],[1.98,1.70,2.10],'boot',p)
        add('boot_toe_'+side,[x,.88,-1.15],[2.08,.88,1.08],'boot',p)
        add('boot_sole_'+side,[x,.23,-.31],[2.18,.46,2.88],'sole',p)
    add('skirt_waist',[0,13.68,0],[5.50,1.20,3.14],'lowwhite','skirt')
    ops.append(segment('skirt_front_center',[0,13.45,-1.54],[0,8.85,-2.46],2.58,.40,'white','skirt'))
    for side,s in [('left',1),('right',-1)]:
        ops.append(segment('skirt_front_mid_'+side,[s*1.16,13.45,-1.53],[s*2.35,8.72,-2.48],1.92,.40,'white','skirt'))
        ops.append(segment('skirt_front_outer_'+side,[s*2.18,13.45,-1.37],[s*4.42,8.40,-2.05],2.06,.40,'white','skirt'))
        for j,(topz,endz,endy,width) in enumerate([(-.72,-1.29,7.45,2.06),(.42,.83,6.55,2.15),(1.30,2.48,5.95,2.06)]):
            ops.append(segment(f'skirt_side_{side}_{j}',[s*2.50,13.45,topz],[s*(5.00+.15*j),endy,endz],width,.40,'lowwhite' if j==1 else 'white','skirt',(s,0,0)))
        ops.append(segment('skirt_back_mid_'+side,[s*1.20,13.45,1.50],[s*2.59,5.25,3.08],2.25,.40,'white','skirt',(0,0,1)))
        ops.append(segment('skirt_back_outer_'+side,[s*2.18,13.45,1.37],[s*4.55,5.55,2.87],2.28,.40,'lowwhite','skirt',(0,0,1)))
    ops.append(segment('skirt_back_center',[0,13.45,1.54],[0,4.95,3.31],2.66,.42,'white','skirt',(0,0,1)))
    return ops

def apply(revise=False):
    if sha(BASE)!=BASE_SHA: raise SystemExit('R15 baseline changed')
    WORK.mkdir(parents=True,exist_ok=True)
    if not revise:
        if (WORK/MODEL).exists(): raise SystemExit('Candidate exists; use revise')
        shutil.copy2(BASE,WORK/MODEL)
    else:
        i=1
        while (WORK/f'pass-{i}').exists(): i+=1
        arc=WORK/f'pass-{i}';arc.mkdir()
        for p in [WORK/MODEL,*WORK.glob('*.png')]: shutil.copy2(p,arc/p.name)
    d=read(WORK/MODEL);pp=helper.parents(d)
    ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] in BODY]
    ops+=body_ops(read(BASE))
    pivots={'body':[0,13.7,0],'skirt':[0,13.7,0], 'arm_left':[3.40,20.10,0], 'arm_right':[-3.40,20.10,0],
            'leg_left':[1.53,13.4,0],'leg_right':[-1.53,13.4,0]}
    ops += [{'op':'update_node','target':name,'origin':pos} for name,pos in pivots.items()]
    ops.append({'op':'set_model_properties','name':'anon-body-balance-r16'})
    save('body-edit-operations.json',ops)
    helper.edit(MODEL,ops,'r16-body-'+('revise-'+str(i) if revise else 'build'))
    freeze()

def freeze():
    a,b=read(BASE),read(WORK/MODEL);pa,pb=helper.parents(a),helper.parents(b)
    head_a={e['uuid']:e for e in a['elements'] if pa[e['uuid']] not in BODY}
    head_b={e['uuid']:e for e in b['elements'] if pb[e['uuid']] not in BODY}
    ga={g['uuid']:g for g in a['groups'] if g['name'] not in BODY}
    gb={g['uuid']:g for g in b['groups'] if g['name'] not in BODY}
    def group_tree(d):
        def walk(ns): return [(n['uuid'],walk(n.get('children',[]))) for n in ns if isinstance(n,dict)]
        return walk(d['outliner'])
    facts={'base_sha256':sha(BASE),'candidate_sha256':sha(WORK/MODEL),
        'frozen_head_and_hair_count':len(head_a),'head_and_hair_identical':head_a==head_b,
        'head_groups_identical':ga==gb,'group_hierarchy_identical':group_tree(a)==group_tree(b),
        'textures_identical':a['textures']==b['textures'],'resolution_identical':a['resolution']==b['resolution'],
        'body_cube_count':sum(pb[e['uuid']] in BODY for e in b['elements']),
        'positive_cubes_only':all(e.get('type','cube')=='cube' and all(t>f for f,t in zip(e['from'],e['to'])) for e in b['elements']),
        'status':'body proportion candidate; user review pending','game_validation':'not performed','self_test':False}
    for key in ['head_and_hair_identical','head_groups_identical','group_hierarchy_identical','textures_identical','resolution_identical','positive_cubes_only']:
        if not facts[key]:raise SystemExit('Invariant failed: '+key)
    if facts['base_sha256']!=BASE_SHA:raise SystemExit('Baseline modified')
    save('frozen-head-and-hair.json',facts);return facts

def preview():
    freeze()
    for view in ['front','three-quarter','left']:
        h.render(MODEL,view,'r16-full-'+view+'.png',900,'r16-full-'+view,'studio')

def finish():
    facts=freeze()
    info=h.mcp('bbmodel_info',{'file':MODEL},'r16-final-info')
    v=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r16-final-static')
    facts.update(counts=info['counts'],static=v['summary'],warning_categories={g['id']:len(g['violations']) for g in v['gates']})
    save('facts.json',facts)
    for view in ['right','back']:h.render(MODEL,view,'r16-full-'+view+'.png',900,'r16-full-'+view,'studio')
    h.render(MODEL,'front','r16-normal-256.png',256,'r16-normal','studio')
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',25)
    for view in ['front','three-quarter','left']:
        im=Image.new('RGB',(1800,960),'#202329');dr=ImageDraw.Draw(im)
        for x,src,title in [(0,BASE.parent/f'r15-full-{view}.png','调整前 R15'),(900,WORK/f'r16-full-{view}.png','身体比例调整 R16（头部保持）')]:
            im.paste(Image.open(src).convert('RGB'),(x,60));dr.text((x+22,14),title,font=font,fill='white')
        im.save(WORK/f'r15-r16-{view}-comparison.png')
    im=Image.new('RGB',(1800,660),'#202329');dr=ImageDraw.Draw(im)
    for i,(view,title) in enumerate([('front','正面'),('left','侧面'),('back','背面')]):
        im.paste(Image.open(WORK/f'r16-full-{view}.png').convert('RGB').resize((600,600)),(i*600,60))
        dr.text((i*600+22,14),title,font=font,fill='white')
    im.save(WORK/'r16-full-three-views.png')
    print(json.dumps(facts,ensure_ascii=False))

def launch(): h.mcp('blockbench_launch',{'file':MODEL,'wait_for_mcp_ms':0},'r16-desktop-launch')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['build','revise','preview','finish','launch','freeze']);a=p.parse_args()
    if a.stage in {'build','revise'}:apply(a.stage=='revise')
    else:globals()[a.stage]()
