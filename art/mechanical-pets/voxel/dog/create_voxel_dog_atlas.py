"""Create a hand-authored, hard-edged 64x64 pixel atlas for the block dog."""
from pathlib import Path
from io import BytesIO
import base64
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).parent
C = {
    "tan": (165, 104, 56, 255),
    "tan2": (190, 128, 69, 255),
    "tan_dark": (116, 67, 37, 255),
    "cream": (235, 222, 191, 255),
    "cream2": (210, 194, 161, 255),
    "black": (39, 31, 27, 255),
    "pink": (188, 99, 107, 255),
    "brass": (185, 136, 49, 255),
    "brass_hi": (224, 179, 77, 255),
}
im = Image.new("RGBA", (64, 64), C["tan"])
d = ImageDraw.Draw(im)


def box(coords, color):
    d.rectangle(coords, fill=C[color])


# Head cube at UV offset (0,0), dimensions 6x6x5. The north face is x=5..10,
# y=5..10. Its face carries a clear tan crown, cream mask and one-pixel eyes.
box((0, 0, 21, 10), "tan")
box((0, 0, 21, 1), "tan_dark")
box((5, 0, 10, 4), "tan2")
box((0, 8, 4, 10), "cream")
box((12, 8, 21, 10), "cream")
box((5, 5, 10, 10), "cream")
box((5, 5, 10, 6), "tan2")
box((5, 7, 10, 7), "cream2")
box((6, 7, 6, 7), "black")
box((9, 7, 9, 7), "black")
box((8, 9, 8, 9), "black")
box((7, 10, 9, 10), "tan_dark")

# Broad torso box at UV offset (0,16), now 6x5x10.
box((0, 16, 31, 30), "tan")
box((0, 16, 31, 18), "tan2")
box((0, 28, 31, 29), "cream")
box((0, 30, 31, 30), "cream2")

# 2x5x2 leg UV at offset (32,0): tan above, cream socks and paws below.
box((32, 0, 39, 6), "tan")
box((32, 0, 39, 2), "tan2")
box((32, 4, 39, 6), "cream")
box((32, 6, 39, 6), "cream2")
box((32, 8, 39, 14), "tan")
box((32, 8, 39, 10), "tan2")
box((32, 12, 39, 14), "cream")

# Ears and the stepped tail use the same small, flat palette.
box((40, 0, 47, 3), "tan")
box((43, 3, 43, 3), "pink")
box((40, 8, 47, 11), "tan")
box((43, 11, 43, 11), "pink")
box((40, 16, 47, 19), "tan")
box((40, 16, 47, 16), "tan2")
box((40, 19, 47, 19), "cream")

# Short square muzzle front face (offset 0,32), 4x2x1.
box((0, 32, 9, 34), "cream")
box((1, 33, 5, 34), "cream2")
box((3, 33, 4, 33), "black")
box((2, 34, 5, 34), "tan_dark")

# Lower jaw/tongue and a very small brass chest tag.
box((0, 40, 9, 42), "cream")
box((0, 42, 9, 42), "cream2")
box((16, 40, 21, 42), "pink")
box((16, 42, 21, 42), "cream2")
box((24, 40, 29, 42), "brass")
box((24, 40, 29, 40), "brass_hi")
box((24, 42, 29, 42), "tan_dark")

# Deliberate block markings; no gradient, blur, or noise.
box((32, 20, 35, 20), "cream")
box((36, 20, 39, 20), "tan_dark")

im.save(ROOT / "dog-atlas.png")
buf = BytesIO()
im.save(buf, format="PNG")
model_path = ROOT / "dog.bbmodel"
if model_path.exists():
    model = json.loads(model_path.read_text(encoding="utf-8"))
    if model.get("textures"):
        model["textures"][0]["source"] = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")
        model_path.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
