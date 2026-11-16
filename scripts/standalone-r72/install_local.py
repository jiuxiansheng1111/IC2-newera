"""Install r72 in the ordinary Forge profile, with reversible r71 file changes."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import zipfile


REPO = Path(__file__).resolve().parents[2]
PACKS = REPO / "build-isolated/player-packaging"
OLD = PACKS / "20261113-delight-joint"
WORK = PACKS / "20261114-r72-standalone"
BASE = REPO / "W/r70-release/IC2-New-Era-Windows-1.20.1-r70-Grove-CMM.mrpack"
OWN = "ic2newera-0.2.0-migration1201-all.jar"
QUESTS = "config/ftbquests/quests"
AI = "config/ic2newera/quest_knowledge.json"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inside(root, relative):
    path = (root / relative).resolve()
    if path == root or not path.is_relative_to(root.resolve()):
        raise ValueError(f"Unsafe path {relative}")
    return path


def protected(rel):
    name = rel.lower()
    return (name.startswith(("pcl/", "saves/", "local/", "logs/", "screenshots/", "xaero/"))
            or any(token in name for token in ("api_key", "api-key", "apikey", "credential", "account", "secret", "token", "robot-voice")))


def game_closed():
    query = ("@(Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' OR Name='java.exe'\" "
             "| Where-Object { $_.CommandLine -match 'net.minecraft.client.main.Main|BootstrapLauncher.*forgeclient|--launchTarget\\s+forgeclient' }).Count")
    count = subprocess.check_output(["powershell", "-NoProfile", "-Command", query], text=True)
    if int(count.strip()):
        raise RuntimeError("A Minecraft client is running")


def link_file(source, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(source, dest)
    except OSError:
        shutil.copy2(source, dest)


def upgraded_camera_keys(options):
    bindings = {
        "phone_camera_view": ("v", "f6"), "phone_camera_capture": ("p", "f8"),
        "phone_camera_upload": ("o", "f9"), "phone_gallery": ("g", "insert")
    }
    contents = options.read_bytes()
    for name, (old, new) in bindings.items():
        key = f"key_key.ic2newera.{name}:key.keyboard.".encode()
        contents = b"".join(
            key + new.encode() + line[len(key + old.encode()):]
            if line.rstrip(b"\r\n") == key + old.encode() else line
            for line in contents.splitlines(keepends=True))
    return contents


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-mods", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--native-only", action="store_true",
                        help="Update just New Era in an existing r72 profile, preserving its current quest and player files")
    parser.add_argument("--native-artifact", type=Path,
                        help="Use a later native build and its adjacent native-manifest.json for a native-only update")
    args = parser.parse_args()
    receipt = next((OLD / "existing-forge-backups/1.20.1-Forge_47.4.28").glob("*/install-receipt.json"))
    old = json.loads(receipt.read_bytes())
    profile = Path(old["profile"]).resolve(strict=True)
    if profile != (REPO.parent / ".minecraft/versions/1.20.1-Forge_47.4.28").resolve(strict=True):
        raise ValueError("Unexpected Forge profile")
    mods = inside(profile, "mods")
    targets = {jar.name: jar.resolve() for jar in args.client_mods.glob("*.jar")}
    own = WORK / "native" / OWN
    if args.native_artifact:
        candidate = args.native_artifact.resolve(strict=True)
        if not args.native_only or candidate.name != OWN:
            raise ValueError("A native artifact is only valid for an existing profile in native-only mode")
        manifest = json.loads((candidate.parent / "native-manifest.json").read_bytes())
        if sha(candidate) != manifest["sha256"].lower():
            raise ValueError("Requested native artifact does not match its build receipt")
        own = candidate
    if OWN not in targets or len(targets) != 46 or not own.is_file():
        raise ValueError("The complete 46-JAR r72 client and its final native JAR are required")
    targets[OWN] = own
    previous = {row["name"] for row in json.loads((OLD / "selection.json").read_bytes())}
    unknown = {jar.name for jar in mods.glob("*.jar")} - (previous | set(targets))
    if unknown:
        raise ValueError(f"Unclassified local mods: {sorted(unknown)}")
    overlay = WORK / "handbook-overlay"
    chapter_src = overlay / QUESTS
    if not (overlay / AI).is_file() or len(list((chapter_src / "chapters").glob("*.snbt"))) != 23:
        raise ValueError("Incomplete r72 quest overlay")
    r72_installed = False
    camera_migrated = False
    for previous_journal in (WORK / "local-install").glob("*/transaction.json"):
        history = json.loads(previous_journal.read_bytes())
        if history.get("state") == "installing":
            raise RuntimeError(f"Interrupted installation requires recovery from {previous_journal}")
        if history.get("state") == "installed":
            r72_installed = set(targets) == {p.name for p in mods.glob("*.jar")}
            camera_migrated |= history.get("cameraBindingsMigrated", False)
    if args.native_only and not r72_installed:
        raise ValueError("A native-only update requires an installed r72 mod set")
    instance = inside(WORK, "local-install/" + uuid.uuid4().hex)
    stage = inside(instance, "new")
    backup = inside(instance, "previous")
    # Each planned change can move one original aside and install one replacement.
    changes = {}
    kept = []
    moved_content = {"kubejs", "ldlib", "hotai", "quests_structures", "moonlight-global-datapacks"}
    for folder in moved_content:
        if not r72_installed and inside(profile, folder).is_dir():
            changes[folder] = None
    if not r72_installed:
        changes["mods"] = stage / "mods"
    else:
        for name, source in targets.items():
            if args.native_only and name != OWN:
                continue
            if sha(source) != sha(mods / name):
                changes["mods/" + name] = source
    installed_chapters = inside(profile, QUESTS)
    chapters = {p.relative_to(chapter_src): sha(p) for p in chapter_src.rglob("*") if p.is_file()}
    active = {p.relative_to(installed_chapters): sha(p) for p in installed_chapters.rglob("*") if p.is_file()}
    if not args.native_only and chapters != active:
        changes[QUESTS] = stage / QUESTS
    if not args.native_only and (not inside(profile, AI).is_file() or sha(inside(profile, AI)) != sha(overlay / AI)):
        changes[AI] = overlay / AI
    options = inside(profile, "options.txt")
    if not args.native_only and not camera_migrated and options.exists():
        data = upgraded_camera_keys(options)
        if data != options.read_bytes():
            dest = inside(stage, "options.txt")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            changes["options.txt"] = dest
    with zipfile.ZipFile(BASE) as archive:
        for row in ([] if r72_installed else old["operations"]):
            path = row["path"]
            root = path.split("/")[0]
            if (root in moved_content | {"mods", "IC2-NewEra-info"} or path == "LICENSE"
                or path == AI or path.startswith(QUESTS + "/") or protected(path)
                or path == "resourcepacks/NewEra-CMM-Model-Fix.zip"):
                continue
            if root not in {"config", "defaultconfigs", "tacz", "resourcepacks", "shaderpacks"}:
                continue
            existing = inside(profile, path)
            if not existing.is_file():
                continue
            # Retain player files and preferences that changed after the r71 install.
            # Managed content packs and stock defaults follow the standalone release.
            expected = row.get("actualNewSha256") or row.get("expectedNewSha256")
            if expected and sha(existing) != expected.lower() and root == "config" and path != "config/ProjectE/custom_emc.json":
                kept.append(path)
                continue
            member = "overrides/" + path
            if member in archive.namelist():
                data = archive.read(member)
                if hashlib.sha256(data).hexdigest() != sha(existing):
                    dest = inside(stage, path)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
                    changes[path] = dest
            elif row.get("backup"):
                saved = inside(receipt.parent, row["backup"])
                if not saved.is_file() or sha(saved) != row["expectedOldSha256"].lower():
                    raise ValueError(f"Pre-r71 backup unavailable for {path}")
                changes[path] = saved
            elif row["action"] == "install":
                changes[path] = None
    print(f"Target {len(targets)} JARs, {len(changes)} managed paths, {len(kept)} local config edits retained", flush=True)
    if not args.apply or not changes:
        return
    game_closed()
    if "mods" in changes:
        for name, source in targets.items():
            link_file(source, inside(stage, "mods/" + name))
    if QUESTS in changes:
        shutil.copytree(chapter_src, inside(stage, QUESTS))
    backup.mkdir(parents=True)
    journal = instance / "transaction.json"
    record = {"state": "installing", "profile": str(profile), "preservedEditedConfigs": kept, "operations": []}
    def save():
        temp = journal.with_suffix(".tmp")
        temp.write_text(json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
        for attempt in range(8):
            try:
                os.replace(temp, journal)
                return
            except PermissionError:
                if attempt == 7:
                    raise
                time.sleep(0.04 * (attempt + 1))
    save()
    try:
        for path, source in changes.items():
            destination, saved = inside(profile, path), inside(backup, path)
            op = {"path": path, "hadOriginal": destination.exists(), "state": "planned"}
            record["operations"].append(op)
            save()
            if op["hadOriginal"]:
                saved.parent.mkdir(parents=True, exist_ok=True)
                os.rename(destination, saved)
            op["state"] = "backed_up"
            if source:
                destination.parent.mkdir(parents=True, exist_ok=True)
                if source.is_relative_to(stage):
                    os.rename(source, destination)
                else:
                    shutil.copy2(source, destination)
            op["state"] = "installed"
        if set(p.name for p in mods.glob("*.jar")) != set(targets):
            raise RuntimeError("Installed mod set differs")
        checked = {OWN: own} if args.native_only else targets
        if any(sha(mods / name) != sha(source) for name, source in checked.items()):
            raise RuntimeError("Installed mod bytes differ")
        record["modCount"] = len(targets)
        record["cameraBindingsMigrated"] = camera_migrated if args.native_only else True
        record["nativeSha256"] = sha(mods / OWN)
        record["state"] = "installed"
        save()
    except BaseException:
        for op in reversed(record["operations"]):
            path = op["path"]
            dest, saved = inside(profile, path), inside(backup, path)
            if op["state"] != "planned" and dest.exists():
                displaced = inside(instance, "interrupted/" + path)
                displaced.parent.mkdir(parents=True, exist_ok=True)
                os.rename(dest, displaced)
            if saved.exists():
                os.rename(saved, dest)
        record["state"] = "restored"
        save()
        raise
    print(journal, flush=True)


if __name__ == "__main__":
    main()
