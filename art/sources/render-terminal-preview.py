"""Render the current terminal JSON/PNG with the shared depth-buffer preview tool."""
from pathlib import Path
import runpy
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
OUTPUT_PATH = ROOT / "art/preview/terminal-model-preview.png"


def main():
    renderer = runpy.run_path(str(ROOT / "art/sources/render-machine-preview.py"))
    canvas = Image.new("RGBA", (1240, 650), "#101923")
    draw = ImageDraw.Draw(canvas)
    draw.text((28, 20), "终端机 · 工业储物箱式终端实际资源预览", font=renderer['font'](28), fill="#eef4f7")
    renderer['panel'](canvas, (24, 80, 608, 626), "正面 · 箱体、嵌入小屏与物料口", "北面 · 分离式输入槽和输出抽屉", 'terminal', (-1, .55, -1), 15.3)
    renderer['panel'](canvas, (632, 80, 1216, 626), "背面 · 加固箱盖与短天线", "南面 · 独立 EU 能源口和光纤网络口", 'terminal', (1, .55, 1), 15.3)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert('RGB').save(OUTPUT_PATH, optimize=True)
    print(OUTPUT_PATH)


if __name__ == '__main__': main()
