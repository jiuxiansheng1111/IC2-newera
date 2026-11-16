"""Build the r74 shared assistant-model update from the frozen, verified r73 distributions."""
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
WORK = ROOT / "build-isolated/player-packaging/20261116-r74-model-routing"
BASE = ROOT / "dist/IC2-New-Era-r73"
DIST = ROOT / "dist/IC2-New-Era-r74"
OWN = "ic2newera-0.2.0-migration1201-all.jar"
BUILT = PROJECT / "build-isolated/libs/ic2newera-0.2.0-migration1201.jar"
NATIVE = WORK / "native" / OWN
NATIVE_SHA = "E79B86983FC2CDF5835419FC6694C9517C25B6F727F657F42CCDA8FB5B5FF4F9"
CLIENT_SHA = "52E652FC3A75E7C1C3F711971B61713A3EEDC6E65FC73ABA51E4123201A71696"
SERVER_SHA = "405AD9A3716EC1C9BA450A99DAFEB72AD2629F97E9CA89A33961E66EB13A4C34"
CLIENT = "IC2-New-Era-Windows-1.20.1-r73-Grove-CMM.mrpack"
SERVER = "IC2-New-Era-Server-1.20.1-r73-Voice.zip"
CLIENT_INFO = "overrides/IC2-NewEra-info/"
SERVER_ROOT = SERVER.removesuffix(".zip") + "/"
BUILD_ID = "20261116-r74-shared-assistant-model"
PACKAGES = ()
FAMILIES = (
    "com/ic2newera/IC2NewEra",
    "com/ic2newera/client/ui/AssistantScreen", "com/ic2newera/client/ui/LLMChatScreen",
    "com/ic2newera/client/ui/LLMSettingsScreen",
    "com/ic2newera/client/ui/LLMModelPickerScreen",
    "com/ic2newera/client/robot/blueprint/DefaultBlueprintClient", "com/ic2newera/media/NativeMediaConfig",
    "com/ic2newera/integration/llm/LLMConfig", "com/ic2newera/integration/llm/LLMTransport",
    "com/ic2newera/integration/llm/ResponsesCodec", "com/ic2newera/integration/llm/SubscriptionInference",
    "com/ic2newera/integration/llm/ModelCatalog", "com/ic2newera/integration/llm/NativeModelTransport",
    "com/ic2newera/media/MediaServer", "com/ic2newera/media/MediaPackets",
    "com/ic2newera/client/media/MediaClient",
)
RETIRED_FAMILIES = ("com/ic2newera/media/SongModel",)
RESOURCES = {"META-INF/MANIFEST.MF", "assets/ic2newera/lang/zh_cn.json", "assets/ic2newera/lang/en_us.json",
             "data/ic2newera/ai/ui_operations_zh.md", "data/ic2newera/ai/migration_runtime_zh.md",
             "data/ic2newera/ai/gameplay_energy_zh.md", "data/ic2newera/ai/qq_subscription.py"}
SUMMARY = "通用模型设置支持 OpenAI、Claude、DeepSeek、Ollama。助手、机器人、音乐和蓝图可以选择默认或独立模型。媒体协议 10。"
SUMMARY_EN = "Select models for assistants, robots, music and blueprints across OpenAI, Claude, DeepSeek, Ollama and custom endpoints. Media protocol 10."


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
        if any(n.endswith(".class") and n.removesuffix(".class").split("$", 1)[0] in RETIRED_FAMILIES
               for n in built.namelist()):
            raise ValueError("The retired SongModel implementation is still present in the build")
        required = RESOURCES | {family + ".class" for family in FAMILIES}
        missing = required - names
        if missing or any(not any(n.startswith(package) for n in names) for package in PACKAGES):
            raise ValueError(f"Incomplete reobfuscated r74 model-routing build: {missing}")
        manifest = built.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        for config in RESOURCES:
            if config.endswith(".mixins.json") and config not in manifest:
                raise ValueError(f"Mixin configuration not loaded: {config}")
        previous_names = set(previous.namelist())
        removed = {n for n in previous_names - names if selected(n) or
                   (n.endswith(".class") and n.removesuffix(".class").split("$", 1)[0] in RETIRED_FAMILIES)}
        changes = {n: built.read(n) for n in names if n not in previous_names or built.read(n) != previous.read(n)}
        if len(changes) < 12 or "com/ic2newera/IC2NewEra.class" not in changes:
            raise ValueError("Build missing the r74 model-routing classes or channel changes")
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
        if any("SongModel" in n for n in assembled.namelist() if n.endswith(".class")):
            raise ValueError("Retired SongModel class remains in the r74 native JAR")
        if any(n.lower().endswith("ic2newera-client.toml") for n in assembled.namelist()):
            raise ValueError("Player-local configuration must not be packaged")
    os.replace(stage, NATIVE)
    (NATIVE.parent / "native-manifest.json").write_bytes(encoded({
        "baseSha256": NATIVE_SHA, "sha256": file_hash(NATIVE), "changed": sorted(changes),
        "removed": sorted(removed), "added": sorted(changes.keys() - previous_names)}))
    print(NATIVE, file_hash(NATIVE), len(changes), "updated members", flush=True)


def verify_archive_delta(source, output, replacements, rename):
    source_names = set(source.namelist())
    expected_names = {rename(name) for name in source_names}
    expected_names.update(rename(name) for name in replacements.keys() - source_names)
    if set(output.namelist()) != expected_names:
        raise ValueError("An archive entry was added or removed outside the declared rename map")
    for original in source.infolist():
        expected = replacements.get(original.filename)
        if expected is None:
            expected = source.read(original)
        if output.read(rename(original.filename)) != expected:
            raise ValueError(f"Unexpected archive change outside the declared update: {original.filename}")
    for name in replacements.keys() - source_names:
        if output.read(rename(name)) != replacements[name]:
            raise ValueError(f"Unexpected new archive member: {name}")


def revision(name):
    return name.replace("r73", "r74")


def package():
    require_file(BASE / CLIENT, CLIENT_SHA)
    require_file(BASE / SERVER, SERVER_SHA)
    receipt = json.loads((NATIVE.parent / "native-manifest.json").read_bytes())
    require_file(NATIVE, receipt["sha256"])
    payload = NATIVE.read_bytes()
    own_hash = digest(payload)
    common = dict(revision="r74", newEraSha256=own_hash, newEraSizeBytes=len(payload))
    client_changes = {"overrides/mods/" + OWN: payload}
    server_changes = {"mods/" + OWN: payload}
    with ZipFile(BASE / CLIENT) as z:
        index = json.loads(z.read("modrinth.index.json"))
        index.update(name=revision(index["name"]), versionId="0.2.0-windows." + BUILD_ID, summary=SUMMARY)
        client_changes["modrinth.index.json"] = encoded(index)
        audit_name = CLIENT_INFO + "r73-artifact-audit.json"
        audit = json.loads(z.read(audit_name))
        jei = audit["jei"]
        audit.update(common, featureSummary=SUMMARY)
        client_changes[audit_name] = encoded(audit)
        for name, feature in (("README-zh_CN.txt", SUMMARY), ("README-en_US.txt", SUMMARY_EN),
                              ("r73-release.txt", SUMMARY), ("PLAYTEST-STATUS.txt", SUMMARY_EN)):
            path = CLIENT_INFO + name
            client_changes[path] = (revision(z.read(path).decode("utf-8-sig")).rstrip() + "\n" + feature + "\n").encode()
    with ZipFile(BASE / SERVER) as z:
        for name, feature in (("README.zh-CN.md", SUMMARY), ("README.md", SUMMARY_EN), ("PLAYTEST-STATUS.txt", SUMMARY_EN)):
            server_changes[name] = (revision(z.read(SERVER_ROOT + name).decode("utf-8-sig")).rstrip() + "\n\n" + feature + "\n").encode()
        manifest = json.loads(z.read(SERVER_ROOT + "server-manifest.json"))
        manifest.update(package=revision(manifest["package"]), releaseRevision="r74", featureSummary=SUMMARY_EN)
        manifest["protocols"]["fieldPlaces"] = 1
        manifest["protocols"]["media"] = 10
        jei_row = next(r for r in manifest["mods"] if r["modId"] == "jei")
        if digest(z.read(SERVER_ROOT + "mods/" + jei_row["file"])) != jei["sha256"].upper():
            raise ValueError("JEI in the server archive differs from the audited client build")
        jei_row["version"] = jei["version"]
        next(m for m in jei_row["containedMods"] if m["modId"] == "jei")["metadataVersion"] = jei["version"]
        own = next(r for r in manifest["mods"] if r["modId"] == "ic2newera")
        own.update(version=own["containedMods"][0]["metadataVersion"], source="First-party release r74",
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
            return revision(n) if n.startswith(CLIENT_INFO + "r73-") else n
        def server_name(n):
            if not n.startswith(SERVER_ROOT):
                raise ValueError(f"Unexpected server root: {n}")
            return revision(SERVER_ROOT) + n[len(SERVER_ROOT):]
        write_archive(BASE / CLIENT, client, client_changes, client_name)
        write_archive(BASE / SERVER, server, {SERVER_ROOT + k: v for k, v in server_changes.items()}, server_name)
        shutil.copy2(NATIVE, stage / OWN)
        release = json.loads((BASE / "release-manifest.json").read_bytes())
        release.update(revision="r74", buildId=BUILD_ID, newEraJarSha256=own_hash, featureSummary=SUMMARY,
                       sourceBaseline=(BASE / CLIENT).relative_to(ROOT).as_posix(), serverBaseline=(BASE / SERVER).relative_to(ROOT).as_posix())
        with ExitStack() as stack:
            cli, srv, base_cli, base_srv = [stack.enter_context(ZipFile(p)) for p in
                                             (client, server, BASE / CLIENT, BASE / SERVER)]
            if cli.testzip() or srv.testzip():
                raise ValueError("Pack CRC failed")
            verify_archive_delta(base_cli, cli, client_changes, client_name)
            verify_archive_delta(base_srv, srv, {SERVER_ROOT + k: v for k, v in server_changes.items()}, server_name)
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
