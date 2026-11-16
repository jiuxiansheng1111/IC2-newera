"""Build the r73 navigation and robot listening update from the verified r72 distributions."""
import argparse
import copy
import hashlib
import json
import os
import shutil
import tempfile
from contextlib import ExitStack
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "build-isolated/migration-1201/newera"
WORK = ROOT / "build-isolated/player-packaging/20261116-r73-navigation"
BASE = ROOT / "dist/IC2-New-Era-r72"
DIST = ROOT / "dist/IC2-New-Era-r73"
OWN = "ic2newera-0.2.0-migration1201-all.jar"
BUILT = PROJECT / "build-isolated/libs/ic2newera-0.2.0-migration1201.jar"
NATIVE = WORK / "native" / OWN
NATIVE_SHA = "F8C5C659D6A14FBFE30D6A29285D5C1234D3F241092532AACA69D0A059F18D95"
CLIENT_SHA = "46534BFCC4A8C0D863F46988DD182BEB6EADF2BC75165C0376DCB328E0B8EA49"
SERVER_SHA = "E2C075DDC5928E8A135418F5AC43440183225D3676777C83227831A0E0225CAF"
CLIENT = "IC2-New-Era-Windows-1.20.1-r72-Grove-CMM.mrpack"
SERVER = "IC2-New-Era-Server-1.20.1-r72-Voice.zip"
CLIENT_INFO = "overrides/IC2-NewEra-info/"
SERVER_ROOT = SERVER.removesuffix(".zip") + "/"
BUILD_ID = "20261116-r73-navigation-music"
PACKAGES = ("com/ic2newera/field/places/", "com/ic2newera/compat/vs/", "com/ic2newera/compat/xaero/")
FAMILIES = ("com/ic2newera/IC2NewEra", "com/ic2newera/field/FieldRoutes",
            "com/ic2newera/client/robot/FieldRouteScreen", "com/ic2newera/client/robot/FieldPlacesScreen",
            "com/ic2newera/media/MediaPackets", "com/ic2newera/media/MediaServer",
            "com/ic2newera/media/music/NetEaseMusicProvider", "com/ic2newera/media/SongConversation",
            "com/ic2newera/media/SongListeningMemory", "com/ic2newera/media/SongModel",
            "com/ic2newera/client/media/PhoneMusicScreen", "com/ic2newera/client/media/SongChatScreen",
            "com/ic2newera/client/media/MusicController")
RESOURCES = {"META-INF/MANIFEST.MF", "data/ic2newera/ai/ui_operations_zh.md",
             "data/ic2newera/ai/migration_runtime_zh.md", "ic2newera.xaero-route.mixins.json",
             "ic2newera.vs-respawn-compat.mixins.json"}
SUMMARY = "网易云歌曲短评、机器人听感和私人听歌回忆；手机地形导航、Xaero 路线、个人地点传送与船边复活修正。"
SUMMARY_EN = "Robot song reflections, public NetEase comments and private listening memories, terrain navigation, Xaero route overlays, waypoint teleportation and ship respawn fixes."


def digest(data):
    return hashlib.sha256(data).hexdigest().upper()


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def require_file(path, expected):
    if file_hash(path) != expected:
        raise ValueError(f"Unexpected build or release input: {path}")


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()


def write_archive(source, target, replacements, rename=lambda name: name, removed=()):
    seen = set()
    target.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(source) as src, ZipFile(target, "w", ZIP_DEFLATED, allowZip64=True) as dst:
        if len(src.infolist()) != len(set(src.namelist())):
            raise ValueError(f"Duplicate archive entries in {source}")
        for original in src.infolist():
            if original.filename in removed:
                continue
            name = rename(original.filename)
            if name in seen:
                raise ValueError(f"Duplicate output member {name}")
            seen.add(name)
            info = copy.copy(original)
            info.filename = info.orig_filename = name
            if original.filename in replacements:
                dst.writestr(info, replacements[original.filename])
            else:
                with src.open(original) as reader, dst.open(info, "w") as writer:
                    shutil.copyfileobj(reader, writer, 1024 * 1024)
        for name in sorted(replacements.keys() - set(src.namelist())):
            if rename(name) in seen:
                raise ValueError(f"Duplicate new archive entry {name}")
            info = ZipInfo(rename(name), (2026, 11, 16, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            dst.writestr(info, replacements[name])


def native():
    require_file(BASE / OWN, NATIVE_SHA)
    NATIVE.parent.mkdir(parents=True, exist_ok=True)
    def selected(name):
        if name in RESOURCES:
            return True
        stem = name.removesuffix(".class").split("$", 1)[0]
        return name.endswith(".class") and (name.startswith(PACKAGES) or stem in FAMILIES)
    with ZipFile(BASE / OWN) as previous, ZipFile(BUILT) as built:
        names = {n for n in built.namelist() if selected(n)}
        required = RESOURCES | {family + ".class" for family in FAMILIES}
        missing = required - names
        if missing or any(not any(n.startswith(package) for n in names) for package in PACKAGES):
            raise ValueError(f"Incomplete reobfuscated navigation build: {missing}")
        manifest = built.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        for config in RESOURCES:
            if config.endswith(".mixins.json") and config not in manifest:
                raise ValueError(f"Mixin configuration not loaded: {config}")
        previous_names = set(previous.namelist())
        removed = {n for n in previous_names - names if selected(n)}
        changes = {n: built.read(n) for n in names if n not in previous_names or built.read(n) != previous.read(n)}
        if len(changes) < 12 or "com/ic2newera/IC2NewEra.class" not in changes:
            raise ValueError("Build missing the navigation channel or gameplay classes")
    stage = NATIVE.with_suffix(".assembling")
    write_archive(BASE / OWN, stage, changes, removed=removed)
    with ZipFile(stage) as assembled:
        if assembled.testzip() is not None:
            raise ValueError("Native JAR CRC failed")
        for config in sorted(RESOURCES):
            if not config.endswith(".mixins.json"):
                continue
            spec = json.loads(assembled.read(config))
            for name in spec["client"] + spec.get("mixins", []):
                assembled.getinfo((spec["package"] + "." + name).replace(".", "/") + ".class")
            if "plugin" in spec:
                assembled.getinfo(spec["plugin"].replace(".", "/") + ".class")
    os.replace(stage, NATIVE)
    (NATIVE.parent / "native-manifest.json").write_bytes(encoded({
        "baseSha256": NATIVE_SHA, "sha256": file_hash(NATIVE), "changed": sorted(changes),
        "removed": sorted(removed), "added": sorted(changes.keys() - previous_names)}))
    print(NATIVE, file_hash(NATIVE), len(changes), "updated members", flush=True)


def revision(name):
    return name.replace("r72", "r73")


def package():
    require_file(BASE / CLIENT, CLIENT_SHA)
    require_file(BASE / SERVER, SERVER_SHA)
    receipt = json.loads((NATIVE.parent / "native-manifest.json").read_bytes())
    require_file(NATIVE, receipt["sha256"])
    payload = NATIVE.read_bytes()
    own_hash = digest(payload)
    common = dict(revision="r73", newEraSha256=own_hash, newEraSizeBytes=len(payload))
    client_changes = {"overrides/mods/" + OWN: payload}
    server_changes = {"mods/" + OWN: payload}
    with ZipFile(BASE / CLIENT) as z:
        index = json.loads(z.read("modrinth.index.json"))
        index.update(name=revision(index["name"]), versionId="0.2.0-windows." + BUILD_ID, summary=SUMMARY)
        client_changes["modrinth.index.json"] = encoded(index)
        audit_name = CLIENT_INFO + "r72-artifact-audit.json"
        audit = json.loads(z.read(audit_name))
        jei = audit["jei"]
        audit.update(common, featureSummary=SUMMARY)
        client_changes[audit_name] = encoded(audit)
        for name, feature in (("README-zh_CN.txt", SUMMARY), ("README-en_US.txt", SUMMARY_EN),
                              ("r72-release.txt", SUMMARY), ("PLAYTEST-STATUS.txt", SUMMARY_EN)):
            path = CLIENT_INFO + name
            client_changes[path] = (revision(z.read(path).decode("utf-8-sig")).rstrip() + "\n" + feature + "\n").encode()
    with ZipFile(BASE / SERVER) as z:
        for name, feature in (("README.zh-CN.md", SUMMARY), ("README.md", SUMMARY_EN), ("PLAYTEST-STATUS.txt", SUMMARY_EN)):
            server_changes[name] = (revision(z.read(SERVER_ROOT + name).decode("utf-8-sig")).rstrip() + "\n\n" + feature + "\n").encode()
        manifest = json.loads(z.read(SERVER_ROOT + "server-manifest.json"))
        manifest.update(package=revision(manifest["package"]), releaseRevision="r73", featureSummary=SUMMARY_EN)
        manifest["protocols"]["fieldPlaces"] = 1
        manifest["protocols"]["media"] = 8
        jei_row = next(r for r in manifest["mods"] if r["modId"] == "jei")
        if digest(z.read(SERVER_ROOT + "mods/" + jei_row["file"])) != jei["sha256"].upper():
            raise ValueError("JEI in the server archive differs from the audited client build")
        jei_row["version"] = jei["version"]
        next(m for m in jei_row["containedMods"] if m["modId"] == "jei")["metadataVersion"] = jei["version"]
        own = next(r for r in manifest["mods"] if r["modId"] == "ic2newera")
        own.update(version=own["containedMods"][0]["metadataVersion"], source="First-party release r73",
                   sha256=own_hash, sizeBytes=len(payload))
        for row in manifest["files"]:
            body = server_changes.get(row["path"])
            if body is not None:
                row.update(sha256=digest(body), sizeBytes=len(body))
        server_changes["server-manifest.json"] = encoded(manifest)
        audit = json.loads(z.read(SERVER_ROOT + "artifact-audit.json"))
        audit.update(common, featureSummary=SUMMARY, protocols=manifest["protocols"],
                     serverManifestSha256=digest(server_changes["server-manifest.json"]))
        server_changes["artifact-audit.json"] = encoded(audit)
    DIST.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=DIST) as temporary:
        stage = Path(temporary)
        client, server = stage / revision(CLIENT), stage / revision(SERVER)
        def client_name(n):
            return revision(n) if n.startswith(CLIENT_INFO + "r72-") else n
        def server_name(n):
            if not n.startswith(SERVER_ROOT):
                raise ValueError(f"Unexpected server root: {n}")
            return revision(SERVER_ROOT) + n[len(SERVER_ROOT):]
        write_archive(BASE / CLIENT, client, client_changes, client_name)
        write_archive(BASE / SERVER, server, {SERVER_ROOT + k: v for k, v in server_changes.items()}, server_name)
        shutil.copy2(NATIVE, stage / OWN)
        release = json.loads((BASE / "release-manifest.json").read_bytes())
        release.update(revision="r73", buildId=BUILD_ID, newEraJarSha256=own_hash, featureSummary=SUMMARY,
                       sourceBaseline=(BASE / CLIENT).relative_to(ROOT).as_posix(), serverBaseline=(BASE / SERVER).relative_to(ROOT).as_posix())
        with ExitStack() as stack:
            cli, srv = [stack.enter_context(ZipFile(p)) for p in (client, server)]
            if cli.testzip() or srv.testzip():
                raise ValueError("Pack CRC failed")
            downloaded = {r["path"]: r["hashes"]["sha1"].lower() for r in index["files"] if r["path"].startswith("mods/")}
            client_mods = dict(downloaded)
            for n in cli.namelist():
                if n.startswith("overrides/mods/") and n.endswith(".jar"):
                    client_mods[n.removeprefix("overrides/")] = hashlib.sha1(cli.read(n)).hexdigest()
            prefix = revision(SERVER_ROOT)
            server_mods = {n[len(prefix):] for n in srv.namelist() if n.startswith(prefix + "mods/") and n.endswith(".jar")}
            if len(client_mods) != 46 or len(server_mods) != 35 or any(
                    hashlib.sha1(srv.read(prefix + n)).hexdigest() != client_mods.get(n) for n in server_mods):
                raise ValueError("Client/server mod mismatch")
            for row in manifest["files"]:
                if digest(srv.read(prefix + row["path"])) != row["sha256"].upper():
                    raise ValueError(f"Server file digest mismatch: {row['path']}")
            for n in cli.namelist():
                if n.startswith(("overrides/config/ftbquests/quests/", "overrides/config/ic2newera/quest_knowledge.json")):
                    if cli.read(n) != srv.read(prefix + n[len("overrides/"):]):
                        raise ValueError(f"Quest or AI data differs: {n}")
            if any(n.startswith((prefix + "resourcepacks/", prefix + "shaderpacks/")) for n in srv.namelist()):
                raise ValueError("Client graphics present in server pack")
            release["staticChecks"].update(archiveCrc="pass", clientEntries=len(cli.namelist()), sharedJarCount=len(server_mods))
        for key, path in (("client", client), ("server", server), ("standaloneFirstPartyJar", stage / OWN)):
            release[key].update(path=path.name, sha256=file_hash(path), sizeBytes=path.stat().st_size)
        (stage / "release-manifest.json").write_bytes(encoded(release))
        (stage / "SHA256SUMS.txt").write_text("".join(release[k]["sha256"] + "  " + release[k]["path"] + "\n"
            for k in ("client", "server", "standaloneFirstPartyJar")), encoding="ascii")
        for path in stage.iterdir():
            os.replace(path, DIST / path.name)
    print(DIST, own_hash, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("step", choices=["native", "package"])
    step = parser.parse_args().step
    native() if step == "native" else package()
