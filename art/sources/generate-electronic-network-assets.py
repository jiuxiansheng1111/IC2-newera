#!/usr/bin/env python3
"""Generate original 16x16 electronic items and compact industrial machine blocks."""

from __future__ import annotations

import json
from pathlib import Path
from PIL import Image, ImageDraw
from material_pixel_style import DUSTS, draw_dust
from machine_visual_style import write_device

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"

# Neutral metallic silicon, warm copper traces and blue-green electronic assemblies.
ITEM_PALETTES = {
    "quartz_dust": ("#4D3C53", "#A98FC0", "#E1D4E8", "#F5EAF6"),
    "silicon": ("#222222", "#575757", "#A0A0A0", "#D6D6D6"),
    "high_purity_silicon": ("#282828", "#797979", "#BEBEBE", "#EFEFEF"),
    "pcb": ("#173D38", "#22765D", "#4BC69B", "#F1B35C"),
    "integrated_circuit": ("#222B37", "#4D5B68", "#9BA9AC", "#F0B75F"),
    "cpu": ("#202A37", "#42515E", "#D4B569", "#FFF0B7"),
    "ram": ("#1C3530", "#427759", "#A4CF72", "#F5D26A"),
    "gpu": ("#202E42", "#3B5C86", "#63B7D2", "#F0B76A"),
    "industrial_ssd": ("#263342", "#4C6977", "#86B5AF", "#D9CA8A"),
    "industrial_ai_core": ("#182D38", "#287E78", "#5DD5C1", "#F3C36B"),
}

BLOCK_PALETTES = {
    "non_metal_processor": ("#4A4938", "#706C4E", "#A9A27A", "#34463D", "#88C7A0", "#E5C36F"),
    "electronic_fabricator": ("#243D4C", "#376C7C", "#72B7B5", "#23394E", "#72D4D0", "#E4B56C"),
    "machine_network_adapter": ("#34444B", "#627981", "#A2B6B6", "#193C48", "#59C4CE", "#F1C76D"),
    "industrial_network_switch": ("#303B43", "#546772", "#8BA0A0", "#1B3036", "#53C69E", "#E4B969"),
    "industrial_control_server": ("#30343A", "#555E64", "#9AA4A5", "#192D35", "#54C4AF", "#E4B66D"),
    "industrial_fiber": ("#201D36", "#433C70", "#8178C7", "#2D294D", "#A9A0FF", "#70D6E8"),
}

SILICON_DESCRIPTIONS = {
    "silicon": "深灰色硅碎晶，带银灰金属断面；由石英粉制成。",
    "high_purity_silicon": "银灰色完整硅晶体，明亮整洁的晶面用于区分高纯阶段。",
}


def rgba(hex_color: str) -> tuple[int, int, int, int]:
    value = hex_color.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def blank() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def write_png(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, format="PNG", optimize=False)


def item_icon(name: str, colors: tuple[str, ...]) -> Image.Image:
    dark, body, bright, glint = map(rgba, colors)
    im, d = blank()
    if name == "quartz_dust":
        return draw_dust(DUSTS[name])
    if name == "silicon":
        # Rough graphite-grey silicon fragments with broad metallic fracture planes.
        d.polygon([(5, 2), (9, 2), (11, 5), (9, 8), (4, 7), (3, 4)], fill=dark)
        d.polygon([(5, 3), (8, 3), (9, 5), (6, 7), (4, 5)], fill=bright)
        d.polygon([(8, 3), (10, 5), (8, 7), (6, 7)], fill=body)
        d.line((5, 3, 8, 3), fill=glint)
        d.polygon([(2, 8), (5, 5), (9, 5), (13, 8), (14, 11), (10, 14), (5, 13), (2, 11)], fill=dark)
        d.polygon([(3, 8), (6, 6), (9, 6), (8, 10), (3, 10)], fill=bright)
        d.polygon([(9, 6), (12, 8), (13, 11), (9, 12), (8, 10)], fill=body)
        d.polygon([(3, 10), (8, 10), (9, 12), (6, 12), (3, 11)], fill=body)
        d.line((4, 8, 6, 6, 8, 6), fill=glint)
        d.line((9, 8, 12, 9), fill=dark)
    elif name == "high_purity_silicon":
        # A single refined silver-grey crystal; purity is conveyed by cleaner facets.
        d.polygon([(2, 5), (7, 2), (12, 4), (14, 9), (11, 13), (5, 14), (2, 10)], fill=dark)
        d.polygon([(3, 5), (7, 3), (11, 5), (9, 8), (4, 9)], fill=bright)
        d.polygon([(11, 5), (13, 9), (10, 12), (9, 8)], fill=body)
        d.polygon([(4, 9), (9, 8), (10, 12), (6, 13), (3, 10)], fill=body)
        d.line((3, 5, 7, 3, 10, 4), fill=glint)
        d.line((4, 6, 7, 4), fill=glint)
        d.line((9, 8, 10, 11), fill=bright)
    elif name == "pcb":
        d.rectangle((2, 3, 13, 12), fill=dark)
        d.rectangle((3, 4, 12, 11), fill=body)
        d.rectangle((6, 6, 9, 9), fill=dark)
        d.rectangle((7, 7, 8, 8), fill=bright)
        for points in (((3, 5), (5, 5), (6, 6)), ((12, 5), (10, 5), (9, 6)), ((4, 10), (6, 10), (7, 9)), ((11, 10), (9, 10), (8, 9))):
            d.line(points, fill=glint, width=1)
        for xy in ((3, 6), (12, 9), (5, 11), (10, 4)):
            d.point(xy, fill=glint)
    elif name in ("integrated_circuit", "cpu", "ram", "gpu", "industrial_ssd", "industrial_ai_core"):
        if name == "ram":
            d.rectangle((1, 5, 14, 10), fill=dark)
            d.rectangle((2, 6, 13, 9), fill=body)
            for x in (3, 6, 9, 12):
                d.rectangle((x, 4, x + 1, 5), fill=glint)
                d.rectangle((x, 10, x + 1, 12), fill=glint)
            for x in (4, 7, 10):
                d.rectangle((x, 7, x + 1, 8), fill=bright)
        elif name in ("industrial_ssd", "gpu"):
            d.polygon([(2, 4), (11, 2), (14, 5), (13, 12), (4, 14), (2, 11)], fill=dark)
            d.polygon([(4, 5), (10, 4), (12, 6), (11, 10), (5, 12), (4, 10)], fill=body)
            if name == "gpu":
                d.ellipse((6, 6, 10, 10), fill=dark)
                d.ellipse((7, 7, 9, 9), fill=bright)
                d.line((3, 6, 5, 6), fill=glint)
                d.line((11, 11, 13, 11), fill=glint)
            else:
                d.rectangle((7, 6, 10, 9), fill=bright)
                d.line((3, 8, 6, 8), fill=glint)
                d.line((11, 7, 13, 7), fill=glint)
                d.line((11, 9, 13, 9), fill=glint)
        else:
            # Square package with pins on four sides and a central silicon die.
            d.rectangle((4, 3, 11, 12), fill=dark)
            d.rectangle((5, 4, 10, 11), fill=body)
            for x in (5, 7, 9, 11):
                d.line((x, 1, x, 3), fill=glint)
                d.line((x, 12, x, 14), fill=glint)
            for y in (5, 7, 9, 11):
                d.line((2, y, 4, y), fill=glint)
                d.line((11, y, 13, y), fill=glint)
            d.rectangle((6, 5, 9, 9), fill=bright if name == "industrial_ai_core" else dark)
            if name == "cpu":
                d.rectangle((7, 6, 8, 8), fill=glint)
            elif name == "integrated_circuit":
                d.point((7, 6), fill=glint)
                d.point((8, 8), fill=glint)
            else:
                d.rectangle((7, 6, 8, 8), fill=glint)
                d.point((6, 9), fill=bright)
    return im


def face_texture(name: str, face: str, palette: tuple[str, ...]) -> Image.Image:
    shell, mid, light, panel, signal, accent = map(rgba, palette)
    im = Image.new("RGBA", (16, 16), shell)
    d = ImageDraw.Draw(im)
    if face == "top":
        d.rectangle((0, 0, 15, 15), fill=mid)
        d.rectangle((2, 2, 13, 13), fill=shell)
        # Machine-specific top grilles, all within the standard cube.
        for y in (4, 6, 8, 10, 12):
            d.line((4, y, 11, y), fill=light)
            d.point((3, y), fill=accent)
        if name in ("industrial_network_switch", "industrial_control_server"):
            d.rectangle((1, 1, 2, 2), fill=accent)
    elif face == "side":
        d.rectangle((1, 1, 14, 14), fill=mid)
        d.rectangle((2, 2, 13, 13), fill=shell)
        if name == "non_metal_processor":
            for y in (4, 6, 8, 10, 12):
                d.rectangle((5, y, 12, y), fill=light)
            d.rectangle((2, 5, 3, 10), fill=accent)
        elif name == "electronic_fabricator":
            for x in (4, 6, 8, 10, 12):
                d.line((x, 3, x, 12), fill=light)
            d.line((3, 5, 12, 5), fill=accent)
        elif name == "machine_network_adapter":
            d.rectangle((5, 3, 10, 12), fill=panel)
            d.line((7, 4, 7, 10), fill=signal)
            d.line((8, 4, 8, 10), fill=signal)
        elif name == "industrial_network_switch":
            for y in (4, 7, 10):
                d.rectangle((3, y, 12, y + 1), fill=panel)
                d.rectangle((4, y, 5, y), fill=accent)
        elif name == "industrial_control_server":
            for y in (3, 6, 9, 12):
                d.rectangle((3, y, 12, y + 1), fill=panel)
                d.rectangle((4, y, 4, y), fill=signal)
        else:
            d.line((1, 8, 14, 8), fill=signal, width=2)
    else:  # front / north face
        d.rectangle((0, 0, 15, 15), fill=mid)
        d.rectangle((1, 1, 14, 14), fill=shell)
        if name == "non_metal_processor":
            # Chunky feed throat and removable dust tray.
            d.rectangle((3, 3, 12, 10), fill=panel)
            d.rectangle((5, 4, 10, 9), fill=shell)
            d.rectangle((6, 5, 9, 8), fill=signal)
            d.line((3, 11, 12, 11), fill=accent, width=2)
            d.rectangle((5, 13, 10, 14), fill=light)
        elif name == "electronic_fabricator":
            d.rectangle((2, 2, 13, 13), fill=panel)
            d.rectangle((4, 4, 11, 11), fill=shell)
            d.rectangle((6, 6, 9, 9), fill=signal)
            d.line((3, 5, 5, 5), fill=accent)
            d.line((10, 10, 12, 10), fill=accent)
            d.point((7, 7), fill=light)
        elif name == "machine_network_adapter":
            d.rectangle((3, 3, 12, 13), fill=panel)
            d.rectangle((5, 5, 10, 10), fill=shell)
            d.line((7, 5, 7, 9), fill=signal)
            d.line((8, 5, 8, 9), fill=signal)
            d.arc((4, 1, 11, 8), 205, 335, fill=accent)
            d.rectangle((7, 11, 8, 12), fill=accent)
        elif name == "industrial_network_switch":
            d.rectangle((2, 3, 13, 12), fill=panel)
            for i, x in enumerate((3, 5, 7, 9, 11)):
                d.rectangle((x, 6, x + 1, 9), fill=darken(signal, i))
                d.point((x, 4), fill=accent if i < 2 else light)
            d.line((3, 11, 12, 11), fill=light)
        elif name == "industrial_control_server":
            d.rectangle((3, 1, 12, 14), fill=panel)
            for y in (3, 6, 9, 12):
                d.rectangle((4, y, 11, y + 1), fill=shell)
                d.point((5, y), fill=signal)
                d.point((10, y), fill=accent)
        else:
            d.rectangle((1, 6, 14, 9), fill=panel)
            d.line((2, 7, 13, 7), fill=signal, width=2)
            d.rectangle((0, 5, 2, 10), fill=accent)
            d.rectangle((13, 5, 15, 10), fill=accent)
    return im


def darken(color: tuple[int, int, int, int], step: int) -> tuple[int, int, int, int]:
    factor = 0.95 - 0.11 * step
    return tuple(max(20, int(channel * factor)) for channel in color[:3]) + (255,)


def cube_model(name: str) -> dict:
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": f"ic2newera:block/{name}_side",
            "north": f"ic2newera:block/{name}_front",
            "south": f"ic2newera:block/{name}_side",
            "east": f"ic2newera:block/{name}_side",
            "west": f"ic2newera:block/{name}_side",
            "up": f"ic2newera:block/{name}_top",
            "down": f"ic2newera:block/{name}_side",
        },
        "elements": [{
            "from": [0, 0, 0], "to": [16, 16, 16],
            "faces": {
                "north": {"texture": "#north"}, "south": {"texture": "#south"},
                "east": {"texture": "#east"}, "west": {"texture": "#west"},
                "up": {"texture": "#up"}, "down": {"texture": "#down"},
            },
        }],
    }


def block_model(name: str) -> dict:
    if name != "industrial_fiber":
        return cube_model(name)
    # A glass-covered horizontal fiber trunk with chunky end couplers.
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": f"ic2newera:block/{name}"},
        "elements": [
            {"from": [0, 6, 6], "to": [16, 10, 10], "faces": {face: {"texture": "#particle"} for face in ("north", "south", "east", "west", "up", "down")}},
            {"from": [0, 5, 5], "to": [2, 11, 11], "faces": {face: {"texture": "#particle"} for face in ("north", "south", "east", "west", "up", "down")}},
            {"from": [14, 5, 5], "to": [16, 11, 11], "faces": {face: {"texture": "#particle"} for face in ("north", "south", "east", "west", "up", "down")}},
        ],
    }


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def update_catalog() -> None:
    catalog_path = ROOT / "art/model-catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8-sig"))
    models = catalog.setdefault("models", [])
    existing = {entry["id"] for entry in models}
    new_entries = [
        ("quartz_dust", "石英粉", "电子材料", "下界石英粉碎得到的硅工业基础材料。", "item"),
        ("silicon", "硅", "电子材料", SILICON_DESCRIPTIONS["silicon"], "item"),
        ("high_purity_silicon", "高纯硅", "电子材料", SILICON_DESCRIPTIONS["high_purity_silicon"], "item"),
        ("pcb", "印刷电路板", "电子材料", "电子元件装配使用的绿色电路基板。", "item"),
        ("integrated_circuit", "集成电路", "电子材料", "封装了精细导线的集成芯片。", "item"),
        ("cpu", "CPU", "电子材料", "工业服务器的处理器芯片。", "item"),
        ("ram", "RAM", "电子材料", "工业服务器使用的内存条。", "item"),
        ("gpu", "GPU", "电子材料", "工业 AI 核心使用的图形处理芯片。", "item"),
        ("industrial_ssd", "工业固态硬盘", "电子材料", "工业服务器使用的固态存储模块。", "item"),
        ("industrial_ai_core", "工业 AI 核心", "工业网络", "插入工业控制服务器的计算核心。", "item"),
        ("non_metal_processor", "非金属处理机", "工业机器", "前置粉料入口与下方输出托盘的非金属处理设备。", "block"),
        ("electronic_fabricator", "电子材料加工机", "工业机器", "具有独立芯片观察窗和电路指示的精密加工设备。", "block"),
        ("machine_network_adapter", "机器网络适配器", "工业网络", "带无线信号指示的机器数据网络接口。", "block"),
        ("industrial_fiber", "工业光纤", "工业网络", "带端部接头的透明紫色数据光纤。", "block"),
        ("industrial_network_switch", "工业网络交换机", "工业网络", "多端口链路灯展示的数据交换设备。", "block"),
        ("industrial_control_server", "工业控制服务器", "工业网络", "具有多层状态灯和存储托架的控制服务器。", "block"),
    ]
    for asset_id, name, category, description, kind in new_entries:
        if asset_id in existing:
            continue
        model_path = f"ic2newera/models/{kind}/{asset_id}.json"
        models.append({
            "id": asset_id,
            "name": name,
            "category": category,
            "status": "资源已补齐，待集成验收",
            "description": description,
            "assetRoot": "src/main/resources/assets",
            "path": model_path,
        })
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate() -> None:
    block_dir = ASSETS / "textures/block"
    item_dir = ASSETS / "textures/item"
    item_models = ASSETS / "models/item"
    block_models = ASSETS / "models/block"
    blockstates = ASSETS / "blockstates"

    for name, palette in ITEM_PALETTES.items():
        write_png(item_icon(name, palette), item_dir / f"{name}.png")
        write_json(item_models / f"{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"ic2newera:item/{name}"},
        })

    for name, palette in BLOCK_PALETTES.items():
        if name == "industrial_fiber":
            im, d = blank()
            d.rectangle((0, 5, 15, 10), fill=rgba(palette[0]))
            d.rectangle((0, 6, 15, 9), fill=rgba(palette[1]))
            d.line((1, 7, 14, 7), fill=rgba(palette[4]), width=1)
            for x in (1, 2, 13, 14):
                d.point((x, 8), fill=rgba(palette[5]))
            write_png(im, block_dir / f"{name}.png")
        else:
            for face in ("front", "side", "top"):
                write_png(face_texture(name, face, palette), block_dir / f"{name}_{face}.png")
        write_json(block_models / f"{name}.json", block_model(name))
        if name in ("non_metal_processor", "electronic_fabricator"):
            variants = {
                "facing=north": {"model": f"ic2newera:block/{name}"},
                "facing=east": {"model": f"ic2newera:block/{name}", "y": 90},
                "facing=south": {"model": f"ic2newera:block/{name}", "y": 180},
                "facing=west": {"model": f"ic2newera:block/{name}", "y": 270},
            }
        else:
            variants = {"": {"model": f"ic2newera:block/{name}"}}
        write_json(blockstates / f"{name}.json", {"variants": variants})
        write_json(item_models / f"{name}.json", {"parent": f"ic2newera:block/{name}"})

    # The control server consumes this item in its core slot.
    write_json(item_models / "industrial_ai_core.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "ic2newera:item/industrial_ai_core"},
    })
    write_png(item_icon("industrial_ai_core", ITEM_PALETTES["industrial_ai_core"]), item_dir / "industrial_ai_core.png")
    update_catalog()
    for name in ('non_metal_processor','electronic_fabricator','machine_network_adapter',
                 'industrial_network_switch','industrial_control_server'):
        write_device(name)


def generate_silicon_only() -> None:
    """Regenerate the two silicon icons without rewriting other device resources."""
    for name in SILICON_DESCRIPTIONS:
        write_png(item_icon(name, ITEM_PALETTES[name]), ASSETS / "textures/item" / f"{name}.png")
    catalog_path = ROOT / "art/model-catalog.json"
    previous = catalog_path.read_bytes().decode("utf-8-sig")
    catalog = json.loads(previous)
    for entry in catalog["models"]:
        if entry["id"] in SILICON_DESCRIPTIONS:
            entry["description"] = SILICON_DESCRIPTIONS[entry["id"]]
    content = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
    if "\r\n" in previous:
        content = content.replace("\n", "\r\n")
    catalog_path.write_bytes(content.encode("utf8"))
    print("Generated the two neutral-grey silicon icons and updated their catalog descriptions.")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--silicon-only", action="store_true", help="only regenerate silicon and high-purity silicon")
    args = parser.parse_args()
    if args.silicon_only:
        generate_silicon_only()
    else:
        generate()
        print("Generated nine electronic item icons, one AI core icon, six machine/network block models, and catalog entries.")
