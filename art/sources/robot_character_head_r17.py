"""Prepare editable R17 head-only Blockbench variants from the accepted R16 files.

This script copies each accepted model into the new head-r17 workspace, fingerprints
all geometry outside the head subtree, and writes role-specific Blockbench edit
operations. Apply those operations with the Blockbench MCP tool; render and inspect
the resulting models before treating them as reviewed assets. No reference pixels,
external models, runtime resources, or body geometry are generated here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHARACTERS = ROOT / "art/robot-mcp-workspace/characters"
OUT = CHARACTERS / "head-r17"

SOURCES = {
    "anon": CHARACTERS / "anon-body-balance-r16/anon-body-balance-r16.bbmodel",
    **{role: CHARACTERS / f"{role}-r16-standard/{role}-r16-standard.bbmodel"
       for role in ("tomori", "soyo", "taki", "rana", "sakiko", "uika", "umiri")},
}

# (x center, bottom y, width, z tilt): distinct tips, spacing, splits, and angles.
FRONT_LOCKS = {
    # (x, tip y, width, tilt, root y); roots begin below the crown surface.
    # Roots rise into the existing crown. A few different sweeping silhouettes
    # replace the exposed forehead tiles from earlier iterations.
    "anon":   [(-1.48,24.25,1.72,-23,27.35),(.02,24.72,1.82,-14,27.45),(1.42,25.16,1.48,-5,27.35)],
    "tomori": [(-1.30,25.20,1.38,5,27.25),(0,25.00,1.55,0,27.35),(1.30,25.20,1.38,-5,27.25)],
    # Two temples sweep out from a clear center part; the forehead stays open.
    "soyo":   [(-1.28,25.15,1.40,-20,27.35),(1.28,25.15,1.40,20,27.35)],
    "taki":   [(-1.30,24.62,2.00,8,27.35),(0,24.55,2.15,0,27.45),(1.30,24.62,2.00,-8,27.35)],
    "rana":   [(-1.00,25.12,1.12,8,27.25),(1.00,25.20,1.12,-8,27.25)],
    "sakiko": [(-1.42,24.28,1.58,22,27.35),(.02,24.76,1.72,14,27.45),(1.40,25.24,1.44,5,27.35)],
    "uika":   [(-1.48,24.96,1.52,7,27.35),(0,25.20,1.55,0,27.45),(1.48,24.96,1.52,-7,27.35)],
    "umiri":  [(-1.42,25.22,1.48,-16,27.35),(-.04,24.78,1.70,-5,27.45),(1.40,24.30,1.50,10,27.35)],
}

# (face width multiplier, jaw width multiplier, vertical shortening)
# Changes apply to the real skin and jaw blocks, not only to hair framing.
FACE_SHAPE = {
    "anon": (1.00, .96, .00), "tomori": (1.02, 1.05, .12),
    "soyo": (.96, .84, .00), "taki": (.93, .88, .00),
    "rana": (1.02, 1.06, .00), "sakiko": (.95, .84, .00),
    "uika": (.98, .91, .00), "umiri": (.92, .86, .00),
}

TIP_SCALE = {"anon": .80, "tomori": .82, "soyo": .84, "taki": .90,
             "rana": .70, "sakiko": .80, "uika": .82, "umiri": .82}

# Face-framing panel end y and width differ by hairstyle.
FACE_FRAMES = {
    # left end, right end, width, x inset from temple
    "anon": (19.2, 20.2, .46, 2.58), "tomori": (20.4, 20.7, .56, 2.66),
    "soyo": (16.7, 16.4, .42, 2.54), "taki": (15.8, 15.4, .38, 2.52),
    "rana": (20.6, 20.4, .54, 2.64), "sakiko": (16.0, 17.5, .48, 2.60),
    "uika": (20.0, 19.7, .50, 2.68), "umiri": (18.2, 18.8, .44, 2.56),
}

FRAME_TILT = {
    "anon": (-8, 5), "tomori": (-10, 10), "soyo": (-4, 4), "taki": (-2, 2),
    "rana": (-12, 12), "sakiko": (-8, 2), "uika": (-10, 10), "umiri": (-12, 8),
}

# (width, y, angle, arch): role-specific mirrored brow shape and pitch.
BROWS = {
    "anon": (.92, 23.66, 12, .14), "tomori": (.76, 23.64, 7, .045),
    "soyo": (1.02, 23.71, 1, .15), "taki": (.94, 23.64, -10, -.10),
    "rana": (.76, 23.69, 0, .085), "sakiko": (.94, 23.68, -8, .12),
    "uika": (.70, 23.72, 2, .035), "umiri": (.98, 23.66, -9, -.075),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def element_descendants(data: dict, group_name: str) -> set[str]:
    group_ids = {g["uuid"] for g in data["groups"] if g["name"] == group_name}
    ids: set[str] = set()
    def visit(nodes, active=False):
        for node in nodes:
            if isinstance(node, str):
                if active:
                    ids.add(node)
            else:
                visit(node.get("children", []), active or node.get("uuid") in group_ids)
    visit(data["outliner"])
    return ids


def non_head_fingerprint(data: dict) -> str:
    head_ids = element_descendants(data, "head")
    kept = [e for e in data["elements"] if e["uuid"] not in head_ids]
    group_names = {g["uuid"]: g["name"] for g in data["groups"]}
    def filter_tree(nodes, in_head=False):
        result = []
        for node in nodes:
            if isinstance(node, str):
                if not in_head and node not in head_ids:
                    result.append(node)
            else:
                now_head = in_head or group_names.get(node.get("uuid")) == "head"
                if not now_head:
                    result.append({"uuid": node["uuid"], "children": filter_tree(node.get("children", []), now_head)})
        return result
    payload = {"elements": kept, "outliner": filter_tree(data["outliner"]),
               "animations": data.get("animations", []), "resolution": data.get("resolution")}
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def palette_uv(role: str, data: dict, color: str) -> tuple[int, list[float]]:
    if role == "anon":
        # Accepted Anon atlas: skin, hair, inner hair, then darker hair swatch.
        x = {"hair": 4.5, "hair_shade": 12.5}[color]
        return 8, [x, .5, x + 3, 3.5]
    palette = CHARACTERS / f"{role}-r16-standard/palette.json"
    colors = json.loads(palette.read_text(encoding="utf-8"))
    slot = list(colors).index(color)
    x, y = slot % 16 * 4, slot // 16 * 4
    return 0, [x + .5, y + .5, x + 3.5, y + 3.5]


def cube(name: str, center, size, rotation, parent: str, texture: int, uv: list[float]) -> dict:
    faces = {face: {"texture": texture, "uv": uv} for face in ("north", "south", "east", "west", "up", "down")}
    return {"op": "add_cube", "name": name, "parent": parent, "box_uv": False,
            "from": [round(c-s/2, 5) for c, s in zip(center, size)],
            "to": [round(c+s/2, 5) for c, s in zip(center, size)],
            "origin": list(center), "rotation": list(rotation), "texture": texture, "faces": faces}


def operations(role: str, data: dict) -> list[dict]:
    texture, uv = palette_uv(role, data, "hair")
    brow_texture, brow_uv = palette_uv(role, data, "hair_shade")
    groups = {g["name"] for g in data["groups"]}
    ops = []
    for group, fallback in (("front_fringe_r17", "side_swept_fringe"),
                            ("brow_geometry_r17", "brow_geometry"),
                            ("face_frame_r17", None)):
        if group in groups:
            ops.append({"op": "remove_node", "target": group})
        elif fallback and fallback in groups:
            ops.append({"op": "remove_node", "target": fallback})
    ops.extend([
        {"op": "add_group", "name": "front_fringe_r17", "origin": [0, 24.3, -3.22], "parent": "head"},
        {"op": "add_group", "name": "brow_geometry_r17", "origin": [0, 23.65, -3.18], "parent": "head"},
        {"op": "add_group", "name": "face_frame_r17", "origin": [0, 21, -3.24], "parent": "head"},
    ])
    face_scale, jaw_scale, face_raise = FACE_SHAPE[role]
    for element in data["elements"]:
        name = element.get("name", "")
        if name.endswith("continuous_face"):
            scale, dy = face_scale, face_raise
        elif name.endswith(("jaw_center", "jaw_internal_bridge", "shallow_jaw_-1", "shallow_jaw_1")):
            scale, dy = jaw_scale, face_raise
        else:
            continue
        old_from, old_to = element["from"], element["to"]
        origin = element.get("origin", [0, 0, 0])
        ops.append({"op": "update_node", "target": name,
                    "from": [round(old_from[0]*scale, 5), round(old_from[1]+dy, 5), old_from[2]],
                    "to": [round(old_to[0]*scale, 5), round(old_to[1]+dy, 5), old_to[2]],
                    "origin": [round(origin[0]*scale, 5), round(origin[1]+dy, 5), origin[2]]})
    for idx, (x, bottom, width, tilt, top) in enumerate(FRONT_LOCKS[role]):
        # The root overlaps both the forehead tip and the original crown in
        # actual 3D volume. Extend its rear edge past the crown's front edge and
        # raise it into the existing crown; touching only the tip left a slit.
        split = max(bottom + .80, top - .65)
        overlap = .34
        root_top = top + .45
        root_bottom = split - overlap
        root_center = (x, (root_top + root_bottom) / 2, -2.45)
        tip_center = (x, (split + overlap + bottom) / 2, -3.20)
        ops.append(cube(f"{role}_r17_main_lock_root_{idx+1:02d}", root_center,
                        (width, root_top-root_bottom, 2.5), (0, 0, tilt),
                        "front_fringe_r17", texture, uv))
        ops.append(cube(f"{role}_r17_main_lock_tip_{idx+1:02d}", tip_center,
                        (width*TIP_SCALE[role], split-bottom+overlap, .90), (0, 0, tilt),
                        "front_fringe_r17", texture, uv))
        # A compact bridge stays inside the old crown. It overlaps the root's
        # rear edge and the rotated crown arch so there is no narrow side slit.
        bridge_center = (x, top + .30, -.90)
        ops.append(cube(f"{role}_r17_main_lock_crown_bridge_{idx+1:02d}", bridge_center,
                        (width+.80, .90, 1.50), (0, 0, tilt),
                        "front_fringe_r17", texture, uv))
    left_end, right_end, frame_width, frame_x = FACE_FRAMES[role]
    left_tilt, right_tilt = FRAME_TILT[role]
    for side, end_y, tilt in ((-1, left_end, left_tilt), (1, right_end, right_tilt)):
        top, split = 25.35, 22.10
        side_name = "left" if side < 0 else "right"
        root_center = (side * frame_x, (top + split) / 2, -2.22)
        tip_center = (side * (frame_x + .10), (split + end_y) / 2, -2.25)
        ops.append(cube(f"{role}_r17_face_frame_root_{side_name}", root_center,
                        (frame_width, top-split, 1.55), (0, 0, tilt),
                        "face_frame_r17", texture, uv))
        ops.append(cube(f"{role}_r17_face_frame_tip_{side_name}", tip_center,
                        (frame_width*.68, split-end_y, 1.50), (0, 0, tilt*2.2),
                        "face_frame_r17", texture, uv))
    brow_width, brow_y, angle, arch = BROWS[role]
    for side in (-1, 1):
        side_name = "left" if side < 0 else "right"
        # A continuous main brow and one slightly arched outer tail; the tail
        # overlaps the base to avoid isolated marks or visible seams.
        main_center = (side * 1.30, brow_y, -3.34)
        ops.append(cube(f"{role}_r17_brow_{side_name}_main", main_center,
                        (brow_width*.82, .13, .20), (0, 0, side*angle*.48),
                        "brow_geometry_r17", brow_texture, brow_uv))
        tail_center = (side * 1.58, brow_y + arch*.30, -3.34)
        ops.append(cube(f"{role}_r17_brow_{side_name}_tail", tail_center,
                        (brow_width*.43, .105, .20), (0, 0, side*(angle*.48 + 3)),
                        "brow_geometry_r17", brow_texture, brow_uv))
    return ops


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare", action="store_true", help="copy R16 sources and write the isolated edit plan")
    parser.add_argument("--receipt", action="store_true", help="compare frozen non-head geometry after MCP edits")
    args = parser.parse_args()
    if args.prepare == args.receipt:
        parser.error("Choose exactly one of --prepare or --receipt.")
    OUT.mkdir(parents=True, exist_ok=True)
    if args.receipt:
        plan_path = OUT / "blockbench-edit-plan.json"
        records = json.loads(plan_path.read_text(encoding="utf-8"))
        for role, record in records.items():
            source = ROOT / record["source"]
            output = ROOT / record["output"]
            source_data = json.loads(source.read_text(encoding="utf-8"))
            output_data = json.loads(output.read_text(encoding="utf-8"))
            before = non_head_fingerprint(source_data)
            after = non_head_fingerprint(output_data)
            record["non_head_fingerprint_before"] = before
            record["non_head_fingerprint_after"] = after
            record["non_head_unchanged"] = before == after
            record["output_sha256"] = sha256(output)
            record["counts_after"] = {
                "groups": len(output_data.get("groups", [])),
                "cubes": len(output_data.get("elements", [])),
                "textures": len(output_data.get("textures", [])),
                "animations": len(output_data.get("animations", [])),
            }
            record["status"] = "non-head geometry unchanged" if before == after else "NON-HEAD CHANGE DETECTED"
        plan_path.write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        failures = [role for role, record in records.items() if not record["non_head_unchanged"]]
        print(f"Recorded {len(records)} source/output fingerprints; unchanged={len(records)-len(failures)}")
        if failures:
            raise SystemExit("Non-head geometry changed: " + ", ".join(failures))
        return

    records = {}
    for role, src in SOURCES.items():
        raw = json.loads(src.read_text(encoding="utf-8"))
        dst = OUT / role / f"{role}-head-r17.bbmodel"
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        records[role] = {
            "source": str(src.relative_to(ROOT)), "source_sha256": sha256(src),
            "output": str(dst.relative_to(ROOT)),
            "non_head_fingerprint_before": non_head_fingerprint(raw),
            "edit_operations": operations(role, raw), "status": "copied; Blockbench MCP edits pending",
        }
    (OUT / "blockbench-edit-plan.json").write_text(
        json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(records)} R17 copies in {OUT}")


if __name__ == "__main__":
    main()
