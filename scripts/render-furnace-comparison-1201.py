"""Render the installed source models next to the r66 machines. Keep comparison images local."""
import json
import runpy
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "build-isolated/player-packaging/20261108-r66-memory-furnaces"
generator = runpy.run_path(str(ROOT / "art/sources/generate-industrial-furnace-models.py"))
render = runpy.run_path(str(ROOT / "art/sources/render-machine-preview.py"))["render_model"]
names = (("alloy_smelter", "合金炉", "电炉"), ("advanced_electric_furnace", "高级电炉", "感应炉"),
         ("non_metal_processor", "非金属处理机", "粉碎机"), ("electronic_fabricator", "电子材料加工机", "压缩机"))
font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 19)
small = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 15)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory() as temp, ZipFile(generator["IC2"]) as ic2:
        cache = Path(temp)
        (cache / "models/block").mkdir(parents=True)
        sheet = Image.new("RGB", (694, 764), "#202930")
        draw = ImageDraw.Draw(sheet)
        for i, title in enumerate(("IC2 原机", "新纪元 · 待机", "新纪元 · 工作")):
            draw.text((178 + i * 190, 15), title, font=font, fill="#e8f0ee", anchor="mm")
        for i, (new, name, reference) in enumerate(names):
            base = generator["DEVICES"][new][0]
            ref = "original_" + new
            for suffix in ("", "_active"):
                mapping = generator["original_faces"](ic2, base, suffix)
                for value in set(mapping.values()):
                    rel = value.removeprefix("ic2:block/") + ".png"
                    target = cache / "textures/block" / rel
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(ic2.read("assets/ic2/textures/block/" + rel))
                if not suffix:
                    model = {"textures": mapping, "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                             "faces": {face: {"texture": "#" + face} for face in generator["FACES"]}}]}
                    (cache / f"models/block/{ref}.json").write_text(json.dumps(model), "utf8")
            y = 31 + i * 182
            draw.line((12, y, 682, y), fill="#3a494e")
            draw.multiline_text((12, y + 63), (name[:4] + "\n" + name[4:]) if len(name) > 5 else name, font=small, fill="#ebd3a4")
            draw.text((12, y + 113), reference, font=small, fill="#b6c5c6")
            for col, key in enumerate((ref, new, new + "_active")):
                path = cache if col == 0 else generator["ASSETS"]
                render.__globals__.update(ASSET_ROOT=path, BLOCK_TEXTURES=path / "textures/block",
                                           EXTERNAL_BLOCK_TEXTURES={"ic2": cache / "textures/block"})
                im = render(key, (-1, .55, -1), 190, 165, 6.8)
                sheet.paste(im, (83 + col * 190, y + 6), im)
        sheet.save(DEST / "four-machines-r66.png")
        sheet.crop((0, 0, 694, 396)).save(DEST / "two-furnaces-r66.png")


if __name__ == "__main__":
    main()
