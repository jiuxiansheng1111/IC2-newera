#!/usr/bin/env python3
"""Build and safely merge the r72 ProjectE cycle-closing EMC overrides."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT_BASE = ROOT / "W/r70-release/IC2-New-Era-Windows-1.20.1-r70-Grove-CMM.mrpack"
SERVER_BASE = ROOT / "W/r70-release/IC2-New-Era-Server-1.20.1-r70-Voice.zip"
BALANCE = Path(__file__).with_name("emc_balance.json")
CONFIG = "config/ProjectE/custom_emc.json"
PROFILE_CONFIG = ROOT.parent / ".minecraft/versions/1.20.1-Forge_47.4.28" / CONFIG
PROFILE_ROOT = ROOT.parent / ".minecraft/versions/1.20.1-Forge_47.4.28"
PROJECTE_JAR_NAME = "projecte-1201-PE1.0.1.jar"
PROJECTE_PATHS = (
    ROOT.parent / ".minecraft/versions/1.20.1-Forge_47.4.28/mods" / PROJECTE_JAR_NAME,
    ROOT / "build-isolated/player-packaging/20261114-r72-standalone/pack/inputs/client/mods" / PROJECTE_JAR_NAME,
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def validate(doc: object, label: str) -> dict:
    if not isinstance(doc, dict) or not isinstance(doc.get("entries"), list):
        raise ValueError(f"{label} must contain an entries array")
    seen = set()
    for row in doc["entries"]:
        if (not isinstance(row, dict) or not isinstance(row.get("item"), str)
                or not isinstance(row.get("emc"), int) or isinstance(row.get("emc"), bool)
                or row["emc"] < 0 or row["item"] in seen):
            raise ValueError(f"Invalid or duplicate custom EMC row in {label}: {row!r}")
        seen.add(row["item"])
    return doc


def compose(base_data: bytes) -> bytes:
    doc = validate(json.loads(base_data.decode("utf-8")), "r70 baseline")
    spec = json.loads(BALANCE.read_text(encoding="utf-8"))
    actual_projecte = [path for path in PROJECTE_PATHS if path.is_file()]
    if not actual_projecte or any(digest(path.read_bytes()) != spec["projecteJarSha256"] for path in actual_projecte):
        raise ValueError("The active/source ProjectE JAR does not match the reviewed 1.0.1 artifact SHA-256")
    mod_dir = ROOT / "build-isolated/player-packaging/20261114-r72-standalone/pack/inputs/client/mods"
    for filename, expected in spec["recipeJarSha256s"].items():
        path = mod_dir / filename
        if not path.is_file() or digest(path.read_bytes()) != expected:
            raise ValueError(f"Recipe evidence JAR does not match the reviewed source: {path}")
    minecraft_jar = PROFILE_ROOT / "1.20.1-Forge_47.4.28.jar"
    if not minecraft_jar.is_file() or digest(minecraft_jar.read_bytes()) != spec["minecraftJarSha256"]:
        raise ValueError("The active Minecraft 1.20.1 jar differs from the reviewed recipe source")
    updates = spec["values"]
    conversion_rel = spec["runtimeConversionResource"]
    conversion_path = ROOT / "build-isolated/migration-1201/newera/src/main/resources" / conversion_rel
    if not conversion_path.is_file() or digest(conversion_path.read_bytes()) != spec["runtimeConversionResourceSha256"]:
        raise ValueError("The reviewed runtime ProjectE after-table resource is missing or has changed")
    after = json.loads(conversion_path.read_text(encoding="utf-8"))["values"]["after"]
    required_after = {
        "#forge:ingots/iron": 252, "#forge:ingots/gold": 2016, "#forge:ingots/copper": 126,
        "#forge:gems/diamond": 8064, "#forge:gems/emerald": 16128, "#forge:gems/quartz": 94,
        "#forge:nuggets/gold": 224, "minecraft:gold_nugget": 224,
        "#forge:nuggets/copper": 14, "create:copper_nugget": 14,
        "minecraft:soul_sand": 49, "minecraft:soul_soil": 1,
        "minecraft:heart_of_the_sea": 6640, "minecraft:trident": 8944,
        "minecraft:netherite_ingot": 28602, "minecraft:sentry_armor_trim_smithing_template": 56449,
        "create:andesite_alloy": 44, "create:andesite_alloy_block": 396,
    }
    if any(after.get(key) != value for key, value in required_after.items()):
        raise ValueError("The after-table does not preserve the reviewed runtime EMC values")
    if updates.get("create:andesite_alloy") != 44 or updates.get("create:andesite_alloy_block") != 396:
        raise ValueError("Andesite alloy and its 9:1 storage block must be clamped to 44 and 396")
    if "minecraft:copper_nugget" in updates or updates.get("create:copper_nugget") != 14:
        raise ValueError("Copper nugget EMC must target the registered Create item, not a nonexistent vanilla ID")
    if any(updates.get(key) != value for key, value in {
            "minecraft:quartz": 94, "minecraft:gold_nugget": 224,
            "minecraft:soul_sand": 49, "minecraft:soul_soil": 1}.items()):
        raise ValueError("The shared EMC config does not protect quartz, gold nugget, soul sand, and soul soil")
    rows = doc["entries"]
    by_id = {row["item"]: row for row in rows}
    for item, value in updates.items():
        if item in by_id:
            by_id[item]["emc"] = value
        else:
            row = {"item": item, "emc": value}
            rows.append(row)
            by_id[item] = row
    # Preserve the frozen baseline's 71 zero registrations and quartz override.
    zeros = [row for row in rows if row["item"].startswith(("create:", "ic2:", "ic2newera:", "laowu:")) and row["emc"] == 0]
    if len(zeros) != 71 or by_id.get("minecraft:quartz", {}).get("emc") != 94:
        raise ValueError("Baseline no longer matches the approved 71 zero entries + unchanged quartz=94")
    validate(doc, "composed r72 config")
    return (json.dumps(doc, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def baseline_config() -> bytes:
    with zipfile.ZipFile(CLIENT_BASE) as client, zipfile.ZipFile(SERVER_BASE) as server:
        c = client.read("overrides/" + CONFIG)
        root = "IC2-New-Era-Server-1.20.1-r70-Voice/"
        s = server.read(root + CONFIG)
    if c != s:
        raise ValueError("Frozen r70 client/server ProjectE configs differ")
    return c


def merged_profile(current_data: bytes, incoming_data: bytes) -> bytes:
    current = validate(json.loads(current_data.decode("utf-8")), "profile config")
    incoming = validate(json.loads(incoming_data.decode("utf-8")), "r72 overlay")
    incoming_by_id = {row["item"]: row["emc"] for row in incoming["entries"]}
    current_ids = {row["item"] for row in current["entries"]}
    # Migrate only the one invalid key this release previously wrote; keep its row position.
    if ("minecraft:copper_nugget" in current_ids
            and "create:copper_nugget" not in current_ids
            and current_ids and incoming_by_id.get("create:copper_nugget") == 14):
        for row in current["entries"]:
            if row["item"] == "minecraft:copper_nugget" and row["emc"] == 14:
                row["item"] = "create:copper_nugget"
                current_ids.remove("minecraft:copper_nugget")
                current_ids.add("create:copper_nugget")
                break
    old_ids = {row["item"] for row in current["entries"]}
    for row in current["entries"]:
        if row["item"] in incoming_by_id:
            row["emc"] = incoming_by_id[row["item"]]
    for row in incoming["entries"]:
        if row["item"] not in old_ids:
            current["entries"].append(dict(row))
    validate(current, "merged profile config")
    return (json.dumps(current, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def apply_profile(path: Path, incoming: bytes) -> None:
    # Query only client/server Java process names; do not inspect command lines.
    query = "@(Get-Process -Name java,javaw -ErrorAction SilentlyContinue).Count"
    count = int(subprocess.check_output(["powershell", "-NoProfile", "-Command", query], text=True).strip())
    if count:
        raise RuntimeError("Java is running; close Minecraft and retry. No config was changed.")
    original = path.read_bytes() if path.is_file() else b'{"entries": []}\n'
    output = merged_profile(original, incoming)
    path.parent.mkdir(parents=True, exist_ok=True)
    backup = path.with_name(path.name + ".r72-backup-" + digest(original)[:12]) if path.exists() else None
    if backup and not backup.exists():
        shutil.copy2(path, backup)
    fd, temp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(output)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_name, path)
    finally:
        Path(temp_name).unlink(missing_ok=True)
    print(json.dumps({"path": str(path), "beforeSha256": digest(original),
                      "afterSha256": digest(output), "backup": str(backup) if backup else None,
                      "managedOverrides": len(json.loads(incoming)["entries"]) - 72}, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile-config", type=Path, default=PROFILE_CONFIG)
    parser.add_argument("--apply", action="store_true", help="Back up then merge this config; default is preview only")
    args = parser.parse_args()
    incoming = compose(baseline_config())
    if not args.apply:
        current = args.profile_config.read_bytes() if args.profile_config.is_file() else b'{"entries": []}\n'
        output = merged_profile(current, incoming)
        print(json.dumps({"profileConfig": str(args.profile_config), "beforeSha256": digest(current),
                          "previewSha256": digest(output), "addedOrUpdated": json.loads(incoming)["entries"][-16:],
                          "applyCommand": f'python scripts/standalone-r72/emc_balance.py --apply --profile-config "{args.profile_config}"'},
                         ensure_ascii=False, indent=2))
        return
    apply_profile(args.profile_config, incoming)


if __name__ == "__main__":
    main()
