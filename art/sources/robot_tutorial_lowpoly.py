"""Original tutorial-style robot art; every .bbmodel mutation uses real MCP.

This source draws an original 64 px hard-edge atlas with Python's standard
library, writes reviewable MCP arguments, and calls the upstream one-shot MCP
client. It does not construct or rewrite a .bbmodel document itself. The
third-party tool remains outside this repository.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import zlib


PROJECT = Path(__file__).resolve().parents[2]
OUTPUT = PROJECT / "art/robot-mcp-workspace/characters/anon-r1"
MODEL = "anon-tutorial-lowpoly-r1.bbmodel"
ATLAS = "anon-tutorial-atlas-64.png"
FACES = ("north", "east", "south", "west", "up", "down")
PALETTE = {
    "skin": "F3D7C8",
    "hair": "E6A7BA",
    "hair_fringe": "F0B6C8",
    "hair_step": "D593AC",
    "blue": "43768F",
    "blue_dark": "2B4D67",
    "white": "EEEDE5",
    "white_edge": "D8D7CF",
    "yellow": "E6D078",
    "boot": "292E38",
    "sole": "1B2029",
    "boot_trim": "58616E",
    "iris": "698597",
    "lash": "414F5D",
    "mouth": "B97E87",
    "eye_white": "FAF8F0",
}
SWATCH = {name: [i * 4, 16, i * 4 + 4, 20] for i, name in enumerate(PALETTE)}
BASE_PALETTE = PALETTE.copy()
CHARACTER_ID = "anon"
CHARACTERS = {
    "anon": {"name": "千早爱音", "hair": "E6A7BA", "fringe": "F0B6C8", "step": "D593AC", "blue": "43768F", "eye": "698597",
             "features": "粉色侧分长发、双发夹；蓝短上衣、黄领结、白前短后长裙、黑靴。"},
    "sakiko": {"name": "丰川祥子", "hair": "8DAAC7", "fringe": "A8BED5", "step": "7793B1", "blue": "A4A5BC", "eye": "B9A376",
               "features": "蓝灰长发、侧编发块及黑结半扎发尾；灰紫长袖开衫、白衬衫、藏青领带、绿格短裙、白袜黑鞋。"},
    "tomori": {"name": "高松灯", "hair": "7E787C", "fringe": "A39AA0", "step": "655F69", "blue": "427694", "eye": "AD7681",
               "features": "灰棕短波波头、参差短刘海、粉灰眼；蓝白棒球外套、白内衫、橄榄绿短裤、米白鞋。"},
    "soyo": {"name": "长崎爽世", "hair": "B19685", "fringe": "CBB09B", "step": "997D70", "blue": "3D6D85", "eye": "6F8FA7",
             "features": "浅棕中分露额长发、少量阶梯波浪发尾；蓝色短袖裙装、金边腰线、白前短后长底裙。"},
    "taki": {"name": "椎名立希", "hair": "453746", "fringe": "685364", "step": "352C3D", "blue": "365E7A", "eye": "AC699B",
             "features": "深紫棕直长发、较齐刘海、紫眼；白色长袖衬衫、蓝胸衣和裙、黑腰带、白裙边与一侧裙尾。"},
    "rana": {"name": "要乐奈", "hair": "D5D8D1", "fringe": "EBEDE4", "step": "B5BEB8", "blue": "397796", "eye": "8AAAC2",
             "features": "银白短发、琥珀/蓝异色瞳；白T恤、单边蓝长外套、黑短裤、一侧格纹袜与高黑靴。"},
    "uika": {"name": "三角初华", "hair": "EBDFB9", "fringe": "F4EACC", "step": "D4C69E", "blue": "30313C", "eye": "AA84B7",
             "features": "浅奶金齐颈短发、少量淡粉内层、紫眼；白领黑短袖与黑金长裙、黑短靴。"},
    "umiri": {"name": "八幡海铃", "hair": "5D5D61", "fringe": "7A7C84", "step": "474A52", "blue": "623842", "eye": "6E9CA0",
              "features": "灰黑及肩短发、侧分与外翻发梢块、青眼；暗红披肩和短袖、黑色中长裙、黑短靴。"},
}


def configure(identifier: str) -> None:
    global CHARACTER_ID, OUTPUT, MODEL, ATLAS, PALETTE, SWATCH
    CHARACTER_ID = identifier
    OUTPUT = PROJECT / f"art/robot-mcp-workspace/characters/{identifier}-r1"
    MODEL = f"{identifier}-tutorial-lowpoly-r1.bbmodel"
    ATLAS = f"{identifier}-tutorial-atlas-64.png"
    PALETTE = BASE_PALETTE.copy()
    s = CHARACTERS[identifier]
    PALETTE.update(hair=s["hair"], hair_fringe=s["fringe"], hair_step=s["step"], blue=s["blue"], iris=s["eye"])
    if identifier != "anon":
        PALETTE.update(green="758267", green_dark="576751", burgundy="713D49", hair_inner="DABFC2", eye_alt="BDA56D")
    SWATCH = {name: [(i % 16) * 4, 16 + (i // 16) * 4, (i % 16) * 4 + 4, 20 + (i // 16) * 4]
              for i, name in enumerate(PALETTE)}
PLAN = """# 爱音教程低模 R1：制作前方案

依据：用户 2026-10-16 最新要求“无所谓了，不需要精细，完全按照这个教程来”，
https://xingduansuzhao.com/model ，以及 art/robot-block-design/prompt-tutorial-lowpoly.md。
这是新方向的单人首稿，不沿用被否定的 A6 / REFERENCE_UV_R1 面部或精细身体。

## 主要体块与比例

- 总高 32.5 单位（含 0.5 单位发顶）；脸是唯一一个 8×8×7 的正常头块。
- 头从 y=24 到 32；颈仅高 0.5，接到 7 单位宽、7.5 单位高的短上衣。
- 上衣、腰部、左右短袖与手臂都用简单长方体。黄领结以领口块和短垂带表达。
- 白裙用短前裙、后长裙与一档收窄裙尾；不堆布纹或薄片褶皱。
- 两腿从裙下露出；黑靴分靴筒、向前伸出的脚尖与鞋底。
- 粉色长发由发顶、后脑、后长发、收窄发尾、左右鬓块和长侧发组成。
  侧分刘海仅 3 块，配两枚小矩形夹。没有发丝切片、mesh 或曲面。
- 64×64 原创 atlas，16 色，硬边界。脸只保留小块灰蓝眼、简短眼线和两像素口。
  服装只保留蓝、白、黄和黑主色，不使用原图投影、渐变、柔光、噪点或 AI 重绘。

## 骨骼与动作设想

root
└─ body
   ├─ head（头、发型和发夹一起转动）
   ├─ arm_left
   ├─ arm_right
   ├─ leg_left
   ├─ leg_right
   └─ skirt（腰带、前裙与后长裙）

共 8 个骨骼。未来可做小幅转头、左右交替迈步/摆臂和轻微裙摆；
本轮只建立静态休息姿势，不制作动画或声明动作已验收。

## 制作与验收边界

.bbmodel 的创建、贴图导入与全部方块/UV 编辑均调用原作者
headless/call.ts，通过真实 MCP StdioClientTransport 执行。
本地文件 MCP 与桌面实时 MCP 分别记录；本稿不声称桌面实时连接。
渲染由原作者 bbmodel_render 生成；bbmodel_validate 仅静态几何检查，self_test=false。
只做爱音首稿，先看实际正/侧/后与常规尺寸并等待根审阅；不扩展其余 7 人。
游戏、Java、构建、安装、测试、存档、玩家密钥、上传与发布均不在本轮范围。
"""


def write_json(path: Path, obj: object) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def make_atlas() -> None:
    size = 64
    pixels = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]

    def color(name: str) -> tuple[int, int, int, int]:
        code = PALETTE[name]
        return tuple(int(code[i : i + 2], 16) for i in (0, 2, 4)) + (255,)

    def rect(x1: int, y1: int, x2: int, y2: int, name: str) -> None:
        for y in range(y1, y2):
            for x in range(x1, x2):
                pixels[y][x] = color(name)

    for name, uv in SWATCH.items():
        rect(*uv, name)

    # The front face is exactly 8x8 pixels. The only head geometry is one cube.
    rect(0, 0, 8, 8, "skin")
    for x in (1, 5):
        rect(x, 4, x + 2, 5, "lash")
        rect(x, 5, x + 2, 6, "eye_white")
    rect(2, 5, 3, 6, "iris")
    rect(5, 5, 6, 6, "iris")
    rect(3, 6, 5, 7, "mouth")
    if CHARACTER_ID in ("sakiko", "tomori", "taki", "rana", "umiri"):
        rect(3, 6, 5, 7, "skin")
        rect(3, 6, 4, 7, "mouth")
    if CHARACTER_ID == "rana":
        rect(2, 5, 3, 6, "eye_alt")

    # Coarse jacket regions, two buttons and a cropped dark hem.
    rect(8, 0, 16, 8, "blue")
    rect(11, 0, 13, 1, "skin")
    rect(11, 1, 13, 3, "blue_dark")
    rect(12, 4, 13, 5, "white_edge")
    rect(12, 6, 13, 7, "white_edge")
    rect(8, 7, 16, 8, "blue_dark")

    # A single hard border on the white cloth, rather than dense pleat shading.
    rect(24, 0, 32, 4, "white")
    rect(24, 3, 32, 4, "white_edge")

    # Simple boot upper: 2 pairs of square lace marks, no phototexture.
    rect(16, 0, 24, 8, "boot")
    for y in (2, 5):
        rect(18, y, 19, y + 1, "boot_trim")
        rect(21, y, 22, y + 1, "boot_trim")

    if CHARACTER_ID == "tomori":
        rect(8, 0, 16, 8, "blue")
        rect(10, 0, 14, 8, "white")
        rect(8, 7, 16, 8, "blue_dark")
        rect(14, 2, 15, 3, "white_edge")
        rect(14, 5, 15, 6, "white_edge")
    elif CHARACTER_ID == "sakiko":
        rect(8, 0, 16, 8, "blue")
        rect(11, 0, 13, 8, "white")
        rect(13, 3, 14, 4, "white_edge")
        rect(13, 6, 14, 7, "white_edge")
        rect(24, 0, 32, 4, "green")
        for x in (24, 28, 31):
            rect(x, 0, x + 1, 4, "green_dark")
        for y in (1, 3):
            rect(24, y, 32, y + 1, "green_dark")
    elif CHARACTER_ID == "soyo":
        rect(8, 0, 16, 8, "blue")
        rect(24, 0, 32, 4, "blue")
        rect(24, 3, 32, 4, "yellow")
    elif CHARACTER_ID == "taki":
        rect(8, 0, 16, 8, "blue")
        rect(8, 0, 16, 3, "white")
        rect(11, 0, 13, 1, "skin")
        rect(11, 1, 13, 2, "yellow")
    elif CHARACTER_ID == "rana":
        rect(8, 0, 16, 8, "white")
        rect(8, 0, 10, 8, "blue")
        rect(32, 0, 36, 8, "skin")
        for y in range(8):
            for x in range(4):
                if (x + y) % 3 == 0:
                    rect(32 + x, y, 33 + x, y + 1, "boot_trim")
    elif CHARACTER_ID == "uika":
        rect(8, 0, 16, 8, "boot")
        rect(24, 0, 32, 4, "boot")
        rect(24, 0, 25, 4, "yellow")
        rect(31, 0, 32, 4, "yellow")
    elif CHARACTER_ID == "umiri":
        rect(8, 0, 16, 8, "boot")
        rect(8, 0, 16, 3, "burgundy")
        rect(11, 0, 13, 3, "boot")

    raw = b"".join(b"\0" + bytes(v for px in row for v in px) for row in pixels)
    png = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += png_chunk(b"IDAT", zlib.compress(raw, 9)) + png_chunk(b"IEND", b"")
    (OUTPUT / ATLAS).write_bytes(png)
    write_json(OUTPUT / "palette.json", PALETTE)


def model_operations() -> list[dict]:
    if CHARACTER_ID != "anon":
        return other_model_operations(CHARACTER_ID)
    ops: list[dict] = []

    def group(name: str, origin: list[float], parent: str | None) -> None:
        ops.append({"op": "add_group", "name": name, "origin": origin, "parent": parent})

    def cube(name: str, a: list[float], b: list[float], parent: str, material: str,
             front: list[float] | None = None, other: dict | None = None) -> None:
        faces = {face: {"uv": SWATCH[material], "texture": 0} for face in FACES}
        if front:
            faces["north"]["uv"] = front
        if other:
            for face, mat in other.items():
                faces[face]["uv"] = SWATCH[mat]
        ops.append({"op": "add_cube", "name": name, "from": a, "to": b, "parent": parent,
                    "box_uv": False, "texture": 0, "faces": faces})

    group("root", [0, 0, 0], None)
    group("body", [0, 14, 0], "root")
    group("head", [0, 24, 0], "body")
    group("arm_left", [5, 23.5, 0], "body")
    group("arm_right", [-5, 23.5, 0], "body")
    group("leg_left", [1.75, 13.5, 0], "body")
    group("leg_right", [-1.75, 13.5, 0], "body")
    group("skirt", [0, 14, 0], "body")

    cube("head_main", [-4, 24, -3.5], [4, 32, 3.5], "head", "skin", [0, 0, 8, 8])
    cube("neck_short", [-1.25, 23.5, -1], [1.25, 24, 1], "body", "skin")
    cube("shirt_cropped", [-3.5, 16, -2], [3.5, 23.5, 2], "body", "blue", [8, 0, 16, 8])
    cube("midriff", [-3, 14.5, -1.75], [3, 16, 1.75], "body", "skin")
    cube("yellow_bow", [-1.25, 22.5, -2.5], [1.25, 23.5, -2], "body", "yellow")
    cube("yellow_ribbon", [-0.5, 20.5, -2.5], [0.5, 22.5, -2], "body", "yellow")

    for side, x1, x2 in (("left", 3.5, 6.5), ("right", -6.5, -3.5)):
        cube(f"sleeve_{side}", [x1, 20.5, -1.75], [x2, 23.5, 1.75], f"arm_{side}", "blue")
        cube(f"arm_skin_{side}", [x1 + 0.25, 13.5, -1.25], [x2 - 0.25, 20.5, 1.25],
             f"arm_{side}", "skin")

    cube("skirt_waistband", [-3.5, 13.5, -2], [3.5, 14.5, 2], "skirt", "white_edge")
    cube("skirt_front_short", [-4, 10, -2.5], [4, 13.5, 2.5], "skirt", "white", [24, 0, 32, 4])
    cube("skirt_back_long", [-4, 7, 1.25], [4, 10, 3], "skirt", "white")
    cube("skirt_back_step", [-3, 5, 1.5], [3, 7, 3.5], "skirt", "white")

    for side, x1, x2 in (("left", 0.5, 3), ("right", -3, -0.5)):
        cube(f"leg_skin_{side}", [x1, 5, -1.25], [x2, 13.5, 1.25], f"leg_{side}", "skin")
        cube(f"boot_shaft_{side}", [x1 - 0.1, 0.75, -1.5], [x2 + 0.1, 5, 1.5],
             f"leg_{side}", "boot", [16, 0, 24, 8])
        cube(f"boot_toe_{side}", [x1 - 0.1, 0.75, -2.25], [x2 + 0.1, 2, -1.5],
             f"leg_{side}", "boot")
        cube(f"boot_sole_{side}", [x1 - 0.1, 0, -2.25], [x2 + 0.1, 0.75, 1.5],
             f"leg_{side}", "sole")

    # Large hair masses only: cap, rear, two temple/side masses and 3-part fringe.
    cube("hair_cap", [-4.25, 31.5, -3.75], [4.25, 32.5, 3.75], "head", "hair_fringe")
    cube("hair_back_upper", [-4.25, 24, 3], [4.25, 31.5, 4.5], "head", "hair")
    cube("hair_back_long", [-4, 17, 2.75], [4, 24, 4.5], "head", "hair")
    cube("hair_back_end_step", [-3.25, 14, 3], [3.25, 17, 4.5], "head", "hair_step")
    cube("hair_temple_left", [3.75, 24, -3.25], [4.75, 31.5, 3.25], "head", "hair")
    cube("hair_temple_right", [-4.75, 24, -3.25], [-3.75, 31.5, 3.25], "head", "hair")
    cube("hair_long_side_left", [3.75, 18, 1.75], [4.75, 24, 3.25], "head", "hair")
    cube("hair_long_side_right", [-4.75, 19, 1.75], [-3.75, 24, 3.25], "head", "hair")
    cube("hair_fringe_sweep", [-4, 29, -4], [1.5, 31.5, -3.25], "head", "hair_fringe")
    cube("hair_fringe_part", [1.5, 30, -4], [4, 31.5, -3.25], "head", "hair_fringe")
    cube("hair_fringe_end", [-4, 27.5, -4], [-3, 29, -3.25], "head", "hair_fringe")
    cube("hair_clip_upper", [3.75, 29, -3.75], [4.5, 29.5, -3.25], "head", "boot_trim")
    cube("hair_clip_lower", [3.75, 28, -3.75], [4.5, 28.5, -3.25], "head", "boot_trim")
    return ops


def other_model_operations(identifier: str) -> list[dict]:
    """Original coarse cuboids; outfit/hair silhouettes are character-specific."""
    ops: list[dict] = []

    def group(name: str, origin: list[float], parent: str | None) -> None:
        ops.append({"op": "add_group", "name": name, "origin": origin, "parent": parent})

    def cube(name: str, a: list[float], b: list[float], parent: str, material: str,
             front: list[float] | None = None, face_materials: dict | None = None) -> None:
        faces = {face: {"uv": SWATCH[material], "texture": 0} for face in FACES}
        if front:
            faces["north"]["uv"] = front
        if face_materials:
            for face, mat in face_materials.items():
                faces[face]["uv"] = SWATCH[mat]
        ops.append({"op": "add_cube", "name": name, "from": a, "to": b, "parent": parent,
                    "box_uv": False, "texture": 0, "faces": faces})

    group("root", [0, 0, 0], None)
    group("body", [0, 14, 0], "root")
    group("head", [0, 24, 0], "body")
    for side, x in (("left", 5), ("right", -5)):
        group(f"arm_{side}", [x, 23.5, 0], "body")
    for side, x in (("left", 1.75), ("right", -1.75)):
        group(f"leg_{side}", [x, 13.5, 0], "body")
    group("skirt", [0, 14, 0], "body")
    cube("head_main", [-4, 24, -3.5], [4, 32, 3.5], "head", "skin", [0, 0, 8, 8])
    cube("neck_short", [-1.25, 23.5, -1], [1.25, 24, 1], "body", "skin")

    body_mat = "boot" if identifier in ("uika", "umiri") else ("white" if identifier == "rana" else "blue")
    cube("outfit_torso", [-3.5, 15.5, -2], [3.5, 23.5, 2], "body", body_mat, [8, 0, 16, 8])
    long_sleeves = identifier in ("sakiko", "tomori", "taki")
    for side, x1, x2 in (("left", 3.5, 6.5), ("right", -6.5, -3.5)):
        islong = long_sleeves or (identifier == "rana" and side == "right")
        mat = "white" if identifier in ("tomori", "taki") or (identifier == "rana" and side == "left") else (
            "blue" if identifier == "rana" else ("burgundy" if identifier == "umiri" else body_mat))
        end = 15 if islong else 20.5
        cube(f"sleeve_{side}", [x1, end, -1.75], [x2, 23.5, 1.75], f"arm_{side}", mat)
        cube(f"hand_or_arm_{side}", [x1 + 0.25, 13.5, -1.25], [x2 - 0.25, end, 1.25], f"arm_{side}", "skin")

    boot_height = {"sakiko": 2, "tomori": 2, "rana": 6.5, "uika": 3, "umiri": 3}.get(identifier, 5)
    for side, x1, x2 in (("left", 0.5, 3), ("right", -3, -0.5)):
        cube(f"leg_skin_{side}", [x1, boot_height, -1.25], [x2, 13.5, 1.25], f"leg_{side}", "skin",
             [32, 0, 36, 8] if identifier == "rana" and side == "left" else None)
        if identifier == "sakiko":
            cube(f"white_sock_{side}", [x1 - 0.05, 2, -1.3], [x2 + 0.05, 7.5, 1.3], f"leg_{side}", "white")
        shoe = "white_edge" if identifier == "tomori" else "boot"
        sole = "white" if identifier == "tomori" else "sole"
        cube(f"shoe_upper_{side}", [x1 - 0.1, 0.75, -1.5], [x2 + 0.1, boot_height, 1.5], f"leg_{side}", shoe,
             [16, 0, 24, 8] if identifier not in ("tomori", "sakiko") else None)
        cube(f"shoe_toe_{side}", [x1 - 0.1, 0.75, -2.25], [x2 + 0.1, min(2, boot_height), -1.5], f"leg_{side}", shoe)
        cube(f"shoe_sole_{side}", [x1 - 0.1, 0, -2.25], [x2 + 0.1, 0.75, 1.5], f"leg_{side}", sole)

    if identifier == "sakiko":
        cube("school_collar_left", [-2.5, 22.5, -2.5], [-0.25, 23.5, -2], "body", "white")
        cube("school_collar_right", [0.25, 22.5, -2.5], [2.5, 23.5, -2], "body", "white")
        cube("school_tie", [-0.4, 20.5, -2.5], [0.4, 23, -2], "body", "blue_dark")
        cube("skirt_waistband", [-3.5, 14.5, -2], [3.5, 15.5, 2], "skirt", "green_dark")
        cube("school_plaid_skirt", [-4, 10, -2.5], [4, 14.5, 2.5], "skirt", "green", [24, 0, 32, 4])
    elif identifier == "tomori":
        cube("shorts_waist", [-3.5, 13, -2], [3.5, 15.5, 2], "skirt", "green")
        cube("shorts_belt", [-3.6, 15, -2.05], [3.6, 15.5, 2.05], "skirt", "yellow")
        for side, x1, x2 in (("left", 0.25, 3.5), ("right", -3.5, -0.25)):
            cube(f"shorts_leg_{side}", [x1, 10, -2], [x2, 13, 2], "skirt", "green")
    elif identifier == "soyo":
        cube("gold_waist", [-3.5, 14.5, -2.1], [3.5, 15.5, 2.1], "skirt", "yellow")
        cube("blue_overskirt", [-4, 11.5, -2.5], [4, 14.5, 2.5], "skirt", "blue", [24, 0, 32, 4])
        cube("white_underskirt_front", [-4.15, 10, -2.6], [4.15, 11.5, 2.6], "skirt", "white")
        cube("white_underskirt_back", [-4, 5, 1.5], [4, 10, 3], "skirt", "white")
        cube("blue_overskirt_back", [-4, 8.5, 2.5], [4, 11.5, 3.25], "skirt", "blue")
    elif identifier == "taki":
        cube("black_waist", [-3.5, 14.5, -2.1], [3.5, 15.5, 2.1], "skirt", "boot")
        cube("blue_dress_skirt", [-4, 10, -2.5], [4, 14.5, 2.5], "skirt", "blue")
        cube("white_dress_hem", [-4.15, 9, -2.6], [4.15, 10, 2.6], "skirt", "white")
        cube("one_side_dress_tail", [-4.25, 6, 0.75], [-3, 10, 2.5], "skirt", "blue")
    elif identifier == "rana":
        cube("black_short_waist", [-3.5, 13, -2], [3.5, 15.5, 2], "skirt", "boot")
        for side, x1, x2 in (("left", 0.25, 3.5), ("right", -3.5, -0.25)):
            cube(f"black_shorts_{side}", [x1, 10.5, -2], [x2, 13, 2], "skirt", "boot")
        cube("one_side_coat_front", [-4, 7, -2.75], [-2, 23.5, -2], "body", "blue")
        cube("one_side_coat_back", [-4, 7, 1.75], [0, 23.5, 2.75], "body", "blue")
    elif identifier == "uika":
        for side, x1, x2 in (("left", 0.25, 2.5), ("right", -2.5, -0.25)):
            cube(f"white_collar_{side}", [x1, 22.5, -2.5], [x2, 23.5, -2], "body", "white")
        cube("gold_waist", [-3.5, 14.5, -2.1], [3.5, 15.5, 2.1], "skirt", "yellow")
        cube("black_long_dress_upper", [-3.75, 10, -2.5], [3.75, 14.5, 2.5], "skirt", "boot", [24, 0, 32, 4])
        cube("black_long_dress_hem", [-4.25, 5, -2.75], [4.25, 10, 2.75], "skirt", "boot", [24, 0, 32, 4])
    elif identifier == "umiri":
        cube("burgundy_shawl", [-3.75, 21, -2.25], [3.75, 23.5, 2.25], "body", "burgundy")
        cube("shawl_black_center", [-1, 21, -2.5], [1, 23.5, -2.25], "body", "boot")
        cube("black_waist", [-3.5, 14.5, -2.1], [3.5, 15.5, 2.1], "skirt", "boot")
        cube("black_mid_dress", [-4, 8, -2.5], [4, 14.5, 2.5], "skirt", "boot")

    cube("hair_cap", [-4.25, 31.5, -3.75], [4.25, 32.5, 3.75], "head", "hair_fringe")
    short = identifier in ("tomori", "rana", "uika", "umiri")
    back_bottom = {"tomori": 24, "rana": 25, "uika": 24, "umiri": 23}.get(identifier, 24)
    cube("hair_back_upper", [-4.25, back_bottom, 3], [4.25, 31.5, 4.5], "head", "hair")
    for side, x1, x2 in (("left", 3.75, 4.75), ("right", -4.75, -3.75)):
        cube(f"hair_temple_{side}", [x1, back_bottom, -3.25], [x2, 31.5, 3.25], "head", "hair")
    if not short:
        bottom = {"sakiko": 17, "soyo": 14, "taki": 13}[identifier]
        width = 4.5 if identifier == "soyo" else 4
        cube("hair_long_back", [-width, bottom + 2, 2.75], [width, 24, 4.5], "head", "hair")
        cube("hair_long_end_step", [-3.5, bottom, 3], [3.5, bottom + 2, 4.5], "head", "hair_step")
        for side, x1, x2 in (("left", 3.75, 4.75), ("right", -4.75, -3.75)):
            cube(f"hair_long_side_{side}", [x1, bottom + (1 if identifier == "soyo" else 4), 1.75], [x2, 24, 3.25], "head", "hair")
    if identifier == "soyo":
        for side, x1, x2 in (("left", 3.25, 5), ("right", -5, -3.25)):
            cube(f"hair_wave_end_{side}", [x1, 13, 3], [x2, 16, 4.5], "head", "hair_step")
        cube("hair_center_part_left", [0.75, 29, -4], [4, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_center_part_right", [-4, 29, -4], [-0.75, 31.5, -3.25], "head", "hair_fringe")
    elif identifier == "sakiko":
        cube("hair_fringe_left", [-4, 29.5, -4], [-1, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_fringe_center", [-1, 28.5, -4], [1, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_fringe_right", [1, 30, -4], [4, 31.5, -3.25], "head", "hair_fringe")
        for side, x1, x2 in (("left", 3.75, 5), ("right", -5, -3.75)):
            cube(f"hair_braid_{side}", [x1, 27, -0.25], [x2, 30, 2.5], "head", "hair_step")
            cube(f"half_tied_tail_{side}", [x1 - 0.25, 18, 2.5], [x2 + 0.25, 25, 4.5], "head", "hair")
            cube(f"black_hair_tie_{side}", [x1 - 0.15, 24.5, 2.25], [x2 + 0.15, 25.5, 3], "head", "boot")
    elif identifier in ("taki", "tomori"):
        cube("hair_fringe_main", [-4, 29.5, -4], [4, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_fringe_drop", [-1.25 if identifier == "taki" else -3, 28.5, -4],
             [0.5 if identifier == "taki" else -1, 29.5, -3.25], "head", "hair_fringe")
        if identifier == "tomori":
            cube("hair_short_bob_end", [-3.75, 23.5, 3], [3.75, 24.5, 4.5], "head", "hair_step")
    elif identifier == "rana":
        cube("hair_side_fringe_wide", [-4, 29.5, -4], [1, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_side_fringe_step", [1, 30.25, -4], [4, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_side_fringe_end", [-4, 28, -4], [-2.75, 29.5, -3.25], "head", "hair_fringe")
    elif identifier == "uika":
        for i, (x1, x2, y) in enumerate(((-4, -2, 29.5), (-2, 0, 30), (0, 2, 28.5), (2, 4, 29.5))):
            cube(f"hair_short_fringe_{i}", [x1, y, -4], [x2, 31.5, -3.25], "head", "hair_fringe")
        for side, x1, x2 in (("left", 3.75, 4.5), ("right", -4.5, -3.75)):
            cube(f"hair_pink_inner_{side}", [x1, 24, -3.5], [x2, 28, -2.5], "head", "hair_inner")
    elif identifier == "umiri":
        cube("hair_side_sweep", [-4, 29.5, -4], [1.5, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_side_part", [1.5, 30.25, -4], [4, 31.5, -3.25], "head", "hair_fringe")
        cube("hair_sweep_tip", [-4, 28.5, -4], [-2, 29.5, -3.25], "head", "hair_fringe")
        for side, x1, x2 in (("left", 3.75, 5.25), ("right", -5.25, -3.75)):
            cube(f"hair_flipped_end_{side}", [x1, 23, 1.75], [x2, 25, 3.5], "head", "hair_step")
    return ops


def prepare() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if CHARACTER_ID == "anon":
        plan = PLAN
    else:
        s = CHARACTERS[CHARACTER_ID]
        plan = f"""# {s['name']}教程低模 R1：制作前方案

沿用经根实际审阅的爱音 R1 方向与用户指定教程，不复制旧高模或面部资产。
主要比例：总高 32.5、单一 8×8×7 正常头块、0.5 高短颈、7 宽躯干、方块四肢。
角色体块与配色：{s['features']}
头发只用少量大块、侧分/中分与粗阶梯发尾，不用 mesh、圆柱、球、细斜块或密集切片。
服装按标志形态另建方块，64×64、有限色板、硬像素小眼口，不投影原图脸或精细重画。

骨骼：root → body → head、arm_left、arm_right、leg_left、leg_right、skirt。
其中 skirt 是下装分组；短裤角色仍用此分组以保持基础骨骼一致。
动作设想仅为轻微转头、摆臂/迈步及必要下装摆动，本轮不制作动画。

.bbmodel 创建、纹理导入和全部编辑通过原作者真实 MCP stdio；渲染同样用其 bbmodel_render。
静态 bbmodel_validate 的 self_test=false，保留实心下装覆盖腿部的内部交叠提示。
本稿待根逐人视觉审阅与用户确认；未进行游戏、Java、构建、安装、测试或发布。
"""
    (OUTPUT / "plan-before-modeling.md").write_text(plan, encoding="utf-8")
    make_atlas()
    ops = model_operations()
    write_json(OUTPUT / "planned-counts.json", {
        "cubes": sum(o["op"] == "add_cube" for o in ops),
        "bones": sum(o["op"] == "add_group" for o in ops),
        "meshes": 0, "animations": 0, "atlas": [64, 64], "palette_colors": len(PALETTE),
    })
    write_json(OUTPUT / "create-args.json", {
        "file": MODEL, "format": "free", "name": f"{CHARACTERS[CHARACTER_ID]['name']}｜教程方块低模 R1",
        "box_uv": False, "resolution": {"width": 64, "height": 64}, "overwrite": False,
    })
    write_json(OUTPUT / "texture-args.json", {"file": MODEL, "image": ATLAS, "name": ATLAS})
    write_json(OUTPUT / "edit-args.json", {"file": MODEL, "operations": ops})
    write_json(OUTPUT / "info-args.json", {"file": MODEL})
    write_json(OUTPUT / "outline-args.json", {"file": MODEL, "transforms": True})
    asymmetric = ["hair_long_side_left", "hair_long_side_right", "hair_fringe_sweep",
                  "hair_fringe_part", "hair_fringe_end", "hair_clip_upper", "hair_clip_lower"] if CHARACTER_ID == "anon" else []
    if CHARACTER_ID == "rana":
        asymmetric += ["sleeve_left", "sleeve_right", "hand_or_arm_left", "hand_or_arm_right"]
    write_json(OUTPUT / "validate-args.json", {
        "file": MODEL, "self_test": False,
        "asymmetric": asymmetric,
    })
    for view in ("front", "right", "back", "three-quarter"):
        write_json(OUTPUT / f"render-{view}-args.json", {
            "file": MODEL, "view": view, "width": 512, "height": 512,
            "orthographic": True, "lighting": "flat", "output": f"{CHARACTER_ID}-{view}-512.png",
        })
    write_json(OUTPUT / "render-normal-args.json", {
        "file": MODEL, "view": "three-quarter", "width": 192, "height": 192,
        "orthographic": True, "lighting": "flat", "output": f"{CHARACTER_ID}-normal-192.png",
    })


def call_mcp(tool_root: Path, tool: str, args_file: str, receipt_name: str,
             revision: str | None = None) -> dict | None:
    argpath = OUTPUT / args_file
    if revision:
        args = json.loads(argpath.read_text(encoding="utf-8"))
        args["expected_revision"] = revision
        write_json(argpath, args)
    bun = tool_root / "node_modules/@oven/bun-windows-x64/bin/bun.exe"
    node_bin = Path.home() / ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin"
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join((str(node_bin), str(bun.parent), env.get("PATH", "")))
    env["BB_RENDER_HOME"] = str(tool_root / "render-runtime")
    env["npm_config_proxy"] = "http://127.0.0.1:7897"
    env["npm_config_https_proxy"] = "http://127.0.0.1:7897"
    command = [str(bun), "run", str(tool_root / "headless/call.ts"), "--root", str(OUTPUT), tool, "@" + str(argpath)]
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    proc = subprocess.run(command, cwd=tool_root, env=env, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=600)
    receipt = {"tool": tool, "started_utc": started, "finished_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
               "transport": "real MCP stdio via upstream headless/call.ts", "exit_code": proc.returncode,
               "stdout": proc.stdout, "stderr": proc.stderr, "arguments_file": str(argpath)}
    write_json(OUTPUT / receipt_name, receipt)
    print(json.dumps({"tool": tool, "exit_code": proc.returncode, "receipt": str(OUTPUT / receipt_name)}, ensure_ascii=False), flush=True)
    if proc.returncode:
        print(proc.stdout, file=sys.stderr)
        print(proc.stderr, file=sys.stderr)
        raise SystemExit(proc.returncode)
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None


def build(tool_root: Path) -> None:
    if (OUTPUT / MODEL).exists():
        raise SystemExit("Model already exists; do not overwrite a reviewed iteration. Use explicit MCP edits or a new revision.")
    result = call_mcp(tool_root, "bbmodel_create", "create-args.json", "mcp-01-create.json")
    result = call_mcp(tool_root, "bbmodel_add_texture", "texture-args.json", "mcp-02-texture.json", result["revision"])
    result = call_mcp(tool_root, "bbmodel_edit", "edit-args.json", "mcp-03-edit.json", result["revision"])
    call_mcp(tool_root, "bbmodel_info", "info-args.json", "mcp-04-info.json")
    call_mcp(tool_root, "bbmodel_outline", "outline-args.json", "mcp-05-outline.json")
    call_mcp(tool_root, "bbmodel_validate", "validate-args.json", "mcp-06-static-validation.json")
    document = OUTPUT / MODEL
    write_json(OUTPUT / "model-provenance.json", {
        "source": str(Path(__file__).resolve()), "tool_root": str(tool_root),
        "upstream_commit": "178ce46feeca740163898748606575a3844a55b6",
        "model": str(document), "sha256": hashlib.sha256(document.read_bytes()).hexdigest(),
        "bbmodel_write_method": "MCP bbmodel_create + bbmodel_add_texture + bbmodel_edit",
        "third_party_character_assets_copied": False, "desktop_realtime_connection_claimed": False,
        "game_build_install_animation_and_user_acceptance": "not performed",
    })


def render(tool_root: Path) -> None:
    for i, view in enumerate(("front", "right", "back", "three-quarter"), start=7):
        call_mcp(tool_root, "bbmodel_render", f"render-{view}-args.json", f"mcp-{i:02d}-render-{view}.json")
    call_mcp(tool_root, "bbmodel_render", "render-normal-args.json", "mcp-11-render-normal.json")


def review_record() -> None:
    info = json.loads(json.loads((OUTPUT / "mcp-04-info.json").read_text(encoding="utf-8"))["stdout"])
    validation = json.loads(json.loads((OUTPUT / "mcp-06-static-validation.json").read_text(encoding="utf-8"))["stdout"])
    warnings = [v["message"] for gate in validation["gates"] for v in gate["violations"]]
    write_json(OUTPUT / "review-facts.json", {"character": CHARACTER_ID, "name": CHARACTERS[CHARACTER_ID]["name"],
        "features": CHARACTERS[CHARACTER_ID]["features"], "counts": info["counts"], "bounds": info["bounds"],
        "validation": validation["summary"], "warnings": warnings,
        "author_viewed_images": False, "root_review": "pending", "user_acceptance": "pending"})
    summary = f"""# {CHARACTERS[CHARACTER_ID]['name']}教程低模 R1

识别元素：{CHARACTERS[CHARACTER_ID]['features']}

工程：[{MODEL}]({MODEL})；原创 64×64 atlas 为 [{ATLAS}]({ATLAS})。
{info['counts']['cubes']} cubes、{info['counts']['groups']} bones、0 mesh、0 animation。
实际尺寸 {info['bounds']['size']}。MCP revision `{info['revision']}`。

实际渲染：[正面]({CHARACTER_ID}-front-512.png)、[右侧]({CHARACTER_ID}-right-512.png)、
[背面]({CHARACTER_ID}-back-512.png)、[三分之四]({CHARACTER_ID}-three-quarter-512.png)、
[常规尺寸 192]({CHARACTER_ID}-normal-192.png)。
均为原作者 bbmodel_render 的真实输出，无重绘/美化。

静态检查 {validation['summary']['errors']} 错误、{validation['summary']['warnings']} 警告，self_test=false。
提示原文：
""" + "\n".join(f"- {message}" for message in warnings) + """

下装是少量实心方块，因此包住上腿的内部交叠保留，并不声称空心服装。
逐项原始回执 mcp-01 至 mcp-11；制作前比例/骨骼/动作设想见 plan-before-modeling.md。
当前是静态美术研究稿；根独立审阅及用户确认分别记录在总交付记录，
不宣称用户已认可八人，不代表动画、实体、控制/烹饪或游戏接入已实现。
未进行游戏、Java、构建、安装、测试、存档或密钥操作，也没有上传发布。
"""
    (OUTPUT / "README.md").write_text(summary, encoding="utf-8")


def overview() -> None:
    # Layout only: paste exact native-size MCP render pixels, without repainting.
    from PIL import Image, ImageDraw, ImageFont
    root = PROJECT / "art/robot-mcp-workspace/characters"
    fontpath = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(fontpath), 17) if fontpath.exists() else ImageFont.load_default()
    normal = Image.new("RGB", (4 * 208, 2 * 232), "#20242A")
    fronts = Image.new("RGB", (4 * 512, 2 * 548), "#20242A")
    ndraw, fdraw = ImageDraw.Draw(normal), ImageDraw.Draw(fronts)
    manifest = []
    for i, (identifier, s) in enumerate(CHARACTERS.items()):
        folder = root / f"{identifier}-r1"
        npng, fpng = folder / f"{identifier}-normal-192.png", folder / f"{identifier}-front-512.png"
        nimage, fimage = Image.open(npng).convert("RGB"), Image.open(fpng).convert("RGB")
        if nimage.size != (192, 192) or fimage.size != (512, 512):
            raise ValueError("Overview expects native render dimensions; no scaling or repainting is performed.")
        x, y = (i % 4) * 208, (i // 4) * 232
        normal.paste(nimage, (x + 8, y + 32))
        ndraw.text((x + 8, y + 7), s["name"], font=font, fill="#EEEEEB")
        fx, fy = (i % 4) * 512, (i // 4) * 548
        fronts.paste(fimage, (fx, fy + 36))
        fdraw.text((fx + 16, fy + 9), s["name"], font=font, fill="#EEEEEB")
        manifest.append({"character": identifier, "normal": str(npng), "normal_sha256": hashlib.sha256(npng.read_bytes()).hexdigest(),
                         "front": str(fpng), "front_sha256": hashlib.sha256(fpng.read_bytes()).hexdigest()})
    normal.save(root / "eight-normal-192-overview.png")
    fronts.save(root / "eight-front-512-overview.png")
    write_json(root / "overview-provenance.json", {"method": "Native-size pixel paste of actual MCP renders, labels outside images; no scaling/repainting/beautification", "sources": manifest})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-root", type=Path, default=Path.home() / ".codex/tmp/ic2-blockbench-mcp-178ce46f")
    parser.add_argument("--build", action="store_true", help="Create a new draft using the real MCP client")
    parser.add_argument("--render", action="store_true", help="Render the saved draft through MCP")
    parser.add_argument("--characters", nargs="+", choices=list(CHARACTERS), default=["anon"])
    parser.add_argument("--overview", action="store_true", help="Arrange the eight actual renders without repainting")
    parser.add_argument("--quota-checked", action="store_true", help="Caller has checked all actual usage windows for this stage")
    args = parser.parse_args()
    if not args.quota_checked:
        raise SystemExit("Read actual ordinary usage and every quota window before this stage, then pass --quota-checked.")
    if args.build or args.render or not args.overview:
        for identifier in args.characters:
            configure(identifier)
            if identifier == "anon" and (OUTPUT / MODEL).exists():
                raise SystemExit("Anon R1 is frozen after root review. Use explicit new-iteration MCP edits if authorized.")
            prepare()
            if args.build:
                build(args.tool_root)
            if args.render:
                render(args.tool_root)
            if args.build and args.render:
                review_record()
            print(str(OUTPUT), flush=True)
    if args.overview:
        overview()


if __name__ == "__main__":
    main()
