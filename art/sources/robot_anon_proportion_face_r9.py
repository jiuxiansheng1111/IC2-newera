"""Anon R9: proportion, flared performance skirt, cheek strands and light brows/mouth.

prepare writes operation requests only; native upstream MCP applies them with revision.
The previous model is copied without reconstructing its JSON. R8 remains read-only.
"""
from pathlib import Path
import argparse,base64,hashlib,importlib.util,json,math,shutil
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'art/robot-mcp-workspace/characters/anon-proportion-face-r9'
BASE=WORK.parent/'anon-identity-r8/anon-identity-r8.bbmodel'
MODEL='anon-proportion-face-r9.bbmodel'
SHIFT=[0,-3.85,0]
spec=importlib.util.spec_from_file_location('own_mcp_helper',ROOT/'art/sources/robot_layered_head.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
helper.WORK=WORK;helper.MODEL=MODEL
mcp=helper.mcp
UV={'skin':[.5,16.5,3.5,19.5],'blue':[16.5,16.5,19.5,19.5],'darkblue':[20.5,16.5,23.5,19.5],
    'white':[24.5,16.5,27.5,19.5],'lowwhite':[28.5,16.5,31.5,19.5],'yellow':[32.5,16.5,35.5,19.5],
    'boot':[36.5,16.5,39.5,19.5],'sole':[40.5,16.5,43.5,19.5]}

def save(name,value):
    (WORK/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def ids_in(doc,groups):
    names={g['uuid']:g['name'] for g in doc['groups']};ids=set()
    def walk(nodes,inside=False):
        for node in nodes:
            if isinstance(node,str):
                if inside:ids.add(node)
            else:walk(node.get('children',[]),inside or names[node['uuid']] in groups)
    walk(doc['outliner']);return ids

def box(name,center,size,color,parent,rotation=None,texture=0):
    uv=UV[color] if texture==0 else [.5,.5,3.5,3.5] if color=='brow' else [4.5,.5,7.5,3.5]
    return {'op':'add_cube','name':'r9_'+name,'from':[round(c-s/2,6) for c,s in zip(center,size)],
        'to':[round(c+s/2,6) for c,s in zip(center,size)],'origin':center,'rotation':rotation or [0,0,0],
        'parent':parent,'box_uv':False,'texture':texture,'faces':{f:{'uv':uv,'texture':texture} for f in ('north','south','east','west','up','down')}}

def cloth(name,a,b,width,depth,normal,color,parent):
    op=helper.segment('r9_'+name,a,b,width,depth,normal,parent,'hair',.16)
    op.update(texture=0,faces={f:{'uv':UV[color],'texture':0} for f in ('north','south','east','west','up','down')})
    return op

def body_operations(doc):
    ops=[]
    def add(*args,**kwargs):ops.append(box(*args,**kwargs))
    add('shirt_core',[0,17.7,0],[5.6,6.0,3.15],'blue','body')
    ops[-1]['faces']['north']={'uv':[8.5,.5,15.5,7.5],'texture':7}
    for side,sign in (('left',1),('right',-1)):
        ops.append(cloth('shirt_taper_'+side,[sign*3.05,20.65,0],[sign*2.70,14.8,0],.68,3.2,[0,0,-1],'blue','body'))
    add('shirt_hem',[0,14.78,0],[5.95,.20,3.25],'darkblue','body')
    add('midriff',[0,14.0,0],[5.2,1.48,2.95],'skin','body')
    # The old visible neck is removed. This shallow shirt yoke conceals the preserved internal skin connector.
    add('collar_yoke',[0,20.40,0],[5.6,.62,3.30],'blue','body')
    add('bow_left',[-.53,19.84,-1.65],[.82,.44,.10],'yellow','body',[0,0,8])
    add('bow_right',[.53,19.84,-1.65],[.82,.44,.10],'yellow','body',[0,0,-8])
    add('bow_knot',[0,19.84,-1.665],[.32,.40,.12],'yellow','body')
    add('bow_tail',[0,18.80,-1.63],[.48,1.72,.08],'yellow','body')
    for side,sign in (('left',1),('right',-1)):
        p='arm_'+side;x=sign*4.05
        add('sleeve_shoulder_'+side,[x,20.0,0],[2.25,1.4,2.9],'blue',p,[0,0,-sign*4])
        add('sleeve_cuff_'+side,[x,18.87,0],[2.1,1.25,2.60],'blue',p)
        add('sleeve_edge_'+side,[x,18.28,0],[2.14,.15,2.64],'darkblue',p)
        add('upper_arm_'+side,[x,16.83,0],[1.92,3.0,1.98],'skin',p)
        add('forearm_'+side,[sign*4.18,14.13,0],[1.73,2.55,1.82],'skin',p,[0,0,sign*6])
        add('wrist_hand_'+side,[sign*4.35,12.36,-.02],[1.62,1.10,1.78],'skin',p,[0,0,sign*3])
        p='leg_'+side;x=sign*1.65
        add('thigh_'+side,[x,10.23,0],[2.36,4.62,2.36],'skin',p)
        add('shin_'+side,[x,6.20,-.02],[2.05,3.75,2.08],'skin',p)
        add('boot_upper_'+side,[x,3.45,-.03],[2.18,1.85,2.30],'boot',p)
        # Retain the original authored boot marks, with the original atlas unchanged.
        ops[-1]['faces']['north']=next(e for e in doc['elements'] if e['name']=='r5_body_boot_upper_'+side)['faces']['north']
        add('boot_ankle_'+side,[x,1.59,-.03],[2.06,2.15,2.22],'boot',p)
        add('boot_toe_'+side,[x,1.06,-1.31],[2.16,1.02,1.15],'boot',p)
        add('boot_sole_'+side,[x,.275,-.4],[2.28,.55,3.12],'sole',p)
    add('skirt_waist',[0,12.88,0],[5.8,1.10,3.40],'lowwhite','skirt')
    # Five front gores, six side gores, five long back gores; their roots overlap beneath the waist.
    # The broad hem is actual outward-directed fabric, rather than a scaled vertical sleeve.
    ops.append(cloth('skirt_front_center',[0,12.65,-1.66],[0,8.55,-2.70],2.65,.44,[0,0,-1],'white','skirt'))
    for side,sign in (('left',1),('right',-1)):
        ops.append(cloth('skirt_front_mid_'+side,[sign*1.22,12.65,-1.65],[sign*2.50,8.45,-2.72],1.95,.43,[0,0,-1],'white','skirt'))
        ops.append(cloth('skirt_front_outer_'+side,[sign*2.30,12.65,-1.48],[sign*4.75,8.10,-2.25],2.12,.44,[0,0,-1],'white','skirt'))
        for j,(topz,endz,endy,width) in enumerate(((-.78,-1.40,7.10,2.12),(.45,.90,6.20,2.2),(1.40,2.70,5.55,2.1))):
            ops.append(cloth(f'skirt_side_{side}_{j}',[sign*2.64,12.65,topz],[sign*(5.35+.16*j),endy,endz],width,.42,[sign,0,0],'lowwhite' if j==1 else 'white','skirt'))
        ops.append(cloth('skirt_back_mid_'+side,[sign*1.25,12.65,1.63],[sign*2.75,4.85,3.35],2.30,.44,[0,0,1],'white','skirt'))
        ops.append(cloth('skirt_back_outer_'+side,[sign*2.30,12.65,1.48],[sign*4.85,5.15,3.12],2.35,.44,[0,0,1],'lowwhite','skirt'))
    ops.append(cloth('skirt_back_center',[0,12.65,1.66],[0,4.55,3.60],2.72,.45,[0,0,1],'white','skirt'))
    return ops

def cheek_operations():
    ops=[];routes=[]
    for side,sign in (('left',1),('right',-1)):
        for tag,points,widths,depths,color in (
            ('inner',[[2.95,29,-3.95],[2.62,27.1,-4.08],[2.20,25.9,-4.09],[1.85,24.9,-4.03],[1.95,24.2,-3.8]],[.36,.44,.36,.22],[.30,.32,.28,.22],'hair'),
            ('outer',[[3.30,28.8,-3.70],[2.99,26.9,-3.90],[2.64,25.35,-4.0],[2.45,23.75,-3.75]],[.42,.50,.30],[.34,.34,.24],'hair'),
            ('fine',[[2.85,27.3,-4.10],[2.4,25.7,-4.18],[2.05,24.7,-4.03]],[.24,.20],[.24,.20],'light')):
            points=[[sign*x,y+SHIFT[1],z] for x,y,z in points];routes.append({'side':side,'tag':tag,'points':points})
            for j,(a,b,w,d) in enumerate(zip(points,points[1:],widths,depths)):
                op=helper.segment(f'r9_cheek_{side}_{tag}_{j}',a,b,w,d,[0,0,-1],'cheek_and_temple_locks',color,.12)
                for f in op['faces'].values():u,v,s,t=f['uv'];f['uv']=[u+.5,v+.5,s-.5,t-.5]
                ops.append(op)
    save('original-cheek-routes.json',routes);return ops

def face_operations():
    ops=[]
    for side,sign in (('left',1),('right',-1)):
        for i,level in enumerate((0,0,1,1,1,1,0,0)):
            ops.append(box(f'brow_{side}_{i}',[sign*(1.53+(i-3.5)*.085),28.29+SHIFT[1]+level*.008,-3.462],[.085,.041,.024],'brow','brow_geometry',texture=7))
    ops.append(box('mouth_single_small_rectangle',[0,26.05+SHIFT[1],-3.457],[.40,.057,.018],'mouth','smile_geometry',texture=7))
    return ops

def prepare():
    WORK.mkdir(parents=True,exist_ok=True);shutil.copy2(BASE,WORK/MODEL)
    (WORK/'plan.md').write_text('# 爱音R9：比例、裙摆与脸缘\n\n依据本轮连续反馈，R8冻结身体/眉嘴/脖子已解除。亲看原演出服合照及清晰设定；不复制资产。短上衣从7.5降到6，适度恢复躯干宽度；腿靴缩短且稍补宽，肩臂连续。裙腰收拢，5前片/6侧片/5后片形成约11—12宽的实际外展衣料，前短后长，不是单尾/直筒放大。\n\n头和眼尺寸不变，全部头部元素和枢轴整体位移[0,-3.85,0]。删原r5_body_neck；衣领浅轭与头底衔接，掩住保留的颅内肤色连接，不留悬空露颈。眼与连续皮肤按扣除位移逐字段冻结。新增左右各3条有厚度细鬓束向下包脸，眼形留空；后发随头平移接新肩。黑眉变短薄、近乎平缓，嘴仍严格1个小横矩形，稍上移减弱色。\n\n原7atlas不改，新增第8张原创双色色板。实际工程通过真实MCP+expected_revision写入；先全身front/3q studio/192与头近景、共同世界边界R8/R9比较供根亲看。0mesh/self_test=false，不测试/Java/游戏/构建/安装/上传/自动化。\n',encoding='utf-8')
    atlas=Image.new('RGBA',(64,64),(0,0,0,0));draw=ImageDraw.Draw(atlas);draw.rectangle((0,0,3,3),fill='#29252B');draw.rectangle((4,0,7,3),fill='#AD858C')
    draw.rectangle((8,0,15,7),fill='#43768F')
    for y in (4,6):draw.point((12,y),fill='#D8D7CF')
    atlas.save(WORK/'anon-r9-brow-mouth-palette.png')
    doc=json.loads(BASE.read_text(encoding='utf-8'));head=ids_in(doc,{'head'});replace=ids_in(doc,{'brow_geometry','smile_geometry'})
    body={e['uuid'] for e in doc['elements'] if e['name'].startswith('r5_body_')}
    changes=[{'op':'remove_node','target':u} for u in sorted(body|replace)]
    for e in doc['elements']:
        if e['uuid'] in head-replace:
            if e['name']=='face_skin_hidden_connector':
                changes.append({'op':'update_node','target':e['uuid'],'from':[-1.25,20.69,-1.50],'to':[1.25,21.35,1.2],'origin':[0,21.02,-.15]})
            else:changes.append({'op':'update_node','target':e['uuid'],**{k:[round(v+SHIFT[j],6) for j,v in enumerate(e[k])] for k in ('from','to','origin') if k in e}})
    head_groups={g['uuid'] for g in doc['groups'] if g['name'] not in ('root','body','arm_left','arm_right','leg_left','leg_right','skirt')}
    pivots={'body':[0,12.9,0],'arm_left':[4.05,20.7,0],'arm_right':[-4.05,20.7,0],
        'leg_left':[1.65,12.5,0],'leg_right':[-1.65,12.5,0],'skirt':[0,12.9,0]}
    for g in doc['groups']:
        if g['uuid'] in head_groups:changes.append({'op':'update_node','target':g['uuid'],'origin':[round(v+SHIFT[j],6) for j,v in enumerate(g['origin'])]})
        elif g['name'] in pivots:changes.append({'op':'update_node','target':g['uuid'],'origin':pivots[g['name']]})
    changes+=body_operations(doc)+cheek_operations()+face_operations()+[{'op':'set_model_properties','name':'千早爱音｜R9比例裙摆与脸缘原型'}]
    save('native-operations.json',changes);save('native-operation-batches.json',{'batches':math.ceil(len(changes)/450),'operations':len(changes),'neck_removed':'r5_body_neck','head_translation':SHIFT})
    print(json.dumps({'operations':len(changes),'batches':math.ceil(len(changes)/450)}),flush=True)

def freeze():
    before=json.loads(BASE.read_text(encoding='utf-8'));after=json.loads((WORK/MODEL).read_text(encoding='utf-8'));byid={e['uuid']:e for e in after['elements']}
    groups={'eye_geometry_left','eye_geometry_right','face_skin_structure'};ids=ids_in(before,groups);checks=[]
    for e in before['elements']:
        if e['uuid'] not in ids or e['name']=='face_skin_hidden_connector':continue
        expected=json.loads(json.dumps(e))
        for key in ('from','to','origin'):
            if key in expected:expected[key]=[round(v+SHIFT[j],6) for j,v in enumerate(expected[key])]
        checks.append(byid.get(e['uuid'])==expected)
    result={'baseline_sha256':hashlib.sha256(BASE.read_bytes()).hexdigest(),'model_sha256':hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),
        'head_translation':SHIFT,'eye_and_skin_elements':len(checks),'eye_and_skin_identical_after_translation':all(checks),
        'all_original_seven_atlases_identical':before['textures']==after['textures'][:7],'resolution_identical':before['resolution']==after['resolution'],
        'visible_neck_removed':not any(e['name']=='r5_body_neck' for e in after['elements']),
        'skin_scope':'five continuous face/jaw pieces frozen after uniform translation; internal hidden connector allowed to retract behind flush collar',
        'mouth_cubes':len(ids_in(after,{'smile_geometry'})),'body_geometry_changed':True,'parent_rotation_preserved':all(g['rotation']==next(b for b in before['groups'] if b['uuid']==g['uuid'])['rotation'] for g in after['groups'])}
    untouched=ids_in(before,{'head'})-ids_in(before,{'brow_geometry','smile_geometry'})
    stable=[]
    for e in before['elements']:
        if e['uuid'] not in untouched or e['name']=='face_skin_hidden_connector':continue
        expected=json.loads(json.dumps(e))
        for key in ('from','to','origin'):
            if key in expected:expected[key]=[round(v+SHIFT[j],6) for j,v in enumerate(expected[key])]
        stable.append(byid.get(e['uuid'])==expected)
    def skeleton(nodes):return [dict(n,children=skeleton(n.get('children',[]))) for n in nodes if isinstance(n,dict)]
    result.update(original_head_elements_preserved_after_translation=len(stable),original_head_relative_fields_identical=all(stable),
        original_group_parent_hierarchy_identical=skeleton(before['outliner'])==skeleton(after['outliner']))
    save('relative-frozen-r8-record.json',result)
    if not all(result[k] for k in ('eye_and_skin_identical_after_translation','all_original_seven_atlases_identical','resolution_identical','visible_neck_removed','parent_rotation_preserved','original_head_relative_fields_identical','original_group_parent_hierarchy_identical')):raise SystemExit('Relative freeze/neck check failed.')
    return result

def inspection(label,source,scope):
    file=f'{label}-{scope}-matched-inspection.bbmodel';shutil.copy2(source,WORK/file)
    info=mcp('bbmodel_info',{'file':file},f'{label}-{scope}-before')
    changes=[]
    if scope=='face':changes=[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'},{'op':'remove_node','target':'rear_long_layers'},{'op':'remove_node','target':'side_long_layers'}]
    low_y=18.5 if scope=='face' else 0
    for i,(a,b) in enumerate((([-7.5,low_y,-6],[-7.48,low_y+.02,-5.98]),([7.48,33.98,6.48],[7.5,34,6.5]))):
        changes.append({'op':'add_cube','name':f'transparent_comparison_bounds_{i}','from':a,'to':b,'parent':'head' if scope=='face' else 'root','box_uv':False,'texture':3,
            'faces':{f:{'uv':[40.25,40.25,40.75,40.75],'texture':3} for f in ('north','south','east','west','up','down')}})
    mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':changes},f'{label}-{scope}-shared-bounds')
    return file,mcp('bbmodel_info',{'file':file},f'{label}-{scope}-info')['bounds']

def views():
    bounds={}
    for label,source in (('r9',WORK/MODEL),('r8',BASE)):
        file,bounds[label]=inspection(label,source,'full')
        helper.render(file,'front',f'{label}-full-front-matched.png',640,f'{label}-full-front-matched')
        helper.render(file,'three-quarter',f'{label}-full-three-quarter-studio-matched.png',640,f'{label}-full-three-quarter-studio-matched','studio')
        if label=='r9':
            helper.render(MODEL,'three-quarter','r9-normal-192.png',192,'r9-normal-192')
            face,_=inspection(label,source,'face')
            helper.render(face,'front','r9-face-front.png',640,'r9-face-front')
            helper.render(face,'three-quarter','r9-face-three-quarter-studio.png',640,'r9-face-three-quarter-studio','studio')
    bounds['identical']=bounds['r9']==bounds['r8'];save('comparison-camera-record.json',bounds)
    for view in ('front','three-quarter-studio'):
        canvas=Image.new('RGB',(1280,688),'#202329');draw=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
        for x,label,text in ((0,'r8','R8：前一版（共同世界边界）'),(640,'r9','R9：比例/裙摆/遮颊，待人工验收')):
            draw.text((x+12,10),text,font=font,fill='white')
            with Image.open(WORK/f'{label}-full-{view}-matched.png') as im:canvas.paste(im.convert('RGB'),(x,48))
        canvas.save(WORK/f'r8-r9-full-{view}-comparison.png')

def finish():
    frozen=freeze();info=mcp('bbmodel_info',{'file':MODEL},'native-final-info-check');static=mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r9-static')
    save('facts.json',{'status':'R9首稿，待根看图','model_sha256':frozen['model_sha256'],'counts':info['counts'],'freeze':frozen,'static':static['summary'],'self_test':False,'author_viewed_images':False,'root_review':'pending','user_acceptance':'pending'})
    views()
    for view in ('right','left','back'):
        helper.render(MODEL,view,f'r9-full-{view}.png',640,f'r9-full-{view}')
    helper.render(MODEL,'front','r9-full-front-studio.png',640,'r9-full-front-studio','studio')
    print(json.dumps({'sha256':frozen['model_sha256'],'counts':info['counts'],'static':static['summary']}),flush=True)

def prepare_refinement():
    first=WORK/'first-pass';first.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/'facts.json',WORK/'relative-frozen-r8-record.json',*WORK.glob('r9-*.png'),*WORK.glob('r8-r9-*.png')]:
        if path.exists():shutil.copy2(path,first/path.name)
    shutil.copytree(WORK/'receipts',first/'receipts',dirs_exist_ok=True)
    doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'));baseline=json.loads(BASE.read_text(encoding='utf-8'))
    atlas=Image.open(WORK/'anon-r9-brow-mouth-palette.png').convert('RGBA');draw=ImageDraw.Draw(atlas)
    draw.rectangle((8,0,15,7),fill='#43768F')
    for y in (4,6):draw.point((12,y),fill='#D8D7CF')
    atlas.save(WORK/'anon-r9-brow-mouth-palette.png')
    source='data:image/png;base64,'+base64.b64encode((WORK/'anon-r9-brow-mouth-palette.png').read_bytes()).decode()
    ops=[{'op':'update_texture','target':doc['textures'][7]['uuid'],'source':source,'width':64,'height':64}]
    chosen={'r9_shirt_core','r9_collar_yoke','r9_bow_left','r9_bow_right','r9_bow_knot','r9_bow_tail',
        'r9_forearm_left','r9_forearm_right','r9_wrist_hand_left','r9_wrist_hand_right'}
    for op in body_operations(baseline):
        if op['name'] in chosen:ops.append({'op':'update_node','target':op['name'],**{k:op[k] for k in ('from','to','origin','rotation','faces')}})
    ops.append({'op':'update_node','target':'face_skin_hidden_connector','from':[-1.25,20.69,-1.50],'to':[1.25,21.35,1.2],'origin':[0,21.02,-.15]})
    save('native-refinement-operations.json',ops)
    save('targeted-refinement-record.json',{'shirt_issue':'old authored atlas north UV rows0-2 contained skin and dark-neck rectangle, repeated beneath the new bow',
        'fix':'original seven atlases untouched; new atlas7 shirt area removes old neckline and keeps two buttons; collar flush, ribbon and bow shallow and continuous',
        'hand_clearance':'forearms/hand ends move slightly outward and rotate6/3deg, preserving upper arms and body/skirt proportions',
        'internal_skin':'hidden connector retracted behind flush collar; main five face/jaw pieces and eyes unchanged relative to translated head',
        'skirt':'strict first side/back images viewed; no large side/back hole; cloth overlap remains'})
    print(json.dumps({'refinement_operations':len(ops)}),flush=True)

def finalize_records():
    frozen=freeze();doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'));old=json.loads(BASE.read_text(encoding='utf-8'))
    info=json.loads(json.loads((WORK/'receipts/native-final-info-check.json').read_text(encoding='utf-8'))['stdout'])
    static=json.loads(json.loads((WORK/'receipts/r9-static.json').read_text(encoding='utf-8'))['stdout'])
    names={g['uuid']:g['name'] for g in doc['groups']};group_counts={}
    def walk(nodes,parent=''):
        for node in nodes:
            if isinstance(node,str):group_counts[parent]=group_counts.get(parent,0)+1
            else:walk(node.get('children',[]),names[node['uuid']])
    walk(doc['outliner'])
    pivots=[]
    for g in doc['groups']:
        before=next(b for b in old['groups'] if b['uuid']==g['uuid'])
        pivots.append({'name':g['name'],'before_origin':before['origin'],'after_origin':g['origin'],'rotation':g['rotation']})
    save('group-pivot-record.json',{'head_translation':SHIFT,'parent_hierarchy_preserved':True,'pivots':pivots})
    # Only labels and layout are regenerated here; these are raw final renders, never repainted.
    for view in ('front','three-quarter-studio'):
        canvas=Image.new('RGB',(1280,688),'#202329');draw=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
        for x,label,text in ((0,'r8','R8：前一版（共同世界边界）'),(640,'r9','R9：比例/裙摆/遮颊，待人工验收')):
            draw.text((x+12,10),text,font=font,fill='white')
            with Image.open(WORK/f'{label}-full-{view}-matched.png') as im:canvas.paste(im.convert('RGB'),(x,48))
        canvas.save(WORK/f'r8-r9-full-{view}-comparison.png')
    facts={'status':'R9主工程几何冻结，待人工外观验收','model_sha256':frozen['model_sha256'],'counts':info['counts'],
        'bounds':info['bounds'],'group_cube_counts':group_counts,'freeze':frozen,'static':static['summary'],
        'static_gates':[{'id':g['id'],'ran':g['ran'],'count':len(g['violations'])} for g in static['gates']],
        'self_test':False,'author_viewed_images':True,
        'root_review':'root viewed final full front/three-quarter/left/right/back/192, both head closeups and common-world front comparison; requested freeze this revision',
        'official_editor_review':'pending root opening final R9 file','user_acceptance':'pending; no likeness score or overall acceptance',
        'remaining_limits':'crown/fringe arcing dark seam remains unresolved; native cloth and hair block transitions/overlap remain visible',
        'neck':'r5_body_neck removed; hidden connector independently retracted; five main face/jaw pieces frozen after translation',
        'chest_fix':'old atlas neckline pixels duplicated a dark rectangle; original7 atlases untouched, own atlas8 corrected; shallow bow/tail connected on shirt, yoke flush',
        'native_writes':['native-add-texture-receipt.json','native-edit-0-receipt.json','native-edit-1-receipt.json','native-refinement-receipt.json'],
        'comparisons':{'size':[1280,688],'common_world_bounds':[-7.5,0,-6,7.5,34,6.5],'method':'real MCP inspection copies with transparent anchors; raw final renders arranged only; main model has no anchors'}}
    save('facts.json',facts);save('final-facts.json',facts)
    (WORK/'README.md').write_text('# 爱音R9：比例、展开裙摆与脸缘（几何冻结）\n\n当前供人工外观验收，不能当作相似度或整人通过。本轮仅爱音，其他七人未同步。\n\n- 主工程：[anon-proportion-face-r9.bbmodel](anon-proportion-face-r9.bbmodel)，614cube/19group/0mesh/0animation，8张贴图。最终高29.284、宽12.004；原R8高33.134/宽11.117，头部本体尺寸未放大。\n- 上衣缩短并适度恢复宽度，腿靴协调收短；裙腰收拢、16片正厚衣料向下展开，前短后长，含5前片/6侧片/5后片，不是直筒或单尾。手/前臂末端微外顺接，留开裙根。\n- 原可见neck删除；头部整体[0,-3.85,0]，肩/胯/腰裙/头部枢轴已随新关节调整，父层级和旋转保留，见group-pivot-record.json。内部hidden_connector单独缩进以贴合退平领口。\n- 双眼254件+连续主脸/浅颌5件共259件扣除统一平移后逐字段相同；包含原发组在内527件原头部元素相对字段保持，见relative-frozen-r8-record.json。新增左右各3条正厚细束包脸；黑眉16薄块，嘴严格1个小横矩形。\n- 原7atlas、未改部位UV和resolution保持。第8原创色板保存眉嘴色及修正shirt区域：旧shirt north贴图区自带肤色/深领口矩形，与新立体结重复，已去重；结和tail贴服接上、领口退平。未将该问题当设计装饰。\n\n## 最新真实图\n\n- 全身：[正面共同边界](r9-full-front-matched.png)、[前侧studio共同边界](r9-full-three-quarter-studio-matched.png)、[正面studio](r9-full-front-studio.png)、[严格右](r9-full-right.png)、[严格左](r9-full-left.png)、[背](r9-full-back.png)、[192常规](r9-normal-192.png)。\n- 头近景：[正面](r9-face-front.png)、[前侧studio](r9-face-three-quarter-studio.png)。\n- R8/R9同取景：[正面](r8-r9-full-front-comparison.png)、[前侧studio](r8-r9-full-three-quarter-studio-comparison.png)，1280×688。MCP info确认共同世界边界[-7.5,0,-6]..[7.5,34,6.5]，透明锚点仅在检查副本，不在主工程；只排布真实渲染。\n- first-pass/为胸前去重前的首稿，不是最终图。r9-first-right/back.png为首稿检查图，亦非最终。\n\n## 记录与限制\n\n根已亲看最终完整多视角、192及头部，要求冻结该revision；最终官方Blockbench实开由根另外记录。冠顶/刘海弧形暗接缝仍未解决，衣片/发块转折与覆盖仍可见，未在本轮额外重构。\n\n全部主模型写入经当前原生上游MCP，带expected_revision；native-operations.json、native-refinement-operations.json及native-*-receipt.json保留请求/回执。检查副本、静态与渲染经同一原作者真实stdio MCP，完整回执在receipts/。静态0错误/432警告：271薄件接触/浮离、146内部覆盖、15非镜像；self_test=false。没有运行测试、Java、游戏、构建、安装、自动化或上传。\n\n## 可重复制作\n\n每阶段先在聊天真实读取全部额度，普通可用且每窗口余量>1%才继续。使用既有Python/PIL和外部已安装上游MCP缓存，无新安装。\n\n1. `python art/sources/robot_anon_proportion_face_r9.py --quota-checked --prepare`：仅复制只读R8并生成原创PNG和native-operations.json，不直接构造模型JSON。\n2. 原生bbmodel_info读取新文件revision；bbmodel_add_texture嵌入anon-r9-brow-mouth-palette.png，带expected_revision。\n3. 原生bbmodel_edit按native-operations.json每450项分批，每批重新info取expected_revision。\n4. `python art/sources/robot_anon_proportion_face_r9.py --quota-checked --finish`：相对冻结、静态self_test=false和真实MCP渲染。\n5. 看图后`--finalize`仅整理标签、facts/索引，不改几何。也可使用`--rebuild-upstream`，以已验证原作者stdio传输完整重建同一原创几何；会生成新UUID/revision，不能期望二进制hash相同。当前已冻结工程不要为展示随意重跑。\n\n最终SHA256：`'+frozen['model_sha256']+'`\n',encoding='utf-8')
    print(json.dumps({'final_sha256':frozen['model_sha256'],'frozen':True,'relative_preserved':frozen['original_head_elements_preserved_after_translation']}),flush=True)

def rebuild_upstream():
    prepare();info=mcp('bbmodel_info',{'file':MODEL},'rebuild-before-palette')
    mcp('bbmodel_add_texture',{'file':MODEL,'image':str(WORK/'anon-r9-brow-mouth-palette.png'),'name':'anon_original_r9_brow_mouth','expected_revision':info['revision']},'rebuild-original-palette')
    changes=json.loads((WORK/'native-operations.json').read_text(encoding='utf-8'))
    for part in range(0,len(changes),450):
        info=mcp('bbmodel_info',{'file':MODEL},f'rebuild-before-{part//450}')
        mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':changes[part:part+450]},f'rebuild-batch-{part//450}')
    finish();finalize_records()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--quota-checked',action='store_true');p.add_argument('--prepare',action='store_true');p.add_argument('--prepare-refinement',action='store_true');p.add_argument('--finish',action='store_true');p.add_argument('--finalize',action='store_true');p.add_argument('--rebuild-upstream',action='store_true');a=p.parse_args()
    if not a.quota_checked:raise SystemExit('Read actual quota before source work.')
    if a.finalize:finalize_records()
    elif a.rebuild_upstream:rebuild_upstream()
    elif a.prepare_refinement:prepare_refinement()
    elif a.prepare:prepare()
    elif a.finish:finish()
