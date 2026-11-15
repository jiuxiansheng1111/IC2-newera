from __future__ import annotations

import base64
from pathlib import Path
from PIL import Image, ImageDraw
import json


ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "src/main/resources/assets/ic2newera"
TEX = ASSETS / "textures/item"
MODEL = ASSETS / "models/item/industrial_phone.json"

INK = {
    "body_dark": (28, 36, 43, 255),
    "body_mid": (61, 74, 83, 255),
    "body_light": (83, 98, 108, 255),
    "edge": (133, 151, 160, 255),
    "edge_hi": (184, 197, 202, 255),
    "black": (8, 13, 18, 255),
    "lens": (11, 25, 39, 255),
    "lens_hi": (68, 119, 139, 255),
    "port": (7, 10, 13, 255),
    "lcd_bg": (7, 20, 31, 255),
    "lcd_panel": (11, 28, 42, 255),
    "lcd_grid": (26, 49, 63, 255),
    "lcd_road": (49, 78, 91, 255),
    "lcd_road_hi": (71, 102, 114, 255),
    "lcd_route": (67, 184, 177, 255),
    "lcd_route_hi": (126, 220, 204, 255),
    "lcd_text": (220, 233, 237, 255),
    "lcd_muted": (116, 145, 157, 255),
    "lcd_amber": (222, 177, 105, 255),
    "lcd_white": (242, 248, 245, 255),
}


def make_screen() -> Image.Image:
    """Build a high-resolution static thumbnail of the real phone launcher."""
    gui_dir = ASSETS / "textures/gui/industrial_phone"
    wallpaper = Image.open(gui_dir / "home_wallpaper.png").convert("RGBA")
    icon_sheet = Image.open(gui_dir / "home_icons.png").convert("RGBA")
    # This is the exact inner screen crop; the source bezel, speaker, and camera hole stay out.
    crop = wallpaper.crop((19, 20, 365, 650))
    logical = crop.copy()
    d = ImageDraw.Draw(logical, "RGBA")

    # Match PhoneHomeView's 3x2 launcher grid and 2-icon dock at its 672px reference height.
    gui_h = 672
    gui_w = round(gui_h * .593)
    padding = round(gui_w * .093)
    inner_w = gui_w - 2 * padding
    icon_size = round(gui_h * .115)
    grid_top = round(gui_h * .343)
    row_pitch = round(gui_h * .17)
    cell_w = inner_w // 3
    for index in range(6):
        col, row = index % 3, index // 3
        cell_x = padding + col * inner_w / 3
        left = round(cell_x + ((col + 1) * inner_w / 3 - col * inner_w / 3 - icon_size) / 2 - 19)
        top = round(grid_top + row * row_pitch - 20)
        icon = icon_sheet.crop((index % 4 * 96, index // 4 * 96,
                                index % 4 * 96 + 96, index // 4 * 96 + 96))
        icon = icon.resize((icon_size, icon_size), Image.Resampling.LANCZOS)
        logical.alpha_composite(icon, (left, top))

    dock_top = round(gui_h * .735)
    dock_height = round(gui_h * .173)
    inner_x = padding - 19
    dock_x1, dock_x2 = inner_x, inner_x + inner_w
    # Restrained translucent dock, matching the rounded launcher glass panel.
    dock = Image.new("RGBA", logical.size, (0, 0, 0, 0))
    dd = ImageDraw.Draw(dock)
    y1, y2 = dock_top - 20, dock_top + dock_height - 20
    dd.rounded_rectangle((dock_x1, y1, dock_x2, y2), radius=8, fill=(90, 140, 148, 118))
    dd.rounded_rectangle((dock_x1 + 2, y1 + 2, dock_x2 - 2, y2 - 2), radius=6, fill=(28, 50, 62, 164))
    logical = Image.alpha_composite(logical, dock)

    dock_inset = round(inner_w * .105)
    dock_cell = (inner_w - 2 * dock_inset) // 2
    dock_icon_top = round(dock_top + (dock_height - (icon_size + 14)) / 2 - 20)
    for i, index in enumerate((6, 7)):
        left = round(inner_x + dock_inset + i * dock_cell + (dock_cell - icon_size) / 2)
        icon = icon_sheet.crop((index % 4 * 96, index // 4 * 96,
                                index % 4 * 96 + 96, index // 4 * 96 + 96))
        icon = icon.resize((icon_size, icon_size), Image.Resampling.LANCZOS)
        logical.alpha_composite(icon, (left, dock_icon_top))

    # Use the actual numeric/colon glyphs from the launcher's icon atlas, not substitute text.
    status_h = round(gui_h * .121)
    digit_w = round(status_h * 32 / 56)
    clock_x, clock_y = padding - 18, round(gui_h * .129) - 20
    glyph_x = {0: 0, 1: 34, 2: 68, 3: 102, 4: 136,
               5: 170, 6: 204, 7: 238, 8: 272, 9: 306, 10: 348}
    cursor = clock_x
    for digit in (0, 9, 10, 4, 1):
        source_w = 16 if digit == 10 else 32
        source_x = glyph_x[digit]
        glyph = icon_sheet.crop((source_x, 198, source_x + source_w, 254))
        target_w = max(4, round(digit_w / 2)) if digit == 10 else digit_w
        glyph = glyph.resize((target_w, status_h), Image.Resampling.LANCZOS)
        logical.alpha_composite(glyph, (cursor, clock_y))
        cursor += target_w

    # Fill to the full sprite with a dark-ink edge to prevent light wallpaper bleed at the glass rim.
    target = (256, 512)
    inset = 2
    inner = logical.resize((target[0] - inset * 2, target[1] - inset * 2), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", target, (5, 12, 18, 255))
    out.alpha_composite(inner, (inset, inset))
    return out


def make_base() -> Image.Image:
    im = Image.new("RGBA", (64, 96), INK["black"])
    d = ImageDraw.Draw(im)
    # Back cover UV tile, 32x64.
    d.rectangle((0, 0, 31, 63), fill=INK["body_mid"])
    for y in range(1, 63, 4):
        d.line((1, y, 30, y), fill=(44, 54, 62, 255), width=1)
    d.rectangle((0, 0, 31, 63), outline=INK["body_light"])
    # Minimal etched hardware mark: a field reticle, with no brand or lettering.
    d.ellipse((12, 31, 19, 38), outline=(86, 111, 121, 255), width=1)
    d.line((15, 32, 15, 37), fill=(86, 111, 121, 255))
    d.line((13, 34, 18, 34), fill=(86, 111, 121, 255))
    d.line((9, 43, 22, 43), fill=(49, 62, 70, 255))
    d.line((11, 46, 20, 46), fill=(49, 62, 70, 255))

    # Front bezel UV tile, 32x64, shaped by the separate stepped chassis geometry.
    d.rectangle((32, 0, 63, 63), fill=(5, 9, 14, 255))
    d.rectangle((33, 1, 62, 62), outline=(31, 42, 50, 255), width=1)

    # Reusable 16x16 face tiles used by the model's hardware cubes.
    d.rectangle((0, 64, 15, 79), fill=INK["body_light"])
    d.line((1, 65, 14, 65), fill=INK["edge_hi"])
    d.rectangle((16, 64, 31, 79), fill=INK["black"])
    d.rectangle((17, 65, 30, 78), outline=INK["edge"])
    d.rectangle((32, 64, 47, 79), fill=INK["lens"])
    d.ellipse((34, 66, 45, 77), fill=(11, 24, 38, 255), outline=INK["lens_hi"], width=1)
    d.ellipse((37, 68, 40, 71), fill=(106, 158, 172, 255))
    d.line((38, 67, 42, 67), fill=(37, 68, 83, 255))
    d.rectangle((48, 64, 63, 79), fill=INK["port"])
    d.rounded_rectangle((51, 68, 60, 75), radius=2, fill=(44, 55, 62, 255), outline=INK["edge"])

    d.rectangle((0, 80, 15, 95), fill=INK["body_mid"])
    d.rectangle((2, 83, 13, 91), fill=(72, 85, 93, 255))
    d.line((4, 84, 11, 84), fill=INK["edge_hi"])
    d.rectangle((16, 80, 31, 95), fill=INK["black"])
    for x in (19, 23, 27):
        d.ellipse((x, 85, x + 1, 86), fill=INK["edge"])
        d.ellipse((x, 89, x + 1, 90), fill=INK["edge"])
    d.rectangle((32, 80, 47, 95), fill=(54, 69, 77, 255))
    d.line((34, 82, 45, 82), fill=(91, 110, 119, 255))
    d.rectangle((48, 80, 63, 95), fill=(20, 27, 33, 255))
    d.line((50, 82, 61, 82), fill=(72, 87, 96, 255))
    return im


def cube(name, start, end, default_uv=(0, 64, 16, 80), face_uv=None, faces=None):
    textures = {
        "north": "#body", "south": "#body", "east": "#body",
        "west": "#body", "up": "#body", "down": "#body",
    }
    result_faces = {}
    if faces:
        for face_name, (texture, uv) in faces.items():
            result_faces[face_name] = {"texture": f"#{texture}", "uv": list(uv)}
    else:
        for face_name in textures:
            uv = face_uv.get(face_name, default_uv) if face_uv else default_uv
            result_faces[face_name] = {"texture": textures[face_name], "uv": list(uv)}
    return {"from": list(start), "to": list(end), "faces": result_faces, "name": name}


def model_elements():
    metal = (0, 64, 16, 80)
    black = (16, 64, 32, 80)
    lens = (32, 64, 48, 80)
    port = (48, 64, 64, 80)
    button = (0, 80, 16, 96)
    speaker = (16, 80, 32, 96)
    elements = []
    # Brushed titanium chassis with clipped stair-step corners.
    elements.append(cube("chassis_core", (3.85, 0.85, 7.45), (12.15, 15.15, 8.55), default_uv=metal))
    elements.append(cube("top_rounded_cap", (4.45, 0.25, 7.45), (11.55, 0.85, 8.55), default_uv=metal))
    elements.append(cube("bottom_rounded_cap", (4.45, 15.15, 7.45), (11.55, 15.75, 8.55), default_uv=metal))
    # Screen-side bezel sits forward of the rail; the active LCD is a separate UV face.
    elements.append(cube("front_bezel", (4.05, 0.75, 7.36), (11.95, 15.25, 7.46),
        default_uv=metal, face_uv={"north": (32, 0, 64, 64)}))
    elements.append(cube("front_top_cap", (4.55, 0.25, 7.36), (11.45, 0.75, 7.46),
        default_uv=metal, face_uv={"north": (32, 0, 64, 3)}))
    elements.append(cube("front_bottom_cap", (4.55, 15.25, 7.36), (11.45, 15.75, 7.46),
        default_uv=metal, face_uv={"north": (32, 61, 64, 64)}))
    elements.append(cube("lcd_glass", (4.50, 1.20, 7.33), (11.50, 14.80, 7.37),
        default_uv=metal, faces={
            "north": ("screen", (0, 0, 256, 512)),
            "south": ("body", metal), "east": ("body", metal), "west": ("body", metal),
            "up": ("body", metal), "down": ("body", metal),
        }))
    # Rear glass cover uses its own 2:4 UV tile, not the LCD artwork.
    elements.append(cube("rear_glass", (4.02, 0.72, 8.53), (11.98, 15.28, 8.62),
        default_uv=metal, face_uv={"south": (0, 0, 32, 64)}))
    elements.append(cube("rear_cap_top", (4.55, 0.25, 8.53), (11.45, 0.72, 8.62),
        default_uv=metal, face_uv={"south": (0, 0, 32, 3)}))
    elements.append(cube("rear_cap_bottom", (4.55, 15.28, 8.53), (11.45, 15.75, 8.62),
        default_uv=metal, face_uv={"south": (0, 61, 32, 64)}))

    # Raised dual-camera island and two stepped optic rings on the rear upper left.
    elements.append(cube("camera_island", (4.35, 11.65, 8.62), (7.80, 15.35, 8.78), default_uv=black))
    elements.append(cube("camera_ring_upper", (4.82, 13.45, 8.78), (6.05, 14.68, 8.88), default_uv=lens))
    elements.append(cube("camera_lens_upper", (5.02, 13.65, 8.88), (5.85, 14.48, 8.94), default_uv=lens))
    elements.append(cube("camera_ring_lower", (4.82, 11.98, 8.78), (6.05, 13.21, 8.88), default_uv=lens))
    elements.append(cube("camera_lens_lower", (5.02, 12.18, 8.88), (5.85, 13.01, 8.94), default_uv=lens))
    elements.append(cube("camera_flash", (6.65, 14.12, 8.78), (7.22, 14.69, 8.87), default_uv=button))

    # Independent side keys and edge seams.
    elements.append(cube("volume_up", (3.62, 9.45, 7.74), (3.88, 10.55, 8.25), default_uv=button))
    elements.append(cube("volume_down", (3.62, 7.95, 7.74), (3.88, 9.05, 8.25), default_uv=button))
    elements.append(cube("action_key", (12.12, 9.30, 7.75), (12.38, 11.05, 8.25), default_uv=button))
    # Bottom USB-C style port and symmetric speaker apertures, in real underside geometry.
    elements.append(cube("charge_port", (7.15, 0.10, 7.68), (8.85, 0.27, 8.32), default_uv=port))
    for index, x in enumerate((5.75, 6.15, 6.55, 9.45, 9.85, 10.25), 1):
        elements.append(cube(f"speaker_slot_{index}", (x, 0.10, 7.80), (x + 0.16, 0.23, 8.20), default_uv=speaker))
    # Front top earpiece, above the readable screen content.
    elements.append(cube("earpiece", (6.95, 14.92, 7.30), (9.05, 15.05, 7.35), default_uv=black))
    return elements


def model_json():
    metal = (0, 64, 16, 80)
    return {
        "gui_light": "front",
        "textures": {
            "particle": "ic2newera:item/industrial_phone",
            "body": "ic2newera:item/industrial_phone",
            "screen": "ic2newera:item/industrial_phone_screen",
        },
        "display": {
            "gui": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.90, 0.90, 0.90]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.42, 0.42, 0.42]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.78, 0.78, 0.78]},
            "head": {"rotation": [0, 0, 0], "translation": [0, 10, 4], "scale": [0.48, 0.48, 0.48]},
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3.2, 0.6], "scale": [0.34, 0.34, 0.34]},
            "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 3.2, 0.6], "scale": [0.34, 0.34, 0.34]},
            "firstperson_righthand": {"rotation": [0, 180, 0], "translation": [0, 5.2, 0.8], "scale": [0.36, 0.36, 0.36]},
            "firstperson_lefthand": {"rotation": [0, 180, 0], "translation": [0, 5.2, 0.8], "scale": [0.36, 0.36, 0.36]},
        },
        "elements": [json_element(item) for item in model_elements()],
    }


def sync_editable_model() -> None:
    """Refresh embedded Blockbench textures from the active Minecraft PNGs."""
    editable = Path(__file__).with_name("industrial_phone.bbmodel")
    if not editable.exists():
        return
    model = json.loads(editable.read_text(encoding="utf-8"))
    active = {
        "industrial_phone": TEX / "industrial_phone.png",
        "industrial_phone_screen": TEX / "industrial_phone_screen.png",
    }
    for texture in model.get("textures", []):
        image_path = active.get(texture.get("name"))
        if image_path is None:
            continue
        raw = image_path.read_bytes()
        with Image.open(image_path) as image:
            width, height = image.size
        texture["source"] = "data:image/png;base64," + base64.b64encode(raw).decode("ascii")
        texture["width"] = texture["uv_width"] = width
        texture["height"] = texture["uv_height"] = height
    editable.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def json_element(item):
    """Minecraft item-model face UVs are normalized to 0..16 for each texture."""
    element = {k: v for k, v in item.items() if k not in ("name", "faces")}
    faces = {}
    for face_name, face in item["faces"].items():
        width, height = (256, 512) if face["texture"] == "#screen" else (64, 96)
        u1, v1, u2, v2 = face["uv"]
        faces[face_name] = {
            "texture": face["texture"],
            "uv": [u1 * 16 / width, v1 * 16 / height,
                   u2 * 16 / width, v2 * 16 / height],
        }
        if face_name == "north" and face["texture"] == "#screen":
            faces[face_name]["forge_data"] = {
                "block_light": 12,
                "sky_light": 12,
                "ambient_occlusion": False,
            }
    element["faces"] = faces
    return element


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    MODEL.parent.mkdir(parents=True, exist_ok=True)
    make_base().save(TEX / "industrial_phone.png")
    make_screen().save(TEX / "industrial_phone_screen.png")
    MODEL.write_text(json.dumps(model_json(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    sync_editable_model()
    print(f"wrote {TEX / 'industrial_phone.png'}")
    print(f"wrote {TEX / 'industrial_phone_screen.png'}")
    print(f"wrote {MODEL}")
    if (Path(__file__).with_name("industrial_phone.bbmodel")).exists():
        print(f"synced {Path(__file__).with_name('industrial_phone.bbmodel')}")


if __name__ == "__main__":
    main()
