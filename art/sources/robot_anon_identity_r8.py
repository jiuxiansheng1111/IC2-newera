"""Original R8 side-part, cheek locks and almond eyes, written through upstream MCP."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,math,shutil
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'art/robot-mcp-workspace/characters/anon-identity-r8'
BASE=WORK.parent/'anon-crown-drape-r7/anon-crown-drape-r7.bbmodel'
MODEL='anon-identity-r8.bbmodel'
HAIR={'crown_layers','side_swept_fringe','cheek_and_temple_locks','rear_long_layers','side_long_layers','hair_clips'}
EYES={'eye_geometry_left','eye_geometry_right'}
COLORS={'L':'433741','W':'FFF7F0','D':'637C8C','B':'98ADB6','A':'BECED0','P':'4B6270','R':'7F99A8','H':'FFFCF7'}
spec=importlib.util.spec_from_file_location('own_mcp_helper',ROOT/'art/sources/robot_layered_head.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
helper.WORK=WORK;helper.MODEL=MODEL
mcp=helper.mcp

def save(name,value):
    (WORK/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def ids_in(doc,chosen):
    names={g['uuid']:g['name'] for g in doc['groups']};ids=set()
    def walk(nodes,inside=False):
        for node in nodes:
            if isinstance(node,str):
                if inside:ids.add(node)
            else:walk(node.get('children',[]),inside or names[node['uuid']] in chosen)
    walk(doc['outliner']);return ids

def selected_ids(doc):
    ids=ids_in(doc,EYES|{'side_swept_fringe','hair_clips'})
    ids.update(e['uuid'] for e in doc['elements'] if e['name'].startswith(('r5_cheek_inner_','r5_cheek_fine_')))
    if (WORK/'local-front-edge-scope.json').exists():
        names=set(json.loads((WORK/'local-front-edge-scope.json').read_text(encoding='utf-8'))['changed_original_names'])
        ids.update(e['uuid'] for e in doc['elements'] if e['name'] in names)
    return ids

def freeze():
    before=json.loads(BASE.read_text(encoding='utf-8'));after=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    mutable=ids_in(before,HAIR|EYES);byid={e['uuid']:e for e in after['elements']}
    fixed=[e for e in before['elements'] if e['uuid'] not in mutable]
    preserved=[e for e in before['elements'] if e['uuid'] not in selected_ids(before)]
    result={'baseline_sha256':hashlib.sha256(BASE.read_bytes()).hexdigest(),'model_sha256':hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),
        'frozen_elements':len(fixed),'frozen_elements_identical':all(byid.get(e['uuid'])==e for e in fixed),
        'additional_preserved_elements':len(preserved),'additional_preserved_identical':all(byid.get(e['uuid'])==e for e in preserved),
        'original_six_atlases_identical':before['textures']==after['textures'][:6],
        'resolution_identical':before['resolution']==after['resolution'],'all_original_groups_identical':before['groups']==after['groups'],
        'scope':'body44 + continuous skin6 + single rectangular mouth1 + black brows20; crown except recorded nine buried front-edge segments, back and shoulder drape unchanged',
        'reference_only':'user sheet observed; no reference geometry/UV/texture/animation copied'}
    # Parent transforms and original group placement stay intact, independent of edited children.
    def skeleton(nodes):return [dict(n,children=skeleton(n.get('children',[]))) for n in nodes if isinstance(n,dict)]
    result['group_hierarchy_identical']=skeleton(before['outliner'])==skeleton(after['outliner'])
    save('frozen-r7-record.json',result)
    if not all(result[k] for k in ('frozen_elements_identical','additional_preserved_identical','original_six_atlases_identical','resolution_identical','all_original_groups_identical','group_hierarchy_identical')):raise SystemExit('Frozen baseline fields changed; stop.')
    return result

def hair_operations():
    ops=[];routes=[]
    def strand(name,points,widths,depths,parent='side_swept_fringe',color='hair'):
        routes.append({'name':name,'points':points,'widths':widths,'depths':depths})
        for j,(a,b,w,d) in enumerate(zip(points,points[1:],widths,depths)):
            op=helper.segment('r8_'+name+'_'+str(j),a,b,w,d,[0,0,-1],parent,color,.11)
            for face in op['faces'].values():
                u,v,x,y=face['uv'];face['uv']=[u+.5,v+.5,x-.5,y-.5]
            ops.append(op)
    # Front camera looks toward +Z: screen-left is +X. The principal locks flow +X to -X.
    # Upper endpoints now start inside the preserved ellipsoid crown. Only the flowing lower lengths emerge.
    strand('sweep_a',[[1.1,32.25,-1.45],[0,31,-3.25],[-1.2,30,-3.83],[-2.1,29.2,-3.9]],[.76,.82,.58],[.34,.36,.32])
    strand('sweep_b',[[1.85,32,-1.35],[1,30.9,-3.20],[-.4,29.9,-3.88],[-1.8,29,-3.95]],[.82,.86,.62],[.34,.36,.32])
    strand('sweep_c',[[2.5,31.65,-1.35],[1.6,30.4,-3.35],[.4,29.5,-3.94],[-.8,29.05,-4]],[.78,.80,.55],[.34,.34,.29])
    strand('sweep_outer',[[-.2,31.8,-1.75],[-1.7,30.6,-3.28],[-2.8,29.2,-3.8],[-3.05,27.9,-3.7]],[.72,.66,.45],[.34,.36,.30])
    strand('short_left_a',[[1.2,32,-1.45],[1.65,30.5,-3.40],[1.95,29.3,-3.9]],[.60,.40],[.34,.32])
    strand('short_left_b',[[2.2,32,-1.10],[2.6,30.3,-3.38],[2.75,28.85,-3.8]],[.60,.45],[.34,.32])
    strand('short_left_c',[[1.25,31.7,-1.90],[1.1,30.2,-3.55],[1.2,29.15,-4]],[.42,.28],[.30,.26])
    for side,sign in (('left',1),('right',-1)):
        points=[[sign*x,y,z] for x,y,z in [[3.15,31.2,-2.8],[3.12,28.9,-3.65],[2.77,27.25,-3.88],[2.46,26.25,-3.90],[2.28,25.1,-3.82],[2.48,24.15,-3.5]]]
        strand('cheek_wrap_'+side,points,[1.35,.78,.66,.58,.36],[.80,.52,.44,.38,.25],'cheek_and_temple_locks')
    save('original-hair-routes.json',routes);return ops

def eye_grid(sign):
    upper=[round(2.8*(2*c/19-1-.15)**2+.9) for c in range(20)]
    lower=[7,9,10,11,12,13,13,14,14,14,14,14,14,13,13,12,11,10,9,7]
    grid={}
    for col in range(20):
        for row in range(upper[col],lower[col]+1):
            if row==upper[col] or (col>=16 and row==upper[col]+1):color='L'
            else:
                radius=((col-9.8)/6.5)**2+((row-7.4)/7.6)**2
                if radius<=1:
                    color='D' if row<=5 else 'B' if row<=9 else 'A'
                    if 9<=col<=11 and 6<=row<=10:color='P'
                else:
                    if row>=13 and 5<=col<=14:continue
                    color='W'
            grid[row,col]=color
    highlight=round(9.5+sign*1.8)
    for row in (4,5):
        for col in (highlight,highlight+sign):
            if (row,col) in grid and grid[row,col]!='L':grid[row,col]='H'
    low=round(9.5-sign*2)
    if (11,low) in grid:grid[11,low]='H'
    return grid

def eye_operations():
    ops=[];layouts={}
    for side,sign in (('left',1),('right',-1)):
        grid=eye_grid(sign);layouts[side]=[{'row':r,'col':c,'color':v} for (r,c),v in sorted(grid.items())]
        for row in range(16):
            col=0
            while col<20:
                color=grid.get((row,col))
                if color is None:col+=1;continue
                end=col+1
                if end<20 and grid.get((row,end))==color:end+=1
                x=sign*(1.53+((col+end-1)/2-9.5)*.085);y=28.13-(row+.5)*.085
                i=list(COLORS).index(color);uv=[i*4+.5,.5,i*4+3.5,3.5]
                ops.append({'op':'add_cube','name':f'r8_eye_{side}_{row:02}_{col:02}','from':[round(x-(end-col)*.085/2,6),round(y-.0425,6),-3.475],
                    'to':[round(x+(end-col)*.085/2,6),round(y+.0425,6),-3.447],'origin':[x,y,-3.461],'parent':'eye_geometry_'+side,'box_uv':False,'texture':6,
                    'faces':{f:{'uv':uv,'texture':6} for f in ('north','south','east','west','up','down')}});col=end
    save('original-eye-layout.json',layouts);return ops

def prepare():
    WORK.mkdir(parents=True,exist_ok=True)
    (WORK/'plan.md').write_text('# 爱音R8：偏分、包脸发束和眼型\n\n仅新目录制作，R7只读。已亲看本轮清晰设定；正面屏幕左=+X，分点在+X靠中，主刘海向-X顺扫，另一侧短束不齐。连续有厚度的内鬓发从眼外下方向嘴角外侧收进，外眼角保留空隙；旧无清晰依据双夹撤去。圆顶、披肩和瘦身服装保留。\n\n眼睛独立重画规整小长方体布局：低缓上睑弧、自然纵向蓝灰虹膜、顶部被睫遮一小段、短矩形瞳芯、同世界方向高光；无完整白圈、尖屋顶/针瞳。黑细眉20件原样。身体44、皮肤6、小矩形嘴1、眉20共71元素与原6atlas/全部组父变换不改，额外逐字段保留现冠顶和后侧长发。\n\n先真实MCP+expected_revision工程、正脸flat/studio、前侧、192及R7同取景；根亲看再必要集中修。0mesh，不复制资产，不运行测试/self_test、游戏/Java/构建/安装/心跳/上传。用户相似度待判断。\n',encoding='utf-8')

def build():
    prepare();shutil.copy2(BASE,WORK/MODEL);doc=json.loads(BASE.read_text(encoding='utf-8'))
    atlas=Image.new('RGBA',(64,64),(0,0,0,0));draw=ImageDraw.Draw(atlas)
    for i,color in enumerate(COLORS.values()):draw.rectangle((i*4,0,i*4+3,3),fill='#'+color)
    atlas.save(WORK/'anon-r8-eye-palette.png')
    info=mcp('bbmodel_info',{'file':MODEL},'01-before-palette')
    mcp('bbmodel_add_texture',{'file':MODEL,'image':str(WORK/'anon-r8-eye-palette.png'),'name':'anon_original_r8_eye_palette','expected_revision':info['revision']},'02-original-palette')
    changes=[{'op':'remove_node','target':u} for u in sorted(selected_ids(doc))]+hair_operations()+eye_operations()+[{'op':'set_model_properties','name':'千早爱音｜R8偏分包脸与眼型原型'}]
    for part in range(0,len(changes),450):
        info=mcp('bbmodel_info',{'file':MODEL},f'03-before-edit-{part//450}')
        mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':changes[part:part+450]},f'04-side-part-cheek-eyes-{part//450}')
    freeze();record();views()

def record():
    frozen=freeze();info=mcp('bbmodel_info',{'file':MODEL},'05-r8-info');static=mcp('bbmodel_validate',{'file':MODEL,'self_test':False,'min_thickness':.01},'06-r8-static')
    save('facts.json',{'status':'R8首稿，待根亲看与用户判断','model_sha256':frozen['model_sha256'],'counts':info['counts'],'freeze':frozen,'static':static['summary'],'self_test':False,'author_viewed_images':False,'root_review':'pending','user_acceptance':'pending'})
    print(json.dumps({'sha256':frozen['model_sha256'],'counts':info['counts'],'static':static['summary']}),flush=True)

def inspection(label,source,scope='face'):
    file=f'{label}-{scope}-matched-inspection.bbmodel';shutil.copy2(source,WORK/file)
    info=mcp('bbmodel_info',{'file':file},f'compare-{label}-{scope}-before')
    changes=[]
    if scope=='face':changes=[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'},{'op':'remove_node','target':'rear_long_layers'},{'op':'remove_node','target':'side_long_layers'}]
    low_y=23 if scope=='face' else 0
    for i,(a,b) in enumerate((([-6.5,low_y,-5],[-6.48,low_y+.02,-4.98]),([6.48,33.98,5.98],[6.5,34,6]))):
        changes.append({'op':'add_cube','name':f'transparent_comparison_bounds_{i}','from':a,'to':b,'parent':'head' if scope=='face' else 'root','box_uv':False,'texture':3,
            'faces':{f:{'uv':[40.25,40.25,40.75,40.75],'texture':3} for f in ('north','south','east','west','up','down')}})
    mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':changes},f'compare-{label}-{scope}-shared-bounds')
    bounds=mcp('bbmodel_info',{'file':file},f'compare-{label}-{scope}-info')['bounds']
    return file,bounds

def views():
    camera={}
    for label,source in (('r8',WORK/MODEL),('r7',BASE)):
        file,camera[label]=inspection(label,source)
        helper.render(file,'front',f'{label}-face-front-flat.png',640,f'{label}-face-front-flat')
        helper.render(file,'three-quarter',f'{label}-face-three-quarter-studio.png',640,f'{label}-face-three-quarter-studio','studio')
        if label=='r8':
            helper.render(file,'front','r8-face-front-studio.png',640,'r8-face-front-studio','studio')
            helper.render(MODEL,'three-quarter','r8-normal-192.png',192,'r8-normal-192')
            helper.render(MODEL,'front','r8-full-front-512.png',512,'r8-full-front-512')
    camera['identical']=camera['r7']==camera['r8'];save('comparison-camera-record.json',camera)
    for view in ('front-flat','three-quarter-studio'):
        canvas=Image.new('RGB',(1280,688),'#202329');draw=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
        for x,label,text in ((0,'r7','R7：前一版（同取景）'),(640,'r8','R8：偏分/包脸与新眼型，待审')):
            draw.text((x+12,10),text,font=font,fill='white')
            with Image.open(WORK/f'{label}-face-{view}.png') as im:canvas.paste(im.convert('RGB'),(x,48))
        canvas.save(WORK/f'r7-r8-face-{view}-comparison.png')

def refine():
    first=WORK/'first-pass';first.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/'facts.json',*WORK.glob('r8-*.png'),*WORK.glob('r7-r8-*.png')]:shutil.copy2(path,first/path.name)
    shutil.copytree(WORK/'receipts',first/'receipts',dirs_exist_ok=True)
    doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    changes=[{'op':'remove_node','target':u} for u in sorted(ids_in(doc,{'side_swept_fringe'}))]
    changes.extend(op for op in hair_operations() if op['parent']=='side_swept_fringe')
    info=mcp('bbmodel_info',{'file':MODEL},'07-before-buried-fringe-roots')
    mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':changes},'08-bury-fringe-root-endfaces')
    record();views();final_views()

def final_views():
    file='r8-whole-head-inspection.bbmodel';shutil.copy2(WORK/MODEL,WORK/file)
    info=mcp('bbmodel_info',{'file':file},'final-whole-head-before')
    mcp('bbmodel_edit',{'file':file,'expected_revision':info['revision'],'operations':[{'op':'update_node','target':'head','parent':None},{'op':'remove_node','target':'root'}]},'final-whole-head-only')
    for view in ('right','left','back'):
        helper.render(file,view,f'r8-head-{view}.png',640,f'final-head-{view}')
        helper.render(MODEL,view,f'r8-full-{view}-512.png',512,f'final-full-{view}')
    helper.render(MODEL,'three-quarter','r8-full-three-quarter-studio.png',640,'final-full-three-quarter-studio','studio')
    fixed=freeze();facts=json.loads((WORK/'facts.json').read_text(encoding='utf-8'))
    facts.update(status='R8集中修后主工程冻结，供根编辑器复核与用户外观判断',author_viewed_images=True,
        root_review='root saw first front/three-quarter/192 and matched comparison; new eyes retained for review; requested one concentrated fringe-root correction, now produced',
        official_editor_review='pending',user_acceptance='pending; no likeness score or overall acceptance',remaining_limits='native cuboid joints remain; no image retouching')
    save('facts.json',facts);save('final-facts.json',facts)
    (WORK/'README.md').write_text('# 爱音R8：偏分、包脸与眼型原型\n\n主工程anon-identity-r8.bbmodel已集中修后冻结，供根实际编辑器复核及用户外观判断。不是相似度通过。\n\n- 正面屏幕左=+X，主刘海从+X向-X顺扫；两侧连续厚鬓发向嘴角外侧包脸，眼角可读。无清晰依据的双夹已撤去。\n- 眼睛为原创新长方体布局、低缓上睑、蓝灰虹膜、短矩形瞳芯、同方向高光；根亲看首稿后要求本轮保留。黑色细眉原样。\n- 集中修仅将刘海上部接根埋入保留圆顶，减少暴露斜板/端面；未改眼或身体。原生块转折仍可见。first-pass/保存修前图及工程。\n- 身体44、皮肤6、小矩形嘴1、黑眉20共71元素逐字段不变；额外311旧元素（包括圆顶/后侧披肩）原样，6原atlas、UV/resolution/所有组及父变换保持，见frozen-r7-record.json。新增第7张原创64像素眼色色板。\n- 最新r8-face-front-flat/studio.png、r8-face-three-quarter-studio.png、r8-normal-192.png、r8-full-front-512.png、r8-full-three-quarter-studio.png，以及r8-head-right/left/back.png、r8-full-right/left/back-512.png均来自最终主工程。\n- R7/R8同取景r7-r8-face-front-flat-comparison.png与r7-r8-face-three-quarter-studio-comparison.png，1280×688；真实MCP检查副本透明锚点的世界边界一致，主工程无锚点，仅排布原始渲染。\n- receipts/含原作者真实MCP stdio、expected_revision和静态原回执，self_test=false。未运行测试/游戏/Java/构建/安装/心跳/上传。静态计数见final-facts.json。\n- 根已看首稿关键图并要求集中修发根；当前最终官方编辑器检查待记录，用户外观待判断，不扩其余七人。\n\n最终SHA256：`'+fixed['model_sha256']+'`\n',encoding='utf-8')
    print(json.dumps({'final_sha256':fixed['model_sha256'],'frozen':True}),flush=True)

def local_front_edge():
    backup=WORK/'pre-front-edge-fix';backup.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/'facts.json',WORK/'final-facts.json',WORK/'README.md',WORK/'frozen-r7-record.json',*WORK.glob('r8-*.png'),*WORK.glob('r7-r8-*.png')]:
        if path.exists():shutil.copy2(path,backup/path.name)
    doc=json.loads((WORK/MODEL).read_text(encoding='utf-8'))
    own_routes=json.loads((BASE.parent/'original-crown-drape-routes.json').read_text(encoding='utf-8'))
    names=[];changes=[]
    for route in own_routes:
        if not route['name'].startswith('crown_arc_'):continue
        index=int(route['name'].split('_')[-1])
        if not 2<=index<=10:continue
        name='r7_'+route['name']+'_0';a,b=[list(p) for p in route['points'][:2]]
        # Shorten and recess the internal front lip; +X is the high part, -X is the lower sweep.
        a[1]+=.62+.12*a[0];a[2]+=.50
        mid=[(a[k]+b[k])/2 for k in range(3)]
        normal=[mid[0]/4.55**2,(mid[1]-29.1)/3.65**2,(mid[2]-.1)/4**2]
        op=helper.segment(name,a,b,1.02,.34,normal,'crown_layers','hair',.045)
        changes.append({'op':'update_node','target':name,**{k:op[k] for k in ('from','to','origin','rotation')}});names.append(name)
    save('local-front-edge-scope.json',{'changed_original_names':names,'reason':'root actual editor review found exposed horizontal crown lip; only nine internal foremost crown segments shortened and recessed, top silhouette unchanged','before_sha256':hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest()})
    info=mcp('bbmodel_info',{'file':MODEL},'09-before-local-front-edge')
    mcp('bbmodel_edit',{'file':MODEL,'expected_revision':info['revision'],'operations':changes},'10-local-buried-front-edge')
    record();file,bounds=inspection('r8',WORK/MODEL)
    for view,lighting,out in (('front','flat','r8-face-front-flat.png'),('front','studio','r8-face-front-studio.png'),('three-quarter','studio','r8-face-three-quarter-studio.png')):
        helper.render(file,view,out,640,'local-final-'+view+'-'+lighting,lighting)
    helper.render(MODEL,'three-quarter','r8-normal-192.png',192,'local-final-normal-192')
    # The complete older view set is archived, never presented as renders of this changed revision.
    for path in list(WORK.glob('r8-full-*.png'))+list(WORK.glob('r8-head-*.png')):path.unlink()
    for view in ('front-flat','three-quarter-studio'):
        canvas=Image.new('RGB',(1280,688),'#202329');draw=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
        for x,label,text in ((0,'r7','R7：前一版（同取景）'),(640,'r8','R8：前缘局部收尾，待审')):
            draw.text((x+12,10),text,font=font,fill='white')
            with Image.open(WORK/f'{label}-face-{view}.png') as im:canvas.paste(im.convert('RGB'),(x,48))
        canvas.save(WORK/f'r7-r8-face-{view}-comparison.png')
    frozen=freeze();facts=json.loads((WORK/'facts.json').read_text(encoding='utf-8'))
    facts.update(status='R8前缘局部收尾后冻结，待根重开最终文件',local_front_edge=names,
        previous_sha256='c2c08ae67533e879fae0b0f0f136105542141c58615dee09245e60cc5859710f',
        author_viewed_images=False,root_review='root opened previous c2c08 revision in official Blockbench and found horizontal crown rim; final local revision needs reopening',official_editor_review='pending final revision',
        current_images=['r8-face-front-flat.png','r8-face-front-studio.png','r8-face-three-quarter-studio.png','r8-normal-192.png'],older_full_views='pre-front-edge-fix/ only; not current revision')
    save('facts.json',facts);save('final-facts.json',facts)
    (WORK/'README.md').write_text('# 爱音R8：偏分、包脸与眼型原型\n\n根在官方编辑器打开c2c08前稿后发现水平帽沿，本次仅局部收短/后埋冠顶最前缘9片，承接现有偏分刘海；不改圆顶外轮廓、眼眉、身体、后发。前稿与旧完整视图保存pre-front-edge-fix/。\n\n最终主工程anon-identity-r8.bbmodel，593cube/19group/0mesh/0animation/7atlas。身体44/皮肤6/小矩形嘴1/黑眉20共71件逐字段冻结；额外保留302件，原6atlas及所有组/父变换/UV/resolution不变。9件前缘变动见local-front-edge-scope.json，不再声称311件全保持。\n\n当前最终真实图仅r8-face-front-flat.png、r8-face-front-studio.png、r8-face-three-quarter-studio.png、r8-normal-192.png；两张r7-r8-face-*-comparison.png已同步。此前完整视图只在归档，不能当作新revision。MCP/revision及静态回执在receipts/，self_test=false，未运行测试/游戏/构建等。\n\n根需重开当前最终revision；用户外观待判断，原生块接缝仍可见，无相似度评分。最终SHA256：`'+frozen['model_sha256']+'`\n',encoding='utf-8')
    print(json.dumps({'final_sha256':frozen['model_sha256'],'additional_preserved':frozen['additional_preserved_elements']}),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--quota-checked',action='store_true');p.add_argument('--views',action='store_true');p.add_argument('--refine',action='store_true');p.add_argument('--local-front-edge',action='store_true');a=p.parse_args()
    if not a.quota_checked:raise SystemExit('Read actual quota before source work.')
    if a.local_front_edge:local_front_edge()
    elif a.refine:refine()
    elif a.views:views()
    else:build()
