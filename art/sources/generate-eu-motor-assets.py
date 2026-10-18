"""Generate the original single-block EU-to-Create motor assets."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw
from machine_visual_style import write_device


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"

COLORS = {
    "edge": "#343B3D",
    "shell": "#626B6A",
    "shell_light": "#828B87",
    "shell_dark": "#485252",
    "panel": "#303A3B",
    "black": "#171F20",
    "copper_dark": "#8D4B2E",
    "copper": "#C77542",
    "copper_light": "#E7A36A",
    "cyan_dark": "#1C6970",
    "cyan": "#45D4D0",
    "cyan_light": "#A0FFF0",
    "steel": "#AAB5B1",
    "steel_dark": "#697674",
    "amber": "#E5B45C",
}


def color(name: str) -> tuple[int, int, int, int]:
    value = COLORS[name].lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def canvas(base: str) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (16, 16), color(base))
    return image, ImageDraw.Draw(image)


def border(draw: ImageDraw.ImageDraw) -> None:
    draw.rectangle((0, 0, 15, 15), outline=color("edge"))
    for point in ((1, 1), (14, 1), (1, 14), (14, 14)):
        draw.point(point, fill=color("steel"))


def front() -> Image.Image:
    image, d = canvas("shell_dark")
    border(d)
    # Concentric iron and copper bearing rings surround an open 6..10 shaft port.
    d.rectangle((2, 2, 13, 13), fill=color("shell"))
    d.rectangle((3, 3, 12, 12), fill=color("copper_dark"))
    d.rectangle((4, 4, 11, 11), fill=color("steel_dark"))
    d.rectangle((5, 5, 10, 10), fill=color("copper_light"))
    d.rectangle((6, 6, 10, 10), fill=color("black"))
    # Cyan lamps identify the powered motor without obscuring the central opening.
    d.point((3, 6), fill=color("cyan"))
    d.point((12, 9), fill=color("cyan_light"))
    d.line((5, 2, 10, 2), fill=color("steel"))
    d.line((5, 13, 10, 13), fill=color("copper"))
    return image


def rear() -> Image.Image:
    image, d = canvas("shell_dark")
    border(d)
    d.rectangle((2, 2, 13, 13), fill=color("shell"))
    # Recessed EU cable socket on the north/rear face.
    d.rectangle((3, 3, 12, 12), fill=color("panel"))
    d.rectangle((4, 4, 11, 11), fill=color("black"))
    d.rectangle((5, 5, 10, 10), fill=color("shell_dark"))
    d.rectangle((6, 6, 9, 9), fill=color("copper_dark"))
    d.rectangle((7, 7, 8, 8), fill=color("black"))
    for point in ((5, 6), (10, 6), (5, 9), (10, 9)):
        d.point(point, fill=color("copper_light"))
    d.point((3, 5), fill=color("cyan"))
    d.point((12, 10), fill=color("cyan_dark"))
    return image


def side() -> Image.Image:
    image, d = canvas("shell_dark")
    border(d)
    d.rectangle((2, 2, 13, 13), fill=color("shell"))
    d.rectangle((3, 3, 12, 12), fill=color("shell_dark"))
    # Alternating cooling ribs and exposed copper winding make the motor silhouette readable.
    for y in (4, 6, 9, 11):
        d.line((3, y, 12, y), fill=color("steel_dark"), width=1)
        d.line((4, y, 11, y), fill=color("shell_light"), width=1)
    d.rectangle((5, 7, 10, 8), fill=color("copper_dark"))
    d.line((6, 7, 6, 8), fill=color("copper_light"))
    d.line((8, 7, 8, 8), fill=color("copper"))
    d.line((10, 7, 10, 8), fill=color("copper_light"))
    d.point((3, 7), fill=color("cyan"))
    d.point((12, 8), fill=color("cyan_dark"))
    return image


def top() -> Image.Image:
    image, d = canvas("shell_dark")
    border(d)
    d.rectangle((2, 2, 13, 13), fill=color("shell"))
    d.rectangle((3, 3, 12, 12), fill=color("panel"))
    # Housing seam and compact cooling grille.
    d.rectangle((4, 4, 11, 11), fill=color("shell_dark"))
    for y in (5, 7, 9, 11):
        d.line((5, y, 10, y), fill=color("steel"))
    d.line((3, 3, 6, 3), fill=color("copper"))
    d.line((9, 12, 12, 12), fill=color("copper_light"))
    d.point((3, 6), fill=color("cyan"))
    d.point((12, 9), fill=color("cyan_dark"))
    return image


def bottom() -> Image.Image:
    image, d = canvas("edge")
    border(d)
    d.rectangle((2, 2, 13, 13), fill=color("shell_dark"))
    d.rectangle((4, 3, 11, 12), fill=color("panel"))
    d.rectangle((5, 4, 10, 11), fill=color("steel_dark"))
    d.rectangle((6, 5, 9, 10), fill=color("shell"))
    d.line((6, 6, 9, 6), fill=color("steel"))
    d.line((6, 8, 9, 8), fill=color("steel"))
    d.line((6, 10, 9, 10), fill=color("copper"))
    d.point((3, 3), fill=color("copper_light"))
    d.point((12, 12), fill=color("cyan_dark"))
    return image


def write_png(name: str, image: Image.Image) -> None:
    path = ASSETS / "textures/block" / f"eu_motor_{name}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=False)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def block_model() -> dict:
    # The south face is the shaft/output face. The renderer supplies the live shaft.
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "ic2newera:block/eu_motor_side",
            "front": "ic2newera:block/eu_motor_front",
            "rear": "ic2newera:block/eu_motor_rear",
            "side": "ic2newera:block/eu_motor_side",
            "top": "ic2newera:block/eu_motor_top",
            "bottom": "ic2newera:block/eu_motor_bottom",
        },
        "elements": [{
            "from": [0, 0, 0],
            "to": [16, 16, 16],
            "faces": {
                "north": {"texture": "#rear"},
                "south": {"texture": "#front"},
                "east": {"texture": "#side"},
                "west": {"texture": "#side"},
                "up": {"texture": "#top"},
                "down": {"texture": "#bottom"},
            },
        }],
    }


def item_display() -> dict:
    return {
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    }


def update_catalog() -> None:
    path = ROOT / "art/model-catalog.json"
    catalog = json.loads(path.read_text(encoding="utf-8-sig"))
    models = catalog.setdefault("models", [])
    models[:] = [entry for entry in models if entry.get("id") != "eu_motor"]
    models.append({
        "id": "eu_motor",
        "name": "EU 电动机",
        "category": "能源转换设备",
        "status": "资源已补齐，待集成验收",
        "description": "单向将 EU 输出为 Create 转速；安山灰机壳、铜绕组与散热片、青色状态灯，南侧中心留有动态出轴孔，北侧设 EU 电缆接口。原创程序绘制资源。",
        "assetRoot": "src/main/resources/assets",
        "path": "ic2newera/models/block/eu_motor.json",
    })
    path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate() -> None:
    for name, painter in (("front", front), ("rear", rear), ("side", side), ("top", top), ("bottom", bottom)):
        write_png(name, painter())

    model = block_model()
    write_json(ASSETS / "models/block/eu_motor.json", model)
    write_json(ASSETS / "models/item/eu_motor.json", {
        "parent": "ic2newera:block/eu_motor",
        "display": item_display(),
    })
    # Base geometry faces south (+Z): south is unrotated; vertical facings use X rotation.
    write_json(ASSETS / "blockstates/eu_motor.json", {
        "variants": {
            "facing=south": {"model": "ic2newera:block/eu_motor"},
            "facing=north": {"model": "ic2newera:block/eu_motor", "y": 180},
            "facing=east": {"model": "ic2newera:block/eu_motor", "y": 270},
            "facing=west": {"model": "ic2newera:block/eu_motor", "y": 90},
            "facing=down": {"model": "ic2newera:block/eu_motor", "x": 90},
            "facing=up": {"model": "ic2newera:block/eu_motor", "x": 270},
        },
    })
    write_json(ROOT / "src/main/resources/data/ic2newera/loot_tables/blocks/eu_motor.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": "ic2newera:eu_motor"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })
    update_catalog()
    write_device('eu_motor')


if __name__ == "__main__":
    generate()
    print("Generated EU motor models, six-way facing states, loot table, five 16x16 textures, and catalog entry.")
