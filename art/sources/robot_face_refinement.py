"""Original Anon face refinement; all .bbmodel edits use the upstream real MCP.

R1 is read-only. Copies of our own R1/R2 models are made for a new iteration or
for a clearly labeled inspection view. Cubes, face UVs, textures and inspection
transforms are then changed only with bbmodel_edit/bbmodel_add_texture.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont


PROJECT = Path(__file__).resolve().parents[2]
WORK = PROJECT / "art/robot-mcp-workspace/characters/anon-face-r2"
SOURCE_R1 = WORK.parent / "anon-r1/anon-tutorial-lowpoly-r1.bbmodel"
MODEL = "anon-face-r2.bbmodel"
TOOL = Path.home() / ".codex/tmp/ic2-blockbench-mcp-178ce46f"
FACES = ("north", "east", "south", "west", "up", "down")
SKIN = "#F3D7C8"
SKIN_UV = [56, 56, 60, 60]
HAIR_UV = {"hair": [4, 16, 8, 20], "fringe": [8, 16, 12, 20], "end": [12, 16, 16, 20], "clip": [44, 16, 48, 20]}
PLAN = """# 爱音脸部 R2：制作前方案

用户 2026-10-16 最新反馈：脸部太抽象、一点都不像。R1 通用 8×8 眼口脸已被否定。
本次只修爱音 head 子树；R1 身体/服装/四肢/裙鞋、UV、第一张贴图与非头部骨骼冻结。

本轮已亲看：r63-face-rereview-2026-10-16.png（用户参考实际为粉发人物，不称甘雨）、
stage-reference-lossless.png 和 anon-source-mosaic-512.png，并读 full-model-review.md。
只学习连续主脸、短浅下颌、薄五官层与侧发框脸的方法，不读取或复制参考几何/UV/贴图/动画。

## 面部特征和体块

- 爱音的蓝灰眼有柔和上睫与杏形轮廓，眼白、虹膜、短瞳和小高光分别可见。
- 眉为细线，并与上眼睫留实际间距；不再共用一条厚黑横杠。
- 口是开朗的微张笑口，有浅色齿带及小口腔/舌色区域，不再是两像素方嘴。
- 不添加方块鼻或粗眼角；原图的倾斜只用于判断五官关系，不把斜脸直接贴上来。
- 主脸约 7 宽、6.6 高，是一个连续前部实体。下端配一片短中央下颌、
  两片约 8°的短脸缘及隐藏下颌后连接；不做密集横切、锐角长叉或尖面具。
- 眼、眉和口分别由贴近主面的薄层承载；第二张原创 128×128 atlas，
  主脸独占 64×64 有效像素，双眼每只 32×24、眉 32×8、口 32×20。
  项目 resolution 仍 64×64，第二贴图的 UV 按 0.5 换算，故身体原 UV 完全不动。
- 粉色侧扫刘海用 3 个宽块重新安排发际；两侧框脸发块与双发夹一起校准可见脸面积。
  保留 R1 长发大轮廓，不恢复瓦片/薄发丝堆砌。

## 骨骼、检查和动作边界

保留 R1 的 root/body/head/四肢/skirt 共 8 骨骼，薄五官都挂 head，不新增动画。
以后可转头和表情控制，但本轮不制作或宣称已验收。
全部工程修改经原作者 headless/call.ts 的真实 MCP stdio 与 expected_revision；
渲染同样经 bbmodel_render。近景用独立 head-only 检查副本，转动副本获得严格左右45°；
副本删掉身体和低于头部的长发，仅为查看脸，完整主工程不删。
R1 对照使用同样副本方法与相机设置。所有图只是真实渲染/裁切/排布，不补画遮掩。
非头部指纹逐字段比较，保留静态原始警告，self_test=false。
用户审阅待定；不扩其余七人，不做测试、Java、游戏、构建、安装、存档、密钥、上传或自动化。
"""


def dump(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def mcp(tool: str, args: dict, label: str) -> dict | None:
    argsfile = WORK / "receipts" / f"{label}-args.json"
    dump(argsfile, args)
    bun = TOOL / "node_modules/@oven/bun-windows-x64/bin/bun.exe"
    node = Path.home() / ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin"
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join((str(node), str(bun.parent), env.get("PATH", "")))
    env["BB_RENDER_HOME"] = str(TOOL / "render-runtime")
    env["npm_config_proxy"] = env["npm_config_https_proxy"] = "http://127.0.0.1:7897"
    start = dt.datetime.now(dt.timezone.utc).isoformat()
    proc = subprocess.run([str(bun), "run", str(TOOL / "headless/call.ts"), "--root", str(WORK), tool, "@" + str(argsfile)],
                          cwd=TOOL, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    dump(WORK / "receipts" / f"{label}.json", {"tool": tool, "transport": "real upstream MCP stdio", "started_utc": start,
        "finished_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "exit_code": proc.returncode,
        "stdout": proc.stdout, "stderr": proc.stderr, "arguments_file": str(argsfile)})
    print(json.dumps({"tool": tool, "label": label, "exit_code": proc.returncode}, ensure_ascii=False), flush=True)
    if proc.returncode:
        print(proc.stdout + proc.stderr, file=sys.stderr)
        raise SystemExit(proc.returncode)
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None


def head_ids(doc: dict) -> set[str]:
    head = next(g["uuid"] for g in doc["groups"] if g["name"] == "head")

    def find(nodes: list) -> dict | None:
        for item in nodes:
            if isinstance(item, dict):
                if item["uuid"] == head:
                    return item
                result = find(item["children"])
                if result:
                    return result
        return None

    def ids(item: str | dict) -> set[str]:
        if isinstance(item, str):
            return {item}
        result = {item["uuid"]}
        for child in item["children"]:
            result |= ids(child)
        return result

    return ids(find(doc["outliner"]))


def body_fingerprint(doc: dict) -> tuple[str, dict]:
    excluded = head_ids(doc)
    head = next(g["uuid"] for g in doc["groups"] if g["name"] == "head")

    def outline(nodes: list) -> list:
        result = []
        for item in nodes:
            if isinstance(item, str):
                result.append(item)
            elif item["uuid"] == head:
                result.append({"head_subtree_marker": head})
            else:
                result.append({**item, "children": outline(item["children"])})
        return result

    selected = {"elements": [e for e in doc["elements"] if e["uuid"] not in excluded],
                "groups": [g for g in doc["groups"] if g["uuid"] not in excluded],
                "outliner": outline(doc["outliner"]), "resolution": doc["resolution"], "body_texture_0": doc["textures"][0]}
    canonical = json.dumps(selected, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest(), selected


def paint_atlas() -> None:
    atlas = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    draw.rectangle((0, 0, 63, 63), fill=SKIN)
    draw.rectangle((112, 112, 119, 119), fill=SKIN)

    def eye() -> Image.Image:
        tile = Image.new("RGBA", (32, 24), (0, 0, 0, 0))
        d = ImageDraw.Draw(tile)
        boundary = [(1, 11), (3, 9), (7, 7), (12, 6), (19, 6), (25, 7), (29, 10),
                    (30, 12), (28, 15), (24, 18), (18, 20), (12, 20), (7, 18), (3, 15)]
        d.polygon(boundary, fill="#FFF8EE")
        # Restricted flat regions inside an original iris; no blur or gradients.
        iris = Image.new("RGBA", tile.size, (0, 0, 0, 0))
        di = ImageDraw.Draw(iris)
        di.polygon([(10, 5), (14, 3), (20, 4), (23, 8), (23, 16), (20, 21), (14, 22), (10, 18)], fill="#6E8B98")
        di.rectangle((11, 5, 22, 9), fill="#4E6878")
        di.polygon([(12, 16), (15, 18), (21, 17), (20, 21), (14, 22), (11, 19)], fill="#9BB7BC")
        di.rectangle((16, 8, 18, 16), fill="#354D5C")
        di.rectangle((12, 7, 14, 10), fill="#FFFDF5")
        di.rectangle((20, 16, 21, 17), fill="#DDE8E6")
        mask = Image.new("L", tile.size, 0)
        ImageDraw.Draw(mask).polygon(boundary, fill=255)
        iris.putalpha(Image.composite(iris.getchannel("A"), Image.new("L", tile.size, 0), mask))
        tile.alpha_composite(iris)
        d = ImageDraw.Draw(tile)
        d.polygon([(2, 10), (5, 8), (10, 6), (15, 5), (20, 5), (25, 7), (29, 10),
                   (28, 11), (24, 9), (19, 7), (14, 7), (10, 8), (5, 10), (3, 12)], fill="#70515D")
        d.line([(4, 15), (7, 18), (12, 20)], fill="#C49898", width=1)
        return tile

    left = eye()
    atlas.alpha_composite(left, (0, 64))
    atlas.alpha_composite(left.transpose(Image.Transpose.FLIP_LEFT_RIGHT), (32, 64))
    brow = Image.new("RGBA", (32, 8), (0, 0, 0, 0))
    ImageDraw.Draw(brow).polygon([(2, 5), (10, 2), (20, 2), (28, 4), (29, 5), (20, 4), (11, 4), (3, 7)], fill="#AE778A")
    atlas.alpha_composite(brow, (64, 64))
    atlas.alpha_composite(brow.transpose(Image.Transpose.FLIP_LEFT_RIGHT), (64, 72))
    mouth = Image.new("RGBA", (32, 20), (0, 0, 0, 0))
    md = ImageDraw.Draw(mouth)
    md.polygon([(5, 4), (10, 6), (16, 7), (22, 6), (27, 4), (26, 9), (22, 13),
                (18, 15), (14, 15), (10, 13), (6, 9)], fill="#B57885")
    md.polygon([(7, 6), (12, 8), (20, 8), (25, 6), (24, 10), (20, 13), (12, 13), (8, 10)], fill="#A4657B")
    md.rectangle((13, 7, 18, 7), fill="#F7E6D6")
    md.polygon([(11, 12), (16, 11), (21, 12), (19, 14), (13, 14)], fill="#D699A3")
    atlas.alpha_composite(mouth, (0, 96))
    atlas.save(WORK / "anon-face-atlas-128.png")
    dump(WORK / "face-atlas-map.json", {"texture_pixels": [128, 128], "project_resolution_preserved": [64, 64],
        "uv_pixels_scale": 0.5, "face_effective_pixels": [64, 64], "eyes": [[0, 64, 32, 88], [32, 64, 64, 88]],
        "brows": [[64, 64, 96, 72], [64, 72, 96, 80]], "mouth": [0, 96, 32, 116],
        "method": "Original native-resolution pixel polygons; no source projection, resampling, blur or third-party assets"})


def refined_head() -> list[dict]:
    ops = []

    def cube(name: str, a: list[float], b: list[float], texture: int = 1, uv: list | None = None,
             origin: list | None = None, rotation: list | None = None, front_only: bool = False) -> None:
        faces = {face: {"uv": uv or SKIN_UV, "texture": None if front_only and face != "north" else texture} for face in FACES}
        op = {"op": "add_cube", "name": name, "from": a, "to": b, "parent": "head", "texture": texture,
              "box_uv": False, "faces": faces}
        if origin is not None:
            op["origin"] = origin
        if rotation is not None:
            op["rotation"] = rotation
        ops.append(op)

    cube("head_inner_support", [-3.65, 25.1, -2.55], [3.65, 31.8, 3.45])
    cube("face_continuous_main", [-3.5, 24.8, -3.5], [3.5, 31.8, -2.45], uv=[0, 0, 32, 32])
    cube("jaw_center_short", [-2.7, 24.5, -3.49], [2.7, 24.95, -2.25])
    cube("jaw_edge_left", [1.8, 24.55, -3.48], [3.45, 25, -2.25], origin=[2.6, 24.85, -2.8], rotation=[0, 0, 6])
    cube("jaw_edge_right", [-3.45, 24.55, -3.48], [-1.8, 25, -2.25], origin=[-2.6, 24.85, -2.8], rotation=[0, 0, -6])
    cube("jaw_hidden_connection", [-1.3, 24, -2.7], [1.3, 25.35, 1.2])

    # Thin original feature layers, close enough to read as one continuous face.
    cube("eye_layer_left", [0.65, 26.45, -3.54], [2.75, 28.5, -3.515], uv=[0, 32, 16, 44], front_only=True)
    cube("eye_layer_right", [-2.75, 26.45, -3.54], [-0.65, 28.5, -3.515], uv=[16, 32, 32, 44], front_only=True)
    cube("brow_layer_left", [0.8, 28.65, -3.55], [2.55, 29.05, -3.525], uv=[32, 32, 48, 36], front_only=True)
    cube("brow_layer_right", [-2.55, 28.65, -3.55], [-0.8, 29.05, -3.525], uv=[32, 36, 48, 40], front_only=True)
    cube("smile_layer", [-0.72, 25.3, -3.55], [0.72, 26.22, -3.525], uv=[0, 48, 16, 58], front_only=True)

    cube("hair_cap_refined", [-4.1, 31.5, -3.65], [4.1, 32.5, 3.75], 0, HAIR_UV["fringe"])
    cube("hair_front_root_cover", [-3.75, 30.4, -3.6], [3.75, 31.9, -3.3], 0, HAIR_UV["fringe"])
    cube("hair_temple_refined_left", [3.35, 25, -3.55], [4.15, 31.7, 3.25], 0, HAIR_UV["hair"])
    cube("hair_temple_refined_right", [-4.15, 25, -3.55], [-3.35, 31.7, 3.25], 0, HAIR_UV["hair"])
    cube("hair_face_frame_left", [3.15, 24.7, -3.7], [4.05, 29.5, -2.7], 0, HAIR_UV["hair"],
         origin=[3.6, 29.4, -3.2], rotation=[0, 0, -3])
    cube("hair_face_frame_right", [-4.05, 24.7, -3.7], [-3.15, 29.5, -2.7], 0, HAIR_UV["hair"],
         origin=[-3.6, 29.4, -3.2], rotation=[0, 0, 3])
    cube("hair_swept_main", [-0.9, 29.9, -3.95], [3.75, 31.8, -3.35], 0, HAIR_UV["fringe"],
         origin=[-0.9, 31.6, -3.5], rotation=[0, 0, -12])
    cube("hair_short_part", [-3.75, 30.4, -3.9], [-0.8, 31.8, -3.3], 0, HAIR_UV["fringe"],
         origin=[-0.8, 31.7, -3.5], rotation=[0, 0, 14])
    cube("hair_sweep_end", [2.8, 28.7, -3.85], [3.65, 30.5, -3.25], 0, HAIR_UV["fringe"],
         origin=[3.2, 30.4, -3.4], rotation=[0, 0, -8])
    cube("hair_clip_refined_upper", [3.65, 28.85, -3.97], [4.1, 29.12, -3.7], 0, HAIR_UV["clip"])
    cube("hair_clip_refined_lower", [3.65, 28.25, -3.97], [4.1, 28.52, -3.7], 0, HAIR_UV["clip"])
    return ops


def initial() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    if (WORK / MODEL).exists():
        raise SystemExit("R2 already exists; edit it explicitly through MCP instead of overwriting it.")
    (WORK / "plan-before-modeling.md").write_text(PLAN, encoding="utf-8")
    paint_atlas()
    shutil.copy2(SOURCE_R1, WORK / MODEL)
    source_doc = json.loads(SOURCE_R1.read_text(encoding="utf-8"))
    source_fp, _ = body_fingerprint(source_doc)
    dump(WORK / "copy-provenance.json", {"source": str(SOURCE_R1), "source_sha256": hashlib.sha256(SOURCE_R1.read_bytes()).hexdigest(),
        "method": "byte copy of own R1 model; every subsequent model/texture/UV mutation uses real MCP", "non_head_sha256": source_fp,
        "reference_asset_data_copied": False})
    result = mcp("bbmodel_info", {"file": MODEL}, "01-copied-info")
    result = mcp("bbmodel_add_texture", {"file": MODEL, "image": "anon-face-atlas-128.png", "name": "anon-face-atlas-128.png",
        "expected_revision": result["revision"]}, "02-add-original-face-atlas")
    replace = {"head_main", "hair_cap", "hair_temple_left", "hair_temple_right", "hair_fringe_sweep",
               "hair_fringe_part", "hair_fringe_end", "hair_clip_upper", "hair_clip_lower"}
    ops = [{"op": "remove_node", "target": name} for name in sorted(replace)]
    ops += refined_head()
    ops.append({"op": "set_model_properties", "name": "千早爱音｜脸部修订 R2"})
    result = mcp("bbmodel_edit", {"file": MODEL, "expected_revision": result["revision"], "operations": ops}, "03-rebuild-head-only")
    record()
    inspection_copy(MODEL, "r2-head-inspection.bbmodel", "r2")
    render("r2-head-inspection.bbmodel", "front", "r2-head-front.png", 640, "05-first-front")
    render("r2-head-inspection.bbmodel", "three-quarter", "r2-head-three-quarter.png", 640, "06-first-three-quarter")
    render(MODEL, "front", "r2-full-front-512.png", 512, "07-first-full-front")
    render(MODEL, "three-quarter", "r2-full-normal-192.png", 192, "08-first-normal")


def inspection_copy(source: str, target: str, label: str) -> None:
    if (WORK / target).exists():
        raise SystemExit(f"Inspection copy exists: {target}")
    shutil.copy2(WORK / source, WORK / target)
    result = mcp("bbmodel_info", {"file": target}, f"{label}-inspection-info")
    names = {e["name"] for e in json.loads((WORK / source).read_text(encoding="utf-8"))["elements"]}
    long_hair = {"hair_back_long", "hair_back_end_step", "hair_long_side_left", "hair_long_side_right"} & names
    ops = [{"op": "update_node", "target": "head", "parent": None}, {"op": "remove_node", "target": "root"}]
    ops += [{"op": "remove_node", "target": name} for name in sorted(long_hair)]
    mcp("bbmodel_edit", {"file": target, "expected_revision": result["revision"], "operations": ops}, f"{label}-inspection-remove-body-long-hair")


def render(file: str, view: str, output: str, size: int, label: str) -> None:
    mcp("bbmodel_render", {"file": file, "view": view, "width": size, "height": size,
        "orthographic": True, "lighting": "flat", "output": output, "overwrite": True}, label)


def record() -> None:
    doc = json.loads((WORK / MODEL).read_text(encoding="utf-8"))
    source = json.loads(SOURCE_R1.read_text(encoding="utf-8"))
    fp1, fields1 = body_fingerprint(source)
    fp2, fields2 = body_fingerprint(doc)
    dump(WORK / "non-head-freeze-record.json", {"source": str(SOURCE_R1), "revision": MODEL,
        "r1_body_sha256": fp1, "r2_body_sha256": fp2, "identical": fp1 == fp2,
        "elements": len(fields1["elements"]), "groups_outside_head": len(fields1["groups"]),
        "body_elements_uv_texture_and_outliner_identical": fields1 == fields2,
        "includes": ["every element outside head subtree including all original UV/texture assignments",
                     "all non-head groups and normalized outliner", "project resolution", "original texture[0] including image source"],
        "r1_file_sha256": hashlib.sha256(SOURCE_R1.read_bytes()).hexdigest(),
        "r2_file_sha256": hashlib.sha256((WORK / MODEL).read_bytes()).hexdigest()})
    if fp1 != fp2:
        raise SystemExit("Non-head fingerprint changed: stop and inspect, do not deliver.")
    mcp("bbmodel_info", {"file": MODEL}, "04-current-info")
    mcp("bbmodel_validate", {"file": MODEL, "self_test": False, "min_thickness": 0.01,
        "asymmetric": ["hair_long_side_left", "hair_long_side_right"]}, "04-current-static-validation")


def full_review() -> None:
    head = "r2-head-inspection.bbmodel"
    # Refresh check copy after an explicit authored revision, never touch R1.
    if (WORK / head).exists():
        old = WORK / "inspection-previous"
        old.mkdir(exist_ok=True)
        shutil.copy2(WORK / head, old / f"head-{dt.datetime.now().strftime('%H%M%S')}.bbmodel")
        (WORK / head).unlink()
    inspection_copy(MODEL, head, "r2-final")
    for view in ("front", "right", "left", "back", "three-quarter"):
        render(head, view, f"r2-head-{view}.png", 640, f"final-head-{view}")
    for angle, label in ((45, "left45"), (-45, "right45")):
        result = mcp("bbmodel_info", {"file": head}, f"{label}-info")
        mcp("bbmodel_edit", {"file": head, "expected_revision": result["revision"], "operations": [
            {"op": "update_node", "target": "head", "rotation": [0, angle, 0]}]}, f"{label}-rotate-inspection-only")
        render(head, "front", f"r2-head-{label}.png", 640, f"final-head-{label}")
    result = mcp("bbmodel_info", {"file": head}, "neutral-inspection-info")
    mcp("bbmodel_edit", {"file": head, "expected_revision": result["revision"], "operations": [
        {"op": "update_node", "target": "head", "rotation": [0, 0, 0]}]}, "neutral-inspection-restore")
    for view in ("front", "right", "back", "three-quarter"):
        render(MODEL, view, f"r2-full-{view}-512.png", 512, f"final-full-{view}")
    render(MODEL, "three-quarter", "r2-full-normal-192.png", 192, "final-full-normal")
    if not (WORK / "r1-comparison.bbmodel").exists():
        shutil.copy2(SOURCE_R1, WORK / "r1-comparison.bbmodel")
        inspection_copy("r1-comparison.bbmodel", "r1-head-inspection.bbmodel", "r1")
    for view in ("front", "right", "three-quarter"):
        if not (WORK / f"r1-head-{view}.png").exists():
            render("r1-head-inspection.bbmodel", view, f"r1-head-{view}.png", 640, f"comparison-r1-head-{view}")
    for view in ("front", "three-quarter"):
        if not (WORK / f"r1-full-{view}-512.png").exists():
            render("r1-comparison.bbmodel", view, f"r1-full-{view}-512.png", 512, f"comparison-r1-full-{view}")
    record()


def correct_first_readback() -> None:
    """Two visible issues found on the actual first render, not a new style pass."""
    snapshot = WORK / "first-prototype"
    snapshot.mkdir(exist_ok=True)
    for name in (MODEL, "anon-face-atlas-128.png", "r2-head-front.png", "r2-head-three-quarter.png",
                 "r2-full-front-512.png", "r2-full-normal-192.png", "non-head-freeze-record.json"):
        shutil.copy2(WORK / name, snapshot / name)
    current = mcp("bbmodel_info", {"file": MODEL}, "09-before-readback-corrections")
    ops = [{"op": "update_node", "target": "jaw_hidden_connection", "from": [-1.3, 24, -2.7], "to": [1.3, 25.35, 1.2]}]
    ops += [op for op in refined_head() if op["name"] == "hair_front_root_cover"]
    mcp("bbmodel_edit", {"file": MODEL, "expected_revision": current["revision"], "operations": ops}, "10-fix-root-cover-and-hidden-connection")
    record()


def soften_features() -> None:
    """Root's real-image review: shallow connected jaw, softer almond eyes/smile."""
    snapshot = WORK / "after-first-correction"
    snapshot.mkdir(exist_ok=True)
    for path in [WORK / MODEL, WORK / "anon-face-atlas-128.png", WORK / "non-head-freeze-record.json", *WORK.glob("r2-*.png")]:
        shutil.copy2(path, snapshot / path.name)
    shutil.copytree(WORK / "receipts", snapshot / "receipts", dirs_exist_ok=True)
    paint_atlas()
    result = mcp("bbmodel_info", {"file": MODEL}, "11-before-soft-feature-refinement")
    source = "data:image/png;base64," + base64.b64encode((WORK / "anon-face-atlas-128.png").read_bytes()).decode("ascii")
    wanted = {"face_continuous_main", "jaw_center_short", "jaw_edge_left", "jaw_edge_right", "smile_layer"}
    ops = [{"op": "update_texture", "target": 1, "source": source, "width": 128, "height": 128}]
    for op in refined_head():
        if op["name"] in wanted:
            ops.append({"op": "update_node", "target": op["name"],
                        **{k: op[k] for k in ("from", "to", "origin", "rotation") if k in op}})
    mcp("bbmodel_edit", {"file": MODEL, "expected_revision": result["revision"], "operations": ops}, "12-soft-almond-small-smile-shallow-jaw")
    dump(WORK / "second-readback-corrections.json", {
        "trigger": "root independently viewed first front/three-quarter/192",
        "actual_changes": ["main face extends to y24.8, central jaw extends only 0.3 beneath it; short 6-degree side connections",
                           "symmetric small smile with reduced pale central teeth, lighter lip rim and a short round bottom",
                           "flatter broad upper eyelash, shorter corners and softer almond eye white shape"],
        "body_modified": False, "other_characters_modified": False, "user_acceptance": "pending"})
    record()


def close_temple_gap() -> None:
    snapshot = WORK / "before-temple-gap-correction"
    snapshot.mkdir(exist_ok=True)
    for path in [WORK / MODEL, WORK / "anon-face-atlas-128.png", *WORK.glob("r2-*.png")]:
        shutil.copy2(path, snapshot / path.name)
    result = mcp("bbmodel_info", {"file": MODEL}, "13-before-temple-depth-correction")
    wanted = {"hair_temple_refined_left", "hair_temple_refined_right"}
    ops = [{"op": "update_node", "target": op["name"], "from": op["from"], "to": op["to"]}
           for op in refined_head() if op["name"] in wanted]
    mcp("bbmodel_edit", {"file": MODEL, "expected_revision": result["revision"], "operations": ops}, "14-close-temple-root-side-gap")
    record()


def comparisons() -> None:
    fontfile = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(fontfile), 22) if fontfile.exists() else ImageFont.load_default()
    pairs = [(f"r1-head-{view}.png", f"r2-head-{view}.png", f"r1-r2-face-{view}-comparison.png")
             for view in ("front", "three-quarter", "right")]
    pairs += [(f"r1-full-{view}-512.png", f"r2-full-{view}-512.png", f"r1-r2-full-{view}-comparison.png")
              for view in ("front", "three-quarter")]
    normal_r1 = "r1-full-normal-192.png"
    if not (WORK / normal_r1).exists():
        shutil.copy2(SOURCE_R1.parent / "anon-normal-192.png", WORK / normal_r1)
    pairs.append((normal_r1, "r2-full-normal-192.png", "r1-r2-normal-comparison.png"))
    provenance = []
    for first, second, target in pairs:
        a, b = Image.open(WORK / first).convert("RGB"), Image.open(WORK / second).convert("RGB")
        if a.size != b.size:
            raise ValueError("Comparison images must retain the same native dimensions.")
        w, h = a.size
        sheet = Image.new("RGB", (2 * w, h + 40), "#20242A")
        sheet.paste(a, (0, 40))
        sheet.paste(b, (w, 40))
        d = ImageDraw.Draw(sheet)
        d.text((12, 8), "R1", font=font, fill="#EEEDEA")
        d.text((w + 12, 8), "R2", font=font, fill="#EEEDEA")
        sheet.save(WORK / target)
        provenance.append({"output": target, "size": list(sheet.size), "sources": [first, second],
                           "source_sha256": [hashlib.sha256((WORK / p).read_bytes()).hexdigest() for p in (first, second)]})
    dump(WORK / "comparison-provenance.json", {"method": "Native-size paste of actual MCP renders; no scaling, painting, beautification or masking",
        "camera": "same named orthographic view and flat lighting; head-only inspection bounds auto-fit independently",
        "pairs": provenance})


def expression_proportions() -> None:
    snapshot = WORK / "before-expression-proportions"
    snapshot.mkdir(exist_ok=True)
    for path in [WORK / MODEL, WORK / "anon-face-atlas-128.png", *WORK.glob("r2-*.png")]:
        shutil.copy2(path, snapshot / path.name)
    shutil.copytree(WORK / "receipts", snapshot / "receipts", dirs_exist_ok=True)
    paint_atlas()
    current = mcp("bbmodel_info", {"file": MODEL}, "15-before-expression-proportions")
    image = "data:image/png;base64," + base64.b64encode((WORK / "anon-face-atlas-128.png").read_bytes()).decode("ascii")
    ops = [{"op": "update_texture", "target": 1, "source": image, "width": 128, "height": 128}]
    for op in refined_head():
        if op["name"] in {"eye_layer_left", "eye_layer_right", "smile_layer"}:
            ops.append({"op": "update_node", "target": op["name"], "from": op["from"], "to": op["to"]})
    mcp("bbmodel_edit", {"file": MODEL, "expected_revision": current["revision"], "operations": ops}, "16-open-eyes-and-small-smile-proportions")
    record()
    inspection_copy(MODEL, "r2-expression-inspection.bbmodel", "expression")
    render("r2-expression-inspection.bbmodel", "front", "r2-expression-front.png", 640, "expression-front-review")
    render(MODEL, "three-quarter", "r2-expression-normal-192.png", 192, "expression-normal-review")
    dump(WORK / "expression-proportions-record.json", {
        "trigger": "root viewed softened face and 192 image; requested midpoint between first wide eyes and over-softened sleepy expression",
        "changes": ["eye width 2.1 unchanged; vertical carrier 1.65 to 2.05 with the same thin flattened eyelash and almond pixels",
                    "mouth width 1.44 unchanged; carrier height 0.7 to 0.92 with level corners, round short bottom and readable cavity/tongue",
                    "small central pale tooth detail shortened to 6x1 native pixels; no through-going tooth line"],
        "other_head_geometry_changed": False, "body_changed": False, "other_characters_changed": False,
        "review_images": ["r2-expression-front.png", "r2-expression-normal-192.png"],
        "remaining_standard_views": "not refreshed for this expression until root review"})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--initial", action="store_true")
    parser.add_argument("--full-review", action="store_true")
    parser.add_argument("--correct-first-readback", action="store_true")
    parser.add_argument("--soften-features", action="store_true")
    parser.add_argument("--close-temple-gap", action="store_true")
    parser.add_argument("--comparisons", action="store_true")
    parser.add_argument("--expression-proportions", action="store_true")
    parser.add_argument("--quota-checked", action="store_true")
    args = parser.parse_args()
    if not args.quota_checked:
        raise SystemExit("Read actual ordinary usage and every window for this stage first.")
    if args.initial:
        initial()
    if args.correct_first_readback:
        correct_first_readback()
    if args.soften_features:
        soften_features()
    if args.close_temple_gap:
        close_temple_gap()
    if args.expression_proportions:
        expression_proportions()
    if args.full_review:
        full_review()
    if args.comparisons:
        comparisons()


if __name__ == "__main__":
    main()
