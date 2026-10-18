"""Anon R4: original grid cuboid facial features; frozen R3 hair, skin and body."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import shutil
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/"art/robot-mcp-workspace/characters/anon-face-r4"
R3=WORK.parent/"anon-layered-r3/anon-layered-r3.bbmodel"
MODEL="anon-face-r4.bbmodel"
FEATURE_GROUPS={"eye_geometry_left","eye_geometry_right","brow_geometry","smile_geometry"}
PALETTE={"L":"36303D","W":"FFF4E8","D":"586F80","B":"829FAC","A":"AAC1C7",
         "P":"354D5F","H":"FFFCF6","E":"92717C","M":"BE9290"}
spec=importlib.util.spec_from_file_location("own_r3_helpers",ROOT/"art/sources/robot_layered_head.py")
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
helper.WORK=WORK;helper.MODEL=MODEL
mcp=helper.mcp


def save(name,value):
    (WORK/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


def selection(doc):
    chosen=set()
    names={group["uuid"]:group["name"] for group in doc.get("groups",[])}
    is_feature=lambda node:names.get(node.get("uuid"),node.get("name")) in FEATURE_GROUPS
    def scan(nodes,inside=False):
        for node in nodes:
            if isinstance(node,str):
                if inside: chosen.add(node)
            else:
                scan(node.get("children",[]),inside or is_feature(node))
    scan(doc["outliner"])
    def tree(nodes):
        return [node if isinstance(node,str) else {**node,"children":[] if is_feature(node) else tree(node.get("children",[]))} for node in nodes]
    frozen={"elements":[e for e in doc["elements"] if e["uuid"] not in chosen],"groups":doc.get("groups"),
            "outliner":tree(doc["outliner"]),"resolution":doc["resolution"],"textures":doc["textures"][:3]}
    return frozen,chosen


def verify_freeze():
    original=json.loads(R3.read_text(encoding="utf-8"));current=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    before,_=selection(original);after,_=selection(current)
    encode=lambda value:json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(",",":")).encode("utf-8")
    same=before==after
    record={"identical":same,"frozen_elements":len(before["elements"]),"frozen_groups":len(before["groups"]),
            "scope":"148 hair cubes, 6 continuous skin/jaw cubes, 21 body cubes; all original groups, UV, three atlases, resolution, normalized outliner",
            "frozen_sha256":hashlib.sha256(encode(before)).hexdigest(),"r3_sha256":hashlib.sha256(R3.read_bytes()).hexdigest(),
            "r4_sha256":hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest()}
    save("frozen-r3-record.json",record)
    if not same: raise SystemExit("Frozen R3 structure changed; stop.")
    return record


def cube(name,x,y,w,h,color,parent,depth=.024):
    index=list(PALETTE).index(color);uv=[index*4,0,index*4+4,4]
    return {"op":"add_cube","name":name,"from":[round(x-w/2,6),round(y-h/2,6),-3.446-depth],
            "to":[round(x+w/2,6),round(y+h/2,6),-3.446],"origin":[x,y,-3.458],"parent":parent,
            "box_uv":False,"texture":3,"faces":{face:{"uv":uv,"texture":3} for face in ("north","south","east","west","up","down")}}


def features():
    ops=[];grid={}
    # Original 14 by 9 pixel grid. Short horizontal lash weight, no slanted line frame.
    ranges=[[(3,10,"L")],[(1,12,"L")],[(0,0,"L"),(1,3,"W"),(4,9,"D"),(10,12,"W"),(13,13,"L")],
            [(1,3,"W"),(4,9,"B"),(10,12,"W")],[(1,2,"W"),(3,10,"B"),(11,12,"W")],
            [(2,3,"W"),(4,9,"B"),(10,11,"W")],[(3,3,"W"),(4,9,"B"),(10,10,"W")],
            [(4,9,"A")],[(5,8,"A")]]
    for row,regions in enumerate(ranges):
        for start,end,color in regions:
            for col in range(start,end+1):grid[row,col]=color
    for row in range(3,7):
        for col in (6,7):grid[row,col]="P"
    grid[3,4]="H";grid[3,5]="H";grid[6,8]="H"
    for side,sign in (("left",1),("right",-1)):
        for (row,col),color in sorted(grid.items()):
            x=sign*(1.5+(col-6.5)*.13);y=28.05-(row+.5)*.13
            ops.append(cube(f"eye_pixel_{side}_{row:02}_{col:02}",x,y,.13,.13,color,f"eye_geometry_{side}"))
        for col in range(9):
            ops.append(cube(f"brow_pixel_{side}_{col:02}",sign*(1.5+(col-4)*.115),28.76,.115,.10,"E","brow_geometry",.02))
    # The mouth is exactly one shallow small cuboid, with no corners, tongue or frame.
    ops.append(cube("mouth_single_small_rectangle",0,25.77,.54,.085,"M","smile_geometry",.018))
    return ops,grid


def render_views():
    close="r4-face-inspection.bbmodel";shutil.copy2(WORK/MODEL,WORK/close)
    info=mcp("bbmodel_info",{"file":close},"05-inspection-info")
    mcp("bbmodel_edit",{"file":close,"expected_revision":info["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"},
        {"op":"remove_node","target":"rear_long_layers"},{"op":"remove_node","target":"side_long_layers"}]},"06-inspection-head-only")
    for view in ("front","three-quarter"):
        for lighting in ("flat","studio"):
            helper.render(close,view,f"r4-face-{view}-{lighting}.png",640,f"render-{view}-{lighting}",lighting)
    helper.render(MODEL,"three-quarter","r4-normal-192.png",192,"render-normal")
    helper.render(MODEL,"front","r4-full-front-512.png",512,"render-full-front")


def records():
    freeze=verify_freeze()
    info=json.loads(json.loads((WORK/"receipts/03-r4-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/04-r4-static.json").read_text(encoding="utf-8"))["stdout"])
    ops,grid=features()
    facts={"status":"R4 only facial prototype for user review; R3 face rejected","model_sha256":freeze["r4_sha256"],
           "counts":info["counts"],"eye_pixels_per_eye":len(grid),"eyebrow_cubes_per_side":9,"mouth_cubes":1,
           "local_eye_grid_cell":.13,"static_summary":static["summary"],"self_test":False,
           "frozen":freeze,"author_viewed_images":False,"user_acceptance":"pending"}
    save("facts.json",facts)
    font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",22)
    for view in ("front","three-quarter"):
        canvas=Image.new("RGB",(1280,688),"#202329");draw=ImageDraw.Draw(canvas)
        draw.text((14,10),"R3：脸部已被用户否定",font=font,fill="white")
        draw.text((654,10),"R4：仅五官修订原型，待验收",font=font,fill="white")
        for x,path in ((0,R3.parent/f"final-face-{view}-flat.png"),(640,WORK/f"r4-face-{view}-flat.png")):
            with Image.open(path) as image:canvas.paste(image.convert("RGB"),(x,48))
        canvas.save(WORK/f"r3-r4-{view}-comparison.png")
    (WORK/"README.md").write_text("# 爱音R4：仅五官修订原型\n\nR3脸已被用户否定。本轮仅替换眼、眉、嘴；148发块、6皮肤/浅颌、21身体块，以及所有原分组/UV/三张原贴图/resolution冻结。未扩其余七人。\n\n主工程 `anon-face-r4.bbmodel`；真实正面/前侧flat与studio、192常规图已输出。`r3-r4-front-comparison.png` 与前侧对照均1280×688，来自相同检查副本取景的真实渲染，仅排布。\n\n嘴严格1个浅小长方体，眉眼为原创规整小cube；新独立64色板，旧atlas未改。所有模型写入与检查副本均经原作者真实MCP+expected_revision。详情见facts.json、frozen-r3-record.json与receipts。静态检查不是外观验收，未运行测试/self_test、Java、游戏、构建或安装。\n\nSHA256：`"+freeze["r4_sha256"]+"`\n",encoding="utf-8")
    print(json.dumps({"counts":info["counts"],"sha256":freeze["r4_sha256"],"static":static["summary"]}),flush=True)


def build():
    WORK.mkdir(parents=True,exist_ok=True)
    (WORK/"basis-before-modeling.md").write_text("# R4制作依据\n\n用户明确否定R3脸；这轮先处理五官。已亲看R63实际近景与爱音合照/512参照：仅学习参考短水平上睫、方块组织和小浅矩形嘴的方法，不复制资产；色彩/位置按爱音原创处理。头发、连续皮肤与浅颌、身体全部冻结。\n\n双眼使用镜像的14×9局部网格，0.13单位小正厚度cube；短横深上睫配少量方角，眼白仅在两侧，蓝灰虹膜/短暗瞳/少量方高光。眉在同一基准线上以9小块组成平整短眉，嘴只1浅小矩形，口居中。无细斜线眼框、弧笑、口角、舌头或唇框。\n",encoding="utf-8")
    shutil.copy2(R3,WORK/MODEL)
    palette=Image.new("RGBA",(64,64),(0,0,0,0));draw=ImageDraw.Draw(palette)
    for i,color in enumerate(PALETTE.values()):draw.rectangle((i*4,0,i*4+3,3),fill="#"+color)
    palette.save(WORK/"original-r4-feature-palette-64.png")
    info=mcp("bbmodel_info",{"file":MODEL},"00-r3-copy-info")
    mcp("bbmodel_add_texture",{"file":MODEL,"expected_revision":info["revision"],"image":"original-r4-feature-palette-64.png","name":"original-r4-feature-palette"},"01-original-palette")
    info=mcp("bbmodel_info",{"file":MODEL},"01-after-palette-info")
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"));_,old_ids=selection(doc)
    ops,_=features();operations=[{"op":"remove_node","target":uuid} for uuid in sorted(old_ids)]+ops
    operations.append({"op":"set_model_properties","name":"千早爱音｜R4规整方块五官原型"})
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":operations},"02-grid-eyes-brows-single-mouth")
    verify_freeze()
    mcp("bbmodel_info",{"file":MODEL},"03-r4-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-r4-static")
    render_views();records()


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--quota-checked",action="store_true");args=parser.parse_args()
    if not args.quota_checked:raise SystemExit("Read actual usage before source work.")
    build()
