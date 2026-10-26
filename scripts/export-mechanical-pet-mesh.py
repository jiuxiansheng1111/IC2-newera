#!/usr/bin/env python3
"""Export authored voxel Blockbench cube rigs into runtime flat-shaded quads.

Only per-face-UV positive-thickness, integer-grid cuboids are accepted. Free mesh
elements, planes, bevels and static rotations are rejected to keep the animals in
the requested Minecraft block style.
"""
from __future__ import annotations

import base64
import hashlib
import json
import math
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "art/mechanical-pets/voxel"
ASSET = ROOT / "build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera"
MESH_DIR = ASSET / "pet_meshes"
TEXTURE_DIR = ASSET / "textures/entity/pet"
RIG = ("body", "head", "jaw", "ear_l", "ear_r", "tail", "leg_fl", "leg_fr", "leg_bl", "leg_br")
EXPECTED_PARENT = {"body": None, "head": "body", "jaw": "head", "ear_l": "head", "ear_r": "head",
                   "tail": "body", "leg_fl": "body", "leg_fr": "body", "leg_bl": "body", "leg_br": "body"}
EPSILON = 1e-8


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def vec3(value, field):
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        raise ValueError(f"{field} must have three coordinates")
    result = tuple(float(v) for v in value)
    if not all(math.isfinite(v) for v in result):
        raise ValueError(f"{field} contains a non-finite coordinate")
    return result


def integer_vec3(value, field):
    result = vec3(value, field)
    if any(abs(component - round(component)) > EPSILON for component in result):
        raise ValueError(f"{field} must use integer 16-unit grid coordinates: {result}")
    return tuple(round(component) for component in result)


def rotate_zyx(point, pivot, angles):
    """Apply Rz * Ry * Rx about an absolute Blockbench pivot."""
    x, y, z = (a - b for a, b in zip(point, pivot))
    rx, ry, rz = (math.radians(a) for a in angles)
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return tuple(c + p for c, p in zip((x, y, z), pivot))


def ancestry(document):
    groups = {g["uuid"]: g for g in document.get("groups", [])}
    parents = {}
    def visit(nodes, parent=None):
        for node in nodes:
            identifier = node if isinstance(node, str) else node["uuid"]
            if identifier in parents:
                raise ValueError(f"Duplicate/out-of-place outliner node {identifier}")
            parents[identifier] = parent
            if isinstance(node, dict):
                visit(node.get("children", []), identifier)
    visit(document.get("outliner", []))
    def chain(identifier):
        result, current = [], identifier if identifier in groups else parents.get(identifier)
        while current is not None:
            if current not in groups:
                raise ValueError(f"Outliner group {current} is missing")
            result.append(groups[current])
            current = parents.get(current)
        return list(reversed(result))
    return groups, parents, chain


def apply_group_chain(point, groups):
    for group in groups:
        pivot = integer_vec3(group.get("origin", [0, 0, 0]), f"{group['name']}.origin")
        rotation = vec3(group.get("rotation", [0, 0, 0]), f"{group['name']}.rotation")
        if any(value != 0 for value in rotation):
            raise ValueError(f"{group['name']}: authored rest rotations must be zero")
        point = rotate_zyx(point, pivot, rotation)
    return point


def embedded_png(document, model_file, kind):
    textures = document.get("textures", [])
    if len(textures) != 1:
        raise ValueError(f"{kind}: expected one embedded atlas texture, found {len(textures)}")
    texture = textures[0]
    source = texture.get("source", "")
    if not source.startswith("data:image/png;base64,"):
        raise ValueError(f"{kind}: only embedded PNG atlas is supported")
    raw = base64.b64decode(source.partition(",")[2], validate=True)
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{kind}: embedded texture is not a PNG")
    width, height = struct.unpack(">II", raw[16:24])
    declared = (int(texture.get("width", width)), int(texture.get("height", height)))
    if (width, height) != declared or width <= 0 or height <= 0:
        raise ValueError(f"{kind}: embedded PNG dimensions disagree with Blockbench metadata")
    if "uv_width" in texture and abs(float(texture["uv_width"]) - width) > EPSILON:
        raise ValueError(f"{kind}: atlas UV width must match PNG pixels")
    if "uv_height" in texture and abs(float(texture["uv_height"]) - height) > EPSILON:
        raise ValueError(f"{kind}: atlas UV height must match PNG pixels")
    candidates = [model_file.with_name(f"{kind}_voxel_atlas.png"),
                  model_file.with_name(f"{kind}-atlas.png"),
                  model_file.with_name(f"{kind}_fur_atlas.png")]
    independent = next((candidate for candidate in candidates if candidate.exists()), candidates[0])
    if independent.exists() and independent.read_bytes() != raw:
        # PNG encoders can produce different bytes for identical pixels; dimensions and
        # decoded pixels are the authoritative contract when Pillow is available.
        try:
            from PIL import Image
            import io
            if Image.open(independent).convert("RGBA").tobytes() != Image.open(io.BytesIO(raw)).convert("RGBA").tobytes():
                raise ValueError(f"{kind}: separate atlas source pixels differ from embedded texture")
        except ImportError as exc:
            raise ValueError(f"{kind}: install Pillow to verify alternate PNG encoding") from exc
    return raw, width, height


UV_CORNERS = ((0, 0), (0, 1), (1, 1), (1, 0))


def face_point(direction, lo, hi, u, v):
    """Blockbench's per-face UV orientation in Y-up model coordinates."""
    lerp = lambda a, b, t: a + (b - a) * t
    lx, ly, lz = lo; hx, hy, hz = hi
    if direction == "north": return (lerp(hx, lx, u), lerp(hy, ly, v), lz)
    if direction == "south": return (lerp(lx, hx, u), lerp(hy, ly, v), hz)
    if direction == "east": return (hx, lerp(hy, ly, v), lerp(hz, lz, u))
    if direction == "west": return (lx, lerp(hy, ly, v), lerp(lz, hz, u))
    if direction == "up": return (lerp(lx, hx, u), hy, lerp(lz, hz, v))
    if direction == "down": return (lerp(lx, hx, u), ly, lerp(hz, lz, v))
    raise ValueError(f"Unknown Blockbench cube face {direction!r}")


def face_uv(face, atlas_width, atlas_height):
    uv = face.get("uv")
    if not isinstance(uv, list) or len(uv) != 4:
        raise ValueError(f"Cube face UV must be [u0,v0,u1,v1], got {uv!r}")
    u0, v0, u1, v1 = map(float, uv)
    if not all(math.isfinite(v) and 0 <= v <= limit
               for v, limit in zip((u0, v0, u1, v1), (atlas_width, atlas_height, atlas_width, atlas_height))):
        raise ValueError(f"Cube face UV outside {atlas_width}x{atlas_height} atlas: {uv!r}")
    if any(abs(value - round(value)) > EPSILON for value in (u0, v0, u1, v1)):
        raise ValueError(f"Cube UV coordinates must use integer atlas pixels: {uv!r}")
    rotation = float(face.get("rotation", 0))
    if not math.isfinite(rotation) or rotation % 90:
        raise ValueError(f"Cube UV rotation must use quarter turns: {rotation}")
    pairs = [((u0 + (u1-u0)*u) / atlas_width,
              (v0 + (v1-v0)*v) / atlas_height) for u, v in UV_CORNERS]
    return pairs


def cube_bounds(element):
    lo = list(vec3(element.get("from"), f"{element.get('name')}.from"))
    hi = list(vec3(element.get("to"), f"{element.get('name')}.to"))
    inflate = float(element.get("inflate") or 0)
    if not math.isfinite(inflate) or abs(inflate) > EPSILON:
        raise ValueError(f"{element.get('name')}: inflate is unsupported; use integer cuboid bounds")
    lo = [v - inflate for v in lo]; hi = [v + inflate for v in hi]
    if any(abs(v - round(v)) > EPSILON for v in (*lo, *hi)):
        raise ValueError(f"{element.get('name')}: cube coordinates must use integer 16-unit grid values")
    if any(b <= a for a, b in zip(lo, hi)):
        raise ValueError(f"{element.get('name')}: voxel pets require positive-thickness cuboids; planes are rejected")
    if any(b - a > 16 for a, b in zip(lo, hi)):
        raise ValueError(f"{element.get('name')}: each cuboid dimension must be at most 16 units")
    return lo, hi


def normal(points):
    a, b, c = points[:3]
    ab = tuple(b[i] - a[i] for i in range(3)); ac = tuple(c[i] - a[i] for i in range(3))
    n = (ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0])
    length = math.sqrt(sum(x * x for x in n))
    if length <= EPSILON:
        raise ValueError("Degenerate mesh face")
    return tuple(x / length for x in n)


def export_one(kind):
    model_file = SOURCE / kind / f"{kind}.bbmodel"
    if not model_file.is_file():
        raise FileNotFoundError(f"Expected authored voxel cube source at {model_file}")
    source_bytes = model_file.read_bytes()
    document = json.loads(source_bytes)
    if document.get("meta", {}).get("model_format") != "free":
        raise ValueError(f"{kind}: Blockbench source must use free model format")
    # Export the authored rest pose only. Blockbench clips are intentionally not
    # copied; the in-game hierarchy is driven by MechanicalPetEntity actions.
    groups, parents, chain = ancestry(document)
    by_name = {}
    name_by_uuid = {}
    for group in groups.values():
        group_name = group.get("name")
        if not group_name or group_name in by_name:
            raise ValueError(f"{kind}: every group must have a unique nonempty name ({group_name!r})")
        by_name[group_name] = group
        name_by_uuid[group["uuid"]] = group_name
        integer_vec3(group.get("origin", [0, 0, 0]), f"{group_name}.origin")
        if any(value != 0 for value in vec3(group.get("rotation", [0, 0, 0]), f"{group_name}.rotation")):
            raise ValueError(f"{kind}: source rest rotation must be zero for {group_name}")
    missing = [name for name in RIG if name not in by_name]
    if missing:
        raise ValueError(f"{kind}: missing rig bone groups {missing}")
    for name in RIG:
        group = by_name[name]
        parent = next((g["name"] for g in reversed(chain(group["uuid"])[:-1]) if g.get("name") in RIG), None)
        if parent != EXPECTED_PARENT[name]:
            raise ValueError(f"{kind}: {name} must parent to {EXPECTED_PARENT[name]}, got {parent}")

    texture, atlas_width, atlas_height = embedded_png(document, model_file, kind)
    ordered_groups = sorted(groups.values(), key=lambda group: len(chain(group["uuid"])))
    vertices_by_bone = {group["name"]: [] for group in ordered_groups}
    source_vertices = 0
    visible_elements = 0
    for element in document.get("elements", []):
        if element.get("type") != "cube":
            raise ValueError(f"{kind}: voxel export accepts positive-thickness cube elements only; rejected {element.get('type')} {element.get('name')}")
        group_chain = chain(element["uuid"])
        if not element.get("visibility", True) or not element.get("export", True) or any(
                not g.get("visibility", True) or not g.get("export", True) for g in group_chain):
            continue
        if not group_chain:
            raise ValueError(f"{kind}: cube {element.get('name')} is not under an authored rig bone")
        bone = group_chain[-1]["name"]
        rest_chain = group_chain
        element_rotation = vec3(element.get("rotation", [0, 0, 0]), "cube.rotation")
        if any(value != 0 for value in element_rotation):
            raise ValueError(f"{kind}: static cube rotation must be zero: {element.get('name')}")
        element_origin = integer_vec3(element.get("origin", [0, 0, 0]), "cube.origin")
        lo, hi = cube_bounds(element)
        faces = element.get("faces", {})
        if set(faces) - {"north", "south", "east", "west", "up", "down"}:
            raise ValueError(f"{kind}: unexpected cube face keys in {element.get('name')}")
        for direction in ("north", "south", "east", "west", "up", "down"):
            face = faces.get(direction)
            if not face or not face.get("enabled", True) or face.get("texture") is None:
                continue
            if face.get("texture") != 0:
                raise ValueError(f"{kind}: all visible faces must use the single embedded atlas")
            uvs = face_uv(face, atlas_width, atlas_height)
            uv_turns = int(float(face.get("rotation", 0)) / 90) % 4
            points = []
            for u, v in UV_CORNERS:
                # Blockbench rotates the sampled geometry under the fixed UV corners.
                for _ in range(uv_turns): u, v = 1 - v, u
                point = face_point(direction, lo, hi, u, v)
                point = rotate_zyx(point, element_origin, element_rotation)
                point = apply_group_chain(point, rest_chain)
                points.append(point)
            source_vertices += len(points)
            n = normal(points)
            for p, uv in zip(points, uvs):
                vertices_by_bone[bone].append([*p, *uv, *n])
        visible_elements += 1

    bones = []
    for group in ordered_groups:
        name = group["name"]
        ancestor_groups = chain(group["uuid"])
        parent = (EXPECTED_PARENT[name] if name in RIG else
                  (ancestor_groups[-2]["name"] if len(ancestor_groups) > 1 else None))
        group_chain = chain(group["uuid"])
        pivot = vec3(group.get("origin", [0, 0, 0]), f"{name}.origin")
        # Absolute rest pivot after ancestor default transforms. Its own rotation
        # pivots around itself, so does not move this point.
        pivot = apply_group_chain(pivot, group_chain[:-1])
        bones.append({"name": name, "parent": parent, "pivot": list(pivot),
                      "rotation": vec3(group.get("rotation", [0, 0, 0]), f"{name}.rotation"),
                      "vertices": vertices_by_bone[name]})

    all_points = [vertex[:3] for bone in bones for vertex in bone["vertices"]]
    if not all_points:
        raise ValueError(f"{kind}: source contains no visible cube faces")
    floor_offset = min(point[1] for point in all_points)
    if abs(floor_offset) > EPSILON:
        raise ValueError(f"{kind}: authored bottom must be y=0, got {floor_offset:.5f}")
    bounds = [[min(point[i] for point in all_points) for i in range(3)],
              [max(point[i] for point in all_points) for i in range(3)]]
    source_height = bounds[1][1]
    if not 6.0 <= source_height <= 16.0:
        raise ValueError(f"{kind}: authored animal height must be at most 16 integer units; got {source_height:.3f}")
    if any(bounds[1][axis] - bounds[0][axis] > 16 for axis in range(3)):
        raise ValueError(f"{kind}: the overall animal bounding box must fit within 16 units per axis")
    if any(abs(component - round(component)) > EPSILON for corner in bounds for component in corner):
        raise ValueError(f"{kind}: final bounds must align to the integer 16-unit grid")
    for bone in bones:
        bone["vertices"] = [component for vertex in bone["vertices"] for component in vertex]
    mesh = {"format": "ic2newera:voxel_pet_mesh_v1", "species": kind,
            "atlas": [atlas_width, atlas_height], "source_sha256": digest(source_bytes),
            "units_per_block": 16, "bounds": bounds, "bones": bones}
    MESH_DIR.mkdir(parents=True, exist_ok=True); TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    mesh_file = MESH_DIR / f"{kind}.json"; texture_file = TEXTURE_DIR / f"{kind}.png"
    mesh_file.write_text(json.dumps(mesh, separators=(",", ":"), ensure_ascii=False) + "\n", encoding="utf-8")
    texture_file.write_bytes(texture)
    return {"species": kind, "source": model_file.relative_to(ROOT).as_posix(),
            "source_sha256": digest(source_bytes), "mesh_resource": mesh_file.relative_to(ROOT).as_posix(),
            "mesh_sha256": digest(mesh_file.read_bytes()), "texture_resource": texture_file.relative_to(ROOT).as_posix(),
            "texture_sha256": digest(texture), "atlas": [atlas_width, atlas_height],
            "bounds": bounds, "source_height": source_height,
            "source_vertices": source_vertices,
            "runtime_quads": sum(len(b["vertices"]) // (4 * 8) for b in bones),
            "bone_vertex_counts": {b["name"]: len(b["vertices"]) for b in bones},
            "visible_cube_elements": visible_elements}


def main():
    results = [export_one(kind) for kind in ("cat", "dog")]
    manifest_file = MESH_DIR / "manifest.json"
    manifest_file.write_text(json.dumps({"format": "ic2newera:voxel_pet_mesh_v1", "coordinate_system": "Blockbench Y-up, 16 units per block; face toward -Z",
                                        "rotation_order": "ZYX (Rz * Ry * Rx)", "uv": "atlas pixels normalized to top-left PNG coordinates",
            "rig": list(RIG), "pets": results}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"pets": results, "manifest": manifest_file.relative_to(ROOT).as_posix()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
