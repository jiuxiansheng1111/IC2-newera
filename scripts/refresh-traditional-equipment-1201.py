"""Recolour verified 1.20.1 vanilla silhouettes for New Era materials and equipment.

Only the isolated 1.20.1 project is changed. Sword assets are deliberately excluded.
Minecraft source textures are read from the local official client and are not copied
into the public source tree. The generated art remains a Minecraft-derived asset.
"""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import shutil
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera"
OUT = ROOT / "art/modernization-2026-10-30"
BACKUP = ROOT / "build-isolated/model-audit-20261030/before"
CLIENT = Path.home() / ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client.jar"
HASHES = {
    "iron_helmet": "db530074cf0a2b1e6d6958f66536625764f47e00e3c1cc9f558586c24a5cf9dc",
    "iron_chestplate": "028d1a738128c5622471a9d1b94f00b49a696e7c4fac3dfccd07272e50021f71",
    "iron_leggings": "64fee8e18c943e5131041b6cd560c33e2d512f3a56f99cbc4a1c545479ccba2c",
    "iron_boots": "e4a500c023d7a54a63810b97187de55503960298a225bd64f68e18253e408a14",
    "iron_pickaxe": "67305d8bd14e1d60633258f52055fce5aeaea7837c10e62d436fc16f163be627",
    "iron_axe": "8dea40bac06c6f14bb0ad9e8b47de63250f6d6a46ae9439b85ddd1377f1edb49",
    "iron_shovel": "c9d36d59ec53ebc631bd24930f62087c316eef39bd237d8bb69cb2bb629dfae5",
    "iron_hoe": "4ed88a87c141168b4552041174e83105e5d5825ea9b96836dd4869c674848d69",
    "iron_layer_1": "b47c7aec22247e30f2a1a0e570c490d58915aa7f1133a8b35be6cf39e65cce53",
    "iron_layer_2": "677f9481319b47bf58685017e437e0aba1047e5b1ca339ba95c7c65a15a993be",
}

INGOTS = {
    "vanadium": ("#283335", "#596C68", "#9AADA2", "#E0E7D8"),
    "niobium": ("#2D303D", "#626C7D", "#A9B7C4", "#E7EBE9"),
    "vanadium_steel": ("#293631", "#5A7563", "#9BB8A0", "#DDEAD6"),
    "niobium_titanium": ("#30323D", "#6A7080", "#ADB8C5", "#E8E9ED"),
    "cobalt": ("#1F3042", "#476A86", "#83A9BC", "#D8E8E9"),
    "molybdenum": ("#2E3231", "#68726C", "#ABB6AA", "#E6E8D9"),
    "tantalum": ("#2D2C37", "#666472", "#AAAAB7", "#E8E1E7"),
}
ARMOR = {
    "vanadium_steel": (INGOTS["vanadium_steel"], "#C5A769"),
    "niobium_titanium": (INGOTS["niobium_titanium"], "#8BA5C2"),
    "magnetic_armor": (("#203239", "#496E78", "#8EADB5", "#DCEBE3"), "#C78E4D"),
    "resonance_armor": (("#302F3D", "#6C6A7F", "#B5B9CC", "#ECEAF2"), "#9E91C7"),
}


def rgb(value: str) -> tuple[int, int, int]:
    return tuple(int(value[i:i + 2], 16) for i in (1, 3, 5))


def blend(level: int, palette: tuple[str, ...]) -> tuple[int, int, int]:
    colors = [rgb(p) for p in palette]
    anchors = (24, 107, 198, 255)
    v = min(255, max(24, level))
    i = next((i for i in range(3) if v <= anchors[i + 1]), 2)
    f = (v - anchors[i]) / (anchors[i + 1] - anchors[i])
    return tuple(round(colors[i][c] * (1 - f) + colors[i + 1][c] * f) for c in range(3))


def tinted(source: Image.Image, palette: tuple[str, ...]) -> Image.Image:
    im = source.convert("RGBA")
    result = im.copy()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = im.getpixel((x, y))
            if a and r == g == b:
                result.putpixel((x, y), (*blend(r, palette), a))
    return result


def accent(im: Image.Image, points: list[tuple[int, int]], color: str) -> None:
    for x, y in points:
        if im.getpixel((x, y))[3] != 0:
            im.putpixel((x, y), (*rgb(color), 255))


def backup(path: Path) -> None:
    dest = BACKUP / path.relative_to(ASSETS)
    if path.exists() and not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)


def main() -> None:
    style_spec = importlib.util.spec_from_file_location("material_pixel_style", ROOT / "art/sources/material_pixel_style.py")
    if style_spec is None or style_spec.loader is None:
        raise RuntimeError("Missing checked-in iron-ingot pixel reference")
    style = importlib.util.module_from_spec(style_spec)
    style_spec.loader.exec_module(style)
    originals: dict[str, Image.Image] = {}
    with ZipFile(CLIENT) as client:
        for name, expected in HASHES.items():
            directory = "models/armor" if "layer" in name else "item"
            raw = client.read(f"assets/minecraft/textures/{directory}/{name}.png")
            if hashlib.sha256(raw).hexdigest() != expected:
                raise ValueError("Unexpected 1.20.1 vanilla texture: " + name)
            originals[name] = Image.open(io.BytesIO(raw)).convert("RGBA")
    swords = {p: hashlib.sha256(p.read_bytes()).hexdigest()
              for p in (ASSETS / "textures/item").glob("*sword.png")}
    icons: list[tuple[str, Image.Image]] = []
    records = []
    for name, palette in INGOTS.items():
        icon = style.draw_ingot(palette)
        path = ASSETS / "textures/item" / f"{name}_ingot.png"
        model = ASSETS / "models/item" / f"{name}_ingot.json"
        backup(path)
        icon.save(path)
        if json.loads(model.read_text(encoding="utf-8"))["parent"] != "minecraft:item/iron_ingot":
            raise ValueError("Ingot model no longer inherits vanilla iron: " + name)
        icons.append((name + "_ingot", icon))
        records.append(path.relative_to(ASSETS).as_posix())
    for name, (palette, detail) in ARMOR.items():
        for kind in ("helmet", "chestplate", "leggings", "boots"):
            icon = tinted(originals["iron_" + kind], palette)
            marks = {"helmet": [(5, 6), (10, 6)], "chestplate": [(6, 8), (9, 8), (7, 10), (8, 10)],
                     "leggings": [(4, 5), (11, 5)], "boots": [(4, 7), (11, 7)]}[kind]
            if name == "magnetic_armor" and kind == "chestplate":
                marks = [(5, 8), (6, 8), (9, 8), (10, 8), (5, 10), (10, 10)]
            if name == "resonance_armor" and kind == "chestplate":
                marks = [(7, 7), (6, 8), (8, 8), (7, 9)]
            accent(icon, marks, detail)
            path = ASSETS / "textures/item" / f"{name}_{kind}.png"
            backup(path)
            icon.save(path)
            icons.append((name + "_" + kind, icon))
            records.append(path.relative_to(ASSETS).as_posix())
        for layer in (1, 2):
            texture = tinted(originals[f"iron_layer_{layer}"], palette)
            marks = [(9, 9), (14, 9), (21, 24), (26, 24)] if layer == 1 else [(20, 28), (27, 28)]
            if name == "magnetic_armor" and layer == 1:
                marks += [(21, 25), (22, 25), (25, 25), (26, 25)]
            if name == "resonance_armor" and layer == 1:
                marks += [(23, 23), (22, 24), (24, 24), (23, 25)]
            accent(texture, marks, detail)
            path = ASSETS / "textures/models/armor" / f"{name}_layer_{layer}.png"
            backup(path)
            texture.save(path)
            records.append(path.relative_to(ASSETS).as_posix())
        if name in ("vanadium_steel", "niobium_titanium"):
            for kind in ("pickaxe", "axe", "shovel", "hoe"):
                icon = tinted(originals["iron_" + kind], palette)
                path = ASSETS / "textures/item" / f"{name}_{kind}.png"
                backup(path)
                icon.save(path)
                icons.append((name + "_" + kind, icon))
                records.append(path.relative_to(ASSETS).as_posix())
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in swords.items()):
        raise ValueError("Sword appearance was modified")
    OUT.mkdir(parents=True, exist_ok=True)
    cols, cell_w, cell_h = 7, 160, 155
    canvas = Image.new("RGBA", (cols * cell_w, ((len(icons) + cols - 1) // cols) * cell_h), "#182129")
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 13)
    chinese = json.loads((ASSETS / "lang/zh_cn.json").read_text(encoding="utf-8"))
    for i, (name, icon) in enumerate(icons):
        x, y = (i % cols) * cell_w, (i // cols) * cell_h
        enlarged = icon.resize((112, 112), Image.Resampling.NEAREST)
        canvas.alpha_composite(enlarged, (x + 24, y + 4))
        label = chinese.get("item.ic2newera." + name, name)
        draw.text((x + 5, y + 122), label, font=font, fill="#e0e9e5")
    canvas.convert("RGB").save(OUT / "traditional-materials-and-equipment.png")
    (OUT / "index.json").write_text(json.dumps({"minecraftVersion": "1.20.1", "generator": "scripts/refresh-traditional-equipment-1201.py",
        "vanillaInputHashes": HASHES, "modifiedAssets": records, "swordAssetsChanged": False,
        "preview": "traditional-materials-and-equipment.png", "runtimeVerified": False}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Updated", len(records), "assets; swords unchanged; preview", OUT / "traditional-materials-and-equipment.png")


if __name__ == "__main__":
    main()
