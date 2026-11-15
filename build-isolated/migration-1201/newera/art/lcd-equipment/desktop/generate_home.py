"""Original LCD home-screen artwork for the New Era industrial phone."""
import math
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


DEST = Path(__file__).resolve().parents[3] / "src/main/resources/assets/ic2newera/textures/gui/industrial_phone"
WHITE = (244, 251, 250)


def blend(a, b, t):
    return tuple(round(u + (v - u) * t) for u, v in zip(a, b))


def wallpaper():
    width, height = 384, 672
    out = Image.new("RGBA", (width, height))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle((7, 2, 376, 669), 48, fill="#141d24", outline="#82919a", width=2)
    d.rounded_rectangle((10, 4, 373, 666), 46, fill="#080e15", outline="#34434e", width=3)
    d.rounded_rectangle((5, 123, 8, 150), 2, fill="#667581")
    d.rounded_rectangle((4, 165, 8, 219), 2, fill="#465660")
    d.rounded_rectangle((375, 162, 379, 228), 2, fill="#637380")
    w, h = 346, 630
    panel = Image.new("RGB", (w, h))
    pixels = panel.load()
    # Layered contour surfaces. Thin illuminated ridgelines follow the glass diagonally.
    for y in range(h):
        v = y / h
        for x in range(w):
            u = x / w
            t = max(0.0, min(1.0, .18 + v * .66 + u * .14))
            c = blend((7, 19, 32), (18, 56, 71), t)
            dx, dy = u - .79, v - .62
            glow = max(0., 1. - math.sqrt(dx*dx*.8 + dy*dy*2.1))
            c = blend(c, (35, 111, 119), .22 * glow**2)
            for i, (offset, lo, hi) in enumerate((
                    (.45,(15,59,78),(104,212,207)),
                    (.51,(12,46,65),(66,175,181)),
                    (.605,(16,67,76),(99,206,192)),
                    (.69,(18,60,70),(59,142,151)),
                    (.80,(11,40,55),(57,133,143)))):
                ridge = offset - .29*u + .10*math.sin(u * 3.15 + .18*i) + .52*(1-u)**3
                below = v - ridge
                if below >= 0:
                    c = blend(lo, hi, .73 * math.exp(-below*(28 + 4*i)))
                    if below < .0022:
                        c = blend(c, hi, .69)
            bottom = max(0., (v - .70)/.30)
            c = blend(c, (7,21,32), min(.65,.64*bottom))
            pixels[x, y] = c
    mask = Image.new("L", (w, h))
    ImageDraw.Draw(mask).rounded_rectangle((0,0,w-1,h-1), 32, fill=255)
    out.paste(panel, (19,20), mask)
    d.rounded_rectangle((171, 8, 212, 10), 1, fill="#3b454b")
    d.ellipse((186,29,197,40), fill="#030910", outline="#1a313c", width=2)
    d.ellipse((189,31,192,33), fill="#304857")
    return out


COLORS = (
    ((75,185,166),(19,105,105)), ((93,158,213),(42,88,150)),
    ((239,161,100),(177,84,64)), ((169,137,205),(95,76,153)),
    ((65,166,178),(27,92,125)), ((92,116,196),(48,56,119)),
    ((110,128,144),(53,71,86)), ((245,238,206),(189,207,180)),
)


def icon(index):
    side = 192
    p = Image.new("RGB", (side,side))
    px = p.load()
    hi, lo = COLORS[index]
    for y in range(side):
        for x in range(side):
            px[x,y] = blend(hi, lo, (.30*x + .70*y)/(side-1))
    mask = Image.new("L",(side,side))
    ImageDraw.Draw(mask).rounded_rectangle((3,3,188,188),39,fill=255)
    p.putalpha(mask)
    draw = ImageDraw.Draw(p)
    def pts(a): return [(x*2,y*2) for x,y in a]
    def line(points,color=WHITE,width=4): draw.line(pts(points),fill=color,width=width*2,joint="curve")
    def poly(points,color): draw.polygon(pts(points),fill=color)
    def circ(bounds,color,stroke=None,width=2):
        draw.ellipse(tuple(n*2 for n in bounds),fill=color,outline=stroke,width=width*2)
    def rr(bounds,r,color,stroke=None,width=2):
        draw.rounded_rectangle(tuple(n*2 for n in bounds),r*2,fill=color,outline=stroke,width=width*2)
    draw.rounded_rectangle((5,5,186,186),37,outline=blend(hi,WHITE,.25),width=2)
    if index == 0:  # Robot head with antenna, earpieces and paired optical sensors
        line([(48,25),(48,34)],width=3); circ((45,21,51,27),WHITE)
        rr((20,43,26,57),3,WHITE); rr((70,43,76,57),3,WHITE)
        rr((25,34,71,68),10,WHITE); rr((31,41,65,57),7,(31,107,110))
        circ((37,46,42,51),(190,251,230)); circ((54,46,59,51),(190,251,230))
        line([(42,62),(54,62)],(70,147,139),2)
    elif index == 1:  # Sawtooth factory and a brick chimney
        poly([(20,65),(20,47),(33,39),(33,47),(47,39),(47,47),(59,39),(61,27),(69,27),(72,65)],WHITE)
        for x in (29,41,53): rr((x,53,x+6,58),1,(63,115,163))
        line([(61,21),(69,21)],(213,235,245),2); rr((18,67,75,71),1,WHITE)
    elif index == 2:  # Map sheet, two folds and a direction arrow
        poly([(18,36),(35,29),(55,36),(74,29),(76,65),(58,72),(37,65),(21,72)],(255,228,190))
        poly([(35,29),(55,36),(58,72),(37,65)],(217,161,119))
        poly([(42,51),(66,31),(55,61),(51,51)],(122,63,69))
        line([(51,51),(65,32)],(244,204,155),2)
    elif index == 3:  # Field journal, binding and bookmark
        rr((27,23,69,73),5,(235,227,246)); rr((27,23,34,73),3,(197,180,223))
        poly([(55,23),(62,23),(62,40),(58,37),(55,40)],(109,80,148))
        line([(39,48),(56,48)],(128,104,159),2); line([(39,54),(51,54)],(128,104,159),2)
        for y in (32,44,57,66): line([(24,y),(32,y)],WHITE,2)
    elif index == 4:
        line([(41,59),(41,32),(66,26),(66,54)],width=5)
        line([(42,39),(65,33)],width=4)
        circ((24,55,42,69),WHITE); circ((49,50,67,64),WHITE)
    elif index == 5:
        draw.ellipse((46,49,148,142),outline=(174,196,241),width=6)
        draw.arc((55,55,141,135),195,457,fill=WHITE,width=7)
        for off in range(2):
            a = 29 + off*7
            line([(a,65),(65,34+off*3)],(224,237,255),2)
        circ((62,29,69,36),WHITE); circ((25,65,29,69),(171,203,247))
    elif index == 6:
        rr((18,35,77,69),7,(223,232,233)); rr((32,28,53,40),3,(223,232,233))
        circ((36,39,61,64),(40,61,75)); circ((41,44,56,59),(85,159,161))
        circ((45,47,52,54),(35,69,86)); circ((63,40,68,45),(109,180,175))
    else:
        rr((18,25,77,70),7,(255,250,227)); rr((23,30,72,65),4,(131,190,188))
        circ((54,33,64,43),(255,226,158))
        poly([(24,58),(40,43),(59,64),(24,64)],(90,148,136))
        poly([(27,64),(51,46),(72,59),(72,64)],(57,115,108))
    return p.resize((96,96),Image.Resampling.LANCZOS)


def atlas():
    sheet=Image.new("RGBA",(512,256))
    for i in range(8):
        img=icon(i)
        sheet.alpha_composite(img,((i%4)*96,(i//4)*96))
    font_name=os.environ.get("NEWERA_UI_FONT","C:/Windows/Fonts/segoeuil.ttf")
    face=ImageFont.truetype(font_name if Path(font_name).is_file() else "DejaVuSans.ttf",49)
    for i,c in enumerate("0123456789:"):
        cell=Image.new("RGBA",(32,56)); draw=ImageDraw.Draw(cell)
        draw.text((16,46),c,font=face,anchor="ms",fill=(239,249,249,255))
        sheet.alpha_composite(cell,(i*34,198))
    cjk_paths = [os.environ.get("NEWERA_CJK_FONT"), "C:/Windows/Fonts/msyh.ttc",
                 "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]
    cjk = next((p for p in cjk_paths if p and Path(p).is_file()), None)
    if not cjk:
        raise FileNotFoundError("Set NEWERA_CJK_FONT to an installed Chinese font")
    face = ImageFont.truetype(cjk,22)
    for i, label in enumerate(("机器人控制","工业控制","导航","远征手记","音乐","虫洞","相机","图库")):
        cell = Image.new("RGBA",(128,29))
        d = ImageDraw.Draw(cell)
        d.text((64,15),label,font=face,anchor="mm",fill=(11,29,39,180),stroke_width=1,stroke_fill=(11,29,39,125))
        d.text((64,14),label,font=face,anchor="mm",fill=(247,252,251,255))
        sheet.alpha_composite(cell,(384,i*31))
    return sheet


if __name__ == "__main__":
    DEST.mkdir(parents=True,exist_ok=True)
    wallpaper().save(DEST/"home_wallpaper.png",optimize=True)
    atlas().save(DEST/"home_icons.png",optimize=True)
