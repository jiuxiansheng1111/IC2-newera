"""Remove fully concealed cuboid faces from actual 1.20.1 machine models.

The pass only removes faces covered by a single six-sided, fully opaque,
unrotated cuboid. It does not change visible geometry, textures or rotations.
Run after the power-converter model generator.
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera"
BACKUP = ROOT / "build-isolated/model-audit-20261030/machine-models-before"
OUT = ROOT / "art/modernization-2026-10-30/machine-model-audit.json"
MACHINES = ("alloy_smelter", "advanced_electric_furnace", "non_metal_processor", "electronic_fabricator",
            "eu_motor", "advanced_eu_motor", "kinetic_generator", "advanced_kinetic_generator", "universal_transformer",
            "machine_network_adapter", "industrial_network_switch", "industrial_control_server", "wormhole_device")
FACES = ("north", "south", "west", "east", "up", "down")


def texture(model: dict, ref: str) -> Path | None:
    seen: set[str] = set()
    while ref.startswith("#"):
        key = ref[1:]
        if key in seen:
            return None
        seen.add(key)
        ref = model.get("textures", {}).get(key, "")
    prefix = "ic2newera:block/"
    if not ref.startswith(prefix):
        return None
    return ASSETS / "textures/block" / (ref[len(prefix):] + ".png")


def opaque_cuboid(model: dict, element: dict, cache: dict[Path, bool]) -> bool:
    if element.get("rotation") or set(element.get("faces", {})) != set(FACES):
        return False
    for face in element["faces"].values():
        path = texture(model, face.get("texture", ""))
        if path is None or not path.is_file():
            return False
        if path not in cache:
            with Image.open(path) as image:
                cache[path] = image.convert("RGBA").getchannel("A").getextrema() == (255, 255)
        if not cache[path]:
            return False
    return True


def hidden(a: dict, b: dict, face: str) -> bool:
    if a.get("rotation"):
        return False
    lo, hi, blo, bhi = a["from"], a["to"], b["from"], b["to"]
    axis, is_min = {"west": (0, True), "east": (0, False), "down": (1, True),
                    "up": (1, False), "north": (2, True), "south": (2, False)}[face]
    for i in range(3):
        if i != axis and not (blo[i] <= lo[i] and bhi[i] >= hi[i]):
            return False
    boundary = lo[axis] if is_min else hi[axis]
    return (blo[axis] < boundary <= bhi[axis]) if is_min else (blo[axis] <= boundary < bhi[axis])


def main(apply: bool) -> None:
    report = []
    cache: dict[Path, bool] = {}
    for name in MACHINES:
        path = ASSETS / "models/block" / (name + ".json")
        model = json.loads(path.read_text(encoding="utf-8"))
        elements = model.get("elements", [])
        for element in elements:
            if len(element.get("from", [])) != 3 or len(element.get("to", [])) != 3:
                raise ValueError("Invalid cuboid: " + name)
            if any(x < 0 or x > 16 for x in element["from"] + element["to"]):
                raise ValueError("Model leaves its block: " + name)
        opaque = [opaque_cuboid(model, element, cache) for element in elements]
        removed = []
        for i, element in enumerate(elements):
            for face in list(element.get("faces", {})):
                if any(j != i and opaque[j] and hidden(element, candidate, face)
                       for j, candidate in enumerate(elements)):
                    removed.append((i, face))
        if apply and removed:
            BACKUP.mkdir(parents=True, exist_ok=True)
            original = BACKUP / path.name
            if not original.exists():
                shutil.copy2(path, original)
            for i, face in removed:
                del elements[i]["faces"][face]
            model["elements"] = [element for element in elements if element.get("faces")]
            path.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        report.append({"model": name, "cuboids": len(elements), "fullyHiddenFacesRemoved": len(removed)})
    if apply:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps({"generator": "scripts/optimize-machine-models-1201.py", "models": report,
                                  "runtimeVerified": False}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    main(parser.parse_args().apply)
