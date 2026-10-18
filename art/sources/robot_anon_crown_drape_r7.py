"""Anon R7: original cuboid crown curvature and shoulder/back hair drape."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import math
import shutil
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/"art/robot-mcp-workspace/characters/anon-crown-drape-r7"
BASE=WORK.parent/"anon-expression-r6/anon-expression-r6.bbmodel"
MODEL="anon-crown-drape-r7.bbmodel"
ALLOWED={"crown_layers","rear_long_layers","side_long_layers"}
spec=importlib.util.spec_from_file_location("own_mcp_cuboids",ROOT/"art/sources/robot_layered_head.py")
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
helper.WORK=WORK;helper.MODEL=MODEL
mcp=helper.mcp


def save(name,value):
    (WORK/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


def ids_in(doc,groups):
    names={g["uuid"]:g["name"] for g in doc["groups"]};ids=set()
    def walk(nodes,inside=False):
        for node in nodes:
            if isinstance(node,str):
                if inside:ids.add(node)
            else:walk(node.get("children",[]),inside or names[node["uuid"]] in groups)
    walk(doc["outliner"]);return ids


def freeze():
    before=json.loads(BASE.read_text(encoding="utf-8"));after=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    def select(doc):
        ids=ids_in(doc,ALLOWED);names={g["uuid"]:g["name"] for g in doc["groups"]}
        def clean(nodes):
            return [n if isinstance(n,str) else {**n,"children":[] if names[n["uuid"]] in ALLOWED else clean(n.get("children",[]))} for n in nodes]
        return {"elements":[e for e in doc["elements"] if e["uuid"] not in ids],"groups":doc["groups"],"outliner":clean(doc["outliner"]),
                "resolution":doc["resolution"],"all_original_atlases":doc["textures"]}
    left,right=select(before),select(after)
    result={"identical":left==right,"frozen_elements":len(left["elements"]),"original_textures":len(before["textures"]),
        "scope":"all R6 eye/brow, body, skin, single mouth, entire fringe/cheek frame and double clips; only crown/rear-long/side-long groups mutable",
        "baseline_sha256":hashlib.sha256(BASE.read_bytes()).hexdigest(),"model_sha256":hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),
        "frozen_sha256":hashlib.sha256(json.dumps(left,sort_keys=True,ensure_ascii=False,separators=(",",":")).encode()).hexdigest()}
    save("frozen-r6-record.json",result)
    if not result["identical"]:raise SystemExit("Frozen R6 fields changed; stop.")
    return result


def geometry():
    ops=[];routes=[]
    def buried_overlaps(points,depths):
        directions=[helper.unit([b[k]-a[k] for k in range(3)]) for a,b in zip(points,points[1:])]
        turns=[math.acos(max(-1,min(1,sum(a[k]*b[k] for k in range(3))))) for a,b in zip(directions,directions[1:])]
        return [max(.025,depth*math.tan(max(turns[max(0,i-1):min(len(turns),i+1)] or [0])/2)+.012)
                for i,depth in enumerate(depths)]
    def strand(name,points,widths,depths,normal,parent,color="hair",overlap=.28):
        if isinstance(depths,(int,float)):depths=[depths]*(len(points)-1)
        routes.append({"name":name,"points":points,"widths":widths,"depths":depths,"parent":parent})
        for i,(a,b) in enumerate(zip(points,points[1:])):
            n=normal[i] if isinstance(normal[0],list) else normal
            join=overlap[i] if isinstance(overlap,list) else overlap
            op=helper.segment(f"r7_{name}_{i}",a,b,widths[i],depths[i],n,parent,color,join)
            for face in op["faces"].values():
                u,v,s,t=face["uv"];face["uv"]=[u+.5,v+.5,s-.5,t-.5]
            ops.append(op)
    # A near-original-scale rounded crown. Curved meridians use more bends only where needed.
    # Overlapping thick joints are inside the surface; no common star point or internal old roof.
    xs=(-3.90,-3.25,-2.60,-1.95,-1.30,-.65,0,.65,1.30,1.95,2.60,3.25,3.90)
    for i,x in enumerate(xs):
        radius=math.sqrt(1-(x/4.55)**2);ry=3.65*radius;rz=4.0*radius
        count=max(4,math.ceil(2.60*(ry+rz)/2/1.38))
        points=[]
        for j in range(count+1):
            fraction=(j/count)**(1+.065*math.sin(i*.68))
            fraction+=.045*math.sin(i*1.1)*math.sin(math.pi*j/count)
            angle=-1.25+2.60*fraction
            points.append([x,round(29.1+ry*math.cos(angle),5),round(.1+rz*math.sin(angle),5)])
        widths=[1.02]*(count-1)+[.92]
        normals=[]
        for a,b in zip(points,points[1:]):
            mid=[(a[k]+b[k])/2 for k in range(3)]
            normals.append([mid[0]/(4.55**2),(mid[1]-29.1)/(3.65**2),(mid[2]-.1)/(4.0**2)])
        strand(f"crown_arc_{i}",points,widths,[.72]*count,normals,"crown_layers",overlap=buried_overlaps(points,[.72]*count))
    # Rear locks curve toward the shoulder/back instead of hanging as a separate vertical wall.
    for i,x in enumerate((-3.6,-3.0,-2.4,-1.8,-1.2,-.6,0,.6,1.2,1.8,2.4,3.0,3.6)):
        fullness=math.sqrt(max(.1,1-(x/4.2)**2));root_y=30.3+.8*fullness
        root_z=.1+3.5*fullness
        end=[17.2,15.7,16.2,14.9,15.8,14.7,15.1,14.4,15.6,15.0,16.4,15.9,17.0][i]
        if i in (5,6,7):ys=[root_y,28.6,26.1,24.3,22.7,19.9,17.2,end]
        elif i%2:ys=[root_y,27.5,24.8,23.0,20.6,18.0,end]
        else:ys=[root_y,28.0,25.0,23.5,21.0,18.2,end]
        ys=[y for j,y in enumerate(ys) if j==0 or y<ys[j-1]-.05]
        ys=[y if j in (0,len(ys)-1) else y+.22*math.sin(i*.8+j*.6) for j,y in enumerate(ys)]
        points=[]
        for j,y in enumerate(ys):
            t=(root_y-y)/(root_y-end)
            base_z=2.35+1.35/(1+math.exp(-(y-26.5)/1.5))
            top_base=2.35+1.35/(1+math.exp(-(root_y-26.5)/1.5))
            z=base_z+(root_z-top_base)*math.exp(-((root_y-y)/2.8)**2)
            z+=.10*math.sin(t*math.pi)*math.sin(i*.9)
            sway=.18*math.sin(t*math.pi)+.10*math.sin(t*math.pi*1.5+i*.45)
            px=x+(1 if x>=0 else -1)*sway
            points.append([round(px,4),round(y,4),round(z,4)])
        # Short tapered endpoint, with different lengths rather than a wide strip joined to a rod.
        a,b=points[-2:];length=math.dist(a,b);tip=.65+.12*(i%3)
        portion=max(.2,1-tip/length)
        points.insert(len(points)-1,[round(a[k]+(b[k]-a[k])*portion,4) for k in range(3)])
        widths=[.92+.06*(i%2)]*(len(points)-1);widths[-2:]=[.68,.38]
        depths=[.66]*(len(widths)-2)+[.50,.30]
        strand(f"back_drape_{i}",points,widths,depths,[0,0,1],"rear_long_layers","hair" if i%5 else "low",buried_overlaps(points,depths))
    # Only a few strands come forward, bending at the actual shoulder then falling along the sleeve.
    for side,sign in (("left",1),("right",-1)):
        reflect=lambda points:[[sign*x,y,z] for x,y,z in points]
        points=reflect([[3.35,30.6,1.7],[3.6,28.1,2.0],[3.5,25.2,2.25],[3.6,23.5,2.35]])
        strand(f"earback_connection_{side}",points,[1.15,1.10,.85],[.68,.70,.60],
               [sign,0,.3],"rear_long_layers","hair",.32)
        points=reflect([[3.75,27.8,-.8],[4.0,25.3,-1.05],[4.35,23.8,-1.65],[4.6,22.2,-1.75],
                        [4.8,20.3,-1.6],[4.55,18.1,-1.3],[4.25,16.8,-1.15]])
        strand(f"shoulder_front_{side}",points,[.75,.80,.75,.65,.58,.32],[.52,.52,.48,.44,.38,.25],
               [0,0,-1],"side_long_layers","hair",.27)
        points=reflect([[3.95,27,1.1],[4.5,24.2,1.5],[5.2,22.3,1.7],[5.25,20.2,1.9],[4.95,18.0,1.8],[4.45,16.0,1.4]])
        strand(f"shoulder_outer_{side}",points,[.8,.75,.70,.55,.30],[.55,.55,.50,.42,.25],
               [sign,0,.15],"side_long_layers","hair",.28)
        points=reflect([[3.6,26.5,-1.65],[4.05,24.4,-1.85],[4.15,21.8,-1.85],[4.35,19.5,-1.65]])
        strand(f"shoulder_inner_{side}",points,[.35,.32,.20],[.28,.25,.18],[0,0,-1],"side_long_layers","light",.22)
    return ops,routes


def prepare():
    WORK.mkdir(parents=True,exist_ok=True)
    (WORK/"plan.md").write_text("# 爱音R7：冠顶弧度与肩背垂发\n\n人类肯定身形、当前R6眼眉保留供用户看；新要求指出头顶/披肩发的圆润感不足。仅换crown_layers、rear_long_layers、side_long_layers，连刘海和全部颊侧框发/双夹也逐字段保留。当前眼眉、皮肤、单矩形嘴和身体不改，既有6atlas全保留。\n\n已亲看原演出服和R63侧背，只学习有厚度方块的组织，不复制资产。冠顶保持原头宽/顶高量级，用相邻弧向长方体的小角转折和内部重叠形成饱满轮廓，撤去旧屋顶/星芒底层，分束随曲率而非统一节数，不加随机碎尖。偏侧分身份与主刘海方向保留。\n\n后发从耳后向肩背收，背部与衣料保持小间隙；侧前仅少量束顺肩绕到袖外，侧后发宽薄/层次和短收尾变化，不整批挪到胸前、不留竖直宽墙。0mesh，不球/圆柱。\n\n真实MCP+expected_revision建模与副本，先studio前侧/严格左右/背/全身front给根集中审阅，再必要修一轮；不自评分/用户认可，不测试/self_test、游戏/Java/构建/安装/自动化/上传，不新委派。\n",encoding="utf-8")


def baseline_views():
    prepare();file="r6-whole-head-before-inspection.bbmodel";shutil.copy2(BASE,WORK/file)
    info=mcp("bbmodel_info",{"file":file},"baseline-head-info")
    mcp("bbmodel_edit",{"file":file,"expected_revision":info["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"}]},"baseline-head-only")
    for view in ("front","right","back"):
        helper.render(file,view,f"r6-head-before-{view}.png",768,f"baseline-head-{view}")


def render_views():
    file="r7-whole-head-inspection.bbmodel";shutil.copy2(WORK/MODEL,WORK/file)
    info=mcp("bbmodel_info",{"file":file},"whole-head-info")
    mcp("bbmodel_edit",{"file":file,"expected_revision":info["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"}]},"whole-head-only")
    helper.render(file,"three-quarter","r7-head-three-quarter-studio.png",768,"head-three-quarter-studio","studio")
    for view in ("front","right","left","back"):
        helper.render(file,view,f"r7-head-{view}.png",768,f"head-{view}")
    for view in ("front","three-quarter","right","left","back"):
        helper.render(MODEL,view,f"r7-full-{view}-512.png",512,f"full-{view}")
    helper.render(MODEL,"three-quarter","r7-full-three-quarter-studio.png",768,"full-three-quarter-studio","studio")
    helper.render(MODEL,"three-quarter","r7-normal-192.png",192,"normal-192")


def records():
    frozen=freeze();info=json.loads(json.loads((WORK/"receipts/03-r7-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/04-r7-static.json").read_text(encoding="utf-8"))["stdout"])
    save("facts.json",{"status":"R7 crown/shoulder drape first prototype, root/user review pending","model_sha256":frozen["model_sha256"],
        "counts":info["counts"],"freeze":frozen,"static":static["summary"],"self_test":False,"author_viewed_images":False})
    print(json.dumps({"sha256":frozen["model_sha256"],"counts":info["counts"],"static":static["summary"]}),flush=True)


def build():
    prepare();shutil.copy2(BASE,WORK/MODEL)
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"));new,routes=geometry()
    changes=[{"op":"remove_node","target":uuid} for uuid in sorted(ids_in(doc,ALLOWED))]+new
    changes.append({"op":"set_model_properties","name":"千早爱音｜R7冠顶与肩背垂发原型"})
    for part in range(0,len(changes),450):
        info=mcp("bbmodel_info",{"file":MODEL},f"01-before-crown-drape-{part//450}")
        mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes[part:part+450]},f"02-rounded-crown-shoulder-drape-{part//450}")
    save("original-crown-drape-routes.json",routes);freeze()
    mcp("bbmodel_info",{"file":MODEL},"03-r7-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-r7-static")
    render_views();records()


def close_earback():
    current=json.loads((WORK/MODEL).read_text(encoding="utf-8"));names={e["name"] for e in current["elements"]}
    new,routes=geometry();chosen=[op for op in new if op["name"].startswith("r7_earback_connection_")]
    changes=[op if op["name"] not in names else {"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation","faces")}} for op in chosen]
    info=mcp("bbmodel_info",{"file":MODEL},"05-before-earback-connection")
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes},"06-continuous-earback-underlayer")
    save("original-crown-drape-routes.json",routes);freeze()
    mcp("bbmodel_info",{"file":MODEL},"03-r7-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-r7-static")
    render_views();records()


def refine_surface_joins():
    first=WORK/"first-pass";first.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/"facts.json",*WORK.glob("r7-*.png")]:shutil.copy2(path,first/path.name)
    shutil.copytree(WORK/"receipts",first/"receipts",dirs_exist_ok=True)
    (first/"issues.md").write_text("首稿弧顶和靠肩背方向保留；本次仅减轻冠顶/背发重复深台阶、藏接头。耳后连续底层连接已修，保持。\n",encoding="utf-8")
    new,routes=geometry()
    chosen=[op for op in new if op["name"].startswith(("r7_crown_arc_","r7_back_drape_"))]
    changes=[{"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation","faces")}} for op in chosen]
    info=mcp("bbmodel_info",{"file":MODEL},"07-before-surface-join-refinement")
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes},"08-bury-endfaces-stagger-crown-back-joins")
    save("original-crown-drape-routes.json",routes);freeze()
    mcp("bbmodel_info",{"file":MODEL},"03-r7-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-r7-static")
    render_views();records()


def final_comparisons():
    camera={}
    for scope in ("head","full"):
        for label,source in (("r6",BASE),("r7",WORK/MODEL)):
            file=f"{label}-{scope}-matched-inspection.bbmodel";shutil.copy2(source,WORK/file)
            info=mcp("bbmodel_info",{"file":file},f"compare-{label}-{scope}-before")
            changes=[]
            if scope=="head":changes=[{"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"}]
            low_y=13.5 if scope=="head" else 0
            for i,(low,high) in enumerate((([-6.5,low_y,-5],[-6.48,low_y+.02,-4.98]),([6.48,33.98,5.98],[6.5,34,6]))):
                changes.append({"op":"add_cube","name":f"transparent_comparison_bounds_{i}","from":low,"to":high,
                    "parent":"head" if scope=="head" else "root","box_uv":False,"texture":3,
                    "faces":{face:{"uv":[40.25,40.25,40.75,40.75],"texture":3} for face in ("north","south","east","west","up","down")}})
            mcp("bbmodel_edit",{"file":file,"expected_revision":info["revision"],"operations":changes},f"compare-{label}-{scope}-shared-bounds")
            camera[f"{label}_{scope}"]=mcp("bbmodel_info",{"file":file},f"compare-{label}-{scope}-info")["bounds"]
            for view in ("front","three-quarter"):
                lighting="studio" if scope=="head" and view=="three-quarter" else "flat"
                size=768 if scope=="head" else 512
                helper.render(file,view,f"{label}-{scope}-matched-{view}.png",size,f"compare-render-{label}-{scope}-{view}",lighting)
        camera[f"{scope}_identical"]=camera[f"r6_{scope}"]==camera[f"r7_{scope}"]
        size=768 if scope=="head" else 512
        for view in ("front","three-quarter"):
            canvas=Image.new("RGB",(size*2,size+48),"#202329");draw=ImageDraw.Draw(canvas)
            font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",22 if size==768 else 19)
            draw.text((14,10),"R6：前一版（相同取景范围）",font=font,fill="white")
            draw.text((size+14,10),"R7：冠顶/披肩集中修后原型，待验收",font=font,fill="white")
            for x,label in ((0,"r6"),(size,"r7")):
                with Image.open(WORK/f"{label}-{scope}-matched-{view}.png") as im:canvas.paste(im.convert("RGB"),(x,48))
            canvas.save(WORK/f"r6-r7-{scope}-{view}-comparison.png")
    save("comparison-camera-record.json",camera)


def finalize_records():
    frozen=freeze()
    info=json.loads(json.loads((WORK/"receipts/03-r7-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/04-r7-static.json").read_text(encoding="utf-8"))["stdout"])
    facts={"status":"R7集中修后主工程冻结，供用户外观验收","model_sha256":frozen["model_sha256"],"counts":info["counts"],
        "freeze":frozen,"static":static["summary"],"self_test":False,"author_viewed_images":True,
        "root_review":"root viewed concentrated head front/three-quarter studio/strict sides/back and whole-body studio/192; keep this version for user",
        "official_editor_review":"pending","user_acceptance":"pending; no overall or likeness approval",
        "remaining_limits":"native cuboid joints and long-lock planes remain visible; eye/brow likeness still for user judgment",
        "static_gates":[{"id":g["id"],"ran":g["ran"],"count":len(g["violations"])} for g in static["gates"]],
        "comparisons":{"head_size":[1536,816],"full_size":[1024,560],"method":"true MCP inspection copies with identical transparent bounds anchors; raw renders arranged only; main model has no anchors"}}
    save("final-facts.json",facts);save("facts.json",facts)
    (WORK/"README.md").write_text("# 爱音R7：冠顶与肩背垂发原型（主工程冻结）\n\n只调整冠顶弧度、接头与肩背垂发。保留R6第二轮眼眉、获方向肯定的身形、皮肤、小矩形嘴、刘海/全部颊侧框发和双夹。当前供用户看，不能当作整人或相似度通过。\n\n- 主工程anon-crown-drape-r7.bbmodel：632cube/19group/0mesh/0animation，6张原atlas未改。\n- 420元素及全部原组/父变换/UV/resolution与R6逐字段相同，见frozen-r6-record.json。冠顶保持原头宽/顶高量级；后发靠肩背，少量前束顺肩和袖外垂下。\n- 集中修减轻重复深台阶，邻束接头错开；耳后漏肤已用连续底层连接覆盖。原生方块接缝/部分长束平面仍在，眼眉相似度仍待用户判断。\n- 最新头部：r7-head-three-quarter-studio.png、r7-head-front/right/left/back.png；完整人：r7-full-front/three-quarter/right/left/back-512.png、r7-full-three-quarter-studio.png、r7-normal-192.png。\n- R6/R7同取景头部对照r6-r7-head-front-comparison.png、r6-r7-head-three-quarter-comparison.png（1536×816，前侧为studio）；完整人对照r6-r7-full-front-comparison.png、r6-r7-full-three-quarter-comparison.png（1024×560）。真实MCP检查副本用透明锚点统一世界边界，只排布真实渲染；主工程不含锚点。\n- first-pass/保留集中修前图/工程/回执，不是当前稿。\n- 所有工程写入经原作者真实MCP+expected_revision，回执在receipts/。静态0错误/467警告：305薄件接触、153内部覆盖/叠层、9非镜像发束，self_test=false。未运行测试/Java/游戏/构建/安装/自动化/上传。\n- 根已亲看集中修后的完整多视角并要求保留供用户看，最终官方编辑器检查待记录；用户验收待定。\n\n最终SHA256：`"+frozen["model_sha256"]+"`\n",encoding="utf-8")
    print(json.dumps({"final_sha256":frozen["model_sha256"],"model_frozen":True}),flush=True)


def finalize():
    final_comparisons();finalize_records()


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--quota-checked",action="store_true");parser.add_argument("--baseline-views",action="store_true");parser.add_argument("--close-earback",action="store_true");parser.add_argument("--refine-surface-joins",action="store_true");parser.add_argument("--finalize",action="store_true");args=parser.parse_args()
    if not args.quota_checked:raise SystemExit("Read actual quota before source work.")
    if args.finalize:finalize()
    elif args.refine_surface_joins:refine_surface_joins()
    elif args.close_earback:close_earback()
    elif args.baseline_views:baseline_views()
    else:build()
