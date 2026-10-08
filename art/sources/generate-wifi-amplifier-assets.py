"""Generate the original Wi-Fi amplifier item icon and model."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"

COLORS = {
    "outline": "#101B1D",
    "board_dark": "#154239",
    "board": "#24765B",
    "board_light": "#46B982",
    "trace": "#B47A43",
    "copper_dark": "#98552F",
    "copper": "#D58A4F",
    "copper_light": "#F1BC79",
    "cyan_dark": "#218892",
    "cyan": "#4CE0D8",
    "cyan_light": "#B3FFF2",
    "chip": "#253942",
    "chip_light": "#778A83",
}


def rgba(name: str) -> tuple[int, int, int, int]:
    value = COLORS[name].lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def amplifier_icon() -> Image.Image:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # Compact dark-green circuit board with visible contacts and distinct traces.
    draw.rectangle((2, 6, 12, 14), fill=rgba("outline"))
    draw.rectangle((3, 7, 11, 13), fill=rgba("board_dark"))
    draw.rectangle((4, 8, 10, 12), fill=rgba("board"))
    draw.rectangle((6, 9, 8, 11), fill=rgba("chip"))
    draw.point((7, 10), fill=rgba("cyan"))
    draw.line((4, 9, 5, 9, 6, 10), fill=rgba("trace"))
    draw.line((10, 9, 9, 9, 8, 10), fill=rgba("copper_light"))
    draw.line((4, 11, 5, 11, 6, 10), fill=rgba("board_light"))
    draw.line((10, 11, 9, 11, 8, 10), fill=rgba("trace"))

    # Three cyan radio arcs point upward and left from the antenna.
    draw.arc((5, 1, 13, 9), 205, 295, fill=rgba("cyan_dark"), width=1)
    draw.arc((7, 2, 14, 9), 205, 295, fill=rgba("cyan"), width=1)
    draw.arc((9, 3, 15, 9), 205, 295, fill=rgba("cyan_light"), width=1)

    # Copper whip antenna rising from the board's right edge, above the radio arcs.
    draw.rectangle((11, 5, 13, 8), fill=rgba("copper_dark"))
    draw.rectangle((12, 3, 13, 6), fill=rgba("copper"))
    draw.point((12, 2), fill=rgba("copper_light"))
    draw.point((13, 3), fill=rgba("copper_light"))

    # Gold edge contacts clarify that the amplifier is an installable module.
    for x in (4, 6, 8, 10):
        draw.rectangle((x, 13, x + 1, 14), fill=rgba("copper_light"))
    draw.line((3, 7, 5, 7), fill=rgba("board_light"))
    draw.point((3, 12), fill=rgba("cyan"))
    return image


def generate() -> None:
    icon_path = ASSETS / "textures/item/wifi_amplifier.png"
    icon_path.parent.mkdir(parents=True, exist_ok=True)
    amplifier_icon().save(icon_path, format="PNG", optimize=False)

    model_path = ASSETS / "models/item/wifi_amplifier.json"
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_text(json.dumps({
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "ic2newera:item/wifi_amplifier"},
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    catalog_path = ROOT / "art/model-catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8-sig"))
    models = catalog.setdefault("models", [])
    models[:] = [entry for entry in models if entry.get("id") != "wifi_amplifier"]
    models.append({
        "id": "wifi_amplifier",
        "name": "Wi-Fi 增幅器",
        "category": "工业网络",
        "status": "资源已补齐，待集成验收",
        "description": "可安装在交换机升级槽中的电路板模块，带铜色天线和青色信号弧；用于表达 Wi-Fi 覆盖增强。原创程序绘制图标。",
        "assetRoot": "src/main/resources/assets",
        "path": "ic2newera/models/item/wifi_amplifier.json",
    })
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    generate()
    print("Generated the Wi-Fi amplifier icon, item model, and catalog entry.")
