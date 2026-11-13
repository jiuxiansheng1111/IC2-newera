"""Safely replace one selected mod JAR in a receipted PCL installation.

This deliberately leaves the version folder, options, configuration, saves,
shared libraries and every other installed file in place.
"""
import argparse
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

from advisory_lock import serialize_r71_release
from build_joint import HOTFIX_LOCK, description, digest, read


def canonical_fingerprint(value):
    raw = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def write_atomic(path, value):
    temp = path.with_name(path.name + f".tmp-{os.getpid()}")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temp, path)


@serialize_r71_release
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work", type=Path, required=True)
    ap.add_argument("--profile", type=Path, required=True, help="The source Forge profile used by r71")
    ap.add_argument("--name", required=True, help="Existing isolated version ID")
    ap.add_argument("--mod", required=True, help="Selected JAR filename to replace")
    args = ap.parse_args()
    work, profile = args.work.resolve(), args.profile.resolve()
    dest = profile.parent / args.name
    receipt_path = dest / "installed.json"
    if not receipt_path.is_file():
        raise FileNotFoundError(f"No installation receipt: {receipt_path}")
    receipt = read(receipt_path)
    receipt_sha_before = digest(receipt_path)
    if receipt.get("versionId") != args.name or not receipt.get("isolatedGameDirectory"):
        raise ValueError("Destination is not the requested receipted isolated instance")
    staged_selection = read(work / "selection.json")
    matches = [r for r in staged_selection if r.get("name") == args.mod]
    if len(matches) != 1:
        raise ValueError(f"selection.json must contain exactly one {args.mod}")
    selected = matches[0]
    source = Path(selected["path"]).resolve()
    target_rel = "mods/" + args.mod
    target = dest / target_rel
    if not source.is_file():
        raise FileNotFoundError(f"Selected replacement JAR is missing: {source}")
    new_ids = sorted(x[0] for x in description(source))
    lock = read(HOTFIX_LOCK)
    preserve_local = set(lock.get("preserveLocalOverlayFiles", []))
    local_preserved = {}
    # Launcher-owned settings may be edited after installation; keep their
    # bytes and fold the observed digest into the refreshed installation receipt.
    for row in receipt["installedFiles"]:
        path = dest / Path(row["path"])
        if row.get("kind") == "pack-file" and row["path"] in preserve_local:
            actual = digest(path) if path.is_file() else None
            local_preserved[row["path"]] = actual
            continue
        if not path.is_file() or digest(path) != row["sha256"]:
            raise ValueError(f"Installation differs from receipt; no changes made: {row['path']}")
    old_rows = []
    for row in receipt["installedFiles"]:
        if row.get("kind") == "mod" and sorted(x[0] for x in description(dest / row["path"])) == new_ids:
            old_rows.append(row)
    if len(old_rows) != 1:
        raise ValueError("Expected exactly one installed JAR with the replacement's mod IDs")
    old_rel = old_rows[0]["path"]
    old_target = dest / old_rel
    if old_rel == target_rel:
        if digest(old_target) != old_rows[0]["sha256"]:
            raise ValueError("Installed JAR does not match its receipt; no changes made")
    elif target.exists():
        raise FileExistsError(f"New filename already exists in installed instance: {target}")
    new_hash = digest(source)
    if new_hash != selected.get("sha256"):
        raise ValueError("Replacement JAR differs from selection lock")
    mod_changed = old_rel != target_rel or digest(old_target) != new_hash
    overlay = work / "client-overlay"
    old_overlay_rows = {r["path"]: r for r in receipt["installedFiles"] if r.get("kind") == "pack-file"}
    overlay_sources = {p.relative_to(overlay).as_posix(): p for p in overlay.rglob("*") if p.is_file()}
    for rel in local_preserved:
        overlay_sources.pop(rel, None)
    exclusions = {rel for patch in lock["patches"] for rel in patch.get("excludeOverlayFiles", [])}
    overlay_removed = exclusions & set(old_overlay_rows)
    preserved_paths = set(local_preserved)
    if set(old_overlay_rows) - exclusions - preserved_paths != set(overlay_sources) or exclusions & set(overlay_sources):
        raise ValueError("Overlay file set changed; targeted updater only accepts byte replacements")
    overlay_changes = {rel for rel, src in overlay_sources.items()
                       if digest(src) != old_overlay_rows[rel]["sha256"]}
    fingerprint_data = json.loads(json.dumps(receipt["inputFingerprintData"]))
    old_selection = fingerprint_data["selection"]
    old_entry = [old_rel, old_rows[0]["sha256"], old_rows[0]["server"]]
    if old_entry not in old_selection:
        raise ValueError("Receipt fingerprint selection does not contain the installed JAR")
    fingerprint_data["selection"].remove(old_entry)
    fingerprint_data["selection"].append([target_rel, new_hash, bool(selected["server"])])
    fingerprint_data["selection"].sort()
    fingerprint_overlay = [(rel, digest(src)) for rel, src in overlay_sources.items()]
    fingerprint_overlay.extend((rel, value) for rel, value in local_preserved.items() if value is not None)
    fingerprint_data["overlay"] = sorted(fingerprint_overlay)
    new_receipt = json.loads(json.dumps(receipt))
    new_receipt["inputFingerprintData"] = fingerprint_data
    new_receipt["inputFingerprint"] = canonical_fingerprint(fingerprint_data)
    new_receipt["installedFiles"] = [r for r in new_receipt["installedFiles"]
                                      if not (r.get("path") == old_rel and r.get("kind") == "mod")
                                      and not (r.get("kind") == "pack-file" and r.get("path") in overlay_removed)
                                      and not (r.get("kind") == "pack-file" and r.get("path") in local_preserved
                                               and local_preserved[r.get("path")] is None)]
    new_receipt["installedFiles"].append({"path": target_rel, "sha256": new_hash, "kind": "mod",
                                           "server": bool(selected["server"])})
    for row in new_receipt["installedFiles"]:
        if row.get("kind") == "pack-file":
            if row["path"] in local_preserved:
                row["sha256"] = local_preserved[row["path"]]
            else:
                row["sha256"] = digest(overlay_sources[row["path"]])
    backup_dir = (work / "deployment-history" / "r71-mod-hotfix" /
                  f"{selected['primary']}-{receipt['inputFingerprint'][:12]}-{new_receipt['inputFingerprint'][:12]}")
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup = backup_dir / (Path(old_rel).name + ".pre-hotfix.bak")
    if mod_changed and backup.exists():
        raise FileExistsError(f"Preserving existing backup; refusing to replace: {backup}")
    overlay_backups = {}
    for rel in overlay_changes | overlay_removed:
        overlay_backup = backup_dir / "overlay" / (rel.replace("/", "__") + ".pre-hotfix.bak")
        if overlay_backup.exists():
            raise FileExistsError(f"Preserving existing backup; refusing to replace: {overlay_backup}")
        overlay_backups[rel] = overlay_backup
    if digest(receipt_path) != receipt_sha_before:
        raise FileExistsError("Instance receipt changed during patch preparation; no files were changed")
    for row in receipt["installedFiles"]:
        path = dest / Path(row["path"])
        if row.get("kind") == "pack-file" and row["path"] in local_preserved:
            actual = digest(path) if path.is_file() else None
            if actual != local_preserved[row["path"]]:
                raise FileExistsError(f"Preserved local setting changed during preparation: {row['path']}")
            continue
        if not path.is_file() or digest(path) != row["sha256"]:
            raise FileExistsError(f"Instance changed during patch preparation; no files were changed: {row['path']}")
    if mod_changed:
        shutil.copy2(old_target, backup)
    temp = target.with_name(target.name + f".hotfix-{os.getpid()}")
    overlay_replaced = []
    try:
        if mod_changed:
            shutil.copy2(source, temp)
            if digest(temp) != new_hash:
                raise IOError("Staged JAR hash mismatch")
            os.replace(temp, target)
            if old_rel != target_rel:
                old_target.unlink()
        if digest(target) != new_hash:
            raise IOError("Installed JAR hash mismatch")
        for rel in sorted(overlay_changes):
            installed_path = dest / Path(rel)
            backup_path = overlay_backups[rel]
            backup_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(installed_path, backup_path)
            overlay_temp = installed_path.with_name(installed_path.name + f".hotfix-{os.getpid()}")
            shutil.copy2(overlay_sources[rel], overlay_temp)
            if digest(overlay_temp) != digest(overlay_sources[rel]):
                raise IOError(f"Overlay copy hash mismatch: {rel}")
            os.replace(overlay_temp, installed_path)
            overlay_replaced.append(rel)
        for rel in sorted(overlay_removed):
            installed_path = dest / Path(rel)
            backup_path = overlay_backups[rel]
            backup_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(installed_path, backup_path)
            installed_path.unlink()
            overlay_replaced.append(rel)
        write_atomic(receipt_path, new_receipt)
        write_atomic(work / "installed.json", new_receipt)
    except Exception:
        for rel in reversed(overlay_replaced):
            shutil.copy2(overlay_backups[rel], dest / Path(rel))
        if mod_changed and backup.is_file():
            target.unlink(missing_ok=True)
            shutil.copy2(backup, old_target)
        write_atomic(receipt_path, receipt)
        write_atomic(work / "installed.json", receipt)
        temp.unlink(missing_ok=True)
        raise
    print(json.dumps({"status": "updated", "destination": str(target), "oldSha256": old_rows[0]["sha256"],
                      "newSha256": new_hash, "backup": str(backup),
                      "removedOverlayFiles": sorted(overlay_removed),
                      "inputFingerprint": new_receipt["inputFingerprint"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
