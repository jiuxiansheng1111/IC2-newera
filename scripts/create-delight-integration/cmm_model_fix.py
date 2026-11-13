"""Provide a resource-only override for one malformed CMM JSON, without rewriting the user's pack."""
import hashlib
import json
import os
import re
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

BASE_NAME = "CMMRP_Release_1.0_[1.20-1.20.1].zip"
PACK_NAME = "NewEra-CMM-Model-Fix.zip"
MODEL = "assets/minecraft/models/item/warped_fungus_on_a_stick1.json"


def build_pack(overlay, visuals):
    source = Path(next(r["source"] for r in visuals if Path(r["path"]).name == BASE_NAME))
    with ZipFile(source) as pack:
        raw = pack.read(MODEL)
    if hashlib.sha256(raw).hexdigest() != "4c818d295ca040a2dd44671deb567768a3afa5d0e7be1a672ca45a594bfdd93b":
        raise ValueError("The upstream CMM resource changed; re-check if this override is needed")
    fixed, count = re.subn(rb"(?m)^    \](?=\r?$)", b"    }", raw)
    if count != 1 or set(json.loads(fixed)["display"]) != {"thirdperson_righthand", "thirdperson_lefthand"}:
        raise ValueError("Unexpected CMM model shape")
    out = overlay / "resourcepacks" / PACK_NAME
    out.parent.mkdir(exist_ok=True, parents=True)
    temp = out.with_name(out.name + ".part")
    with ZipFile(temp, "w") as z:
        for key, data in [("pack.mcmeta", json.dumps({"pack": {"pack_format": 15,
                             "description": "新纪元：修正 CMM 诡异菌钓竿的手持模型"}}, ensure_ascii=False).encode()), (MODEL, fixed)]:
            z.writestr(ZipInfo(key, date_time=(2026, 11, 13, 0, 0, 0)), data, compress_type=ZIP_DEFLATED)
    os.replace(temp, out)


def enabled_options(raw):
    # Work at the byte level to preserve key bindings, visual choices and line endings.
    pattern = rb"(?m)^resourcePacks:([^\r\n]*)"
    matches = list(re.finditer(pattern, raw))
    if len(matches) != 1:
        raise ValueError("Expected one resourcePacks line")
    m = matches[0]
    packs = json.loads(m.group(1).decode("utf-8"))
    base, fixed = "file/" + BASE_NAME, "file/" + PACK_NAME
    if base not in packs or (fixed in packs and packs.index(fixed) > packs.index(base)):
        return raw
    packs = [p for p in packs if p != fixed]
    packs.insert(packs.index(base) + 1, fixed)
    return raw[:m.start(1)] + json.dumps(packs, ensure_ascii=False, separators=(",", ":")).encode() + raw[m.end(1):]


def stage(overlay, visuals):
    build_pack(overlay, visuals)
    options = overlay / "options.txt"
    raw = options.read_bytes()
    new = enabled_options(raw)
    if new != raw:
        options.write_bytes(new)
