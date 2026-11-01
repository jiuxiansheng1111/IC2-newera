"""Build the Windows private playtest installer and its relocatable voice data.

Exports use explicit first-party inputs and the selected voice dependency closure.
They never traverse Minecraft accounts, settings, saves, or environment secrets.
Large third-party/model archives remain local delivery artifacts, not source releases.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import io
import json
import os
import re
import sys
import time
import zipfile
from datetime import datetime, timezone, timedelta
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
BUILD = ROOT / "build-isolated/player-packaging/20261024-voice-slim"
PLAYER_BUILD = ROOT / "build-isolated/player-packaging/20261024-r41-slimvoice"
JAVA_SOURCE = ROOT / "build-isolated/player-packaging/20261024-r41-java17/java-source"
PLAYER = DIST / "IC2-NewEra-Voice-Installer-Win64-2026.10.24.zip"
VOICE = DIST / "IC2-NewEra-Voice-Slim-Win64-2026.10.24.zip"
PREFIX = "IC2-NewEra-Voice-Installer-Win64"
VERSION = "0.2.0-playtest.20261024-r41-slimvoice"
PLAYER_VERSION = VERSION + "-voice-installer"
VOICE_SIZE_LIMIT = 9_000_000_000
CHECKPOINT = ROOT / "build-isolated/migration-1201/checkpoints/build41-robot-space-tag"
MOD_LOCK = ROOT / "build-isolated/migration-1201/dependencies/runtime-lock-1201.json"
EXPECTED_JAR = "56E584D8B03D145EB759808811138C1DF36E3C1B72480E7B94EDA29B7BCC5254"
JAR_NAME = "ic2newera-0.2.0-migration1201-all.jar"


def digest_file(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            value.update(block)
    return value.hexdigest().upper()


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def safe_name(value: str) -> str:
    if (not value or "\\" in value or ":" in value or value.startswith("/")
            or any(part in ("", ".", "..") for part in value.split("/"))):
        raise ValueError("Unsafe archive entry")
    return PurePosixPath(value).as_posix()


def load_collector():
    path = ROOT / "scripts/player-pack/collect_voice_slim_payload.py"
    spec = importlib.util.spec_from_file_location("ic2newera_voice_payload", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Voice collector is missing")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.collect_voice_payload(ROOT)


def progress(message: str) -> None:
    print(message, flush=True)


def build_voice(*, plan_only: bool = False) -> dict:
    payload = load_collector()
    sources = {safe_name(name): Path(path) for name, path in payload["files"].items()}
    generated = {safe_name(name): bytes(value) for name, value in payload["generated"].items()}
    for notice in ("README-zh_CN.txt", "README-en_US.txt", "THIRD-PARTY-NOTICES.txt"):
        generated["tools/robot_voice/player-notices/" + notice] = (ROOT / "scripts/player-pack" / notice).read_bytes()
    generated["tools/robot_voice/player-notices/LICENSE-first-party-MIT.txt"] = (ROOT / "LICENSE").read_bytes()
    if sources.keys() & generated.keys():
        raise ValueError("Generated and source voice entries collide")
    names = sorted(set(sources) | set(generated))
    if len({name.casefold() for name in names}) != len(names):
        raise ValueError("Case-insensitive archive paths collide")
    if any(not name.startswith("tools/robot_voice/") for name in names):
        raise ValueError("Voice entries must remain under tools/robot_voice")
    for name, source in sources.items():
        if not source.is_file() or source.is_symlink():
            raise ValueError("Voice dependency is absent or linked: " + name)
        if source.name.casefold() in {"pyvenv.cfg", ".env", "direct_url.json"}:
            raise ValueError("Private or nonportable metadata would enter the pack")
    if payload.get("metadata", {}).get("unresolved_or_blocking_items"):
        raise ValueError("Unresolved runtime closure: " + str(payload["metadata"]["unresolved_or_blocking_items"]))
    total = sum(path.stat().st_size for path in sources.values()) + sum(map(len, generated.values()))
    BUILD.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(parents=True, exist_ok=True)
    plan = {"files": len(names), "uncompressedBytes": total,
            "metadata": payload.get("metadata", {}), "planOnly": plan_only,
            "privateVoiceAssetsNotUploaded": True, "testsRun": False}
    (BUILD / "voice-plan.json").write_bytes(json_bytes(plan))
    progress(json.dumps({"voiceFileCount": len(names), "voiceUncompressedBytes": total,
                         "planOnly": plan_only}, ensure_ascii=True))
    if plan_only:
        return plan
    if VOICE.exists():
        raise FileExistsError("Voice delivery already exists; review it before producing a new revision")
    partial = VOICE.with_suffix(VOICE.suffix + ".partial")
    if partial.exists():
        raise FileExistsError("Review and remove the exact incomplete voice .partial file before restarting")
    rows = []
    count, done, last_report = 0, 0, time.monotonic()
    with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_DEFLATED,
                         compresslevel=1, allowZip64=True) as archive:
        for name in names:
            checksum = hashlib.sha256()
            size = 0
            # Stream both input and compression: no multi-gigabyte in-memory reads.
            with archive.open(name, "w", force_zip64=True) as out:
                if name in generated:
                    data = generated[name]
                    out.write(data)
                    checksum.update(data)
                    size = len(data)
                else:
                    with sources[name].open("rb") as source:
                        for block in iter(lambda: source.read(1024 * 1024), b""):
                            out.write(block)
                            checksum.update(block)
                            size += len(block)
            rows.append({"path": name, "sizeBytes": size, "sha256": checksum.hexdigest().upper()})
            count += 1
            done += size
            if time.monotonic() - last_report >= 15:
                progress(json.dumps({"voiceFilesWritten": count, "voiceBytesWritten": done,
                                     "totalBytes": total}, ensure_ascii=True))
                last_report = time.monotonic()
        manifest = {"schemaVersion": 1, "packVersion": VERSION, "platform": "Windows-x64",
                    "files": rows, "totalBytes": done, "fileCount": count,
                    "distributionScope": "private noncommercial playtest; model rights not relicensed"}
        archive.writestr("voice-files.json", json_bytes(manifest))
    if partial.stat().st_size >= VOICE_SIZE_LIMIT:
        raise ValueError("Voice archive exceeds the 9 GB publishing budget; do not deliver the partial file")
    partial.replace(VOICE)
    # Structural/integrity review is a packaging check, not inference or a game test.
    with zipfile.ZipFile(VOICE) as archive:
        by_name = {entry.filename: entry for entry in archive.infolist()}
        if len(by_name) != count + 1 or len(by_name) != len(archive.infolist()):
            raise ValueError("Voice archive has missing or duplicate entries")
        for row in rows:
            if by_name[row["path"]].file_size != row["sizeBytes"]:
                raise ValueError("Voice archive byte count changed")
        if json.loads(archive.read("voice-files.json")) != manifest:
            raise ValueError("Voice archive manifest changed")
        configuration = json.loads(archive.read("tools/robot_voice/ic2newera-voice-runtime.json"))
        assert configuration["base_tts"]["python"].startswith("portable/")
        assert configuration["rvc"]["python"].startswith("portable/")
    audit = dict(plan, planOnly=False, archive=VOICE.name, bytes=VOICE.stat().st_size,
                 sha256=digest_file(VOICE), uncompressedBytes=done,
                 independentMachineInferenceVerified=False, computerUseStopped=True,
                 hardSizeLimitBytes=VOICE_SIZE_LIMIT, underSizeLimit=True)
    (BUILD / "voice-archive-audit.json").write_bytes(json_bytes(audit))
    (DIST / (VOICE.name + ".sha256")).write_text(audit["sha256"] + "  " + VOICE.name + "\n", encoding="ascii")
    progress(json.dumps({k: audit[k] for k in ("archive", "bytes", "sha256", "uncompressedBytes")}, ensure_ascii=True))
    return audit


def bootstrap_entries() -> dict[str, bytes | Path]:
    """Relocate the existing CPython base, not its venv redirector or global packages."""
    base = Path(sys.base_prefix).resolve(strict=True)
    if sys.version_info[:2] != (3, 11) or os.name != "nt":
        raise RuntimeError("Build the installer bootstrap with existing Windows CPython 3.11")
    required = ["python.exe", "pythonw.exe", "python311.dll", "LICENSE.txt"]
    for name in required:
        if not (base / name).is_file():
            raise FileNotFoundError("CPython bootstrap dependency missing: " + name)
    result: dict[str, bytes | Path] = {"bootstrap/" + name: base / name for name in required}
    for source in sorted(base.glob("*.dll")):
        result["bootstrap/" + source.name] = source
    for source in sorted((base / "DLLs").rglob("*")):
        if source.is_file() and "_tkinter" not in source.name:
            result["bootstrap/DLLs/" + source.relative_to(base / "DLLs").as_posix()] = source
    PLAYER_BUILD.mkdir(parents=True, exist_ok=True)
    stdlib = PLAYER_BUILD / "bootstrap-python311.zip"
    with zipfile.ZipFile(stdlib, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as library:
        for source in sorted((base / "Lib").rglob("*")):
            if not source.is_file():
                continue
            relative = source.relative_to(base / "Lib")
            if any(part.casefold() in {"site-packages", "__pycache__", "test", "idlelib", "tkinter", "ensurepip"}
                   for part in relative.parts) or source.suffix in {".pyc", ".pyo"}:
                continue
            library.write(source, relative.as_posix())
    result["bootstrap/python311.zip"] = stdlib
    result["bootstrap/python311._pth"] = b"python311.zip\n.\nDLLs\n../installer\nimport site\n"
    return result


def locked_mods() -> list[dict]:
    lock = json.loads(MOD_LOCK.read_text(encoding="utf-8-sig"))
    if (lock["target"]["minecraft"], lock["target"]["forge"]) != ("1.20.1", "47.4.28"):
        raise ValueError("Wrong Minecraft lock")
    result = []
    for source in lock["runtimeMods"]:
        path = MOD_LOCK.parent / source["file"]
        if path.stat().st_size != source["sizeBytes"] or digest_file(path) != source["sha256"].upper():
            raise ValueError("Locked dependency changed: " + source["modId"])
        url = source["source"]
        if source["modId"] == "ic2":
            url = "https://github.com/craftorio/ic2/releases/download/2.10.33-ex120/ic2-forge-2.10.33-ex120.jar"
        if not url.startswith("https://"):
            raise ValueError("Missing public HTTPS artifact source")
        result.append({"modId": source["modId"], "file": source["file"], "version": source["version"],
                       "sizeBytes": source["sizeBytes"], "sha256": source["sha256"].upper(), "source": url})
    jar = CHECKPOINT / JAR_NAME
    if digest_file(jar) != EXPECTED_JAR:
        raise ValueError("First-party r41 release changed")
    result.append({"modId": "ic2newera", "file": JAR_NAME, "version": "0.2.0-migration1201",
                   "sizeBytes": jar.stat().st_size, "sha256": EXPECTED_JAR,
                   "bundled": "payload/mods/" + JAR_NAME})
    if len(result) != 14 or len({row["modId"] for row in result}) != 14:
        raise ValueError("Runtime mod set is not the reviewed fourteen")
    return result


def java_payload() -> tuple[dict[str, bytes | Path], dict]:
    """Keep the vendor binary, notices and matching full source unmodified."""
    receipt = json.loads((JAVA_SOURCE / "adoptium-download-receipt.json").read_text(encoding="utf-8"))
    by_name = {row["name"]: row for row in receipt["files"]}
    binary_name = "OpenJDK17U-jdk_x64_windows_hotspot_17.0.20_8.zip"
    source_name = "OpenJDK17U-jdk-sources_17.0.20_8.tar.gz"
    expected = "418497BE5CF585BDD2203D6486A565D66D3F5E992D5630D45104CB873FAB8122"
    for name in (binary_name, source_name):
        source = JAVA_SOURCE / name
        if source.stat().st_size != by_name[name]["sizeBytes"] or digest_file(source) != by_name[name]["sha256"]:
            raise ValueError("Original OpenJDK artifact changed: " + name)
    if by_name[binary_name]["sha256"] != expected or not by_name[binary_name]["vendorChecksumMatched"]:
        raise ValueError("OpenJDK vendor checksum was not confirmed")
    rows = []
    with zipfile.ZipFile(JAVA_SOURCE / binary_name) as vendor:
        names = vendor.namelist()
        roots = {name.split("/", 1)[0] for name in names if "/" in name}
        if len(roots) != 1:
            raise ValueError("OpenJDK ZIP must have one top-level root")
        jdk_root = roots.pop()
        release = vendor.read(jdk_root + "/release").decode("utf-8-sig")
        properties = dict(re.findall(r'^([A-Z_]+)="([^"]*)"', release, flags=re.MULTILINE))
        if not properties.get("JAVA_VERSION", "").startswith("17.") or properties.get("OS_ARCH") not in {"x86_64", "amd64"}:
            raise ValueError("Bundled OpenJDK is not Java 17 x64")
        seen = set()
        for info in vendor.infolist():
            if info.is_dir():
                continue
            name = safe_name(info.filename)
            if not name.startswith(jdk_root + "/"):
                raise ValueError("Vendor file is outside its root")
            relative = name[len(jdk_root) + 1:]
            if relative.casefold() in seen:
                raise ValueError("Vendor ZIP contains colliding names")
            seen.add(relative.casefold())
            checksum = hashlib.sha256()
            size = 0
            with vendor.open(info) as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    checksum.update(block)
                    size += len(block)
            if size != info.file_size:
                raise ValueError("Vendor entry changed size")
            rows.append({"path": relative, "sizeBytes": size, "sha256": checksum.hexdigest().upper()})
        if not {"bin/java.exe", "bin/javaw.exe", "release", "legal/java.base/license"}.issubset(seen):
            raise ValueError("Vendor runtime or its license is incomplete")
    files_manifest = json_bytes({"schemaVersion": 1, "rootDirectory": jdk_root,
                                 "totalBytes": sum(row["sizeBytes"] for row in rows), "files": rows})
    manifest = {"vendor": "Eclipse Temurin", "javaVersion": properties["JAVA_VERSION"],
                "architecture": "x64", "archive": "payload/java17/" + binary_name,
                "sizeBytes": by_name[binary_name]["sizeBytes"], "sha256": expected,
                "rootDirectory": jdk_root, "filesManifest": "payload/java17/java-files.json",
                "filesManifestSizeBytes": len(files_manifest),
                "filesManifestSha256": hashlib.sha256(files_manifest).hexdigest().upper(),
                "uncompressedBytes": sum(row["sizeBytes"] for row in rows),
                "sourceArchive": "payload/java17/" + source_name,
                "sourceArchiveSha256": by_name[source_name]["sha256"],
                "sourceRepository": receipt["sourceRepository"],
                "sourceReference": receipt["sourceReference"],
                "license": "GPL-2.0-only WITH Classpath-exception-2.0"}
    notice = ("Unmodified Eclipse Temurin OpenJDK 17.0.20+8 Windows x64.\n"
              "The full vendor ZIP preserves legal/ notices and LICENSE files.\n"
              "Matching full source is included alongside the binary archive.\n"
              "Official source: " + receipt["sourceRepository"] + "/tree/"
              + receipt["sourceReference"] + "\n"
              "OpenJDK and its third-party components retain their own licenses; "
              "the first-party MIT license does not replace them.\n")
    entries = {manifest["archive"]: JAVA_SOURCE / binary_name,
               manifest["sourceArchive"]: JAVA_SOURCE / source_name,
               manifest["filesManifest"]: files_manifest,
               "payload/java17/OPENJDK-NOTICES.txt": notice.encode("utf-8")}
    (PLAYER_BUILD / "bundled-java-audit.json").write_bytes(json_bytes(manifest))
    return entries, manifest


def build_player() -> dict:
    """Build the small optional voice installer; the game is a separate .mrpack."""
    voice_audit = json.loads((BUILD / "voice-archive-audit.json").read_text(encoding="utf-8"))
    if not VOICE.is_file() or VOICE.stat().st_size != voice_audit["bytes"]:
        raise ValueError("Build the actual slim voice archive first")
    if digest_file(VOICE) != voice_audit["sha256"]:
        raise ValueError("Voice delivery checksum mismatch")
    entries: dict[str, bytes | Path] = bootstrap_entries()
    for name in ("install_player.py", "java_runtime.py", "java_launcher.py", "install_voice_only.py"):
        entries["installer/" + name] = ROOT / "scripts/player-pack" / name
    entries["Install.cmd"] = (b"@echo off\r\nchcp 65001 >nul\r\n"
        b'"%~dp0bootstrap\\python.exe" -I -X utf8 "%~dp0installer\\install_voice_only.py" %*\r\n'
        b"if errorlevel 1 echo Voice installation did not finish. See the message above.\r\npause\r\n")
    manifest = {"schemaVersion": 1, "packVersion": PLAYER_VERSION, "minecraft": "1.20.1", "forge": "47.4.28",
                "voice": {"archive": VOICE.name, "sha256": voice_audit["sha256"],
                          "sizeBytes": voice_audit["bytes"], "uncompressedBytes": voice_audit["uncompressedBytes"],
                          "manifestEntry": "voice-files.json", "rootPrefix": "tools/robot_voice/"}}
    entries["install-manifest.json"] = json_bytes(manifest)
    entries["LICENSE-first-party-MIT.txt"] = ROOT / "LICENSE"
    for name in ("README-zh_CN.txt", "README-en_US.txt", "THIRD-PARTY-NOTICES.txt", "PLAYTEST-STATUS.txt"):
        entries[name] = ROOT / "scripts/player-pack" / name
    if PLAYER.exists():
        raise FileExistsError("Voice installer already exists; review before replacing it")
    partial = PLAYER.with_suffix(PLAYER.suffix + ".partial")
    if partial.exists():
        raise FileExistsError("Review the exact incomplete voice installer before rebuilding")
    rows = []
    with zipfile.ZipFile(partial, "w", zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
        for name, value in sorted(entries.items()):
            name = safe_name(name)
            raw = value if isinstance(value, bytes) else value.read_bytes()
            archive.writestr(PREFIX + "/" + name, raw)
            rows.append({"path": name, "sizeBytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest().upper()})
        archive.writestr(PREFIX + "/installer-files.json", json_bytes({"schemaVersion": 1, "files": rows}))
    with zipfile.ZipFile(partial) as archive:
        for row in rows:
            raw = archive.read(PREFIX + "/" + row["path"])
            if len(raw) != row["sizeBytes"] or hashlib.sha256(raw).hexdigest().upper() != row["sha256"]:
                raise ValueError("Voice installer archive round-trip mismatch")
    partial.replace(PLAYER)
    audit = {"archive": PLAYER.name, "bytes": PLAYER.stat().st_size, "sha256": digest_file(PLAYER),
             "packVersion": PLAYER_VERSION, "fileCount": len(rows) + 1,
             "purpose": "optional voice files only; import the no-voice .mrpack first",
             "voiceArchive": VOICE.name, "voiceArchiveSha256": voice_audit["sha256"],
             "gameOrJavaPayloadBundled": False, "gameOrJavaChangedByInstaller": False,
             "playerCredentialsRead": False, "savesBundled": False,
             "newInstallerExecuted": False, "independentMachineInferenceVerified": False,
             "testsRun": False, "exportedAt": datetime.now(timezone(timedelta(hours=8))).isoformat()}
    (PLAYER_BUILD / "player-archive-audit.json").write_bytes(json_bytes(audit))
    (DIST / (PLAYER.name + ".sha256")).write_text(audit["sha256"] + "  " + PLAYER.name + "\n", encoding="ascii")
    progress(json.dumps(audit, ensure_ascii=True))
    return audit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", action="store_true")
    parser.add_argument("--voice", action="store_true")
    parser.add_argument("--player", action="store_true")
    args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    if args.plan:
        build_voice(plan_only=True)
    elif args.voice:
        build_voice()
    elif args.player:
        build_player()
    else:
        parser.error("Choose --plan, --voice, or --player")


if __name__ == "__main__":
    main()
