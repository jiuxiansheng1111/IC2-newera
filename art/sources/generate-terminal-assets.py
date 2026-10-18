"""Generate terminal models through the shared terminal-only device branch."""

from __future__ import annotations

import json
from pathlib import Path

from machine_visual_style import write_device


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate() -> None:
    # Geometry and dedicated PNGs come from the terminal branch in
    # machine_visual_style.device_model/surface. Keep orientation and drops local.
    write_device("terminal")
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


if __name__ == "__main__":
    generate()
    print("Generated the terminal block/item model, facing blockstate, loot table and dedicated textures.")
