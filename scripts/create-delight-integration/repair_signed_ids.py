"""Remap FTB Quests IDs that cannot round-trip through Java signed Long parsing."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

HEX_ID = re.compile(r"^[0-9A-Fa-f]{16}$")
SIGNED_MAX = (1 << 63) - 1


def _ids(value):
    if isinstance(value, dict):
        ident = value.get("id")
        if isinstance(ident, str) and HEX_ID.fullmatch(ident):
            yield ident.upper()
        for child in value.values():
            yield from _ids(child)
    elif isinstance(value, list):
        for child in value:
            yield from _ids(child)


def _rewrite(value, mapping):
    if isinstance(value, dict):
        for key, child in list(value.items()):
            if key in {"id", "linked_quest", "group"} and isinstance(child, str):
                value[key] = mapping.get(child.upper(), child)
            elif key == "dependencies" and isinstance(child, list):
                value[key] = [mapping.get(item.upper(), item) if isinstance(item, str) else item
                              for item in child]
            else:
                _rewrite(child, mapping)
    elif isinstance(value, list):
        for child in value:
            _rewrite(child, mapping)


def repair_tree(quests_dir: Path, parse_snbt, to_snbt) -> dict:
    """Rewrite only overflowing 64-bit IDs and all matching references in emitted SNBT."""
    files = sorted(quests_dir.rglob("*.snbt"))
    parsed = {p: parse_snbt(p.read_text(encoding="utf-8-sig")) for p in files}
    ids = {ident for root in parsed.values() for ident in _ids(root)}
    overflowing = sorted(ident for ident in ids if int(ident, 16) > SIGNED_MAX)
    occupied = {int(ident, 16) for ident in ids if int(ident, 16) <= SIGNED_MAX}
    mapping = {}
    for old in overflowing:
        salt = 0
        while True:
            candidate = int(hashlib.sha256(f"ftbquests-signed-id-v1:{old}:{salt}".encode()).hexdigest()[:16], 16) & SIGNED_MAX
            if candidate not in occupied and candidate != 0:
                occupied.add(candidate)
                mapping[old] = f"{candidate:016X}"
                break
            salt += 1
    for path, root in parsed.items():
        _rewrite(root, mapping)
        path.write_text(to_snbt(root) + "\n", encoding="utf-8")
    report = {"format": "ftbquests-signed-long-v1", "objects_remapped": len(mapping),
              "preserved_ids": len(ids) - len(mapping), "mapping": mapping}
    (quests_dir.parent / "signed-id-remap.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # Verify every ID-bearing value accepted by FTB's Long.parseLong(hex, 16).
    remaining = []
    for path in sorted(quests_dir.rglob("*.snbt")):
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), 1):
            if re.search(r'\b(?:id|linked_quest|group):\s*"[89A-Fa-f][0-9A-Fa-f]{15}"', line):
                remaining.append(f"{path.name}:{line_no}")
            if "dependencies:" in line and re.search(r'"[89A-Fa-f][0-9A-Fa-f]{15}"', line):
                remaining.append(f"{path.name}:{line_no}")
    if remaining:
        raise ValueError(f"FTB signed-long overflow remains: {remaining[:20]}")
    return report
