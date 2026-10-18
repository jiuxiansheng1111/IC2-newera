"""Inspect emitted robot art resources; does not start Minecraft or model services."""
from __future__ import annotations

import base64
import hashlib
import io
import json
import math
from datetime import datetime, timezone

from PIL import Image

from humanoid_robot_sculpt import MODEL_REVISION, RESOLUTION, STUDY, STYLE, TILE


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def finite(values):
    return all(isinstance(value, (int, float)) and math.isfinite(value) for value in values)


def inspect():
    baseline = STUDY / "iterations/v6"
    baseline_report = json.loads((baseline / "resource-audit.json").read_text(encoding="utf-8"))
    old_bodies = {entry["id"]: entry["body_snapshot_sha256"] for entry in baseline_report["records"]}
    old_report_path = STUDY / "resource-audit.json"
    old_report = json.loads(old_report_path.read_text(encoding="utf-8")) if old_report_path.exists() else {}
    records, errors = [], []
    for identifier in STYLE:
        paths = [STUDY / "models" / (identifier + ".json"), STUDY / "textures" / (identifier + ".png"),
                 STUDY / "editable" / (identifier + ".bbmodel")]
        model = json.loads(paths[0].read_text(encoding="utf-8"))
        project = json.loads(paths[2].read_text(encoding="utf-8"))
        texture = Image.open(paths[1]).convert("RGBA")
        previous_texture = Image.open(baseline / "textures" / (identifier + ".png")).convert("RGBA")
        counts = dict(bones=0, meshes=0, vertices=0, faces=0)
        issues, body_tiles, expected_shading = [], set(), []

        def visit(part, is_body):
            counts["bones"] += 1
            if not finite(part["pivot"] + part["rotation"]):
                issues.append("nonfinite bone transform")
            for mesh in part["meshes"]:
                expected_shading.append(mesh.get("shading", "flat"))
                counts["meshes"] += 1
                counts["vertices"] += len(mesh["vertices"])
                counts["faces"] += len(mesh["faces"])
                if any(len(vertex) != 3 or not finite(vertex) for vertex in mesh["vertices"]):
                    issues.append("invalid vertex")
                if mesh.get("shading") == "smooth":
                    normals = mesh.get("normals", [])
                    if len(normals) != len(mesh["vertices"]) or any(len(n) != 3 or not finite(n) or
                           abs(sum(v*v for v in n)-1) > 1e-5 for n in normals):
                        issues.append("invalid smooth normals")
                for face in mesh["faces"]:
                    indices, uv = face["vertices"], face["uv"]
                    if len(indices) < 3 or any(not isinstance(i, int) or not 0 <= i < len(mesh["vertices"]) for i in indices):
                        issues.append("invalid face indices")
                    valid_uv = (len(uv) == len(indices) and all(len(p) == 2 and finite(p) and
                                all(0 <= value <= 1 for value in p) for p in uv))
                    if not valid_uv:
                        issues.append("invalid UV")
                        continue
                    for u, v in uv:
                        x = min(RESOLUTION - 1, int(u * RESOLUTION))
                        y = min(RESOLUTION - 1, int(v * RESOLUTION))
                        if texture.getpixel((x, y))[3] == 0:
                            issues.append("UV points to empty atlas")
                        if is_body:
                            body_tiles.add((x // TILE * TILE, y // TILE * TILE))
            for child in part["children"].values():
                visit(child, is_body)

        for name, part in model["parts"].items():
            visit(part, name != "head")
        if model.get("revision") != MODEL_REVISION or model["format"] != "ic2newera_rigged_mesh_v2":
            issues.append("revision or format mismatch")
        if project.get("name") != identifier + "_" + MODEL_REVISION:
            issues.append("editor revision name")
        if texture.size != (RESOLUTION, RESOLUTION):
            issues.append("texture dimensions")
        uuids = [g["uuid"] for g in project["groups"]] + [e["uuid"] for e in project["elements"]]
        used = []

        def outliner(entry):
            if isinstance(entry, str):
                used.append(entry)
            else:
                used.append(entry["uuid"])
                for child in entry["children"]:
                    outliner(child)

        for entry in project["outliner"]:
            outliner(entry)
        if len(uuids) != len(set(uuids)) or sorted(used) != sorted(uuids):
            issues.append("editor hierarchy references")
        if len(project["groups"]) != counts["bones"] or len(project["elements"]) != counts["meshes"]:
            issues.append("editor geometry count")
        if [element.get("shading") for element in project["elements"]] != expected_shading:
            issues.append("editor shading differs from source")
        for element in project["elements"]:
            if element.get("shading") not in ("flat", "smooth"):
                issues.append("editor shading setting")
            if any(len(p) != 3 or not finite(p) for p in element["vertices"].values()):
                issues.append("editor vertex")
            for face in element["faces"].values():
                if set(face["vertices"]) != set(face["uv"]) or any(key not in element["vertices"] for key in face["vertices"]):
                    issues.append("editor face references")
                if any(not finite(p) or any(value < 0 or value > RESOLUTION for value in p) for p in face["uv"].values()):
                    issues.append("editor UV")
                if face["texture"] != 0:
                    issues.append("editor texture reference")
        source = project["textures"][0]["source"]
        if not source.startswith("data:image/png;base64,"):
            issues.append("editor texture is not embedded PNG")
        else:
            embedded = Image.open(io.BytesIO(base64.b64decode(source.split(",", 1)[1]))).convert("RGBA")
            if embedded.tobytes() != texture.tobytes():
                issues.append("embedded texture differs")
        body_hash = digest({name: part for name, part in model["parts"].items() if name != "head"})
        body_matches = body_hash == old_bodies[identifier]
        if not body_matches:
            issues.append("body differs from V6")
        body_texture_matches = all(texture.crop((x, y, x + TILE, y + TILE)).tobytes() ==
                                   previous_texture.crop((x, y, x + TILE, y + TILE)).tobytes()
                                   for x, y in body_tiles)
        if not body_texture_matches:
            issues.append("body texture differs from V6")
        for suffix, size in (("-study.png", (1320, 950)), ("-head-study.png", (1200, 450)),
                             ("-orthographic.png", (1320, 950)), ("-head-orthographic.png", (1200, 450))):
            preview = Image.open(STUDY / "preview" / (identifier + suffix))
            preview.load()
            if preview.size != size:
                issues.append("preview dimensions: " + suffix)
        record = dict(id=identifier, revision=model.get("revision"), **counts,
                      smoothed_meshes=expected_shading.count("smooth"),
                      body_snapshot_sha256=body_hash, body_matches_v6=body_matches,
                      body_texture_matches_v6=body_texture_matches,
                      files_sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                      issues=sorted(set(issues)))
        records.append(record)
        errors.extend(identifier + ": " + issue for issue in record["issues"])
    report = dict(checked_at=datetime.now(timezone.utc).isoformat(), revision=MODEL_REVISION,
                  kind="static_resource_inspection", result="passed" if not errors else "failed",
                  records=records, errors=errors,
                  editor_import_observed=old_report.get("editor_import_observed", []),
                  limitations=["Appearance and similarity require human visual acceptance.",
                               "Editor import observations apply only to their recorded revision.",
                               "Minecraft renderer, animations, build, installation and runtime acceptance are pending."])
    old_report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(result=report["result"], revision=MODEL_REVISION, models=len(records),
                         faces=sum(r["faces"] for r in records),
                         all_bodies_match_v6=all(r["body_matches_v6"] for r in records),
                         all_body_textures_match_v6=all(r["body_texture_matches_v6"] for r in records),
                         errors=errors), ensure_ascii=False))
    return not errors


if __name__ == "__main__":
    raise SystemExit(0 if inspect() else 1)
