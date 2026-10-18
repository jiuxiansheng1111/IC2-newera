#!/usr/bin/env python3
"""Generate original 16x16 core mineral ore and material icons."""

from __future__ import annotations

import json
import random
from pathlib import Path

from PIL import Image, ImageDraw
from material_pixel_style import draw_ingot as metal_ingot, draw_dust as metal_dust


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"
BLOCK_MODELS = ASSETS / "models/block"
ITEM_MODELS = ASSETS / "models/item"
BLOCK_TEXTURES = ASSETS / "textures/block"
ITEM_TEXTURES = ASSETS / "textures/item"

# Each metal has its own hue family and value range so all three stages remain
# distinguishable at inventory-icon scale. Colors and pixel shapes are authored here.
MATERIALS = {
    "nickel": {
        "ore": ("#36463E", "#819287", "#CBD9C6", "#E9E9CB"),
        "dust": ("#304037", "#7E9381", "#B8C8A7", "#E8E8C9"),
        "ingot": ("#273A32", "#809384", "#C4D1C1", "#F0EFD6"),
        "impure": ("#28352E", "#718372", "#AFB99B", "#E0DFBF"),
    },
    "chromium": {
        "ore": ("#3C2528", "#9E4C48", "#E98367", "#F6C49A"),
        "dust": ("#37424A", "#939CA7", "#D3DEE5", "#F4FAFF"),
        "ingot": ("#303D48", "#929DAA", "#D6E2EC", "#FAFCFF"),
        "impure": ("#383638", "#7D8187", "#B5B6BB", "#D9DADD"),
    },
    "titanium": {
        "ore": ("#203745", "#3E748A", "#76B6C6", "#C1E9E8"),
        "dust": ("#203441", "#477E93", "#79B7C5", "#C8E8E1"),
        "ingot": ("#1D3341", "#44768E", "#8EC3D0", "#D5F0EB"),
        "impure": ("#1D303D", "#3C7083", "#6FA9B5", "#B8D8D4"),
    },
    "tungsten": {
        "ore": ("#302E43", "#625B7A", "#A68CB0", "#E4C67B"),
        "dust": ("#302D41", "#665D7F", "#A58DB1", "#E8C875"),
        "ingot": ("#29283B", "#5E5879", "#A08CB1", "#F0D183"),
        "impure": ("#282638", "#554F6D", "#8A789C", "#D9B968"),
    },
}


def rgb(value: str) -> tuple[int, int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4)) + (255,)


def new_icon() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def draw_ore(material: str, palette: tuple[str, ...]) -> Image.Image:
    image = Image.new("RGBA", (16, 16))
    draw = ImageDraw.Draw(image)
    rock = ("#494A46", "#555650", "#62615A", "#3C3E3B")
    rng = random.Random(4301 + list(MATERIALS).index(material) * 101)
    for y in range(16):
        for x in range(16):
            shade = rock[rng.choices((0, 1, 2, 3), (5, 4, 2, 2))[0]]
            draw.point((x, y), fill=rgb(shade))

    # Broken angular seams, not a repeated copied vanilla ore pattern.
    clusters = {
        "nickel": [(2, 3), (10, 2), (6, 8), (12, 11), (2, 13)],
        "chromium": [(4, 2), (11, 4), (2, 8), (8, 10), (13, 13)],
        "titanium": [(2, 4), (8, 2), (12, 7), (4, 10), (10, 13)],
        "tungsten": [(4, 3), (12, 3), (7, 7), (2, 11), (12, 12)],
    }[material]
    outline, body, light, glint = map(rgb, palette)
    for index, (cx, cy) in enumerate(clusters):
        pixels = [(0, 0), (1, 0), (2, 0), (-1, 1), (0, 1), (1, 1), (0, 2)]
        if index % 2:
            pixels = [(dy, dx) for dx, dy in pixels]
        for dx, dy in pixels:
            x, y = cx + dx, cy + dy
            if 0 <= x < 16 and 0 <= y < 16:
                draw.point((x, y), fill=outline)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            x, y = cx + dx, cy + dy
            if 0 <= x < 16 and 0 <= y < 16:
                draw.point((x, y), fill=body)
        if 0 <= cx < 16 and 0 <= cy < 16:
            draw.point((cx, cy), fill=light)
        if index in (1, 3) and 0 <= cx + 1 < 16 and 0 <= cy < 16:
            draw.point((cx + 1, cy), fill=glint)
    # A few chipped seams bind the inclusions into the stone.
    for x, y in ((5, 4), (9, 5), (4, 9), (10, 10), (7, 14)):
        if image.getpixel((x, y))[0] > 80:
            draw.point((x, y), fill=rgb(palette[1]))
    return image


def draw_dust(palette: tuple[str, ...], impure: bool, seed: int) -> Image.Image:
    return metal_dust(palette, impure, seed)


def draw_ingot(palette: tuple[str, ...]) -> Image.Image:
    return metal_ingot(palette)


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def save_image(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=False)


def generate() -> None:
    BLOCK_MODELS.mkdir(parents=True, exist_ok=True)
    ITEM_MODELS.mkdir(parents=True, exist_ok=True)
    for index, (material, palettes) in enumerate(MATERIALS.items()):
        ore_id = f"{material}_ore"
        save_image(draw_ore(material, palettes["ore"]), BLOCK_TEXTURES / f"{ore_id}.png")
        write_json(BLOCK_MODELS / f"{ore_id}.json", {
            "parent": "minecraft:block/cube_all",
            "textures": {"all": f"ic2newera:block/{ore_id}"},
        })
        write_json(ITEM_MODELS / f"{ore_id}.json", {"parent": f"ic2newera:block/{ore_id}"})

        for suffix, kind in (("dust", "dust"), ("impure_dust", "impure"), ("ingot", "ingot")):
            item_id = f"impure_{material}_dust" if suffix == "impure_dust" else f"{material}_{suffix}"
            palette = palettes[kind]
            image = draw_dust(palette, suffix == "impure_dust", 700 + index) if "dust" in suffix else draw_ingot(palette)
            save_image(image, ITEM_TEXTURES / f"{item_id}.png")
            write_json(ITEM_MODELS / f"{item_id}.json", {
                "parent": "minecraft:item/iron_ingot" if suffix == "ingot" else "minecraft:item/generated",
                "textures": {"layer0": f"ic2newera:item/{item_id}"},
            })


if __name__ == "__main__":
    generate()
    print("Generated 4 ore blocks and 12 original 16x16 dust/ingot icons.")
