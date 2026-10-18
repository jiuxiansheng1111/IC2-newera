"""Root-authored Anon face study. R9 geometry frozen except eyes, brows, mouth.

All model writes and rendering use the existing upstream Blockbench MCP.
No reference asset copying, quota polling, tests, game or installation work.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import importlib.util
import io
import json
import math
import shutil
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT/'art/robot-mcp-workspace/characters/anon-r9-likeness-r13'
BASE = WORK.parent/'anon-proportion-face-r9/anon-proportion-face-r9.bbmodel'
MODEL = 'anon-r9-likeness-r13.bbmodel'
BASE_SHA = 'e1b95c692c2892d69df1314def89d5132f732cb50b3e6d71cfbde70bc655b80b'
FEATURES = {'eye_geometry_left', 'eye_geometry_right', 'brow_geometry', 'smile_geometry'}
PALETTE = {'lash':'38343F','white':'FFF8F3','rim':'57748A','upper':'597D94',
           'iris':'91B4C4','lower':'C4DCE1','pupil':'36556D','shine':'FFFEFB',
           'brow':'29252B','mouth':'B27F8A','soft':'A3BDC7'}
spec = importlib.util.spec_from_file_location('own_mcp_helper', ROOT/'art/sources/robot_layered_head.py')
h = importlib.util.module_from_spec(spec); spec.loader.exec_module(h); h.WORK=WORK

def save(name, data):
    p=WORK/name; p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf8')

def read(p): return json.loads(p.read_text(encoding='utf8'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def parents(d):
    names={g['uuid']:g['name'] for g in d['groups']}; out={}
    def walk(nodes,parent=None):
        for n in nodes:
            if isinstance(n,str):out[n]=parent
            else:walk(n.get('children',[]),names[n['uuid']])
    walk(d['outliner']);return out

def box(name,x,y,w,ht,color,parent,rotation=0):
    # All features have a 0.024 thickness and overlap the face plane by 0.002.
    i=list(PALETTE).index(color); uv=[i*4+.5,.5,i*4+3.5,3.5]
    return {'op':'add_cube','name':'r13_'+name,'from':[round(x-w/2,6),round(y-ht/2,6),-3.472],
            'to':[round(x+w/2,6),round(y+ht/2,6),-3.448],'origin':[x,y,-3.46],
            'rotation':[0,0,rotation],'parent':parent,'texture':8,'box_uv':False,
            'faces':{f:{'texture':8,'uv':uv} for f in ('north','south','east','west','up','down')}}

def eye_grid(sign=1):
    # Each cell is square in the face plane. Row spans become joined rectangles.
    # Local X runs inner -> outer on either eye. No imported image or UV tracing.
    grid=[]
    for row in range(16):
        y=.75-row*.1
        line=[]
        for col in range(20):
            x=-.95+col*.1
            # Gently lifted outer corner, rounded upper arch, soft lower contour.
            top=.68*math.sqrt(max(0,1-(x/1.01)**2))+.10*x
            bottom=-.65*math.sqrt(max(0,1-(x/1.01)**2))+.10*x
            c=None
            if bottom<=y<=top:
                c='white'
                q=((x-.04)/.57)**2+((y+.01)/.68)**2
                if q<=1:
                    c='rim' if q>.84 and y>0 else ('upper' if y>.30 else 'iris' if y>-.28 else 'lower')
                    # Short rounded core instead of a tall rectangle.
                    if ((x-.04)/.145)**2+((y-.105)/.255)**2<1:c='pupil'
                    light_x=sign*x
                    if -.28<light_x<-.04 and .24<y<.49:c='shine'
                    if .21<light_x<.34 and -.39<y<-.25:c='shine'
                if top-y<.105:c='lash'
                # No black lower ring: a short soft outer lower corner only.
                if x>.65 and y-bottom<.065:c='soft'
            line.append(c)
        grid.append(line)
    return grid

def feature_ops():
    ops=[]; save('authored-eye-grid.json',{str(sign):eye_grid(sign) for sign in (-1,1)})
    for side,sign in (('left',1),('right',-1)):
        cx=1.43*sign
        grid=eye_grid(sign)
        for row,line in enumerate(grid):
            col=0
            while col<len(line):
                color=line[col];end=col+1
                while end<len(line) and line[end]==color:end+=1
                if color:
                    x=-.95+(col+end-1)*.05
                    ops.append(box(f'eye_{side}_{row:02}_{col:02}',cx+sign*x,23.66+.75-row*.1,
                                   (end-col)*.1,.1,color,'eye_geometry_'+side))
                col=end
        # Two tidy short rectangles extend the outer lash, not an oversized wing.
        ops.append(box('lash_outer_'+side,cx+sign*.96,23.91,.18,.068,'lash','eye_geometry_'+side,sign*18))
        ops.append(box('lash_tip_'+side,cx+sign*1.015,23.945,.11,.052,'lash','eye_geometry_'+side,sign*18))
        # A restrained shallow arch. Fine enough to be distinct from the lash.
        for j,(dx,dy,angle,width) in enumerate(((-.34,0,11,.26),(-.09,.037,4,.26),(.16,.032,-6,.25),(.38,-.008,-13,.21))):
            ops.append(box(f'brow_{side}_{j}',cx+sign*dx,24.76+dy,width,.043,'brow','brow_geometry',sign*angle))
    ops.append(box('mouth_single_rectangle',0,22.19,.46,.060,'mouth','smile_geometry'))
    return ops

def refine():
    archive=WORK/'first-pass'
    if archive.exists():raise SystemExit('First-pass archive already exists')
    archive.mkdir()
    for p in [WORK/MODEL,*WORK.glob('*.png'),WORK/'authored-eye-grid.json']:
        if p.exists():shutil.copy2(p,archive/p.name)
    d=read(WORK/MODEL);pp=parents(d)
    ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] in FEATURES]
    ops+=feature_ops()
    info=h.mcp('bbmodel_info',{'file':MODEL},'r13-refine-before')
    h.mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':ops},'r13-root-eye-refine')
    frozen()

def apply():
    if sha(BASE)!=BASE_SHA:raise SystemExit('R9 baseline changed')
    WORK.mkdir(parents=True,exist_ok=True)
    if (WORK/MODEL).exists():raise SystemExit('Refusing to overwrite an existing candidate')
    shutil.copy2(BASE,WORK/MODEL)
    d=read(BASE);pp=parents(d)
    im=Image.new('RGBA',(64,64),(0,0,0,0));draw=ImageDraw.Draw(im)
    for i,c in enumerate(PALETTE.values()):draw.rectangle((i*4,0,i*4+3,3),fill='#'+c)
    im.save(WORK/'original-r13-feature-palette.png');b=io.BytesIO();im.save(b,format='PNG')
    ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements'] if pp[e['uuid']] in FEATURES]
    ops.append({'op':'add_texture','name':'original-r13-feature-palette','width':64,'height':64,
                'source':'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()})
    ops+=feature_ops()
    ops.append({'op':'set_model_properties','name':'anon-r9-likeness-r13'})
    info=h.mcp('bbmodel_info',{'file':MODEL},'r13-before')
    h.mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':ops},'r13-root-face-edit')
    frozen()

def frozen():
    a,b=read(BASE),read(WORK/MODEL);pa,pb=parents(a),parents(b)
    ea={e['uuid']:e for e in a['elements'] if pa[e['uuid']] not in FEATURES}
    eb={e['uuid']:e for e in b['elements'] if pb[e['uuid']] not in FEATURES}
    def group_tree(d):
        def walk(nodes):return [{**{k:v for k,v in n.items() if k!='children'},'children':walk(n.get('children',[]))}
                                for n in nodes if not isinstance(n,str)]
        return walk(d['outliner'])
    result={'base_sha256':sha(BASE),'candidate_sha256':sha(WORK/MODEL),
        'frozen_elements':len(ea),'all_nonfeature_elements_equal':ea==eb,'groups_equal':a['groups']==b['groups'],
        'group_hierarchy_equal':group_tree(a)==group_tree(b),'old_textures_equal':a['textures']==b['textures'][:8],
        'resolution_equal':a['resolution']==b['resolution'],
        'cube_count':len(b['elements']),'mouth_count':sum(pb[e['uuid']]=='smile_geometry' for e in b['elements']),
        'positive_cubes_only':all(e.get('type','cube')=='cube' and all(v>u for u,v in zip(e['from'],e['to'])) for e in b['elements']),
        'author':'root personally; no delegated model work'}
    for key in ('all_nonfeature_elements_equal','groups_equal','group_hierarchy_equal','old_textures_equal','resolution_equal','positive_cubes_only'):
        if not result[key]:raise SystemExit('Freeze failed: '+key)
    if result['base_sha256']!=BASE_SHA or result['mouth_count']!=1:raise SystemExit('Baseline/mouth invariant failed')
    save('frozen-r9-layout.json',result);return result

def frame(label,source):
    file=label+'-face-inspection.bbmodel';shutil.copy2(source,WORK/file)
    d=read(WORK/file);pp=parents(d)
    ops=[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'}]
    ops += [{'op':'remove_node','target':e['uuid']} for e in d['elements']
            if pp[e['uuid']] in {'rear_long_layers','side_long_layers','cheek_and_temple_locks'} and e.get('origin',[0,100,0])[1]<20.1]
    for j,(lo,hi) in enumerate((([-6.5,19,-5],[-6.48,19.02,-4.98]),([6.48,29.98,5.48],[6.5,30,5.5]))):
        ops.append({'op':'add_cube','name':'transparent_frame_'+str(j),'from':lo,'to':hi,'parent':'head','texture':3,'box_uv':False,
                    'faces':{f:{'texture':3,'uv':[40.25,40.25,40.75,40.75]} for f in ('north','south','east','west','up','down')}})
    info=h.mcp('bbmodel_info',{'file':file},label+'-frame-before')
    h.mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':ops},label+'-frame-edit')
    return file,h.mcp('bbmodel_info',{'file':file},label+'-frame-info')['bounds']

def preview():
    frozen(); bounds={}
    for label,source in (('r9',BASE),('r13',WORK/MODEL)):
        file,bounds[label]=frame(label,source)
        for view in ('front','three-quarter'):
            h.render(file,view,f'{label}-face-{view}.png',768,label+'-face-'+view,'studio')
    if bounds['r9']!=bounds['r13']:raise SystemExit('Comparison bounds differ')
    save('comparison-bounds.json',bounds)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
    for view in ('front','three-quarter'):
        canvas=Image.new('RGB',(1536,816),'#202329');draw=ImageDraw.Draw(canvas)
        for x,label,title in ((0,'r9','R9 原布局 / 原五官'),(768,'r13','R13 相同布局 / 重做眼眉嘴')):
            draw.text((x+18,12),title,font=font,fill='white')
            with Image.open(WORK/f'{label}-face-{view}.png') as im:canvas.paste(im.convert('RGB'),(x,48))
        canvas.save(WORK/f'r9-r13-face-{view}-comparison.png')
    for view in ('front','three-quarter'):
        h.render(MODEL,view,f'r13-full-{view}.png',768,'r13-full-'+view,'studio')
    h.render(MODEL,'front','r13-normal-256.png',256,'r13-normal','studio')

def finish():
    state=frozen()
    info=h.mcp('bbmodel_info',{'file':MODEL},'r13-final-info')
    static=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r13-final-static')
    for view in ('left','right','back'):
        h.render(MODEL,view,f'r13-full-{view}.png',768,'r13-final-'+view,'studio')
    state.update({'counts':info['counts'],'static':static['summary'],'self_test':False,
                  'warning_categories':{g['id']:len(g['violations']) for g in static['gates']},
                  'status':'candidate for human facial-likeness review; no objective similarity score',
                  'remaining_limits':['R9 continuous square lower face intentionally preserved','R9 hair seams preserved','rectangular closed mouth retained per user requirement']})
    save('facts.json',state)
    print(json.dumps(state,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['apply','preview','refine','finish','frozen'])
    args=p.parse_args();globals()[args.stage]()
