"""Render the terminal block model as a two-view offline PNG preview.

Run from the project root with: py -3 art/sources/render-terminal-preview.py
This renderer reads the actual block model JSON and the project's block textures.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSET_ROOT = ROOT / "src/main/resources/assets/ic2newera"
MODEL_PATH = ASSET_ROOT / "models/block/terminal.json"
OUTPUT_PATH = ROOT / "art/preview/terminal-model-preview.png"
WIDTH, HEIGHT = 1120, 650


def fonts():
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    try:
        return (
            ImageFont.truetype(str(font_path), 27),
            ImageFont.truetype(str(font_path), 17),
            ImageFont.truetype(str(font_path), 16),
        )
    except OSError:
        fallback = ImageFont.load_default()
        return fallback, fallback, fallback


def project(point, view, scale):
    x, y, z = point
    if view == "front":
        # Camera at the north-west corner: north and west faces are toward us.
        screen_x = (x - z) * math.sqrt(0.5) * scale
        screen_y = (-0.81649658 * y - 0.40824829 * (x + z)) * scale
    else:
        # Camera at the south-east corner: south and east faces are toward us.
        screen_x = (z - x) * math.sqrt(0.5) * scale
        screen_y = (-0.81649658 * y + 0.40824829 * (x + z)) * scale
    return screen_x, screen_y


def face_vertices(face, box):
    x1, y1, z1 = box["from"]
    x2, y2, z2 = box["to"]
    return {
        "north": [(x1, y2, z1), (x2, y2, z1), (x2, y1, z1), (x1, y1, z1)],
        "south": [(x2, y2, z2), (x1, y2, z2), (x1, y1, z2), (x2, y1, z2)],
        "west": [(x1, y2, z2), (x1, y2, z1), (x1, y1, z1), (x1, y1, z2)],
        "east": [(x2, y2, z1), (x2, y2, z2), (x2, y1, z2), (x2, y1, z1)],
        "up": [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)],
        "down": [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)],
    }[face]


def face_uv(face, point):
    x, y, z = point
    return {
        "north": (x, 16 - y),
        "south": (16 - x, 16 - y),
        "west": (z, 16 - y),
        "east": (16 - z, 16 - y),
        "up": (x, z),
        "down": (x, 16 - z),
    }[face]


def world_from_uv(face, u, v, box):
    x1, y1, z1 = box["from"]
    x2, y2, z2 = box["to"]
    return {
        "north": (u, 16 - v, z1),
        "south": (16 - u, 16 - v, z2),
        "west": (x1, 16 - v, u),
        "east": (x2, 16 - v, 16 - u),
        "up": (u, y2, v),
        "down": (u, y1, 16 - v),
    }[face]


def draw_face(draw, box, face, view, scale, offset, textures):
    texture_ref = box["faces"][face]["texture"]
    texture_key = texture_ref[1:] if texture_ref.startswith("#") else texture_ref
    texture_id = textures[texture_key].split(":", 1)[1]
    texture = Image.open(ASSET_ROOT / "textures" / f"{texture_id}.png").convert("RGBA")
    uvs = [face_uv(face, point) for point in face_vertices(face, box)]
    u_min = max(0, min(point[0] for point in uvs))
    u_max = min(16, max(point[0] for point in uvs))
    v_min = max(0, min(point[1] for point in uvs))
    v_max = min(16, max(point[1] for point in uvs))
    shade = {"up": 1.12, "north": 1.0, "south": 0.94, "west": 0.92, "east": 0.96, "down": 0.75}[face]

    for v in range(math.floor(v_min), math.ceil(v_max)):
        for u in range(math.floor(u_min), math.ceil(u_max)):
            source = texture.getpixel((u, v))
            if source[3] < 16:
                continue
            color = tuple(min(255, max(20, int(channel * shade))) for channel in source[:3])
            corners = [
                world_from_uv(face, u, v, box),
                world_from_uv(face, u + 1, v, box),
                world_from_uv(face, u + 1, v + 1, box),
                world_from_uv(face, u, v + 1, box),
            ]
            polygon = []
            for point in corners:
                px, py = project(point, view, scale)
                polygon.append((px + offset[0], py + offset[1]))
            draw.polygon(polygon, fill=color)


def render_panel(canvas, title_draw, boxes, textures, view, x0, label, caption, title_font, body_font):
    panel_x, panel_y, panel_width, panel_height = x0, 82, 528, 526
    title_draw.rounded_rectangle(
        (panel_x, panel_y, panel_x + panel_width, panel_y + panel_height),
        radius=22, fill="#1c2936", outline="#405464", width=2,
    )
    title_draw.text((panel_x + 22, panel_y + 18), label, font=title_font, fill="#e7edf2")
    title_draw.text((panel_x + 22, panel_y + 58), caption, font=body_font, fill="#91a9b5")

    scale = 15.3
    projected = [
        project((x, y, z), view, scale)
        for box in boxes
        for x in (box["from"][0], box["to"][0])
        for y in (box["from"][1], box["to"][1])
        for z in (box["from"][2], box["to"][2])
    ]
    min_x = min(point[0] for point in projected)
    max_x = max(point[0] for point in projected)
    min_y = min(point[1] for point in projected)
    max_y = max(point[1] for point in projected)
    offset = (
        panel_x + panel_width / 2 - (min_x + max_x) / 2,
        panel_y + 337 - (min_y + max_y) / 2,
    )

    faces = {"front": ("up", "west", "north"), "back": ("up", "east", "south")}[view]
    draw_order = list(boxes)
    if view == "front":
        draw_order.reverse()  # Hide the rear antenna behind the casing from the front.
    draw = ImageDraw.Draw(canvas)
    for box in draw_order:
        for face in faces:
            draw_face(draw, box, face, view, scale, offset, textures)

    title_draw.line((panel_x + 26, panel_y + panel_height - 26, panel_x + 88, panel_y + panel_height - 26), fill="#45d7d0", width=3)
    title_draw.text((panel_x + 98, panel_y + panel_height - 38), "IC2 NEW ERA  /  TERMINAL", font=body_font, fill="#8098a6")


def main():
    model = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
    canvas = Image.new("RGB", (WIDTH, HEIGHT), "#111a24")
    draw = ImageDraw.Draw(canvas)
    title_font, body_font, small_font = fonts()
    draw.text((30, 22), "终端机 · 模型离线预览", font=title_font, fill="#eef4f7")
    draw.text((WIDTH - 252, 30), "JSON 几何 + 项目内 16×16 贴图", font=small_font, fill="#8398a4")
    render_panel(canvas, draw, model["elements"], model["textures"], "front", 24,
                 "正面 · 左前上方", "青色状态屏  /  双物料端口", title_font, body_font)
    render_panel(canvas, draw, model["elements"], model["textures"], "back", 568,
                 "背面 · 右后上方", "紫色光纤端口  /  内置无线天线", title_font, body_font)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUTPUT_PATH, format="PNG", optimize=True)
    print(OUTPUT_PATH)


if __name__ == "__main__":
    main()
