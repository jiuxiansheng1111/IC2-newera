"""Original R10 continuous face and ordered root-to-tip cuboid hair; native MCP requests."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,math,shutil
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'art/robot-mcp-workspace/characters/anon-face-hair-r10'
BASE=WORK.parent/'anon-proportion-face-r9/anon-proportion-face-r9.bbmodel'
MODEL='anon-face-hair-r10.bbmodel'
COLORS={'hair':'E8AFC1','hair_light':'EDB8C9','hair_low':'DFA6BA','skin':'F3D7C8','white':'FFF6EE',
    'lash':'493940','iris':'91ADB9','iris_top':'617E91','iris_bottom':'B6CCD3','pupil':'405F73',
    'shine':'FFFDF9','brow':'30272D','mouth':'B9848E'}
spec=importlib.util.spec_from_file_location('own_cuboid_helper',ROOT/'art/sources/robot_layered_head.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
helper.WORK=WORK;helper.MODEL=MODEL;mcp=helper.mcp

def save(name,value):(WORK/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def ids_in(doc,groups):
    names={g['uuid']:g['name'] for g in doc['groups']};ids=set()
    def walk(nodes,inside=False):
        for n in nodes:
            if isinstance(n,str):
                if inside:ids.add(n)
            else:walk(n.get('children',[]),inside or names[n['uuid']] in groups)
    walk(doc['outliner']);return ids

def faces(color):
    i=list(COLORS).index(color);uv=[i*4+.5,.5,i*4+3.5,3.5]
    return {f:{'uv':uv,'texture':8} for f in ('north','south','east','west','up','down')}

def box(name,center,size,color,parent,rotation=None):
    return {'op':'add_cube','name':'r10_'+name,'from':[round(c-s/2,6) for c,s in zip(center,size)],
        'to':[round(c+s/2,6) for c,s in zip(center,size)],'origin':center,'rotation':rotation or [0,0,0],
        'parent':parent,'box_uv':False,'texture':8,'faces':faces(color)}

def strand(name,points,widths,depths,normal,parents,colors='hair'):
    directions=[helper.unit([b[k]-a[k] for k in range(3)]) for a,b in zip(points,points[1:])]
    turns=[math.acos(max(-1,min(1,sum(a[k]*b[k] for k in range(3))))) for a,b in zip(directions,directions[1:])]
    ops=[]
    for i,(a,b,w,d) in enumerate(zip(points,points[1:],widths,depths)):
        overlap=max(.025,d*math.tan(max(turns[max(0,i-1):min(len(turns),i+1)] or [0])/2)+.018)
        n=normal[i] if isinstance(normal[0],list) else normal
        op=helper.segment('r10_'+name+'_'+str(i),a,b,w,d,n,parents[i] if isinstance(parents,list) else parents,'hair',overlap)
        op.update(texture=8,faces=faces(colors[i] if isinstance(colors,list) else colors));ops.append(op)
    return ops

def hair_operations():
    ops=[];routes=[]
    def add(name,points,widths,depths,normal,parents,colors='hair'):
        routes.append({'name':name,'points':points,'widths':widths,'depths':depths,'parents':parents})
        ops.extend(strand(name,points,widths,depths,normal,parents,colors))
    # Neighboring cap streams meet internal continuations. Frontal locks are authored independently below.
    for i in range(25):
        s=-3.96+i*.33;radius=math.sqrt(max(.06,1-(s/4.45)**2))
        front_end=-1.62-.09*(s+3.6)/4.8 if s<=1.2 else -1.60+.15*(s-1.2)/3
        count=max(6,math.ceil((1.35-front_end)*3.8*radius/.88))
        points=[]
        for j in range(count+1):
            t=j/count;theta=1.35+(front_end-1.35)*t
            front=max(0,min(1,(-theta-.10)/1.60))
            main=1/(1+math.exp((s-1.2)/.55));sway=(-1.70*main+.28*(1-main))*max(0,1-(s/4.3)**2)
            x=s+sway*front;r=math.sqrt(max(.04,1-(x/4.45)**2))
            points.append([round(x,5),round(25.25+3.65*r*math.cos(theta),5),round(.1+4*r*math.sin(theta),5)])
        normals=[]
        for j,(a,b) in enumerate(zip(points,points[1:])):
            m=[(a[k]+b[k])/2 for k in range(3)]
            normals.append([m[0]/4.45**2,(m[1]-25.25)/3.65**2,(m[2]-.1)/4**2])
        widths=[.55]*(count-3)+[.63,.61,.48]
        depths=[.40]*(count-2)+[.38,.32]
        parents=['crown_layers']*(count-3)+['side_swept_fringe']*3
        add('ordered_crown_flow_'+str(i),points,widths,depths,normals,parents,'hair_light' if i%7==1 else 'hair')
        # The crown ends internally. Its last three calculated guide segments are not emitted as a fringe.
        guides=ops[-3:];del ops[-3:]
        bridge_to=min(count,max(count-3,math.ceil((1.35+1.10)/(1.35-front_end)*count)))
        extra=bridge_to-(count-3)
        for j,guide in enumerate(guides[:extra]):
            guide['name']=f'r10_crown_internal_bridge_{i}_{j}';guide['parent']='crown_layers'
            # Same curved route; deep positive volume meets side roots, outer surface stays .08 inside the old cap.
            guide['from'][2]=round(guide['origin'][2]-.30,6);guide['to'][2]=round(guide['origin'][2]+.30,6)
            inward=helper.unit(normals[count-3+j])
            for key in ('from','to','origin'):guide[key]=[round(v-.18*inward[q],6) for q,v in enumerate(guide[key])]
            ops.append(guide)
        routes[-1].update(emitted_segments=count-3,internal_continuation_segments=extra)
    # Independently arranged neighboring frontal locks share an off-center part, then sweep +X to -X.
    for j in range(12):
        t=j/11;root_x=1.0+.045*j;root_z=1.4-.26*j
        root_y=25.25+3.65*math.sqrt(max(.1,1-(root_x/4.45)**2-((root_z-.1)/4)**2))-.16
        points=[[root_x,root_y,root_z],[.30+1.30*t,28.25-.25*t,-1.50-.50*t],
            [-1.40+2.80*t,27.10+.10*t,-3.45-.05*t],[-2.50+3.65*t,26.0+.60*t,-3.83-.07*t],
            [-3.20+4.18*t,25.05+.88*t,-3.73-.27*t]]
        normals=[]
        for k,(a,b) in enumerate(zip(points,points[1:])):
            m=[(a[q]+b[q])/2 for q in range(3)]
            normals.append([m[0]/4.45**2,(m[1]-25.25)/3.65**2,(m[2]-.1)/4**2] if k<2 else [0,.10,-1])
        add('manual_main_sweep_'+str(j),points,[.50,.62,.58,.42],[.35,.38,.35,.25],normals,'side_swept_fringe',
            'hair_light' if j in (3,9) else 'hair')
        # The first long guide is wholly inside the cap and is not a visible hair strand.
        del ops[-4];routes[-1].update(omitted_root_guide_segments=1)
    for j in range(5):
        points=[[1.55+.30*j,28.50-.16*j,-1.10+.16*j],[1.65+.36*j,27.20-.10*j,-2.80+.12*j],
            [1.60+.40*j,26.20-.08*j,-3.85+.13*j],[1.60+.42*j,25.10+.15*j,-3.92+.13*j]]
        add('manual_short_side_'+str(j),points,[.56,.55,.36],[.38,.34,.24],[0,.12,-1],'side_swept_fringe','hair')
        del ops[-3:-2];routes[-1].update(omitted_root_guide_segments=1)
    # One complete buried pink support closes the outer-sweep forehead hole without external patches.
    ops.append(box('fringe_under_support_right',[-2.67,26.25,-3.45],[1.12,1.45,.42],'hair','side_swept_fringe'))
    ops.append(box('fringe_under_support_left',[2.67,26.25,-3.45],[1.12,1.45,.42],'hair','side_swept_fringe'))
    # Twenty-one back locks form a covered, gently inward drape with no repeated horizontal cutoff row.
    for i in range(21):
        s=-3.6+i*.36;r=math.sqrt(max(.1,1-(s/4.2)**2));root_y=26.7+.8*r;root_z=.1+3.5*r
        end=10.6+.50*(abs(s)/3.6)+.16*(i%3)
        ys=[root_y,24.0+.12*s,21.3+.08*s,18.0,14.6,end]
        if abs(s)<1.1:ys.insert(4,16.2)
        points=[]
        for j,y in enumerate(ys):
            t=(root_y-y)/(root_y-end);z=2.20+1.2/(1+math.exp(-(y-22.7)/1.3))
            z+=(root_z-(2.20+1.2/(1+math.exp(-(root_y-22.7)/1.3))))*math.exp(-((root_y-y)/2.3)**2)
            x=s*(1+.055*math.sin(t*math.pi))-.10*(1 if s>0 else -1)*t*t
            points.append([round(x,5),round(y,5),round(z,5)])
        a,b=points[-2:];tip=.76+.08*(i%3);fraction=1-tip/math.dist(a,b)
        points.insert(len(points)-1,[round(a[k]+(b[k]-a[k])*fraction,5) for k in range(3)])
        widths=[.58]*(len(points)-1);widths[-2:]=[.46,.27]
        depths=[.48]*(len(points)-1);depths[-2:]=[.36,.23]
        add('ordered_back_'+str(i),points,widths,depths,[0,0,1],'rear_long_layers','hair_low' if i%7==0 else 'hair')
    for side,sign in (('left',1),('right',-1)):
        # Four connected front locks make a smooth cheek frame; farther locks remain behind them.
        for j in range(4):
            points=[[3.0+.16*j,27.10-.12*j,-2.35+.19*j],[2.95+.18*j,25.85-.05*j,-3.65+.18*j],
                [2.70+.22*j,23.80,-3.96+.16*j],[2.45+.23*j,22.05,-4.01+.15*j],
                [2.42+.25*j,20.70,-3.94+.16*j],[2.70+.27*j,19.35,-3.68+.16*j],
                [3.02+.28*j,18.0,-3.30+.16*j],[3.32+.28*j,16.8-.16*j,-2.85+.16*j]]
            points=[[sign*x,y,z] for x,y,z in points]
            add(f'ordered_cheek_{side}_{j}',points,[.55,.48,.48,.44,.40,.34,.26],[.47,.40,.36,.34,.31,.27,.21],[0,0,-1],
                'cheek_and_temple_locks','hair_light' if j==1 else 'hair')
        for j in range(13):
            root_z=-2.90+.48*j-(.12 if j==0 else 0);end=14.3+.15*j
            points=[[3.25,27.05,root_z],[3.70,25.10,root_z*.96],[3.96,22.40,root_z*.88],
                [4.38,20.0,root_z*.80],[4.48,17.35,root_z*.75],[4.10,end,root_z*.68]]
            # The frontmost two locks wrap the shoulder toward the blouse, the rest remain side/back.
            if j<2:
                for k in range(2,len(points)):points[k][2]-=.40
            points=[[sign*x,y,z] for x,y,z in points]
            widths=[.98,.96,.94,.67,.43] if j==0 else [.84,.83,.81,.67,.43] if j==1 else [.76,.75,.73,.67,.43]
            add(f'ordered_side_{side}_{j}',points,widths,[.62,.60,.56,.47,.29],[sign,0,.1],
                'side_long_layers','hair_low' if j==12 else 'hair')
    save('original-hair-routes.json',routes);return ops

def face_operations():
    ops=[]
    def add(*args,**kwargs):ops.append(box(*args,**kwargs))
    # A continuous skin panel with a short, shallow lower closure; no horizontal skin slicing.
    add('face_inner_support',[0,23.77,-.02],[4.9,5.15,4.85],'skin','face_skin_structure')
    add('face_continuous_main',[0,23.72,-2.90],[5.90,5.00,1.00],'skin','face_skin_structure')
    add('face_chin_short',[0,20.98,-2.86],[4.10,.55,1.05],'skin','face_skin_structure')
    add('face_jaw_left',[2.34,21.19,-2.87],[1.25,.62,1.04],'skin','face_skin_structure',[0,0,16])
    add('face_jaw_right',[-2.34,21.19,-2.87],[1.25,.62,1.04],'skin','face_skin_structure',[0,0,-16])
    add('internal_connector',[0,21.04,-.1],[2.5,.70,2.7],'skin','face_skin_structure')
    for side,sign in (('left',1),('right',-1)):
        cx=sign*1.47;p='eye_geometry_'+side
        # Broad connected eye-white pieces, not an old 20x16 per-pixel round-disc grid.
        add('eye_white_mid_'+side,[cx,23.71,-3.424],[1.58,.84,.036],'white',p)
        add('eye_white_upper_'+side,[cx+sign*.015,24.19,-3.424],[1.28,.18,.036],'white',p)
        add('eye_white_lower_'+side,[cx,23.21,-3.424],[1.04,.16,.036],'white',p)
        for dx in (-.79,.79):add(f'eye_corner_white_{side}_{dx}',[cx+dx,23.80,-3.424],[.14,.32,.036],'white',p)
        # A vertical iris whose upper edge is clipped by the eyelid, with a broad short pupil.
        add('iris_main_'+side,[cx,23.73,-3.454],[.84,1.00,.022],'iris',p)
        add('iris_top_'+side,[cx,24.23,-3.454],[.64,.16,.022],'iris_top',p)
        add('iris_bottom_'+side,[cx,23.155,-3.454],[.55,.15,.022],'iris_bottom',p)
        add('iris_upper_shade_'+side,[cx,24.07,-3.476],[.84,.31,.018],'iris_top',p)
        add('iris_lower_light_'+side,[cx,23.41,-3.476],[.73,.26,.018],'iris_bottom',p)
        add('pupil_short_'+side,[cx,23.82,-3.496],[.25,.60,.018],'pupil',p)
        add('highlight_upper_'+side,[cx+.17,24.07,-3.516],[.145,.145,.014],'shine',p)
        add('highlight_lower_'+side,[cx-.17,23.26,-3.496],[.07,.07,.014],'shine',p)
        # Six contiguous short eyelid steps with a gentle arch and a lightly weighted outer corner.
        for j,level in enumerate((-.01,.07,.12,.12,.07,0)):
            dx=(j-2.5)*.275;y=24.20+level
            add(f'upper_lid_{side}_{j}',[cx+sign*dx,y,-3.490],[.282,.075 if j<5 else .105,.024],'lash',p)
        add('outer_lid_corner_'+side,[cx+sign*.875,24.12,-3.490],[.13,.12,.024],'lash',p)
        # The lower lid is left light; one short skin-toned transition does not form a dark ring.
        for j,level in enumerate((0,.02,.035,.030,.01,-.005)):
            add(f'brow_{side}_{j}',[cx+sign*(j-2.5)*.175,24.64+level,-3.43],[.178,.043,.026],'brow','brow_geometry')
    add('mouth_single_small_rectangle',[0,22.34,-3.417],[.42,.057,.018],'mouth','smile_geometry')
    return ops

def prepare():
    WORK.mkdir(parents=True,exist_ok=True)
    if (WORK/MODEL).exists():raise SystemExit('Refuse overwrite of an existing model. Use --rebuild-upstream for a separate reproduction directory.')
    (WORK/'plan.md').write_text('# 爱音R10：整张脸与有序根梢发束\n\n用户明确否定R9脸/眉眼和杂乱、视觉稀疏的头发。已亲看本轮清晰设定、R9实际Blockbench近脸和R63方法截图；只学连续承托与正厚度块组织，不复制参考资产。\n\n新正脸宽5.9、高5的连续前肤面，底部少量浅短颌收口，五官随新脸重新排列；双眼由连贯眼白、轻缓上睑、纵向蓝灰虹膜和短瞳芯组成，不再用旧127块/眼圆盘网格。黑眉随眼窝轻缓连接，小嘴严格1个浅小横矩形。内部连接藏在领口，保持无外露颈。\n\n25条相邻顶发路线只负责圆顶围合与内部连续承接；额前独立12条主扫和5条短侧，从+X偏分向-X展开，不把顶盖经线统一延成齐帘。分点在+X侧，主要额发沿-X顺扫；每条路线次序连续，宽薄有规律变化，转折采用短重叠埋接头。21后长束顺肩背，左右各4条包脸束和13条肩侧长束，避免随机交叉碎片/等长针梢。不靠放大头或单纯总块数代替造型。\n\n只从自己R9复制新工程，原51非head元素、全部19组/父变换/pivot、原8atlas/resolution逐字段冻结。全部新head几何通过原生上游MCP+expected_revision写入，源仅生成参数，不直接写最终bbmodel JSON。先实际脸/头正侧背/3q/192给根看，再集中修。0mesh、self_test=false，不测试/Java/游戏/构建/安装/自动化/上传，不扩七人。\n',encoding='utf-8')
    shutil.copy2(BASE,WORK/MODEL);doc=json.loads(BASE.read_text(encoding='utf-8'))
    atlas=Image.new('RGBA',(64,64),(0,0,0,0));draw=ImageDraw.Draw(atlas)
    for i,color in enumerate(COLORS.values()):draw.rectangle((i*4,0,i*4+3,3),fill='#'+color)
    atlas.save(WORK/'anon-r10-original-palette.png')
    changes=[{'op':'remove_node','target':u} for u in sorted(ids_in(doc,{'head'}))]+face_operations()+hair_operations()+[{'op':'set_model_properties','name':'千早爱音｜R10整脸与有序发束原型'}]
    save('native-operations.json',changes);save('native-operation-batches.json',{'operations':len(changes),'batches':math.ceil(len(changes)/450)})
    print(json.dumps({'operations':len(changes),'batches':math.ceil(len(changes)/450)}),flush=True)

def freeze():
    before=json.loads(BASE.read_text(encoding='utf-8'));after=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    def select(doc):
        head=ids_in(doc,{'head'});names={g['uuid']:g['name'] for g in doc['groups']}
        def tree(nodes):return [n if isinstance(n,str) else {**n,'children':[] if names[n['uuid']]=='head' else tree(n.get('children',[]))} for n in nodes]
        return {'nonhead':[e for e in doc['elements'] if e['uuid'] not in head],'groups':doc['groups'],'outliner':tree(doc['outliner']),'textures':doc['textures'][:8],'resolution':doc['resolution']}
    def skeleton(nodes):return [dict(n,children=skeleton(n.get('children',[]))) for n in nodes if isinstance(n,dict)]
    hierarchy=skeleton(before['outliner'])==skeleton(after['outliner'])
    a,b=select(before),select(after);result={'identical':a==b and hierarchy,'frozen_nonhead_elements':len(a['nonhead']),'all_original_groups_pivots_parent_hierarchy_frozen':a['groups']==b['groups'] and hierarchy,
        'original_eight_atlases_identical':before['textures']==after['textures'][:8],'resolution_identical':before['resolution']==after['resolution'],
        'baseline_sha256':hashlib.sha256(BASE.read_bytes()).hexdigest(),'model_sha256':hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest()}
    save('frozen-r9-record.json',result)
    if not result['identical']:raise SystemExit('R9 nonhead/group/atlas freeze failed.')
    return result

def inspection(label,source,scope):
    file=f'{label}-{scope}-inspection.bbmodel';shutil.copy2(source,WORK/file);info=mcp('bbmodel_info',{'file':file},label+'-'+scope+'-before')
    changes=[]
    if scope in ('face','head'):changes=[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'}]
    if scope=='face':
        # Keep actual temple/side/rear coverage. Remove only the lower lengths for face-camera framing.
        doc=json.loads((WORK/file).read_text(encoding='utf-8'))
        hair=ids_in(doc,{'rear_long_layers','side_long_layers','cheek_and_temple_locks'})
        changes.extend({'op':'remove_node','target':e['uuid']} for e in doc['elements'] if e['uuid'] in hair and e.get('origin',[0,100,0])[1]<21.0)
    low=18 if scope=='face' else 9 if scope=='head' else 0
    for j,(a,b) in enumerate((([-7.5,low,-6],[-7.48,low+.02,-5.98]),([7.48,29.98,6.48],[7.5,30,6.5]))):
        changes.append({'op':'add_cube','name':f'transparent_bounds_{j}','from':a,'to':b,'parent':'head' if scope!='full' else 'root','texture':3,'box_uv':False,
            'faces':{f:{'uv':[40.25,40.25,40.75,40.75],'texture':3} for f in ('north','south','east','west','up','down')}})
    mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':changes},label+'-'+scope+'-framing')
    return file,mcp('bbmodel_info',{'file':file},label+'-'+scope+'-info')['bounds']

def finish():
    frozen=freeze();info=mcp('bbmodel_info',{'file':MODEL},'r10-main-info');static=mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r10-static')
    save('facts.json',{'status':'R10新整脸/有序发束首稿，待根集中审阅','counts':info['counts'],'bounds':info['bounds'],'model_sha256':frozen['model_sha256'],'freeze':frozen,'static':static['summary'],'self_test':False,'author_viewed_images':False,'user_acceptance':'pending'})
    face,_=inspection('r10',WORK/MODEL,'face')
    for view,light in (('front','flat'),('front','studio'),('three-quarter','studio')):helper.render(face,view,f'r10-face-{view}-{light}.png',768,f'face-{view}-{light}',light)
    helper.render(MODEL,'three-quarter','r10-normal-192.png',192,'normal-192')
    helper.render(MODEL,'three-quarter','r10-full-three-quarter-studio.png',640,'full-three-quarter-studio','studio')
    helper.render(MODEL,'front','r10-full-front-studio.png',640,'full-front-studio','studio')
    head,_=inspection('r10',WORK/MODEL,'head')
    for view in ('front','right','left','back'):helper.render(head,view,f'r10-head-{view}.png',768,'head-'+view)
    helper.render(head,'three-quarter','r10-head-three-quarter-studio.png',768,'head-three-quarter-studio','studio')
    print(json.dumps({'sha256':frozen['model_sha256'],'counts':info['counts'],'static':static['summary']}),flush=True)

def prepare_refinement():
    first=WORK/'first-pass';first.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/'facts.json',*WORK.glob('r10-*.png')]:
        if path.exists():shutil.copy2(path,first/path.name)
    shutil.copytree(WORK/'receipts',first/'receipts',dirs_exist_ok=True)
    doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    changes=[{'op':'remove_node','target':u} for u in sorted(ids_in(doc,{'head'}))]+face_operations()+hair_operations()
    save('native-refinement-operations.json',changes);save('native-refinement-batches.json',{'operations':len(changes),'batches':math.ceil(len(changes)/450)})
    save('concentrated-refinement-plan.json',{'issues':['visible side skin support','front brim and blunt fringe','cheek locks kinked at jaw','flat sleepy eyes'],
        'changes':['cap and fringe now one ellipsoid stream through the front vertical tangent, no independent lower shelf','main locks continue obliquely; short side ends higher','side z coverage expands -2.9..2.86 with thick adjacent roots','cheek bends distributed over seven connected segments','eyes and vertical iris height raised together, lower white strip reduced, brows longer and nearer']})
    print(json.dumps({'operations':len(changes),'batches':math.ceil(len(changes)/450)}),flush=True)

def prepare_fringe_refinement():
    previous=WORK/'second-pass';previous.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/'facts.json',*WORK.glob('r10-*.png')]:
        if path.exists():shutil.copy2(path,previous/path.name)
    doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    generated=hair_operations()
    changes=[{'op':'remove_node','target':u} for u in sorted(ids_in(doc,{'side_swept_fringe'}))]
    changes.extend(op for op in generated if op['parent']=='side_swept_fringe')
    for op in generated:
        name=op['name']
        if any(name.startswith(f'r10_ordered_side_{side}_{j}_') for side in ('left','right') for j in (0,1)):
            changes.append({'op':'update_node','target':name,**{k:op[k] for k in ('from','to','origin','rotation','faces')}})
    save('native-fringe-operations.json',changes)
    save('fringe-refinement-record.json',{'scope':'independent 12 main sweep locks + 5 short side locks; existing crown retained internally, no crown meridian fringe',
        'coverage':'frontmost two existing side root layers widened; front edge moved .12 toward face, lower/back overall drape retained',
        'eyes':'second-pass eye structure retained exactly','nonhead':'all51 and all groups/pivots/original8 atlas still frozen'})
    print(json.dumps({'operations':len(changes),'batches':math.ceil(len(changes)/450)}),flush=True)

def prepare_root_cleanup():
    old=WORK/'third-pass';old.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/'facts.json',*WORK.glob('r10-*.png')]:
        if path.exists():shutil.copy2(path,old/path.name)
    doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'));generated=hair_operations()
    changes=[{'op':'remove_node','target':u} for u in sorted(ids_in(doc,{'side_swept_fringe'}))]
    changes.extend(op for op in generated if op['parent']=='side_swept_fringe')
    save('native-root-cleanup-operations.json',changes)
    save('root-cleanup-record.json',{'scope':'remove hidden/overlapping first guide bars, retract short-side roots into cap, one complete pink under-support beneath outer main sweep',
        'unchanged':'new face/eyes/brows/mouth, crown, side/back long hair, R9 nonhead51 and all original groups/pivots/8 atlases'})
    print(json.dumps({'operations':len(changes),'batches':math.ceil(len(changes)/450)}),flush=True)

def prepare_connection_cleanup():
    previous=WORK/'before-connection-cleanup';previous.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/'facts.json',*WORK.glob('r10-face-*.png')]:
        if path.exists():shutil.copy2(path,previous/path.name)
    changes=[op for op in hair_operations() if op['name'].startswith('r10_crown_internal_bridge_')]
    save('native-connection-operations.json',changes)
    save('connection-cleanup-record.json',{'scope':'internal continuations follow each existing crown route to theta about -1.1; depth .60 and inward .18 give an almost flush joining surface, with overlapping side roots',
        'crossing_root_bars':'17 removed guide bars remain absent','unchanged':'face/eyes/brows/mouth, independent sweep tips, shoulder/back drape, all51 nonhead/all groups/pivots/old8atlas'})
    print(json.dumps({'operations':len(changes)}),flush=True)

def face_preview():
    frozen=freeze();info=mcp('bbmodel_info',{'file':MODEL},'r10-main-info');static=mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'r10-static')
    save('facts.json',{'status':'R10 root connection local preview, pending root review','counts':info['counts'],'bounds':info['bounds'],'model_sha256':frozen['model_sha256'],'freeze':frozen,'static':static['summary'],'self_test':False,'author_viewed_images':False,'user_acceptance':'pending'})
    face,_=inspection('r10',WORK/MODEL,'face')
    for view in ('front','three-quarter'):helper.render(face,view,f'r10-face-{view}-studio.png',768,'connection-face-'+view,'studio')
    print(json.dumps({'sha256':frozen['model_sha256'],'counts':info['counts'],'static':static['summary']}),flush=True)

def final_comparisons():
    bounds={}
    for scope in ('face','head'):
        for label,source in (('r9',BASE),('r10',WORK/MODEL)):
            file,bounds[label+'_'+scope]=inspection(label,source,scope)
            for view in ('front','three-quarter'):
                helper.render(file,view,f'{label}-{scope}-matched-{view}-studio.png',768,f'compare-{label}-{scope}-{view}','studio')
        bounds[scope+'_identical']=bounds['r9_'+scope]==bounds['r10_'+scope]
        for view in ('front','three-quarter'):
            canvas=Image.new('RGB',(1536,816),'#202329');draw=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
            for x,label,text in ((0,'r9','R9：前一版（脸/发已被反馈否定）'),(768,'r10','R10：新脸与有序发束，待人工验收')):
                draw.text((x+12,10),text,font=font,fill='white')
                with Image.open(WORK/f'{label}-{scope}-matched-{view}-studio.png') as im:canvas.paste(im.convert('RGB'),(x,48))
            canvas.save(WORK/f'r9-r10-{scope}-{view}-comparison.png')
    save('comparison-camera-record.json',bounds)
    if not all(bounds[k] for k in ('face_identical','head_identical')):raise SystemExit('Comparison world bounds differ.')

def finalize_records():
    frozen=freeze();doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    info=json.loads(json.loads((WORK/'receipts/r10-main-info.json').read_text(encoding='utf-8'))['stdout'])
    static=json.loads(json.loads((WORK/'receipts/r10-static.json').read_text(encoding='utf-8'))['stdout'])
    names={g['uuid']:g['name'] for g in doc['groups']};counts={}
    def walk(nodes,parent=''):
        for n in nodes:
            if isinstance(n,str):counts[parent]=counts.get(parent,0)+1
            else:walk(n.get('children',[]),names[n['uuid']])
    walk(doc['outliner'])
    receipts=[]
    for path in WORK.glob('native-*-receipt.json'):
        value=json.loads(path.read_text(encoding='utf-8-sig'))
        if 'expected_revision' in value and 'revision' in value:receipts.append({'receipt_file':path.name,**value})
    ordered=[];current=frozen['baseline_sha256'][:16]
    while True:
        match=next((r for r in receipts if r['expected_revision']==current),None)
        if match is None:break
        ordered.append(match);receipts.remove(match);current=match['revision']
    save('native-write-ledger.json',{'transport':'native upstream MCP tools','baseline_revision':frozen['baseline_sha256'][:16],
        'final_revision':current,'chain_complete':current==frozen['model_sha256'][:16],'writes':ordered,
        'receipt_form':'exact compact revision/operation-range results plus complete operation request JSON files; raw inspection/static/render stdout/stderr in receipts/'})
    facts={'status':'R10主工程几何冻结，待人工外观验收','model_sha256':frozen['model_sha256'],'counts':info['counts'],'bounds':info['bounds'],
        'group_cube_counts':counts,'freeze':frozen,'static':static['summary'],
        'static_gates':[{'id':g['id'],'ran':g['ran'],'count':len(g['violations'])} for g in static['gates']],
        'self_test':False,'author_viewed_images':True,'root_review':'root viewed final front/three-quarter face after root connection fill; freeze ba44 revision; earlier whole-head angles viewed during review',
        'official_editor_review':{'status':'root reopened final ba44 file in official Blockbench; viewed full three-quarter, close three-quarter and North front',
            'screenshots':['../../anon-face-hair-r10-full-blockbench.png','../../anon-face-hair-r10-close-blockbench.png','../../anon-face-hair-r10-face-blockbench.png'],
            'independent_audit':'../../anon-face-hair-r10-root-audit.json'},'user_acceptance':'pending; no likeness score or overall approval',
        'native_write_chain_complete':current==frozen['model_sha256'][:16],'all_standard_images_refreshed_from_final_revision':True,
        'remaining_limits':'native cuboid edge steps, local hair root facets and long-lock planes remain visible; skin, eye shape and likeness remain for human judgment',
        'face_inspection':'keeps all side/rear upper roots; removes only hair pieces with centerY<21 for framing, no skin gaps manufactured by deleting entire groups',
        'main_has_transparent_camera_anchors':False,'comparison_size':[1536,816]}
    save('facts.json',facts);save('final-facts.json',facts)
    current_plan='# 爱音R10最终依据与落点\n\nR9脸/眉眼与杂乱头发已被人类否定。本轮仅爱音，整张脸/全部头发重构；非head51件、全部19组/父层级/旋转/pivot、原8atlas和resolution逐字段冻结。\n\n连续主肤面与少量浅短颌重新校准。眼白为连贯少量长方体，纵向蓝灰虹膜/短瞳芯/同方向高光，黑眉轻缓，小嘴严格1件浅小横矩形；无外露脖子。没有复用旧127块/眼网格。\n\n顶盖25组路线只负责圆顶围合和内部连续承接，不能全延成齐帘。额前独立12主扫+5短侧从+X偏分向-X展开；移去17条交叉导向首段，完整粉发承托遮额角孔，39段内部曲线延续补齐顶盖—主扫/鬓根的U形断沟。左右各4条连贯颊侧束及13条侧长束，21组后发保持饱满顺势；未用随机碎片填缝。\n\n原生MCP全部写入带expected_revision，当前ba44几何冻结。所有最新head/full/face/192和R9共同世界边界对照都从最终工程刷新。仍有原生块接边/局部台阶，待人工外观验收，不以静态或数量评分替代。0mesh、self_test=false，无测试/Java/游戏/构建/安装/自动化/上传。\n'
    (WORK/'plan.md').write_text(current_plan,encoding='utf-8')
    (WORK/'README.md').write_text('# 爱音R10：新脸与有序发束（主几何冻结）\n\n当前供人工外观验收，不代表整人物或相似度通过；其他七人未同步。\n\n- 主工程：[anon-face-hair-r10.bbmodel](anon-face-hair-r10.bbmodel)，722cube/19group/9atlas/0mesh/0animation。新脸与眼眉口全部原创正厚长方体；嘴严格1件。\n- 顶盖内部接根，额前为独立偏分主扫和短侧，screen-left=+X、主扫向-X。侧后长发更密且路径有序；额角肤孔、大片侧肤与U形断开的根带已实际集中修。原生块边/局部根段台阶与长束平面仍可见，不称光滑或相似度通过。\n- R9非head51件、全部原组/父层级/旋转/pivot、原8atlas/resolution完全冻结，见[frozen-r9-record.json](frozen-r9-record.json)。本轮脸/眼/发型允许重做，未伪称沿用R9脸冻结。无外露颈。\n\n## 最终同一revision真实图\n\n- 脸：[正面flat](r10-face-front-flat.png)、[正面studio](r10-face-front-studio.png)、[前侧studio](r10-face-three-quarter-studio.png)。检查副本保留真实侧根/后根覆盖，仅剔除中心y<21的下段发量用于取景。\n- 完整头部：[正](r10-head-front.png)、[右](r10-head-right.png)、[左](r10-head-left.png)、[后](r10-head-back.png)、[前侧studio](r10-head-three-quarter-studio.png)。\n- 完整人物：[正面studio](r10-full-front-studio.png)、[前侧studio](r10-full-three-quarter-studio.png)、[192常规](r10-normal-192.png)。这些head/full/192已从ba44最终revision全部刷新，不能混用历史图。\n- R9/R10共同世界边界：[脸正面](r9-r10-face-front-comparison.png)、[脸前侧](r9-r10-face-three-quarter-comparison.png)、[完整头正面](r9-r10-head-front-comparison.png)、[完整头前侧](r9-r10-head-three-quarter-comparison.png)，1536×816、两边均studio。真实MCP检查副本使用透明取景锚点，主工程无锚点；只排布原始渲染，无重画/美化。边界见comparison-camera-record.json。\n\n## 回执、检查与历史\n\n所有主工程写入使用当前原生上游MCP+expected_revision。native-write-ledger.json汇总完整revision链，各native-*-receipt.json保留精确revision/操作范围；native-operations.json及各refinement/fringe/root/under-support/connection请求文件为完整操作参数。静态/检查副本/渲染沿同一原作者stdio MCP，原stdout/stderr在receipts/。静态0错误/301警告：35薄件/浮离、262覆盖、4非镜像，原始分类在final-facts.json；self_test=false。未运行测试或游戏/Java/构建/安装/上传/自动化。\n\nfirst-pass/、second-pass/、third-pass/、before-connection-cleanup/是历史问题稿，不是最终图。首稿有侧肤板/扁眼/齐帘，后续根部阶段曾有U形断沟，均未宣称通过。根已亲看最终连接补齐后的近景并要求冻结，随后在官方Blockbench重新本地打开同一ba44工程，看全身3q、近脸3q和North正脸：[全身实景](../../anon-face-hair-r10-full-blockbench.png)、[近景实景](../../anon-face-hair-r10-close-blockbench.png)、[正脸实景](../../anon-face-hair-r10-face-blockbench.png)。[根独立审计](../../anon-face-hair-r10-root-audit.json)核对51非head/全部组父变换/8原atlas/resolution及R9和参考原SHA保持；用户外观仍待判断。\n\n## 可重现制作\n\n先真实读取全部额度，普通可用且每窗口余量>1%。源在art/sources/robot_anon_face_hair_r10.py，仅生成原创参数和PNG，所有模型操作经MCP。新工作目录中运行--prepare，原生bbmodel_info取revision，bbmodel_add_texture加入anon-r10-original-palette.png，再对native-operations.json每450项bbmodel_edit、逐批重新info并传expected_revision；--finish执行静态self_test=false和真实渲染。当前冻结主工程不应复跑覆盖。\n\n可用`python art/sources/robot_anon_face_hair_r10.py --quota-checked --rebuild-upstream`在本目录独立reproduction/用已安装原作者stdio客户端重建同一最终几何，不覆盖冻结主工程；若该目录已有模型会拒绝覆盖。新UUID/revision会不同，不能要求二进制hash相同。此入口未为演示执行，不新增测试或安装。\n\n最终SHA256：`'+frozen['model_sha256']+'`\n',encoding='utf-8')
    print(json.dumps({'final_sha256':frozen['model_sha256'],'native_chain_complete':current==frozen['model_sha256'][:16],'writes':len(ordered),'frozen':True}),flush=True)

def rebuild_upstream():
    global WORK
    WORK=WORK/'reproduction';helper.WORK=WORK
    prepare();info=mcp('bbmodel_info',{'file':MODEL},'rebuild-before-palette')
    mcp('bbmodel_add_texture',{'file':MODEL,'image':str(WORK/'anon-r10-original-palette.png'),'name':'anon_original_r10_palette','expected_revision':info['revision']},'rebuild-palette')
    changes=json.loads((WORK/'native-operations.json').read_text(encoding='utf-8'))
    for part in range(0,len(changes),450):
        info=mcp('bbmodel_info',{'file':MODEL},f'rebuild-before-{part//450}')
        mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':changes[part:part+450]},f'rebuild-batch-{part//450}')
    finish()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--quota-checked',action='store_true');p.add_argument('--prepare',action='store_true');p.add_argument('--prepare-refinement',action='store_true');p.add_argument('--prepare-fringe-refinement',action='store_true');p.add_argument('--prepare-root-cleanup',action='store_true');p.add_argument('--prepare-connection-cleanup',action='store_true');p.add_argument('--face-preview',action='store_true');p.add_argument('--finish',action='store_true');p.add_argument('--comparisons',action='store_true');p.add_argument('--finalize',action='store_true');p.add_argument('--rebuild-upstream',action='store_true');a=p.parse_args()
    if not a.quota_checked:raise SystemExit('Read real quota before source work.')
    if a.rebuild_upstream:rebuild_upstream()
    elif a.finalize:finalize_records()
    elif a.comparisons:final_comparisons()
    elif a.face_preview:face_preview()
    elif a.prepare_connection_cleanup:prepare_connection_cleanup()
    elif a.prepare_root_cleanup:prepare_root_cleanup()
    elif a.prepare_fringe_refinement:prepare_fringe_refinement()
    elif a.prepare_refinement:prepare_refinement()
    elif a.prepare:prepare()
    elif a.finish:finish()
