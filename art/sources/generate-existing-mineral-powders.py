"""Generate original impure dust assets for existing Minecraft minerals."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import re

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"
STYLE_PATH = Path(__file__).with_name("material_pixel_style.py")
PREVIEW = ROOT / "art/preview/existing-mineral-impure-powders.png"

# Four readable mineral tones: outline, shadow, body, and highlight.
POWDERS = {
    "iron": ("铁", "Iron", ("#34383B", "#777F83", "#B7BEC0", "#E6E8E5")),
    "gold": ("金", "Gold", ("#55421C", "#A77A24", "#E1B84A", "#FFF0A2")),
    "copper": ("铜", "Copper", ("#503020", "#A65F37", "#D98C56", "#F5C18A")),
    "coal": ("煤", "Coal", ("#202124", "#45464A", "#73777B", "#B7B9B8")),
    "diamond": ("钻石", "Diamond", ("#284652", "#4F9BA8", "#91D7D8", "#D9FFFF")),
    "quartz": ("石英", "Quartz", ("#514E58", "#96909B", "#D6D0DA", "#FFF8FF")),
    "tin": ("锡", "Tin", ("#36434A", "#7D939A", "#C0D0D2", "#F1FFFF")),
    "silver": ("银", "Silver", ("#3D4651", "#8493A0", "#CCD8DE", "#FFFFFF")),
    "aluminium": ("铝", "Aluminium", ("#3B4143", "#879091", "#C8D0CD", "#F4F5EA")),
    "zinc": ("锌", "Zinc", ("#34434A", "#688794", "#ACC7D2", "#E4F4F7")),
}


def load_draw_dust():
    spec = importlib.util.spec_from_file_location("material_pixel_style", STYLE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load material style source: {STYLE_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.draw_dust


def update_language(path: Path, key_values: dict[str, str]) -> None:
    text = path.read_text(encoding="utf-8-sig")
    for key, value in key_values.items():
        encoded_key = json.dumps(key, ensure_ascii=False)
        encoded_value = json.dumps(value, ensure_ascii=False)
        pattern = re.compile(r"^(\s*)" + re.escape(encoded_key) + r"\s*:\s*.*?,?\s*$", re.MULTILINE)
        replacement = lambda match: f"{match.group(1)}{encoded_key}: {encoded_value},"
        if pattern.search(text):
            text = pattern.sub(replacement, text, count=1)
        else:
            close = text.rfind("}")
            if close < 0:
                raise ValueError(f"Missing closing brace in {path}")
            before = text[:close].rstrip()
            if before.endswith("{"):
                prefix = ""
            elif before.endswith(","):
                prefix = "\n"
            else:
                prefix = ",\n"
            text = before + prefix + f"  {encoded_key}: {encoded_value}\n" + text[close:]
    # The insertion form leaves a trailing comma on replaced final entries; JSON allows
    # no trailing commas, so normalize only a comma immediately before the object end.
    text = re.sub(r",(\s*})", r"\1", text)
    json.loads(text)
    path.write_text(text, encoding="utf-8", newline="")


def update_catalog(entries: list[dict]) -> None:
    path = ROOT / "art/model-catalog.json"
    original = path.read_text(encoding="utf-8-sig")
    catalog = json.loads(original)
    existing = {item["id"] for item in catalog["models"]}
    additions = [item for item in entries if item["id"] not in existing]
    if not additions:
        return
    closing = original.rfind("  ]")
    if closing < 0:
        raise ValueError("Cannot find models array end in art/model-catalog.json")
    rendered = []
    for item in additions:
        rendered.append("    {\n" + ",\n".join(
            "      " + json.dumps(key, ensure_ascii=False) + ": " + json.dumps(value, ensure_ascii=False)
            for key, value in item.items()
        ) + "\n    }")
    prefix = ",\n" if catalog["models"] else "\n"
    updated = original[:closing].rstrip() + prefix + ",\n".join(rendered) + "\n" + original[closing:]
    json.loads(updated)
    path.write_text(updated, encoding="utf-8", newline="")


def make_preview(images: dict[str, Image.Image]) -> Image.Image:
    columns, cell_w, cell_h, icon_scale = 4, 160, 116, 5
    rows = (len(images) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * cell_w, rows * cell_h), "#17191D")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for index, (name, icon) in enumerate(images.items()):
        x, y = (index % columns) * cell_w, (index // columns) * cell_h
        draw.rounded_rectangle((x + 5, y + 5, x + cell_w - 5, y + cell_h - 5), radius=7,
                               fill="#22262B", outline="#41474E")
        enlarged = icon.resize((16 * icon_scale, 16 * icon_scale), Image.Resampling.NEAREST)
        sheet.alpha_composite(enlarged, (x + (cell_w - enlarged.width) // 2, y + 8))
        material = name.removeprefix("impure_").removesuffix("_dust")
        draw.text((x + 12, y + 91), material, font=font, fill="#E7E9EA")
    return sheet


def generate() -> None:
    draw_dust = load_draw_dust()
    textures = ASSETS / "textures/item"
    models = ASSETS / "models/item"
    icons: dict[str, Image.Image] = {}
    zh_names: dict[str, str] = {}
    en_names: dict[str, str] = {}
    catalog_entries = []

    for material, (chinese, english, palette) in POWDERS.items():
        item_id = f"impure_{material}_dust"
        texture = draw_dust(palette, impure=True)
        texture.save(textures / f"{item_id}.png")
        model = {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"ic2newera:item/{item_id}"},
        }
        (models / f"{item_id}.json").write_text(
            json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline=""
        )
        icons[item_id] = texture
        zh_names[f"item.ic2newera.{item_id}"] = f"含杂质的{chinese}粉"
        en_names[f"item.ic2newera.{item_id}"] = f"Impure {english} Dust"
        catalog_entries.append({
            "id": item_id,
            "name": f"含杂质的{chinese}粉",
            "category": "既有矿物加工",
            "status": "源码资源已生成，注册与工艺接入待主线整合",
            "description": "原创16×16像素尖顶粉堆，深色夹杂标识清洗前阶段。",
            "assetRoot": "src/main/resources/assets",
            "path": f"ic2newera/models/item/{item_id}.json",
        })

    update_language(ASSETS / "lang/zh_cn.json", zh_names)
    update_language(ASSETS / "lang/en_us.json", en_names)
    update_catalog(catalog_entries)
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    make_preview(icons).save(PREVIEW)
    print(f"Generated {len(icons)} impure dust textures/models, language names, catalog entries, and {PREVIEW}.")


if __name__ == "__main__":
    generate()
