"""Select the two real distributions by identity, build the native overlays and export PCL."""
import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import tomllib
from pathlib import Path, PurePosixPath
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED

from advisory_lock import serialize_r71_release
from quest_knowledge import build_catalogue
from repair_extendedae_refmap import repair_extendedae_refmap, UPSTREAM_FIX_SHA256
from repair_tacz_lod import repair_installed_tree

ROOT = Path(__file__).resolve().parents[2]
M = ROOT / "build-isolated/migration-1201/newera"
W = ROOT / "build-isolated/player-packaging/20261113-delight-joint"
HOTFIX_LOCK = Path(__file__).with_name("hotfix-lock-r71.json")
BASE = W / "quests-work/base.mrpack"
R70 = Path(os.environ.get("NEWERA_R70_RELEASE") or (
    ROOT / "W/r70-release" if (ROOT / "W/r70-release").is_dir() else ROOT / "dist/IC2-New-Era-r70"
))
PROFILE = Path(os.environ.get("NEWERA_R70_PROFILE") or json.loads((M / "build/deployment/pcl-installation-1201.json").read_text("utf-8-sig"))["versionDirectory"])
CLIENT = next(R70.glob("*Windows*.mrpack"))
SERVER = next(R70.glob("*Server*.zip"))
OVERLAY = W / "client-overlay"
OUT = ROOT / "dist/Create-Delight-New-Era-r71"
NAME = "Create-Delight-New-Era-r71"
R71_TASK_SNAPSHOT = ROOT / "build-isolated/.r71-tacz12-pre-overlay"


def read(path):
    return json.loads(path.read_text("utf-8-sig"))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest(path, kind="sha256"):
    with path.open("rb") as f:
        return hashlib.file_digest(f, kind).hexdigest()


def safe(folder, member):
    rel = PurePosixPath(member)
    if rel.is_absolute() or ".." in rel.parts or "\\" in member or ":" in member:
        raise ValueError(member)
    result = folder.joinpath(*rel.parts)
    if not result.resolve().is_relative_to(folder.resolve()):
        raise ValueError(member)
    result.parent.mkdir(parents=True, exist_ok=True)
    return result


def description(path):
    with ZipFile(path) as jar:
        if "META-INF/mods.toml" not in jar.namelist():
            if path.name.startswith("kotlinforforge-"):
                return [("kotlinforforge", path.name.split("-")[1], "Kotlin for Forge")]
            raise ValueError(f"Unexpected non-Forge artifact: {path}")
        mods = tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8-sig"))["mods"]
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", "replace").replace("\r\n ", "").splitlines() if "META-INF/MANIFEST.MF" in jar.namelist() else []
        implementation = next((s.partition(": ")[2] for s in manifest if s.lower().startswith("implementation-version: ")), "")
        return [(m["modId"], str(m.get("version", "")).replace("${file.jarVersion}", implementation), m.get("displayName", m["modId"])) for m in mods]


def select(built=None):
    if built is None:
        built = M / "build/libs/ic2newera-0.2.0-migration1201-all.jar"
    with ZipFile(built) as newera:
        if "ic2newera.bulk-compat.mixins.json" not in newera.namelist():
            raise ValueError("Build the r71 startup fix before selecting the release JARs")
    acquisition = read(W / "acquisition.json")
    source_side = read(Path(__file__).with_name("server-sides.json"))["sideClassification"]
    both = set(source_side["baseServerFiles"] + source_side["upstreamOverrideAddedFiles"])
    client_only = set(source_side["clientOnly"])
    hotfix_lock = read(HOTFIX_LOCK)
    patches = hotfix_lock.get("patches", [])
    superseded = {p["replaces"] for p in patches}
    patched_names = {p["filename"] for p in patches}
    for patch in patches:
        old, new = patch["replaces"], patch["filename"]
        old_sides = (old in both, old in client_only)
        new_sides = (new in both, new in client_only)
        if sum(old_sides) == 1:
            if any(new_sides): raise ValueError(f"Both old and locked names are classified: {old} / {new}")
            (both if old_sides[0] else client_only).remove(old)
            (both if patch["server"] else client_only).add(new)
        elif old_sides == (False, False) and new_sides == (patch["server"], not patch["server"]):
            pass  # Also accept a workspace whose source-side classification was already updated.
        else:
            raise ValueError(f"Hotfix side classification is missing or ambiguous: {old} / {new}")
        if (new in both) != bool(patch["server"]) or not patch["client"]:
            raise ValueError(f"Locked side classification is inconsistent: {new}")
    base_index = read(W / "reference-index.json")
    byname = {PurePosixPath(row["path"]).name: row for row in base_index["files"] if row["path"].startswith("mods/")}
    with ZipFile(SERVER) as z:
        old_server = {PurePosixPath(n).name for n in z.namelist() if "/mods/" in n and n.endswith(".jar")}
    with ZipFile(CLIENT) as z:
        rindex = json.loads(z.read("modrinth.index.json"))
        rfiles = {PurePosixPath(row["path"]).name: row for row in rindex["files"] if row["path"].startswith("mods/")}
        raw = {PurePosixPath(n).name: z.read(n) for n in z.namelist() if n.startswith("overrides/mods/") and n.endswith(".jar")}
    sources = []
    acquired_rows = {name: value for name, value in acquisition["rows"].items()
                     if name not in superseded and name not in patched_names}
    for patch in patches:
        acquired_rows[patch["filename"]] = "verified-stage"
    embedded_rows = {name: "verified-stage" for name in acquisition["embedded"]
                     if name not in superseded and name not in patched_names}
    for name, value in {**acquired_rows, **embedded_rows}.items():
        path = W / "incoming-mods" / name if value in ("download", "verified-stage") else Path(value)
        if not (name in both or name in client_only):
            raise ValueError(f"Unclassified server file {name}")
        locked = next((p for p in patches if p["filename"] == name), None)
        if locked:
            if path.stat().st_size != locked["fileSize"] or digest(path, "sha512") != locked["sha512"].lower():
                raise ValueError(f"Locked size/SHA-512 mismatch: {name}")
            mod_ids = tuple(sorted(m[0] for m in description(path)))
            if mod_ids != (locked["modId"],):
                raise ValueError(f"Locked Modrinth version has unexpected mod identity: {name}: {mod_ids}")
        index_row = byname.get(name)
        if name == "extendedae_plus-1.5.5.jar":
            # The official 1.6.4 target mapping works with 1.5.5. Updating the entire
            # mod would require a different installed JEI; do not re-download stock 1.5.5 in PCL.
            reference = W / "r71-three-fixes/vendor/extendedae_plus-1.6.4-forge-1.20.1.jar"
            if not reference.is_file():
                from urllib.request import urlopen
                reference.parent.mkdir(parents=True, exist_ok=True)
                with urlopen("https://cdn.modrinth.com/data/xr109llC/versions/auasuDQR/extendedae_plus-1.6.4.jar", timeout=45) as response:
                    payload = response.read()
                if hashlib.sha256(payload).hexdigest() != UPSTREAM_FIX_SHA256:
                    raise ValueError("Author reference SHA-256 mismatch")
                reference.write_bytes(payload)
            repaired = repair_extendedae_refmap(path, reference, W / "r71-three-fixes/ae" / name)
            if repaired.output_sha256 != "e66afb708b3266ed762d7b55b05cd6c01251a081d02829b3dbc28f0a6515821b":
                raise ValueError("Unexpected r71 ExtendedAE Plus refmap output")
            path, index_row = repaired.output_jar, None
        sources.append((name, path, "delight", name in both, index_row))
    for name in sorted(raw.keys() | rfiles.keys()):
        path = PROFILE / "mods" / name
        if name not in raw:
            row = rfiles[name]
            if digest(path, "sha512") != row["hashes"]["sha512"]:
                raise ValueError(f"Local r70 file diverges from its locked install {name}")
        elif hashlib.sha256(raw[name]).hexdigest() != digest(path):
            raise ValueError(f"Local custom file diverges from its locked r70 archive {name}")
        if built and name.startswith("ic2newera-"):
            path = built
        sources.append((name, path, "newera", name in old_server, None if name in raw else rfiles[name]))
    groups = {}
    for name, path, origin, server, index in sources:
        desc = description(path)
        # Explicit identity also catches two differently named IMBlocker files inside the base pack.
        ids = tuple(sorted(m[0] for m in desc))
        groups.setdefault(ids, []).append(dict(name=name, path=str(path.resolve()), origin=origin, server=server,
                                               ids=list(ids), version=desc[0][1], display=desc[0][2], primary=desc[0][0],
                                               index=index, sha256=digest(path)))
    prefer_r70 = {"ic2newera", "farmersdelight", "ae2", "jei", "immersive_aircraft", "xaerominimap", "yes_steve_model", "imblocker"}
    picks, duplicates, known = [], [], set()
    for ids, rows in groups.items():
        if "irisflw" in ids and any("colorwheel" in identity for identity in groups):
            duplicates.append({"ids": ids, "inputs": [r["name"] for r in rows], "selected": None,
                               "reason": "Colorwheel supplies Flywheel shader integration and excludes irisflw via its client dependency range."})
            continue
        if known.intersection(ids):
            raise ValueError(f"Partial mod ID conflict {ids}")
        known.update(ids)
        chosen = next((r for r in rows if r["origin"] == ("newera" if prefer_r70.intersection(ids) else "delight")), rows[0])
        if len(rows) > 1:
            duplicates.append({"ids": ids, "inputs": [r["name"] for r in rows], "selected": chosen["name"]})
        chosen = dict(chosen)
        chosen["server"] = any(r["server"] for r in rows)
        picks.append(chosen)
    picks.sort(key=lambda r: r["name"].lower())
    write(W / "selection.json", picks)
    write(W / "overlap.json", duplicates)
    print(f"Selected {len(picks)} client / {sum(r['server'] for r in picks)} server; {len(sources) - len(picks)} redundant files")
    return picks


def overlay(picks):
    backup = ROOT / "build-isolated/.r71-tacz12-pre-overlay"
    if OVERLAY.is_dir() and not backup.exists():
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(OVERLAY, backup)
    previous_overlay = W / "client-overlay.pre-rebuild"
    if previous_overlay.exists():
        if not previous_overlay.resolve().is_relative_to(W.resolve()): raise ValueError(previous_overlay)
        try:
            shutil.rmtree(previous_overlay)
        except OSError:
            previous_overlay = W / f"client-overlay.pre-rebuild-{os.getpid()}"
    if OVERLAY.exists():
        if not OVERLAY.resolve().is_relative_to(W.resolve()): raise ValueError(OVERLAY)
        os.replace(OVERLAY, previous_overlay)
    OVERLAY.mkdir(parents=True, exist_ok=True)
    locked_exclusions = {rel for patch in read(HOTFIX_LOCK)["patches"]
                         for rel in patch.get("excludeOverlayFiles", [])}
    with ZipFile(BASE) as base, ZipFile(CLIENT) as new:
        for z, source in ((base, "base"), (new, "r70")):
            for entry in z.infolist():
                if not entry.filename.startswith("overrides/") or entry.is_dir(): continue
                rel = entry.filename[len("overrides/"):]
                if rel in locked_exclusions: continue
                if rel.startswith(("mods/", "config/ftbquests/quests/")): continue
                if rel.startswith(("config/yes_steve_model/cache/", "config/yes_steve_model/builtin/")): continue
                if source == "r70":
                    if rel.startswith(("PCL/", "IC2-NewEra-info/")) and not rel.endswith(("LICENSE-first-party-MIT.txt", "THIRD-PARTY-NOTICES.txt", "SPAWN-r63.zh-CN.txt")): continue
                    if rel in ("options.txt", "config/oculus.properties"): continue
                target = safe(OVERLAY, rel)
                if source == "r70" and rel == "config/ProjectE/custom_emc.json" and target.exists():
                    old, addon = read(target), json.loads(z.read(entry))
                    applied = {i["item"] for i in addon["entries"]}
                    old["entries"] = [i for i in old["entries"] if i["item"] not in applied] + addon["entries"]
                    write(target, old)
                else:
                    with z.open(entry) as part, target.open("wb") as dst: shutil.copyfileobj(part, dst)
    for rel in locked_exclusions:
        target = safe(OVERLAY, rel)
        if target.exists(): target.unlink()
    canonical_emc = R71_TASK_SNAPSHOT / "config/ProjectE/custom_emc.json"
    if canonical_emc.is_file():
        emc_dest = OVERLAY / "config/ProjectE/custom_emc.json"
        emc_dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(canonical_emc, emc_dest)
    quests = OVERLAY / "config/ftbquests/quests"
    # The base export contained generated YSM 2.6.5 models and multiplayer
    # caches. Let the selected 2.6.3 JAR provide its own built-ins on first run.
    ysm_config = OVERLAY / "config/yes_steve_model"
    for folder in ("cache", "builtin"):
        local_copy = ysm_config / folder
        if local_copy.exists():
            if not local_copy.resolve().is_relative_to(OVERLAY.resolve()): raise ValueError(local_copy)
            shutil.rmtree(local_copy)
    # The user's PCL export drops LDLib project files while retaining scripts
    # and quests that depend on their MBD runtime registries.
    machines = W / "repair-overlay/ldlib"
    if not all((machines / "assets/mbd2/machine" / name).is_file() for name in ("greenhouse_builder.sm", "sell_bin.sm")):
        raise FileNotFoundError("Recover the matching upstream LDLib machine definitions before packaging")
    shutil.copytree(machines, OVERLAY / "ldlib", dirs_exist_ok=True)
    canonical_quests = R71_TASK_SNAPSHOT / "config/ftbquests/quests"
    canonical_index = R71_TASK_SNAPSHOT / "config/ic2newera/quest_knowledge.json"
    use_snapshot = canonical_quests.is_dir() and canonical_index.is_file()
    quest_source = canonical_quests if use_snapshot else W / "quests-work/merged"
    shutil.copytree(quest_source, quests, dirs_exist_ok=True, ignore=shutil.ignore_patterns("merge_report.json"))
    shutil.copytree(W / "compat-work/tacz-overlay-1200/overrides", OVERLAY, dirs_exist_ok=True)
    repair_installed_tree(OVERLAY / "tacz")
    for rel in ("PCL/Setup.ini", "PCL/config.json", "config/oculus.properties", "options.txt"):
        path = OVERLAY / rel
        original = path.read_bytes()
        if rel == "PCL/config.json":
            config = json.loads(original.decode("utf-8-sig"))
            config["MinecraftInstallManifest"]["Components"]["ModLoaderVersions"]["Forge"] = "47.4.28"
            write(path, config)
        elif rel == "PCL/Setup.ini":
            # Build a minimal launcher-neutral setup rather than shipping a stale launcher cache.
            path.write_text("VersionArgumentIndie:1\nVersionArgumentIndieV2:True\nVersionArgumentJavaV2:2\nLogoCustom:True\nLogo:PCL\\Logo.png\nIsStar:False\n", encoding="utf-8")
        elif rel == "config/oculus.properties":
            path.write_bytes(original.replace(b"enableShaders=true", b"enableShaders=false"))
        else:
            lines = original.decode("utf-8-sig").replace("\r\r\n", "\n").splitlines()
            for i, line in enumerate(lines):
                if line.startswith("resourcePacks:"):
                    packs = json.loads(line.split(":", 1)[1])
                    grove = "file/Soartex Grove 1.0.4.zip"
                    packs.insert(packs.index("file/Squareful-Create_Delight_Remake.zip"), grove)
                    packs.extend(["file/CMMRP_Release_1.0_[1.20-1.20.1].zip", "file/CMM_FIX_EMF_v.1_[1.20-1.20.1].zip"])
                    lines[i] = "resourcePacks:" + json.dumps(list(dict.fromkeys(packs)), separators=(",", ":"))
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    modlist = OVERLAY / "config/crash_assistant/modlist.json"
    original = read(modlist)
    current = {r["name"]: original.get(r["name"], {"modId": r["primary"], "name": r["display"], "version": r["version"]})
               for r in picks}
    for patch in read(HOTFIX_LOCK)["patches"]:
        current[patch["filename"]] = {"modId": patch["modId"],
                                      "name": next(r["display"] for r in picks if r["name"] == patch["filename"]),
                                      "version": next(r["version"] for r in picks if r["name"] == patch["filename"])}
    write(modlist, current)
    write(OVERLAY / "config/modpack_defaults/config/crash_assistant/modlist.json", current)
    assets = PROFILE.parents[1] / "assets"
    index = read(PROFILE / (PROFILE.name + ".json"))["assetIndex"]["id"]
    zh = read(assets / "indexes" / (index + ".json"))["objects"]["minecraft/lang/zh_cn.json"]["hash"]
    zh_file = assets / "objects" / zh[:2] / zh
    final_index = OVERLAY / "config/ic2newera/quest_knowledge.json"
    final_index.parent.mkdir(parents=True, exist_ok=True)
    generated_index = W / "compat-work/generated-quest-index-preview.json" if use_snapshot else final_index
    data = build_catalogue(quests, [Path(r["path"]) for r in picks], PROFILE / (PROFILE.name + ".jar"),
                           [zh_file, *list((OVERLAY / "kubejs/assets").glob("*/lang/zh_cn.json"))],
                           generated_index)
    if data != {"chapters": 61, "quests": 2676, "own": 167}: raise ValueError(data)
    if use_snapshot:
        shutil.copy2(canonical_index, final_index)
    print(data)
    if previous_overlay.exists():
        try:
            shutil.rmtree(previous_overlay)
        except OSError:
            pass


def export(picks):
    index = read(W / "reference-index.json")
    with ZipFile(CLIENT) as old:
        r70 = json.loads(old.read("modrinth.index.json"))
    visuals = [r for i in (index, r70) for r in i["files"] if r["path"].startswith(("resourcepacks/", "shaderpacks/"))]
    rows = []
    for row in visuals:
        row = copy.deepcopy(row)
        row["env"] = {"client": "required", "server": "unsupported"}
        rows.append(row)
    for p in picks:
        if p["index"]:
            row = copy.deepcopy(p["index"])
            row["path"] = "mods/" + p["name"]
            row["env"] = {"client": "required", "server": "required" if p["server"] else "unsupported"}
            rows.append(row)
    if len({r["path"].casefold() for r in rows}) != len(rows):
        raise ValueError("Duplicate download path")
    index.update(versionId="r71", name="机械动力：齿轮盛宴 × IC2：新纪元", summary="生存、工业、生态与匠魂联合任务书", files=rows)
    index["dependencies"]["forge"] = "47.4.28"
    OUT.mkdir(parents=True, exist_ok=True)
    dest = OUT / (NAME + "-Windows.mrpack")
    initial_dest_sha = digest(dest) if dest.exists() else None
    temp = W / f"joint-client-{os.getpid()}.tmp"
    try:
        with ZipFile(temp, "w", ZIP_DEFLATED, compresslevel=4) as z:
            z.writestr("modrinth.index.json", json.dumps(index, ensure_ascii=False, indent=2))
            for f in sorted(OVERLAY.rglob("*")):
                if f.is_file():
                    z.write(f, "overrides/" + f.relative_to(OVERLAY).as_posix(),
                            compress_type=ZIP_STORED if f.suffix.lower() in (".zip", ".jar", ".png", ".ogg") else ZIP_DEFLATED)
            for p in picks:
                if not p["index"]: z.write(p["path"], "overrides/mods/" + p["name"], compress_type=ZIP_STORED)
        with ZipFile(temp) as z:
            if z.testzip() or len(z.namelist()) != len(set(z.namelist())): raise ValueError("Corrupt client pack")
        current_dest_sha = digest(dest) if dest.exists() else None
        if current_dest_sha != initial_dest_sha:
            raise FileExistsError(f"Client pack changed during export; refusing to replace it: {dest}")
        os.replace(temp, dest)
    finally:
        temp.unlink(missing_ok=True)
    print(dest)


@serialize_r71_release
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["select", "overlay", "export"])
    parser.add_argument("--built", type=Path)
    args = parser.parse_args()
    if args.phase == "select": select(args.built)
    elif args.phase == "overlay": overlay(read(W / "selection.json"))
    else: export(read(W / "selection.json"))


if __name__ == "__main__":
    main()
