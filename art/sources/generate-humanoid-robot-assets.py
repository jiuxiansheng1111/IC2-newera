"""Generate eight original articulated humanoid robot models and resource previews.

Run from any directory with: py -3 art/sources/generate-humanoid-robot-assets.py
The previews are rasterized from the generated JSON geometry and PNG textures.
"""
from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / "src/main/resources/assets/ic2newera"
MODEL_DIR = ASSET / "robot_models"
TEXTURE_DIR = ASSET / "textures/entity/robot"
PREVIEW_DIR = ROOT / "art/preview"
CATALOG_PATH = ROOT / "art/robot-model-catalog.json"
SCALE = 4
LOGICAL_TEXTURE = 256
TEXTURE_PIXELS = LOGICAL_TEXTURE * SCALE
EDITABLE_ROBOTS = ("anon", "soyo", "tomori", "taki", "rana")

# Each palette is deliberately separated by silhouette, hair material and outfit
# contrast. Common robot mechanisms keep the shared characters visibly mechanical.
COMMON = {
    "skin": "#F0D9CD", "skin_shadow": "#CDA99A", "joint": "#54636A",
    "joint_dark": "#26343B", "silver": "#BCC8C8", "lamp": "#62E6D2",
    "gold": "#D4AA50", "black": "#20262D", "white": "#F0F0E7",
    "fishnet": "#E4CEC4", "eye_outline": "#494454", "lip": "#B87579",
}

ROBOTS: dict[str, dict[str, Any]] = {
    "tomori": {
        "name": "高松灯", "label": "tomori · 高松灯", "hair": "#777482",
        "hair_light": "#AAA3B5", "cloth": "#4F83AA", "accent": "#E4E9E9",
        "skirt": "#637149", "skirt_light": "#8D9865", "sock": "#343A43",
        "eyes": ("#AA7272", "#76535C"), "hairstyle": "round_bob",
        "dress": "green_pleats", "description": "短灰紫圆波波头、蓝身白袖棒球外套、黄腰带、灰绿高腰短裤与米白运动鞋。",
    },
    "anon": {
        "name": "千早爱音", "label": "anon · 千早爱音", "hair": "#D77F9A",
        "hair_light": "#F1B1C3", "cloth": "#397DAD", "accent": "#7CC4DB",
        "skirt": "#ECEDE8", "skirt_light": "#D5D8D1", "sock": "#596B83",
        "eyes": ("#789DB7", "#5F829D"), "hairstyle": "long_side_swept",
        "dress": "asym_white_skirt", "description": "粉色侧分长发、黄领蓝短袖露腰上衣、前短后长多层白裙与黑踝靴。",
    },
    "rana": {
        "name": "要乐奈", "label": "rana · 要乐奈", "hair": "#AEB5B4",
        "hair_light": "#E0E4DD", "cloth": "#4385B8", "accent": "#E9F1E9",
        "skirt": "#202831", "skirt_light": "#46525C", "sock": "#252C36",
        "eyes": ("#56A9E0", "#E7BC4B"), "hairstyle": "choppy_silver_bob",
        "dress": "asym_jacket_shorts", "description": "银色碎短波波头、白短袖内搭、蓝色不对称单肩长外套、黑短裤、单侧网袜与黑长靴。",
    },
    "soyo": {
        "name": "长崎爽世", "label": "soyo · 长崎爽世", "hair": "#A88461",
        "hair_light": "#D0AE83", "cloth": "#527B9E", "accent": "#E5E7E0",
        "skirt": "#718DA3", "skirt_light": "#D4E0E2", "sock": "#475562",
        "eyes": ("#718BA8", "#829BB4"), "hairstyle": "soft_wave_long",
        "dress": "blue_white_panel", "description": "浅棕长波浪发、蓝色荷叶短袖与金边前短后长白裙、黑踝靴。",
    },
    "taki": {
        "name": "椎名立希", "label": "taki · 椎名立希", "hair": "#39344D",
        "hair_light": "#6C6285", "cloth": "#334B67", "accent": "#D5DDE0",
        "skirt": "#E6E8E3", "skirt_light": "#B9C8D1", "sock": "#292D3D",
        "eyes": ("#985C9A", "#C16DA6"), "hairstyle": "straight_violet_long",
        "dress": "sleeveless_blue_white", "description": "深紫长直发齐刘海、白泡袖上衣、蓝色束腰裙与白褶边、裸腿黑踝靴。",
    },
    "sakiko": {
        "name": "丰川祥子", "label": "sakiko · 丰川祥子", "hair": "#8794B5",
        "hair_light": "#BBC9D9", "cloth": "#A7B8C7", "accent": "#D8E0E5",
        "skirt": "#65775A", "skirt_light": "#8F9E73", "sock": "#E7E8E4",
        "eyes": ("#91B9DC", "#A7CDE4"), "hairstyle": "pale_blue_twin_curls",
        "dress": "gray_blue_uniform_green_pleats", "description": "蓝紫双卷长发、浅灰蓝长袖制服外套、白内搭、绿格百褶短裙、白袜与深色便鞋。",
    },
    "uika": {
        "name": "三角初华", "label": "uika · 三角初华", "hair": "#D5B254",
        "hair_light": "#F2DE91", "cloth": "#24252D", "accent": "#D8B95B",
        "skirt": "#24252D", "skirt_light": "#D7BA64", "sock": "#252831",
        "eyes": ("#A5BFD0", "#B4CAD5"), "hairstyle": "gold_straight_shoulder",
        "dress": "black_gold_column", "description": "及肩金色直发、黑色长裙与金色饰边。",
    },
    "umiri": {
        "name": "八幡海铃", "label": "umiri · 八幡海铃", "hair": "#464950",
        "hair_light": "#858990", "cloth": "#612F36", "accent": "#A34D50",
        "skirt": "#252930", "skirt_light": "#65676D", "sock": "#2C3038",
        "eyes": ("#A7AFB5", "#9DAAB1"), "hairstyle": "flipped_gray_shoulder",
        "dress": "red_shawl_black", "description": "灰黑外翻及肩发、暗红披肩、黑裙与机械短靴。",
    },
}


def rgb(hex_color: str) -> tuple[int, int, int, int]:
    value = hex_color.lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255


def quantize(value: float) -> float:
    """Keep edited cube edges on quarter units so SCALE=4 maps UVs exactly."""
    result = round(float(value) * SCALE) / SCALE
    return int(result) if result.is_integer() else result


def part(pivot: tuple[float, float, float] = (0, 0, 0), rotation: tuple[float, float, float] | None = None) -> dict:
    result: dict[str, Any] = {"pivot": list(pivot), "cubes": [], "children": {}}
    if rotation and any(rotation):
        result["rotation"] = list(rotation)
    return result


def box(target: dict, origin: tuple[float, float, float], size: tuple[float, float, float],
        material: str, pattern: str = "plain", mirror: bool = False) -> dict:
    origin = tuple(quantize(n) for n in origin)
    # Avoid degenerate one-dimensional cubes; even tiny trim has a real depth.
    size = tuple(max(.25, quantize(n)) for n in size)
    cube = {"origin": list(origin), "size": list(size), "uv": [0, 0], "_material": material,
            "_pattern": pattern}
    if mirror:
        cube["mirror"] = True
    target["cubes"].append(cube)
    return cube


def rotated_box(target: dict, name: str, origin: tuple[float, float, float],
                size: tuple[float, float, float], material: str, pattern: str = "plain",
                rotation: tuple[float, float, float] = (0, 0, 0)) -> dict:
    pivot = tuple(origin[i] + size[i] / 2 for i in range(3))
    child = part(pivot, rotation)
    box(child, tuple(-v / 2 for v in size), size, material, pattern)
    target["children"][name] = child
    return child


def pack_uv(root_parts: list[dict]) -> None:
    cubes: list[dict] = []
    def visit(node: dict) -> None:
        cubes.extend(node["cubes"])
        for child in node["children"].values():
            visit(child)
    for root_part in root_parts:
        visit(root_part)
    rectangles = []
    for cube in cubes:
        w, h, d = cube["size"]
        # Minecraft's CubeListBuilder uses the original float values. The atlas
        # coordinates below are physical texels; four texels represent one UV unit.
        uw = round((2 * d + 2 * w) * SCALE)
        uh = round((h + d) * SCALE)
        if uw > TEXTURE_PIXELS or uh > TEXTURE_PIXELS:
            raise ValueError(f"Cube UV rectangle {uw}x{uh} exceeds 512x512")
        rectangles.append((cube, uw, uh))
    rectangles.sort(key=lambda item: (item[2], item[1]), reverse=True)
    x = y = row_h = 0
    for cube, uw, uh in rectangles:
        if x + uw > TEXTURE_PIXELS:
            x, y, row_h = 0, y + row_h, 0
        if y + uh > TEXTURE_PIXELS:
            raise ValueError(f"UV atlas overflow at {y + uh}/512")
        # texOffs in the 1.19.2 model builder uses integer UV origins.
        # Shelf dimensions are quarter-unit exact, while each next rectangle
        # begins at an integer logical UV coordinate.
        cube["uv"] = [x // SCALE, y // SCALE]
        cube["_uv_px"] = [x, y, uw, uh]
        x += ((uw + SCALE - 1) // SCALE + 1) * SCALE
        row_h = max(row_h, ((uh + SCALE - 1) // SCALE + 1) * SCALE)


def clean_part(node: dict) -> dict:
    result = {key: node[key] for key in ("pivot", "rotation", "cubes") if key in node}
    result["cubes"] = [{key: cube[key] for key in ("origin", "size", "uv", "mirror") if key in cube}
                        for cube in node["cubes"]]
    if node["children"]:
        result["children"] = {name: clean_part(child) for name, child in node["children"].items()}
    return result


def add_head(head: dict, robot: dict) -> None:
    box(head, (-4, -8, -4), (8, 8, 8), "skin", "face")
    hair = robot["hair_style"]
    # Crown shell shared as a base; the side/back silhouettes below are character-specific.
    box(head, (-4.5, -9, -4.5), (9, 2.4, 9), "hair", "hair")
    if hair == "round_bob":
        box(head, (-4.7, -6.3, 1.7), (9.4, 5.2, 3.0), "hair", "hair")
        box(head, (-4.7, -5.6, -4.6), (1.8, 5.2, 8.2), "hair", "hair")
        box(head, (2.9, -5.6, -4.6), (1.8, 5.2, 8.2), "hair", "hair")
        box(head, (-4.0, -7.1, -4.8), (3.3, 2.2, 1.8), "hair", "hair")
        box(head, (0.7, -7.4, -4.8), (3.3, 2.5, 1.8), "hair_light", "hair")
    elif hair == "long_side_swept":
        box(head, (-5.2, -5.3, -4.3), (2.0, 10.7, 8.4), "hair", "hair")
        box(head, (3.2, -3.7, -4.3), (2.0, 7.3, 8.4), "hair", "hair")
        box(head, (-4.5, -6.2, -4.8), (5.9, 2.0, 2.1), "hair_light", "hair")
        rotated_box(head, "long_side_swoop", (0.3, -7.6, -5.2), (4.8, 2.0, 1.7),
                    "hair", "hair", (0, 0, -0.30))
    elif hair == "choppy_silver_bob":
        box(head, (-4.7, -5.4, 1.3), (9.4, 4.5, 3.0), "hair", "hair")
        box(head, (-4.8, -5.1, -4.5), (1.8, 4.2, 8.0), "hair", "hair")
        box(head, (3.0, -5.0, -4.5), (1.8, 4.0, 8.0), "hair", "hair")
        for i, (x, w) in enumerate(((-4.2, 2.4), (-1.6, 2.4), (1.0, 2.8))):
            rotated_box(head, f"choppy_fringe_{i}", (x, -7.8 + (i % 2) * .5, -4.9),
                        (w, 2.2, 1.3), "hair_light" if i == 1 else "hair", "hair",
                        (0, 0, (-1 if i == 0 else 1) * .11))
    elif hair == "soft_wave_long":
        box(head, (-5, -5.2, 1.5), (10, 10, 3.3), "hair", "hair")
        for side, suffix in ((-1, "l"), (1, "r")):
            x = -5.2 if side < 0 else 3.2
            box(head, (x, -5.7, -4.5), (2.0, 7.1, 8.5), "hair", "hair")
            rotated_box(head, f"wave_outer_{suffix}", (x + (1.1 if side < 0 else -1.0), .1, -3.9),
                        (2.1, 5.0, 4.5), "hair_light", "hair", (0, 0, side * .17))
            box(head, (x, -7.1, -4.9), (2.0, 2.1, 1.4), "hair_light", "hair")
    elif hair == "straight_violet_long":
        box(head, (-5, -5.2, 1.6), (10, 14, 3.2), "hair", "hair")
        box(head, (-5.1, -5.6, -4.4), (2.0, 12.0, 8.2), "hair", "hair")
        box(head, (3.1, -5.6, -4.4), (2.0, 12.0, 8.2), "hair", "hair")
        box(head, (-4.6, -6.8, -4.8), (9.2, 2.2, 1.5), "hair_light", "hair")
    elif hair == "pale_blue_twin_curls":
        box(head, (-4.8, -5.1, 1.6), (9.6, 6.7, 3.4), "hair", "hair")
        box(head, (-4.7, -6.3, -4.8), (9.4, 2.0, 1.6), "hair_light", "hair")
        for side, suffix in ((-1, "l"), (1, "r")):
            x = -5.4 if side < 0 else 3.4
            rotated_box(head, f"twin_curl_upper_{suffix}", (x, -4.2, -1.3), (2.3, 5.1, 2.4),
                        "hair", "hair", (0, 0, side * .22))
            rotated_box(head, f"twin_curl_mid_{suffix}", (x + side * .7, -.8, -1.2), (2.2, 4.4, 2.3),
                        "hair_light", "hair", (0, 0, -side * .25))
            rotated_box(head, f"twin_curl_end_{suffix}", (x + side * .1, 2.0, -1.1), (2.5, 3.1, 2.5),
                        "hair", "hair", (0, 0, side * .2))
    elif hair == "gold_straight_shoulder":
        box(head, (-4.8, -5.0, 1.4), (9.6, 8.5, 3.4), "hair", "hair")
        box(head, (-5.0, -5.2, -4.6), (2.1, 8.2, 8.2), "hair", "hair")
        box(head, (2.9, -5.2, -4.6), (2.1, 8.2, 8.2), "hair", "hair")
        box(head, (-4.5, -6.9, -4.8), (4.8, 2.0, 1.6), "hair_light", "hair")
        box(head, (.5, -7.4, -4.8), (4.0, 2.2, 1.6), "hair", "hair")
    elif hair == "flipped_gray_shoulder":
        box(head, (-4.8, -5.1, 1.5), (9.6, 8.4, 3.2), "hair", "hair")
        box(head, (-5.0, -5.2, -4.5), (2.0, 7.2, 8.0), "hair", "hair")
        box(head, (3.0, -5.2, -4.5), (2.0, 7.2, 8.0), "hair", "hair")
        rotated_box(head, "flip_out_l", (-5.3, 1.0, -4.1), (3.3, 2.0, 5.6), "hair_light", "hair", (0, 0, -.4))
        rotated_box(head, "flip_out_r", (2.0, 1.0, -4.1), (3.3, 2.0, 5.6), "hair", "hair", (0, 0, .4))


def add_body_style(body: dict, robot: dict, identifier: str) -> None:
    # Clothing is built as layered volumes over a narrow mechanical torso.
    box(body, (-2.65, .2, -1.8), (5.3, 11.5, 3.6), "skin_shadow", "plain")
    if identifier == "anon":
        box(body, (-3.1, .45, -2.45), (6.2, 4.8, 4.9), "cloth", "fabric")
        box(body, (-2.5, .0, -2.74), (5.0, .5, .35), "gold", "trim")
        box(body, (-2.8, .42, -2.76), (5.6, .55, .35), "white", "trim")
        box(body, (-3.0, 5.25, -2.45), (6.0, 1.5, 4.6), "skin", "plain")
        box(body, (-3.4, 6.65, -2.75), (6.8, .55, 5.4), "gold", "trim")
        box(body, (-3.75, 7.15, -2.65), (7.5, 2.7, 5.25), "white", "fabric")
        box(body, (-4.0, 8.0, -3.08), (8.0, 2.05, .38), "skirt_light", "panel")
        box(body, (-4.2, 8.25, 2.0), (8.4, 9.5, .72), "white", "fabric")
        box(body, (-4.45, 9.3, 2.35), (8.9, 8.4, .72), "skirt_light", "panel")
        box(body, (-4.45, 8.3, -1.2), (.42, 8.0, 3.5), "white", "fabric")
        box(body, (4.03, 8.3, -1.2), (.42, 8.0, 3.5), "white", "fabric")
        for x in (-2.7, -1.2, .3, 1.8): box(body,(x,7.45,-3.0),(.14,2.3,.12),"white","trim")
    elif identifier == "soyo":
        box(body, (-3.0, .05, -2.45), (6.0, 5.75, 4.9), "cloth", "fabric")
        box(body, (-2.0, .0, -2.72), (4.0, .5, .34), "white", "trim")
        box(body, (-1.8, 4.6, -2.74), (3.6, .85, .38), "white", "panel")
        box(body, (-3.15, 5.65, -2.7), (6.3, .45, 5.2), "gold", "trim")
        box(body, (-3.5, 6.1, -2.65), (7.0, 2.5, 5.3), "accent", "fabric")
        box(body, (-3.9, 8.25, -3.12), (7.8, 2.1, 5.65), "white", "panel")
        box(body, (-4.25, 8.85, -3.45), (8.5, 1.65, .55), "white", "trim")
        box(body, (-4.5, 8.1, 2.1), (9.0, 9.6, .68), "white", "fabric")
        box(body, (-4.75, 9.25, 2.42), (9.5, 8.3, .65), "accent", "panel")
        box(body, (-4.65, 8.2, -1.15), (.42, 8.0, 3.5), "white", "fabric")
        box(body, (4.23, 8.2, -1.15), (.42, 8.0, 3.5), "white", "fabric")
        box(body, (-4.0, 6.15, -3.0), (8.0, .22, .16), "gold", "trim")
    elif identifier == "tomori":
        box(body, (-2.85, .15, -2.35), (5.7, 6.7, 4.7), "cloth", "fabric")
        box(body, (-3.2, .1, -2.7), (2.0, 6.7, .38), "cloth", "panel")
        box(body, (1.2, .1, -2.7), (2.0, 6.7, .38), "cloth", "panel")
        box(body, (-1.0, .2, -2.75), (2.0, 6.4, .42), "white", "fabric")
        box(body, (-1.0, 5.9, -2.92), (2.0, .35, .22), "gold", "trim")
        box(body, (-3.0, 6.4, -2.65), (6.0, .65, 5.1), "gold", "trim")
        box(body, (-3.1, 7.0, -2.6), (6.2, 3.35, 5.1), "skirt", "fabric")
        box(body, (-3.12, 7.25, -2.91), (3.0, 3.1, .35), "skirt_light", "panel")
        box(body, (.12, 7.25, -2.91), (3.0, 3.1, .35), "skirt_light", "panel")
        box(body, (-.18, 8.1, -3.0), (.36, 1.8, .18), "joint_dark", "metal")
        box(body, (-3.3, 6.2, -2.85), (6.6, .38, .25), "cloth", "trim")
    elif identifier == "taki":
        box(body, (-3.0, .0, -2.4), (6.0, 3.1, 4.8), "white", "fabric")
        box(body, (-1.8, .0, -2.72), (3.6, .68, .35), "white", "trim")
        box(body, (-2.75, 2.25, -2.74), (5.5, 4.4, .4), "cloth", "fabric")
        box(body, (-.38, 2.3, -2.98), (.76, 4.4, .2), "black", "trim")
        box(body, (-3.05, 6.45, -2.86), (6.1, .55, .4), "black", "trim")
        box(body, (-3.45, 7.0, -2.75), (6.9, 3.2, 5.5), "cloth", "fabric")
        box(body, (-3.85, 9.5, -3.02), (7.7, 1.45, 5.75), "cloth", "panel")
        box(body, (-4.0, 10.35, -3.2), (8.0, 1.15, 6.0), "white", "trim")
        box(body, (-4.2, 11.05, -3.32), (8.4, .72, 6.25), "white", "fabric")
    elif identifier == "rana":
        box(body, (-2.9, .1, -2.42), (5.8, 6.05, 4.85), "white", "fabric")
        box(body, (-2.7, .1, -2.72), (3.8, .45, .32), "white", "trim")
        box(body, (-3.0, 5.8, -2.75), (6.0, .38, .35), "gold", "trim")
        box(body, (-3.05, 6.2, -2.55), (6.1, .65, 5.0), "black", "metal")
        box(body, (-3.1, 6.85, -2.55), (6.2, 3.7, 5.0), "black", "fabric")
        # Long asymmetric coat is a real side volume, with a lapel and hem.
        box(body, (-4.15, -.1, -2.92), (2.45, 11.9, .72), "cloth", "fabric")
        box(body, (-4.32, .25, -3.32), (.38, 11.0, .22), "accent", "trim")
        box(body, (-4.22, 10.9, -3.28), (2.6, .82, .5), "accent", "trim")
    elif identifier == "sakiko":
        box(body, (-3.0, .0, -2.4), (6.0, 6.2, 4.8), "cloth", "fabric")
        box(body, (-2.5, .0, -2.72), (5.0, 5.9, .36), "white", "panel")
        box(body, (-2.8, 5.8, -2.76), (5.6, .58, .4), "accent", "trim")
        box(body, (-3.5, 6.4, -2.7), (7.0, .55, 5.3), "skirt_light", "trim")
        box(body, (-3.65, 6.9, -2.62), (7.3, 3.7, 5.25), "skirt", "plaid")
        box(body, (-3.9, 7.25, -2.94), (3.7, 3.35, .36), "skirt_light", "plaid")
        box(body, (.2, 7.25, -2.94), (3.7, 3.35, .36), "skirt_light", "plaid")
        for x in (-2.4, -1.2, 0.0, 1.2): box(body,(x,7.0,-3.05),(.14,3.55,.12),"skirt_light","trim")
    elif identifier == "uika":
        box(body, (-3.0, .0, -2.4), (6.0, 6.3, 4.8), "black", "fabric")
        box(body, (-2.2, .0, -2.72), (4.4, 1.0, .36), "white", "panel")
        box(body, (-3.25, 6.0, -2.75), (6.5, .52, .4), "gold", "trim")
        box(body, (-3.55, 6.5, -2.65), (7.1, 5.4, 5.2), "black", "fabric")
        box(body, (-3.75, 6.8, -2.95), (.42, 5.0, .25), "gold", "trim")
        box(body, (3.33, 6.8, -2.95), (.42, 5.0, .25), "gold", "trim")
    elif identifier == "umiri":
        box(body, (-3.0, .0, -2.4), (6.0, 6.3, 4.8), "black", "fabric")
        box(body, (-3.4, 6.0, -2.7), (6.8, 5.7, 5.2), "black", "fabric")
        box(body, (-4.1, .15, -3.0), (8.2, 2.5, .75), "accent", "fabric")
        rotated_box(body, "shawl_left", (-4.0, .7, -3.1), (4.3, 3.2, .75), "cloth", "fabric", (0,0,-.12))
        rotated_box(body, "shawl_right", (-.3, .7, -3.1), (4.3, 3.2, .75), "cloth", "fabric", (0,0,.12))

    # Fine robotic details sit at the sides and never cover the costume front.
    box(body, (-3.12, 1.1, -.25), (.22, 1.1, .5), "joint", "metal")
    box(body, (2.9, 1.1, -.25), (.22, 1.1, .5), "joint", "metal")
    box(body, (-.3, 1.8, -2.86), (.6, .38, .16), "joint_dark", "metal")
    box(body, (-.12, 1.91, -3.02), (.24, .16, .08), "lamp", "status")


def add_limb_clothing(arm: dict, leg: dict, identifier: str, side_name: str) -> None:
    # Sleeves, skin, fine joint seams and footwear are distinct layered geometry.
    sleeve = {"anon":"cloth", "soyo":"cloth", "tomori":"white", "taki":"white",
              "rana":"white", "sakiko":"cloth", "uika":"black", "umiri":"accent"}[identifier]
    box(arm, (-1.55,-2.0,-1.55), (3.1,5.5,3.1), sleeve, "fabric")
    if identifier in ("soyo", "taki"):
        box(arm, (-1.72,-1.75,-1.72), (3.44,2.4,3.44), "white", "fabric")
        box(arm, (-1.65,2.9,-1.65), (3.3,.38,3.3), "gold" if identifier == "soyo" else "accent", "trim")
    if identifier == "tomori":
        box(arm, (-1.62,-1.7,-1.62), (3.24,4.7,3.24), "white", "fabric")
        box(arm, (-1.7,2.6,-1.7), (3.4,.4,3.4), "cloth", "trim")
    if identifier == "rana" and side_name == "left":
        box(arm, (-1.65,-1.8,-1.68), (3.3,8.0,3.36), "cloth", "fabric")
        box(arm, (-1.7,5.7,-1.75), (3.4,.42,3.5), "accent", "trim")
    if identifier == "sakiko":
        box(arm, (-1.62,-1.8,-1.62), (3.24,7.0,3.24), "cloth", "fabric")
        box(arm, (-1.68,4.75,-1.68), (3.36,.38,3.36), "accent", "trim")
    # visible forearm/hand, with only a hairline cuff and small side actuator
    box(arm, (-1.34,4.0,-1.34), (2.68,2.5,2.68), "skin", "plain")
    box(arm, (-1.42,6.35,-1.42), (2.84,.22,2.84), "joint_dark", "metal")
    box(arm, (-1.27,6.58,-1.27), (2.54,2.6,2.54), "skin", "plain")
    box(arm, (1.28,6.75,-.42), (.24,1.15,.84), "joint", "metal")
    box(arm, (-.35,7.05,-1.43), (.7,.28,.16), "lamp", "status")
    # Skin or one patterned stocking runs to the ankle; seams are small flush plates.
    if identifier == "rana" and side_name == "left":
        box(leg, (-1.45,.15,-1.5), (2.9,9.0,3.0), "fishnet", "fishnet")
    elif identifier == "sakiko":
        box(leg, (-1.48,.15,-1.5), (2.96,4.9,3.0), "skin", "plain")
        box(leg, (-1.48,5.0,-1.5), (2.96,4.0,3.0), "sock", "fabric")
    else:
        box(leg, (-1.48,.15,-1.5), (2.96,9.0,3.0), "skin", "plain")
    box(leg, (-1.52,7.65,-1.58), (3.04,.32,3.16), "skin_shadow", "trim")
    box(leg, (-1.12,7.7,-1.72), (2.24,.18,.18), "joint_dark", "metal")
    # Small kneecap flush with the front silhouette.
    box(leg, (-.62,7.82,-1.62), (1.24,.42,.16), "joint", "metal")
    box(leg, (-.18,7.94,-1.76), (.36,.16,.08), "lamp", "status")
    shoe_material = "white" if identifier == "tomori" else ("sock" if identifier == "sakiko" else "black")
    if identifier == "tomori":
        box(leg, (-1.72,8.65,-2.35), (3.44,3.0,4.75), "white", "fabric")
        box(leg, (-1.52,9.65,-2.58), (3.04,.22,.16), "skirt_light", "trim")
    elif identifier == "sakiko":
        box(leg, (-1.64,8.0,-1.72), (3.28,2.35,3.44), "sock", "fabric")
        box(leg, (-1.74,10.0,-2.2), (3.48,1.8,4.65), "black", "fabric")
    else:
        # Ankle boots for MyGO; Rana's are knee-high, but not oversized mechanical armor.
        if identifier == "rana":
            box(leg, (-1.62,6.15,-1.7), (3.24,3.7,3.4), "black", "fabric")
            box(leg, (-1.7,9.2,-2.25), (3.4,2.65,4.6), "black", "fabric")
        else:
            box(leg, (-1.7,8.45,-2.25), (3.4,3.35,4.55), shoe_material, "fabric")
    if identifier == "soyo":
        box(leg, (-1.48,8.9,-1.57), (2.96,.5,.12), "white", "ankle_dots")

def make_robot(identifier: str, spec: dict) -> dict:
    robot = {**spec, "hair_style": spec["hairstyle"]}
    result = {"texture_width": LOGICAL_TEXTURE, "texture_height": LOGICAL_TEXTURE, "pixel_scale": SCALE,
              "parts": {}}
    parts = result["parts"]
    parts["head"] = part()
    parts["hat"] = part()
    parts["body"] = part()
    parts["right_arm"] = part((-4, 2, 0))
    parts["left_arm"] = part((4, 2, 0))
    parts["right_leg"] = part((-1.8, 12, 0))
    parts["left_leg"] = part((1.8, 12, 0))
    add_head(parts["head"], robot)
    add_body_style(parts["body"], robot, identifier)

    for side_name in ("right", "left"):
        add_limb_clothing(parts[f"{side_name}_arm"], parts[f"{side_name}_leg"], identifier, side_name)

    pack_uv(list(parts.values()))
    return result


def iter_cubes(node: dict):
    for cube in node["cubes"]:
        yield cube
    for child in node["children"].values():
        yield from iter_cubes(child)


def shade(color: tuple[int, int, int, int], factor: float) -> tuple[int, int, int, int]:
    return tuple(max(0, min(255, round(c * factor))) for c in color[:3]) + (255,)


def paint_cube(texture: Image.Image, cube: dict, robot: dict) -> None:
    u, v = (round(value * SCALE) for value in cube["uv"])
    # Match the original float UV unfold: north starts at u+d,v+d and retains
    # the full model-space dimensions before SCALE expands it to PNG texels.
    real_w, real_h, real_d = (round(n * SCALE) for n in cube["size"])
    material = cube["_material"]
    palettes = {**COMMON, "hair": robot["hair"], "hair_light": robot["hair_light"],
                "cloth": robot["cloth"], "accent": robot["accent"], "skirt": robot["skirt"],
                "skirt_light": robot["skirt_light"], "sock": robot["sock"],
                "eye_left": robot["eyes"][0], "eye_right": robot["eyes"][1]}
    base = rgb(palettes.get(material, "#8C9897"))
    pattern = cube["_pattern"]
    regions = {
        "north": (u + real_d, v + real_d, real_w, real_h),
        "south": (u + 2 * real_d + real_w, v + real_d, real_w, real_h),
        "west": (u, v + real_d, real_d, real_h),
        "east": (u + real_d + real_w, v + real_d, real_d, real_h),
        "up": (u + real_d, v, real_w, real_d),
        "down": (u + real_d + real_w, v, real_w, real_d),
    }
    factors = {"north": 1.0, "south": .79, "west": .88, "east": .94, "up": 1.08, "down": .72}
    draw = ImageDraw.Draw(texture)
    for face_name, (rx, ry, rw, rh) in regions.items():
        if rw <= 0 or rh <= 0:
            continue
        rect = (rx, ry, rx + rw - 1, ry + rh - 1)
        draw.rectangle(rect, fill=shade(base, factors[face_name]))
        # Fine graphic accents are laid on the front plane only; all surfaces remain pixel crisp.
        if face_name != "north":
            continue
        x0, y0 = rx, ry
        px, py = max(1, rw), max(1, rh)
        def cell(cx: int, cy: int, color: str, cw: int = 1, ch: int = 1) -> None:
            if cx < 0 or cy < 0 or cx + cw > px or cy + ch > py:
                return
            draw.rectangle((x0 + cx, y0 + cy, x0 + cx + cw - 1, y0 + cy + ch - 1), fill=rgb(color))
        if pattern == "face" and px >= 20 and py >= 20:
            eye_w = max(6, px // 5); eye_h = max(5, py // 6); eye_y = py // 2
            left_x, right_x = px // 7, px * 4 // 7
            for x, iris in ((left_x, robot["eyes"][0]), (right_x, robot["eyes"][1])):
                cell(x, eye_y, "#F7F5EE", eye_w, eye_h)
                cell(x + eye_w//2, eye_y, iris, max(2,eye_w//3), eye_h)
                cell(x + eye_w//2 + max(1,eye_w//12), eye_y, "#302E3A", max(1,eye_w//8), eye_h)
                cell(x + eye_w//2, eye_y, "#FFFFFF", max(1,eye_w//8), max(1,eye_h//4))
            brow_y = eye_y - max(2, py//16)
            cell(left_x, brow_y, robot["hair"], eye_w, max(1,py//32))
            cell(right_x, brow_y, robot["hair"], eye_w, max(1,py//32))
            cell(px//2, py*21//32, "#D5B6A7", max(1,px//32), max(2,py//12))
            cell(px//2-px//16, py*25//32, "#A75E68", max(2,px//8), max(1,py//32))
            cell(px//9, py*21//32, "#E4B5AA", max(2,px//12), max(1,py//32))
            cell(px*7//9, py*21//32, "#E4B5AA", max(2,px//12), max(1,py//32))
        elif pattern == "hair":
            for stripe in range(2, px - 1, max(4, SCALE*2)):
                cell(stripe, 0, robot["hair_light"], max(1,SCALE//2), py)
        elif pattern == "plaid":
            grid = robot["skirt_light"]
            for gx in range(SCALE, px, SCALE*3): cell(gx, 0, grid, 1, py)
            for gy in range(SCALE, py, SCALE*3): cell(0, gy, grid, px, 1)
        elif pattern == "trim":
            cell(0, 0, "#F0D47B", px, 1)
            if py > 2: cell(0, py - 1, "#E0C06C", px, 1)
        elif pattern == "status":
            draw.rectangle(rect, fill=rgb("#233C43"))
            cell(max(0, px//4), max(0, py//4), material_color(palettes, material),
                 max(1, px//2), max(1, py//2))
        elif pattern == "metal":
            cell(0, 0, "#DCE7E5", px, 1)
            if py > 2: cell(0, py - 1, "#303D44", px, 1)
        elif pattern == "panel":
            cell(0, 0, "#F4F5EE", px, 1)
            cell(0, py - 1, "#526B7B", px, 1)
        elif pattern == "fabric" and px > 2:
            cell(0, 0, robot["accent"], px, max(1,SCALE//2))
        elif pattern == "fishnet":
            spacing = max(5, SCALE*2)
            for start_x in range(0, px, spacing):
                draw.line((x0+start_x,y0,x0+min(px-1,start_x+py),y0+min(py-1,py-1)),
                          fill=rgb("#38333B"),width=1)
                draw.line((x0+start_x,y0+py-1,x0+min(px-1,start_x+py),y0+max(0,py-1-py)),
                          fill=rgb("#38333B"),width=1)
            for start_y in range(spacing, py, spacing):
                draw.line((x0,y0+start_y,x0+min(px-1,px-1),y0+min(py-1,start_y+px)),
                          fill=rgb("#38333B"),width=1)
                draw.line((x0,y0+start_y,x0+min(px-1,px-1),y0+max(0,start_y-px)),
                          fill=rgb("#38333B"),width=1)
        elif pattern == "ankle_dots":
            for gx in range(SCALE,px,SCALE*2):
                for gy in range(SCALE,py,SCALE*2): cell(gx,gy,"#CAD0D0",max(1,SCALE//2),max(1,SCALE//2))


def material_color(palette: dict, material: str) -> str:
    return palette.get(material, "#63E8D2")


def build_texture(model: dict, robot: dict) -> Image.Image:
    texture = Image.new("RGBA", (TEXTURE_PIXELS, TEXTURE_PIXELS), (0, 0, 0, 0))
    for node in model["parts"].values():
        for cube in iter_cubes(node):
            paint_cube(texture, cube, robot)
    return texture


# --- Actual resource preview rasterizer: hierarchy transforms, projected cube faces,
# nearest-neighbour UV sampling from the generated 512x512 entity texture, and depth test.
FACE_VERTS = {
    # Cube model Y grows downward from origin; first vertices are the visual top.
    "north": ((0,0,0),(1,0,0),(1,1,0),(0,1,0)),
    "south": ((1,0,1),(0,0,1),(0,1,1),(1,1,1)),
    "west": ((0,0,1),(0,0,0),(0,1,0),(0,1,1)),
    "east": ((1,0,0),(1,0,1),(1,1,1),(1,1,0)),
    "up": ((0,0,0),(1,0,0),(1,0,1),(0,0,1)),
    "down": ((0,1,1),(1,1,1),(1,1,0),(0,1,0)),
}
FACE_REGIONS = ("north", "south", "west", "east", "up", "down")


def rotate_vec(p: tuple[float,float,float], angles: list[float] | tuple[float,float,float]) -> tuple[float,float,float]:
    x, y, z = p
    rx, ry, rz = angles
    c, s = math.cos(rx), math.sin(rx); y, z = y*c-z*s, y*s+z*c
    c, s = math.cos(ry), math.sin(ry); x, z = x*c+z*s, -x*s+z*c
    c, s = math.cos(rz), math.sin(rz); x, y = x*c-y*s, x*s+y*c
    return x, y, z


def walk_geometry(node: dict, parent_transform, output: list) -> None:
    pivot = tuple(node["pivot"])
    angles = tuple(node.get("rotation", (0,0,0)))
    def transform(point):
        rotated = rotate_vec(point, angles)
        return parent_transform(tuple(pivot[i] + rotated[i] for i in range(3)))
    for cube in node["cubes"]:
        x,y,z = cube["origin"]; w,h,d = cube["size"]
        bounds = ((x,y,z),(x+w,y+h,z+d))
        output.append((cube, transform, bounds))
    for child in node.get("children", {}).values():
        walk_geometry(child, transform, output)


def vec_add(a,b): return tuple(a[i]+b[i] for i in range(3))
def vec_dot(a,b): return sum(a[i]*b[i] for i in range(3))
def vec_cross(a,b): return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def vec_unit(a):
    length=math.sqrt(vec_dot(a,a)); return tuple(v/length for v in a)


def uv_face(cube: dict, face: str, dims: tuple[float,float,float]) -> tuple[float,float,float,float]:
    u,v = cube["uv"]; w,h,d = dims
    return {
        "north":(u+d,v+d,w,h), "south":(u+2*d+w,v+d,w,h),
        "west":(u,v+d,d,h), "east":(u+d+w,v+d,d,h),
        "up":(u+d,v,w,d), "down":(u+d+w,v,w,d),
    }[face]


def barycentric(px: float, py: float, a, b, c):
    den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
    if abs(den)<1e-8: return None
    w0=((b[1]-c[1])*(px-c[0])+(c[0]-b[0])*(py-c[1]))/den
    w1=((c[1]-a[1])*(px-c[0])+(a[0]-c[0])*(py-c[1]))/den
    return w0,w1,1-w0-w1


def render_model(model: dict, texture: Image.Image, direction: tuple[float,float,float],
                 width: int, height: int, pixels_per_unit: float = 5.1) -> Image.Image:
    toward=vec_unit(direction); screen_right=vec_unit(vec_cross((0,-1,0),toward)); screen_up=vec_cross(toward,screen_right)
    geometry=[]
    identity=lambda p:p
    for node in model["parts"].values():
        walk_geometry(node, identity, geometry)
    projected=[]
    for cube, transform, (a,b) in geometry:
        x0,y0,z0=a; x1,y1,z1=b
        for face in FACE_REGIONS:
            local=[(x0+vx*(x1-x0),y0+vy*(y1-y0),z0+vz*(z1-z0)) for vx,vy,vz in FACE_VERTS[face]]
            world=[transform(p) for p in local]
            uv=uv_face(cube,face,(x1-x0,y1-y0,z1-z0))
            u0,v0,uw,uh=uv
            # Each face is stored in the corresponding standard unfolded rectangle.
            if face in ("north","south","west","east"):
                coords=((u0,v0),(u0+uw,v0),(u0+uw,v0+uh),(u0,v0+uh))
            elif face=="up": coords=((u0,v0+uh),(u0+uw,v0+uh),(u0+uw,v0),(u0,v0))
            else: coords=((u0,v0),(u0+uw,v0),(u0+uw,v0+uh),(u0,v0+uh))
            pts=[]
            for p in world:
                centered=(p[0],p[1]-8,p[2])
                sx=vec_dot(centered,screen_right); sy=vec_dot(centered,screen_up); depth=vec_dot(centered,toward)
                pts.append((sx,sy,depth))
            projected.append((face,pts,coords))
    allxy=[(p[0],p[1]) for _,pts,_ in projected for p in pts]
    minx=min(x for x,y in allxy); maxx=max(x for x,y in allxy); miny=min(y for x,y in allxy); maxy=max(y for x,y in allxy)
    model_w=(maxx-minx)*pixels_per_unit; model_h=(maxy-miny)*pixels_per_unit
    scale=min((width-8)/max(model_w,1),(height-8)/max(model_h,1))
    ox=width/2-(minx+maxx)/2*pixels_per_unit*scale
    oy=height/2+(miny+maxy)/2*pixels_per_unit*scale
    depth_buffer=[-1e30]*(width*height)
    canvas=Image.new("RGBA",(width,height),(0,0,0,0)); pixels=canvas.load(); tex=texture.load()
    face_light={"north":1.0,"south":.72,"west":.86,"east":.94,"up":1.08,"down":.68}
    for face,pts,uvs in projected:
        screen=[(ox+p[0]*pixels_per_unit*scale,oy-p[1]*pixels_per_unit*scale,p[2]) for p in pts]
        uvs_scaled=[(u*SCALE,v*SCALE) for u,v in uvs]
        for tri in ((0,1,2),(0,2,3)):
            ia,ib,ic=tri; a,b,c=screen[ia],screen[ib],screen[ic]
            left=max(0,int(math.floor(min(a[0],b[0],c[0])))); right=min(width-1,int(math.ceil(max(a[0],b[0],c[0]))))
            top=max(0,int(math.floor(min(a[1],b[1],c[1])))); bottom=min(height-1,int(math.ceil(max(a[1],b[1],c[1]))))
            for py in range(top,bottom+1):
                for px in range(left,right+1):
                    weights=barycentric(px+.5,py+.5,a,b,c)
                    if weights is None or min(weights)<-1e-5: continue
                    depth=sum(weights[k]*(a,b,c)[k][2] for k in range(3))
                    index=py*width+px
                    if depth<depth_buffer[index]: continue
                    uvx=sum(weights[k]*uvs_scaled[(ia,ib,ic)[k]][0] for k in range(3))
                    uvy=sum(weights[k]*uvs_scaled[(ia,ib,ic)[k]][1] for k in range(3))
                    tx=max(0,min(texture.width-1,int(uvx))); ty=max(0,min(texture.height-1,int(uvy)))
                    color=tex[tx,ty]
                    factor=face_light[face]
                    pixels[px,py]=(min(255,int(color[0]*factor)),min(255,int(color[1]*factor)),min(255,int(color[2]*factor)),255)
                    depth_buffer[index]=depth
    return canvas


def label_font(size: int = 17):
    for candidate in (Path("C:/Windows/Fonts/msyh.ttc"), Path("C:/Windows/Fonts/simhei.ttf")):
        try: return ImageFont.truetype(str(candidate), size)
        except OSError: pass
    return ImageFont.load_default()


def make_lineup(models: dict[str,dict], textures: dict[str,Image.Image], rear: bool) -> Image.Image:
    columns, rows, cell_w, cell_h = 4, 2, 250, 340
    canvas=Image.new("RGBA",(columns*cell_w,rows*cell_h),(20,24,29,255)); draw=ImageDraw.Draw(canvas)
    font=label_font(17); small=label_font(12)
    view_specs=(("后侧斜视",(.68,-.22,1.0)),("背面",(0,-.14,1.0))) if rear else (("正面",(0,-.14,-1.0)),("前侧斜视",(-.68,-.22,-1.0)))
    for i,(identifier,spec) in enumerate(ROBOTS.items()):
        cx=(i%columns)*cell_w; cy=(i//columns)*cell_h
        draw.rounded_rectangle((cx+7,cy+7,cx+cell_w-7,cy+cell_h-7),radius=10,fill=(31,37,44,255),outline=(71,82,92,255),width=1)
        model=models[identifier]; texture=textures[identifier]
        for view_index,(view_name,direction) in enumerate(view_specs):
            rendered=render_model(model,texture,direction,112,254,5.0)
            x=cx+8+view_index*116; y=cy+20
            canvas.alpha_composite(rendered,(x,y))
            draw.text((x+4,cy+277),view_name,font=small,fill=(191,206,214,255))
        draw.text((cx+16,cy+301),spec["label"],font=font,fill=(240,242,242,255))
    return canvas


def make_mygo_detail(models: dict[str,dict], textures: dict[str,Image.Image]) -> Image.Image:
    identifiers = ("anon", "soyo", "tomori", "taki", "rana", "sakiko")
    columns, cell_w, cell_h = 3, 320, 390
    rows = math.ceil(len(identifiers) / columns)
    canvas = Image.new("RGBA", (columns*cell_w, rows*cell_h), (73,84,95,255))
    draw = ImageDraw.Draw(canvas)
    title_font, small = label_font(18), label_font(14)
    views = (("正面",(0,0,-1.0)), ("前侧",(-.62,0,-1.0)))
    for index, identifier in enumerate(identifiers):
        x0, y0 = (index % columns)*cell_w, (index // columns)*cell_h
        draw.rounded_rectangle((x0+8,y0+8,x0+cell_w-8,y0+cell_h-8),radius=12,
                               fill=(86,98,109,255),outline=(142,154,164,255),width=2)
        for view_index,(label,direction) in enumerate(views):
            render = render_model(models[identifier],textures[identifier],direction,148,310,6.0)
            x, y = x0+8+view_index*151, y0+18
            canvas.alpha_composite(render,(x,y))
            draw.text((x+8,y0+333),label,font=small,fill=(194,209,218,255))
        draw.text((x0+18,y0+357),ROBOTS[identifier]["label"],font=title_font,fill=(244,246,247,255))
    return canvas


def generate() -> None:
    MODEL_DIR.mkdir(parents=True,exist_ok=True); TEXTURE_DIR.mkdir(parents=True,exist_ok=True); PREVIEW_DIR.mkdir(parents=True,exist_ok=True)
    models={}; textures={}; catalog=[]
    for identifier,spec in ROBOTS.items():
        model=make_robot(identifier,spec)
        texture=build_texture(model,spec)
        model_path=MODEL_DIR/f"{identifier}.json"
        texture_path=TEXTURE_DIR/f"{identifier}.png"
        model_path.write_text(json.dumps({"texture_width":LOGICAL_TEXTURE,"texture_height":LOGICAL_TEXTURE,
                                          "pixel_scale":SCALE,"parts":{k:clean_part(v) for k,v in model["parts"].items()}},
                                         ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="")
        texture.save(texture_path)
        # Keep exactly the emitted schema in memory for the actual-resource preview renderer.
        saved=json.loads(model_path.read_text(encoding="utf-8"))
        # Restore private style tags only in this in-memory render source; UV/geometry is from JSON.
        def transfer(src,dst):
            for sc,dc in zip(src["cubes"],dst["cubes"]):
                dc["_material"]=sc["_material"]; dc["_pattern"]=sc["_pattern"]
            for name,child in src["children"].items(): transfer(child,dst["children"][name])
        for key in model["parts"]: transfer(model["parts"][key],saved["parts"][key])
        models[identifier]=saved; textures[identifier]=texture
        catalog.append({"id":identifier,"name":spec["name"],"model":"ic2newera:robot_models/"+identifier,
                        "texture":"ic2newera:textures/entity/robot/"+identifier,"status":"原创资源已生成，实体与游戏渲染接入待主线整合",
                        "description":spec["description"]})
    (CATALOG_PATH).write_text(json.dumps({"format":"ic2newera_humanoid_robot_v1","models":catalog},ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="")
    make_lineup(models,textures,False).save(PREVIEW_DIR/"humanoid-robot-lineup.png")
    make_lineup(models,textures,True).save(PREVIEW_DIR/"humanoid-robot-lineup-back.png")
    make_mygo_detail(models,textures).save(PREVIEW_DIR/"humanoid-robot-mygo-detail.png")
    print(f"Generated {len(models)} humanoid robot models and textures, catalog, and three actual-resource previews.")


if __name__ == "__main__":
    generate()

