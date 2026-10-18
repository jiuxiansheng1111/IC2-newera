#!/usr/bin/env python3
"""Generate vanilla-proportioned 16x16 ingots for the six approved alloys."""

from __future__ import annotations
import json
from pathlib import Path
from PIL import Image
from material_pixel_style import draw_ingot

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


def make_ingot(palette: tuple[str, ...], pattern: str) -> Image.Image:
    return draw_ingot(palette)


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
            "parent": "minecraft:item/iron_ingot",
            "textures": {"layer0": f"ic2newera:item/{item_id}"},
        })
    update_catalog()


if __name__ == "__main__":
    generate()
    print("Generated six original alloy ingots, item models, and catalog entries.")
