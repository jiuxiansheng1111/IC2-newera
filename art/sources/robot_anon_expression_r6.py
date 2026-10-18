"""Original Anon R6 eyes/brows; frozen R5 hair, body, skin and single mouth."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import math
import shutil
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/"art/robot-mcp-workspace/characters/anon-expression-r6"
BASE=WORK.parent/"anon-hair-r5/anon-hair-r5.bbmodel"
MODEL="anon-expression-r6.bbmodel"
FEATURES={"eye_geometry_left","eye_geometry_right","brow_geometry"}
COLORS={"L":"423640","W":"FFF5EE","D":"69818F","B":"94AEB8","A":"B8CED2",
        "P":"4F6676","R":"7A95A3","H":"FFFDFA","E":"29252D"}
spec=importlib.util.spec_from_file_location("own_mcp_helper",ROOT/"art/sources/robot_layered_head.py")
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
helper.WORK=WORK;helper.MODEL=MODEL
mcp=helper.mcp


def save(name,value):
    (WORK/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


def feature_ids(doc):
    names={g["uuid"]:g["name"] for g in doc["groups"]};found=set()
    def walk(nodes,inside=False):
        for node in nodes:
            if isinstance(node,str):
                if inside:found.add(node)
            else:walk(node.get("children",[]),inside or names[node["uuid"]] in FEATURES)
    walk(doc["outliner"]);return found


def freeze():
    before=json.loads(BASE.read_text(encoding="utf-8"));after=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    def selected(doc):
        ids=feature_ids(doc);names={g["uuid"]:g["name"] for g in doc["groups"]}
        def tree(nodes):
            return [n if isinstance(n,str) else {**n,"children":[] if names[n["uuid"]] in FEATURES else tree(n.get("children",[]))} for n in nodes]
        return {"elements":[e for e in doc["elements"] if e["uuid"] not in ids],"groups":doc["groups"],
                "outliner":tree(doc["outliner"]),"resolution":doc["resolution"],"original_atlases":doc["textures"][:5]}
    left,right=selected(before),selected(after)
    result={"identical":left==right,"frozen_elements":len(left["elements"]),"all_original_groups_identical":before["groups"]==after["groups"],
        "scope":"R5 hair, body/outfit, continuous skin/jaw and single small rectangular mouth; original five atlases, UV, resolution and normalized hierarchy",
        "baseline_sha256":hashlib.sha256(BASE.read_bytes()).hexdigest(),"model_sha256":hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest(),
        "frozen_sha256":hashlib.sha256(json.dumps(left,ensure_ascii=False,sort_keys=True,separators=(",",":")).encode()).hexdigest()}
    save("frozen-r5-record.json",result)
    if not result["identical"]:raise SystemExit("Frozen R5 fields changed; stop.")
    return result


def box(name,x,y,w,h,color,parent):
    i=list(COLORS).index(color);uv=[i*4+.5,.5,i*4+3.5,3.5]
    return {"op":"add_cube","name":name,"from":[round(x-w/2,6),round(y-h/2,6),-3.475],
        "to":[round(x+w/2,6),round(y+h/2,6),-3.447],"origin":[x,y,-3.461],"parent":parent,"box_uv":False,"texture":5,
        "faces":{face:{"uv":uv,"texture":5} for face in ("north","south","east","west","up","down")}}


def eye_grid(sign):
    # New rounded opening and full oval iris, independently authored from the clear Anon sheet.
    upper=[7,5,4,3,2,2,1,1,0,0,0,0,1,1,2,2,3,3,4,5]
    lower=[9,11,13,14,15,16,16,17,17,17,17,17,17,16,16,15,14,13,11,9]
    grid={}
    for col in range(20):
        for row in range(upper[col],lower[col]+1):
            if row==upper[col] or (3<=col<=18 and row==upper[col]+1):color="L"
            else:
                radius=((col-9.5)/6.6)**2+((row-7.9)/8.4)**2
                if radius<=1 and row<=16:
                    color="D" if row<=6 else "R" if row<=8 else "A" if row>=12 else "B"
                    if 9<=col<=11 and 7<=row<=11:color="P"
                else:
                    if row>=14 and 5<=col<=14:continue
                    color="W"
            grid[row,col]=color
    # Same world lighting direction for both eyes: highlight is +X/up, never blindly mirrored.
    highlight=round(9.5+sign*1.8)
    for row in (5,6):
        for col in (highlight,highlight+sign):
            if (row,col) in grid:grid[row,col]="H"
    small=round(9.5-sign*2.0)
    if (12,small) in grid:grid[12,small]="H"
    return grid


def feature_operations():
    ops=[];layouts={}
    for side,sign in (("left",1),("right",-1)):
        grid=eye_grid(sign);layouts[side]=[{"row":r,"col":c,"color":v} for (r,c),v in sorted(grid.items())]
        # Adjacent same-color cells share short rectangles of at most two cells, preserving the pixel contour.
        for row in range(18):
            col=0
            while col<20:
                color=grid.get((row,col))
                if color is None:col+=1;continue
                end=col+1
                if end<20 and grid.get((row,end))==color:end+=1
                x=sign*(1.53+((col+end-1)/2-9.5)*.085)
                y=27.43+(28.15-(row+.5)*.085-27.43)*.83
                ops.append(box(f"r6_eye_{side}_{row:02}_{col:02}",x,y,(end-col)*.085,.085*.83,color,f"eye_geometry_{side}"));col=end
        levels=[0,1,1,2,2,2,1,1,0,-1]
        for i,level in enumerate(levels):
            x=sign*(1.53+(i-4.5)*.09);y=28.35+level*.018
            ops.append(box(f"r6_brow_{side}_{i:02}",x,y,.09,.065,"E","brow_geometry"))
    return ops,layouts


def render_views():
    close="r6-face-inspection.bbmodel";shutil.copy2(WORK/MODEL,WORK/close)
    info=mcp("bbmodel_info",{"file":close},"05-face-inspection-info")
    mcp("bbmodel_edit",{"file":close,"expected_revision":info["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"},
        {"op":"remove_node","target":"rear_long_layers"},{"op":"remove_node","target":"side_long_layers"}]},"06-face-inspection-crop")
    for view in ("front","three-quarter"):
        for lighting in ("flat","studio"):
            helper.render(close,view,f"r6-face-{view}-{lighting}.png",640,f"render-{view}-{lighting}",lighting)
    helper.render(MODEL,"three-quarter","r6-normal-192.png",192,"render-normal-192")
    helper.render(MODEL,"front","r6-full-front-512.png",512,"render-full-front")


def records():
    frozen=freeze();info=json.loads(json.loads((WORK/"receipts/03-r6-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/04-r6-static.json").read_text(encoding="utf-8"))["stdout"])
    save("facts.json",{"status":"R6 first eyes/brows prototype, root/user review pending","model_sha256":frozen["model_sha256"],
        "counts":info["counts"],"freeze":frozen,"static":static["summary"],"self_test":False,"author_viewed_images":False,
        "reference_source":"user original jjdao page sheet screenshot; official sites were unavailable, not verified official",
        "highlight_world_direction":"+X/up in both eyes","eye_grid_cell":.085,"user_acceptance":"pending"})
    font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",22)
    for view in ("front","three-quarter"):
        canvas=Image.new("RGB",(1280,688),"#202329");draw=ImageDraw.Draw(canvas)
        draw.text((14,10),"R5：眼眉已被用户否定",font=font,fill="white");draw.text((654,10),"R6：新眼眉首稿，待审阅",font=font,fill="white")
        for x,path in ((0,BASE.parent/f"r5-face-{view}-flat.png"),(640,WORK/f"r6-face-{view}-flat.png")):
            with Image.open(path) as im:canvas.paste(im.convert("RGB"),(x,48))
        canvas.save(WORK/f"r5-r6-{view}-comparison.png")
    print(json.dumps({"sha256":frozen["model_sha256"],"counts":info["counts"],"static":static["summary"]}),flush=True)


def build():
    WORK.mkdir(parents=True,exist_ok=True)
    (WORK/"plan.md").write_text("# 爱音R6：只重建眼眉\n\n用户否定R5眼眉，肯定发型与身形。本轮冻结235发块/44身体/6皮肤/单矩形嘴共286元素、全部原组/父变换/原5atlas与UV/resolution。只眼眉，0mesh。\n\n已亲看根保存的anon-sheet-browser-2026-10-16.png（用户最初jjdao网页2000×1449设定图的实际浏览截图）与演出服mosaic。不是成功读取官方页，不复制图像/几何/UV/纹理资产。\n\n新眼不放大R4格子：轮廓略收宽、竖向打开，短小块阶梯上睫弧，撤去厚平帽；蓝灰虹膜饱满椭圆、底部圆短，小瞳芯而非长针；眼白适量无整圈粗框。黑眉仍小块，较薄短，有轻微阶梯弧并拉近眉眼关系。双眼高光同一世界光照方向+X/up，不盲目镜像。\n\n全程真实原作者MCP及expected_revision，新增独立64纯色色板；旧R5只读。先实际front flat/studio、3q、192及同取景R5对照给根审阅。不自评分、不宣称用户认可；不测试/self_test、Java/构建/游戏/安装/心跳/上传、不新委派。\n",encoding="utf-8")
    shutil.copy2(BASE,WORK/MODEL)
    palette=Image.new("RGBA",(64,64),(0,0,0,0));draw=ImageDraw.Draw(palette)
    for i,color in enumerate(COLORS.values()):draw.rectangle((i*4,0,i*4+3,3),fill="#"+color)
    palette.save(WORK/"original-r6-eye-brow-palette-64.png")
    info=mcp("bbmodel_info",{"file":MODEL},"00-r5-baseline-info")
    mcp("bbmodel_add_texture",{"file":MODEL,"expected_revision":info["revision"],"image":"original-r6-eye-brow-palette-64.png","name":"original-r6-eye-brow-palette"},"01-original-r6-palette")
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"));ops,layouts=feature_operations();save("original-eye-layouts.json",layouts)
    changes=[{"op":"remove_node","target":uuid} for uuid in sorted(feature_ids(doc))]+ops
    changes.append({"op":"set_model_properties","name":"千早爱音｜R6新眼眉原型"})
    for part in range(0,len(changes),450):
        info=mcp("bbmodel_info",{"file":MODEL},f"02-before-expression-{part//450}")
        mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes[part:part+450]},f"02-rebuild-round-eyes-step-brows-{part//450}")
    freeze();mcp("bbmodel_info",{"file":MODEL},"03-r6-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-r6-static")
    render_views();records()


def refine_expression():
    first=WORK/"first-pass";first.mkdir(exist_ok=True)
    for path in [WORK/MODEL,WORK/"facts.json",*WORK.glob("r6-*.png"),*WORK.glob("r5-r6-*.png")]:shutil.copy2(path,first/path.name)
    shutil.copytree(WORK/"receipts",first/"receipts",dirs_exist_ok=True)
    (first/"issues.md").write_text("首稿过圆，完整虹膜圆盘/下方白圈与点状瞳芯、高弓眉形成惊讶凝视，不是最终眼形。\n",encoding="utf-8")
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"));ops,layouts=feature_operations()
    changes=[{"op":"remove_node","target":uuid} for uuid in sorted(feature_ids(doc))]+ops
    for part in range(0,len(changes),450):
        info=mcp("bbmodel_info",{"file":MODEL},f"07-before-calm-open-eyes-{part//450}")
        mcp("bbmodel_edit",{"file":MODEL,"expected_revision":info["revision"],"operations":changes[part:part+450]},f"08-rounded-almond-iris-occlusion-brow-{part//450}")
    save("original-eye-layouts.json",layouts);freeze()
    mcp("bbmodel_info",{"file":MODEL},"03-r6-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"04-r6-static")
    render_views();records()


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--quota-checked",action="store_true");parser.add_argument("--refine-expression",action="store_true");args=parser.parse_args()
    if not args.quota_checked:raise SystemExit("Read actual quota before source work.")
    if args.refine_expression:refine_expression()
    else:build()
