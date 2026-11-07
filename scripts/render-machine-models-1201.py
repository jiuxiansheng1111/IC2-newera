"""Render the actual isolated-1.20.1 machine JSON and textures for review."""
from __future__ import annotations

import argparse
import json
import runpy
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera"
BASE = ROOT / "build-isolated/model-audit-20261030/renders"
PUBLISHED = ROOT / "art/modernization-2026-10-30/machine-models.png"
MACHINES = [
    ("alloy_smelter", "合金兼容炉"), ("advanced_electric_furnace", "高级熔炼兼容炉"),
    ("non_metal_processor", "非金属处理机"), ("electronic_fabricator", "电子材料加工机"),
    ("eu_motor", "初级EU电动机"), ("advanced_eu_motor", "高级EU电动机"),
    ("kinetic_generator", "初级机械发电机"), ("advanced_kinetic_generator", "高级机械发电机"),
    ("universal_transformer", "万能变压器"), ("machine_network_adapter", "机器网络适配器"),
    ("industrial_network_switch", "工业交换机"), ("industrial_control_server", "工业控制服务器"),
    ("wormhole_device", "虫洞装置")]


def prepare_renderer():
    module = runpy.run_path(str(ROOT / "art/sources/render-machine-preview.py"))
    render = module["render_model"]
    render.__globals__["ASSET_ROOT"] = ASSETS
    render.__globals__["BLOCK_TEXTURES"] = ASSETS / "textures/block"
    # Resolve the same IC2 textures used by the game. Keep native PNGs in the private render cache.
    cache = ROOT / "build-isolated/model-preview-cache/ic2/textures/block"
    model = json.loads((ASSETS / "models/block/universal_transformer.json").read_text("utf-8"))
    with ZipFile(ROOT / "build-isolated/migration-1201/dependencies/ic2-forge-2.10.33-ex120.jar") as ic2:
        for value in set(model["textures"].values()):
            if value.startswith("ic2:block/"):
                relative = Path(value.removeprefix("ic2:block/") + ".png")
                if relative.is_absolute() or ".." in relative.parts:
                    raise ValueError(f"Invalid block texture {value}")
                destination = cache / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(ic2.read("assets/ic2/textures/block/" + relative.as_posix()))
    render.__globals__["EXTERNAL_BLOCK_TEXTURES"] = {"ic2": cache}
    return render


def main(stage: str) -> None:
    render = prepare_renderer()
    destination = BASE / stage
    destination.mkdir(parents=True, exist_ok=True)
    for name, _ in MACHINES:
        for view, direction in (("north", (-1, .55, -1)), ("south", (1, .55, 1))):
            render(name, direction, 240, 220, 8.0).save(destination / f"{name}-{view}.png")
    if stage != "after":
        return
    mismatches = []
    before = BASE / "before"
    if before.is_dir():
        for name, _ in MACHINES:
            for view in ("north", "south"):
                f = f"{name}-{view}.png"
                if ImageChops.difference(Image.open(before / f).convert("RGBA"),
                                         Image.open(destination / f).convert("RGBA")).getbbox():
                    mismatches.append(f)
    columns, width, height = 4, 318, 265
    rows = (len(MACHINES) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * width, rows * height), "#17212b")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 15)
    for i, (name, label) in enumerate(MACHINES):
        x, y = (i % columns) * width, (i // columns) * height
        # View the marked/front side of the converter models; other blocks use their north front.
        view = "south" if name in {"eu_motor", "advanced_eu_motor", "kinetic_generator", "advanced_kinetic_generator"} else "north"
        rendered = Image.open(destination / f"{name}-{view}.png").convert("RGBA")
        sheet.alpha_composite(rendered, (x + 38, y + 4))
        draw.text((x + 18, y + 229), label, font=font, fill="#e2ece8")
    PUBLISHED.parent.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(PUBLISHED)
    (BASE / "comparison.json").write_text(json.dumps({"changedViews": mismatches, "count": len(MACHINES) * 2}, indent=2) + "\n", encoding="utf-8")
    print("Rendered", len(MACHINES), "models; visually changed views:", mismatches, "; preview:", PUBLISHED)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("before", "after"))
    main(parser.parse_args().stage)
