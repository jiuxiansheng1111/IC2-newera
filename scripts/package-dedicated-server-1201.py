"""Package a local Forge 1.20.1 dedicated-server distribution.

This deliberately does not launch Forge or inspect the prepared server's config,
logs, mods directory, worlds, or player data. Third-party binaries stay local and
are included only to migrate the user's own server; they are not a GitHub upload.
"""
from __future__ import annotations

import argparse
from functools import cmp_to_key
import hashlib
import io
import json
import os
import re
import tarfile
import tomllib
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
CORE_LOCK = ROOT / "build-isolated/migration-1201/dependencies/runtime-lock-1201.json"
ENHANCEMENTS_LOCK = ROOT / "scripts/player-enhancements-lock-1201.json"
FORGE_ROOT_DEFAULT = ROOT / "build-isolated/server-packaging/20261028-r47-server/prepared"
TEMPLATE = ROOT / "scripts/server-pack-1201"
BLUEPRINTS = ROOT / "build-isolated/migration-1201/newera/art/robot-blueprints"
NOTICES = ROOT / "scripts/player-pack/THIRD-PARTY-NOTICES.txt"
PROJECTE_BLACKLIST = ROOT / "build-isolated/migration-1201/newera/scripts/projecte-emc-1201-blacklist.json"
OUT_DIR = ROOT / "build-isolated/server-packaging/20261028-r48-server"
ARCHIVE_NAME = "IC2-New-Era-Server-1.20.1-r48.tar.gz"
TOP = ARCHIVE_NAME.removesuffix(".tar.gz")
ALLOWED_TEMPLATE_SUFFIXES = {".sh", ".bat", ".txt", ".md", ".properties"}
ALLOWED_TEMPLATE_PATHS = {"defaultconfigs/ftbultimine/ftbultimine-server.snbt"}
PLATFORM_MODS = {"minecraft", "forge", "javafml", "lowcodefml"}
GRAPHICS_MODS = {"embeddium", "sodium", "oculus", "iris", "irisflw",
                 "entity_model_features", "entity_texture_features", "fusion"}


def fail(message: str) -> None:
    raise ValueError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest().upper()


def safe_name(value: str) -> str:
    if (not value or "\\" in value or ":" in value or value.startswith("/")
            or re.match(r"^[A-Za-z]:", value)
            or any(part in ("", ".", "..") for part in value.split("/"))):
        fail(f"Unsafe archive path: {value!r}")
    p = PurePosixPath(value)
    if p.is_absolute() or ".." in p.parts:
        fail(f"Unsafe archive path: {value!r}")
    return p.as_posix()


def add_bytes(files: dict[str, bytes], name: str, data: bytes) -> None:
    name = safe_name(name)
    if name in files:
        fail(f"Duplicate archive path: {name}")
    files[name] = data


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_locked_file(row: dict, root: Path, label: str) -> tuple[Path, bytes]:
    filename = row.get("file")
    if not isinstance(filename, str) or Path(filename).name != filename:
        fail(f"{label}: invalid file field {filename!r}")
    path = root / filename
    if not path.is_file() or path.is_symlink():
        fail(f"{label}: missing/non-regular dependency {path}")
    data = path.read_bytes()
    expected_size = int(row.get("sizeBytes", -1))
    expected_hash = str(row.get("sha256", "")).upper()
    if len(data) != expected_size or sha256_bytes(data) != expected_hash:
        fail(f"{label}: lock size/hash mismatch for {filename}")
    return path, data


def archive_path_for_mod(filename: str) -> str:
    return safe_name("mods/" + filename)


def manifest_attributes(jar: zipfile.ZipFile) -> dict[str, str]:
    try:
        raw = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    except KeyError:
        return {}
    unfolded: list[str] = []
    for line in raw.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if line.startswith(" ") and unfolded:
            unfolded[-1] += line[1:]
        elif line:
            unfolded.append(line)
    attrs = {}
    for line in unfolded:
        if ":" in line:
            key, value = line.split(":", 1)
            attrs[key.strip().lower()] = value.strip()
    return attrs


def metadata_from_jar(data: bytes, source: str) -> tuple[list[dict], list[dict]]:
    """Read metadata for one JAR only; JarJar modules are resolved separately."""
    mods: list[dict] = []
    dependencies: list[dict] = []
    try:
        jar = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as exc:
        fail(f"Invalid mod JAR {source}: {exc}")
    with jar:
        manifest = manifest_attributes(jar)
        for meta_path in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
            try:
                raw = jar.read(meta_path)
            except KeyError:
                continue
            try:
                meta = tomllib.loads(raw.decode("utf-8-sig"))
            except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
                fail(f"Malformed {meta_path} in {source}: {exc}")
            for item in meta.get("mods", []):
                modid = str(item.get("modId", "")).strip()
                if not modid:
                    continue
                version = str(item.get("version", "unknown")).strip()
                if version == "${file.jarVersion}":
                    version = (manifest.get("implementation-version")
                               or manifest.get("bundle-version")
                               or manifest.get("specification-version"))
                    if not version:
                        fail(f"{source} uses ${{file.jarVersion}} but has no manifest version")
                mods.append({"modId": modid, "version": version,
                             "jar": source, "displayName": item.get("displayName")})
                for dep in meta.get("dependencies", {}).get(modid, []):
                    if not dep.get("mandatory", False):
                        continue
                    side = str(dep.get("side", "BOTH")).upper()
                    if side == "CLIENT":
                        continue
                    dependencies.append({"from": modid, "requires": str(dep.get("modId", "")),
                                         "versionRange": str(dep.get("versionRange", "*")),
                                         "side": side, "jar": source})
    return mods, dependencies


def collect_jarjar_candidates(data: bytes, owner: str, source: str,
                              candidates: dict[tuple[str, str], list[dict]],
                              visited: set[tuple[str, str]], depth: int = 0) -> None:
    """Validate every JarJar metadata path and recursively inspect referenced JARs."""
    if depth > 12:
        fail(f"JarJar nesting too deep in {source}")
    content_hash = sha256_bytes(data)
    visit_key = (owner, content_hash)
    if visit_key in visited:
        return
    visited.add(visit_key)
    try:
        jar = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as exc:
        fail(f"Invalid embedded JAR {source}: {exc}")
    with jar:
        try:
            raw = jar.read("META-INF/jarjar/metadata.json")
        except KeyError:
            return
        try:
            metadata = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            fail(f"Malformed JarJar metadata in {source}: {exc}")
        entries = metadata.get("jars")
        if not isinstance(entries, list):
            fail(f"JarJar metadata has no jars array: {source}")
        for entry in entries:
            ident = entry.get("identifier", {})
            version = entry.get("version", {})
            group, artifact = ident.get("group"), ident.get("artifact")
            artifact_version = version.get("artifactVersion")
            version_range = version.get("range", "*")
            nested_path = entry.get("path")
            if not all(isinstance(v, str) and v for v in (group, artifact, artifact_version, nested_path)):
                fail(f"Incomplete JarJar coordinate/path in {source}: {entry!r}")
            safe_name(nested_path)
            member = jar.getinfo(nested_path) if nested_path in jar.namelist() else None
            if member is None or member.is_dir():
                fail(f"JarJar reference path is missing: {source}!/{nested_path}")
            if not version_satisfies(artifact_version, str(version_range)):
                fail(f"JarJar candidate {group}:{artifact}:{artifact_version} violates {version_range} in {source}")
            child_data = jar.read(nested_path)
            record = {"group": group, "artifact": artifact, "version": artifact_version,
                      "versionRange": str(version_range), "path": nested_path,
                      "source": source, "owner": owner,
                      "sha256": sha256_bytes(child_data), "data": child_data}
            candidates.setdefault((group, artifact), []).append(record)
            collect_jarjar_candidates(child_data, owner, f"{source}!/{nested_path}",
                                      candidates, visited, depth + 1)


def resolve_jarjar_candidates(candidates: dict[tuple[str, str], list[dict]]) -> list[dict]:
    """Choose one highest candidate per Maven coordinate satisfying all declared ranges."""
    selected = []
    for coordinate, entries in sorted(candidates.items()):
        versions: dict[str, list[dict]] = {}
        ranges = sorted({item["versionRange"] for item in entries})
        for entry in entries:
            versions.setdefault(entry["version"], []).append(entry)
        for version, same_version in versions.items():
            hashes = {item["sha256"] for item in same_version}
            if len(hashes) > 1:
                fail(f"Ambiguous JarJar content for {coordinate[0]}:{coordinate[1]}:{version}")
        viable = []
        for version in versions:
            if all(version_satisfies(version, requirement) for requirement in ranges):
                viable.append(version)
        if not viable:
            fail(f"No JarJar version for {coordinate[0]}:{coordinate[1]} satisfies all ranges: {ranges}")
        chosen_version = sorted(viable, key=cmp_to_key(compare_versions))[-1]
        chosen_entries = versions[chosen_version]
        representative = chosen_entries[0]
        selected.append({"group": coordinate[0], "artifact": coordinate[1],
                         "version": chosen_version, "versionRanges": ranges,
                         "sources": sorted({item["source"] for item in chosen_entries}),
                         "owners": sorted({item["owner"] for item in chosen_entries}),
                         "path": representative["path"], "sha256": representative["sha256"],
                         "data": representative["data"]})
    return selected


def check_no_forbidden_paths(files: dict[str, bytes]) -> None:
    forbidden_segments = {"logs", "world", "worlds", "saves", "screenshots",
                          "crash-reports", "playerdata", "config"}
    forbidden_names = re.compile(r"(?i)(^|[._-])(credentials?|secrets?|tokens?|apikey|api-key|private-key)([._-]|$)")
    for name, data in files.items():
        safe_name(name)
        parts = PurePosixPath(name).parts
        is_managed_projecte_config = name == "config/ProjectE/custom_emc.json"
        if any(p.lower() in forbidden_segments for p in parts) and not is_managed_projecte_config:
            fail(f"Forbidden user/server-state path: {name}")
        if forbidden_names.search(PurePosixPath(name).name):
            fail(f"Credential-like filename is forbidden: {name}")
        if name.lower().endswith((".pem", ".key", ".p12", ".pfx")):
            fail(f"Credential/key file is forbidden: {name}")
        if name.endswith("server.properties"):
            text = data.decode("utf-8", errors="replace")
            eula = re.search(r"(?im)^\s*eula\s*=\s*(true|false)\s*$", text)
            if eula and eula.group(1).lower() != "false":
                fail(f"EULA must remain false: {name}")
        if PurePosixPath(name).name.lower() == "eula.txt":
            text = data.decode("utf-8", errors="replace")
            match = re.search(r"(?im)^\s*eula\s*=\s*(true|false)\s*$", text)
            if not match or match.group(1).lower() != "false":
                fail(f"eula.txt must explicitly contain eula=false: {name}")


def collect_library_files(forge_root: Path, files: dict[str, bytes]) -> None:
    library_root = forge_root / "libraries"
    if not library_root.is_dir():
        fail(f"Forge libraries directory missing: {library_root}")
    for path in sorted(library_root.rglob("*")):
        if path.is_symlink():
            fail(f"Symlink in Forge libraries is not allowed: {path}")
        if not path.is_file():
            continue
        rel = path.relative_to(library_root).as_posix()
        add_bytes(files, safe_name(f"libraries/{rel}"), path.read_bytes())


def validate_forge_args(forge_root: Path) -> list[dict]:
    base = forge_root / "libraries/net/minecraftforge/forge/1.20.1-47.4.28"
    records = []
    for filename in ("win_args.txt", "unix_args.txt"):
        path = base / filename
        if not path.is_file():
            fail(f"Missing Forge argument file: {path}")
        raw = path.read_text(encoding="utf-8")
        references = sorted(set(re.findall(r"(?<![A-Za-z0-9_.-])(libraries/[A-Za-z0-9_./+@-]+\.jar)", raw)))
        if not references:
            fail(f"No library references found in {filename}")
        for ref in references:
            safe_name(ref)
            resolved = forge_root / Path(*PurePosixPath(ref).parts)
            if not resolved.is_file():
                fail(f"Forge {filename} references missing library: {ref}")
        records.append({"file": f"libraries/net/minecraftforge/forge/1.20.1-47.4.28/{filename}",
                        "libraryReferences": len(references), "allPresent": True})
    return records


QUALIFIER_ORDER = {"alpha": 0, "beta": 1, "milestone": 2, "rc": 3,
                   "snapshot": 4, "": 5, "sp": 6}
QUALIFIER_ALIASES = {"a": "alpha", "b": "beta", "m": "milestone",
                     "cr": "rc", "ga": "", "final": "", "release": ""}


def version_tokens(value: str) -> list[tuple[str, object]]:
    """Tokenize numeric and Maven-style qualifier components without dropping text."""
    value = value.strip().lower()
    if not value or not re.fullmatch(r"[a-z0-9._+-]+", value):
        fail(f"Cannot statically compare dependency version {value!r}")
    raw = re.findall(r"\d+|[a-z]+", value)
    if not raw:
        fail(f"Cannot statically compare dependency version {value!r}")
    tokens: list[tuple[str, object]] = []
    for token in raw:
        if token.isdigit():
            tokens.append(("number", int(token)))
        else:
            qualifier = QUALIFIER_ALIASES.get(token, token)
            tokens.append(("qualifier", qualifier))
    return tokens


def compare_versions(left: str, right: str) -> int:
    left_tokens = version_tokens(left)
    right_tokens = version_tokens(right)
    for index in range(max(len(left_tokens), len(right_tokens))):
        # Missing numeric components are zero; a missing qualifier is a release.
        ltoken = left_tokens[index] if index < len(left_tokens) else (
            ("qualifier", "") if index < len(right_tokens) and right_tokens[index][0] == "qualifier"
            else ("number", 0))
        rtoken = right_tokens[index] if index < len(right_tokens) else (
            ("qualifier", "") if index < len(left_tokens) and left_tokens[index][0] == "qualifier"
            else ("number", 0))
        if ltoken[0] == rtoken[0] == "number":
            lv, rv = int(ltoken[1]), int(rtoken[1])
            if lv != rv:
                return -1 if lv < rv else 1
        elif ltoken[0] != rtoken[0]:
            # A release's numeric component sorts above a prerelease qualifier.
            return 1 if ltoken[0] == "number" else -1
        else:
            lqual, rqual = str(ltoken[1]), str(rtoken[1])
            lv = QUALIFIER_ORDER.get(lqual, 7)
            rv = QUALIFIER_ORDER.get(rqual, 7)
            if lv != rv:
                return -1 if lv < rv else 1
            if lv == 7 and lqual != rqual:
                return -1 if lqual < rqual else 1
    return 0


def version_satisfies(actual: str, constraint: str) -> bool:
    constraint = constraint.strip()
    if constraint in ("", "*", "${file.jarVersion}"):
        if constraint == "${file.jarVersion}":
            fail("Unresolved ${file.jarVersion} dependency range")
        return True
    # Validate actual even for an open interval.
    version_tokens(actual)
    # Maven-style alternatives, e.g. [1.0,2.0),[3.0,4.0].
    alternatives = re.findall(r"([\[(])([^,\[\]()]*),([^,\[\]()]*)([\])])", constraint)
    consumed = "".join("".join(part) for part in alternatives)
    if alternatives and re.sub(r"[\s,]", "", consumed) == re.sub(r"[\s,]", "", constraint):
        for left_bracket, lower, upper, right_bracket in alternatives:
            lower_cmp = compare_versions(actual, lower) if lower else 1
            upper_cmp = compare_versions(actual, upper) if upper else -1
            lower_ok = not lower or lower_cmp > 0 or (left_bracket == "[" and lower_cmp == 0)
            upper_ok = not upper or upper_cmp < 0 or (right_bracket == "]" and upper_cmp == 0)
            if lower_ok and upper_ok:
                return True
        return False
    exact = re.fullmatch(r"\[([^,\[\]()]+)\]", constraint)
    if exact:
        return compare_versions(actual, exact.group(1)) == 0
    if re.fullmatch(r"[A-Za-z0-9._+-]+", constraint):
        return compare_versions(actual, constraint) == 0
    fail(f"Cannot statically parse dependency version range {constraint!r}")


def generated_projecte_config() -> bytes:
    source = read_json(PROJECTE_BLACKLIST)
    if (source.get("minecraftInstance") != "1.20.1-Forge_47.4.28"
            or source.get("projecteVersionJar") != "projecte-1201-PE1.0.1.jar"):
        fail("ProjectE blacklist is not pinned to the package's 1.20.1 runtime")
    item_ids = source.get("itemIds")
    if not isinstance(item_ids, list) or not item_ids or len(item_ids) != len(set(item_ids)):
        fail("ProjectE blacklist itemIds are missing or duplicated")
    if any(not isinstance(item, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", item)
           for item in item_ids):
        fail("ProjectE blacklist contains an invalid item ID")
    generated = {
        "__comment": "Generated from the IC2: New Era controlled-intermediate EMC blacklist; do not replace with player config.",
        "entries": [{"item": item, "emc": 0} for item in sorted(item_ids)],
    }
    return (json.dumps(generated, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--own-jar", required=True, type=Path)
    parser.add_argument("--own-sha256", required=True)
    parser.add_argument("--forge-root", type=Path, default=FORGE_ROOT_DEFAULT)
    args = parser.parse_args()
    # Lock integrity is checked before any archive assembly or metadata closure work.
    core = read_json(CORE_LOCK)
    core_rows = core.get("runtimeMods", [])
    if len(core_rows) != 15:
        fail(f"Expected exactly 15 core runtime mods, found {len(core_rows)}")
    enhancement = read_json(ENHANCEMENTS_LOCK)
    enhancement_rows = enhancement.get("mods", [])
    if not isinstance(enhancement_rows, list):
        fail("Enhancement lock has no mods array")
    dependencies_root = ROOT / "build-isolated/migration-1201/dependencies"
    files: dict[str, bytes] = {}
    mod_sources: list[dict] = []
    selected = []
    for row in core_rows:
        path, data = validate_locked_file(row, dependencies_root, "core lock")
        selected.append((row, path, data, "both", "core"))
    for row in enhancement_rows:
        env = row.get("environment")
        if env not in ("client", "both"):
            fail(f"Invalid enhancement environment for {row.get('modId')}: {env!r}")
        path, data = validate_locked_file(row, dependencies_root, "enhancement lock")
        if env == "both":
            selected.append((row, path, data, env, "enhancement"))
    own_path = args.own_jar
    if own_path.is_symlink() or not own_path.is_file():
        fail(f"Own mod JAR missing/non-regular: {own_path}")
    own_path = own_path.resolve()
    own_data = own_path.read_bytes()
    if sha256_bytes(own_data) != args.own_sha256.upper():
        fail("Own JAR SHA-256 does not match --own-sha256")
    own_row = {"modId": "ic2newera", "version": "local", "file": own_path.name,
               "source": "user-supplied local artifact", "sourceVersionUrl": None,
               "sizeBytes": len(own_data), "sha256": sha256_bytes(own_data), "environment": "both"}
    selected.append((own_row, own_path, own_data, "both", "own"))

    # Read only the prepared/libraries subtree from the installer output.
    forge_root = args.forge_root.resolve()
    if not forge_root.is_dir():
        fail(f"Forge root does not exist: {forge_root}")
    arg_audit = validate_forge_args(forge_root)
    collect_library_files(forge_root, files)

    mandatory: list[dict] = []
    included_ids: set[str] = set()
    top_level_ids: dict[str, str] = {}
    jarjar_candidates: dict[tuple[str, str], list[dict]] = {}
    jarjar_seen: set[tuple[str, str]] = set()
    jarjar_selections: list[dict] = []
    selected_versions: dict[str, str] = {
        "minecraft": "1.20.1", "forge": "47.4.28", "javafml": "47.4.28",
        "lowcodefml": "47.4.28",
    }
    for row, path, data, env, kind in selected:
        filename = str(row["file"])
        add_bytes(files, archive_path_for_mod(filename), data)
        jar_mods, jar_deps = metadata_from_jar(data, filename)
        for m in jar_mods:
            if m["modId"] in GRAPHICS_MODS:
                fail(f"Graphics/client-only mod unexpectedly selected: {m['modId']} in {filename}")
            previous = top_level_ids.get(m["modId"])
            if previous is not None:
                fail(f"Duplicate top-level mod ID {m['modId']} in {previous} and {filename}")
            top_level_ids[m["modId"]] = filename
            included_ids.add(m["modId"])
            selected_versions[m["modId"]] = str(m["version"])
        mandatory.extend(jar_deps)
        collect_jarjar_candidates(data, filename, filename, jarjar_candidates, jarjar_seen)
        mod_sources.append({"modId": row.get("modId"), "version": row.get("version"),
                            "file": filename, "source": row.get("source"),
                            "sourceVersionUrl": row.get("sourceVersionUrl"),
                            "sizeBytes": len(data), "sha256": sha256_bytes(data),
                            "environment": env, "lock": kind,
                            "containedMods": [{"modId": m["modId"], "metadataVersion": m["version"]}
                                              for m in jar_mods]})
    jarjar_selections = resolve_jarjar_candidates(jarjar_candidates)
    selected_embedded_ids: dict[str, str] = {}
    for selection in jarjar_selections:
        embedded_mods, embedded_deps = metadata_from_jar(selection["data"],
                                                         f"{selection['group']}:{selection['artifact']}:{selection['version']}")
        selection["containedMods"] = [{"modId": m["modId"], "metadataVersion": m["version"]}
                                       for m in embedded_mods]
        for m in embedded_mods:
            modid = m["modId"]
            if modid in GRAPHICS_MODS:
                fail(f"Graphics/client-only embedded mod unexpectedly selected: {modid}")
            if modid in top_level_ids:
                fail(f"JarJar mod ID {modid} duplicates top-level mod in {top_level_ids[modid]}")
            prior = selected_embedded_ids.get(modid)
            if prior is not None:
                fail(f"Duplicate selected JarJar mod ID {modid} from {prior} and {selection['group']}:{selection['artifact']}:{selection['version']}")
            selected_embedded_ids[modid] = f"{selection['group']}:{selection['artifact']}:{selection['version']}"
            included_ids.add(modid)
            selected_versions[modid] = str(m["version"])
        mandatory.extend(embedded_deps)
    for row, path, _data, _env, _kind in selected:
        filename = str(row["file"])
        owned_ids = {modid for modid, source in top_level_ids.items() if source == filename}
        for selection in jarjar_selections:
            if filename in selection["owners"]:
                owned_ids.update(m["modId"] for m in selection.get("containedMods", []))
        if row.get("modId") not in owned_ids:
            fail(f"Locked mod ID {row.get('modId')} was not found in {filename} or its selected JarJar modules")
    missing = sorted({d["requires"] for d in mandatory
                      if d["requires"] and d["requires"] not in included_ids
                      and d["requires"] not in PLATFORM_MODS})
    if missing:
        fail("Missing mandatory server dependency mod IDs: " + ", ".join(missing))
    dependency_audit = []
    for dep in mandatory:
        required = dep["requires"]
        present = required in included_ids or required in PLATFORM_MODS
        if not present:
            fail(f"Mandatory dependency is not present: {required}")
        actual_version = selected_versions.get(required)
        if actual_version is None:
            fail(f"Dependency {required} is present but its selected version is unknown")
        requirement = dep["versionRange"]
        if not version_satisfies(actual_version, requirement):
            fail(f"Dependency version mismatch: {dep['from']} requires {required} {requirement}, selected {actual_version}")
        dependency_audit.append({**dep, "selectedVersion": actual_version,
                                 "present": True, "versionRangeSatisfied": True})

    # Constrain template inputs to explicitly allowed text/script suffixes.
    if not TEMPLATE.is_dir():
        fail(f"Server template directory missing: {TEMPLATE}")
    for path in sorted(TEMPLATE.rglob("*")):
        if path.is_symlink():
            fail(f"Symlink in server template: {path}")
        if path.is_file():
            rel = safe_name(path.relative_to(TEMPLATE).as_posix())
            if path.suffix.lower() in ALLOWED_TEMPLATE_SUFFIXES or rel in ALLOWED_TEMPLATE_PATHS:
                add_bytes(files, rel, path.read_bytes())
    if not any(PurePosixPath(n).name.lower() == "eula.txt" for n in files):
        fail("Server template must provide eula.txt with eula=false")
    if not any(PurePosixPath(n).name.lower() == "server.properties" for n in files):
        fail("Server template must provide server.properties")

    for name in ("README.md", "example-house.json", "simple-pavilion.json"):
        path = BLUEPRINTS / name
        if not path.is_file() or path.is_symlink():
            fail(f"Required public blueprint example missing: {path}")
        add_bytes(files, safe_name(f"examples/blueprints/{name}"), path.read_bytes())
    if not NOTICES.is_file():
        fail(f"Third-party notice file missing: {NOTICES}")
    add_bytes(files, "THIRD-PARTY-NOTICES.txt", NOTICES.read_bytes())
    add_bytes(files, "config/ProjectE/custom_emc.json", generated_projecte_config())

    check_no_forbidden_paths(files)
    # Client-only enhancements are integrity-checked but never copied into mods/.
    client_only = [{"modId": r.get("modId"), "version": r.get("version"), "file": r.get("file"),
                    "environment": "client", "included": False}
                   for r in enhancement_rows if r.get("environment") == "client"]
    manifest_files = [{"path": name, "sizeBytes": len(data), "sha256": sha256_bytes(data)}
                      for name, data in sorted(files.items())]
    manifest = {"schemaVersion": 1, "package": TOP, "minecraft": "1.20.1",
                "forge": "47.4.28", "java": 17,
                "mods": mod_sources, "embeddedJarSelections": [
                    {k: v for k, v in item.items() if k != "data"} for item in jarjar_selections
                ], "clientOnlyExcluded": client_only,
                "mandatoryServerDependencyClosure": dependency_audit,
                "files": manifest_files,
                "manifestNote": "This file is self-hashed in the sibling artifact-audit.json."}
    add_bytes(files, "server-manifest.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    check_no_forbidden_paths(files)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    archive_path = OUT_DIR / ARCHIVE_NAME
    if archive_path.exists():
        fail(f"Refusing to overwrite existing archive: {archive_path}")
    temp_path = OUT_DIR / (ARCHIVE_NAME + ".partial")
    if temp_path.exists():
        fail(f"Refusing to overwrite existing partial archive: {temp_path}")
    try:
        with tarfile.open(temp_path, "w:gz", format=tarfile.PAX_FORMAT) as tar:
            for name, data in sorted(files.items()):
                member_name = safe_name(f"{TOP}/{name}")
                info = tarfile.TarInfo(member_name)
                info.size = len(data)
                info.mode = 0o755 if name.lower().endswith(".sh") else 0o644
                info.mtime = 0
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                tar.addfile(info, io.BytesIO(data))
        # Read the produced tar back and verify every expected member digest.
        expected = {f"{TOP}/{k}": (len(v), sha256_bytes(v)) for k, v in files.items()}
        with tarfile.open(temp_path, "r:gz") as tar:
            members = tar.getmembers()
            actual_names = {m.name for m in members if m.isfile()}
            if actual_names != set(expected):
                fail("Tar member set differs from expected payload")
            for member in members:
                safe_name(member.name)
                if not member.isfile():
                    fail(f"Non-regular tar entry: {member.name}")
                if member.mode != (0o755 if member.name.lower().endswith(".sh") else 0o644):
                    fail(f"Incorrect file mode in tar: {member.name}")
                extracted = tar.extractfile(member)
                if extracted is None:
                    fail(f"Unable to read back tar member: {member.name}")
                data = extracted.read()
                size, digest = expected[member.name]
                if len(data) != size or sha256_bytes(data) != digest:
                    fail(f"Tar read-back hash mismatch: {member.name}")
        os.replace(temp_path, archive_path)
    except Exception:
        if temp_path.exists():
            temp_path.unlink()
        raise

    audit = {"createdUtc": datetime.now(timezone.utc).isoformat(),
             "archive": str(archive_path.relative_to(ROOT).as_posix()),
             "archiveSha256": sha256_file(archive_path), "archiveSizeBytes": archive_path.stat().st_size,
             "serverManifestSha256": sha256_bytes(files["server-manifest.json"]),
             "forgeInstaller": "complete", "forgeArgsLibraries": arg_audit,
             "serverWorld": "not_started", "linuxHardwareVerified": False,
             "testsRun": False, "thirdPartyScope": "Locally held dependencies packaged only for the user's own server migration; no third-party content is uploaded to GitHub.",
             "clientOnlyGraphicsIncluded": False,
             "tarReadbackSha256Verified": True, "fileCount": len(files)}
    audit_path = OUT_DIR / "artifact-audit.json"
    audit_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Created {archive_path}")
    print(f"Artifact audit: {audit_path}")


if __name__ == "__main__":
    main()
