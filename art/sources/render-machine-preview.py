"""Render project block-model JSON as deterministic offline PNG previews.

Run from any directory with: py -3 art/sources/render-machine-preview.py
Use --inspect to report the source models without writing preview images.
The renderer uses orthographic projection, per-triangle depth buffering, and
nearest-neighbour texture sampling. It intentionally renders the authored
block geometry; lighting and perspective are not simulated.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
ASSET_ROOT = ROOT / "src/main/resources/assets/ic2newera"
BLOCK_TEXTURES = ASSET_ROOT / "textures/block"
MODEL_NAMES = (
    "terminal",
    "industrial_control_server",
    "non_metal_processor",
    "electronic_fabricator",
    "machine_network_adapter",
    "industrial_network_switch",
    "eu_motor",
)
OUTPUT_COMPARISON = ROOT / "art/preview/machine-design-comparison.png"
OUTPUT_DETAIL = ROOT / "art/preview/terminal-server-detail.png"

FACE_VERTICES = {
    "north": lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
    "south": lambda a, b: [(b[0], b[1], b[2]), (a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], b[2]), (a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], a[2]), (b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], b[1], b[2]), (a[0], b[1], b[2])],
    "down": lambda a, b: [(a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
}
FACE_NORMALS = {
    "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0),
    "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0),
}
FACE_SHADE = {"up": 1.06, "north": 1.0, "south": 0.92, "west": 0.90, "east": 0.96, "down": 0.72}
LABELS = {
    "terminal": "终端机",
    "industrial_control_server": "工业控制服务器",
    "non_metal_processor": "非金属处理机",
    "electronic_fabricator": "电子材料加工机",
    "machine_network_adapter": "机器网络适配器",
    "industrial_network_switch": "工业网络交换机",
    "eu_motor": "EU 电动机",
}


def dot(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def add(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return a[0] + b[0], a[1] + b[1], a[2] + b[2]


def sub(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return a[0] - b[0], a[1] - b[1], a[2] - b[2]


def scale3(a: tuple[float, float, float], amount: float) -> tuple[float, float, float]:
    return a[0] * amount, a[1] * amount, a[2] * amount


def cross(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def unit(a: tuple[float, float, float]) -> tuple[float, float, float]:
    length = math.sqrt(dot(a, a))
    if length == 0:
        raise ValueError("Zero-length camera or rotation vector")
    return scale3(a, 1 / length)


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for candidate in (Path("C:/Windows/Fonts/msyh.ttc"), Path("C:/Windows/Fonts/simhei.ttf")):
        try:
            return ImageFont.truetype(str(candidate), size)
        except OSError:
            pass
    return ImageFont.load_default()


def resolve_texture(model: dict[str, Any], ref: str) -> Path:
    textures = model.get("textures", {})
    seen: set[str] = set()
    value = ref
    while value.startswith("#"):
        key = value[1:]
        if key in seen:
            raise ValueError(f"Texture alias loop at #{key}")
        seen.add(key)
        if key not in textures:
            raise ValueError(f"Missing texture key #{key}")
        value = textures[key]
    if ":" in value:
        namespace, path = value.split(":", 1)
        if namespace != "ic2newera":
            raise ValueError(f"Unexpected external texture {value!r}")
    else:
        path = value
    path = path.removeprefix("textures/").removeprefix("block/")
    candidate = (BLOCK_TEXTURES / f"{path}.png").resolve()
    if not candidate.is_relative_to(BLOCK_TEXTURES.resolve()):
        raise ValueError(f"Texture path escapes block textures: {value!r}")
    if not candidate.is_file():
        raise FileNotFoundError(candidate)
    return candidate


def face_uv_default(face: str, p: tuple[float, float, float]) -> tuple[float, float]:
    x, y, z = p
    return {
        "north": (x, 16 - y), "south": (16 - x, 16 - y),
        "west": (z, 16 - y), "east": (16 - z, 16 - y),
        "up": (x, z), "down": (x, 16 - z),
    }[face]


def face_uvs(face: str, face_data: dict[str, Any], box_from: tuple[float, float, float],
             box_to: tuple[float, float, float], vertices: list[tuple[float, float, float]]) -> list[tuple[float, float]]:
    explicit = face_data.get("uv")
    defaults = [face_uv_default(face, p) for p in FACE_VERTICES[face](box_from, box_to)]
    if explicit is None:
        return defaults
    u0, v0, u1, v1 = (float(n) for n in explicit)
    du0, du1 = min(p[0] for p in defaults), max(p[0] for p in defaults)
    dv0, dv1 = min(p[1] for p in defaults), max(p[1] for p in defaults)
    result = []
    for p in vertices:
        du, dv = face_uv_default(face, p)
        fu = 0 if du1 == du0 else (du - du0) / (du1 - du0)
        fv = 0 if dv1 == dv0 else (dv - dv0) / (dv1 - dv0)
        # Minecraft model UV coordinates are in a nominal 16x16 space.
        # Sampling later scales these coordinates to the image's real width/height.
        result.append((u0 + (u1 - u0) * fu, v0 + (v1 - v0) * fv))
    return result


def rotated(point: tuple[float, float, float], rotation: dict[str, Any] | None) -> tuple[float, float, float]:
    if not rotation:
        return point
    axis = str(rotation.get("axis", "y"))
    angle = math.radians(float(rotation.get("angle", 0)))
    origin = tuple(float(n) for n in rotation.get("origin", (8, 8, 8)))
    x, y, z = sub(point, origin)
    c, s = math.cos(angle), math.sin(angle)
    if axis == "x":
        out = (x, y * c - z * s, y * s + z * c)
    elif axis == "y":
        out = (x * c + z * s, y, -x * s + z * c)
    elif axis == "z":
        out = (x * c - y * s, x * s + y * c, z)
    else:
        raise ValueError(f"Unsupported element rotation axis {axis!r}")
    return add(origin, out)


def rotated_normal(normal: tuple[float, float, float], rotation: dict[str, Any] | None) -> tuple[float, float, float]:
    if not rotation:
        return normal
    rotated_end = rotated(add((8, 8, 8), normal), {**rotation, "origin": (8, 8, 8)})
    rotated_start = rotated((8, 8, 8), {**rotation, "origin": (8, 8, 8)})
    return unit(sub(rotated_end, rotated_start))


class Camera:
    def __init__(self, direction: tuple[float, float, float], pixels_per_block_unit: float):
        self.view = unit(direction)  # Direction from object toward the camera.
        world_up = (0.0, 1.0, 0.0)
        self.right = unit((-self.view[2], 0.0, self.view[0]))
        self.up = unit(cross(self.right, self.view))
        self.s = pixels_per_block_unit

    def project(self, p: tuple[float, float, float]) -> tuple[float, float, float]:
        return dot(p, self.right) * self.s, -dot(p, self.up) * self.s, dot(p, self.view)


def triangles_for(model: dict[str, Any], model_name: str, direction: tuple[float, float, float],
                  pixels_per_unit: float) -> tuple[list[dict[str, Any]], tuple[float, float, float, float]]:
    camera = Camera(direction, pixels_per_unit)
    triangles: list[dict[str, Any]] = []
    projected_bounds: list[tuple[float, float]] = []
    textures_cache: dict[Path, Image.Image] = {}
    for element in model.get("elements", []):
        lo = tuple(float(n) for n in element["from"])
        hi = tuple(float(n) for n in element["to"])
        rotation = element.get("rotation")
        for face, data in element.get("faces", {}).items():
            normal = rotated_normal(FACE_NORMALS[face], rotation)
            if dot(normal, camera.view) <= 1e-7:
                continue
            base_vertices = FACE_VERTICES[face](lo, hi)
            vertices = [rotated(p, rotation) for p in base_vertices]
            uvs = face_uvs(face, data, lo, hi, base_vertices)
            texture_path = resolve_texture(model, data["texture"])
            if texture_path not in textures_cache:
                textures_cache[texture_path] = Image.open(texture_path).convert("RGBA")
            texture = textures_cache[texture_path]
            points = [camera.project(p) for p in vertices]
            projected_bounds.extend((p[0], p[1]) for p in points)
            tint = FACE_SHADE[face]
            for tri in ((0, 1, 2), (0, 2, 3)):
                triangles.append({
                    "p": [points[i] for i in tri],
                    "uv": [uvs[i] for i in tri],
                    "texture": texture,
                    "tint": tint,
                    "face": face,
                })
    if not projected_bounds:
        raise ValueError(f"Model {model_name} has no visible faces from camera {direction}")
    xs, ys = zip(*projected_bounds)
    return triangles, (min(xs), min(ys), max(xs), max(ys))


def edge(a: tuple[float, float], b: tuple[float, float], p: tuple[float, float]) -> float:
    return (p[0] - a[0]) * (b[1] - a[1]) - (p[1] - a[1]) * (b[0] - a[0])


def draw_triangle(canvas: Image.Image, depth_buffer: list[float], triangle: dict[str, Any],
                  origin_x: float, origin_y: float) -> None:
    points = [(p[0] + origin_x, p[1] + origin_y) for p in triangle["p"]]
    x0 = max(0, math.floor(min(p[0] for p in points)))
    x1 = min(canvas.width - 1, math.ceil(max(p[0] for p in points)))
    y0 = max(0, math.floor(min(p[1] for p in points)))
    y1 = min(canvas.height - 1, math.ceil(max(p[1] for p in points)))
    a, b, c = points
    area = edge(a, b, c)
    if abs(area) < 1e-10:
        return
    texture: Image.Image = triangle["texture"]
    tw, th = texture.size
    uv = triangle["uv"]
    z = [p[2] for p in triangle["p"]]
    src = texture.load()
    dest = canvas.load()
    tint = triangle["tint"]
    for y in range(y0, y1 + 1):
        py = y + 0.5
        for x in range(x0, x1 + 1):
            px = x + 0.5
            w0 = edge(b, c, (px, py)) / area
            w1 = edge(c, a, (px, py)) / area
            w2 = 1 - w0 - w1
            if w0 < -1e-8 or w1 < -1e-8 or w2 < -1e-8:
                continue
            depth = w0 * z[0] + w1 * z[1] + w2 * z[2]
            pos = y * canvas.width + x
            if depth <= depth_buffer[pos]:
                continue
            u = w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0]
            v = w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1]
            # Explicit/default model UV is in 0..16 units, independent of PNG dimensions.
            tx = min(tw - 1, max(0, math.floor(u * tw / 16)))
            ty = min(th - 1, max(0, math.floor(v * th / 16)))
            color = src[tx, ty]
            if color[3] < 16:
                continue
            depth_buffer[pos] = depth
            dest[x, y] = (min(255, int(color[0] * tint)), min(255, int(color[1] * tint)),
                          min(255, int(color[2] * tint)), color[3])


def render_model(model_name: str, direction: tuple[float, float, float], width: int, height: int,
                 pixels_per_unit: float) -> Image.Image:
    model_path = ASSET_ROOT / f"models/block/{model_name}.json"
    model = json.loads(model_path.read_text(encoding="utf-8"))
    triangles, bounds = triangles_for(model, model_name, direction, pixels_per_unit)
    min_x, min_y, max_x, max_y = bounds
    span_x, span_y = max_x - min_x, max_y - min_y
    if span_x > width or span_y > height:
        raise ValueError(f"{model_name} exceeds render area: {span_x:.1f}x{span_y:.1f} > {width}x{height}")
    ox = (width - span_x) / 2 - min_x
    oy = (height - span_y) / 2 - min_y
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    depth_buffer = [-math.inf] * (width * height)
    for triangle in triangles:
        draw_triangle(image, depth_buffer, triangle, ox, oy)
    return image


def panel(canvas: Image.Image, box: tuple[int, int, int, int], title: str, caption: str,
          model_name: str, direction: tuple[float, float, float], pixels_per_unit: float,
          supersample: int = 2) -> None:
    x0, y0, x1, y1 = box
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(box, radius=18, fill="#1b2935", outline="#405564", width=2)
    title_font, small_font = font(24), font(15)
    draw.text((x0 + 18, y0 + 14), title, font=title_font, fill="#e9f0f4")
    draw.text((x0 + 19, y0 + 47), caption, font=small_font, fill="#9ab0bb")
    render_w, render_h = int((x1 - x0 - 24) * supersample), int((y1 - y0 - 102) * supersample)
    rendered = render_model(model_name, direction, render_w, render_h, pixels_per_unit * supersample)
    if supersample > 1:
        rendered = rendered.resize((render_w // supersample, render_h // supersample), Image.Resampling.NEAREST)
    canvas.alpha_composite(rendered, (x0 + (x1 - x0 - rendered.width) // 2,
                                      y0 + 76 + (y1 - y0 - 102 - rendered.height) // 2))
    draw = ImageDraw.Draw(canvas)
    draw.line((x0 + 18, y1 - 25, x0 + 72, y1 - 25), fill="#48d7d0", width=3)
    draw.text((x0 + 82, y1 - 34), "JSON 几何 · 原始项目贴图", font=small_font, fill="#8198a5")


def render_comparison() -> None:
    width, height = 1900, 1150
    canvas = Image.new("RGBA", (width, height), "#101923")
    draw = ImageDraw.Draw(canvas)
    draw.text((34, 22), "IC2：新纪元 · 机器模型对照预览", font=font(34), fill="#eef4f7")
    draw.text((36, 68), "七种方块模型统一世界比例；使用正面与顶部的三分之四视角", font=font(17), fill="#95aab5")
    left, gap, top, tile_w, tile_h = 34, 20, 112, 440, 480
    positions = [(left + i * (tile_w + gap), top) for i in range(4)]
    positions += [(left + i * (tile_w + gap), top + tile_h + 18) for i in range(3)]
    north_front = (-1.0, 0.55, -1.0)
    for model_name, (x, y) in zip(MODEL_NAMES, positions):
        direction = (1.0, 0.55, 1.0) if model_name == "eu_motor" else north_front
        view_label = "南面机头 · 3/4视角" if model_name == "eu_motor" else "北面正面 · 3/4视角"
        panel(canvas, (x, y, x + tile_w, y + tile_h), LABELS[model_name], view_label,
              model_name, direction, pixels_per_unit=15.0)
    OUTPUT_COMPARISON.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(OUTPUT_COMPARISON, format="PNG", optimize=True)
    print(OUTPUT_COMPARISON)


def render_detail() -> None:
    width, height = 1900, 1040
    canvas = Image.new("RGBA", (width, height), "#101923")
    draw = ImageDraw.Draw(canvas)
    draw.text((34, 22), "终端机与工业控制服务器 · 正后结构", font=font(34), fill="#eef4f7")
    draw.text((36, 68), "四幅图使用相同尺度；端口、屏幕与天线按模型面向显示", font=font(17), fill="#95aab5")
    cards = [
        ((34, 112, 930, 560), "终端机 · 正面", "北面 · 屏幕与物料接口", "terminal", (-1, .55, -1)),
        ((966, 112, 1862, 560), "终端机 · 背面", "南面 · 网络接口与天线", "terminal", (1, .55, 1)),
        ((34, 584, 930, 1032), "工业控制服务器 · 正面", "北面 · 前置面板", "industrial_control_server", (-1, .55, -1)),
        ((966, 584, 1862, 1032), "工业控制服务器 · 背面", "南面 · 后部接口", "industrial_control_server", (1, .55, 1)),
    ]
    for box, title, caption, model, direction in cards:
        panel(canvas, box, title, caption, model, direction, pixels_per_unit=14.0, supersample=2)
    OUTPUT_DETAIL.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(OUTPUT_DETAIL, format="PNG", optimize=True)
    print(OUTPUT_DETAIL)


def inspect_sources() -> None:
    for name in MODEL_NAMES:
        path = ASSET_ROOT / f"models/block/{name}.json"
        model = json.loads(path.read_text(encoding="utf-8"))
        face_count = sum(len(element.get("faces", {})) for element in model.get("elements", []))
        rotations = sum(1 for element in model.get("elements", []) if element.get("rotation"))
        refs = sorted({face["texture"] for element in model.get("elements", [])
                       for face in element.get("faces", {}).values()})
        for ref in refs:
            tex = Image.open(resolve_texture(model, ref))
            tex.close()
        print(f"{name}: {len(model.get('elements', []))} elements, {face_count} faces, "
              f"{rotations} rotated elements, {len(refs)} texture refs")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inspect", action="store_true", help="validate/read source model geometry only")
    args = parser.parse_args()
    if args.inspect:
        inspect_sources()
        return
    render_comparison()
    render_detail()


if __name__ == "__main__":
    main()
