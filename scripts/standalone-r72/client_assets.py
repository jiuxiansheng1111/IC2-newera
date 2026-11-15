"""Generate the two client-only r72 resource layers without editing Grove or a source mod."""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import zipfile

from PIL import Image

FIX_NAME = "NewEra-CMM-Model-Fix.zip"
FALLBACK_NAME = "NewEra-Grove-Fallback.zip"
OLD_FIX = "B104D293F050FBCFFCEE84C505ED38ACD04509704ADCB813797AB01CA05D60E1"
ROD = "assets/minecraft/models/item/warped_fungus_on_a_stick1.json"
GR_PACK = "file/Soartex Grove 1.0.4.zip"
LOCAL_MIPS = "ic2newera:local_mips"


def digest(p):
    with Path(p).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def j(obj):
    return (json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def png(im):
    raw = io.BytesIO()
    im.save(raw, format="PNG", compress_level=9)
    return raw.getvalue()


def write_zip(dest, files):
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=dest.name + ".", suffix=".tmp", dir=dest.parent)
    os.close(fd)
    tmp = Path(name)
    try:
        with zipfile.ZipFile(tmp, "w") as z:
            for path, data in sorted(files.items()):
                meta = zipfile.ZipInfo(path, (2026, 11, 15, 0, 0, 0))
                meta.compress_type = zipfile.ZIP_DEFLATED
                meta.create_system = 3
                meta.external_attr = 0o100644 << 16
                z.writestr(meta, data)
        with zipfile.ZipFile(tmp) as z:
            if z.testzip() is not None or set(z.namelist()) != set(files):
                raise ValueError("Incomplete generated resource pack")
        if not dest.is_file() or digest(tmp) != digest(dest):
            os.replace(tmp, dest)
        return digest(dest)
    finally:
        tmp.unlink(missing_ok=True)


def build_client_assets(mods_dir, original_cmm_zip, output_dir):
    mods_dir, original_cmm_zip, output_dir = map(Path, (mods_dir, original_cmm_zip, output_dir))
    if digest(original_cmm_zip) != OLD_FIX:
        raise ValueError("An immutable copy of the original r72 CMM repair is required")
    with zipfile.ZipFile(original_cmm_zip) as z:
        high = {ROD: z.read(ROD)}
    high["pack.mcmeta"] = j({"pack":{"pack_format":15,"description":"新纪元｜Grove、CMM 与模组模型兼容"},
                              "filter":{"block":[{"namespace":"minecraft","path":r"blockstates/cauldron\.json"}]}})
    low = {"pack.mcmeta": j({"pack":{"pack_format":15,"description":"新纪元｜未启用 Grove 时使用原版模型"}})}
    def parent(m):
        return j({"parent":"minecraft:block/" + m})
    def model(m):
        return {"model":"minecraft:block/" + m}
    low["assets/minecraft/models/block/cauldron/empty.json"] = parent("cauldron")
    for level in range(1, 4):
        low[f"assets/minecraft/models/block/cauldron/level{level}.json"] = parent(
            "water_cauldron_full" if level == 3 else f"water_cauldron_level{level}")
    # Only the optional Grove model references this sheet. When it is off, provide an
    # independently drawn neutral sprite so that the optional atlas source still resolves.
    candle = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for x in range(5, 11):
        for y in range(5, 15):
            candle.putpixel((x, y), (166 + (2 if x < 8 else -6), 164, 158, 255))
    candle.putpixel((7, 4), (64, 55, 47, 255))
    low["assets/minecraft/textures/block/candles_01.png"] = png(candle)
    high["assets/minecraft/models/block/door_bottom.json"] = parent("block")
    high["assets/minecraft/models/valkyrienskies.json"] = parent("block")
    high["assets/minecraft/blockstates/cauldron.json"] = j({"variants":{"":model("cauldron/empty")}})
    high["assets/minecraft/blockstates/water_cauldron.json"] = j({"variants":{
        f"level={i}":model(f"cauldron/level{i}") for i in range(1, 4)}})
    high["assets/createpropulsion/blockstates/oxidizer.json"] = j({"variants":{"":model("water")}})
    # Both the base game and Grove supply the same solid-white 4x4 utility sprite.
    # Draw it at atlas resolution to avoid reducing mip depth for unrelated sprites.
    high["assets/minecraft/textures/misc/white.png"] = png(Image.new("RGBA",(16,16),(255,255,255,255)))
    # These registered generic events have no sound definitions in the 1.20.1 asset index.
    # Normal horns continue to select their own instrument-specific sound. Reuse game audio by ID.
    high["assets/minecraft/sounds.json"] = j({
        "item.goat_horn.play":{"sounds":[{"name":"minecraft:item.goat_horn.sound.0","type":"event"}]},
        "entity.goat.screaming.horn_break":{"sounds":[{"name":"minecraft:entity.goat.horn_break","type":"event"}]}})
    high["assets/minecraft/atlases/blocks.json"] = j({"sources":[
        {"type":"minecraft:single","resource":"minecraft:block/candles_01","sprite":"minecraft:block/_bdc/candles_01"},
        {"type":"minecraft:single","resource":"valkyrienskies:test_wing"}]})
    # The mod loads the nine small optional sprites directly from installed local JARs.
    # Do not ship their original or enlarged images in the resource pack.
    return {FIX_NAME:write_zip(output_dir / FIX_NAME, high), FALLBACK_NAME:write_zip(output_dir / FALLBACK_NAME, low)}


def add_fallback_to_options(raw):
    """Change only one opted-in resource-pack order line. Preserve other bytes, including CRLF."""
    lines = raw.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if not line.startswith(b"resourcePacks:"):
            continue
        clean = line.rstrip(b"\r\n")
        order = json.loads(clean.split(b":",1)[1])
        if "file/" + FIX_NAME not in order:
            return raw
        order = [n for n in order if n not in ("file/" + FALLBACK_NAME, LOCAL_MIPS)]
        at = min(order.index(n) for n in (GR_PACK, "file/" + FIX_NAME) if n in order)
        order[at:at] = [LOCAL_MIPS, "file/" + FALLBACK_NAME]
        lines[i] = b"resourcePacks:" + j(order).rstrip(b"\n") + line[len(clean):]
        return b"".join(lines)
    return raw
