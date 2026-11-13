#!/usr/bin/env python3
"""Generate the first-party cuboid notice-board model and facing blockstate."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera"

TEXTURES = {
    "shell": "minecraft:block/deepslate_tiles",
    "metal": "minecraft:block/iron_block",
    "copper": "minecraft:block/copper_block",
    "screen": "minecraft:block/black_concrete",
    "cyan": "minecraft:block/light_blue_concrete",
    "green": "minecraft:block/lime_concrete",
    "amber": "minecraft:block/orange_concrete",
    "red": "minecraft:block/red_concrete",
    "paper": "minecraft:block/white_concrete",
}


def element(x1, y1, z1, x2, y2, z2, texture, *, north=None, south=None, up=None):
    faces = {face: {"texture": "#" + texture} for face in ("north", "south", "east", "west", "up", "down")}
    if north:
        faces["north"] = {"texture": "#" + north}
    if south:
        faces["south"] = {"texture": "#" + south}
    if up:
        faces["up"] = {"texture": "#" + up}
    return {"from": [x1, y1, z1], "to": [x2, y2, z2], "shade": True, "faces": faces}


def write(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


elements = [
    # Weighted iron foot and copper trim.
    element(1, 0, 3, 15, 2, 15, "shell", up="metal"),
    element(2, 2, 4, 14, 3, 14, "copper"),
    element(4, 3, 7, 12, 5, 13, "shell"),
    element(6, 4, 8, 10, 9, 13, "metal"),
    element(7, 4, 6, 9, 7, 8, "copper"),
    # Distinct raised display housing, glass face, and pixel status strip.
    element(1, 6, 2, 15, 16, 8, "shell", north="metal", south="shell", up="metal"),
    element(2, 7, 1.6, 14, 15, 2.1, "metal", north="screen"),
    element(3, 8, 1.3, 13, 14, 1.6, "screen", north="screen"),
    element(4, 9, 1.1, 7, 10, 1.3, "green", north="green"),
    element(8, 9, 1.1, 12, 10, 1.3, "cyan", north="cyan"),
    element(4, 11, 1.1, 11, 11.8, 1.3, "cyan", north="cyan"),
    element(4, 12.2, 1.1, 9, 13, 1.3, "green", north="green"),
    element(10, 12.2, 1.1, 12, 13, 1.3, "amber", north="amber"),
    # Service slot and tactile controls on the lower front.
    element(3, 3.2, 3.6, 13, 4.4, 4.0, "screen", north="screen"),
    element(4, 3.4, 4.0, 6, 4.2, 4.2, "amber", north="amber"),
    element(8, 3.3, 4.0, 9, 4.1, 4.2, "cyan", north="cyan"),
    element(10, 3.3, 4.0, 12, 4.1, 4.2, "green", north="green"),
    # Side rails, corner fasteners, and top antenna rails make the unit dimensional.
    element(0.5, 7, 3, 1.4, 15, 7, "metal"),
    element(14.6, 7, 3, 15.5, 15, 7, "metal"),
    element(2, 15.5, 3, 14, 16, 7, "copper"),
    element(3, 14.7, 1.1, 4, 15.2, 1.5, "amber"),
    element(12, 14.7, 1.1, 13, 15.2, 1.5, "red"),
    element(2, 1.2, 2.5, 3, 1.8, 3.0, "metal"),
    element(13, 1.2, 2.5, 14, 1.8, 3.0, "metal"),
]

write(ASSETS / "models/block/field_notice_board.json", {
    "parent": "minecraft:block/block",
    "textures": {**TEXTURES, "particle": TEXTURES["shell"]},
    "elements": elements,
})
write(ASSETS / "models/item/field_notice_board.json", {
    "parent": "ic2newera:block/field_notice_board",
})
write(ASSETS / "blockstates/field_notice_board.json", {
    "variants": {
        "facing=north": {"model": "ic2newera:block/field_notice_board"},
        "facing=east": {"model": "ic2newera:block/field_notice_board", "y": 90},
        "facing=south": {"model": "ic2newera:block/field_notice_board", "y": 180},
        "facing=west": {"model": "ic2newera:block/field_notice_board", "y": 270},
    }
})
