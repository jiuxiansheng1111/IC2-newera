"""Original Anon R5 hair and slimmer silhouette, authored through upstream MCP."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import math
import shutil
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/"art/robot-mcp-workspace/characters/anon-hair-r5"
BASE=WORK.parent/"anon-face-r4/anon-face-r4.bbmodel"
MODEL="anon-hair-r5.bbmodel"
HAIR={"crown_layers","side_swept_fringe","cheek_and_temple_locks","rear_long_layers","side_long_layers","hair_clips"}
spec=importlib.util.spec_from_file_location("own_cuboid_helpers",ROOT/"art/sources/robot_layered_head.py")
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
helper.WORK=WORK;helper.MODEL=MODEL
mcp=helper.mcp


def save(name,value):
    (WORK/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


def descendants(doc,groups):
    names={group["uuid"]:group["name"] for group in doc["groups"]};found=set()
    def walk(nodes,inside=False):
        for node in nodes:
            if isinstance(node,str):
                if inside:found.add(node)
            else:walk(node.get("children",[]),inside or names.get(node["uuid"]) in groups)
    walk(doc["outliner"]);return found


def freeze_record(body_stage=False):
    before=json.loads(BASE.read_text(encoding="utf-8"));after=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    def frozen(doc):
        excluded=descendants(doc,HAIR|{"brow_geometry"})
        if body_stage:
            keep=descendants(doc,{"eye_geometry_left","eye_geometry_right","smile_geometry","face_skin_structure"})
            elements=[e for e in doc["elements"] if e["uuid"] in keep]
        else:elements=[e for e in doc["elements"] if e["uuid"] not in excluded]
        names={g["uuid"]:g["name"] for g in doc["groups"]}
        def clean(nodes):
            return [n if isinstance(n,str) else {**n,"children":[] if names.get(n["uuid"]) in HAIR|{"brow_geometry"} else clean(n.get("children",[]))} for n in nodes]
        result={"elements":elements,"original_atlases":doc["textures"][:4],"resolution":doc["resolution"]}
        if not body_stage:result.update(groups=doc["groups"],outliner=clean(doc["outliner"]))
        return result
    left,right=frozen(before),frozen(after)
    brow_before=[{k:v for k,v in e.items() if k!="faces"} for e in before["elements"] if e["uuid"] in descendants(before,{"brow_geometry"})]
    brow_after=[{k:v for k,v in e.items() if k!="faces"} for e in after["elements"] if e["uuid"] in descendants(after,{"brow_geometry"})]
    record={"stage":"body" if body_stage else "hair-first","identical":left==right,"frozen_elements":len(left["elements"]),
            "brow_geometry_identical":brow_before==brow_after,"original_four_atlases_identical":before["textures"][:4]==after["textures"][:4],
            "base_sha256":hashlib.sha256(BASE.read_bytes()).hexdigest(),"model_sha256":hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),
            "frozen_sha256":hashlib.sha256(json.dumps(left,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode()).hexdigest()}
    save("freeze-record.json",record)
    if not record["identical"] or not record["brow_geometry_identical"]:raise SystemExit("Frozen fields changed; stop.")
    return record


def hair_operations():
    ops=[];routes=[]
    def lock(name,points,widths,depths,normal,parent,color="hair"):
        if isinstance(depths,(float,int)):depths=[depths]*(len(points)-1)
        routes.append({"name":name,"points":points,"widths":widths,"depths":depths,"parent":parent})
        for i,(a,b) in enumerate(zip(points,points[1:])):
            ops.append(helper.segment(f"r5_{name}_{i}",a,b,widths[i],depths[i],normal,parent,color,.12))
    # Internal cuboid coverage lies below the exterior; the visible routes fan from the side part.
    original,old_routes=helper.hair_operations()
    for op in original:
        if op["op"]!="add_cube" or op.get("parent")!="crown_layers":continue
        op={**op,"name":"r5_inner_"+op["name"]}
        for key in ("from","to","origin"):
            op[key]=[op[key][0],op[key][1]-.44,op[key][2]]
        ops.append(op)
    crown=[]
    # Roots lie along a short part line rather than intersecting in a radial star.
    for i,z in enumerate((-2.55,-1.7,-.85,0,.85,1.7,2.55)):
        root_x=-1.42+.045*i;end_z=z*1.045
        end_x=4.2*math.sqrt(max(.1,1-(end_z/4.05)**2))*.96
        ts=([0,.55,1],[0,.28,.70,1],[0,.43,.83,1])[i%3]
        crown.append((f"right_sweep_{i}",[root_x,z],[end_x,end_z],ts,1.10))
    for i,z in enumerate((-2.4,-1.2,0,1.2,2.4)):
        root_x=-1.45+.03*i;end_z=z*1.04
        end_x=-4.2*math.sqrt(max(.1,1-(end_z/4.05)**2))*.96
        crown.append((f"left_sweep_{i}",[root_x,z+.10],[end_x,end_z],[0,.42+.08*(i%2),1],1.32))
    # Front and back continuations start below the part-line surface and blend into fringe/long hair.
    for side,sign in (("front",-1),("back",1)):
        for i,x in enumerate((-2.65,-1.3,.05,1.4,2.7)):
            end_z=sign*4.05*math.sqrt(max(.15,1-(x/4.2)**2))*.94
            crown.append((f"{side}_continuation_{i}",[x*.86,sign*2.15],[x,end_z],[0,.47+.10*(i%2),1],1.30))
    for name,start,end,ts,width in crown:
        points=[]
        for t in ts:
            x=start[0]*(1-t)+end[0]*t;z=start[1]*(1-t)+end[1]*t
            radius=(x/4.2)**2+(z/4.05)**2
            y=29.15+3.48*math.sqrt(max(.08,1-radius))+.20
            if "continuation" in name:y-=.12*(1-t)
            points.append([round(x,4),round(y,4),round(z,4)])
        widths=[width]*(len(points)-1);depths=[.57]*(len(points)-1)
        routes.append({"name":f"crown_partline_{name}","points":points,"widths":widths,"depths":depths,"parent":"crown_layers"})
        for j,(a,b) in enumerate(zip(points,points[1:])):
            mid=[(a[k]+b[k])/2 for k in range(3)]
            normal=[mid[0]/(4.2**2),max(.3,mid[1]-29.15)/(3.48**2),mid[2]/(4.05**2)]
            ops.append(helper.segment(f"r5_crown_partline_{name}_{j}",a,b,widths[j],depths[j],normal,"crown_layers","hair",.20))
    # Three major side-swept flows plus inset strands; ends descend without hiding the eyes.
    bangs=[
        ("sweep_a",[[-1.8,32.0,-2.9],[-1.15,30.7,-3.75],[-.05,29.7,-4.0],[1.1,29.25,-4.0]],[1.0,1.05,.62],"hair"),
        ("sweep_b",[[-.75,32.2,-2.8],[.15,30.8,-3.75],[1.45,29.7,-4.04],[2.45,28.95,-3.9]],[1.05,1.0,.60],"light"),
        ("sweep_c",[[.4,32.05,-2.7],[1.7,30.65,-3.60],[2.75,29.25,-3.85],[3.05,27.95,-3.65]],[1.0,.82,.48],"hair"),
        ("sweep_inset_a",[[-1.55,31.9,-2.85],[-.6,30.5,-3.87],[.55,29.55,-4.14]],[.55,.44],"light"),
        ("sweep_inset_b",[[-.15,31.85,-2.95],[1.0,30.3,-3.9],[2.0,29.35,-4.12]],[.52,.40],"hair"),
        ("sweep_outer_root",[[1.5,31.8,-2.3],[2.55,30.5,-3.65],[3.3,29.05,-3.70]],[.95,.90],"low"),
        ("part_short",[[-1.8,31.95,-2.85],[-2.35,30.5,-3.65],[-2.8,29.1,-3.90]],[1.05,.67],"hair"),
        ("part_inner",[[-1.65,31.7,-3.05],[-1.9,30.25,-3.90],[-2.15,29.3,-4.02]],[.52,.35],"light"),
        ("part_outer",[[-2.8,31.25,-2.6],[-3.3,29.7,-3.25],[-3.05,28.15,-3.65]],[.98,.58],"hair"),
        ("part_under",[[-2.35,31.4,-2.7],[-2.85,29.8,-3.35],[-2.65,28.75,-3.70]],[.65,.43],"low"),
    ]
    for name,points,widths,color in bangs:lock(name,points,widths,[.48]*(len(widths)-1)+[.34],[0,0,-1],"side_swept_fringe",color)
    # Existing inner temple/back volume is retained, with new front cheek locks curving inward.
    keep_prefixes=("temple_","rear_flow_","side_cascade_","outer_cascade_","double_clip_")
    ops += [op for op in original if op["op"]=="add_cube" and op["name"].startswith(keep_prefixes)]
    routes += [route for route in old_routes if route["name"].startswith(keep_prefixes)]
    for side,sign in (("left",1),("right",-1)):
        reflect=lambda points:[[sign*x,y,z] for x,y,z in points]
        lock(f"cheek_inner_{side}",reflect([[3.2,31.15,-2.85],[3.38,29.15,-3.45],[3.0,27.45,-3.69],
             [2.9,25.7,-3.68],[3.02,24.45,-3.28]]),[1.40,.75,.70,.43],[.84,.50,.40,.28],[0,0,-1],"cheek_and_temple_locks")
        lock(f"cheek_outer_{side}",reflect([[3.65,30.8,-2.5],[3.65,28.65,-3.05],[3.42,26.3,-3.55],
             [3.42,24.7,-3.55],[3.65,23.9,-2.80]]),[.95,.88,.70,.35],[.70,.62,.56,.30],[0,0,-1],"cheek_and_temple_locks","light")
        lock(f"cheek_fine_{side}",reflect([[3.37,28.2,-3.69],[3.15,26.8,-3.87],[3.2,25.3,-3.72]]),
             [.25,.20],[.22,.16],[0,0,-1],"cheek_and_temple_locks","light")
    # Longer shallow secondary ridges follow selected existing long locks; no tile grid.
    for i,route in enumerate(old_routes):
        if route["parent"]!="rear_long_layers" or i%2:continue
        points=route["points"];middle=points[1:-1]
        if len(middle)<3:continue
        new=[]
        for j,(x,y,z) in enumerate(middle):
            length=math.sqrt(x*x+(z-.5)**2);nx,nz=x/length,(z-.5)/length
            new.append([x+nx*.39+.10,y+.22*(j%2),z+nz*.39])
        widths=[.36]*(len(new)-1);widths[-1]=.23
        lock(f"rear_secondary_{i}",new,widths,[.26]*(len(widths)-1)+[.18],[nx,0,nz],"rear_long_layers","light")
    return ops,routes


def render_head():
    whole="r5-whole-head-inspection.bbmodel";shutil.copy2(WORK/MODEL,WORK/whole)
    info=mcp("bbmodel_info",{"file":whole},"whole-head-before-detach")
    mcp("bbmodel_edit",{"file":whole,"expected_revision":info["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"}]},"whole-head-detach")
    for view in ("front","right","left","back"):
        helper.render(whole,view,f"r5-head-{view}.png",768,f"head-{view}")
    helper.render(whole,"three-quarter","r5-head-three-quarter-studio.png",768,"head-three-quarter-studio","studio")
    close="r5-face-inspection.bbmodel";shutil.copy2(WORK/whole,WORK/close)
    info=mcp("bbmodel_info",{"file":close},"face-inspection-before-crop")
    mcp("bbmodel_edit",{"file":close,"expected_revision":info["revision"],"operations":[
        {"op":"remove_node","target":"rear_long_layers"},{"op":"remove_node","target":"side_long_layers"}]},"face-inspection-crop")
    helper.render(close,"front","r5-face-front-flat.png",640,"face-front-flat")
    helper.render(close,"three-quarter","r5-face-three-quarter-flat.png",640,"face-three-quarter-flat")
    helper.render(close,"three-quarter","r5-face-three-quarter-studio.png",640,"face-three-quarter-studio","studio")
    for name,angle in (("left45",45),("right45",-45)):
        turn=f"r5-face-{name}-inspection.bbmodel";shutil.copy2(WORK/close,WORK/turn)
        info=mcp("bbmodel_info",{"file":turn},f"{name}-before-turn")
        mcp("bbmodel_edit",{"file":turn,"expected_revision":info["revision"],"operations":[
            {"op":"update_node","target":"head","rotation":[0,angle,0]}]},f"{name}-turn")
        helper.render(turn,"front",f"r5-face-{name}.png",640,f"face-{name}")
    helper.render(MODEL,"three-quarter","r5-normal-192.png",192,"normal-192")
    helper.render(MODEL,"front","r5-full-front-512.png",512,"full-front")


def records():
    freeze=freeze_record();info=json.loads(json.loads((WORK/"receipts/03-hair-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/04-hair-static.json").read_text(encoding="utf-8"))["stdout"])
    save("hair-stage-facts.json",{"status":"hair/black-brow first stage, root review pending; body refinement is next authorized stage",
         "model_sha256":freeze["model_sha256"],"counts":info["counts"],"freeze":freeze,"static":static["summary"],"self_test":False,
         "user_acceptance":"pending","author_viewed_images":False})
    canvas=Image.new("RGB",(1280,688),"#202329");draw=ImageDraw.Draw(canvas);font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",22)
    draw.text((14,10),"R4：前一版",font=font,fill="white");draw.text((654,10),"R5：头发与黑眉首稿，待审阅",font=font,fill="white")
    for x,path in ((0,BASE.parent/"r4-face-front-flat.png"),(640,WORK/"r5-face-front-flat.png")):
        with Image.open(path) as im:canvas.paste(im.convert("RGB"),(x,48))
    canvas.save(WORK/"r4-r5-hair-front-comparison.png")
    print(json.dumps({"sha256":freeze["model_sha256"],"counts":info["counts"],"static":static["summary"]}),flush=True)


def build_hair():
    WORK.mkdir(parents=True,exist_ok=True)
    (WORK/"plan.md").write_text("# 爱音R5：头发与身体轮廓细化\n\n最新用户要求头发更精细，稍遮大额头/脸颊，眉用黑色方块；随后追加衣服、手臂和腿纤细。\n\n阶段1仅细化头发与黑眉：冠顶宽板改较窄连续流向、侧扫刘海自然下降，眼睛上方留可读区，额角与颊侧发向内收；保留粉色长发、双夹及发量，不做密集瓦片。阶段1仍冻结R4非发非眉200元素。眉18小块仅换黑色材质，形状位置保留。\n\n阶段2待根看头发后进行：衣肩袖、腰、上下臂/腿与靴鞋适度收细，保持身高站姿/演出服颜色/裙摆展开，接缝与骨骼连续，不独立缩块导致断肢。全程冻结172眼+单矩形嘴+6皮肤浅颌共179元素、原4atlas；不重做脸，不扩其他七人。\n\n已经亲看R4真实图与R63正侧截图，只学组织方法，不复制资产。全部.bbmodel写入通过原作者真实MCP及expected_revision；0mesh，不测试/self_test、Java、游戏、构建、安装、自动化或上传。\n",encoding="utf-8")
    shutil.copy2(BASE,WORK/MODEL)
    palette=Image.new("RGBA",(64,64),(0,0,0,0));ImageDraw.Draw(palette).rectangle((0,0,3,3),fill="#29252D")
    palette.save(WORK/"original-r5-black-brow-64.png")
    info=mcp("bbmodel_info",{"file":MODEL},"00-baseline-info")
    mcp("bbmodel_add_texture",{"file":MODEL,"expected_revision":info["revision"],"image":"original-r5-black-brow-64.png","name":"original-r5-black-brow"},"01-black-brow-palette")
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"));old_hair=descendants(doc,HAIR);brows=descendants(doc,{"brow_geometry"})
    ops,routes=hair_operations()
    changes=[{"op":"remove_node","target":uuid} for uuid in sorted(old_hair)]+ops
    changes += [{"op":"update_node","target":uuid,"faces":{face:{"uv":[0,0,4,4],"texture":4} for face in ("north","south","east","west","up","down")}} for uuid in sorted(brows)]
    changes.append({"op":"set_model_properties","name":"千早爱音｜R5头发与身体轮廓原型（头发首阶段）"})
    for part in range(0,len(changes),450):
        info=mcp("bbmodel_info",{"file":MODEL},f"02-before-hair-part-{part//450}")
        mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes[part:part+450]},f"02-refine-hair-black-brow-part-{part//450}")
    save("original-hair-routes.json",routes);freeze_record()
    mcp("bbmodel_info",{"file":MODEL},"03-hair-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-hair-static")
    render_head();records()


def refine_hair_roots():
    frozen=WORK/"first-hair-stage";frozen.mkdir(exist_ok=True)
    for path in [WORK/MODEL,*WORK.glob("r5-*.png")]:shutil.copy2(path,frozen/path.name)
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"));old=descendants(doc,HAIR)
    new,routes=hair_operations();changes=[{"op":"remove_node","target":uuid} for uuid in sorted(old)]+new
    for part in range(0,len(changes),450):
        info=mcp("bbmodel_info",{"file":MODEL},f"05-before-fan-roots-part-{part//450}")
        mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes[part:part+450]},f"06-sidepart-fan-continuous-cheek-part-{part//450}")
    save("original-hair-routes.json",routes);freeze_record()
    mcp("bbmodel_info",{"file":MODEL},"03-hair-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-hair-static")
    render_head();records()


def close_remaining_coverage():
    ops,routes=hair_operations()
    chosen=[op for op in ops if op["name"].startswith(("r5_sweep_outer_root_","r5_cheek_outer_"))]
    changes=[{"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation")}} for op in chosen]
    info=mcp("bbmodel_info",{"file":MODEL},"07-before-final-side-coverage")
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes},"08-cover-existing-root-and-cheek-depth")
    save("original-hair-routes.json",routes);freeze_record()
    mcp("bbmodel_info",{"file":MODEL},"03-hair-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-hair-static")
    render_head();records()


def body_operations():
    ops=[]
    uv={"skin":[0,16,4,20],"blue":[16,16,20,20],"blue_dark":[20,16,24,20],
        "white":[24,16,28,20],"white_low":[28,16,32,20],"yellow":[32,16,36,20],
        "boot":[36,16,40,20],"sole":[40,16,44,20]}
    def box(name,center,size,color,parent,rotation=None,front=None):
        inset=lambda rect:[rect[0]+.5,rect[1]+.5,rect[2]-.5,rect[3]-.5]
        op={"op":"add_cube","name":"r5_body_"+name,"from":[round(c-s/2,6) for c,s in zip(center,size)],
            "to":[round(c+s/2,6) for c,s in zip(center,size)],"origin":center,"rotation":rotation or [0,0,0],
            "parent":parent,"box_uv":False,"texture":0,
            "faces":{face:{"uv":inset(uv[color]),"texture":0} for face in ("north","south","east","west","up","down")}}
        if front:op["faces"]["north"]["uv"]=inset(front)
        ops.append(op)
    # The torso stays connected: a continuous front/back core and two shallow tapering side solids.
    box("neck",[0,23.75,0],[2.5,.5,2.0],"skin","body")
    box("shirt_core",[0,19.75,0],[4.8,7.5,2.76],"blue","body",front=[8,0,16,8])
    for side,sign in (("left",1),("right",-1)):
        box(f"shirt_taper_{side}",[sign*2.45,19.75,0],[.80,7.4,2.9],"blue","body",[0,0,-sign*5])
    box("shirt_hem",[0,16.1,0],[5.10,.22,2.95],"blue_dark","body")
    box("midriff",[0,15.28,0],[4.8,1.64,2.7],"skin","body")
    box("bow_left",[-.58,23.02,-1.58],[.90,.55,.25],"yellow","body",[0,0,10])
    box("bow_right",[.58,23.02,-1.58],[.90,.55,.25],"yellow","body",[0,0,-10])
    box("bow_knot",[0,23.02,-1.65],[.38,.50,.28],"yellow","body")
    box("bow_tail",[0,21.48,-1.53],[.55,2.60,.25],"yellow","body")
    for side,sign in (("left",1),("right",-1)):
        parent=f"arm_{side}";x=sign*3.95
        # Overlapping shoulder/cuff and upper/forearm/wrist pieces maintain the original arm length.
        box(f"sleeve_shoulder_{side}",[x,22.78,0],[2.10,1.44,2.80],"blue",parent)
        box(f"sleeve_cuff_{side}",[x,21.45,0],[1.95,1.56,2.45],"blue",parent)
        box(f"sleeve_edge_{side}",[x,20.72,0],[1.97,.16,2.47],"blue_dark",parent)
        box(f"upper_arm_{side}",[x,18.90,0],[1.75,3.60,1.80],"skin",parent)
        box(f"forearm_{side}",[x,15.80,0],[1.52,2.80,1.58],"skin",parent)
        box(f"wrist_hand_{side}",[x,14.02,-.01],[1.44,1.04,1.60],"skin",parent)
        ops.append({"op":"update_node","target":parent,"origin":[x,23.5,0]})
    # A narrow waistband joins separate front, side and longer rear fabric panels.
    box("skirt_waist",[0,14.0,0],[5.4,1.0,3.2],"white_low","skirt")
    box("skirt_front_center",[0,11.62,-1.68],[4.60,3.90,.35],"white","skirt",front=[24,0,32,4])
    for side,sign in (("left",1),("right",-1)):
        box(f"skirt_front_flare_{side}",[sign*2.6,11.65,-1.65],[1.5,3.8,.40],"white","skirt",[0,0,sign*12])
        box(f"skirt_side_{side}",[sign*3.0,11.65,.25],[.35,3.9,3.60],"white_low","skirt",[0,0,sign*10])
    box("skirt_back_center",[0,10.30,1.78],[5.9,6.6,.48],"white","skirt")
    for side,sign in (("left",1),("right",-1)):
        box(f"skirt_back_flare_{side}",[sign*2.75,10.25,1.84],[1.20,6.6,.40],"white","skirt",[0,0,sign*8.5])
    box("skirt_back_tail",[0,6.00,2.03],[4.9,2.2,.48],"white","skirt")
    for side,sign in (("left",1),("right",-1)):
        parent=f"leg_{side}";x=sign*1.55
        box(f"thigh_{side}",[x,11.06,0],[2.15,4.98,2.15],"skin",parent)
        box(f"shin_{side}",[x,6.90,-.025],[1.85,3.80,1.90],"skin",parent)
        box(f"boot_upper_{side}",[x,3.95,-.025],[2.0,2.20,2.15],"boot",parent,front=[16,0,24,8])
        box(f"boot_ankle_{side}",[x,1.99,-.025],[1.85,2.68,2.10],"boot",parent)
        box(f"boot_toe_{side}",[x,1.15,-1.30],[1.95,1.10,1.10],"boot",parent)
        box(f"boot_sole_{side}",[x,.30,-.40],[2.05,.60,3.10],"sole",parent)
        ops.append({"op":"update_node","target":parent,"origin":[x,13.5,0]})
    return ops


def render_body():
    for view in ("front","three-quarter","right","left","back"):
        helper.render(MODEL,view,f"r5-full-{view}-512.png",512,f"body-{view}")
    helper.render(MODEL,"three-quarter","r5-full-three-quarter-studio.png",768,"body-three-quarter-studio","studio")
    helper.render(MODEL,"three-quarter","r5-normal-192.png",192,"body-normal-192")
    font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",20)
    for view in ("front","three-quarter"):
        canvas=Image.new("RGB",(1024,560),"#202329");draw=ImageDraw.Draw(canvas)
        draw.text((14,10),"R4：前一版",font=font,fill="white");draw.text((526,10),"R5：头发/黑眉/身体轮廓原型，待审阅",font=font,fill="white")
        old=BASE.parent/("r4-full-front-512.png" if view=="front" else "r4-full-three-quarter-512.png")
        if not old.exists():
            copy="r4-comparison-readonly.bbmodel";shutil.copy2(BASE,WORK/copy)
            helper.render(copy,view,f"r4-comparison-{view}.png",512,f"r4-comparison-{view}")
            old=WORK/f"r4-comparison-{view}.png"
        for x,path in ((0,old),(512,WORK/f"r5-full-{view}-512.png")):
            with Image.open(path) as im:canvas.paste(im.convert("RGB"),(x,48))
        canvas.save(WORK/f"r4-r5-full-{view}-comparison.png")


def build_body():
    frozen=WORK/"hair-stage-frozen";frozen.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/"freeze-record.json",WORK/"hair-stage-facts.json",*WORK.glob("r5-*.png")]:shutil.copy2(path,frozen/path.name)
    shutil.copytree(WORK/"receipts",frozen/"receipts",dirs_exist_ok=True)
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    body_ids=descendants(doc,{"body","arm_left","arm_right","leg_left","leg_right","skirt"})-descendants(doc,{"head"})
    changes=[{"op":"remove_node","target":uuid} for uuid in sorted(body_ids)]+body_operations()
    changes.append({"op":"set_model_properties","name":"千早爱音｜R5头发与纤细演出服轮廓原型"})
    info=mcp("bbmodel_info",{"file":MODEL},"09-before-body-silhouette")
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes},"10-continuous-slim-outfit-limbs-boots")
    freeze_record(body_stage=True)
    mcp("bbmodel_info",{"file":MODEL},"11-body-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"12-body-static")
    # Front and three-quarter are deliberately first so root can inspect the actual silhouette early.
    render_body()
    info=json.loads(json.loads((WORK/"receipts/11-body-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/12-body-static.json").read_text(encoding="utf-8"))["stdout"])
    save("body-stage-facts.json",{"status":"hair plus slimmer body first prototype, pending root/user visual review",
         "model_sha256":hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),"counts":info["counts"],"static":static["summary"],
         "self_test":False,"frozen_face_elements":179,"arm_widths":[1.75,1.52,1.44],"thigh_width":2.15,"shin_width":1.85,
         "root_review":"pending","user_acceptance":"pending"})


def matched_body_comparison():
    # Both genuine MCP inspection copies receive transparent bounds anchors only.
    # The renderer therefore frames identical world bounds without rescaling either PNG.
    camera_records={}
    for label,source in (("r4",BASE),("r5",WORK/MODEL)):
        file=f"{label}-matched-camera-inspection.bbmodel";shutil.copy2(source,WORK/file)
        info=mcp("bbmodel_info",{"file":file},f"{label}-matched-camera-before")
        anchors=[]
        for i,(low,high) in enumerate((([-6.5,0,-4.5],[-6.48,.02,-4.48]),([6.48,33.98,5.98],[6.5,34,6]))):
            anchors.append({"op":"add_cube","name":f"transparent_camera_anchor_{i}","from":low,"to":high,"parent":"root",
                "box_uv":False,"texture":3,"faces":{face:{"uv":[40.25,40.25,40.75,40.75],"texture":3}
                for face in ("north","south","east","west","up","down")}})
        mcp("bbmodel_edit",{"file":file,"expected_revision":info["revision"],"operations":anchors},f"{label}-matched-camera-bounds")
        camera_records[label]=mcp("bbmodel_info",{"file":file},f"{label}-matched-camera-info")["bounds"]
        for view in ("front","three-quarter"):
            helper.render(file,view,f"{label}-matched-{view}-512.png",512,f"{label}-matched-{view}")
    camera_records["identical"]=camera_records["r4"]==camera_records["r5"]
    save("comparison-camera-record.json",camera_records)
    font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",20)
    for view in ("front","three-quarter"):
        canvas=Image.new("RGB",(1024,560),"#202329");draw=ImageDraw.Draw(canvas)
        draw.text((14,10),"R4：前一版（共同取景范围）",font=font,fill="white")
        draw.text((526,10),"R5：当前原型（共同取景范围）",font=font,fill="white")
        for x,label in ((0,"r4"),(512,"r5")):
            with Image.open(WORK/f"{label}-matched-{view}-512.png") as im:canvas.paste(im.convert("RGB"),(x,48))
        canvas.save(WORK/f"r4-r5-full-{view}-comparison.png")


def clean_body_uv_and_finalize():
    changes=[{"op":"update_node","target":op["name"],"faces":op["faces"]} for op in body_operations() if op["op"]=="add_cube"]
    info=mcp("bbmodel_info",{"file":MODEL},"13-before-body-uv-inset")
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes},"14-inset-body-uv-to-avoid-neighbor-bleed")
    freeze_record(body_stage=True)
    mcp("bbmodel_info",{"file":MODEL},"11-body-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"12-body-static")
    render_body()
    render_head()
    matched_body_comparison()
    finish_records()


def finish_records():
    record=freeze_record(body_stage=True)
    info=json.loads(json.loads((WORK/"receipts/11-body-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/12-body-static.json").read_text(encoding="utf-8"))["stdout"])
    current=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    head_stage=json.loads((WORK/"hair-stage-frozen"/MODEL).read_text(encoding="utf-8"))
    def head_parts(doc):
        ids=descendants(doc,{"head"});return [e for e in doc["elements"] if e["uuid"] in ids]
    head_unchanged=head_parts(current)==head_parts(head_stage)
    facts={"status":"爱音R5头发、黑眉与纤细衣服四肢原型，待用户外观验收",
           "model_sha256":record["model_sha256"],"counts":info["counts"],"freeze":record,
           "complete_head_identical_to_final_hair_stage":head_unchanged,"black_brow_cubes":18,
           "brow_rgb":"29252D","mouth_cube_count":1,"eye_cubes":172,"skin_jaw_cubes":6,
           "body_cubes":len(current["elements"])-len(head_parts(current)),"hair_cubes":len(descendants(current,HAIR)),"self_test":False,"static":static["summary"],
           "warning_gates":[{"id":g["id"],"ran":g["ran"],"count":len(g["violations"])} for g in static["gates"]],
           "body_uv_inset_pixels":.5,"uv_note":"胸侧浅虚线及靴前微线为原atlas相邻块串色，已收进半像素；未改PNG。",
           "sleeve_note":"袖肩/袖口分层有实际深度/高度差与重叠包覆，非共面面片。",
           "comparison":"R4/R5完整工程检查副本以相同透明范围锚点统一正交取景，非图片缩放；主工程不含锚点。",
           "root_review":"已看头发及身体首稿，最终UV收边后待官方编辑器检查",
           "user_acceptance":"pending","author_viewed_images":False}
    save("final-facts.json",facts);save("body-stage-facts.json",facts)
    (WORK/"README.md").write_text("# 爱音R5：头发、黑眉与纤细衣服四肢原型\n\n用户认为R4面部好一些，随后要求细化头发遮额遮颊、黑眉，以及衣服/手臂/腿更纤细。本轮仅爱音，外观待用户验收。\n\n- 主工程：`anon-hair-r5.bbmodel`，476cube/19group/0mesh/0animation。235发块、18黑眉、172眼、1小矩形嘴、6皮肤浅颌、44身体衣服块。\n- 头发按偏侧分线分组延伸，整理冠顶接缝与额颊露肤；原生长方体层接缝仍存在。眉形位置不变，仅换黑色小块材质。\n- 衣肩/袖口/臂腕、腰部、裙围合与大腿/小腿/踝靴相接收细；保持站立身高、粉色长发/双夹、蓝上衣/黄领结/白色前短后长裙/黑靴身份。\n- 冻结R4的172眼+单嘴+6皮肤浅颌，共179元素逐字段相同，原4张atlas/resolution不变；身体改动后整个头部也与最后头发阶段逐字段相同。见`freeze-record.json`与`final-facts.json`。\n- 完整最新图：`r5-full-front/three-quarter/right/left/back-512.png`、`r5-full-three-quarter-studio.png`、`r5-normal-192.png`。近脸正面/两45/前侧与完整头部左右后视图亦已从最终主工程重渲染。\n- 同取景完整对照：`r4-r5-full-front-comparison.png`和`r4-r5-full-three-quarter-comparison.png`，均1024×560，实际MCP检查副本用透明锚点取得相同世界边界，仅排布真实渲染。主工程不含锚点。\n- 胸侧浅虚线/靴前微线是UV邻块串色，已将新身体UV缩进半像素；原PNG没改。袖肩/袖口为有体积、不同深度的包覆层。\n- 所有工程修改和检查副本均经原作者真实MCP+expected_revision，回执在`receipts/`。静态0错误/400警告：191薄五官接触提示、199覆盖/叠层、10偏侧分发束非镜像提示，self_test=false。未运行测试、Java、游戏、构建、安装、自动化或上传。静态与编辑器显示不代替用户验收。\n\n最终SHA256：`"+record["model_sha256"]+"`\n",encoding="utf-8")
    print(json.dumps({"sha256":record["model_sha256"],"head_unchanged":head_unchanged,"counts":info["counts"],"static":static["summary"]}),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--quota-checked",action="store_true");parser.add_argument("--refine-hair-roots",action="store_true");parser.add_argument("--close-coverage",action="store_true");parser.add_argument("--build-body",action="store_true");parser.add_argument("--clean-body-uv",action="store_true");parser.add_argument("--finish-records",action="store_true");args=parser.parse_args()
    if not args.quota_checked:raise SystemExit("Read real quota before source work.")
    if args.clean_body_uv:clean_body_uv_and_finalize()
    elif args.finish_records:finish_records()
    elif args.build_body:build_body()
    elif args.close_coverage:close_remaining_coverage()
    elif args.refine_hair_roots:refine_hair_roots()
    else:build_hair()
