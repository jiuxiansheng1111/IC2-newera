"""Give four 1.20.1 processors the installed IC2R casing and one small original badge.

Read model metadata only. The game's resource manager resolves upstream texture IDs,
so the appearance follows IC2 and compatible resource packs without copying artwork.
"""
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "build-isolated/migration-1201"
ASSETS = MIGRATION / "newera/src/main/resources/assets/ic2newera"
IC2 = MIGRATION / "dependencies/ic2-forge-2.10.33-ex120.jar"
FACES = ("down", "up", "north", "south", "west", "east")
# From left to right within each mark: two ingots, three windings, sieve holes and a chip.
DEVICES = {
    "alloy_smelter": ("basic/electric_furnace", "alloy", ("aa.aa", "bb.bb", "cc.cc"), ("ebac65", "ce8b4b", "9a5f37")),
    "advanced_electric_furnace": ("advanced/induction_furnace", "coil", ("a.a.a", "b.b.b", "cbcbc"), ("edca76", "bd954e", "876a44")),
    "non_metal_processor": ("basic/macerator", "sieve", ("aaaaa", "bcbcb", "aaaaa"), ("9fb9bd", "556c73", "e2e8e4")),
    "electronic_fabricator": ("basic/compressor", "chip", (".a.a.", "bcbcb", ".a.a."), ("a1c29f", "507d6a", "8bb696")),
}


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def original_faces(archive, machine, suffix):
    name = f"assets/ic2/models/block/machine/processing/{machine}{suffix}.json"
    model = json.loads(archive.read(name))
    tex = model["textures"]
    if model["parent"].split(":")[-1] == "block/cube":
        return {key: tex[key] for key in ("particle", *FACES)}
    if model["parent"].split(":")[-1] == "block/orientable_with_bottom":
        return dict(particle=tex["particle"], north=tex["front"], up=tex["top"], down=tex["bottom"],
                    south=tex["side"], east=tex["side"], west=tex["side"])
    raise ValueError(f"Unsupported IC2 model parent for {name}")


def make_badge(name, rows, colors):
    palette = dict(zip(".abc", ("c6c9c6", *colors)))
    # Extend border texels; mipmaps can sample past the 5x3 UV rectangle.
    image = Image.new("RGB", (16, 16))
    for y in range(16):
        for x in range(16):
            color = palette[rows[max(0, min(2, y - 6))][max(0, min(4, x - 5))]]
            image.putpixel((x, y), tuple(bytes.fromhex(color)))
    image.save(ASSETS / f"textures/block/ic2newera_{name}_badge.png")


def model(textures, badge):
    elements = [{"from": [0, 0, 0], "to": [16, 16, 16],
                 "faces": {face: {"texture": f"#{face}", "cullface": face} for face in FACES if face != "north"}}]
    x0, x1, y0, y1 = 2, 7, 11, 14
    # Each surface texel is covered exactly once. Vanilla's default north UV is
    # relative to the whole block and keeps the remaining texture continuous.
    for left, right, bottom, top, mark in (
        (0, 16, 0, y0, False), (0, 16, y1, 16, False),
        (0, x0, y0, y1, False), (x1, 16, y0, y1, False), (x0, x1, y0, y1, True),
    ):
        face = {"texture": "#badge" if mark else "#north", "cullface": "north"}
        if mark:
            face["uv"] = [5, 6, 10, 9]
        elements.append({"from": [left, bottom, 0], "to": [right, top, 0], "faces": {"north": face}})
    return {"parent": "minecraft:block/block", "textures": {**textures, "badge": f"ic2newera:block/ic2newera_{badge}_badge"},
            "elements": elements}


def main():
    with ZipFile(IC2) as archive:
        for device, (machine, badge, pixels, palette) in DEVICES.items():
            make_badge(badge, pixels, palette)
            variants = {}
            for lit, suffix in ((False, ""), (True, "_active")):
                name = device + suffix
                write_json(ASSETS / f"models/block/{name}.json", model(original_faces(archive, machine, suffix), badge))
                for turn, facing in enumerate(("north", "east", "south", "west")):
                    key = f"facing={facing},lit={str(lit).lower()}"
                    variants[key] = {"model": f"ic2newera:block/{name}", **({"y": turn * 90} if turn else {})}
            write_json(ASSETS / f"blockstates/{device}.json", {"variants": variants})


if __name__ == "__main__":
    main()
