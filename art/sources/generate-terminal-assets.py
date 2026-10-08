"""Generate the original IC2 New Era industrial terminal assets."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"

COLORS = {
    "black": "#111820",
    "shell": "#26313A",
    "shell_light": "#3C4A55",
    "steel": "#596773",
    "steel_light": "#83929A",
    "panel": "#18232C",
    "screen": "#102F38",
    "cyan": "#45D7D0",
    "cyan_dim": "#277A82",
    "purple": "#A77BEB",
    "purple_dim": "#64459A",
    "amber": "#E6B95F",
}


def rgba(name: str) -> tuple[int, int, int, int]:
    value = COLORS[name].lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def save_texture(name: str, draw_face) -> None:
    image = Image.new("RGBA", (16, 16), rgba("shell"))
    draw_face(ImageDraw.Draw(image))
    target = ASSETS / "textures/block" / f"terminal_{name}.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, format="PNG", optimize=False)


def front(draw: ImageDraw.ImageDraw) -> None:
    # Recessed chest-like face with a cyan status screen and two distinct ports.
    draw.rectangle((0, 0, 15, 15), fill=rgba("shell_light"))
    draw.rectangle((1, 1, 14, 14), fill=rgba("shell"))
    draw.rectangle((2, 2, 13, 13), fill=rgba("panel"))
    draw.rectangle((3, 3, 12, 9), fill=rgba("steel"))
    draw.rectangle((4, 4, 11, 8), fill=rgba("screen"))
    draw.rectangle((5, 5, 10, 5), fill=rgba("cyan_dim"))
    draw.line((5, 6, 8, 6), fill=rgba("cyan"))
    draw.line((5, 7, 9, 7), fill=rgba("cyan_dim"))
    draw.point((10, 7), fill=rgba("cyan"))
    # Two lower slots read as item input/output rather than decorative lights.
    draw.rectangle((3, 11, 7, 13), fill=rgba("black"))
    draw.rectangle((4, 12, 6, 12), fill=rgba("cyan_dim"))
    draw.rectangle((9, 11, 13, 13), fill=rgba("black"))
    draw.rectangle((10, 12, 12, 12), fill=rgba("purple"))
    for point in ((1, 2), (14, 2), (1, 13), (14, 13)):
        draw.point(point, fill=rgba("steel_light"))
    draw.line((6, 0, 9, 0), fill=rgba("amber"))


def back(draw: ImageDraw.ImageDraw) -> None:
    # Rear data panel: paired optical sockets plus wireless antenna marking.
    draw.rectangle((0, 0, 15, 15), fill=rgba("shell_light"))
    draw.rectangle((1, 1, 14, 14), fill=rgba("shell"))
    draw.rectangle((2, 2, 13, 13), fill=rgba("panel"))
    draw.rectangle((3, 3, 12, 6), fill=rgba("black"))
    for x in (4, 6, 9, 11):
        draw.rectangle((x, 4, x + 1, 5), fill=rgba("purple" if x in (4, 11) else "purple_dim"))
    draw.rectangle((6, 8, 9, 12), fill=rgba("black"))
    draw.rectangle((7, 9, 8, 11), fill=rgba("purple"))
    draw.arc((4, 7, 11, 14), 205, 335, fill=rgba("cyan_dim"))
    draw.arc((2, 6, 13, 15), 205, 335, fill=rgba("cyan"))
    for x in (1, 14):
        draw.point((x, 2), fill=rgba("steel_light"))
        draw.point((x, 13), fill=rgba("steel_light"))


def side(draw: ImageDraw.ImageDraw) -> None:
    # Side panels carry separate item-transfer and EU/data connection cues.
    draw.rectangle((0, 0, 15, 15), fill=rgba("shell_light"))
    draw.rectangle((1, 1, 14, 14), fill=rgba("shell"))
    draw.rectangle((2, 2, 13, 13), fill=rgba("panel"))
    draw.rectangle((3, 4, 6, 11), fill=rgba("black"))
    draw.rectangle((4, 5, 5, 10), fill=rgba("cyan_dim"))
    draw.polygon(((4, 3), (6, 3), (5, 2)), fill=rgba("cyan"))
    draw.rectangle((9, 4, 12, 11), fill=rgba("black"))
    draw.rectangle((10, 5, 11, 10), fill=rgba("purple_dim"))
    draw.polygon(((10, 12), (11, 12), (10, 13)), fill=rgba("purple"))
    draw.line((7, 4, 7, 11), fill=rgba("steel"))
    draw.point((1, 2), fill=rgba("steel_light"))
    draw.point((14, 13), fill=rgba("steel_light"))


def top(draw: ImageDraw.ImageDraw) -> None:
    # Hinged lid and two bright latches, visually distinct from the server rack.
    draw.rectangle((0, 0, 15, 15), fill=rgba("steel"))
    draw.rectangle((1, 1, 14, 14), fill=rgba("shell"))
    draw.rectangle((2, 2, 13, 13), fill=rgba("panel"))
    draw.rectangle((3, 3, 12, 11), fill=rgba("shell_light"))
    draw.rectangle((4, 4, 11, 10), fill=rgba("shell"))
    draw.line((5, 5, 10, 5), fill=rgba("steel_light"))
    draw.line((5, 7, 10, 7), fill=rgba("steel"))
    draw.line((5, 9, 10, 9), fill=rgba("steel_light"))
    draw.rectangle((5, 12, 7, 13), fill=rgba("amber"))
    draw.rectangle((9, 12, 11, 13), fill=rgba("amber"))
    draw.point((2, 2), fill=rgba("cyan"))
    draw.point((13, 2), fill=rgba("cyan"))


def bottom(draw: ImageDraw.ImageDraw) -> None:
    # Underside service hatch gives the lower face a readable logistics interface.
    draw.rectangle((0, 0, 15, 15), fill=rgba("black"))
    draw.rectangle((1, 1, 14, 14), fill=rgba("steel"))
    draw.rectangle((2, 2, 13, 13), fill=rgba("shell"))
    draw.rectangle((4, 3, 11, 12), fill=rgba("panel"))
    draw.rectangle((5, 4, 10, 10), fill=rgba("black"))
    draw.rectangle((6, 5, 9, 9), fill=rgba("purple_dim"))
    draw.line((6, 6, 9, 6), fill=rgba("purple"))
    draw.rectangle((5, 11, 10, 12), fill=rgba("cyan_dim"))
    for point in ((2, 2), (13, 2), (2, 13), (13, 13)):
        draw.point(point, fill=rgba("steel_light"))


def model() -> dict:
    faces = {
        "north": "#front",
        "south": "#back",
        "east": "#side",
        "west": "#side",
        "up": "#top",
        "down": "#bottom",
    }
    cube_faces = {face: {"texture": texture} for face, texture in faces.items()}
    antenna_faces = {face: {"texture": "#antenna"} for face in ("north", "south", "east", "west", "up", "down")}
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "ic2newera:block/terminal_side",
            "front": "ic2newera:block/terminal_front",
            "back": "ic2newera:block/terminal_back",
            "side": "ic2newera:block/terminal_side",
            "top": "ic2newera:block/terminal_top",
            "bottom": "ic2newera:block/terminal_bottom",
            "shell": "ic2newera:block/terminal_side",
            "antenna": "ic2newera:block/terminal_antenna",
        },
        "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 15], "faces": cube_faces},
            # The rear mast fills the final pixel of depth and remains within the block.
            {"from": [7, 8, 14], "to": [9, 15, 16], "faces": antenna_faces},
        ],
        "display": {
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
        },
    }


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def update_catalog() -> None:
    path = ROOT / "art/model-catalog.json"
    catalog = json.loads(path.read_text(encoding="utf-8-sig"))
    models = catalog.setdefault("models", [])
    models[:] = [entry for entry in models if entry.get("id") != "terminal"]
    models.append({
        "id": "terminal",
        "name": "终端机",
        "category": "工业网络",
        "status": "源码接入待实机验收",
        "description": "单方块赛博机箱；正面屏幕与物料端口、背面光纤和 Wi-Fi 天线、顶部检修盖及独立底部接口。原创程序绘制资源。",
        "assetRoot": "src/main/resources/assets",
        "path": "ic2newera/models/block/terminal.json",
    })
    path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate() -> None:
    save_texture("front", front)
    save_texture("back", back)
    save_texture("side", side)
    save_texture("top", top)
    save_texture("bottom", bottom)

    antenna = Image.new("RGBA", (16, 16), rgba("shell"))
    draw = ImageDraw.Draw(antenna)
    draw.rectangle((3, 3, 12, 12), fill=rgba("purple_dim"))
    draw.rectangle((5, 5, 10, 10), fill=rgba("purple"))
    draw.line((7, 7, 8, 8), fill=rgba("cyan"))
    target = ASSETS / "textures/block/terminal_antenna.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    antenna.save(target, format="PNG", optimize=False)

    terminal_model = model()
    write_json(ASSETS / "models/block/terminal.json", terminal_model)
    write_json(ASSETS / "models/item/terminal.json", {
        "parent": "ic2newera:block/terminal",
        "display": terminal_model["display"],
    })
    write_json(ASSETS / "blockstates/terminal.json", {
        "variants": {
            "facing=north": {"model": "ic2newera:block/terminal"},
            "facing=east": {"model": "ic2newera:block/terminal", "y": 90},
            "facing=south": {"model": "ic2newera:block/terminal", "y": 180},
            "facing=west": {"model": "ic2newera:block/terminal", "y": 270},
        },
    })
    write_json(ROOT / "src/main/resources/data/ic2newera/loot_tables/blocks/terminal.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": "ic2newera:terminal"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })
    update_catalog()


if __name__ == "__main__":
    generate()
    print("Generated terminal block/item models, facing blockstate, loot table, six textures, and catalog entry.")
