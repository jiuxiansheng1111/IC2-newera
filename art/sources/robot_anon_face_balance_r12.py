"""Original R12 cuboid study on read-only R9; all model writes use upstream MCP.

The external R63 asset is read only as a method reference. No geometry, UV,
texture, or animation from it is copied. No quota lookup, game, build, or test.
"""
from pathlib import Path
import argparse
import collections
import hashlib
import importlib.util
import json
import shutil
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'art/robot-mcp-workspace/characters/anon-face-balance-r12'
BASE = WORK.parent / 'anon-proportion-face-r9/anon-proportion-face-r9.bbmodel'
MODEL = 'anon-face-balance-r12.bbmodel'
BASE_SHA = 'e1b95c692c2892d69df1314def89d5132f732cb50b3e6d71cfbde70bc655b80b'
REFERENCE = Path('C:/Users/罗斌/Desktop/百度网盘资源/R63_YSM_完整动画.bbmodel')
FACE_GROUPS = {'face_skin_structure', 'eye_geometry_left', 'eye_geometry_right',
               'brow_geometry', 'smile_geometry'}
HAIR_GROUPS = {'crown_layers', 'side_swept_fringe', 'cheek_and_temple_locks',
               'rear_long_layers', 'side_long_layers', 'hair_clips'}
spec = importlib.util.spec_from_file_location('own_layer_helper', ROOT/'art/sources/robot_layered_head.py')
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
h.WORK = WORK


def save(name, value):
    p = WORK/name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def parents(d):
    names = {g['uuid']:g['name'] for g in d['groups']}
    result = {}
    def walk(nodes, parent=None):
        for n in nodes:
            if isinstance(n, str):
                result[n] = parent
            else:
                walk(n.get('children', []), names[n['uuid']])
    walk(d['outliner'])
    return result


def box(name, center, size, tex, uv, parent, rotation=None):
    return {'op':'add_cube', 'name':'r12_'+name,
            'from':[round(c-s/2, 6) for c,s in zip(center,size)],
            'to':[round(c+s/2, 6) for c,s in zip(center,size)],
            'origin':center, 'rotation':rotation or [0,0,0], 'parent':parent,
            'box_uv':False, 'texture':tex,
            'faces':{f:{'texture':tex,'uv':uv} for f in ('north','east','south','west','up','down')}}


def face_operations(d):
    ops = []
    def skin(name, center, size, rotation=None):
        ops.append(box(name,center,size,2,[16.5,.5,19.5,3.5],'face_skin_structure',rotation))
    # A continuous face, two short shallow edges, and a broad shallow chin.
    # The cheek locks mask the lower outer corners instead of skin slicing.
    skin('face_continuous_main',[0,23.30,-2.75],[4.80,4.40,1.0])
    skin('face_inner_support',[0,23.30,-.05],[4.45,4.30,4.50])
    skin('chin_shallow',[0,20.905,-2.68],[2.80,.45,1.12])
    for sign in (-1,1):
        skin('jaw_edge_'+str(sign),[sign*1.74,21.035,-2.68],[1.30,.38,1.12],[0,0,sign*12])
    e = next(e for e in d['elements'] if e['name']=='face_skin_hidden_connector')
    ops.append({'op':'add_cube','name':'r12_hidden_connector','from':e['from'],'to':e['to'],
                'origin':e['origin'],'parent':'face_skin_structure','box_uv':False,
                'texture':2,'faces':e['faces']})
    for side,sign in (('left',1),('right',-1)):
        cx = sign*1.18
        parent = 'eye_geometry_'+side
        def eye(name, dx, y, size, index, z=-3.271):
            ops.append(box(name+'_'+side,[cx+sign*dx,y,z],size,6,
                           [index*4+.5,.5,index*4+3.5,3.5],parent))
        # Three coherent sclera pieces and a vertical iris with restrained colors.
        eye('sclera_mid',0,22.95,[1.60,.88,.028],1)
        eye('sclera_upper',0,23.43,[1.36,.16,.028],1)
        eye('sclera_lower',0,22.46,[1.28,.16,.028],1)
        eye('iris_main',0,22.98,[.76,1.06,.022],3,-3.297)
        eye('iris_upper',0,23.37,[.76,.26,.018],2,-3.317)
        eye('iris_lower',0,22.54,[.62,.18,.018],4,-3.317)
        eye('pupil',0,23.00,[.205,.60,.018],5,-3.337)
        eye('iris_highlight',-.15,23.27,[.135,.135,.014],7,-3.354)
        # Low arch, upper contour clearly stronger than the brow.
        eye('upper_lid_center',0,23.545,[1.20,.085,.028],0,-3.337)
        eye('upper_lid_inner',-.68,23.47,[.22,.075,.028],0,-3.337)
        eye('upper_lid_outer',.68,23.49,[.22,.085,.028],0,-3.337)
        eye('upper_lid_wing',.83,23.45,[.12,.07,.028],0,-3.337)
        eye('lower_corner',.68,22.49,[.16,.042,.025],2,-3.316)
        # One light, short black eyebrow, three aligned pieces.
        for j,(dx,dy) in enumerate(((-.26,0),(0,.02),(.26,.015))):
            ops.append(box('brow_'+side+'_'+str(j),[cx+sign*dx,23.925+dy,-3.268],
                           [.266,.034,.022],7,[.5,.5,3.5,3.5],'brow_geometry'))
    ops.append(box('mouth_single_rectangle',[0,21.58,-3.265],[.38,.057,.018],
                   7,[4.5,.5,7.5,3.5],'smile_geometry'))
    return ops


def cheek_operations():
    ops, routes = [], []
    paths = [
        ('inner',[[2.37,24.50,-3.82],[2.25,23.45,-3.94],[1.97,22.35,-3.98],
                  [1.70,21.25,-3.92],[1.76,20.45,-3.70]], [.43,.44,.42,.22], [.30,.30,.28,.20]),
        ('outer',[[2.82,24.60,-3.57],[2.67,23.20,-3.72],[2.38,22.00,-3.75],
                  [2.12,20.85,-3.60]], [.50,.50,.40], [.31,.31,.24]),
    ]
    for side,sign in (('left',1),('right',-1)):
        for name,points,widths,depths in paths:
            pts = [[sign*x,y,z] for x,y,z in points]
            routes.append({'name':name+'_'+side,'points':pts,'widths':widths,'depths':depths})
            for i,(a,b) in enumerate(zip(pts,pts[1:])):
                ops.append(h.segment('r12_cheek_'+side+'_'+name+'_'+str(i),a,b,widths[i],depths[i],
                                     [0,0,-1],'cheek_and_temple_locks','hair',.065))
    return ops,routes


def prepare():
    WORK.mkdir(parents=True, exist_ok=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest()!=BASE_SHA:
        raise SystemExit('Read-only R9 baseline changed.')
    if (WORK/MODEL).exists():
        raise SystemExit('Existing candidate is not overwritten.')
    d = json.loads(BASE.read_text(encoding='utf-8'))
    pp = parents(d)
    shutil.copy2(BASE,WORK/MODEL)
    ops = []
    for e in d['elements']:
        group = pp[e['uuid']]
        if group in FACE_GROUPS or e['name'].startswith('r9_cheek_'):
            ops.append({'op':'remove_node','target':e['uuid']})
        elif group in HAIR_GROUPS:
            # Exact uniform scaling of the existing cuboids, about the collar.
            def tr(v): return [round(v[0]*.94,6),round(20.7+(v[1]-20.7)*.94,6),round(v[2]*.94,6)]
            ops.append({'op':'update_node','target':e['uuid'],'from':tr(e['from']),
                        'to':tr(e['to']),'origin':tr(e['origin'])})
    cheek,routes = cheek_operations()
    ops += face_operations(d)+cheek+[{'op':'set_model_properties','name':'千早爱音｜R12短浅脸与竖向蓝灰眼'}]
    save('native-operations.json',ops)
    save('authored-cheek-routes.json',routes)
    save('plan.json',{'baseline_sha256':BASE_SHA,'reference_sha256':hashlib.sha256(REFERENCE.read_bytes()).hexdigest(),
         'reference_use':'method only; no reference elements, UVs, textures, animations copied',
         'head_hair_uniform_scale':.94,'scale_anchor':[0,20.7,0],
         'skin_main_size':[4.8,4.4,1.0],'chin_width':2.8,'eye_centers':[[1.18,22.98],[-1.18,22.98]],
         'frozen':'R9 non-head 51 cubes, all 19 groups, hierarchy, origins/rotations/pivots, original 8 atlases/resolution',
         'status':'first candidate; visual review pending','self_test':False})
    print(json.dumps({'operations':len(ops),'work':str(WORK)}),flush=True)


def apply():
    ops = json.loads((WORK/'native-operations.json').read_text(encoding='utf-8'))
    for i in range(0,len(ops),400):
        info = h.mcp('bbmodel_info',{'file':MODEL},'r12-before-edit-'+str(i//400))
        h.mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],
                             'operations':ops[i:i+400]},'r12-native-edit-'+str(i//400))
    frozen()


def refine_eyes():
    """Root-directed second pass: retain compact face; open stepped almond eyes."""
    archive=WORK/'first-pass'
    if archive.exists():
        raise SystemExit('First pass archive exists; avoid reapplying refinement.')
    archive.mkdir()
    for path in WORK.iterdir():
        if path.is_file():
            shutil.copy2(path,archive/path.name)
    shutil.copytree(WORK/'receipts',archive/'receipts')
    d=json.loads((WORK/MODEL).read_text(encoding='utf-8'));pp=parents(d)
    ops=[{'op':'remove_node','target':e['uuid']} for e in d['elements']
         if pp[e['uuid']] in {'eye_geometry_left','eye_geometry_right'}]
    for side,sign in (('left',1),('right',-1)):
        cx=sign*1.18;parent='eye_geometry_'+side
        def eye(name,dx,y,size,index,z=-3.271):
            ops.append(box('almond_'+name+'_'+side,[cx+sign*dx,y,z],size,6,
                           [index*4+.5,.5,index*4+3.5,3.5],parent))
        # Four wide, touching sections form one open sclera silhouette.
        # Upper and lower edges contract at different rates; corners stay open.
        eye('sclera_upper',0,23.36,[1.25,.30,.028],1)
        eye('sclera_mid',-.02,23.08,[1.62,.32,.028],1)
        eye('sclera_lower',-.04,22.78,[1.44,.32,.028],1)
        eye('sclera_base',-.05,22.53,[1.02,.20,.028],1)
        eye('sclera_outer_corner',.68,23.23,[.25,.24,.028],1)
        # Iris upper band is broad, with a shallow taper toward the lower edge.
        eye('iris_upper',0,23.32,[.80,.24,.022],2,-3.297)
        eye('iris_mid',0,23.03,[.76,.34,.022],3,-3.297)
        eye('iris_lower',0,22.755,[.64,.21,.022],4,-3.297)
        eye('iris_tip',0,22.555,[.46,.19,.022],4,-3.297)
        eye('pupil',0,23.045,[.19,.37,.018],5,-3.320)
        eye('highlight',-.16,23.275,[.14,.14,.014],7,-3.338)
        # Four connected sloping bars follow the open upper contour.
        points=[[-.81,23.24],[-.50,23.45],[.24,23.56],[.64,23.43],[.86,23.32]]
        for i,(a,b) in enumerate(zip(points,points[1:])):
            aa=[cx+sign*a[0],a[1],-3.328];bb=[cx+sign*b[0],b[1],-3.328]
            op=h.segment('r12_almond_upper_lid_'+side+'_'+str(i),aa,bb,.08 if i<3 else .065,
                         .027,[0,0,-1],parent,'lash',.018)
            op['texture']=6
            for f in op['faces'].values():f.update(texture=6,uv=[.5,.5,3.5,3.5])
            ops.append(op)
        eye('lower_outer_corner',.67,22.63,[.13,.045,.025],2,-3.314)
    ops.append({'op':'update_node','target':'r12_mouth_single_rectangle',
                'from':[-.19,21.7315,-3.274],'to':[.19,21.7885,-3.256],
                'origin':[0,21.76,-3.265]})
    # Three final sweep pieces are gently combed toward the same direction.
    # Root positions and overall R9 fringe outline are retained.
    for name,z in [('r8_sweep_a_2',-53),('r8_sweep_b_2',-55),('r8_sweep_c_2',-59)]:
        e=next(e for e in d['elements'] if e['name']==name)
        rotation=e.get('rotation',[0,0,0]).copy();rotation[2]=z
        ops.append({'op':'update_node','target':e['uuid'],'rotation':rotation})
    save('native-eye-refinement-operations.json',ops)
    info=h.mcp('bbmodel_info',{'file':MODEL},'r12-before-eye-refinement')
    h.mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':ops},
          'r12-native-eye-refinement')
    save('eye-refinement-plan.json',{'root_feedback':'retain compact face; remove rectangular-window eyes',
         'sclera':'five coherent cuboids, open stepped almond outline',
         'iris':'upper broad dark section, light lower two-stage taper, shorter pupil and one square highlight',
         'eye_centers_unchanged':[1.18,-1.18],'mouth_y':21.76,
         'hair_exception':'only last a/b/c sweep Z rotations -53/-55/-59; roots and cuboid dimensions unchanged',
         'status':'second candidate; root visual review pending'})
    frozen()


def frozen():
    a=json.loads(BASE.read_text(encoding='utf-8'));b=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    pa,pb=parents(a),parents(b)
    def body(d,pp):return [e for e in d['elements'] if pp[e['uuid']] not in FACE_GROUPS|HAIR_GROUPS]
    def hierarchy(nodes):return [dict(n,children=hierarchy(n.get('children',[]))) for n in nodes if isinstance(n,dict)]
    record={'r9_sha256':hashlib.sha256(BASE.read_bytes()).hexdigest(),
            'r12_sha256':hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),
            'non_head_51_identical':body(a,pa)==body(b,pb),'non_head_count':len(body(a,pa)),
            'all_groups_pivots_identical':a['groups']==b['groups'],
            'parent_hierarchy_identical':hierarchy(a['outliner'])==hierarchy(b['outliner']),
            'original_8_atlases_identical':a['textures']==b['textures'],
            'resolution_identical':a['resolution']==b['resolution'],
            'all_cube_dimensions_positive':all(all(x<y for x,y in zip(e['from'],e['to'])) for e in b['elements']),
            'counts_by_group':dict(collections.Counter(pb.values())),
            'head_pivot_change':None,'main_model_has_camera_anchors':False}
    save('frozen-r9-record.json',record)
    required=['non_head_51_identical','all_groups_pivots_identical','parent_hierarchy_identical',
              'original_8_atlases_identical','resolution_identical','all_cube_dimensions_positive']
    if not all(record[k] for k in required) or record['r9_sha256']!=BASE_SHA:
        raise SystemExit('Frozen fields differ or non-positive cuboid.')
    return record


def frame(label, source):
    file=label+'-face-inspection.bbmodel';shutil.copy2(source,WORK/file)
    d=json.loads((WORK/file).read_text(encoding='utf-8'));pp=parents(d)
    ops=[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'}]
    ops += [{'op':'remove_node','target':e['uuid']} for e in d['elements']
            if pp[e['uuid']] in {'rear_long_layers','side_long_layers','cheek_and_temple_locks'}
            and e.get('origin',[0,100,0])[1]<20.1]
    for j,(lo,hi) in enumerate((([-6.5,19,-5],[-6.48,19.02,-4.98]),([6.48,29.98,5.48],[6.5,30,5.5]))):
        ops.append({'op':'add_cube','name':'transparent_frame_'+str(j),'from':lo,'to':hi,'parent':'head',
                    'texture':3,'box_uv':False,'faces':{f:{'texture':3,'uv':[40.25,40.25,40.75,40.75]}
                     for f in ('north','south','east','west','up','down')}})
    info=h.mcp('bbmodel_info',{'file':file},label+'-face-before')
    h.mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':ops},label+'-face-frame')
    return file,h.mcp('bbmodel_info',{'file':file},label+'-face-info')['bounds']


def preview():
    refined=(WORK/'eye-refinement-plan.json').exists()
    stage='第二轮待审' if refined else '首轮待审'
    freeze=frozen()
    info=h.mcp('bbmodel_info',{'file':MODEL},'r12-main-info')
    static=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r12-static')
    bounds={}
    for label,source in (('r12',WORK/MODEL),('r9',BASE)):
        file,bounds[label]=frame(label,source)
        for view in ('front','three-quarter'):
            h.render(file,view,f'{label}-face-{view}-studio.png',640,label+'-face-'+view,'studio')
    for view in ('front','three-quarter'):
        h.render(MODEL,view,f'r12-full-{view}-studio.png',640,'r12-full-'+view,'studio')
    h.render(MODEL,'three-quarter','r12-normal-192.png',192,'r12-normal-192','studio')
    bounds['identical']=bounds['r9']==bounds['r12'];save('comparison-camera-record.json',bounds)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    for view in ('front','three-quarter'):
        canvas=Image.new('RGB',(1280,688),'#202329');draw=ImageDraw.Draw(canvas)
        for x,label,title in ((0,'r9','R9：用户指定基底（同世界边界）'),
                               (640,'r12','R12：短浅脸／竖向虹膜，'+stage)):
            draw.text((x+12,10),title,font=font,fill='white')
            with Image.open(WORK/f'{label}-face-{view}-studio.png') as im:
                canvas.paste(im.convert('RGB'),(x,48))
        canvas.save(WORK/f'r9-r12-face-{view}-comparison.png')
    save('facts.json',{'status':('second' if refined else 'first')+' candidate; root and user visual review pending',
         'counts':info['counts'],'freeze':freeze,'static':static['summary'],'self_test':False})
    (WORK/'README.md').write_text('# 爱音 R12 '+stage+'：从原 R9 收短浅脸\n\n'
      '主工程：`anon-face-balance-r12.bbmodel`。真实原作者 MCP 改模和渲染，写入带 expected_revision；'
      '本目录 receipts 保留调用参数、回执。源为 `art/sources/robot_anon_face_balance_r12.py`。\n\n'
      '首轮改动：R9发型以领口为锚整体缩为0.94；主脸4.8×4.4、浅下巴宽2.8；'
      '双眼宽1.6、中心±1.18/y22.98，少量规整块组织竖向蓝灰虹膜；黑细眉与上眼线分开；'
      '单横矩形嘴初版y21.58、第二轮y21.76；替换内侧遮颊束跟随短脸。R9非head51件、原8atlas、'
      '全部组与父层级、pivot/旋转保持。\n\n'
      '先看 `r9-r12-face-front-comparison.png`、`r9-r12-face-three-quarter-comparison.png`；'
      '均由同边界真实渲染原像素拼排，标签在图外。完整人物为 `r12-full-front-studio.png`、'
      '`r12-full-three-quarter-studio.png`；常规尺寸为 `r12-normal-192.png`。'
      'inspection副本仅为取景，主工程无取景锚点。\n\n'
      '首轮已归档first-pass；第二轮将白矩形改为打开的浅阶梯杏眼轮廓，虹膜上宽下收、'
      '缩短瞳芯，四条斜上睑替代平线。最低a/b/c束仅调整Z旋转跟顺同一走势。'
      '当前等待根看图反馈；未宣称外观、相似度、动画或游戏通过。'
      'R9头发原有接缝/块边保留，眼型仍有简化几何折角，下巴仍有直边。静态资源检查 self_test=false；'
      '未运行游戏、Java、构建、安装、测试或自检注入，未改其它七人。\n\n'
      'SHA256：`'+freeze['r12_sha256']+'`\n',encoding='utf-8')
    print(json.dumps({'sha256':freeze['r12_sha256'],'counts':info['counts'],
                      'static':static['summary'],'matched_bounds':bounds['identical']}),flush=True)


def finish():
    """Freeze second-pass geometry and render the final saved model; no main edit."""
    before=hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest()
    if not (WORK/'eye-refinement-plan.json').exists():
        raise SystemExit('Second eye pass must precede final view export.')
    freeze=frozen()
    info=h.mcp('bbmodel_info',{'file':MODEL},'r12-final-info')
    static=h.mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r12-final-static')
    # Head copy keeps every hair cuboid. Only the body tree is removed with MCP.
    head='r12-final-complete-head-inspection.bbmodel'
    shutil.copy2(WORK/MODEL,WORK/head)
    current=h.mcp('bbmodel_info',{'file':head},'r12-final-head-before')
    h.mcp('bbmodel_edit',{'file':head,'expected_revision':current['revision'],'operations':[
        {'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'}]},
        'r12-final-head-detach')
    for view in ('front','left','right','back','three-quarter'):
        # The final full front and 3q are copied without pixel modifications.
        source=WORK/f'r12-full-{view}-studio.png'
        if view in ('front','three-quarter') and source.exists():
            shutil.copy2(source,WORK/f'r12-final-full-{view}-studio.png')
        else:
            h.render(MODEL,view,f'r12-final-full-{view}-studio.png',640,'r12-final-full-'+view,'studio')
        h.render(head,view,f'r12-final-head-{view}-studio.png',768,'r12-final-head-'+view,'studio')
    after=hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest()
    if before!=after:
        raise SystemExit('Final rendering changed the frozen main model.')
    warnings={g['id']:len(g['violations']) for g in static['gates']}
    renders=[]
    for p in sorted(WORK.glob('r12-final-*.png')):
        with Image.open(p) as im:size=list(im.size)
        renders.append({'file':p.name,'size':size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
    facts={'status':'second-pass geometry frozen for human acceptance; no appearance/similarity approval',
           'model':MODEL,'main_sha256':after,'source':'art/sources/robot_anon_face_balance_r12.py',
           'counts':info['counts'],'static':{'self_test':False,'summary':static['summary'],'violations_by_gate':warnings},
           'freeze':freeze,'main_unchanged_during_final_exports':before==after,
           'reference_sha256':hashlib.sha256(REFERENCE.read_bytes()).hexdigest(),
           'reference_use':'read-only structural method; no copied geometry/UVs/textures/animations',
           'final_renders':renders,'first_pass_archive':'first-pass/',
           'root_review':'second front/3q/matched comparison/192 personally viewed; accepted only as a pending human review draft',
           'desktop_editor_review':'root is handling official desktop open; this source makes no claim of completion',
           'remaining_visual_limits':['geometric eye corners','neutral expression','straight shallow chin edge',
                                      'inherited R9 crown/fringe seams and hair cuboid joins'],
           'excluded':['game','Java','build','installation','tests','self_test injection','other seven characters','upload','automation','quota lookup']}
    save('final-facts.json',facts)
    save('facts.json',facts)
    (WORK/'README.md').write_text('''# 爱音 R12：短浅脸与打开的杏眼，二轮几何冻结

当前是供人类外观反馈的单人静态稿。根已亲看二轮正脸、3q、R9同边界对照与192图；这只确认可作为待验收稿，未宣称外观、相似度、动画或游戏通过。桌面版官方Blockbench打开由根负责，当前文件不代替其编辑器回执。

主工程：[anon-face-balance-r12.bbmodel](anon-face-balance-r12.bbmodel)。保留19组、原8atlas、378个正厚cube、0mesh、0animation。真实原作者MCP改模、expected_revision保护；参数和完整原始回执在`receipts/`。源为`art/sources/robot_anon_face_balance_r12.py`。

## 本轮修改

- 从原R9开始，发型以领口[0,20.7,0]为锚整体缩为0.94，保留原有分组和发型大方向。主脸为连续4.8×4.4面，浅下巴宽2.8；两侧短浅脸缘和内鬓发共同收窄实际可见脸、压短眼下留白。
- 双眼中心仍约±1.18/y22.98，眼白用5片连贯实体形成打开的浅阶梯杏眼，上睑由4段轻斜线围合；蓝灰虹膜上宽下收、暗上区与亮下区分开，短瞳芯和一处高光。每眼16件，未恢复R9的127件碎块眼，也未保留首轮方窗轮廓。
- 黑细眉比上睑轻薄，保持清楚间隔。嘴严格一件薄横矩形，位于y21.76。额前a/b/c最低段只调整Z旋转至-53/-55/-59，朝向稍统一。
- R9非head51件、全部组/父层级/组origin/rotation/pivot、原8atlas/resolution逐字段保持；头部pivot没有例外修改。原R9与方法参考原文件SHA保持。主模型无取景锚点。

## 查看

- [R9同边界正脸对照](r9-r12-face-front-comparison.png)、[R9同边界3q对照](r9-r12-face-three-quarter-comparison.png)。两边world bounds完全相同，真实渲染像素原样拼排，文字仅放在图外。
- 完整人物640与完整头部768如下；头部保留全部头发，只有MCP移除身体，未裁掉下段长发。

| 视角 | 完整人物 | 完整头部 |
| --- | --- | --- |
| 正面 | [全身](r12-final-full-front-studio.png) | [头部](r12-final-head-front-studio.png) |
| 左侧 | [全身](r12-final-full-left-studio.png) | [头部](r12-final-head-left-studio.png) |
| 右侧 | [全身](r12-final-full-right-studio.png) | [头部](r12-final-head-right-studio.png) |
| 背面 | [全身](r12-final-full-back-studio.png) | [头部](r12-final-head-back-studio.png) |
| 3q | [全身](r12-final-full-three-quarter-studio.png) | [头部](r12-final-head-three-quarter-studio.png) |

- [近脸正面](r12-face-front-studio.png)、[近脸3q](r12-face-three-quarter-studio.png)、[常规192](r12-normal-192.png)。近脸取景副本带透明框，非主模型；仅取景副本省略少数低于y20.1的发束。
- 首轮工程、图、回执完整保留在`first-pass/`。最终真值是本目录主工程及`final-facts.json`，首轮不作为最终稿。

## 实际边界

已保留眼型的几何折角、偏中性的表情、浅下巴底沿直边，R9冠顶/刘海接缝和发块转折仍可见。不继续自行迭代，不以资源计数或静态通过替代外观反馈。

静态检查`self_test=false`：0错误、146警告（floating 11、interpenetration 129、mirror 6）；完整逐项内容在`receipts/r12-final-static.json`。这些包含薄五官的接触判定、头发/肤面覆盖和偏分不镜像；没有隐去警告。仅资源检查，不代表目标游戏格式、动画或运行验收。

未运行游戏、Java、构建、安装、测试或自检注入，未改其它七人、自动化或额度设置，未查询额度、购买或重置。只从R9复制我方基底；R63仅只读研究组织方法，未复制其几何/UV/贴图/动画。

## 重现

从项目根目录运行源的阶段命令：`prepare` → `apply` → `preview` → `refine_eyes` → `preview` → `finish`。例如`python art/sources/robot_anon_face_balance_r12.py finish`只导出冻结二轮多视角和记录。`prepare`拒绝覆盖已有模型；若重做，应先将本次交付妥善归档，并将新任务源的WORK改为新的独立输出目录。`refine_eyes`先归档首版并拒绝重复执行。所有模型修改和取景副本调整均经过真实MCP；无quota flag，也不检查额度。

SHA256：`'''+after+'`\n',encoding='utf-8')
    print(json.dumps({'sha256':after,'counts':info['counts'],'static':static['summary'],
                      'warning_categories':warnings,'main_frozen':before==after}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['prepare','apply','preview','frozen','refine_eyes','finish'])
    args=p.parse_args()
    globals()[args.stage]()
