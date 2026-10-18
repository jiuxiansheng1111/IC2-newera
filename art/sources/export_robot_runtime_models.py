"""Export the eight approved R16 Generic cuboid projects without changing them.

This is an original, deliberately narrow converter, not a Java block-model export.
It bakes Blockbench ZYX Euler transforms and UV-to-face directions into float32
quads. No third-party geometry, code or image pixels are imported. Requires Pillow.
The output has seven rigid parts; source animation clips are deliberately rejected.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import math
from pathlib import Path
import struct

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"
MODEL_DIR = ASSETS / "robot_models/r16"
TEXTURE_DIR = ASSETS / "textures/entity/robot/r16"
CHARACTERS = ("anon", "tomori", "soyo", "taki", "rana", "sakiko", "uika", "umiri")
PARTS = ("body", "head", "arm_left", "arm_right", "leg_left", "leg_right", "skirt")
MAGIC = b"NER16M01"
APPROVED_SHA256 = {
    "anon": "b64ef1cf9ca3c9202d501a1fe8a284b536e99e77790bd98f9159451a1e86a4ec",
    "tomori": "5d0cc46d45e7bd0acaa710ecce4f53c413be32a8ec8fda9bcdb334fc0237f496",
    "soyo": "c0b6a3406030f055d5a8242bb58e27d9dcb0077000eef33ebf57ba63f598d562",
    "taki": "bd0e4c0e7d8c27b0f93511629a53cdd6e6e3efee2a8e231f878872d962036a32",
    "rana": "53040887eaf3ec290279356a0e1f749f224728be427d83d0d5d09726575a51e9",
    "sakiko": "8a612919cd1e4e2e283f663c53565610b135dc27893d189ddefc1b3b7d58824f",
    "uika": "cf9323e48d521e9f36b41ef2fc991c9d384767baf1277debd099a864ac920e7a",
    "umiri": "62933d7340fbc11847e497b069b8c14189eb9b73fb7c7de8cb2f03a98ea1b73d",
}
# Pairs are normalized texture coordinates, in outward winding before the Y flip.
UV_CORNERS = ((0, 0), (0, 1), (1, 1), (1, 0))


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def vector(value, default=(0, 0, 0)):
    result = tuple(float(x) for x in (value if value is not None else default))
    if len(result) != 3 or not all(math.isfinite(x) for x in result):
        raise ValueError(f"Invalid vector: {value}")
    return result


def rotate(point, origin, angles):
    """Rz * Ry * Rx around an absolute Blockbench origin; no Euler rounding."""
    x, y, z = (a - b for a, b in zip(point, origin))
    rx, ry, rz = (math.radians(a) for a in angles)
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return tuple(a + b for a, b in zip((x, y, z), origin))


def face_point(direction, from_, to, u, v):
    """Blockbench face directions: image V starts at the top, including up/down."""
    lo_x, lo_y, lo_z = from_
    hi_x, hi_y, hi_z = to
    lerp = lambda a, b, t: a + (b - a) * t
    if direction == "north": return (lerp(hi_x, lo_x, u), lerp(hi_y, lo_y, v), lo_z)
    if direction == "south": return (lerp(lo_x, hi_x, u), lerp(hi_y, lo_y, v), hi_z)
    if direction == "east": return (hi_x, lerp(hi_y, lo_y, v), lerp(hi_z, lo_z, u))
    if direction == "west": return (lo_x, lerp(hi_y, lo_y, v), lerp(lo_z, hi_z, u))
    if direction == "up": return (lerp(lo_x, hi_x, u), hi_y, lerp(lo_z, hi_z, v))
    if direction == "down": return (lerp(lo_x, hi_x, u), lo_y, lerp(hi_z, lo_z, v))
    raise ValueError(f"Unknown face direction: {direction}")


def entity_point(p):
    # LivingEntityRenderer translates by -1.501 then flips X/Y. 24/16 is its
    # ground-plane convention. Keep X and Z so the original front stays north.
    return (p[0] / 16, (24 - p[1]) / 16, p[2] / 16)


def hierarchy(document):
    groups = {g["uuid"]: g for g in document["groups"]}
    parents = {}
    def visit(nodes, parent=None):
        for node in nodes:
            identifier = node if isinstance(node, str) else node["uuid"]
            if identifier in parents: raise ValueError(f"Duplicate outliner node: {identifier}")
            parents[identifier] = parent
            if isinstance(node, dict): visit(node.get("children", []), identifier)
    visit(document["outliner"])
    def chain(identifier):
        result = []
        current = parents[identifier]
        while current is not None:
            result.append(groups[current])
            current = parents[current]
        return result
    return groups, chain


def atlas(document, key):
    images = []
    for tex in document["textures"]:
        source = tex["source"]
        if not source.startswith("data:image/png;base64,"):
            raise ValueError(f"Only embedded PNG textures are supported: {tex.get('name')}")
        raw = base64.b64decode(source.partition(",")[2], validate=True)
        image = Image.open(io.BytesIO(raw)).convert("RGBA")
        if image.height != tex["height"] or image.width != tex["width"]:
            raise ValueError("Animated or mismatched texture dimensions are unsupported")
        if not set(image.getchannel("A").tobytes()).issubset({0, 255}):
            raise ValueError("R16 cutout rendering requires binary alpha; do not discard partial transparency")
        images.append((image, raw, tex))
    # Exact pixel copies, never resampling. Include all original atlases, even
    # older sheets with zero referenced faces, so no source texture is discarded.
    if len(images) == 1:
        width, height = images[0][0].size
        data = images[0][1]
        offsets = [(0, 0)]
        packed = images[0][0]
    else:
        width = 1 << (max(im.width for im, _, _ in images) - 1).bit_length()
        height = 1 << (sum(im.height for im, _, _ in images) - 1).bit_length()
        packed = Image.new("RGBA", (width, height))
        offsets, y = [], 0
        for image, _, _ in images:
            offsets.append((0, y)); packed.paste(image, (0, y)); y += image.height
        output = io.BytesIO(); packed.save(output, format="PNG", optimize=False); data = output.getvalue()
    facts = []
    for index, ((image, raw, tex), (x, y)) in enumerate(zip(images, offsets)):
        if packed.crop((x, y, x + image.width, y + image.height)).tobytes() != image.tobytes():
            raise ValueError("Atlas packing changed RGBA pixels")
        facts.append(dict(index=index, name=tex["name"], png_sha256=digest(raw),
                          rgba_sha256=digest(image.tobytes()), width=image.width,
                          height=image.height, offset=[x, y], pixels_identical=True,
                          alpha_values=sorted(set(image.getchannel("A").tobytes()))))
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    target = TEXTURE_DIR / f"{key}.png"; target.write_bytes(data)
    # SimpleTexture reads these flags in 1.19.2. Nearest sampling prevents any
    # interpolated bleed between Anon's independently packed original sheets.
    target.with_suffix(".png.mcmeta").write_text('{"texture":{"blur":false,"clamp":true}}\n', encoding="utf8")
    return images, offsets, width, height, facts, target


def export_character(key):
    source = ROOT / f"art/robot-models/current/{key}.bbmodel"
    original = source.read_bytes(); document = json.loads(original)
    if digest(original) != APPROVED_SHA256[key]: raise ValueError(f"Approved {key} R16 hash changed")
    if document["meta"]["model_format"] != "free" or document.get("animations"):
        raise ValueError("Expected a static Generic/free R16 project")
    groups, chain = hierarchy(document)
    images, offsets, width, height, texture_facts, texture_file = atlas(document, key)
    by_name = {g["name"]: g for g in groups.values()}
    vertices = {part: [] for part in PARTS}
    pivots = {}
    for part in PARTS:
        group = by_name[part]; p = vector(group["origin"])
        for ancestor in chain(group["uuid"]):
            p = rotate(p, vector(ancestor.get("origin")), vector(ancestor.get("rotation")))
        pivots[part] = entity_point(p)
    quads, transformed_points = 0, []
    uv_rotation_count, multi_axis_count = 0, 0
    for element in document["elements"]:
        if element.get("type", "cube") != "cube" or element.get("box_uv", False):
            raise ValueError(f"Only per-face cuboids are supported: {element['name']}")
        if any(element.get(k, False) for k in ("rescale", "inflate")) or element.get("stretch", [1, 1, 1]) != [1, 1, 1]:
            raise ValueError(f"Unsupported geometric modifier: {element['name']}")
        ancestors = chain(element["uuid"])
        if not element.get("visibility", True) or not element.get("export", True) or any(not g.get("visibility", True) or not g.get("export", True) for g in ancestors):
            raise ValueError("R16 export must include every approved cube")
        from_, to = vector(element["from"]), vector(element["to"])
        if any(b <= a for a, b in zip(from_, to)): raise ValueError("R16 requires positive thickness")
        rotation = vector(element.get("rotation")); multi_axis_count += sum(bool(a) for a in rotation) > 1
        origin = vector(element.get("origin"))
        part = next((g["name"] for g in ancestors if g["name"] in PARTS), "body")
        for direction, face in element["faces"].items():
            if face.get("texture") is None or not face.get("enabled", True): continue
            index = face["texture"]
            if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < len(images):
                raise ValueError(f"Unresolved texture reference: {index}")
            image, _, tex = images[index]
            u1, v1, u2, v2 = map(float, face["uv"])
            turns = float(face.get("rotation", 0))
            if turns % 90: raise ValueError("Unsupported non-quarter-turn face UV")
            uv_rotation_count += bool(turns)
            uv_width = float(tex.get("uv_width", document["resolution"]["width"]))
            uv_height = float(tex.get("uv_height", document["resolution"]["height"]))
            if not all(math.isfinite(n) and 0 <= n <= maximum for n, maximum in zip((u1, v1, u2, v2), (uv_width, uv_height, uv_width, uv_height))):
                raise ValueError(f"UV outside atlas tile (wrapping is unsupported): {element['name']}/{direction}")
            positions, uvs = [], []
            for u, v in UV_CORNERS:
                # A face rotation moves the geometry under its fixed UV corners.
                local_u, local_v = u, v
                for _ in range(int(turns / 90) % 4): local_u, local_v = 1 - local_v, local_u
                p = face_point(direction, from_, to, local_u, local_v)
                p = rotate(p, origin, rotation)
                for group in ancestors: p = rotate(p, vector(group.get("origin")), vector(group.get("rotation")))
                positions.append(entity_point(p)); transformed_points.append(entity_point(p))
                x, y = offsets[index]
                uvs.append(((x + (u1 + (u2-u1)*u) * image.width / uv_width) / width,
                            (y + (v1 + (v2-v1)*v) * image.height / uv_height) / height))
            # Reflecting Y reverses handedness. Reverse the winding as well.
            order = (0, 3, 2, 1)
            a, b, c = (positions[i] for i in order[:3])
            ab = [b[i]-a[i] for i in range(3)]; ac = [c[i]-a[i] for i in range(3)]
            n = (ab[1]*ac[2]-ab[2]*ac[1], ab[2]*ac[0]-ab[0]*ac[2], ab[0]*ac[1]-ab[1]*ac[0])
            length = math.sqrt(sum(x*x for x in n))
            if length < 1e-12: raise ValueError(f"Degenerate face: {element['name']}/{direction}")
            normal = tuple(x/length for x in n)
            for i in order: vertices[part].extend((*positions[i], *uvs[i], *normal))
            quads += 1
    bounds = ([min(p[i] for p in transformed_points) for i in range(3)] +
              [max(p[i] for p in transformed_points) for i in range(3)])
    mesh = io.BytesIO(); mesh.write(MAGIC)
    mesh.write(struct.pack(">6f", *bounds))
    float_error = 0.0
    for part in PARTS:
        mesh.write(struct.pack(">3fI", *pivots[part], len(vertices[part]) // 8))
        data = struct.pack(f">{len(vertices[part])}f", *vertices[part]); mesh.write(data)
        restored = struct.unpack(f">{len(vertices[part])}f", data)
        float_error = max(float_error, max((abs(a-b) for a, b in zip(vertices[part], restored)), default=0))
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    target = MODEL_DIR / f"{key}.mesh"; target.write_bytes(mesh.getvalue())
    if source.read_bytes() != original: raise ValueError("Source file changed during export")
    return dict(character=key, source=source.relative_to(ROOT).as_posix(), source_sha256=digest(original),
                cubes=len(document["elements"]), quads=quads, vertices=quads*4, groups=len(groups),
                multi_axis_cubes=multi_axis_count, rotated_uv_faces=uv_rotation_count,
                textures=texture_facts, atlas_size=[width, height],
                bounds_model_blocks=bounds, pivots_model_blocks=pivots,
                part_vertices={part:len(vertices[part])//8 for part in PARTS},
                max_float32_component_error=float_error,
                resources=[dict(path=p.relative_to(ROOT).as_posix(), sha256=digest(p.read_bytes()), bytes=p.stat().st_size)
                           for p in (target, texture_file, texture_file.with_suffix(".png.mcmeta"))])


def main():
    facts = [export_character(key) for key in CHARACTERS]
    manifest = dict(format="NER16M01", stride=8, fields=["x", "y", "z", "u", "v", "nx", "ny", "nz"],
                    byte_order="big-endian", parts=PARTS, euler_order="ZYX (Rz * Ry * Rx)",
                    coordinates="x/16, (24-y)/16, z/16; reverse winding for Y reflection",
                    source_animations=0, original_sources_unchanged=True, characters=facts)
    (MODEL_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf8")
    print(json.dumps(dict(characters=len(facts), cubes=sum(f["cubes"] for f in facts),
                          quads=sum(f["quads"] for f in facts), original_textures=sum(len(f["textures"]) for f in facts),
                          source_hashes_unchanged=True, rgba_pixels_preserved=True), ensure_ascii=False))


if __name__ == "__main__":
    main()
