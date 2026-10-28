"""Create a PCL2-importable Modrinth pack for the isolated 1.20.1 profile.

Only the first-party mod and portable Temurin Java are carried in overrides.
Third-party mod JARs are fetched by the launcher from their locked HTTPS URLs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import time
import tomllib
import zipfile
from datetime import datetime, timezone, timedelta
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build-isolated/player-packaging/20261028-server-ae-r48"
LOCK = ROOT / "build-isolated/migration-1201/dependencies/runtime-lock-1201.json"
DEPS = LOCK.parent
ENHANCEMENTS_LOCK = ROOT / "scripts/player-enhancements-lock-1201.json"
CHECKPOINT = ROOT / "build-isolated/migration-1201/checkpoints/build48-server-ae"
OWN_JAR = "ic2newera-0.2.0-migration1201-all.jar"
OWN_SHA256 = "5C98DDAFD0511AB0CF5D679314916603FE677D34681F4F89F7380B58200218FE"
JAVA_DIR = ROOT / "build-isolated/player-packaging/20261024-r41-java17/java-source"
JAVA_ZIP = "OpenJDK17U-jdk_x64_windows_hotspot_17.0.20_8.zip"
JAVA_SOURCE = "OpenJDK17U-jdk-sources_17.0.20_8.tar.gz"
JAVA_ZIP_SHA256 = "418497BE5CF585BDD2203D6486A565D66D3F5E992D5630D45104CB873FAB8122"
JAVA_SOURCE_SHA256 = "A9FADD969738666D59A241BC6FB01D0963154489D934EE387630DD4DCA5E8F54"
JAVA_ROOT = "jdk-17.0.20+8"
PACK_NAME = "IC2-New-Era-1.20.1-Forge-47.4.28-r48.mrpack"
PACK_VERSION = "0.2.0-playtest.20261028-r48-server-ae"


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def validate_pack_path(value: str) -> str:
    if (not value or "\\" in value or ":" in value or value.startswith("/")
            or "\x00" in value or any(part in ("", ".", "..") for part in value.split("/"))):
        raise ValueError("Unsafe archive path")
    return PurePosixPath(value).as_posix()


def validate_https(value: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Dependency source must be a public HTTPS URL")


def read_locked_mods() -> list[dict]:
    lock = json.loads(LOCK.read_text(encoding="utf-8-sig"))
    if lock.get("target", {}).get("minecraft") != "1.20.1" or lock.get("target", {}).get("forge") != "47.4.28":
        raise ValueError("Runtime lock target is not Minecraft 1.20.1 / Forge 47.4.28")
    rows = lock.get("runtimeMods")
    if not isinstance(rows, list) or len(rows) != 15:
        raise ValueError("Expected exactly 15 locked third-party runtime mods")
    enhancements = json.loads(ENHANCEMENTS_LOCK.read_text(encoding="utf-8-sig"))
    if enhancements.get("target") != {"minecraft": "1.20.1", "forge": "47.4.28", "java": 17}:
        raise ValueError("Enhancement lock target differs from the core lock")
    rows = rows + enhancements["mods"]
    result = []
    for row in rows:
        mod_id = row["modId"]
        source = row.get("source")
        if mod_id == "ic2":
            source = "https://github.com/craftorio/ic2/releases/download/2.10.33-ex120/ic2-forge-2.10.33-ex120.jar"
        validate_https(source or "")
        local = DEPS / row["file"]
        if not local.is_file() or local.stat().st_size != row["sizeBytes"]:
            raise ValueError("Locked dependency file missing or wrong size: " + mod_id)
        if sha_file(local, "sha256") != row["sha256"].upper():
            raise ValueError("Locked dependency SHA-256 mismatch: " + mod_id)
        result.append({"modId": mod_id, "version": row["version"], "file": row["file"],
                       "url": source, "sizeBytes": row["sizeBytes"], "localSha256": row["sha256"].upper(),
                       "sha1": sha_file(local, "sha1").lower(),
                       "sha512": sha_file(local, "sha512").lower()})
    if len({row["modId"] for row in result}) != len(rows) or len({row["file"].casefold() for row in result}) != len(rows):
        raise ValueError("The dependency lock contains duplicate mod ids or filenames")
    return result


def validate_exact_dependency_pins(own: Path, mods: list[dict]) -> list[dict]:
    """Reject stale exact-version metadata before distributing a pack."""
    versions = {row["modId"]: row["version"] for row in mods}
    versions.update({"minecraft": "1.20.1", "forge": "47.4.28"})
    with zipfile.ZipFile(own) as archive:
        manifest = tomllib.loads(archive.read("META-INF/mods.toml").decode("utf-8-sig"))
    checked = []
    for row in manifest.get("dependencies", {}).get("ic2newera", []):
        mod_id, required = row["modId"], row.get("versionRange", "")
        actual = versions.get(mod_id)
        if actual is None:
            if row.get("mandatory", False):
                raise ValueError("Mandatory first-party dependency missing from pack: " + mod_id)
            continue
        if required.startswith("[") and required.endswith("]") and "," not in required:
            if actual != required[1:-1]:
                raise ValueError(f"First-party dependency mismatch: {mod_id} requires {required}, pack has {actual}")
            checked.append({"modId": mod_id, "required": required, "actual": actual})
    return checked


def inspect_java_archive() -> tuple[str, list[dict], dict[str, zipfile.ZipInfo]]:
    receipt = json.loads((JAVA_DIR / "adoptium-download-receipt.json").read_text(encoding="utf-8"))
    declared = {item["name"]: item for item in receipt.get("files", [])}
    for name, expected in ((JAVA_ZIP, JAVA_ZIP_SHA256), (JAVA_SOURCE, JAVA_SOURCE_SHA256)):
        path = JAVA_DIR / name
        if not path.is_file() or path.stat().st_size != declared[name]["sizeBytes"]:
            raise ValueError("Official Temurin artifact is missing or wrong size: " + name)
        if sha_file(path, "sha256") != expected or declared[name]["sha256"].upper() != expected:
            raise ValueError("Official Temurin artifact SHA-256 mismatch: " + name)
        if not declared[name].get("vendorChecksumMatched"):
            raise ValueError("Temurin vendor checksum confirmation is missing: " + name)
    if receipt.get("vendor") != "Eclipse Temurin" or receipt.get("sourceReference") != "jdk-17.0.20+8_adopt":
        raise ValueError("Temurin receipt does not identify the expected source")

    archive = zipfile.ZipFile(JAVA_DIR / JAVA_ZIP, "r")
    infos: dict[str, zipfile.ZipInfo] = {}
    folded: set[str] = set()
    all_paths: list[str] = []
    roots: set[str] = set()
    for info in archive.infolist():
        raw = info.filename[:-1] if info.is_dir() else info.filename
        clean = validate_pack_path(raw)
        case_key = clean.casefold()
        if case_key in folded:
            raise ValueError("Case-insensitive duplicate in Temurin archive")
        folded.add(case_key)
        all_paths.append(clean)
        roots.add(clean.split("/", 1)[0])
        mode = (info.external_attr >> 16) & 0xFFFF
        kind = mode & 0o170000
        if kind == 0o120000 or kind not in (0, 0o040000 if info.is_dir() else 0o100000):
            raise ValueError("Symlink or special file in Temurin archive")
        if info.flag_bits & 0x1:
            raise ValueError("Encrypted Temurin entry is not supported")
        if info.is_dir():
            continue
        infos[clean] = info
    if roots != {JAVA_ROOT}:
        raise ValueError("Temurin ZIP root differs from the pinned vendor root")

    release_path = JAVA_ROOT + "/release"
    if release_path not in infos:
        raise ValueError("Temurin release metadata is missing")
    release_bytes = archive.read(infos[release_path])
    release = release_bytes.decode("utf-8-sig")
    props = dict(re.findall(r'^([A-Z_]+)="([^"]*)"\s*$', release, flags=re.MULTILINE))
    full_java_version = props.get("JAVA_RUNTIME_VERSION") or props.get("FULL_VERSION")
    if (props.get("JAVA_VERSION") != "17.0.20" or full_java_version != "17.0.20+8"
            or props.get("OS_ARCH") != "x86_64"):
        raise ValueError("Temurin archive release is not Java 17.0.20+8 Windows x64")
    required = {JAVA_ROOT + "/bin/java.exe", JAVA_ROOT + "/bin/javaw.exe", release_path,
                JAVA_ROOT + "/legal/java.base/LICENSE"}
    if not required.issubset(infos):
        raise ValueError("Temurin runtime executable or license is missing")
    # Every explicit ZIP directory must correspond to an actual file prefix.
    file_set = set(infos)
    for clean in all_paths:
        if clean in infos:
            continue
        if not any(path.startswith(clean + "/") for path in file_set):
            raise ValueError("Unmatched directory entry in Temurin archive")
    archive.close()
    return full_java_version, infos, {"_archive": JAVA_DIR / JAVA_ZIP}


def build_pack() -> dict:
    OUT.mkdir(parents=True, exist_ok=True)
    output = OUT / PACK_NAME
    partial = output.with_suffix(output.suffix + ".partial")
    audit_path = OUT / "pcl-import-audit.json"
    if output.exists() or partial.exists():
        raise FileExistsError("Review/remove the existing output or exact .partial file before rebuilding")

    mods = read_locked_mods()
    own = CHECKPOINT / OWN_JAR
    if not own.is_file() or sha_file(own, "sha256") != OWN_SHA256:
        raise ValueError("Reviewed first-party JAR does not match the reviewed SHA-256")
    exact_dependency_pins = validate_exact_dependency_pins(own, mods)
    java_version, java_infos, _ = inspect_java_archive()
    java_zip_path = JAVA_DIR / JAVA_ZIP
    source_path = JAVA_DIR / JAVA_SOURCE
    source_receipt = json.loads((JAVA_DIR / "adoptium-download-receipt.json").read_text(encoding="utf-8"))
    source_row = next(row for row in source_receipt["files"] if row["name"] == JAVA_SOURCE)

    blacklist_path = ROOT / "build-isolated/migration-1201/newera/scripts/projecte-emc-1201-blacklist.json"
    blacklist = json.loads(blacklist_path.read_text(encoding="utf-8-sig"))
    emc = {"__comment": "受控工业中间物与探索物品EMC为零。", "entries": [
        {"item": item, "emc": 0} for item in sorted(set(blacklist["itemIds"]))]}
    managed = {
        "overrides/mods/" + OWN_JAR: own,
        "overrides/config/ProjectE/custom_emc.json": json_bytes(emc),
        "overrides/config/ic2newera-robot-voice.json": json_bytes({"enabled": False}),
        "overrides/options.txt": "lang:zh_cn\nguiScale:0\n".encode("utf-8"),
        "overrides/defaultconfigs/ftbultimine/ftbultimine-server.snbt": b"{\n  max_blocks: 64\n}\n",
        "overrides/PCL/Setup.ini": ("VersionArgumentJavaV2:2\n"
                                     "VersionArgumentIndie:1\n"
                                     "VersionArgumentIndieV2:True\n"
                                     "IsStar:False\n").encode("utf-8"),
        "overrides/IC2-NewEra-runtime/source/" + JAVA_SOURCE: source_path,
        "overrides/IC2-NewEra-runtime/OPENJDK-NOTICES.txt": (
            "Unmodified Eclipse Temurin OpenJDK 17.0.20+8 Windows x64.\n"
            "Java files were unpacked from the original vendor ZIP. The matching full source archive is next to this notice.\n"
            "Official binary and source: https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20%2B8\n"
            "Source reference: https://github.com/adoptium/jdk17u/tree/jdk-17.0.20+8_adopt\n"
            "OpenJDK and its components retain their own licenses.\n").encode("utf-8")
    }
    for name in ("README.md", "example-house.json", "simple-pavilion.json"):
        managed["overrides/IC2-NewEra-blueprints/inbox/" + name] = ROOT / "build-isolated/migration-1201/newera/art/robot-blueprints" / name
    # Version-local Java discovery in PCL finds javaw.exe below the profile folder.
    for notice in ("README-zh_CN.txt", "README-en_US.txt", "THIRD-PARTY-NOTICES.txt", "PLAYTEST-STATUS.txt"):
        managed["overrides/IC2-NewEra-info/" + notice] = ROOT / "scripts/player-pack" / notice
    managed["overrides/IC2-NewEra-info/LICENSE-first-party-MIT.txt"] = ROOT / "LICENSE"
    for full_name in java_infos:
        relative = full_name[len(JAVA_ROOT) + 1:]
        pack_path = "overrides/IC2-NewEra-runtime/java17/" + validate_pack_path(relative)
        if pack_path in managed:
            raise ValueError("Java payload collides with a managed override")
        managed[pack_path] = ("__zip__", full_name)

    index_files = []
    for row in mods:
        index_files.append({
            "path": "mods/" + validate_pack_path(row["file"]),
            "hashes": {"sha1": row["sha1"], "sha512": row["sha512"].lower()},
            "env": {"client": "required", "server": "unsupported"},
            "downloads": [row["url"]],
            "fileSize": row["sizeBytes"]
        })
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": PACK_VERSION,
        "name": "IC2：新纪元 1.20.1（PCL导入包）",
        "summary": f"Forge 47.4.28；AE2仓储；连锁采集与中键整理；工业飞行与光影材质前置。首次导入联网下载{len(mods)}个锁定依赖。",
        "files": index_files,
        "dependencies": {"minecraft": "1.20.1", "forge": "47.4.28"}
    }
    managed["modrinth.index.json"] = json_bytes(index)

    java_file_rows = []
    java_total = 0
    for full_name, info in sorted(java_infos.items()):
        relative = full_name[len(JAVA_ROOT) + 1:]
        digest = hashlib.sha256()
        size = 0
        with zipfile.ZipFile(java_zip_path) as vendor, vendor.open(info) as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
                size += len(block)
        if size != info.file_size:
            raise ValueError("Temurin entry size differs from its ZIP metadata")
        java_total += size
        java_file_rows.append({"path": relative, "sizeBytes": size, "sha256": digest.hexdigest().upper()})

    if not any(name.endswith("/java.exe") for name in managed) or not any(name.endswith("/javaw.exe") for name in managed):
        raise ValueError("Portable Java executables are not present in the profile overrides")

    total = len(managed)
    rows = []
    started = time.monotonic()
    with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as out:
        for ordinal, name in enumerate(sorted(managed), 1):
            validate_pack_path(name)
            value = managed[name]
            if isinstance(value, tuple) and value[0] == "__zip__":
                entry_name = value[1]
                with zipfile.ZipFile(java_zip_path) as vendor, vendor.open(java_infos[entry_name]) as source:
                    with out.open(name, "w", force_zip64=True) as target:
                        while block := source.read(1024 * 1024):
                            target.write(block)
            else:
                source_path_or_bytes = value
                if isinstance(source_path_or_bytes, bytes):
                    out.writestr(name, source_path_or_bytes)
                else:
                    compression = zipfile.ZIP_STORED if name.endswith(".tar.gz") else zipfile.ZIP_DEFLATED
                    info_out = zipfile.ZipInfo(name)
                    info_out.compress_type = compression
                    with source_path_or_bytes.open("rb") as source, out.open(info_out, "w", force_zip64=True) as target:
                        while block := source.read(1024 * 1024):
                            target.write(block)
            if ordinal % 80 == 0 or ordinal == total:
                print(json.dumps({"written": ordinal, "total": total,
                                  "elapsedSeconds": round(time.monotonic() - started, 1)}, ensure_ascii=True), flush=True)

    # Read back the MRPack structure and stream-check every declared Java file.
    with zipfile.ZipFile(partial, "r") as packed:
        names = packed.namelist()
        if len(names) != len(set(name.casefold() for name in names)):
            raise ValueError("Output pack contains duplicate paths")
        if "modrinth.index.json" not in names:
            raise ValueError("Modrinth index is not at archive root")
        index_read = json.loads(packed.read("modrinth.index.json"))
        if index_read != index or len(index_read["files"]) != len(mods):
            raise ValueError("Modrinth index round-trip differs from the intended dependency set")
        for row in java_file_rows:
            path = "overrides/IC2-NewEra-runtime/java17/" + row["path"]
            checksum = hashlib.sha256()
            size = 0
            with packed.open(path) as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    checksum.update(block)
                    size += len(block)
            if size != row["sizeBytes"] or checksum.hexdigest().upper() != row["sha256"]:
                raise ValueError("Bundled Java file failed output round-trip: " + row["path"])
        if packed.read("overrides/config/ic2newera-robot-voice.json") != b'{\n  "enabled": false\n}\n':
            raise ValueError("Voice must be disabled in the ordinary pack")
        if b"lang:zh_cn" not in packed.read("overrides/options.txt"):
            raise ValueError("Chinese language option is missing")
        if "overrides/mods/" + OWN_JAR not in names:
            raise ValueError("First-party r48 mod was not included")
        if any(name.startswith("overrides/mods/") and name.lower().endswith(".jar") and OWN_JAR not in name
               for name in names):
            raise ValueError("A third-party mod JAR was copied into overrides")
        setup = packed.read("overrides/PCL/Setup.ini").decode("utf-8")
        if "VersionArgumentJavaV2:2" not in setup or "VersionArgumentIndieV2:True" not in setup:
            raise ValueError("PCL profile Java mode or isolation setting is missing")

    partial.replace(output)
    audit = {
        "archive": output.name, "sizeBytes": output.stat().st_size, "sha256": sha_file(output, "sha256"),
        "format": "Modrinth modpack v1; root modrinth.index.json; PCL2 import format verified from official source",
        "minecraft": "1.20.1", "forge": "47.4.28", "thirdPartyDependencies": [
            {"modId": row["modId"], "file": row["file"], "url": row["url"], "sizeBytes": row["sizeBytes"],
             "sha1": row["sha1"], "sha512": row["sha512"]} for row in mods],
        "firstPartyMod": {"path": "overrides/mods/" + OWN_JAR, "sha256": OWN_SHA256,
                          "sizeBytes": own.stat().st_size},
        "exactFirstPartyDependencyPins": exact_dependency_pins,
        "java": {"vendor": "Eclipse Temurin", "version": java_version, "architecture": "Windows x64",
                 "root": "overrides/IC2-NewEra-runtime/java17", "fileCount": len(java_file_rows),
                 "uncompressedBytes": java_total, "archiveSha256": JAVA_ZIP_SHA256,
                 "sourceArchive": "overrides/IC2-NewEra-runtime/source/" + JAVA_SOURCE,
                 "sourceArchiveSizeBytes": source_path.stat().st_size,
                 "sourceArchiveSha256": source_row["sha256"].upper()},
        "pclSetup": "VersionArgumentJavaV2=2; version isolation enabled in PCL/Setup.ini",
        "voiceEnabledByDefault": False, "projecteZeroValueEntries": len(emc["entries"]),
        "chineseOptions": True, "thirdPartyJarRedistribution": False,
        "accountDataRead": False, "apiKeysRead": False, "savesIncluded": False,
        "launcherExecuted": False, "gameExecuted": False, "testsRun": False,
        "staticArchiveRoundTripVerified": True,
        "createdAt": datetime.now(timezone(timedelta(hours=8))).isoformat()
    }
    audit_path.write_bytes(json_bytes(audit))
    print(json.dumps({"archive": audit["archive"], "sizeBytes": audit["sizeBytes"],
                      "sha256": audit["sha256"], "javaFiles": len(java_file_rows), "mods": len(mods)},
                     ensure_ascii=True), flush=True)
    return audit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    build_pack()


if __name__ == "__main__":
    main()
