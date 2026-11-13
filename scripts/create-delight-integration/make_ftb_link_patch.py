"""Create a small server-relative ZIP containing corrected r71 FTB chapters."""
from __future__ import annotations

import hashlib
import json
import re
import shutil
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from merge_quests import read

ROOT = Path(__file__).resolve().parents[2]
W = ROOT / "build-isolated/player-packaging/20261113-delight-joint"
MERGED = W / "quests-work/merged"
CLIENT_QUESTS = W / "client-overlay/config/ftbquests/quests"
OUT = W / "live-r71/r71-ftb-questlinks.zip"
REMAP = W / "quests-work/signed-id-remap.json"
PATCH_CHAPTERS = (
    "Animal_Companions.snbt",
    "delight_ecology.snbt",
    "delight_workshop.snbt",
    "newera_field.snbt",
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    mapping = json.loads(REMAP.read_text(encoding="utf-8"))["mapping"]
    if len(mapping) != 26:
        raise ValueError(f"Expected the audited 26 overflowing IDs, got {len(mapping)}")
    high = re.compile(r'"[89A-Fa-f][0-9A-Fa-f]{15}"')
    chapters = sorted((MERGED / "chapters").glob("*.snbt"))
    links = 0
    for path in chapters:
        tree = read(path)
        links += len(tree.get("quest_links", []))
        text = path.read_text(encoding="utf-8")
        if high.search(text):
            raise ValueError(f"Signed-long overflow remains in {path.name}")
    if len(chapters) != 61 or links != 32:
        raise ValueError(f"Unexpected quest tree shape: {len(chapters)} chapters, {links} links")
    for path in (MERGED / "chapters").glob("*.snbt"):
        tree = read(path)
        for link in tree.get("quest_links", []):
            int(link["linked_quest"], 16)  # same signed radix-16 range as FTB's Long.parseLong
    patch_files = {}
    for name in PATCH_CHAPTERS:
        source = MERGED / "chapters" / name
        if not source.is_file():
            raise FileNotFoundError(source)
        text = source.read_text(encoding="utf-8")
        if not any(value in text for value in mapping.values()):
            raise ValueError(f"Patch chapter contains no remapped ID: {name}")
        rel = f"config/ftbquests/quests/chapters/{name}"
        patch_files[rel] = source.read_bytes()
        target = CLIENT_QUESTS / "chapters" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    with ZipFile(OUT, "w", ZIP_DEFLATED, compresslevel=9) as zf:
        for rel, data in sorted(patch_files.items()):
            zf.writestr(rel, data)
    manifest = {
        "release": "r71",
        "format": "server-relative quest config patch",
        "archive": OUT.name,
        "files": [{"path": rel, "bytes": len(data), "sha256": sha256(data)}
                  for rel, data in sorted(patch_files.items())],
        "counts": {"chapters": len(chapters), "links": links, "remappedIds": len(mapping),
                   "preservedIds": json.loads(REMAP.read_text(encoding="utf-8"))["preserved_ids"]},
        "reason": "FTB Quests 2001.4.22 uses Long.parseLong(hex, 16), which rejects unsigned 64-bit IDs above Long.MAX_VALUE.",
    }
    (OUT.parent / "r71-ftb-questlinks-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"archive": str(OUT), "bytes": OUT.stat().st_size,
                      "files": list(patch_files), "chapters": len(chapters), "links": links,
                      "remappedIds": len(mapping)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
