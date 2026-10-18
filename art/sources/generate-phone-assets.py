"""Generate the original phone model, texture, and resource preview.

Run with: py -3 art/sources/generate-phone-assets.py
Only the industrial_phone item JSON/PNG and its dedicated preview are written.
"""
from __future__ import annotations

import json
import math
import runpy
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"
MODEL_PATH = ASSETS / "models/item/industrial_phone.json"
TEXTURE_PATH = ASSETS / "textures/item/industrial_phone.png"
PREVIEW_PATH = ROOT / "art/preview/phone-model-preview.png"


def rgb(value: str) -> tuple[int, int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def make_texture() -> Image.Image:
    """Build separated 56x96 panels and isolated 8x8 swatches in 128 atlas."""
    image = Image.new("RGBA", (128, 128), rgb("#080E15"))
    d = ImageDraw.Draw(image)
    # Front panel occupies x=0..55, y=0..95. No swatches overlap its UV.
    for y in range(96):
        t = y / 95
        col = tuple(round(a * (1 - t) + b * t) for a, b in zip((7, 16, 28), (31, 79, 112))) + (255,)
        d.line((0, y, 55, y), fill=col)
    d.rounded_rectangle((0, 0, 55, 95), radius=10, fill=rgb("#080E15"))
    for y in range(96):
        t = y / 95
        col = tuple(round(a * (1 - t) + b * t) for a, b in zip((8, 18, 30), (26, 55, 76))) + (255,)
        d.line((4, y, 51, y), fill=col)
    # Large, soft forms sit fully inside the screen without icon/text strokes.
    d.ellipse((-24, 52, 49, 125), fill=rgb("#1F6D8A"))
    d.ellipse((1, 57, 55, 111), fill=rgb("#3197A7"))
    d.ellipse((10, 64, 48, 102), fill=rgb("#183D62"))
    # Rear panel occupies x=64..119, y=0..95 and stays plain matte ceramic.
    for y in range(96):
        t = y / 95
        col = tuple(round(a * (1 - t) + b * t) for a, b in zip((91, 108, 116), (65, 79, 87))) + (255,)
        d.line((64, y, 119, y), fill=col)
    # Every swatch occupies its own full UV unit below the panel boundary.
    d.rectangle((0, 104, 7, 111), fill=rgb("#B8C7CC"))       # titanium
    d.rectangle((8, 104, 15, 111), fill=rgb("#080E15"))     # black glass
    d.rectangle((16, 104, 23, 111), fill=rgb("#202B34"))    # camera island
    d.rectangle((24, 104, 31, 111), fill=rgb("#D9D5C2"))    # flash
    d.rectangle((32, 104, 39, 111), fill=rgb("#202B34"))
    d.ellipse((32, 104, 39, 111), fill=rgb("#14232E"), outline=rgb("#B5E7EE"), width=1)
    d.ellipse((34, 106, 37, 109), fill=rgb("#2C8FA9"), outline=rgb("#73D9E8"), width=1)
    d.point((35, 107), fill=rgb("#D1F7F4"))
    return image


def box(lo: tuple[float, float, float], hi: tuple[float, float, float],
        textures: dict[str, str], uv: dict[str, list[float]] | None = None) -> dict:
    faces = {}
    swatches = {"#metal": [0, 13, 1, 14], "#black": [1, 13, 2, 14],
                "#camera": [2, 13, 3, 14], "#flash": [3, 13, 4, 14],
                "#lens": [4, 13, 5, 14], "#front": [0, 0, 7, 12],
                "#back": [8, 0, 15, 12]}
    for face in ("north", "south", "east", "west", "up", "down"):
        key = textures.get(face, "#metal")
        item = {"texture": key, "uv": uv[face] if uv and face in uv else swatches.get(key, [7, 15, 8, 16])}
        faces[face] = item
    return {"from": list(lo), "to": list(hi), "faces": faces}


def build_model() -> dict:
    metal = {face: "#metal" for face in ("north", "south", "east", "west", "up", "down")}
    elements = [
        # Thin metal chassis, fully enclosed by two glass panels.
        box((3.25, 0.25, 7.22), (12.75, 15.75, 8.78), metal),
        box((3.62, 0.62, 7.17), (12.38, 15.38, 7.23),
            {"north": "#front", "south": "#metal", "east": "#metal", "west": "#metal", "up": "#metal", "down": "#metal"}),
        box((3.62, 0.62, 8.77), (12.38, 15.38, 8.83),
            {"north": "#metal", "south": "#back", "east": "#metal", "west": "#metal", "up": "#metal", "down": "#metal"}),
        # Centered black sensor island on the front face.
        box((6.35, 14.55, 7.11), (9.65, 15.02, 7.17), {f: "#black" for f in metal}),
        # Raised rear camera island and three individual convex lens units.
        box((4.0, 12.0, 8.83), (8.35, 15.25, 9.05), {f: "#camera" for f in metal}),
        box((4.38, 14.05, 9.05), (5.22, 14.89, 9.19), {**metal, "south": "#lens"}),
        box((5.66, 14.05, 9.05), (6.50, 14.89, 9.19), {**metal, "south": "#lens"}),
        box((4.38, 12.77, 9.05), (5.22, 13.61, 9.19), {**metal, "south": "#lens"}),
        box((7.15, 14.35, 9.05), (7.70, 14.90, 9.13), {**metal, "south": "#flash"}),
        # Slim power/lock and two volume keys. Their entire surfaces map to
        # the isolated titanium swatch instead of inheriting full-image UVs.
        box((12.72, 11.5, 7.48), (13.05, 13.42, 8.20), metal),
        box((2.95, 10.45, 7.50), (3.28, 11.72, 8.15), metal),
        box((2.95, 8.25, 7.50), (3.28, 9.65, 8.15), metal),
    ]
    return {
        # Do not inherit minecraft:item/generated: 1.19.2 turns that marker
        # into a generated layer model and drops custom elements.
        "gui_light": "front",
        "textures": {"particle": "ic2newera:item/industrial_phone", "front": "ic2newera:item/industrial_phone", "back": "ic2newera:item/industrial_phone", "metal": "ic2newera:item/industrial_phone", "black": "ic2newera:item/industrial_phone", "camera": "ic2newera:item/industrial_phone", "lens": "ic2newera:item/industrial_phone", "flash": "ic2newera:item/industrial_phone"},
        "display": {
            "gui": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
            "firstperson_righthand": {"rotation": [0, 90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
        },
        "elements": elements,
    }


def render_preview() -> None:
    # Reuse the project's actual JSON cuboid/UV/depth renderer, redirecting
    # only its texture lookup to this item texture. No concept geometry is drawn.
    renderer = runpy.run_path(str(ROOT / "art/sources/render-machine-preview.py"))
    def resolve_item_texture(model_data: dict, ref: str) -> Path:
        value = ref
        seen: set[str] = set()
        while value.startswith("#"):
            key = value[1:]
            if key in seen or key not in model_data.get("textures", {}):
                raise ValueError(f"Invalid phone texture alias {value}")
            seen.add(key)
            value = model_data["textures"][key]
        namespace, separator, path = value.partition(":")
        if separator and namespace != "ic2newera":
            raise ValueError(f"Unexpected external phone texture {value}")
        if path.startswith("item/"):
            path = path[5:]
        candidate = (ASSETS / "textures/item" / f"{path}.png").resolve()
        if not candidate.is_relative_to((ASSETS / "textures/item").resolve()) or not candidate.is_file():
            raise FileNotFoundError(candidate)
        return candidate
    renderer["triangles_for"].__globals__["resolve_texture"] = resolve_item_texture
    model = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
    directions = {"front": (0.15, 0.22, -1), "back": (0.15, 0.22, 1), "side": (1, 0.08, -0.16)}

    def actual_model_view(direction: tuple[float, float, float], size: tuple[int, int], scale: float) -> Image.Image:
        triangles, bounds = renderer["triangles_for"](model, "industrial_phone", direction, scale)
        w, h = size
        min_x, min_y, max_x, max_y = bounds
        span_x, span_y = max_x - min_x, max_y - min_y
        image = Image.new("RGBA", size, (0, 0, 0, 0))
        zbuf = [-math.inf] * (w * h)
        ox, oy = (w - span_x) / 2 - min_x, (h - span_y) / 2 - min_y
        for tri in triangles:
            renderer["draw_triangle"](image, zbuf, tri, ox, oy)
        return image

    canvas = Image.new("RGBA", (1480, 820), "#101923")
    draw = ImageDraw.Draw(canvas)
    font = renderer["font"]
    draw.text((34, 22), "手机 · 实际 Minecraft 物品模型预览", font=font(32), fill="#eef4f7")
    draw.text((36, 68), "读取当前 industrial_phone.json 与 128×128 PNG；隔离UV、几何与遮挡按 JSON 渲染", font=font(17), fill="#9ab0bb")
    cards = [((30, 112, 510, 700), "正面 · 黑边冷色玻璃屏", directions["front"]),
             ((520, 112, 1000, 700), "背面 · 三镜头哑光机背", directions["back"]),
             ((1010, 112, 1450, 700), "侧面 · 金属机身与实体侧键", directions["side"])]
    for bounds, title, direction in cards:
        x0, y0, x1, y1 = bounds
        draw.rounded_rectangle(bounds, radius=18, fill="#1b2935", outline="#405564", width=2)
        draw.text((x0 + 18, y0 + 14), title, font=font(21), fill="#e9f0f4")
        view = actual_model_view(direction, (int((x1 - x0 - 40) * 2), int((y1 - y0 - 110) * 2)), 32)
        view = view.resize((view.width // 2, view.height // 2), Image.Resampling.NEAREST)
        canvas.alpha_composite(view, (x0 + (x1 - x0 - view.width) // 2, y0 + 72 + (y1 - y0 - 110 - view.height) // 2))
        draw = ImageDraw.Draw(canvas)
        draw.text((x0 + 18, y1 - 55), "1 方块单位网格；外廓约 9.5 × 16", font=font(14), fill="#9ab0bb")
    # Explicit Minecraft 1.19.2 item display transforms preserve the vanilla
    # item scale while this remains an independent elements model.
    draw.rounded_rectangle((30, 720, 1450, 798), radius=14, fill="#17232d", outline="#344a58", width=1)
    draw.text((50, 736), "持有比例参考：显式保留1.19.2原版物品display比例；独立elements模型，完整落在0..16范围内。", font=font(18), fill="#c7d6dd")
    draw.text((50, 768), "原创手机造型；不复刻具体品牌、商标或受保护产品细节。游戏内光照和手持实机比例仍需验收。", font=font(14), fill="#8ca2ad")
    PREVIEW_PATH.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(PREVIEW_PATH, format="PNG", optimize=True)


def main() -> None:
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    TEXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    make_texture().save(TEXTURE_PATH, format="PNG", optimize=False)
    MODEL_PATH.write_text(json.dumps(build_model(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    render_preview()
    print(f"Generated {MODEL_PATH}")
    print(f"Generated {TEXTURE_PATH}")
    print(f"Rendered {PREVIEW_PATH}")


if __name__ == "__main__":
    main()
