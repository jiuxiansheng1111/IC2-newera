#!/usr/bin/env python3
"""Prepare and package the standalone r72 pack from the frozen r70 baseline.

Release builds stage in the system temporary directory on the release volume, then
transactionally promote only the five fixed files in dist/IC2-New-Era-r72. Other
working files stay under build-isolated/player-packaging/20261114-r72-standalone.
Source archives/releases are read-only.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
import re
import tempfile
import tomllib
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "W/r70-release"
WORK = ROOT / "build-isolated/player-packaging/20261114-r72-standalone"
PACK = WORK / "pack"
DIST = ROOT / "dist/IC2-New-Era-r72"
PCL_PROFILE = ROOT.parent / ".minecraft/versions/1.20.1-Forge_47.4.28"
PROFILE_MODS = PCL_PROFILE / "mods"
PROFILE_RESOURCEPACKS = PCL_PROFILE / "resourcepacks"
CLIENT_BASE = BASE / "IC2-New-Era-Windows-1.20.1-r70-Grove-CMM.mrpack"
SERVER_BASE = BASE / "IC2-New-Era-Server-1.20.1-r70-Voice.zip"
OWN = "ic2newera-0.2.0-migration1201-all.jar"
GEAR_FEAST_MOD_IDS = {"create_delight_remake", "create_delight", "create_delight_remake"}
GEAR_FEAST_NAME_HINTS = ("create-delight-remake", "create_delight_remake", "gearsfeast")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def sha_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest().upper()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_mkdir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    if not path.resolve().is_relative_to(WORK.resolve()):
        raise RuntimeError(f"Output escaped r72 work directory: {path}")


def jar_mod_metadata(path: Path) -> dict:
    ids: set[str] = set()
    nested_ids: set[str] = set()
    dependencies: list[dict] = []
    visited: set[str] = set()

    def inspect(data: bytes | None, jar_path: Path | None, label: str, nested: bool = False) -> None:
        try:
            jar = zipfile.ZipFile(jar_path) if jar_path is not None else zipfile.ZipFile(io.BytesIO(data or b""))
        except Exception:
            return
        with jar:
            names = jar.namelist()
            meta = next((n for n in names if n.upper() == "META-INF/MODS.TOML"), None)
            if meta:
                try:
                    parsed = tomllib.loads(jar.read(meta).decode("utf-8"))
                    local = {str(m["modId"]) for m in parsed.get("mods", []) if m.get("modId")}
                    (nested_ids if nested else ids).update(local)
                    for owner, reqs in parsed.get("dependencies", {}).items():
                        for req in reqs:
                            if req.get("mandatory") is True and req.get("modId"):
                                dependencies.append({"from": owner, "requires": req["modId"], "origin": label})
                except Exception as exc:
                    dependencies.append({"metadataParseError": label, "error": str(exc)})
            metadata_name = next((n for n in names if n.upper() == "META-INF/JARJAR/METADATA.JSON"), None)
            if metadata_name:
                try:
                    metadata = json.loads(jar.read(metadata_name))
                    for entry in metadata.get("jars", []):
                        entry_path = entry.get("path")
                        if not entry_path or entry_path in visited:
                            continue
                        visited.add(entry_path)
                        inspect(jar.read(entry_path), None, f"{label}!/{entry_path}", nested=True)
                except Exception as exc:
                    dependencies.append({"jarJarMetadataParseError": label, "error": str(exc)})

    inspect(None, path, str(path), False)
    return {"modIds": sorted(ids), "nestedJarjarModIds": sorted(nested_ids),
            "mandatoryDependencies": dependencies}


def validate_inputs() -> tuple[dict, list[dict]]:
    if not CLIENT_BASE.is_file() or not SERVER_BASE.is_file():
        raise FileNotFoundError("Frozen r70 Windows mrpack and server ZIP are required")
    with zipfile.ZipFile(CLIENT_BASE) as z:
        index = json.loads(z.read("modrinth.index.json"))
    with zipfile.ZipFile(SERVER_BASE) as z:
        names = z.namelist()
        root = "IC2-New-Era-Server-1.20.1-r70-Voice/"
        mods = []
        for name in names:
            if not name.startswith(root + "mods/") or not name.lower().endswith(".jar"):
                continue
            rel = name[len(root):]
            if "gear" in Path(rel).name.lower() and any(h in Path(rel).name.lower() for h in GEAR_FEAST_NAME_HINTS):
                continue
            data = z.read(name)
            mods.append({"path": rel, "sourceArchive": SERVER_BASE.relative_to(ROOT).as_posix() + "#" + name,
                         "sha256": sha(data), "sizeBytes": len(data)})
    if not mods:
        raise RuntimeError("No server mod JARs found in r70 baseline")
    return index, mods


def archive_mod_overrides() -> list[dict]:
    results = []
    with zipfile.ZipFile(CLIENT_BASE) as z:
        for name in z.namelist():
            if not (name.startswith("overrides/mods/") and name.lower().endswith(".jar")):
                continue
            data = z.read(name)
            results.append({"path": name.removeprefix("overrides/"), "archiveMember": name,
                            "sha256": sha(data), "sizeBytes": len(data)})
    return results


def stage_client_mods(index: dict) -> dict:
    """Hard-link the 46 locked r70+r71 client JARs into the install-ready input."""
    profile_mods = PROFILE_MODS
    search_roots = [profile_mods, PACK / "inputs/r71-debug/mods",
                    ROOT / "build-isolated/player-packaging/20261113-delight-joint",
                    ROOT / "W/r70-release"]
    client_dir = PACK / "inputs/client/mods"
    client_dir.mkdir(parents=True, exist_ok=True)
    expected = [f for f in index["files"] if f["path"].lower().endswith(".jar")
                and "jei-1201-15.56.0.205.jar" not in f["path"].lower()]
    extra_names = ["jei-1.20.1-forge-15.74.0.234.jar"]
    if len(expected) != 42:
        raise RuntimeError(f"Expected 42 r70 client JARs after replacing old JEI, got {len(expected)}")
    all_candidates: list[Path] = []
    for root in search_roots:
        if root.is_dir():
            all_candidates.extend(p for p in root.rglob("*.jar") if p.is_file())
    linked = []

    def install_file(target_name: str, candidate: Path, checks: dict) -> None:
        dest = client_dir / target_name
        digest = sha_file(candidate)
        if dest.exists():
            if dest.resolve() == candidate.resolve() or sha_file(dest) == digest:
                if dest.resolve() != candidate.resolve():
                    dest.unlink()
                    os.link(candidate, dest)
            else:
                dest.unlink()
                os.link(candidate, dest)
        else:
            try:
                os.link(candidate, dest)
                method = "hardlink"
            except OSError:
                shutil.copy2(candidate, dest)
                method = "copy-fallback"
        if not dest.is_file() or sha_file(dest) != digest:
            raise RuntimeError(f"Staged client JAR hash mismatch: {dest}")
        linked.append({"path": dest.relative_to(ROOT).as_posix(), "sourcePath": str(candidate.resolve()),
                       "sha256": digest, "sizeBytes": dest.stat().st_size,
                       "method": "hardlink" if os.stat(candidate).st_ino == os.stat(dest).st_ino else "copy-fallback",
                       **checks, **jar_mod_metadata(dest)})

    for item in expected:
        fname = Path(item["path"]).name
        candidates = [p for p in all_candidates if p.name.lower() == fname.lower()]
        match = None
        for candidate in candidates:
            if item["hashes"].get("sha512"):
                h = hashlib.sha512(candidate.read_bytes()).hexdigest().lower()
                if h != item["hashes"]["sha512"].lower():
                    continue
            if item["hashes"].get("sha1"):
                h1 = hashlib.sha1(candidate.read_bytes()).hexdigest().lower()
                if h1 != item["hashes"]["sha1"].lower():
                    continue
            match = candidate
            break
        if not match:
            raise FileNotFoundError(f"No local exact-hash source for locked client JAR {fname}")
        install_file(fname, match, {"sourceKind": "r70 locked Modrinth manifest",
                                    "expectedSha1": item["hashes"].get("sha1"),
                                    "expectedSha512": item["hashes"].get("sha512")})

    for fname in extra_names:
        candidates = [p for p in all_candidates if p.name.lower() == fname.lower()]
        if not candidates:
            raise FileNotFoundError(f"No local actual JAR found for approved r71 addition {fname}")
        # JEI 15.74 is the approved r71 upgrade replacing the older locked JEI.
        install_file(fname, candidates[0], {"sourceKind": "r71 approved installed/original JAR"})
    override_rows = archive_mod_overrides()
    if len(override_rows) != 3:
        raise RuntimeError(f"Expected 3 r70 active mod overrides, found {len(override_rows)}")
    for item in override_rows:
            fname = Path(item["path"]).name
            dest = client_dir / fname
            candidates = [p for p in all_candidates if p.name.lower() == fname.lower() and sha_file(p) == item["sha256"]]
            if candidates:
                source = candidates[0]
                if not dest.exists() or sha_file(dest) != item["sha256"]:
                    dest.unlink(missing_ok=True)
                    os.link(source, dest)
                source_path = str(source.resolve())
                method = "hardlink"
            else:
                with zipfile.ZipFile(CLIENT_BASE) as z:
                    data = z.read(item["archiveMember"])
                if not dest.exists() or sha_file(dest) != item["sha256"]:
                    dest.unlink(missing_ok=True)
                    dest.write_bytes(data)
                source_path = str(CLIENT_BASE.resolve()) + "#" + item["archiveMember"]
                method = "archive member extracted"
            if sha_file(dest) != item["sha256"]:
                raise RuntimeError(f"Override JAR failed source hash check: {fname}")
            linked.append({"path": dest.relative_to(ROOT).as_posix(),
                           "sourcePath": source_path,
                           "sha256": item["sha256"], "sizeBytes": item["sizeBytes"],
                           "method": method, "sourceKind": "r70 Windows mrpack override",
                           **jar_mod_metadata(dest)})
    if len(linked) != 46 or len({Path(x["path"]).name.lower() for x in linked}) != 46:
        raise RuntimeError(f"Expected 46 unique client JARs; staged {len(linked)}")
    write_json(PACK / "target-manifest-client-jars.json", {
        "revision": "r72-client-install-inputs", "count": len(linked), "modsDirectory": client_dir.relative_to(ROOT).as_posix(),
        "base": "r70 Windows manifest exact SHA-1/SHA-512 plus all 3 active mod JARs from overrides; JEI upgraded to approved r71 15.74.0.234; no extra hard dependency added",
        "items": linked,
    })
    return {"count": len(linked), "directory": str(client_dir), "manifest": str(PACK / "target-manifest-client-jars.json")}


def integrate_first_party() -> dict | None:
    source = WORK / "native" / OWN
    manifest_path = source.with_suffix(".manifest.json")
    if not source.is_file() or not manifest_path.is_file():
        return None
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    actual = sha_file(source)
    expected = str(manifest.get("result_sha256", "")).upper()
    if not expected or actual != expected:
        raise RuntimeError(f"r72 first-party JAR disagrees with its sibling manifest: {actual} != {expected}")
    client_dest = PACK / "inputs/client/mods" / OWN
    debug_dest = PACK / "inputs/r71-debug/mods" / OWN
    for dest in (client_dest, debug_dest):
        dest.parent.mkdir(parents=True, exist_ok=True)
        temporary = dest.with_suffix(dest.suffix + ".pending")
        temporary.unlink(missing_ok=True)
        shutil.copy2(source, temporary)
        if sha_file(temporary) != actual:
            temporary.unlink(missing_ok=True)
            raise RuntimeError(f"Staged first-party copy hash mismatch: {temporary}")
        os.replace(temporary, dest)
    # r70 already contains JEI on both sides; upgrade that same slot to the r71-approved build.
    jei_source = PACK / "inputs/client/mods/jei-1.20.1-forge-15.74.0.234.jar"
    if not jei_source.is_file():
        raise FileNotFoundError("Approved JEI 15.74.0.234 client input missing")
    server_mod_dir = PACK / "inputs/r71-debug/mods"
    for old in server_mod_dir.glob("jei-1201-15.56.0.205.jar"):
        old.unlink()
    jei_server = server_mod_dir / jei_source.name
    jei_server.unlink(missing_ok=True)
    os.link(jei_source, jei_server)
    client_rows = json.loads((PACK / "target-manifest-client-jars.json").read_text(encoding="utf-8"))
    for row in client_rows["items"]:
        if Path(row["path"]).name == OWN:
            row.update(sourcePath=str(source.resolve()), sha256=actual, sizeBytes=source.stat().st_size,
                       method="copy", sourceKind="r72 first-party native JAR")
    write_json(PACK / "target-manifest-client-jars.json", client_rows)
    for filename in ("target-manifest-client.json", "target-manifest-server.json"):
        path = PACK / filename
        data = json.loads(path.read_text(encoding="utf-8"))
        data["firstPartyJar"] = {"expectedPath": "mods/" + OWN, "sourcePath": str(source.resolve()),
                                 "sha256": actual, "sizeBytes": source.stat().st_size,
                                 "status": "staged r72 first-party JAR"}
        if filename.endswith("server.json"):
            data["mods"] = [m for m in data["mods"] if Path(m["path"]).name.lower() != "jei-1201-15.56.0.205.jar"]
            data["mods"].append({
                "path": "mods/" + jei_source.name,
                "sourceArchive": SERVER_BASE.relative_to(ROOT).as_posix() + "#r71-approved-locked-JEI-15.74.0.234",
                "targetSourcePath": str(jei_source.resolve()), "sha256": sha_file(jei_source),
                "sizeBytes": jei_source.stat().st_size, "stagedPath": jei_server.relative_to(ROOT).as_posix(),
                **jar_mod_metadata(jei_server),
            })
            for item in data["mods"]:
                if Path(item["path"]).name == OWN:
                    item.update(targetSourcePath=str(source.resolve()), sha256=actual,
                                sizeBytes=source.stat().st_size, **jar_mod_metadata(client_dest))
        write_json(path, data)
    server_manifest_path = PACK / "target-manifest-server.json"
    server_data = json.loads(server_manifest_path.read_text(encoding="utf-8"))
    client_data = json.loads((PACK / "target-manifest-client-jars.json").read_text(encoding="utf-8"))
    client_names = {Path(x["path"]).name.lower() for x in client_data["items"]}
    server_names = {Path(x["path"]).name.lower() for x in server_data["mods"]}
    audit_path = PACK / "target-manifest-audit.json"
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    audit["clientServerOverlapByTargetFilename"] = sorted(client_names & server_names)
    audit["serverOnlyByTargetFilename"] = sorted(server_names - client_names)
    audit["clientOnlyByTargetFilename"] = sorted(client_names - server_names)
    audit["clientTargetJarCount"] = len(client_names)
    audit["serverTargetJarCount"] = len(server_names)
    client_ids = {modid for item in client_data["items"] for modid in item.get("modIds", []) + item.get("nestedJarjarModIds", [])}
    server_ids = {modid for item in server_data["mods"] for modid in item.get("modIds", []) + item.get("nestedJarjarModIds", [])}
    audit["clientTargetModIds"] = sorted(client_ids)
    audit["serverTargetModIds"] = sorted(server_ids)
    audit["declaredServerModIds"] = sorted(server_ids)
    audit["serverOnlyModIds"] = sorted(server_ids - client_ids)
    audit["clientOnlyModIds"] = sorted(client_ids - server_ids)
    audit["sharedModIds"] = sorted(client_ids & server_ids)
    audit["targetServerMissingMandatoryDependencies"] = sorted({
        dep["requires"] for item in server_data["mods"] for dep in item.get("mandatoryDependencies", [])
        if dep.get("requires") not in (server_ids | {"minecraft", "forge", "javafml", "lowcodefml", "fml"})
    })
    client_hashes_by_name = {Path(item["path"]).name.lower(): item["sha256"] for item in client_data["items"]}
    server_hashes_by_name = {Path(item["path"]).name.lower(): item["sha256"] for item in server_data["mods"]}
    mismatches = {name: {"client": client_hashes_by_name[name], "server": server_hashes_by_name[name]}
                  for name in client_hashes_by_name.keys() & server_hashes_by_name.keys()
                  if client_hashes_by_name[name] != server_hashes_by_name[name]}
    audit["targetSharedJarSha256Mismatches"] = mismatches
    if mismatches:
        raise RuntimeError(f"Shared client/server mod JAR SHA256 mismatch: {mismatches}")
    write_json(audit_path, audit)
    write_json(server_manifest_path, server_data)
    return {"path": str(client_dest), "sha256": actual, "sizeBytes": source.stat().st_size}


def stage_client_model_fix() -> dict:
    filename = "NewEra-CMM-Model-Fix.zip"
    source_options = [
        PROFILE_RESOURCEPACKS / filename,
        ROOT / "build-isolated/player-packaging/20261113-delight-joint/client-overlay/resourcepacks" / filename,
    ]
    source = next((p for p in source_options if p.is_file()), None)
    if not source:
        raise FileNotFoundError("Approved Windows NewEra CMM model fix resource pack is missing")
    digest = sha_file(source)
    expected = "B104D293F050FBCFFCEE84C505ED38ACD04509704ADCB813797AB01CA05D60E1"
    if digest != expected:
        raise RuntimeError(f"NewEra CMM model fix hash changed unexpectedly: {digest}")
    dest = PACK / "overrides/resourcepacks" / filename
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.unlink(missing_ok=True)
    os.link(source, dest)
    order = ["vanilla", "Soartex Grove 1.0.4.zip", "CMMRP_Release_1.0_[1.20-1.20.1].zip",
             filename, "CMM_FIX_EMF_v.1_[1.20-1.20.1].zip"]
    write_json(PACK / "target-manifest-client-resourcepack.json", {
        "path": dest.relative_to(ROOT).as_posix(), "sourcePath": str(source.resolve()),
        "sha256": digest, "sizeBytes": dest.stat().st_size,
        "orderObservedInReadOnlyWindowsOptions": order,
        "newEraPhoneKeyMappings": {
            "key_key.ic2newera.phone_camera_view": "key.keyboard.f6",
            "key_key.ic2newera.phone_camera_capture": "key.keyboard.f8",
            "key_key.ic2newera.phone_camera_upload": "key.keyboard.f9",
            "key_key.ic2newera.phone_gallery": "key.keyboard.insert",
            "key_key.ic2newera.phone_camera_exit": "key.keyboard.backspace",
        },
        "windowsOnly": True,
    })
    return {"path": str(dest), "sha256": digest}


def quest_payload() -> dict[str, bytes]:
    base = WORK / "handbook-overlay"
    required = [base / "config/ftbquests/quests", base / "config/ic2newera/quest_knowledge.json"]
    if not all(p.exists() for p in required):
        raise FileNotFoundError("r72 handbook overlay or assistant index is missing")
    result = {}
    for file in sorted((base / "config/ftbquests/quests").rglob("*")):
        if file.is_symlink():
            raise RuntimeError(f"Quest overlay cannot contain a symlink: {file}")
        if file.is_file():
            rel = file.relative_to(base).as_posix()
            result[rel] = file.read_bytes()
    knowledge = base / "config/ic2newera/quest_knowledge.json"
    result[knowledge.relative_to(base).as_posix()] = knowledge.read_bytes()
    if not any(k.endswith("/chapter_groups.snbt") for k in result) or not any(k.endswith("/data.snbt") for k in result):
        raise RuntimeError("The final quest overlay must contain chapter_groups.snbt and data.snbt")
    json.loads(result["config/ic2newera/quest_knowledge.json"].decode("utf-8"))
    return result


def options_with_model_fix(raw: bytes) -> bytes:
    text = raw.decode("utf-8")
    desired = ["vanilla", "file/Soartex Grove 1.0.4.zip",
               "file/CMMRP_Release_1.0_[1.20-1.20.1].zip", "file/NewEra-CMM-Model-Fix.zip",
               "file/CMM_FIX_EMF_v.1_[1.20-1.20.1].zip"]
    line = "resourcePacks:" + json.dumps(desired, ensure_ascii=False, separators=(",", ":"))
    if re.search(r"(?m)^resourcePacks:.*$", text):
        text = re.sub(r"(?m)^resourcePacks:.*$", lambda _: line, text)
    else:
        text = text.rstrip("\r\n") + "\n" + line + "\n"
    mappings = {
        "key_key.ic2newera.phone_camera_view": "key.keyboard.f6",
        "key_key.ic2newera.phone_camera_capture": "key.keyboard.f8",
        "key_key.ic2newera.phone_camera_upload": "key.keyboard.f9",
        "key_key.ic2newera.phone_gallery": "key.keyboard.insert",
        "key_key.ic2newera.phone_camera_exit": "key.keyboard.backspace",
    }
    lines = text.splitlines()
    for key, value in mappings.items():
        item = f"{key}:{value}"
        existing = next((i for i, content in enumerate(lines) if content.startswith(key + ":")), None)
        if existing is None:
            lines.append(item)
        else:
            lines[existing] = item
    text = "\n".join(lines) + "\n"
    return text.encode("utf-8")


def package_client(overlay: dict[str, bytes], out_path: Path, verified: dict) -> dict:
    with zipfile.ZipFile(CLIENT_BASE) as source:
        index = json.loads(source.read("modrinth.index.json"))
        old_jei = "jei-1201-15.56.0.205.jar"
        index["files"] = [f for f in index["files"] if Path(f["path"]).name.lower() != old_jei.lower()]
        jei = next(x for x in verified["compatibilityHotfixes"] if x.get("filename") == "jei-1.20.1-forge-15.74.0.234.jar")
        index["files"].append({
            "path": "mods/" + jei["filename"],
            "hashes": {"sha1": jei["sha1"], "sha512": jei["sha512"]},
            "env": {"client": "required", "server": "required"},
            "downloads": [jei["source"]["downloadUrl"]], "fileSize": jei["fileSize"],
        })
        index.update(versionId="0.2.0-windows.20261114-r72-standalone",
                     name="IC2：新纪元 Windows 完整整合包 r72",
                     summary="Forge 47.4.28；保留r70活动模组与资源，使用r72新纪元任务、摄影目标和内置助手资料。")
        entries: dict[str, bytes] = {}
        old_own = "overrides/mods/" + OWN
        options_key = "overrides/options.txt"
        with zipfile.ZipFile(CLIENT_BASE) as z:
            replaced_info = {
                "overrides/IC2-NewEra-info/README-zh_CN.txt",
                "overrides/IC2-NewEra-info/README-en_US.txt",
                "overrides/IC2-NewEra-info/PLAYTEST-STATUS.txt",
                "overrides/IC2-NewEra-info/r70-artifact-audit.json",
                "overrides/IC2-NewEra-info/r72-artifact-audit.json",
            }
            for name in z.namelist():
                if (name in {"modrinth.index.json", old_own} or name in overlay
                        or name == "overrides/resourcepacks/NewEra-CMM-Model-Fix.zip" or name in replaced_info):
                    continue
                if name == options_key:
                    entries[name] = options_with_model_fix(z.read(name))
                else:
                    entries[name] = z.read(name)
        entries["modrinth.index.json"] = (json.dumps(index, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        entries[old_own] = (WORK / "native" / OWN).read_bytes()
        entries["overrides/resourcepacks/NewEra-CMM-Model-Fix.zip"] = (PACK / "overrides/resourcepacks/NewEra-CMM-Model-Fix.zip").read_bytes()
        for path, data in overlay.items():
            entries["overrides/" + path] = data
        chapter_count = len(list((WORK / "handbook-overlay/config/ftbquests/quests/chapters").glob("*.snbt")))
        knowledge = json.loads(overlay["config/ic2newera/quest_knowledge.json"])
        target_audit = json.loads((PACK / "target-manifest-audit.json").read_text(encoding="utf-8"))
        final_jar = WORK / "native" / OWN
        r72_audit = {
            "revision": "r72", "newEraJar": OWN, "newEraSha256": sha_file(final_jar),
            "newEraSizeBytes": final_jar.stat().st_size,
            "jei": {"version": "15.74.0.234", "sha256": jei["sha256"], "fileSize": jei["fileSize"]},
            "minecraft": "1.20.1", "forge": "47.4.28", "clientJarCount": 46, "serverJarCount": 35,
            "chapterCount": chapter_count, "indexedQuestCount": len(knowledge.get("quests", [])),
            "questOverlayFiles": {p: {"sha256": sha(data), "sizeBytes": len(data)} for p, data in overlay.items()},
            "sharedModIds": target_audit.get("sharedModIds", []),
            "missingServerDependencies": target_audit.get("targetServerMissingMandatoryDependencies", []),
            "sharedJarSha256Mismatches": target_audit.get("targetSharedJarSha256Mismatches", {}),
            "status": "built; static package checks run; no gameplay acceptance claimed",
        }
        entries["overrides/IC2-NewEra-info/r72-artifact-audit.json"] = (json.dumps(r72_audit, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        entries["overrides/IC2-NewEra-info/README-zh_CN.txt"] = (
            "IC2：新纪元 Windows 完整整合包 r72\nMinecraft 1.20.1 / Forge 47.4.28 / Java 17 / 46个客户端JAR。\n"
            "保留r70活动模组和客户端资源，JEI更新至15.74.0.234；使用r72新纪元JAR、FTB任务书和助手资料。\n"
            f"FTB任务书含{chapter_count}章、{len(knowledge.get('quests', []))}项索引任务。\n"
            "Grove、CMM与ITT仅用于客户端；内置独立CMM修复小包，资源顺序为Grove/CMMRP/新纪元小包/CMM_FIX_EMF。\n"
            "手机快捷键：F6取景、F8拍照、F9上传、Insert相册、Backspace退出。通过PCL导入；首次启动需联网下载清单中的模组。\n"
            "与r72完整服务端包配套。打包核验不代表游戏内玩法已完成验收。\n").encode("utf-8")
        entries["overrides/IC2-NewEra-info/README-en_US.txt"] = (
            "IC2: New Era Windows full pack r72\nMinecraft 1.20.1 / Forge 47.4.28 / Java 17 / 46 client JARs.\n"
            "Retains the r70 active mod set and client visuals; JEI is upgraded to 15.74.0.234.\n"
            "Includes the r72 New Era JAR, FTB quest book and assistant index. Import with PCL; first launch downloads listed mods.\n"
            "Grove, CMM and ITT are client-side. Resource order: Grove / CMMRP / New Era model fix / CMM_FIX_EMF.\n"
            "Phone keys: F6 view, F8 capture, F9 upload, Insert gallery, Backspace exit. Use with the matching r72 server.\n"
            "Package checks do not claim in-game acceptance.\n").encode("utf-8")
        entries["overrides/IC2-NewEra-info/PLAYTEST-STATUS.txt"] = (
            "r72 package status: complete Windows and dual-platform server archives are built and statically checked.\n"
            "No game is started by the packaging script. Actual gameplay acceptance is tracked separately.\n").encode("utf-8")
        info = ("IC2：新纪元 r72 Windows 整合包\nMinecraft 1.20.1 / Forge 47.4.28\n"
                "r70活动模组基线、Windows Grove/CMM视觉内容与服务器依赖保持；JEI升级到15.74.0.234。\n"
                "新纪元JAR及任务书/助手资料为r72版本。首次导入需联网获取清单中的第三方模组。\n"
                "资源包顺序：Grove → CMMRP → NewEra-CMM-Model-Fix → CMM_FIX_EMF。\n"
                "手机：F6取景、F8拍照、F9上传、Insert相册、Backspace退出。\n")
        entries["overrides/IC2-NewEra-info/r72-release.txt"] = info.encode("utf-8")
        out_path.parent.mkdir(parents=True, exist_ok=True)
        if out_path.exists():
            raise FileExistsError(f"Refusing to overwrite existing r72 artifact: {out_path}")
        with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as dest:
            for name, data in sorted(entries.items()):
                dest.writestr(name, data)
        return {"path": str(out_path), "sha256": sha_file(out_path), "sizeBytes": out_path.stat().st_size,
                "entries": len(entries), "downloadFileCount": len(index["files"]),
                "downloadModCount": sum(1 for item in index["files"]
                                         if item["path"].replace("\\", "/").lower().startswith("mods/"))}


def package_server(overlay: dict[str, bytes], out_path: Path, verified: dict) -> dict:
    root_old = "IC2-New-Era-Server-1.20.1-r70-Voice/"
    root_new = "IC2-New-Era-Server-1.20.1-r72-Voice/"
    source_root = root_old
    quest_prefix = "config/ftbquests/quests/"
    overlay_paths = {quest_prefix + p.removeprefix("config/ftbquests/quests/"): data
                     for p, data in overlay.items() if p.startswith("config/ftbquests/quests/")}
    knowledge_key = "config/ic2newera/quest_knowledge.json"
    jei = next(x for x in verified["compatibilityHotfixes"] if x.get("filename") == "jei-1.20.1-forge-15.74.0.234.jar")
    own_data = (WORK / "native" / OWN).read_bytes()
    jei_data = (PACK / "inputs/client/mods" / jei["filename"]).read_bytes()
    server_manifest = None
    artifact_audit = None
    with zipfile.ZipFile(SERVER_BASE) as source:
        manifest_name = source_root + "server-manifest.json"
        server_manifest = json.loads(source.read(manifest_name))
        server_manifest.update(package=root_new.rstrip("/"), releaseRevision="r72")
        chapter_count = len(list((WORK / "handbook-overlay/config/ftbquests/quests/chapters").glob("*.snbt")))
        knowledge = json.loads(overlay["config/ic2newera/quest_knowledge.json"])
        server_manifest["featureSummary"] = (
            f"r72 standalone first-party jar, photography survey and FTB quest handbook; {chapter_count} chapters, "
            f"{len(knowledge.get('quests', []))} indexed quests; r70 mod baseline, Forge 47.4.28, fixed voice."
        )
        for mod in server_manifest["mods"]:
            if mod.get("modId") == "ic2newera":
                mod.update(sizeBytes=len(own_data), sha256=sha(own_data), file=OWN)
            elif mod.get("modId") == "jei":
                mod.update(file=jei["filename"], sizeBytes=jei["fileSize"], sha256=jei["sha256"],
                           sha1=jei["sha1"], sha512=jei["sha512"], source=jei["source"]["downloadUrl"])
        new_args = b"-Xms2G\n-Xmx9G\n--add-modules=jdk.httpserver\n-javaagent:ic2newera-fixed-voice.jar\n"
        readme_zh = ("# IC2：新纪元 r72 专用服务器\n\nMinecraft 1.20.1 / Forge 47.4.28 / Java 17。\n"
                     "包含r70活动服务端模组、FTB Teams/Quests、Create、IC2、匠魂、Spawn及固定语音。\n"
                     f"包含{chapter_count}章、{len(knowledge.get('quests', []))}项任务的FTB手册；使用r72新纪元JAR与助手资料，启动堆内存上限9GiB。\n"
                     "Start.bat/Start.sh 分别用于Windows/Linux。\n").encode("utf-8")
        readme_en = ("# IC2: New Era r72 dedicated server\n\nMinecraft 1.20.1 / Forge 47.4.28 / Java 17.\n"
                     f"Retains the r70 active server mods, FTB Teams/Quests, Create, IC2, Tinkers, Spawn and fixed voice.\n"
                     f"The handbook contains {chapter_count} chapters and {len(knowledge.get('quests', []))} indexed quests.\n"
                     "Includes the r72 New Era JAR and assistant index; server heap is capped at 9 GiB.\n"
                     "Use Start.bat on Windows and Start.sh on Linux.\n").encode("utf-8")
        playtest_status = ("r72 package status: complete dual-platform server archive is built and statically checked.\n"
                           "No game is started by the packaging script. Actual gameplay acceptance is tracked separately.\n").encode("utf-8")
        manifest_file_updates = {
            "mods/" + OWN: own_data,
            "mods/jei-1201-15.56.0.205.jar": None,
            "mods/" + jei["filename"]: jei_data,
            "user_jvm_args.txt": new_args,
            "README.md": readme_en,
            "README.zh-CN.md": readme_zh,
            "PLAYTEST-STATUS.txt": playtest_status,
            **overlay_paths,
            knowledge_key: overlay[knowledge_key],
        }
        file_rows = {row["path"]: row for row in server_manifest.get("files", [])}
        for path, data in manifest_file_updates.items():
            if data is None:
                file_rows.pop(path, None)
            else:
                row = file_rows.get(path, {"path": path})
                row.update(sizeBytes=len(data), sha256=sha(data))
                file_rows[path] = row
        server_manifest["files"] = [file_rows[k] for k in sorted(file_rows)]
        audit_name = source_root + "artifact-audit.json"
        artifact_audit = json.loads(source.read(audit_name))
        artifact_audit.update(revision="r72", newEraSha256=sha(own_data), newEraSizeBytes=len(own_data))
        manifest_bytes = (json.dumps(server_manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        artifact_audit["serverManifestSha256"] = sha(manifest_bytes)
        artifact_audit["questOverlay"] = {path: {"sha256": sha(data), "sizeBytes": len(data)} for path, data in overlay.items()}
        changed = {**manifest_file_updates,
                   "server-manifest.json": manifest_bytes,
                   "artifact-audit.json": (json.dumps(artifact_audit, ensure_ascii=False, indent=2) + "\n").encode("utf-8")}
        written: set[str] = set()
        names_seen: set[str] = set()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        if out_path.exists():
            raise FileExistsError(f"Refusing to overwrite existing r72 artifact: {out_path}")
        with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as dest:
            for info in source.infolist():
                if info.is_dir():
                    continue
                if info.filename.startswith(source_root):
                    rel = info.filename[len(source_root):]
                    target_name = root_new + rel
                else:
                    target_name = info.filename
                    rel = None
                if rel in changed:
                    data = changed[rel]
                    if data is None:
                        continue
                    dest.writestr(target_name, data)
                    written.add(rel)
                elif rel in overlay_paths:
                    dest.writestr(target_name, overlay_paths[rel])
                    written.add(rel)
                else:
                    target_info = zipfile.ZipInfo(target_name, info.date_time)
                    target_info.compress_type = info.compress_type
                    target_info.comment = info.comment
                    target_info.extra = info.extra
                    target_info.create_system = info.create_system
                    target_info.external_attr = info.external_attr
                    target_info.internal_attr = info.internal_attr
                    target_info.flag_bits = info.flag_bits
                    with source.open(info, "r") as src, dest.open(target_info, "w") as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)
                names_seen.add(target_name)
            for rel, data in changed.items():
                if data is not None and rel not in written and rel != knowledge_key:
                    dest.writestr(root_new + rel, data)
            if knowledge_key not in written:
                dest.writestr(root_new + knowledge_key, overlay[knowledge_key])
    return {"path": str(out_path), "sha256": sha_file(out_path), "sizeBytes": out_path.stat().st_size}


def verify_full_archives(client_path: Path, server_path: Path, overlay: dict[str, bytes]) -> dict:
    with zipfile.ZipFile(client_path) as client, zipfile.ZipFile(server_path) as server:
        client_entry_count = len(client.namelist())
        bad_client = client.testzip()
        bad_server = server.testzip()
        if bad_client or bad_server:
            raise RuntimeError(f"Archive CRC failure: client={bad_client} server={bad_server}")
        if len(client.namelist()) != len(set(client.namelist())) or len(server.namelist()) != len(set(server.namelist())):
            raise RuntimeError("Duplicate member paths found in a release archive")
        idx = json.loads(client.read("modrinth.index.json"))
        downloads = [f for f in idx["files"] if f["path"].lower().endswith(".jar")]
        client_overrides = [n for n in client.namelist() if n.startswith("overrides/mods/") and n.lower().endswith(".jar")]
        if len(downloads) != 43 or len(client_overrides) != 3:
            raise RuntimeError(f"Windows mrpack target must have 43 downloaded JARs + 3 override JARs, got {len(downloads)} + {len(client_overrides)}")
        if any(any(hint in Path(f["path"]).name.lower() for hint in GEAR_FEAST_NAME_HINTS) for f in idx["files"]):
            raise RuntimeError("A Gear Feast-specific JAR entered the r72 Windows mod list")
        if any("jei-1201-15.56.0.205.jar" in f["path"] for f in idx["files"]):
            raise RuntimeError("Old JEI 15.56 remains in the Windows download manifest")
        new_jei_items = [f for f in idx["files"] if Path(f["path"]).name.lower() == "jei-1.20.1-forge-15.74.0.234.jar"]
        if len(new_jei_items) != 1:
            raise RuntimeError("Windows manifest must include exactly one JEI 15.74.0.234 download")
        client_own_members = [n for n in client.namelist() if n.lower().endswith("/mods/" + OWN.lower())]
        if len(client_own_members) != 1:
            raise RuntimeError("Windows pack must contain exactly one active New Era JAR")
        for file in downloads:
            staged = PACK / "inputs/client/mods" / Path(file["path"]).name
            if not staged.is_file():
                raise FileNotFoundError(f"Missing actual client JAR source: {staged}")
            if hashlib.sha1(staged.read_bytes()).hexdigest().lower() != file["hashes"]["sha1"].lower():
                raise RuntimeError("Modrinth SHA-1 mismatch for " + file["path"])
            if hashlib.sha512(staged.read_bytes()).hexdigest().lower() != file["hashes"]["sha512"].lower():
                raise RuntimeError("Modrinth SHA-512 mismatch for " + file["path"])
        target_manifest = json.loads((PACK / "target-manifest-client-jars.json").read_text(encoding="utf-8"))
        client_hashes = {Path(x["path"]).name.lower(): x["sha256"] for x in target_manifest["items"]}
        for name in client_overrides:
            if sha(client.read(name)) != client_hashes[Path(name).name.lower()]:
                raise RuntimeError("Windows mrpack override hash mismatch: " + name)
        new_options = client.read("overrides/options.txt").decode("utf-8")
        required_keys = {
            "key_key.ic2newera.phone_camera_view:key.keyboard.f6",
            "key_key.ic2newera.phone_camera_capture:key.keyboard.f8",
            "key_key.ic2newera.phone_camera_upload:key.keyboard.f9",
            "key_key.ic2newera.phone_gallery:key.keyboard.insert",
            "key_key.ic2newera.phone_camera_exit:key.keyboard.backspace",
        }
        if not required_keys.issubset(set(new_options.splitlines())):
            raise RuntimeError("Windows options.txt does not contain the agreed conflict-free phone keys")
        resourcepack = "overrides/resourcepacks/NewEra-CMM-Model-Fix.zip"
        if sha(client.read(resourcepack)) != "B104D293F050FBCFFCEE84C505ED38ACD04509704ADCB813797AB01CA05D60E1":
            raise RuntimeError("Windows CMM model fix resource pack hash mismatch")
        root = "IC2-New-Era-Server-1.20.1-r72-Voice/"
        server_manifest = json.loads(server.read(root + "server-manifest.json"))
        if server_manifest.get("releaseRevision") != "r72" or len(server_manifest.get("mods", [])) != 35:
            raise RuntimeError("Server manifest is not the 35-JAR r72 target")
        server_names_all = server.namelist()
        own_server_members = [n for n in server_names_all if n.lower().endswith("/mods/" + OWN.lower())]
        jei_server_members = [n for n in server_names_all if n.lower().endswith("/mods/jei-1.20.1-forge-15.74.0.234.jar")]
        if len(own_server_members) != 1 or len(jei_server_members) != 1:
            raise RuntimeError("Server archive must contain exactly one New Era JAR and one JEI 15.74 JAR")
        if any("/mods/jei-1201-15.56.0.205.jar" in n.lower() for n in server_names_all):
            raise RuntimeError("Old JEI 15.56 JAR remains in server archive")
        if sha(client.read(client_own_members[0])) != sha(server.read(own_server_members[0])):
            raise RuntimeError("Client/server New Era JAR hashes differ")
        target_audit = json.loads((PACK / "target-manifest-audit.json").read_text(encoding="utf-8"))
        if target_audit.get("targetServerMissingMandatoryDependencies") or target_audit.get("targetSharedJarSha256Mismatches"):
            raise RuntimeError("Target JAR dependency/hash audit contains errors")
        for entry in server_manifest.get("files", []):
            member = root + entry["path"]
            data = server.read(member)
            if len(data) != entry["sizeBytes"] or sha(data) != entry["sha256"].upper():
                raise RuntimeError("Server file manifest mismatch: " + entry["path"])
        if any("jei-1201-15.56.0.205.jar" in row.get("path", "") for row in server_manifest.get("files", [])):
            raise RuntimeError("Old JEI 15.56 remains in server file manifest")
        server_hashes = {}
        for mod in server_manifest["mods"]:
            name = mod["file"]
            data = server.read(root + "mods/" + name)
            digest = sha(data)
            if digest != mod["sha256"].upper() or len(data) != mod["sizeBytes"]:
                raise RuntimeError("Server manifest JAR mismatch: " + name)
            server_hashes[name.lower()] = digest
        client_common = set(client_hashes) & set(server_hashes)
        mismatch = {name: (client_hashes[name], server_hashes[name]) for name in client_common
                    if client_hashes[name] != server_hashes[name]}
        if mismatch:
            raise RuntimeError(f"Client/server shared mod hash mismatch: {mismatch}")
        if len(client_hashes) != 46 or len(server_hashes) != 35 or len(client_common) != 35:
            raise RuntimeError("Expected 46 Windows and 35 server JARs with 35 matching shared files")
        for rel, data in overlay.items():
            client_member = "overrides/" + rel
            server_member = root + rel
            if client.read(client_member) != data or server.read(server_member) != data:
                raise RuntimeError("Quest/assistant overlay differs between client and server: " + rel)
        args = server.read(root + "user_jvm_args.txt").decode("utf-8")
        if "-Xmx9G" not in args or "-javaagent:ic2newera-fixed-voice.jar" not in args:
            raise RuntimeError("Server must retain fixed voice and 9GiB heap configuration")
        for launcher in ("Start.bat", "Start.sh", "start-server.sh"):
            if root + launcher not in server.namelist():
                raise RuntimeError("Missing cross-platform server launcher: " + launcher)
        for launcher in ("Start.sh", "start-server.sh"):
            mode = server.getinfo(root + launcher).external_attr >> 16
            if not mode & 0o111:
                raise RuntimeError("Linux server launcher lost executable permissions: " + launcher)
    return {"clientEntries": client_entry_count,
            "clientJarCount": 46, "serverJarCount": 35, "sharedJarCount": 35,
            "questOverlayFiles": len(overlay), "clientServerHashMismatches": 0,
            "archiveCrc": "pass", "dependenciesMissing": 0}


@contextlib.contextmanager
def r72_release_lock():
    """Serialize release promotion while keeping the lock file outside the desktop workspace."""
    target = os.path.normcase(os.path.abspath(str(DIST)))
    target_digest = hashlib.sha256(target.encode("utf-8")).hexdigest()[:16]
    lock_name = f"ic2newera-r72-{target_digest}.lock"
    lock_path = Path(tempfile.gettempdir()) / lock_name
    with lock_path.open("a+b") as lock_file:
        if os.name == "nt":
            import msvcrt
            lock_file.seek(0, os.SEEK_END)
            if lock_file.tell() == 0:
                lock_file.write(b"\0")
                lock_file.flush()
            lock_file.seek(0)
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_LOCK, 1)
            try:
                yield
            finally:
                lock_file.seek(0)
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def package_release() -> None:
    with r72_release_lock():
        package_release_locked()


def package_release_locked() -> None:
    native = WORK / "native" / OWN
    native_manifest = native.with_suffix(".manifest.json")
    verified_path = ROOT / "build-isolated/player-packaging/20261113-delight-joint/verified-release.json"
    if not native.is_file() or not native_manifest.is_file() or not verified_path.is_file():
        raise FileNotFoundError("r72 native and approved JEI source receipts must be present")
    receipts = json.loads(native_manifest.read_text(encoding="utf-8"))
    if sha_file(native) != str(receipts["result_sha256"]).upper():
        raise RuntimeError("r72 native JAR hash does not match its build receipt")
    verified = json.loads(verified_path.read_text(encoding="utf-8"))
    overlay = quest_payload()
    client_path = DIST / "IC2-New-Era-Windows-1.20.1-r72-Grove-CMM.mrpack"
    server_path = DIST / "IC2-New-Era-Server-1.20.1-r72-Voice.zip"
    jar_path = DIST / OWN
    DIST.mkdir(parents=True, exist_ok=True)
    final_files = (
        (client_path.name, client_path),
        (server_path.name, server_path),
        (OWN, jar_path),
        ("SHA256SUMS.txt", DIST / "SHA256SUMS.txt"),
        ("release-manifest.json", DIST / "release-manifest.json"),
    )
    for _, final_path in final_files:
        if final_path.exists() and not final_path.is_file():
            raise RuntimeError(f"Refusing to replace non-file r72 output: {final_path}")

    temp_base = Path(tempfile.gettempdir())
    if os.stat(temp_base).st_dev != os.stat(DIST).st_dev:
        temp_base = DIST
    temp_root = Path(tempfile.mkdtemp(prefix=".r72-release-tmp-", dir=temp_base))
    output_root, backup_root = temp_root / "outputs", temp_root / "backup"
    output_root.mkdir()
    backup_root.mkdir()
    client_tmp = output_root / client_path.name
    server_tmp = output_root / server_path.name
    jar_tmp = output_root / OWN
    staged_paths = {client_path.name: client_tmp, server_path.name: server_tmp, OWN: jar_tmp,
                    "SHA256SUMS.txt": output_root / "SHA256SUMS.txt",
                    "release-manifest.json": output_root / "release-manifest.json"}
    promoted: list[Path] = []
    backed_up: list[tuple[Path, Path]] = []
    try:
        client = package_client(overlay, client_tmp, verified)
        server = package_server(overlay, server_tmp, verified)
        checks = verify_full_archives(client_tmp, server_tmp, overlay)
        shutil.copy2(native, jar_tmp)
        if sha_file(jar_tmp) != sha_file(native):
            raise RuntimeError("Standalone first-party JAR copy mismatch")

        client.update(path=client_path.name, sha256=sha_file(client_tmp), sizeBytes=client_tmp.stat().st_size)
        server.update(path=server_path.name, sha256=sha_file(server_tmp), sizeBytes=server_tmp.stat().st_size)
        jar = {"path": jar_path.name, "sha256": sha_file(jar_tmp), "sizeBytes": jar_tmp.stat().st_size}
        sums = (f"{client['sha256']}  {client_path.name}\n{server['sha256']}  {server_path.name}\n"
                f"{jar['sha256']}  {jar_path.name}\n")
        staged_paths["SHA256SUMS.txt"].write_text(sums, encoding="ascii")
        write_json(staged_paths["release-manifest.json"], {
            "revision": "r72", "minecraft": "1.20.1", "forge": "47.4.28", "java": "17",
            "client": {**client, "path": client_path.name},
            "server": {**server, "path": server_path.name},
            "standaloneFirstPartyJar": {**jar, "path": jar_path.name},
            "clientJarCount": 46, "serverJarCount": 35,
            "newEraJarSha256": sha_file(native), "questOverlayCount": len(overlay),
            "staticChecks": checks,
            "sourceBaseline": str(CLIENT_BASE.relative_to(ROOT)),
            "serverBaseline": str(SERVER_BASE.relative_to(ROOT)),
            "gearsFeastExclusiveModsIncluded": False,
        })

        # Validate the complete five-file set before touching any previous release.
        sums_lines = staged_paths["SHA256SUMS.txt"].read_text(encoding="ascii").splitlines()
        expected_sums = {line.split()[1]: line.split()[0] for line in sums_lines}
        for filename in (client_path.name, server_path.name, OWN):
            if expected_sums.get(filename) != sha_file(staged_paths[filename]):
                raise RuntimeError(f"Staged checksum manifest mismatch: {filename}")
        manifest = json.loads(staged_paths["release-manifest.json"].read_text(encoding="utf-8"))
        if (manifest["newEraJarSha256"] != sha_file(jar_tmp)
                or manifest["client"]["downloadFileCount"] != 47
                or manifest["client"]["downloadModCount"] != 43):
            raise RuntimeError("Staged release manifest failed validation")
        if any(not staged_paths[name].is_file() for name, _ in final_files):
            raise RuntimeError("Staged release is missing one or more of its five fixed output files")

        # Prefer hard-link backups, so prior files remain visible until each new
        # file atomically replaces its matching target.
        hardlink_backup = True
        try:
            for filename, final_path in final_files:
                if final_path.exists():
                    backup_path = backup_root / filename
                    os.link(final_path, backup_path)
                    backed_up.append((final_path, backup_path))
        except OSError:
            hardlink_backup = False
            for _, backup_path in backed_up:
                backup_path.unlink(missing_ok=True)
            backed_up.clear()

        if hardlink_backup:
            for filename, final_path in final_files:
                os.replace(staged_paths[filename], final_path)
                promoted.append(final_path)
        else:
            # If hard links are unavailable, move and replace one file at a time.
            for filename, final_path in final_files:
                backup_path = backup_root / filename
                if final_path.exists():
                    os.replace(final_path, backup_path)
                    backed_up.append((final_path, backup_path))
                os.replace(staged_paths[filename], final_path)
                promoted.append(final_path)
    except BaseException:
        rollback_errors = []
        for final_path in reversed(promoted):
            try:
                final_path.unlink(missing_ok=True)
            except OSError as exc:
                rollback_errors.append((final_path, exc))
        for final_path, backup_path in reversed(backed_up):
            if backup_path.exists():
                try:
                    if final_path.exists():
                        final_path.unlink()
                    os.replace(backup_path, final_path)
                except OSError as exc:
                    rollback_errors.append((backup_path, exc))
        if rollback_errors:
            print(f"Release rollback needs manual recovery; prior files are preserved in {backup_root}")
            raise
        shutil.rmtree(temp_root, ignore_errors=True)
        raise
    for _, backup_path in backed_up:
        backup_path.unlink(missing_ok=True)
    shutil.rmtree(temp_root, ignore_errors=True)
    print(f"Packaged r72 client and server under {DIST}")


def materialize_stage(index: dict, server_mods: list[dict]) -> None:
    safe_mkdir(PACK)
    src_client = PACK / "sources"
    safe_mkdir(src_client)
    # Preserve the complete r70 Modrinth recipe for instant client installation.
    target_mrpack = src_client / CLIENT_BASE.name
    if (not target_mrpack.exists() or sha_file(target_mrpack) != sha_file(CLIENT_BASE)
            or os.stat(target_mrpack).st_ino != os.stat(CLIENT_BASE).st_ino):
        target_mrpack.unlink(missing_ok=True)
        os.link(CLIENT_BASE, target_mrpack)
    # Real path + hash for every server-active JAR; this is also the first startup input.
    mods_dir = PACK / "inputs/r71-debug/mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    profile_mods = PROFILE_MODS
    with zipfile.ZipFile(SERVER_BASE) as z:
        for item in server_mods:
            out = mods_dir / Path(item["path"]).name
            candidates = list(profile_mods.glob(out.name)) if profile_mods.is_dir() else []
            source = next((p for p in candidates if sha_file(p) == item["sha256"]), None)
            if source:
                if not out.exists() or sha_file(out) != item["sha256"] or os.stat(out).st_ino != os.stat(source).st_ino:
                    out.unlink(missing_ok=True)
                    os.link(source, out)
            elif not out.exists() or sha_file(out) != item["sha256"]:
                out.unlink(missing_ok=True)
                out.write_bytes(z.read(item["sourceArchive"].split("#", 1)[1]))
    client_files = []
    for item in index["files"]:
        name = Path(item["path"]).name.lower()
        modid_hint = Path(item["path"]).stem.lower()
        if any(h in name or h in modid_hint for h in GEAR_FEAST_NAME_HINTS):
            continue
        client_files.append({"path": item["path"], "sha1": item["hashes"].get("sha1"),
                             "sha512": item["hashes"].get("sha512"), "fileSize": item.get("fileSize"),
                             "env": item.get("env"), "downloads": item.get("downloads", [])})
    jei_index = json.loads((ROOT / "build-isolated/player-packaging/20261113-delight-joint/verified-release.json").read_text(encoding="utf-8"))
    jei = next(x for x in jei_index["compatibilityHotfixes"] if x.get("filename") == "jei-1.20.1-forge-15.74.0.234.jar")
    client_files = [x for x in client_files if "jei-1201-15.56.0.205.jar" not in x["path"].lower()]
    client_files.append({"path": "mods/" + jei["filename"],
                         "hashes": {"sha1": jei["sha1"], "sha512": jei["sha512"]},
                         "env": {"client": "required", "server": "required"},
                         "downloads": [jei["source"]["downloadUrl"]], "fileSize": jei["fileSize"],
                         "source": "r71 approved Modrinth release"})
    overrides = archive_mod_overrides()
    client_manifest = {
        "revision": "r72-target-client-bootstrap", "baseline": str(CLIENT_BASE.relative_to(ROOT)),
        "baselineSha256": sha_file(CLIENT_BASE), "minecraft": index.get("dependencies", {}).get("minecraft"),
        "forge": index.get("dependencies", {}).get("forge"), "mods": client_files,
        "overrideMods": overrides,
        "firstPartyJar": {"expectedPath": "mods/" + OWN, "status": "awaiting native output"},
    }
    server_manifest = {
        "revision": "r72-target-server-bootstrap", "baseline": str(SERVER_BASE.relative_to(ROOT)),
        "baselineSha256": sha_file(SERVER_BASE), "minecraft": "1.20.1", "forge": "47.4.28",
        "mods": [{**m, "stagedPath": (mods_dir / Path(m["path"]).name).relative_to(ROOT).as_posix(),
                  **jar_mod_metadata(mods_dir / Path(m["path"]).name)} for m in server_mods],
        "firstPartyJar": {"expectedPath": "mods/" + OWN, "status": "awaiting W2/native output"},
    }
    client_hashes = {Path(f["path"]).name.lower(): f.get("sha1") for f in client_files}
    client_hashes.update({Path(x["path"]).name.lower(): x["sha256"] for x in archive_mod_overrides()})
    server_names = {Path(m["path"]).name.lower() for m in server_mods}
    client_server_overlap = sorted(name for name in server_names if name in client_hashes)
    declared_ids: set[str] = set()
    dependencies: list[dict] = []

    def inspect_metadata(raw: bytes, origin: str) -> None:
        try:
            metadata = tomllib.loads(raw.decode("utf-8"))
        except Exception as exc:
            dependencies.append({"metadataParseError": origin, "error": str(exc)})
            return
        local_ids = {str(m.get("modId")) for m in metadata.get("mods", []) if m.get("modId")}
        declared_ids.update(local_ids)
        for owner, reqs in metadata.get("dependencies", {}).items():
            for req in reqs:
                if req.get("mandatory") is True and req.get("modId"):
                    dependencies.append({"from": owner, "requires": req["modId"], "origin": origin})

    for item in server_mods:
        staged = mods_dir / Path(item["path"]).name
        with zipfile.ZipFile(staged) as jar:
            meta_name = next((n for n in jar.namelist() if n.upper() == "META-INF/MODS.TOML"), None)
            if meta_name:
                inspect_metadata(jar.read(meta_name), f"{item['path']}!/{meta_name}")
            jarjar = next((n for n in jar.namelist() if n.upper() == "META-INF/JARJAR/METADATA.JSON"), None)
            if jarjar:
                try:
                    payload = json.loads(jar.read(jarjar))
                    for embedded in payload.get("jars", []):
                        embedded_name = embedded.get("path")
                        if not embedded_name:
                            continue
                        try:
                            with zipfile.ZipFile(io.BytesIO(jar.read(embedded_name))) as nested:
                                nested_meta = next((n for n in nested.namelist() if n.upper() == "META-INF/MODS.TOML"), None)
                                if nested_meta:
                                    inspect_metadata(nested.read(nested_meta), f"{item['path']}!/{embedded_name}!/{nested_meta}")
                        except Exception as exc:
                            dependencies.append({"nestedJarReadError": embedded_name, "error": str(exc)})
                except Exception as exc:
                    dependencies.append({"jarJarMetadataParseError": item["path"], "error": str(exc)})
    available = declared_ids | {"minecraft", "forge", "javafml", "lowcodefml", "fml"}
    missing_hard = sorted({d["requires"] for d in dependencies if d.get("requires") and d["requires"] not in available})
    client_resource_entries = len(zipfile.ZipFile(target_mrpack).namelist())
    write_json(PACK / "target-manifest-client.json", client_manifest)
    write_json(PACK / "target-manifest-server.json", server_manifest)
    write_json(PACK / "target-manifest-audit.json", {
        "baselineR70WindowsFileCount": len(index["files"]), "targetWindowsManifestFileCount": len(client_files),
        "baselineR70ServerModJarCount": len(server_mods),
        "clientManifestServerOverlapByFilename": client_server_overlap,
        "serverOnly": sorted(server_names - set(client_hashes)),
        "clientOnly": sorted(set(client_hashes) - server_names),
        "declaredServerModIds": sorted(declared_ids),
        "mandatoryDependenciesMissingFromServer": missing_hard,
        "mandatoryDependencies": dependencies,
        "r70WindowsMrpackEntryCount": client_resource_entries,
        "gearFeastFilenameExclusions": [f["path"] for f in index["files"]
                                           if any(h in Path(f["path"]).name.lower() for h in GEAR_FEAST_NAME_HINTS)],
        "sourceArchivesReadOnly": True,
    })
    # A launch input is a staging directory only; no Java process is started here.
    (PACK / "inputs/r71-debug/README.txt").write_text(
        "临时启动排错输入（仅文件，不会自动启动游戏）\n"
        "Forge 1.20.1 / 47.4.28。mods/ 为r70活动服务器模组集合，已更新JEI 15.74.0.234与r72新纪元JAR。\n"
        "本目录用于临时排错，不含世界、完整Forge运行时或用户配置；任务书待handbook-overlay最终确认后再并入。\n"
        "启动参数建议：-Xms2G -Xmx9G；本目录没有 world、账号、密钥或用户配置。\n", encoding="utf-8")
    (PACK / "inputs/client/README.txt").write_text(
        "r72 Windows 客户端 JAR 安装输入（共46个）\n"
        "mods/ 中所有文件均有逐项 SHA256 清单；共享文件优先使用硬链接以减少磁盘占用。\n"
        "r70 Windows原清单与3个活动override JAR保留；JEI更新到已批准的15.74.0.234。\n"
        "新纪元JAR是r72 native最终输出。Windows专用 NewEra-CMM-Model-Fix.zip 位于 pack/overrides/resourcepacks。\n"
        "不包含Gear Feast专属模组、Just Enough Threads、TaCZ addon/JS或玩家存档/账户配置。\n",
        encoding="utf-8")
    with zipfile.ZipFile(CLIENT_BASE) as source:
        base_options = source.read("overrides/options.txt")
    (PACK / "inputs/client/options.txt").write_bytes(options_with_model_fix(base_options))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", action="store_true", help="create/update the isolated bootstrap manifests and debug inputs")
    ap.add_argument("--release", action="store_true", help="build complete client and dual-platform server archives")
    args = ap.parse_args()
    index, mods = validate_inputs()
    if args.release:
        package_release()
    elif args.stage:
        materialize_stage(index, mods)
        client = stage_client_mods(index)
        native = integrate_first_party()
        model_fix = stage_client_model_fix()
        print(f"Prepared r72 bootstrap in {PACK}")
        print(f"Prepared {client['count']} client JARs at {client['directory']}")
        if native:
            print(f"Integrated first-party JAR {native['sha256']} into client and debug inputs")
        print(f"Added Windows CMM resource fix {model_fix['sha256']}")
    else:
        ap.error("choose --stage or --release")


if __name__ == "__main__":
    main()
