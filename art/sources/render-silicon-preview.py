"""Preview the two real silicon item textures with nearest-neighbour enlargement."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "art/preview/silicon-material-styles.png"


def font(size):
    return ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", size)


def main():
    canvas = Image.new("RGB", (880, 410), "#111922")
    draw = ImageDraw.Draw(canvas)
    draw.text((24, 16), "硅材料 · 实际16×16贴图", font=font(26), fill="#edf1f4")
    for name, title, caption, x in (
        ("silicon", "硅", "深灰碎晶 · 银灰金属断面", 24),
        ("high_purity_silicon", "高纯硅", "银灰完整晶体 · 明亮整洁晶面", 456),
    ):
        draw.rounded_rectangle((x, 66, x+400, 388), radius=14, fill="#26313c", outline="#54616c")
        draw.text((x+18, 76), title, font=font(22), fill="#edf1f4")
        texture = Image.open(ROOT / f"src/main/resources/assets/ic2newera/textures/item/{name}.png").convert("RGBA")
        canvas.paste(texture.resize((224, 224), Image.Resampling.NEAREST), (x+86, 114), texture.resize((224, 224), Image.Resampling.NEAREST))
        # The small icon shows the same asset at an ordinary GUI pixel scale.
        small = texture.resize((32, 32), Image.Resampling.NEAREST)
        canvas.paste(small, (x+345, 292), small)
        draw.text((x+18, 348), caption, font=font(17), fill="#bbc3ca")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUTPUT, optimize=True)
    print(OUTPUT)


if __name__ == "__main__":
    main()
