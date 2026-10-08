#!/usr/bin/env python3
"""Generate original 16x16 ingot icons for the six approved alloy materials."""

from __future__ import annotations
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"

ALLOYS = {
    "stainless_steel_ingot": ("不锈钢锭", "#28323A", "#71838E", "#B8C6C7", "#EEF3E8", "stripe"),
    "invar_ingot": ("因瓦合金锭", "#33352C", "#77775A", "#B4AD7A", "#E7D59B", "bar"),
    "nichrome_ingot": ("镍铬合金锭", "#452D2C", "#A65C4B", "#E39C71", "#FFE0A0", "stripe"),
    "tool_steel_ingot": ("工具钢锭", "#202A33", "#4B5D6A", "#8799A0", "#D4E0DF", "bar"),
    "titanium_alloy_ingot": ("钛合金锭", "#183642", "#3D8091", "#84C5CE", "#E0FFF0", "stripe"),
    "tungsten_carbide_ingot": ("碳化钨锭", "#2A2637", "#605675", "#A28B91", "#E9C878", "bar"),
}


def color(hex_value: str) -> tuple[int, int, int, int]:
    value = hex_value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def make_ingot(palette: tuple[str, ...], pattern: str) -> Image.Image:
    outline, shadow, base, highlight = map(color, palette)
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Beveled, slanted metal bar. Silhouette and rim stay consistent with the core metals.
    rows = {4: (8, 10), 5: (6, 12), 6: (4, 14), 7: (3, 13), 8: (2, 12), 9: (3, 11), 10: (4, 10), 11: (5, 9)}
    for y, (left, right) in rows.items():
        draw.line((left, y, right, y), fill=outline)
    for y in range(6, 11):
        left, right = rows[y]
        draw.line((left + 1, y, right - 1, y), fill=base)
    draw.line((7, 5, 11, 5), fill=highlight)
    draw.point((6, 5), fill=highlight)
    draw.line((5, 6, 8, 6), fill=highlight)
    draw.point((4, 7), fill=highlight)
    draw.line((4, 8, 7, 8), fill=shadow)
    draw.line((8, 9, 11, 9), fill=outline)
    draw.line((6, 10, 9, 10), fill=outline)
    if pattern == "stripe":
        # Distinct alloy tempering seam that follows the diagonal bar.
        for point in ((10, 6), (9, 7), (8, 8), (7, 9)):
            draw.point(point, fill=highlight)
        draw.point((11, 6), fill=shadow)
    else:
        # Two restrained stamped facets distinguish the heavier carbide / tooling ingots.
        draw.line((9, 6, 11, 6), fill=shadow)
        draw.line((5, 9, 7, 9), fill=highlight)
    return image


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def update_catalog() -> None:
    catalog_path = ROOT / "art/model-catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8-sig"))
    entries = catalog.setdefault("models", [])
    known = {entry["id"] for entry in entries}
    for item_id, (name, *_palette) in ALLOYS.items():
        if item_id in known:
            continue
        entries.append({
            "id": item_id,
            "name": name,
            "category": "合金材料",
            "status": "资源已补齐，待集成验收",
            "description": "ProcessGraph 所列合金路线的金属锭图标。",
            "assetRoot": "src/main/resources/assets",
            "path": f"ic2newera/models/item/{item_id}.json",
        })
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate() -> None:
    for item_id, (name, *palette_and_pattern) in ALLOYS.items():
        palette = tuple(palette_and_pattern[:4])
        pattern = palette_and_pattern[4]
        image = make_ingot(palette, pattern)
        texture = ASSETS / "textures/item" / f"{item_id}.png"
        texture.parent.mkdir(parents=True, exist_ok=True)
        image.save(texture, format="PNG", optimize=False)
        write_json(ASSETS / "models/item" / f"{item_id}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"ic2newera:item/{item_id}"},
        })
    update_catalog()


if __name__ == "__main__":
    generate()
    print("Generated six original alloy ingots, item models, and catalog entries.")
