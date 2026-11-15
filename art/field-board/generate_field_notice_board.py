"""Build the 1.20.1 industrial LCD enclosure, its two displays and an editable model."""
from __future__ import annotations

import base64
import json
import math
import os
import uuid
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


PROJECT = Path(__file__).resolve().parents[2] / "build-isolated/migration-1201/newera"
ASSETS = PROJECT / "src/main/resources/assets/ic2newera"
ART = PROJECT / "art/lcd-equipment/board"
SIZE = 512
DIRECTIONS = ("north", "south", "east", "west", "up", "down")
NAMESPACE = uuid.UUID("324edd11-9751-49f5-9d2a-af65e2412afe")


def font(size, bold=False):
    # Fonts are used to rasterise the screen once. None are distributed with the mod.
    path = os.environ.get("NEWERA_CJK_BOLD_FONT" if bold else "NEWERA_CJK_FONT")
    candidates = [path, "C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc",
                  "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc" if bold
                  else "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return ImageFont.truetype(candidate, size)
    raise FileNotFoundError("Set NEWERA_CJK_FONT and NEWERA_CJK_BOLD_FONT to an installed CJK font")


def between(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def mark(draw, x, y, scale, color):
    for i, height in enumerate((5, 9, 13)):
        start = x + i * 5 * scale
        draw.polygon([(start, y), (start + 3 * scale, y), (start + 5 * scale, y - height * scale),
                      (start + 2 * scale, y - height * scale)], fill=color)


def task_display(lit):
    w, h = 496, 279  # an exact 16:9 active panel inside a square, mip-safe sprite
    content = Image.new("RGB", (w, h))
    pixels = content.load()
    for y in range(h):
        for x in range(w):
            u, v = x / (w - 1), y / (h - 1)
            diagonal = u + v * 0.37
            window = (1.0 + math.tanh((0.42 - diagonal) * 23.0)) / 2.0
            if lit:
                light = math.exp(-((u - .82) / .38) ** 2 - ((v - .3) / .84) ** 2)
                base = between((12, 34, 45), (29, 90, 96), light)
                pixels[x, y] = tuple(round(c + window * (7 if k == 2 else 4)) for k, c in enumerate(base))
            else:
                # Diffuse reflection of a broad light source on dark, polarised cover glass.
                sky = math.exp(-((u - .13) / .68) ** 2 - ((v + .32) / .96) ** 2)
                edge = math.exp(-((diagonal - .43) / .034) ** 2) * 1.7
                pixels[x, y] = tuple(round(c + window * k + edge) for c, k in
                                     zip(between((9, 15, 20), (23, 34, 43), sky), (3, 4, 5)))
    if lit:
        d = ImageDraw.Draw(content)
        ice, silver, mint = (237, 246, 242), (179, 204, 207), (128, 224, 197)
        # Original terrain contour artwork. It occupies the right panel, away from text.
        contour = Image.new("RGBA", (w, h))
        lines = ImageDraw.Draw(contour)
        for i in range(15):
            pts = []
            for j in range(233):
                a = j * math.tau / 232
                radius = (14 + i * 9.2) * (1 + .14 * math.sin(3*a+.6) + .10 * math.cos(2*a-.9))
                pts.append((402 + radius * math.cos(a), 121 + .62*radius*math.sin(a) - .23*radius*math.cos(a)))
            lines.line(pts, fill=(137, 211, 192, 53 if i % 3 == 0 else 30), width=1)
        content.paste(contour.crop((278, 36, w, 208)), (278, 36), contour.crop((278, 36, w, 208)))
        d = ImageDraw.Draw(content)
        mark(d, 22, 27, 1, mint)
        d.text((47, 17), "新纪元工业", font=font(15), fill=silver, anchor="lt")
        d.ellipse((419, 20, 425, 26), fill=mint)
        d.text((436, 16), "运行", font=font(15), fill=ice, anchor="lt")
        d.line((21, 41, 474, 41), fill=(73, 109, 113), width=1)
        d.text((20, 73), "任务公告", font=font(49, True), fill=ice, anchor="lt")
        d.text((24, 147), "右键查看本期委托", font=font(19), fill=silver, anchor="lt")
        # A signed work order. The outline remains legible from several blocks away.
        d.rounded_rectangle((349, 77, 411, 160), radius=5, outline=(87, 163, 158), width=2)
        d.rounded_rectangle((356, 70, 418, 154), radius=5, fill=(23, 64, 72), outline=mint, width=3)
        d.rounded_rectangle((374, 66, 401, 76), radius=3, fill=(139, 224, 198))
        for y, length in ((90,32), (99,25), (108,19)):
            d.line((370,y,370+length,y),fill=(151,204,197),width=2)
        d.ellipse((391,118,428,155),fill=(33,85,83),outline=mint,width=2)
        d.line([(400,136),(406,142),(418,128)],fill=ice,width=3)
        # Two real contract types, rather than fixed examples masquerading as current orders.
        d.rectangle((0, 207, w-1, h-1), fill=(15, 37, 46))
        d.line((0,207,w,207),fill=(78,115,117),width=1)
        d.line((244,222,244,264),fill=(57,86,94),width=1)
        d.line([(25,237),(38,230),(51,237),(38,245),(25,237),(25,251),(38,259),(51,251),(51,237)],
               fill=mint,width=2)
        d.line((38,245,38,259),fill=mint,width=2)
        d.text((65,233), "物资交付", font=font(22,True), fill=ice, anchor="lt")
        d.rounded_rectangle((268,236,298,256),radius=3,outline=mint,width=2)
        d.line([(273,235),(276,230),(287,230),(290,235)],fill=mint,width=2)
        d.ellipse((277,240,289,252),outline=mint,width=2)
        d.text((312,233), "生态摄影", font=font(22,True), fill=ice, anchor="lt")
    sheet = Image.new("RGB", (SIZE, SIZE))
    sheet.paste(content, (8, 8))
    # Extrude the outer pixels so mipmapping never samples a black or transparent seam.
    sheet.paste(content.crop((0,0,1,h)).resize((8,h)), (0,8))
    sheet.paste(content.crop((w-1,0,w,h)).resize((8,h)), (504,8))
    sheet.paste(sheet.crop((0,8,SIZE,9)).resize((SIZE,8)), (0,0))
    sheet.paste(sheet.crop((0,286,SIZE,287)).resize((SIZE,SIZE-287)), (0,287))
    return sheet


def screw(d, x, y, r):
    d.ellipse((x-r-1,y-r-1,x+r+1,y+r+1), fill=(56,64,66))
    d.ellipse((x-r,y-r,x+r,y+r), fill=(137,148,148), outline=(175,186,185))
    for angle in (0, math.pi/3, 2*math.pi/3):
        dx, dy = (r*.53*math.cos(angle),r*.53*math.sin(angle))
        d.line((x-dx,y-dy,x+dx,y+dy), fill=(46,54,56), width=max(1,r//3))


def hardware_texture():
    atlas = Image.new("RGB", (SIZE,SIZE), (38,45,47))
    d = ImageDraw.Draw(atlas)
    regions = {}
    # Anodised and painted parts use separate texels. Directional brushing is subtle.
    swatches = {
        "alloy": ((181,191,190),(136,149,150)), "rim": ((159,174,176),(105,121,125)),
        "housing": ((105,119,122),(75,88,91)), "graphite": ((61,71,75),(40,50,54)),
        "gasket": ((34,42,44),(25,33,35)), "glass": ((26,34,39),(15,21,25)),
        "joint": ((91,103,104),(61,73,77)), "foot": ((136,149,150),(100,115,118)),
        "black": ((27,33,35),(20,26,28)), "terminal": ((61,105,91),(39,75,66)),
        "brass": ((161,144,96),(102,92,65)), "lit_led": ((176,235,196),(118,199,157)),
    }
    for index,(key,(first,last)) in enumerate(swatches.items()):
        x0, y0 = 4+(index%6)*84, 346+(index//6)*72
        for y in range(64):
            c = between(first,last,y/63)
            grain = (1,0,-1,0,1,-1,0)[y%7]
            d.line((x0,y0+y,x0+76,y0+y), fill=tuple(v+grain for v in c))
        regions[key] = (x0+4,y0+4,x0+72,y0+59)
    # Rear casting with recessed grilles, fasteners, regulatory plate, and a service marking.
    for y in range(5,244):
        col = between((117,130,130),(90,103,106),(y-5)/239)
        d.line((5,y,505,y),fill=col)
    for left in (29,345):
        for i in range(9):
            y=33+10*i
            d.rounded_rectangle((left,y,left+127,y+4),radius=2,fill=(52,64,66))
            d.line((left+3,y+1,left+124,y+1),fill=(33,43,46))
            d.line((left+3,y+5,left+124,y+5),fill=(147,156,155))
    for x in (17,494):
        for y in (18,225):
            screw(d,x,y,5)
    for x in (178,332):
        for y in (52,194):
            d.ellipse((x-6,y-6,x+6,y+6),fill=(71,81,83),outline=(150,160,159),width=1)
            screw(d,x,y,3)
    d.rounded_rectangle((28,154,159,221),radius=3,fill=(55,66,69),outline=(132,143,143))
    d.text((37,162),"IC2   /   新纪元工业",font=font(12,True),fill=(213,222,217),anchor="lt")
    d.line((37,183,150,183),fill=(108,125,126))
    d.text((37,192),"一级低压 · 100 EU/min",font=font(10),fill=(192,205,202),anchor="lt")
    regions["rear"]=(6,6,505,243)
    # The lower bezel has permanent silk-screen markings, separate from LCD content.
    for y in range(259,298):
        d.line((2,y,509,y),fill=between((24,31,36),(18,24,28),(y-259)/39))
    mark(d,20,285,.78,(159,172,175))
    d.text((41,270),"新纪元",font=font(15),fill=(156,170,174),anchor="lt")
    d.line((418,278,428,278),fill=(137,153,157),width=2)
    d.line((442,278,452,278),fill=(137,153,157),width=2)
    d.line((447,273,447,283),fill=(137,153,157),width=2)
    d.arc((474,271,484,282),-58,238,fill=(153,167,171),width=2)
    d.line((479,269,479,276),fill=(153,167,171),width=2)
    regions["chin"]=(3,260,509,297)
    d.rounded_rectangle((8,306,92,336),radius=4,fill=(20,26,28),outline=(121,130,126),width=2)
    for x in (27,51,75):
        screw(d,x,319,6)
        d.rectangle((x-5,328,x+5,331), fill=(91,119,92))
    regions["socket"]=(8,306,93,337)
    return atlas, regions


def uv(rect):
    return [round(c*16/SIZE,5) for c in rect]


def equipment(lit, regions):
    elements=[]
    def face(kind):
        return {"texture":"#hardware","uv":uv(regions[kind])}

    def box(name, low, high, kind, overrides=None):
        faces={side:face(kind) for side in DIRECTIONS}
        if overrides:
            faces.update({side:face(value) for side,value in overrides.items()})
        item={"name":name, "from":list(low), "to":list(high), "faces":faces}
        elements.append(item)
        return item

    def shell(name, bounds, radius, kind):
        x,y,z,X,Y,Z=bounds
        # Three slabs give the bezel a clipped corner without diagonal UV seams.
        box(name+" lower",(x+radius,y,z),(X-radius,y+radius,Z),kind)
        box(name+" centre",(x,y+radius,z),(X,Y-radius,Z),kind)
        box(name+" upper",(x+radius,Y-radius,z),(X-radius,Y,Z),kind)

    def applique(name, bounds, side, front, glow=0):
        x,y,z,X,Y,Z=bounds
        if glow:
            front={**front,"forge_data":{"block_light":glow,"sky_light":0,"ambient_occlusion":False}}
        elements.append({"name":name, "from":[x,y,z],"to":[X,Y,Z],"shade":not bool(glow),"faces":{side:front}})

    # The stand, display and die-cast back have different silhouettes when seen edge-on.
    for x in (4.06,10.64):
        for z in (4.66,10.40):
            box("Elastomer foot",(x,0,z),(x+1.30,.17,z+1.03),"gasket")
    box("Pedestal lower lip",(3.64,.17,4.18),(12.36,.41,11.72),"graphite")
    box("Weighted aluminium base",(3.77,.41,4.31),(12.23,.71,11.60),"foot",{"up":"alloy"})
    box("Stand socket",(6.46,.71,8.03),(9.54,1.09,11.07),"joint")
    box("Stand lower section",(6.92,1.09,8.29),(9.08,3.65,10.67),"foot",{"north":"rim"})
    for x in (6.97,8.59):
        box("Cable opening side",(x,3.65,8.36),(x+.44,6.17,10.55),"foot")
    box("Stand upper section",(6.96,6.17,8.36),(9.04,8.22,10.55),"foot")
    shell("Brushed aluminium bezel",(.50,4.40,4.22,15.50,13.40,5.19),.16,"alloy")
    shell("Perimeter seal",(.62,4.48,4.11,15.38,13.32,4.23),.13,"gasket")
    shell("Cover glass perimeter",(.72,4.54,3.999,15.28,13.26,4.12),.10,"glass")
    applique("Silkscreen and capacitive keys",(.84,4.555,3.985,15.16,5.050,3.997),"north",face("chin"))
    applique("Laminated 16:9 TFT", (1.08,5.10,3.983,14.92,12.885,3.998),
             "north", {"texture":"#screen","uv":uv((8,8,504,287))}, 13 if lit else 0)
    applique("Power pilot",(.99,4.75,3.976,1.115,4.82,3.984),"north",
             face("lit_led" if lit else "black"),9 if lit else 0)
    shell("Enclosure seam",(.67,4.52,5.19,15.33,13.28,5.30),.19,"gasket")
    shell("Die-cast enclosure",(.78,4.62,5.30,15.22,13.18,6.31),.20,"housing")
    box("Rear inspection cover",(1.00,4.88,6.30),(15.00,12.91,6.40),"housing",{"south":"rear"})
    box("Electronics housing",(5.28,6.03,6.40),(10.72,11.79,7.67),"housing",{"up":"rim"})
    for i in range(7):
        x=5.56+.77*i
        box("Heat sink rib",(x,11.79,6.45),(x+.19,12.15,7.58),"rim")
    box("VESA mounting flange",(5.63,6.37,7.67),(10.37,11.42,7.92),"joint")
    for x in (5.66,9.80):
        box("VESA screw",(x,6.52,7.92),(x+.51,7.03,8.02),"graphite")
        box("VESA screw",(x,10.73,7.92),(x+.51,11.24,8.02),"graphite")
    box("Tilt bracket",(6.21,7.22,7.92),(9.79,9.82,9.11),"housing")
    box("Pivot boss",(6.77,7.05,9.11),(9.23,9.25,10.84),"joint")
    for x in (6.48,9.23):
        box("Hinge cap",(x,7.51,9.30),(x+.29,8.88,10.57),"graphite")
    box("Low-voltage connector surround",(10.87,5.31,6.40),(13.59,6.39,6.85),"graphite")
    box("Pluggable screw terminal",(11.05,5.43,6.85),(13.42,6.27,7.13),"terminal",{"south":"socket"})
    for x in (1.24,14.50):
        box("Recessed side retainer",(x,7.42,6.31),(x+.23,8.43,6.61),"joint")
    return {"parent":"minecraft:block/block",
            "textures":{"particle":"ic2newera:block/notice_lcd_hardware","hardware":"ic2newera:block/notice_lcd_hardware",
                        "screen":"ic2newera:block/notice_lcd_"+("on" if lit else "off")},
            "elements":elements}


def write_json(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


def editable(model):
    textures=[]
    for key in ("hardware","screen"):
        name=model["textures"][key].split(":")[1]
        raw=(ASSETS / ("textures/"+name+".png")).read_bytes()
        textures.append({"name":name.split("/")[-1]+".png","id":str(len(textures)),
                         "uuid":str(uuid.uuid5(NAMESPACE,key)), "mode":"bitmap","saved":False,
                         "width":SIZE,"height":SIZE,"uv_width":SIZE,"uv_height":SIZE,
                         "source":"data:image/png;base64,"+base64.b64encode(raw).decode("ascii")})
    cubes=[]
    for i,e in enumerate(model["elements"]):
        faces={}
        for side in DIRECTIONS:
            f=e["faces"].get(side)
            faces[side]={"uv":[round(v*SIZE/16,3) for v in f["uv"]],"texture":int(f["texture"]=="#screen")} if f else {"uv":[0,0,0,0],"texture":None}
        cubes.append({"type":"cube","name":e["name"],"uuid":str(uuid.uuid5(NAMESPACE,str(i))),
                      "from":e["from"],"to":e["to"],"origin":[8,8,8],"box_uv":False,"rescale":False,"autouv":0,
                      "faces":faces,"shade":e.get("shade",True),"export":True})
    return {"meta":{"format_version":"4.10","model_format":"java_block","box_uv":False},
            "name":"Field notice board","resolution":{"width":SIZE,"height":SIZE},
            "elements":cubes,"outliner":[e["uuid"] for e in cubes],"textures":textures}


def main():
    atlas,regions=hardware_texture()
    dst=ASSETS/"textures/block"
    dst.mkdir(parents=True,exist_ok=True)
    atlas.save(dst/"notice_lcd_hardware.png",optimize=True)
    for lit in (False,True):
        task_display(lit).save(dst/("notice_lcd_"+("on" if lit else "off")+".png"),optimize=True)
        model=equipment(lit,regions)
        name="field_notice_board"+("_on" if lit else "")
        write_json(ASSETS/f"models/block/{name}.json",model)
        if lit:
            write_json(ART/"field_notice_board.bbmodel",editable(model))
    # An inventory item represents the device; the placed block follows real IC2 power.
    display={"gui":{"rotation":[12,158,0],"translation":[0,1.2,0],"scale":[.92,.92,.92]},
             "fixed":{"rotation":[0,180,0],"translation":[0,1.0,0],"scale":[.9,.9,.9]}}
    write_json(ASSETS/"models/item/field_notice_board.json",{"parent":"ic2newera:block/field_notice_board_on","display":display})
    state={"variants":{}}
    for direction,rotation in (("north",0),("east",90),("south",180),("west",270)):
        for lit in (False,True):
            variant={"model":"ic2newera:block/field_notice_board"+("_on" if lit else "")}
            if rotation:
                variant["y"]=rotation
            state["variants"][f"facing={direction},lit={str(lit).lower()}"]=variant
    write_json(ASSETS/"blockstates/field_notice_board.json",state)


if __name__=="__main__":
    main()
