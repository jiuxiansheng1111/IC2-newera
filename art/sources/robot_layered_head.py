"""Original layered cuboid hair and geometric face study, using real MCP writes.

Only our own R2 model is copied. R63 is read only for aggregate organization
and its hash; no reference cube, UV, texture or animation enters these assets.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "art/robot-mcp-workspace/characters/anon-layered-r3"
R1 = WORK.parent / "anon-r1/anon-tutorial-lowpoly-r1.bbmodel"
R2 = WORK.parent / "anon-face-r2/anon-face-r2.bbmodel"
REFERENCE = Path("C:/Users/罗斌/Desktop/百度网盘资源/R63_YSM_完整动画.bbmodel")
MODEL = "anon-layered-r3.bbmodel"
TOOLS = Path.home() / ".codex/tmp/ic2-blockbench-mcp-178ce46f"
COLORS = {"hair": "E8AEC0", "light": "EEBBCB", "low": "DEA2B7", "deep": "D798B0", "skin": "F3D7C8",
          "lash": "6C4B5A", "brow": "AC748C", "white": "FFF8EF", "iris": "7A99A7", "iris_top": "556E80",
          "pupil": "3E5869", "shine": "FFFDF4", "mouth": "AA6B80", "tongue": "D692A2", "lip": "C28A96", "clip": "596675",
          "iris_bottom": "A4BEC2"}
UV = {name: [(i%16)*4, (i//16)*4, (i%16)*4+4, (i//16)*4+4] for i, name in enumerate(COLORS)}
PLAN = """# 爱音 R3：长方体叠层头部 — 制作前方案

用户最新明确否定 R2，要求学习参考以一堆长方形叠加头发与五官，精致、全面。
不再按旧的“少量方块”标准做帽壳；本轮仍只爱音一个样本，非 head 的 21 元素/7 骨骼/UV/原 atlas/resolution 冻结。

已经重新亲看本轮参考近脸及旧头、侧、后截图、用户合照、爱音512像素参照，读完整参考审阅。
R63 实际是粉发人物，只学习有厚度长方体按方向、宽薄变化和转折相接的方法；不复制其几何、UV、贴图或动画。

## 头发的主要发束草图说明

              偏侧分点（模型x约-1.5）
           /  冠顶弧向围合  \\
        短侧分组 → 多条宽薄不同的侧扫刘海 → 颊侧锁发
       太阳穴前束      清楚的眉眼区       太阳穴后束
             \\    侧后体积覆盖    /
               不同折点的长发组
           背中流向 / 左后流向 / 右后流向
            少量内收、外翻和不齐的收尾

- 冠顶由跨前额—顶冠—后脑的数条方向块围合，长度/倾斜跟随拱度；没有一整块平顶或后板。
- 刘海约八个主要分束，偏侧分点向一侧扫；前后层厚度/发端位置不同，不做两块大矩形额帘。
- 颊侧前束与耳后束分开，根部接进冠顶，侧发框住脸而不是整片大侧板。
- 后脑覆盖接多组侧后长发，各条路线的折点、长度和宽度不同，避免同层横条、齐尾矩形墙。
- 发束由实际有厚度的长方体相接；转折处少量重叠藏根，材质大多同一纯粉色，由几何和光照形成层次。
  不把碎片乱贴成发丝，不以方块数或统一每束层数充当完成指标。

## 两阶段与临时脸

第一阶段只实际重建完整头发。R2 连续皮肤和五官暂用于摆位，**临时脸不是最终成品**；
只调整被头发盖住的颅内承托与顶部范围来避免新冠顶露肤。
先实际渲染正/前侧/侧/后及常规全身给根看，收到结构指导后再做第二阶段。
第二阶段将眼白/虹膜/瞳/高光、上睫/眼角/眉、口腔/唇边分别以薄且有正厚度的真实长方体组合，
不会只微调三张透明眼口贴花；连续主脸与短浅颌保留，不堆皮肤横切片，不做长斜叉。

全部 .bbmodel 修改、检查副本调整与渲染经过原作者真实 MCP，写入带 expected_revision。
不运行测试或 self_test，不做 Java/游戏/构建/安装/存档/密钥/上传/自动化，不扩其他七人。
"""


def dump(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def mcp(tool: str, args: dict, label: str) -> dict | None:
    params = WORK / "receipts" / f"{label}-args.json"
    dump(params, args)
    bun = TOOLS / "node_modules/@oven/bun-windows-x64/bin/bun.exe"
    node = Path.home() / ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin"
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join((str(node), str(bun.parent), env.get("PATH", "")))
    env["BB_RENDER_HOME"] = str(TOOLS / "render-runtime")
    env["npm_config_proxy"] = env["npm_config_https_proxy"] = "http://127.0.0.1:7897"
    result = subprocess.run([str(bun), "run", str(TOOLS / "headless/call.ts"), "--root", str(WORK), tool, "@" + str(params)],
                            cwd=TOOLS, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    dump(WORK / "receipts" / f"{label}.json", {"tool": tool, "transport": "real upstream MCP stdio", "exit_code": result.returncode,
         "recorded_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "stdout": result.stdout, "stderr": result.stderr})
    print(json.dumps({"tool": tool, "label": label, "exit_code": result.returncode}), flush=True)
    if result.returncode:
        print(result.stdout + result.stderr, file=sys.stderr)
        raise SystemExit(result.returncode)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def unit(v: list[float]) -> list[float]:
    length = math.sqrt(sum(a * a for a in v))
    if length < 1e-9:
        raise ValueError("Zero direction")
    return [a / length for a in v]


def cross(a: list[float], b: list[float]) -> list[float]:
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def cuboid(name: str, center: list[float], size: list[float], color: str, parent: str, rotation: list[float] | None = None) -> dict:
    return {"op": "add_cube", "name": name, "from": [round(c - s / 2, 6) for c, s in zip(center, size)],
            "to": [round(c + s / 2, 6) for c, s in zip(center, size)], "origin": center,
            "rotation": rotation or [0, 0, 0], "parent": parent, "texture": 2, "box_uv": False,
            "faces": {face: {"uv": UV[color], "texture": 2} for face in ("north", "east", "south", "west", "up", "down")}}


def segment(name: str, a: list[float], b: list[float], width: float, thickness: float, normal: list[float],
            parent: str, color: str = "hair", overlap: float = 0.16) -> dict:
    delta = [b[i] - a[i] for i in range(3)]
    length = math.sqrt(sum(v * v for v in delta))
    y_axis = unit(delta)
    x_axis = unit(cross(y_axis, normal))
    z_axis = unit(cross(x_axis, y_axis))
    # Columns of the rotation matrix are local X/Y/Z, converted to Z·Y·X Euler.
    ry = math.asin(max(-1, min(1, -x_axis[2])))
    if abs(math.cos(ry)) > 1e-7:
        rx, rz = math.atan2(y_axis[2], z_axis[2]), math.atan2(x_axis[1], x_axis[0])
    else:
        rx, rz = 0, math.atan2(-y_axis[0], y_axis[1])
    rotation = [round(math.degrees(v), 6) for v in (rx, ry, rz)]
    center = [round((a[i] + b[i]) / 2, 6) for i in range(3)]
    return cuboid(name, center, [width, length + overlap, thickness], color, parent, rotation)


def hair_operations() -> tuple[list[dict], list[dict]]:
    ops: list[dict] = []
    routes: list[dict] = []
    groups = ("crown_layers", "side_swept_fringe", "cheek_and_temple_locks", "rear_long_layers", "side_long_layers", "hair_clips")
    for name in groups:
        ops.append({"op": "add_group", "name": name, "parent": "head", "origin": [0, 24, 0]})

    def lock(name: str, points: list, widths: list, thicknesses: list | float, normal: list,
             parent: str, color: str = "hair") -> None:
        routes.append({"name": name, "points": points, "widths": widths, "thicknesses": thicknesses, "parent": parent})
        if isinstance(thicknesses, (int, float)):
            thicknesses = [thicknesses] * (len(points) - 1)
        for i, (a, b) in enumerate(zip(points, points[1:])):
            ops.append(segment(f"{name}_{i}", a, b, widths[i], thicknesses[i], normal, parent, color))

    # Seven original crown routes. Crosswise heights make an actual arched crown.
    for i, x in enumerate((-3.45, -2.3, -1.15, 0, 1.15, 2.3, 3.45)):
        rise = 0.95 * (1 - (abs(x) / 4) ** 1.5)
        front_turn = [-.32, .27, -.08, .34, -.26, .12, -.18][i]
        back_turn = [.22, -.31, .13, -.08, .30, -.20, .09][i]
        height = [.04, -.06, .09, -.03, .06, -.05, .02][i]
        width = [1.54, 1.50, 1.58, 1.54, 1.52, 1.57, 1.50][i]
        points = [[x, 30.6 + rise, -3.25], [x * 0.96, 31.85 + rise + height, -.35 + front_turn],
                  [x * 0.94, 31.35 + rise - height, 2.25 + back_turn], [x * 0.98, 29.6 + rise * 0.3, 3.4]]
        lock(f"crown_arc_{i}", points, [width, width, width+.05], [0.75, 0.8, 0.8], [0, 1, 0], "crown_layers", "hair")

    fringe = [
        ("sweep_inner", [[-1.7,32.05,-2.95],[-.8,30.9,-3.8],[.8,30.2,-4.03]], [1.4,1.4]),
        ("sweep_center", [[-.65,32.3,-2.9],[.5,31.05,-3.85],[1.9,29.9,-4.05],[2.65,29.4,-3.9]], [1.45,1.5,1.02]),
        ("sweep_outer", [[.45,32.2,-2.8],[1.75,31.05,-3.7],[2.95,29.8,-3.85],[3.4,28.55,-3.55]], [1.45,1.25,.78]),
        ("sweep_edge", [[1.65,31.95,-2.4],[2.8,31.0,-3.05],[3.5,30.1,-3.0]], [1.25,1.0]),
        ("sweep_temple", [[2.85,31.35,-2.2],[3.65,30.1,-2.9],[3.65,28.9,-2.8]], [1.1,.85]),
        ("part_short", [[-1.65,32.05,-2.85],[-2.5,30.8,-3.65],[-3.4,29.45,-3.4]], [1.4,1.05]),
        ("part_outer", [[-2.65,31.65,-2.35],[-3.4,30.4,-3.05],[-3.55,29.0,-2.75]], [1.1,.85]),
        ("part_under", [[-2.0,31.8,-2.65],[-1.1,30.95,-3.15]], [1.0]),
    ]
    for name, points, widths in fringe:
        lock(name, points, widths, 0.52, [0, 0, -1], "side_swept_fringe", "light")

    # Two front locks and two secondary temple locks per side, not full side walls.
    for side, sign in (("left", 1), ("right", -1)):
        def reflect(points: list) -> list:
            return [[sign*x, y, z] for x,y,z in points]
        lock(f"cheek_front_{side}", reflect([[3.15,31.2,-2.8],[3.8,28.3,-3.0],[3.65,25.6,-3.2],[3.25,24.2,-2.85]]),
             [1.18,0.8,0.5], [0.66,0.6,0.42], [sign,0,-0.35], "cheek_and_temple_locks", "hair")
        lock(f"temple_mid_{side}", reflect([[3.5,31.65,-1.3],[4.0,28.2,-1.1],[3.95,25.5,-0.8],[3.65,23.7,-0.7]]),
             [1.15,1.1,0.72], [0.72,0.72,0.5], [sign,0,0], "cheek_and_temple_locks", "hair")
        lock(f"temple_under_{side}", reflect([[3.45,31.5,-2.1],[3.8,28.1,-2.05],[3.7,25.6,-1.9]]),
             [1.35,1.25], [0.68,0.65], [sign,0,-.1], "cheek_and_temple_locks", "low")
        lock(f"temple_rear_under_{side}", reflect([[3.4,31.65,0],[3.9,28.3,.05],[3.85,25.4,.3]]),
             [1.45,1.35], [.72,.68], [sign,0,.05], "cheek_and_temple_locks", "low")
        lock(f"temple_back_{side}", reflect([[3.55,31.3,0.5],[4.05,27.9,1.05],[4.0,25.1,1.5]]),
             [1.6,1.2], [0.8,0.7], [sign,0,0.2], "cheek_and_temple_locks", "low")
        ops.append(cuboid(f"temple_crown_bridge_{side}",[sign*3.25,30.5,1.35],[.9,1.5,1.6],"low",
                          "cheek_and_temple_locks",[0,0,-sign*10]))

    # Long rear ribbons have different roots, bends, endpoints and segment counts.
    rear = [
        (-76,[30.3,27.8,24.7,21.6,18.7,16.1,14.8],[-.1,.0,.2,.45,.35,.05,-.25]),
        (-57,[31.0,28.0,24.9,22.0,19.0,16.5],[0,.05,.1,-.15,-.35,-.15]),
        (-38,[31.3,28.7,25.7,23.0,20.1,17.4,15.2],[0,0,-.05,.05,.3,.45,.2]),
        (-18,[31.6,28.8,25.6,22.5,19.3,16.8,14.4],[0,0,.0,.2,.1,-.1,-.2]),
        (2,[31.6,28.9,26.1,23.4,20.7,18.0,15.5],[0,0,.1,.15,-.1,-.3,-.1]),
        (22,[31.5,28.5,25.4,22.3,19.4,16.7,14.8],[0,-.05,-.1,.05,.35,.4,.15]),
        (42,[31.2,28.4,25.3,22.1,19.2,16.6],[0,.05,.15,.3,.05,-.15]),
        (63,[30.8,28.2,25.1,22.0,19.0,16.2,14.9],[0,0,-.1,-.25,-.1,.2,.3]),
        (81,[30.2,27.5,24.6,21.8,18.7,16.7],[0,.05,.1,.3,.2,-.1]),
    ]
    for i, (degrees, ys, waves) in enumerate(rear):
        angle = math.radians(degrees)
        points=[]
        for j,y in enumerate(ys):
            t=j/(len(ys)-1)
            radius=3.65+0.65*math.sin(t*math.pi*.85)
            depth_bias=[.15,-.25,.35,-.1,.45,.05,-.3,.2,.1][i]*math.sin(t*math.pi)
            points.append([round(radius*math.sin(angle)+waves[j],3),y,
                           round(radius*math.cos(angle)+0.15+0.6*t+depth_bias,3)])
        a,b=points[-2:]; length=math.sqrt(sum((b[k]-a[k])**2 for k in range(3)))
        tip=.75+.15*(i%3); fraction=max(.15,1-tip/length)
        points.insert(len(points)-1,[round(a[k]+(b[k]-a[k])*fraction,3) for k in range(3)])
        base=1.45 if abs(degrees)<50 else 1.3
        widths=[base]*(len(points)-1); widths[-3:]=[base*.86,base*.66,base*.38]
        lock(f"rear_flow_{i}",points,widths,[0.78]*(len(widths)-2)+[.56,.32],
             [math.sin(angle),0,math.cos(angle)],"rear_long_layers","hair" if i%4 else "low")

    # Two free side cascades give a lateral long-hair silhouette rather than a back wall.
    for side, sign in (("left",1),("right",-1)):
        points=[[sign*3.9,27.0,.6],[sign*4.25,24.2,.85],[sign*4.65,21.6,1.1],
                [sign*4.9,18.8,1.55],[sign*4.6,16.5,1.8],[sign*4.15,15.2,1.55]]
        a,b=points[-2:]; points.insert(len(points)-1,[(a[k]+b[k])/2 for k in range(3)])
        lock(f"side_cascade_{side}",points,[1.05,1.15,1.2,1.0,.78,.46], [0.7,.75,.75,.65,.5,.3],
             [sign,0,0],"side_long_layers","light")
        points=[[sign*3.8,26.4,2.0],[sign*4.4,23.5,2.4],[sign*5.1,20.6,2.9],
                [sign*5.35,18.4,3.1],[sign*4.95,16.7,3.0]]
        a,b=points[-2:]; points.insert(len(points)-1,[(a[k]+b[k])/2 for k in range(3)])
        lock(f"outer_cascade_{side}",points,[1.1,1.2,1.0,.78,.45], [0.7,.7,.65,.5,.3],
             [sign,0,.3],"side_long_layers","hair")

    # Original double simple clips, each a bar crossing the front-side lock.
    for i, y in enumerate((29.15,28.65)):
        ops.append(segment(f"double_clip_{i}",[3.4,y,-3.65],[4.05,y+.08,-3.45],.19,.15,[0,0,-1],"hair_clips","clip",.02))
    return ops,routes


def atlas() -> None:
    image=Image.new("RGBA",(64,64),(0,0,0,0)); draw=ImageDraw.Draw(image)
    for name,coords in UV.items():
        x1,y1,x2,y2=coords
        draw.rectangle((x1,y1,x2-1,y2-1),fill="#"+COLORS[name])
    image.save(WORK/"original-layer-palette-64.png")
    dump(WORK/"original-palette.json",COLORS)


def fingerprints() -> None:
    spec=importlib.util.spec_from_file_location("own_r2_static_fingerprint",ROOT/"art/sources/robot_face_refinement.py")
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    records={}
    for label,path in (("r1",R1),("r2",R2),("r3",WORK/MODEL)):
        digest,selected=module.body_fingerprint(json.loads(path.read_text(encoding="utf-8")))
        records[label]={"file":str(path),"file_sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
                        "body_sha256":digest,"non_head_elements":len(selected["elements"]),"non_head_bones":len(selected["groups"])}
    same=len({r["body_sha256"] for r in records.values()})==1
    dump(WORK/"non-head-freeze-record.json",{"identical":same,"records":records,
        "includes":"21 non-head elements with every UV/texture field, 7 bones, normalized outliner, first embedded atlas and resolution",
        "reference_sha256":hashlib.sha256(REFERENCE.read_bytes()).hexdigest()})
    if not same:
        raise SystemExit("Non-head changed: stop.")


def start_hair() -> None:
    WORK.mkdir(parents=True,exist_ok=True)
    if (WORK/MODEL).exists():
        raise SystemExit("R3 exists; apply explicit MCP revisions instead.")
    (WORK/"plan-before-modeling.md").write_text(PLAN,encoding="utf-8")
    atlas(); shutil.copy2(R2,WORK/MODEL)
    current=mcp("bbmodel_info",{"file":MODEL},"01-copied-own-r2-info")
    current=mcp("bbmodel_add_texture",{"file":MODEL,"image":"original-layer-palette-64.png","name":"original-layer-palette-64.png",
         "expected_revision":current["revision"]},"02-add-original-layer-palette")
    old=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    removed=[e["name"] for e in old["elements"] if e["name"].startswith("hair")]
    ops=[{"op":"remove_node","target":name} for name in removed]
    # This is hidden temporary facial support, not a final face design.
    ops += [{"op":"update_node","target":"head_inner_support","from":[-3.15,25.1,-2.55],"to":[3.15,31.25,2.5]},
            {"op":"update_node","target":"face_continuous_main","to":[3.5,30.65,-2.45]}]
    new,routes=hair_operations(); ops+=new
    ops.append({"op":"set_model_properties","name":"千早爱音｜R3叠层头发阶段（临时R2脸）"})
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":ops},"03-rebuild-all-hair-real-cuboids")
    dump(WORK/"authored-strand-routes.json",{"routes":routes,"removed_r2_hair_elements":removed,
         "method":"independent authored paths and thick oriented cuboids; no reference element data imported",
         "temporary_face":"R2 only for placement, rejected and not a final face"})
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"04-hair-stage-info")
    mcp("bbmodel_outline",{"file":MODEL,"transforms":True},"05-hair-stage-outline")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"06-hair-stage-static")
    render_hair()


def render(file: str,view: str,output: str,size: int,label: str,lighting: str="flat") -> None:
    mcp("bbmodel_render",{"file":file,"view":view,"width":size,"height":size,"orthographic":True,
        "lighting":lighting,"output":output,"overwrite":True},label)


def render_hair() -> None:
    helper="hair-stage-inspection.bbmodel"
    if (WORK/helper).exists():
        stamp=dt.datetime.now().strftime("%H%M%S")
        shutil.copy2(WORK/helper,WORK/f"hair-inspection-previous-{stamp}.bbmodel")
    shutil.copy2(WORK/MODEL,WORK/helper)
    current=mcp("bbmodel_info",{"file":helper},"hair-inspection-info")
    mcp("bbmodel_edit",{"file":helper,"expected_revision":current["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"}]},"hair-inspection-head-only")
    # All newly authored long hair remains in this inspection, so it exposes the whole hairstyle.
    for view in ("front","three-quarter","right","back","left"):
        render(helper,view,f"hair-stage-{view}.png",768,f"hair-stage-render-{view}")
    render(helper,"three-quarter","hair-stage-three-quarter-studio.png",768,"hair-stage-studio-volume","studio")
    render(MODEL,"front","hair-stage-full-front-512.png",512,"hair-stage-full-front")
    render(MODEL,"three-quarter","hair-stage-normal-192.png",192,"hair-stage-normal")


def correct_hair_coverage() -> None:
    snapshot=WORK/"first-hair-prototype"; snapshot.mkdir(exist_ok=True)
    for path in [WORK/MODEL,*WORK.glob("hair-stage-*.png")]:
        shutil.copy2(path,snapshot/path.name)
    current=mcp("bbmodel_info",{"file":MODEL},"07-before-temple-coverage")
    new,routes=hair_operations()
    additions=[op for op in new if op["op"]=="add_cube" and op["name"].startswith("temple_under_")]
    ops=[{"op":"update_node","target":"face_continuous_main","to":[3.5,30.65,-2.45]}]+additions
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":ops},"08-connect-temple-under-locks")
    dump(WORK/"authored-strand-routes.json",{"routes":routes,"face":"temporary rejected R2 face for placement only"})
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"04-hair-stage-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"06-hair-stage-static")
    render_hair()


def refine_hair_flow() -> None:
    snapshot=WORK/"before-root-flow-corrections"; snapshot.mkdir(exist_ok=True)
    for path in [WORK/MODEL,*WORK.glob("hair-stage-*.png")]:
        shutil.copy2(path,snapshot/path.name)
    shutil.copytree(WORK/"receipts",snapshot/"receipts",dirs_exist_ok=True)
    current=mcp("bbmodel_info",{"file":MODEL},"09-before-root-flow-corrections")
    new,routes=hair_operations()
    groups=[op["name"] for op in new if op["op"]=="add_group"]
    ops=[{"op":"remove_node","target":group} for group in groups]+new
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":ops},"10-main-sweep-short-tapers-temple-depth")
    dump(WORK/"authored-strand-routes.json",{"routes":routes,"face":"temporary rejected R2 face for placement only"})
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"04-hair-stage-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"06-hair-stage-static")
    render_hair()


def bury_temple_roots() -> None:
    current=mcp("bbmodel_info",{"file":MODEL},"11-before-burying-temple-roots")
    new,_=hair_operations()
    prefixes=("cheek_front_","temple_mid_","temple_under_","temple_rear_under_","temple_back_")
    chosen=[op for op in new if op["op"]=="add_cube" and op["name"].startswith(prefixes) and op["name"].endswith("_0")]
    ops=[{"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation")}} for op in chosen]
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":ops},"12-bury-existing-temple-root-ends")
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"04-hair-stage-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"06-hair-stage-static")
    render_hair()


def close_final_hair_seam() -> None:
    current=mcp("bbmodel_info",{"file":MODEL},"13-before-final-hair-seam")
    new,_=hair_operations()
    chosen=[op for op in new if op["op"]=="add_cube" and op["name"].startswith("temple_back_")]
    ops=[{"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation")}} for op in chosen]
    existing={e["name"] for e in json.loads((WORK/MODEL).read_text(encoding="utf-8"))["elements"]}
    ops += [op for op in new if op["op"]=="add_cube" and op["name"].startswith("temple_crown_bridge_") and op["name"] not in existing]
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":ops},"14-close-back-temple-root-overlap")
    fingerprints()
    helper="hair-seam-inspection.bbmodel"; shutil.copy2(WORK/MODEL,WORK/helper)
    current=mcp("bbmodel_info",{"file":helper},"hair-seam-inspection-info")
    mcp("bbmodel_edit",{"file":helper,"expected_revision":current["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"}]},"hair-seam-inspection-head-only")
    render(helper,"right","hair-seam-right.png",768,"hair-seam-check-right")
    render(helper,"three-quarter","hair-seam-three-quarter-studio.png",768,"hair-seam-check-studio","studio")


def geometric_face_operations() -> list[dict]:
    ops=[]
    for group in ("face_skin_structure","eye_geometry_left","eye_geometry_right","brow_geometry","smile_geometry"):
        ops.append({"op":"add_group","name":group,"parent":"head","origin":[0,24,0]})

    def box(name: str, center: list, size: list, color: str, parent: str, rotation: list | None=None) -> None:
        ops.append(cuboid(name,center,size,color,parent,rotation))

    def bar(name: str, a: list, b: list, width: float, color: str, parent: str, thickness: float=.05) -> None:
        ops.append(segment(name,a,b,width,thickness,[0,0,-1],parent,color,.012))

    box("face_skin_inner_support",[0,27.575,-.05],[5.1,5.05,4.9],"skin","face_skin_structure")
    box("face_skin_continuous_main",[0,27.625,-2.95],[6.7,5.65,1.0],"skin","face_skin_structure")
    box("face_skin_chin_short",[0,24.8,-2.86],[5.1,.5,1.14],"skin","face_skin_structure")
    box("face_skin_jaw_left",[2.55,24.9,-2.85],[1.4,.45,1.12],"skin","face_skin_structure",[0,0,5])
    box("face_skin_jaw_right",[-2.55,24.9,-2.85],[1.4,.45,1.12],"skin","face_skin_structure",[0,0,-5])
    box("face_skin_hidden_connector",[0,24.6,-.7],[2.5,1.2,3.8],"skin","face_skin_structure")

    for side,sign in (("left",1),("right",-1)):
        parent=f"eye_geometry_{side}"
        def eye_box(name: str,u: float,y: float,z: float,w: float,h: float,d: float,color: str) -> None:
            box(f"{name}_{side}",[sign*u,y,z],[w,h,d],color,parent)
        def eye_bar(name: str,a: list,b: list,w: float,color: str,depth: float=.07) -> None:
            bar(f"{name}_{side}",[sign*a[0],a[1],a[2]],[sign*b[0],b[1],b[2]],w,color,parent,depth)
        # Five touching opaque white solids form the opening, not an alpha decal.
        eye_box("white_main",1.5,27.4,-3.4625,1.3,1.1,.035,"white")
        eye_box("white_inner_corner",.725,27.4,-3.4625,.25,.7,.035,"white")
        eye_box("white_outer_corner",2.275,27.415,-3.4625,.25,.77,.035,"white")
        eye_box("white_upper_center",1.5,28.0,-3.4625,.8,.1,.035,"white")
        eye_box("white_lower_center",1.5,26.79,-3.4625,1.0,.12,.035,"white")
        eye_box("iris_main",1.5,27.38,-3.488,.78,1.0,.026,"iris")
        eye_box("iris_upper_band",1.5,27.80,-3.51,.74,.16,.026,"iris_top")
        eye_box("iris_lower_band",1.5,26.99,-3.51,.60,.15,.026,"iris_bottom")
        eye_box("pupil_short",1.5,27.40,-3.522,.18,.55,.06,"pupil")
        eye_box("eye_highlight_main",1.68,27.70,-3.54,.14,.17,.08,"shine")
        eye_box("eye_highlight_small",1.29,27.06,-3.54,.07,.08,.08,"shine")
        eye_bar("lash_inner",[.64,27.76,-3.525],[1.18,28.01,-3.525],.10,"lash")
        eye_bar("lash_upper",[1.17,28.01,-3.525],[1.95,28.035,-3.525],.12,"lash")
        eye_bar("lash_outer",[1.94,28.035,-3.525],[2.43,27.81,-3.525],.115,"lash")
        eye_bar("eye_outer_corner",[2.42,27.81,-3.515],[2.52,27.63,-3.515],.08,"lash",.06)
        eye_bar("lower_lid_outer",[2.33,27.03,-3.49],[1.95,26.81,-3.49],.035,"lip",.06)
        eye_bar("lower_lid_inner",[.69,27.06,-3.49],[.98,26.86,-3.49],.03,"lip",.06)
        # Thin separated eyebrows, three shallow connected cuboids each.
        brow_points=[[.78,28.63,-3.47],[1.3,28.78,-3.47],[1.9,28.76,-3.47],[2.25,28.62,-3.47]]
        for i,(a,b) in enumerate(zip(brow_points,brow_points[1:])):
            bar(f"brow_{side}_{i}",[sign*a[0],a[1],a[2]],[sign*b[0],b[1],b[2]],.065,"brow","brow_geometry",.05)

    # One shallow cavity layer meets the skin; two short corner edges avoid a lip frame.
    box("smile_cavity_center",[0,25.69,-3.457],[.85,.27,.018],"mouth","smile_geometry")
    box("smile_cavity_bottom",[0,25.535,-3.457],[.55,.09,.018],"mouth","smile_geometry")
    for sign in (-1,1):
        box(f"smile_cavity_corner_{sign}",[sign*.5125,25.80,-3.457],[.175,.16,.018],"mouth","smile_geometry")
        bar(f"smile_corner_edge_{sign}",[sign*.60,25.88,-3.466],[sign*.36,25.825,-3.466],
            .02,"lip","smile_geometry",.016)
    box("smile_tongue",[0,25.545,-3.474],[.48,.07,.018],"tongue","smile_geometry")
    return ops


def build_geometric_face() -> None:
    frozen=WORK/"hair-stage-frozen"; frozen.mkdir(exist_ok=True)
    for path in [WORK/MODEL,*WORK.glob("hair-stage-*.png"),*WORK.glob("hair-seam-*.png")]:
        shutil.copy2(path,frozen/path.name)
    shutil.copytree(WORK/"receipts",frozen/"receipts",dirs_exist_ok=True)
    atlas()
    current=mcp("bbmodel_info",{"file":MODEL},"15-before-geometric-face")
    image="data:image/png;base64,"+base64.b64encode((WORK/"original-layer-palette-64.png").read_bytes()).decode("ascii")
    old_names=("head_inner_support","face_continuous_main","jaw_center_short","jaw_edge_left","jaw_edge_right",
               "jaw_hidden_connection","eye_layer_left","eye_layer_right","brow_layer_left","brow_layer_right","smile_layer")
    ops=[{"op":"update_texture","target":2,"source":image,"width":64,"height":64}]
    ops += [{"op":"remove_node","target":name} for name in old_names]
    ops += geometric_face_operations()
    ops.append({"op":"set_model_properties","name":"千早爱音｜R3叠层发束与长方体五官原型"})
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":ops},"16-rebuild-positive-depth-geometric-face")
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"17-geometric-face-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"18-geometric-face-static")
    helper="face-stage-inspection.bbmodel"; shutil.copy2(WORK/MODEL,WORK/helper)
    current=mcp("bbmodel_info",{"file":helper},"face-inspection-info")
    mcp("bbmodel_edit",{"file":helper,"expected_revision":current["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"},
        {"op":"remove_node","target":"rear_long_layers"},{"op":"remove_node","target":"side_long_layers"}]},"face-inspection-head-only")
    for view in ("front","three-quarter"):
        for lighting in ("flat","studio"):
            render(helper,view,f"face-stage-{view}-{lighting}.png",640,f"face-stage-{view}-{lighting}",lighting)
    render(MODEL,"three-quarter","face-stage-normal-192.png",192,"face-stage-normal")
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    face_ops=geometric_face_operations()
    dump(WORK/"geometric-face-facts.json",{"face_cube_count":sum(op["op"]=="add_cube" for op in face_ops),
         "every_feature_positive_depth":True,"new_features_use_texture_index":2,
         "old_r2_decal_nodes_removed":list(old_names),
         "texture_1_face_references":sum(face.get("texture")==1 for e in doc["elements"] for face in e.get("faces",{}).values()),
         "status":"first actual geometric face prototype, pending root visual review and user acceptance"})


def refine_shallow_mouth_and_crown() -> None:
    frozen=WORK/"first-geometric-face"; frozen.mkdir(exist_ok=True)
    for path in [WORK/MODEL,*WORK.glob("face-stage-*.png")]:
        shutil.copy2(path,frozen/path.name)
    shutil.copytree(WORK/"receipts",frozen/"receipts",dirs_exist_ok=True)
    current=mcp("bbmodel_info",{"file":MODEL},"19-before-shallow-mouth-crown")
    hair_ops,routes=hair_operations()
    face_ops=geometric_face_operations()
    changed=[op for op in hair_ops if op["op"]=="add_cube" and op["name"].startswith("crown_arc_")]
    changed += [op for op in face_ops if op["op"]=="add_cube" and op["name"] in
                ("face_skin_inner_support","face_skin_continuous_main")]
    operations=[{"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation")}} for op in changed]
    operations.append({"op":"remove_node","target":"smile_geometry"})
    operations += [op for op in face_ops if (op["op"]=="add_group" and op["name"]=="smile_geometry") or
                   (op["op"]=="add_cube" and op.get("parent")=="smile_geometry")]
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":operations},"20-shallow-mouth-stagger-crown-hide-skin")
    dump(WORK/"authored-strand-routes.json",{"routes":routes,"face":"independent positive-depth geometric face; rejected R2 decals removed"})
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"21-final-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"22-final-static")
    render_final_views()


def render_final_views() -> None:
    # Inspection copies are altered only by MCP; the main retains all hair and the body.
    whole="final-head-inspection.bbmodel"; shutil.copy2(WORK/MODEL,WORK/whole)
    current=mcp("bbmodel_info",{"file":whole},"final-head-before-detach")
    mcp("bbmodel_edit",{"file":whole,"expected_revision":current["revision"],"operations":[
        {"op":"update_node","target":"head","parent":None},{"op":"remove_node","target":"root"}]},"final-head-detach")
    for view in ("front","three-quarter","right","left","back"):
        render(whole,view,f"final-head-{view}.png",768,f"final-head-{view}")
    render(whole,"three-quarter","final-head-three-quarter-studio.png",768,"final-head-studio","studio")
    close="final-face-inspection.bbmodel"; shutil.copy2(WORK/whole,WORK/close)
    current=mcp("bbmodel_info",{"file":close},"final-face-before-crop")
    mcp("bbmodel_edit",{"file":close,"expected_revision":current["revision"],"operations":[
        {"op":"remove_node","target":"rear_long_layers"},{"op":"remove_node","target":"side_long_layers"}]},"final-face-inspection-crop")
    for view in ("front","three-quarter"):
        for lighting in ("flat","studio"):
            render(close,view,f"final-face-{view}-{lighting}.png",640,f"final-face-{view}-{lighting}",lighting)
    render(close,"right","final-face-right.png",640,"final-face-right")
    render(close,"left","final-face-left.png",640,"final-face-left")
    for view in ("front","three-quarter"):
        render(MODEL,view,f"final-full-{view}-512.png",512,f"final-full-{view}")
    render(MODEL,"three-quarter","final-normal-192.png",192,"final-normal")


def cover_remaining_forehead_corner() -> None:
    current=mcp("bbmodel_info",{"file":MODEL},"23-before-front-temple-root")
    hair_ops,routes=hair_operations()
    chosen=[op for op in hair_ops if op["op"]=="add_cube" and op["name"].startswith("cheek_front_") and op["name"].endswith("_0")]
    operations=[{"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation")}} for op in chosen]
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":operations},"24-overlap-existing-front-temple-roots")
    dump(WORK/"authored-strand-routes.json",{"routes":routes,"face":"independent positive-depth geometric face; rejected R2 decals removed"})
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"21-final-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"22-final-static")
    render_final_views()


def finish_records() -> None:
    """Arrange unmodified real renders and record the frozen prototype's facts."""
    fingerprints()
    doc=json.loads((WORK/MODEL).read_text(encoding="utf-8"))
    info=json.loads(json.loads((WORK/"receipts/21-final-info.json").read_text(encoding="utf-8"))["stdout"])
    static=json.loads(json.loads((WORK/"receipts/22-final-static.json").read_text(encoding="utf-8"))["stdout"])
    face_names={op["name"] for op in geometric_face_operations() if op["op"]=="add_cube"}
    face_elements=[element for element in doc["elements"] if element["name"] in face_names]
    minimum_depth=min(min(b-a for a,b in zip(element["from"],element["to"])) for element in face_elements)
    pngs=[]
    for path in sorted(WORK.glob("final-*.png")):
        with Image.open(path) as im:
            pngs.append({"file":path.name,"size":list(im.size),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
    revision=hashlib.sha256((WORK/MODEL).read_bytes()).hexdigest()
    assert info["revision"]==static["revision"]==revision[:16]
    facts={"status":"爱音R3单人叠层头部原型，供用户验收；模型冻结，未扩其余七人",
           "model_file":MODEL,"model_sha256":revision,"source_file":"art/sources/robot_layered_head.py",
           "counts":info["counts"],"original_hair_cubes":148,"original_face_cubes":len(face_elements),
           "all_face_parts_positive_thickness":minimum_depth>0,"minimum_face_local_axis":round(minimum_depth,6),
           "r2_transparent_feature_references":sum(face.get("texture")==1 for e in doc["elements"] for face in e.get("faces",{}).values()),
           "non_head_fingerprint":"ac228df0d82e41c7e1ed30848013fc0fd42a4c49e5b0a54e7ea5b1eb511fad29",
           "static":{"self_test":False,"summary":static["summary"],"gates":[{"id":gate["id"],"ran":gate["ran"],
                      "violations":len(gate["violations"])} for gate in static["gates"]]},
           "reference_sha256":hashlib.sha256(REFERENCE.read_bytes()).hexdigest(),
           "author_viewed_images":True,"root_review":"头发阶段已独立审阅及官方编辑器打开；最终头部待根编辑器复核",
           "user_acceptance":"pending; user explicitly rejected R1 generic face and R2",
           "render_sources":pngs,"inspection_note":"final-head保留全部长发、仅MCP移除身体；final-face再用MCP移除长发以便看脸。主工程完整。",
           "verification_scope":"仅实际MCP建模、真实渲染与静态检查；未运行测试、self_test、构建、游戏或安装。"}
    dump(WORK/"final-facts.json",facts)
    dump(WORK/"geometric-face-facts.json",{"face_cube_count":len(face_elements),"every_feature_positive_depth":minimum_depth>0,
         "new_features_use_texture_index":2,"texture_1_face_references":facts["r2_transparent_feature_references"],
         "revision":info["revision"],"status":facts["status"]})
    # Only pixel-preserving placement of genuine renderer output, with labels outside the views.
    canvas=Image.new("RGB",(1280,1256),"#202329"); draw=ImageDraw.Draw(canvas)
    font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",22)
    labels=((0,"R2：用户已否定"),(640,"R3：叠层头发＋长方体五官原型，待验收"))
    for x,label in labels:
        draw.text((x+14,10),label,font=font,fill="#f0eef0")
    for x,path in ((0,R2.parent/"r2-head-front.png"),(640,WORK/"final-face-front-flat.png")):
        with Image.open(path) as im:
            im=im.convert("RGB")
            if im.size!=(640,640):
                im=im.resize((640,640),Image.Resampling.NEAREST)
            canvas.paste(im,(x,48))
    draw.text((14,695),"下排：完整工程同一三分之四视角；图片仅排布，未重画、美化或遮盖",font=font,fill="#ddd6dc")
    for x,path in ((64,R2.parent/"r2-full-three-quarter-512.png"),(704,WORK/"final-full-three-quarter-512.png")):
        with Image.open(path) as im:
            canvas.paste(im.convert("RGB"),(x,736))
    canvas.save(WORK/"r2-r3-comparison.png")
    readme="""# 爱音 R3 单人叠层头部原型

用户已否定 R1 的通用脸与 R2。当前只有爱音重建了完整 head，供用户验收；其余七人没有同步修订。

- 主工程：`anon-layered-r3.bbmodel`；222 cube / 19 group / 0 mesh / 0 animation，3纹理，项目UV空间64。
- 原创148个头发长方体组织为冠顶、侧扫刘海、颊侧鬓根、侧后长发与双夹；53个脸部方块含连续皮肤、短浅颌、独立薄眉眼/虹膜/口腔。R2透明五官贴花引用为0。没有复制参考的几何、UV、贴图或动画。
- 身体服装冻结：21非head元素、7非head骨骼及逐字段UV/原atlas/resolution与R1、R2相同，见 `non-head-freeze-record.json`。
- `final-face-front-flat.png` / `final-face-three-quarter-studio.png` 看五官；`final-head-front/right/left/back/three-quarter.png` 看完整长发；`final-head-three-quarter-studio.png` 看体积；`final-full-front-512.png`、`final-full-three-quarter-512.png` 与 `final-normal-192.png` 看全身。
- `r2-r3-comparison.png` 为1280×1256真实渲染排布。上排均正面但检查副本取景不同，下排为完整工程同一三分之四视角；不作为量化相似度依据。
- 近脸副本通过真实MCP移除身体和侧后长发方便看脸；完整head副本只移除身体。主工程保留全部发束与身体。所有工程写入均经原作者MCP与expected_revision，回执在 `receipts/`。
- 静态0错误、125警告：16项独立薄件未触及主身体、109项叠层/内部覆盖提示。原始回执 `receipts/22-final-static.json` 保留全部信息；self_test=false。静态检查不等于游戏或玩家验收。
- 根已审阅头发阶段并实际在官方Blockbench打开。最终头部待根复核，用户验收待定；不声称相似度通过。未运行测试、Java、游戏、构建、安装或上传。

最终SHA256：`"""+revision+"`\n"
    overlaps=next(len(gate["violations"]) for gate in static["gates"] if gate["id"]=="interpenetration")
    readme=readme.replace("125警告",f"{static['summary']['warnings']}警告").replace("109项",f"{overlaps}项")
    (WORK/"README.md").write_text(readme,encoding="utf-8")
    print(json.dumps({"model_sha256":revision,"comparison":"r2-r3-comparison.png","comparison_size":[1280,1256],
                      "face_cube_count":len(face_elements),"status":"model frozen"}),flush=True)


def tuck_hidden_skull_corner() -> None:
    current=mcp("bbmodel_info",{"file":MODEL},"25-before-hidden-skull-corner")
    op=next(op for op in geometric_face_operations() if op.get("name")=="face_skin_inner_support")
    mcp("bbmodel_edit",{"file":MODEL,"expected_revision":current["revision"],"operations":[
        {"op":"update_node","target":op["name"],**{k:op[k] for k in ("from","to","origin","rotation")}}]},"26-tuck-hidden-skull-into-hair")
    fingerprints()
    mcp("bbmodel_info",{"file":MODEL},"21-final-info")
    mcp("bbmodel_validate",{"file":MODEL,"self_test":False,"min_thickness":.01},"22-final-static")
    render_final_views()
    finish_records()


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start-hair",action="store_true")
    parser.add_argument("--render-hair",action="store_true")
    parser.add_argument("--correct-hair-coverage",action="store_true")
    parser.add_argument("--refine-hair-flow",action="store_true")
    parser.add_argument("--bury-temple-roots",action="store_true")
    parser.add_argument("--close-final-hair-seam",action="store_true")
    parser.add_argument("--build-geometric-face",action="store_true")
    parser.add_argument("--refine-shallow-mouth-crown",action="store_true")
    parser.add_argument("--render-final",action="store_true")
    parser.add_argument("--cover-forehead-corner",action="store_true")
    parser.add_argument("--finish-records",action="store_true")
    parser.add_argument("--tuck-hidden-skull",action="store_true")
    parser.add_argument("--quota-checked",action="store_true")
    args=parser.parse_args()
    if not args.quota_checked:
        raise SystemExit("Read real ordinary usage and every applicable quota window first.")
    if args.start_hair:
        start_hair()
    if args.render_hair:
        render_hair()
    if args.correct_hair_coverage:
        correct_hair_coverage()
    if args.refine_hair_flow:
        refine_hair_flow()
    if args.bury_temple_roots:
        bury_temple_roots()
    if args.close_final_hair_seam:
        close_final_hair_seam()
    if args.build_geometric_face:
        build_geometric_face()
    if args.refine_shallow_mouth_crown:
        refine_shallow_mouth_and_crown()
    if args.render_final:
        render_final_views()
    if args.cover_forehead_corner:
        cover_remaining_forehead_corner()
    if args.finish_records:
        finish_records()
    if args.tuck_hidden_skull:
        tuck_hidden_skull_corner()


if __name__=="__main__":
    main()
