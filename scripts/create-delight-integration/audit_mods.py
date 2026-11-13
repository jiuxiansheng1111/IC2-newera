#!/usr/bin/env python3
"""Static Forge mod/JarJar dependency audit for a selected two-sided pack.

Plan format (JSON array):
  [{"path":"C:/absolute/mod.jar","server":true,"name":"mod.jar"}]

Every plan row is present on the client. Rows with server=true are also
present on the dedicated server. No JAR is executed and nothing is downloaded.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
import tomllib
import zipfile
from collections import defaultdict
from pathlib import Path
from typing import Any

_MAVEN_CHECKER: Any = None


class _MavenRangeChecker:
    """Batch adapter for the same Maven VersionRange API used by Forge."""

    JAVA_SOURCE = r'''import java.io.*;
import org.apache.maven.artifact.versioning.*;
public class ForgeMavenRangeChecker {
  public static void main(String[] args) throws Exception {
    var in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
    String line;
    while ((line = in.readLine()) != null) {
      String[] p = line.split("\t", 2);
      try {
        VersionRange r = VersionRange.createFromVersionSpec(p[1]);
        boolean contains = r.containsVersion(new DefaultArtifactVersion(p[0]));
        String recommended = r.getRecommendedVersion() == null ? "" : r.getRecommendedVersion().toString();
        System.out.println((contains ? "1" : "0") + "\t" + recommended + "\t" + r.hasRestrictions());
      } catch (Exception e) { System.out.println("?\t\tfalse"); }
    }
  }
}'''

    def __init__(self, report_dir: Path, library_roots: list[Path]):
        self.cache: dict[tuple[str, str], tuple[bool | None, str, bool]] = {}
        jars: list[Path] = []
        for root in library_roots:
            jars.extend(root.glob("org/apache/maven/maven-artifact/*/maven-artifact-*.jar"))
            jars.extend(root.glob("org/apache/commons/commons-lang3/*/commons-lang3-*.jar"))
        maven_jars = [p for p in jars if p.name.startswith("maven-artifact-")]
        commons_jars = [p for p in jars if p.name.startswith("commons-lang3-")]
        if not maven_jars or not commons_jars:
            raise RuntimeError("Maven artifact / commons-lang3 JARs required for Forge-accurate range checks were not found in the selected profile or Gradle cache.")
        classpath = str(maven_jars[-1]) + ";" + str(commons_jars[-1])
        self.runtime_jars = [str(maven_jars[-1]), str(commons_jars[-1])]
        helper_dir = report_dir / "compat-work" / "maven-range-checker"
        helper_dir.mkdir(parents=True, exist_ok=True)
        source = helper_dir / "ForgeMavenRangeChecker.java"
        source.write_text(self.JAVA_SOURCE, encoding="utf-8")
        compiled = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(helper_dir), str(source)], capture_output=True, text=True)
        if compiled.returncode:
            raise RuntimeError(f"Could not compile Forge Maven range checker: {compiled.stderr.strip()}")
        self.proc = subprocess.Popen(["java", "-cp", str(helper_dir) + ";" + classpath, "ForgeMavenRangeChecker"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", bufsize=1)

    def query(self, version: str, spec: str) -> tuple[bool | None, str, bool]:
        key = (version, spec)
        if key not in self.cache:
            if not self.proc.stdin or not self.proc.stdout:
                return (None, "", False)
            self.proc.stdin.write(version + "\t" + spec + "\n")
            self.proc.stdin.flush()
            fields = self.proc.stdout.readline().rstrip("\r\n").split("\t")
            result = None if not fields or fields[0] == "?" else fields[0] == "1"
            self.cache[key] = (result, fields[1] if len(fields) > 1 else "", len(fields) > 2 and fields[2].lower() == "true")
        return self.cache[key]

    def close(self) -> None:
        if self.proc.stdin:
            self.proc.stdin.close()
        try:
            self.proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def _manifest(blob: bytes) -> dict[str, str]:
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as jar:
            raw = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    except (KeyError, zipfile.BadZipFile):
        return {}
    # Manifest continuation lines begin with one space.
    unfolded = re.sub(r"\r?\n ", "", raw)
    result: dict[str, str] = {}
    for line in unfolded.splitlines():
        if ": " in line:
            key, value = line.split(": ", 1)
            result[key.strip()] = value.strip()
    return result


def _zip_entries(blob: bytes) -> zipfile.ZipFile | None:
    try:
        return zipfile.ZipFile(io.BytesIO(blob))
    except zipfile.BadZipFile:
        return None


def _bool(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"true", "yes", "1"}


def _version_tokens(value: str) -> list[tuple[int, Any]]:
    # Forge/Maven mod versions are usually numeric dotted versions with optional
    # qualifiers/builds. This comparison preserves numeric segments and treats
    # alphabetic qualifiers as lower than a final numeric/release segment.
    parts = re.findall(r"\d+|[A-Za-z]+", value.strip().lower())
    tokens: list[tuple[int, Any]] = []
    for part in parts:
        if part.isdigit():
            tokens.append((1, int(part)))
        else:
            tokens.append((0, part))
    return tokens


def _compare_versions(left: str, right: str) -> int:
    a, b = _version_tokens(left), _version_tokens(right)
    for index in range(max(len(a), len(b))):
        if index >= len(a):
            tail = b[index:]
            # Missing numeric components are zero; a release qualifier is
            # below the corresponding unqualified version.
            for kind, value in tail:
                if kind == 1 and value != 0:
                    return -1
                if kind == 0:
                    return 1
            return 0
        if index >= len(b):
            tail = a[index:]
            for kind, value in tail:
                if kind == 1 and value != 0:
                    return 1
                if kind == 0:
                    return -1
            return 0
        ak, av = a[index]
        bk, bv = b[index]
        if ak != bk:
            return -1 if ak == 0 else 1
        if av != bv:
            return -1 if av < bv else 1
    return 0


def _parse_range(spec: str) -> list[tuple[str | None, str | None, bool, bool]] | None:
    value = (spec or "").strip()
    if not value or value == "*":
        return [(None, None, False, False)]
    intervals: list[tuple[str | None, str | None, bool, bool]] = []
    cursor = 0
    while cursor < len(value):
        while cursor < len(value) and (value[cursor].isspace() or value[cursor] == ","):
            cursor += 1
        if cursor >= len(value):
            break
        if value[cursor] not in "[(":
            # Forge delegates these to Maven VersionRange. A bare version is
            # a soft recommendation, not an inclusive exact constraint.
            end = value.find(",", cursor)
            atom = value[cursor:] if end < 0 else value[cursor:end]
            atom = atom.strip()
            if not atom:
                return None
            intervals.append((None, None, False, False))
            cursor = len(value) if end < 0 else end + 1
            continue
        opening = value[cursor]
        closing_index = next((i for i in range(cursor + 1, len(value)) if value[i] in ")]"), -1)
        if closing_index < 0:
            return None
        closing = value[closing_index]
        inside = value[cursor + 1 : closing_index]
        if "," not in inside:
            point = inside.strip()
            if not point:
                return None
            if opening == "[" and closing == "]":
                intervals.append((point, point, True, True))
            elif opening == "(" and closing == ")":
                intervals.append((None, None, False, False))
            else:
                return None
        else:
            low, high = (part.strip() for part in inside.split(",", 1))
            intervals.append((low or None, high or None, opening == "[", closing == "]"))
        cursor = closing_index + 1
    return intervals or None


def _version_in_range(version: str, spec: str) -> bool | None:
    if _MAVEN_CHECKER is not None and spec.strip() and version:
        return _MAVEN_CHECKER.query(version, spec)[0]
    intervals = _parse_range(spec)
    if intervals is None or not version or "${" in version:
        return None
    for low, high, include_low, include_high in intervals:
        if low is not None:
            cmp_low = _compare_versions(version, low)
            if cmp_low < 0 or (cmp_low == 0 and not include_low):
                continue
        if high is not None:
            cmp_high = _compare_versions(version, high)
            if cmp_high > 0 or (cmp_high == 0 and not include_high):
                continue
        return True
    return False


def _resolve_mod_version(raw_version: str, manifest: dict[str, str]) -> str:
    version = raw_version or "unknown"
    for key in ("Implementation-Version", "Specification-Version", "Bundle-Version"):
        replacement = manifest.get(key)
        if replacement:
            version = version.replace("${file.jarVersion}", replacement)
    return version


def _scan_jar(
    blob: bytes,
    origin: dict[str, Any],
    row: dict[str, Any],
    mods: list[dict[str, Any]],
    libraries: list[dict[str, Any]],
    issues: list[dict[str, Any]],
    depth: int = 0,
    ancestry: tuple[str, ...] = (),
) -> None:
    if depth > 6:
        issues.append({"severity": "warning", "code": "jarjar_depth", "origin": origin, "message": "Nested JarJar depth exceeded 6."})
        return
    digest = hashlib.sha256(blob).hexdigest()
    marker = digest
    if marker in ancestry:
        issues.append({"severity": "warning", "code": "jarjar_cycle", "origin": origin, "message": "Repeated JAR content in nested JarJar chain."})
        return
    archive = _zip_entries(blob)
    if archive is None:
        issues.append({"severity": "error", "code": "bad_jar", "origin": origin, "message": "Selected file is not a readable JAR/ZIP."})
        return
    with archive:
        names = set(archive.namelist())
        manifest = _manifest(blob)
        forge_meta_path = "META-INF/mods.toml" if "META-INF/mods.toml" in names else None
        neoforge_meta_path = "META-INF/neoforge.mods.toml" if "META-INF/neoforge.mods.toml" in names else None
        if forge_meta_path:
            try:
                metadata = tomllib.loads(archive.read(forge_meta_path).decode("utf-8-sig"))
            except Exception as exc:
                issues.append({"severity": "error", "code": "mods_toml_parse", "origin": origin, "message": f"Cannot parse META-INF/mods.toml: {exc}"})
                metadata = {}
            loader = str(metadata.get("modLoader", "")).strip()
            loader_version = str(metadata.get("loaderVersion", "")).strip()
            mods_rows = metadata.get("mods", []) or []
            dependency_groups = metadata.get("dependencies", {}) or {}
            if isinstance(dependency_groups, list):
                # A few Forge jars use the compact flat form; its dependencies
                # apply to the jar's primary declared mod.
                primary_id = str((mods_rows[0] or {}).get("modId", "")).strip() if mods_rows else ""
                dependency_groups = {primary_id: dependency_groups} if primary_id else {}
            elif not isinstance(dependency_groups, dict):
                issues.append({"severity": "error", "code": "dependency_metadata_shape", "origin": origin, "message": "Forge dependencies metadata must be a table or an array of dependency tables."})
                dependency_groups = {}
            for mod_row in mods_rows:
                mod_id = str(mod_row.get("modId", "")).strip()
                raw_version = str(mod_row.get("version", "unknown")).strip()
                if not mod_id:
                    issues.append({"severity": "error", "code": "missing_modid", "origin": origin, "message": "A [[mods]] entry has no modId."})
                    continue
                mods.append({
                    "modid": mod_id,
                    "version": _resolve_mod_version(raw_version, manifest),
                    "raw_version": raw_version,
                    "loader": loader,
                    "loader_version": loader_version,
                    "origin": origin,
                    "server": bool(row["server"]),
                    "client": True,
                    "embedded": depth > 0,
                    "sha256": digest,
                })
                for dep in dependency_groups.get(mod_id, []) or []:
                    dep_id = str(dep.get("modId", "")).strip()
                    if not dep_id:
                        issues.append({"severity": "error", "code": "missing_dependency_modid", "origin": origin, "owner": mod_id, "message": "Dependency entry has no modId."})
                        continue
                    dependency = {
                        "owner": mod_id,
                        "modid": dep_id,
                        "mandatory": _bool(dep.get("mandatory"), True),
                        "version_range": str(dep.get("versionRange", "")),
                        "ordering": str(dep.get("ordering", "NONE")).upper(),
                        "side": str(dep.get("side", "BOTH")).upper(),
                        "origin": origin,
                    }
                    mods[-1].setdefault("dependencies", []).append(dependency)
            if loader and loader not in {"javafml", "lowcodefml", "kotlinforforge"}:
                issues.append({"severity": "error", "code": "unsupported_loader", "origin": origin, "message": f"Forge audit found modLoader={loader!r}; this is not JavaFML/LowCodeFML/KotlinForForge."})
        elif neoforge_meta_path:
            issues.append({"severity": "error", "code": "neoforge_jar_in_forge_plan", "origin": origin, "message": "JAR has META-INF/neoforge.mods.toml but no Forge META-INF/mods.toml."})
        elif "fabric.mod.json" in names:
            issues.append({"severity": "warning", "code": "fabric_metadata_ignored_by_forge", "origin": origin, "message": "JAR has Fabric metadata but no Forge metadata; Forge will not load it as a mod unless another selected Forge JAR provides its mod ID."})
        else:
            issues.append({"severity": "info", "code": "no_mod_metadata", "origin": origin, "message": "JAR contains no Forge mod metadata; treated as a library/resource unless JarJar metadata identifies it."})

        jarjar_path = "META-INF/jarjar/metadata.json"
        if jarjar_path in names:
            try:
                jarjar = json.loads(archive.read(jarjar_path).decode("utf-8"))
            except Exception as exc:
                issues.append({"severity": "error", "code": "jarjar_metadata_parse", "origin": origin, "message": f"Cannot parse JarJar metadata: {exc}"})
                jarjar = {}
            nested_rows = jarjar if isinstance(jarjar, list) else jarjar.get("jars", []) if isinstance(jarjar, dict) else []
            if not isinstance(nested_rows, list):
                issues.append({"severity": "error", "code": "jarjar_metadata_shape", "origin": origin, "message": "JarJar metadata must be an array or an object containing a jars array."})
                nested_rows = []
            for nested in nested_rows:
                if not isinstance(nested, dict):
                    issues.append({"severity": "error", "code": "jarjar_entry_shape", "origin": origin, "message": "JarJar metadata contains a non-object entry."})
                    continue
                nested_path = str(nested.get("path", ""))
                identifier = nested.get("identifier", {}) or {}
                version_info = nested.get("version", {}) or {}
                group, artifact = str(identifier.get("group", "")), str(identifier.get("artifact", ""))
                nested_version = str(version_info.get("artifactVersion", "unknown"))
                nested_range = str(version_info.get("range", ""))
                try:
                    nested_blob = archive.read(nested_path)
                except KeyError:
                    issues.append({"severity": "error", "code": "jarjar_file_missing", "origin": origin, "path": nested_path, "message": "JarJar metadata names a nested JAR that is absent from the archive."})
                    continue
                nested_origin = {
                    **origin,
                    "jarjar": f"{group}:{artifact}:{nested_version}",
                    "nested_path": nested_path,
                }
                libraries.append({
                    "group": group,
                    "artifact": artifact,
                    "version": nested_version,
                    "version_range": nested_range,
                    "path": nested_path,
                    "origin": origin,
                    "sha256": hashlib.sha256(nested_blob).hexdigest(),
                    "server": bool(row["server"]),
                })
                _scan_jar(nested_blob, nested_origin, row, mods, libraries, issues, depth + 1, ancestry + (marker,))


def _side_active(mod: dict[str, Any], side: str) -> bool:
    return bool(mod.get("client")) if side == "client" else bool(mod.get("server"))


def _dependency_sides(value: str) -> list[str]:
    side = (value or "BOTH").strip().upper()
    if side in {"CLIENT", "SERVER"}:
        return [side.lower()]
    if side in {"BOTH", "NONE", ""}:
        return ["client", "server"]
    return []


def _find_cycles(nodes: set[str], edges: dict[str, set[str]]) -> list[list[str]]:
    cycles: list[list[str]] = []
    visiting: list[str] = []
    visited: set[str] = set()

    def visit(node: str) -> None:
        if node in visiting:
            cycle = visiting[visiting.index(node) :] + [node]
            if cycle not in cycles:
                cycles.append(cycle)
            return
        if node in visited:
            return
        visiting.append(node)
        for target in sorted(edges.get(node, set())):
            if target in nodes:
                visit(target)
        visiting.pop()
        visited.add(node)

    for node in sorted(nodes):
        visit(node)
    return cycles


def audit_plan(
    plan_path: Path,
    report_path: Path,
    minecraft: str = "1.20.1",
    forge: str = "47.4.28",
    forge_libraries: Path | None = None,
) -> dict[str, Any]:
    global _MAVEN_CHECKER
    plan_path, report_path = Path(plan_path), Path(report_path)
    plan_data = json.loads(plan_path.read_text(encoding="utf-8-sig"))
    if isinstance(plan_data, list):
        rows = plan_data
        environment: dict[str, Any] = {"minecraft": minecraft, "forge": forge, "assumed_defaults": True}
    elif isinstance(plan_data, dict):
        rows = plan_data.get("jars", plan_data.get("mods", plan_data.get("selection", [])))
        environment = {
            "minecraft": plan_data.get("minecraft", minecraft),
            "forge": plan_data.get("forge", forge),
            "assumed_defaults": not ("minecraft" in plan_data or "forge" in plan_data),
        }
    else:
        raise ValueError("Plan must be a JSON list or an object with a jars/mods/selection list.")
    if not isinstance(rows, list):
        raise ValueError("Plan's jars/mods/selection value must be a list.")

    issues: list[dict[str, Any]] = []
    mods: list[dict[str, Any]] = []
    libraries: list[dict[str, Any]] = []
    normalized_rows: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            issues.append({"severity": "error", "code": "invalid_plan_row", "row": index, "message": "Plan row must be an object."})
            continue
        path_text = str(row.get("path", ""))
        if not path_text:
            issues.append({"severity": "error", "code": "missing_path", "row": index, "message": "Plan row has no path."})
            continue
        path = Path(path_text)
        if not path.is_absolute():
            issues.append({"severity": "error", "code": "path_not_absolute", "row": index, "path": path_text, "message": "Plan paths must be absolute."})
        if not path.is_file():
            issues.append({"severity": "error", "code": "jar_missing", "row": index, "path": str(path), "message": "Selected JAR path does not exist or is not a file."})
            continue
        if "server" not in row or not isinstance(row["server"], bool):
            issues.append({"severity": "error", "code": "invalid_server_flag", "row": index, "path": str(path), "message": "Each plan row must include a boolean server field."})
            continue
        display_name = str(row.get("name", path.name))
        origin = {"row": index, "name": display_name, "path": str(path), "server": row["server"]}
        if display_name != path.name:
            issues.append({"severity": "warning", "code": "name_path_mismatch", "origin": origin, "message": "Plan name differs from the selected file's basename; path is authoritative."})
        normalized_rows.append(origin)
        _scan_jar(path.read_bytes(), origin, row, mods, libraries, issues)

    library_roots = [Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"]
    if forge_libraries is not None:
        library_roots.append(forge_libraries.resolve(strict=True))
    for row in normalized_rows:
        path = Path(row["path"])
        for ancestor in path.parents:
            if ancestor.name.lower() == ".minecraft":
                library_roots.append(ancestor / "libraries")
                break
    try:
        _MAVEN_CHECKER = _MavenRangeChecker(report_path.parent, library_roots)
    except Exception as exc:
        issues.append({"severity": "error", "code": "maven_range_runtime_unavailable", "message": f"Could not initialize Forge's Maven VersionRange API: {exc}"})

    # Match Forge JarSelector's group:artifact candidate handling before
    # checking mod IDs: singleton candidates are selected unconditionally;
    # competing candidates must satisfy every Maven range in the group.
    library_groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for library in libraries:
        library_groups[(library["group"], library["artifact"])].append(library)
    selected_nested_hashes: set[str] = set()
    jarjar_selection: list[dict[str, Any]] = []
    for (group, artifact), entries in sorted(library_groups.items()):
        if len(entries) == 1:
            selected = entries
        else:
            ranges = [entry["version_range"] for entry in entries]
            candidates = [entry for entry in entries if all(_version_in_range(entry["version"], spec) is True for spec in ranges)]
            if not candidates:
                issues.append({"severity": "error", "code": "jarjar_selector_no_candidate", "group": group, "artifact": artifact, "versions": [entry["version"] for entry in entries], "ranges": ranges, "origins": [entry["origin"] for entry in entries], "message": "Forge JarSelector cannot find one contained artifact version accepted by every candidate range."})
                selected = []
            else:
                selected = candidates[:1]
        selected_nested_hashes.update(entry["sha256"] for entry in selected)
        jarjar_selection.append({"group": group, "artifact": artifact, "candidates": [{"version": entry["version"], "range": entry["version_range"], "origin": entry["origin"]} for entry in entries], "selected": [{"version": entry["version"], "origin": entry["origin"]} for entry in selected]})
    mods = [mod for mod in mods if not mod.get("embedded") or mod["sha256"] in selected_nested_hashes]

    # A single row can bundle many nested mods. Duplicate mod IDs are resolved
    # independently for the client and dedicated server closures.
    sides: dict[str, dict[str, list[dict[str, Any]]]] = {}
    ordering: dict[str, dict[str, set[str]]] = {"client": defaultdict(set), "server": defaultdict(set)}
    for side in ("client", "server"):
        active = [mod for mod in mods if _side_active(mod, side)]
        # Forge's JarInJarDependencyLocator identifies loaded files from their
        # mod IDs. JarSelector gives a top-level source JAR precedence over a
        # nested JAR with the same identification, then keeps only one nested
        # JAR for duplicate identifications.
        top_level_ids = {mod["modid"] for mod in active if not mod.get("embedded")}
        nested_ids_seen: set[str] = set()
        resolved_active: list[dict[str, Any]] = []
        for mod in active:
            if mod.get("embedded"):
                mod_id = mod["modid"]
                if mod_id in top_level_ids or mod_id in nested_ids_seen:
                    continue
                nested_ids_seen.add(mod_id)
            resolved_active.append(mod)
        active = resolved_active
        by_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for mod in active:
            by_id[mod["modid"]].append(mod)
        sides[side] = by_id
        for mod_id, entries in sorted(by_id.items()):
            if len(entries) > 1:
                issues.append({
                    "severity": "error",
                    "code": "duplicate_modid",
                    "side": side,
                    "modid": mod_id,
                    "versions": [entry["version"] for entry in entries],
                    "origins": [entry["origin"] for entry in entries],
                    "message": f"More than one selected JAR provides mod ID {mod_id!r} on {side}.",
                })

        for mod in active:
            loader = mod.get("loader", "")
            loader_version = mod.get("loader_version", "")
            if loader in {"javafml", "lowcodefml"}:
                # Forge mods commonly use the literal "0" to mean an
                # unconstrained language-provider version.
                if loader_version == "0":
                    result = True
                else:
                    result = _version_in_range(environment.get("forge", ""), loader_version)
                if result is False:
                    issues.append({"severity": "error", "code": "forge_loader_range", "side": side, "modid": mod["modid"], "version_range": loader_version, "forge": environment.get("forge"), "origin": mod["origin"], "message": "Selected Forge version is outside this mod's loaderVersion range."})
                elif result is None:
                    issues.append({"severity": "warning", "code": "forge_loader_range_unparsed", "side": side, "modid": mod["modid"], "version_range": loader_version, "origin": mod["origin"], "message": "Could not compare this mod's loaderVersion range."})
            elif loader == "kotlinforforge":
                providers = by_id.get("kotlinforforge", [])
                if not providers:
                    issues.append({"severity": "error", "code": "kotlin_loader_missing", "side": side, "modid": mod["modid"], "origin": mod["origin"], "message": "This mod uses KotlinForForge as its FML language provider, but KotlinForForge is absent on this side."})
                elif len(providers) == 1:
                    result = _version_in_range(providers[0]["version"], loader_version)
                    if result is False:
                        issues.append({"severity": "error", "code": "kotlin_loader_range", "side": side, "modid": mod["modid"], "version_range": loader_version, "provider_version": providers[0]["version"], "origin": mod["origin"], "message": "KotlinForForge version is outside this mod's loaderVersion range."})
                    elif result is None:
                        issues.append({"severity": "warning", "code": "kotlin_loader_range_unparsed", "side": side, "modid": mod["modid"], "version_range": loader_version, "origin": mod["origin"], "message": "Could not compare this mod's KotlinForForge loaderVersion range."})

            for dep in mod.get("dependencies", []):
                for dep_side in _dependency_sides(dep["side"]):
                    if dep_side != side:
                        continue
                    owner_entries = sides.get(side, {}).get(dep["owner"], [])
                    if not owner_entries:
                        continue
                    dep_id = dep["modid"]
                    if dep_id == "minecraft":
                        candidates = [{"version": environment.get("minecraft", "")}]
                    elif dep_id in {"forge", "javafml", "lowcodefml"}:
                        candidates = [{"version": environment.get("forge", "")}]
                    else:
                        candidates = sides.get(side, {}).get(dep_id, [])
                    if not candidates:
                        if dep["mandatory"]:
                            issues.append({"severity": "error", "code": "missing_mandatory_dependency", "side": side, "owner": dep["owner"], "dependency": dep_id, "version_range": dep["version_range"], "origin": dep["origin"], "message": f"Required dependency {dep_id!r} is absent on {side}."})
                        else:
                            issues.append({"severity": "info", "code": "optional_dependency_absent", "side": side, "owner": dep["owner"], "dependency": dep_id, "version_range": dep["version_range"], "origin": dep["origin"], "message": f"Optional dependency {dep_id!r} is absent; this is allowed."})
                        continue
                    if len(candidates) == 1:
                        found_version = candidates[0].get("version", "unknown")
                        result = _version_in_range(found_version, dep["version_range"])
                        if result is False:
                            issues.append({"severity": "error", "code": "dependency_version_out_of_range", "side": side, "owner": dep["owner"], "dependency": dep_id, "required_range": dep["version_range"], "found_version": found_version, "origin": dep["origin"], "message": f"Installed dependency {dep_id!r} does not satisfy the declared range."})
                        elif result is None:
                            issues.append({"severity": "warning", "code": "dependency_version_unresolved", "side": side, "owner": dep["owner"], "dependency": dep_id, "required_range": dep["version_range"], "found_version": found_version, "origin": dep["origin"], "message": "Version or range could not be compared; review this edge manually."})
                        elif dep["version_range"].strip() and not any(ch in dep["version_range"] for ch in "[()]") and _compare_versions(found_version, dep["version_range"].strip()) != 0:
                            issues.append({"severity": "warning", "code": "dependency_recommendation_differs", "side": side, "owner": dep["owner"], "dependency": dep_id, "recommended_version": dep["version_range"], "found_version": found_version, "origin": dep["origin"], "message": "Installed version differs from Maven's soft recommendedVersion; this is not a hard range conflict."})
                    dep_order = dep["ordering"]
                    if dep_order == "AFTER":
                        ordering[side][dep_id].add(dep["owner"])
                    elif dep_order == "BEFORE":
                        ordering[side][dep["owner"]].add(dep_id)

        graph_nodes = set(by_id)
        for cycle in _find_cycles(graph_nodes, ordering[side]):
            issues.append({"severity": "error", "code": "ordering_cycle", "side": side, "cycle": cycle, "message": "Forge dependency ordering contains a cycle."})

    jarjar_duplicates: list[dict[str, Any]] = []
    for (group, artifact), entries in sorted(library_groups.items()):
        distinct = sorted({entry["version"] for entry in entries})
        unsatisfied = [
            entry for entry in entries
            if _version_in_range(entry["version"], entry["version_range"]) is False
        ]
        if len(entries) > 1 and unsatisfied:
            issues.append({
                "severity": "error",
                "code": "jarjar_version_out_of_range",
                "group": group,
                "artifact": artifact,
                "candidates": [{"version": entry["version"], "range": entry["version_range"], "origin": entry["origin"]} for entry in unsatisfied],
                "message": "A JarJar nested artifact's declared artifactVersion is outside its own version.range.",
            })
        if len(entries) > 1:
            ranges = [entry["version_range"] for entry in entries if entry["version_range"]]
            candidates_satisfying_all = [
                version for version in distinct
                if all(_version_in_range(version, requirement) is True for requirement in ranges)
            ]
            record = {
                "group": group,
                "artifact": artifact,
                "versions": distinct,
                "ranges": ranges,
                "candidate_versions_satisfying_all_ranges": candidates_satisfying_all,
                "origins": [entry["origin"] for entry in entries],
            }
            jarjar_duplicates.append(record)
            issues.append({
                "severity": "error" if ranges and not candidates_satisfying_all else "warning",
                "code": "jarjar_no_common_version" if ranges and not candidates_satisfying_all else "jarjar_library_duplicate",
                **record,
                "message": "No packaged JarJar version satisfies every declared range." if ranges and not candidates_satisfying_all else "Multiple selected JARs bundle this JarJar group:artifact; ranges intersect on the listed candidate(s), and Forge JarJar should select one version.",
            })

    # Summaries deliberately distinguish the client set from the server closure.
    side_summary = {}
    for side, by_id in sides.items():
        side_summary[side] = {
            "mod_count_including_embedded": sum(len(entries) for entries in by_id.values()),
            "unique_mod_ids": len(by_id),
            "mod_ids": {key: [{"version": row["version"], "origin": row["origin"]} for row in entries] for key, entries in sorted(by_id.items())},
        }
    counts = {severity: sum(1 for issue in issues if issue["severity"] == severity) for severity in ("error", "warning", "info")}
    report = {
        "schema_version": 1,
        "plan": str(plan_path.resolve()),
        "environment": environment,
        "range_engine": {
            "implementation": "org.apache.maven.artifact.versioning.VersionRange / DefaultArtifactVersion",
            "runtime_jars": _MAVEN_CHECKER.runtime_jars if _MAVEN_CHECKER is not None else [],
            "status": "java_api" if _MAVEN_CHECKER is not None else "fallback",
        },
        "rows": normalized_rows,
        "summary": {
            "status": "pass" if counts["error"] == 0 else "fail",
            "issues": counts,
            "client": side_summary["client"],
            "server": side_summary["server"],
            "jarjar_library_conflicts": jarjar_duplicates,
        },
        "jarjar_selection": jarjar_selection,
        "issues": issues,
    }
    if _MAVEN_CHECKER is not None:
        _MAVEN_CHECKER.close()
        _MAVEN_CHECKER = None
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True, type=Path, help="JSON selection plan")
    parser.add_argument("--report", required=True, type=Path, help="Write JSON audit report here")
    parser.add_argument("--minecraft", default="1.20.1", help="Minecraft version if not in plan")
    parser.add_argument("--forge", default="47.4.28", help="Forge version if not in plan")
    parser.add_argument("--forge-libraries", type=Path, help="Launcher's libraries directory when inputs are in the verified source cache")
    args = parser.parse_args(argv)
    try:
        report = audit_plan(args.plan, args.report, minecraft=args.minecraft, forge=args.forge, forge_libraries=args.forge_libraries)
    except Exception as exc:
        print(f"audit_mods: {exc}", file=sys.stderr)
        return 2
    summary = report["summary"]
    print(json.dumps({
        "status": summary["status"],
        "issues": summary["issues"],
        "client": {"mod_count_including_embedded": summary["client"]["mod_count_including_embedded"], "unique_mod_ids": summary["client"]["unique_mod_ids"]},
        "server": {"mod_count_including_embedded": summary["server"]["mod_count_including_embedded"], "unique_mod_ids": summary["server"]["unique_mod_ids"]},
        "jarjar_conflict_groups": len(summary["jarjar_library_conflicts"]),
    }, ensure_ascii=False, indent=2))
    print(f"Wrote {args.report}")
    return 0 if report["summary"]["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
