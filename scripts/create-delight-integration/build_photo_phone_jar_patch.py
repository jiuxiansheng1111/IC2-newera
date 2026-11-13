#!/usr/bin/env python3
"""Build an isolated JAR with only the verified phone evidence class replaced.

Run after ForgeGradle `reobfJar --offline -PisolatedBuild`. The old JAR is
resolved by the locked player-packaging selection; the replacement class comes
from the reobfuscated first-party New Era artifact. All other JAR members are
checked byte-for-byte after extraction.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build-isolated/player-packaging/20261113-delight-joint"
SELECTION = WORK / "selection.json"
REOBF = ROOT / "build-isolated/migration-1201/newera/build-isolated/libs/ic2newera-0.2.0-migration1201.jar"
OUTPUT = WORK / "photo-assignment-generated/binaries/ic2newera-0.2.0-migration1201-all.jar"
PATCH_MEMBER = "com/ic2newera/field/EcologyAlbum.class"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def primary_jar() -> Path:
    previous = WORK / "collision-quests-20261114/player-handbook-r71/core-before/ic2newera-0.2.0-migration1201-all.jar"
    if previous.is_file() and sha256(previous) == "7dba2477dc2429c4db7fd59465ff178991548f64fac800a042b31223deaed8e5":
        return previous
    records = json.loads(SELECTION.read_text(encoding="utf-8"))
    matches = [row for row in records if row.get("primary") == "ic2newera"]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one locked primary ic2newera JAR, found {len(matches)}")
    path = Path(matches[0]["path"])
    expected = matches[0].get("sha256")
    if not path.is_file() or not expected or sha256(path) != expected:
        raise RuntimeError("Locked primary New Era JAR is missing or has changed")
    return path


def main() -> None:
    original = primary_jar()
    if not REOBF.is_file():
        raise RuntimeError(f"Missing ForgeGradle reobfuscated source JAR: {REOBF}")
    if original.resolve() == OUTPUT.resolve():
        raise RuntimeError("Output must remain separate from the locked source JAR")

    with ZipFile(original) as old_zip, ZipFile(REOBF) as built_zip:
        old_names = old_zip.namelist()
        built_names = built_zip.namelist()
        if PATCH_MEMBER not in old_names or PATCH_MEMBER not in built_names:
            raise RuntimeError(f"Required class member absent: {PATCH_MEMBER}")
        if any(name.upper().endswith((".SF", ".RSA", ".DSA")) for name in old_names):
            raise RuntimeError("Refusing to patch a signed JAR")
        replacement = built_zip.read(PATCH_MEMBER)
        original_members = {name: old_zip.read(name) for name in old_names}
        old_hash = sha256(original)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(original) as old_zip, ZipFile(OUTPUT, "w", ZIP_DEFLATED, compresslevel=9) as output_zip:
        output_zip.comment = old_zip.comment
        for info in old_zip.infolist():
            payload = replacement if info.filename == PATCH_MEMBER else original_members[info.filename]
            output_zip.writestr(info, payload)

    changed = []
    with ZipFile(original) as old_zip, ZipFile(OUTPUT) as new_zip:
        if old_zip.namelist() != new_zip.namelist():
            raise RuntimeError("JAR member order or membership changed")
        for name in old_zip.namelist():
            if old_zip.read(name) != new_zip.read(name):
                changed.append(name)
    if changed != [PATCH_MEMBER]:
        OUTPUT.unlink(missing_ok=True)
        raise RuntimeError(f"Unexpected changed JAR member set: {changed}")

    manifest = {
        "source_jar": str(original),
        "source_sha256": old_hash,
        "reobfuscated_build_jar": str(REOBF),
        "result_jar": str(OUTPUT),
        "result_sha256": sha256(OUTPUT),
        "changed_members": changed,
        "unchanged_members_byte_verified": len(original_members) - 1,
        "source_class": str(ROOT / "build-isolated/migration-1201/newera/src/main/java/com/ic2newera/field/EcologyAlbum.java"),
    }
    OUTPUT.with_suffix(".manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
