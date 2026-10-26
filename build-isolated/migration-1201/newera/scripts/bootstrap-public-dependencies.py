#!/usr/bin/env python3
"""Rebuild only this project's locked public Maven overlays using Python stdlib."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent
MIGRATION_DIR = PROJECT_DIR.parent
LOCK_PATH = SCRIPT_DIR / "public-maven-lock.json"
TARGET_ROOT = MIGRATION_DIR / "verified-maven"
CHUNK_SIZE = 128 * 1024
USER_AGENT = "IC2NewEra-public-dependency-bootstrap/1.0"


class BootstrapError(Exception):
    pass


def fail(message: str) -> None:
    raise BootstrapError(message)


def safe_coordinate_part(value: object, field: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.+-]+", value):
        fail(f"Invalid {field} in lock: {value!r}")
    if value in (".", ".."):
        fail(f"Invalid path component for {field}: {value!r}")
    return value


def validate_lock() -> tuple[dict, list[dict]]:
    try:
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"Cannot read dependency lock {LOCK_PATH}: {error}")
    if lock.get("schemaVersion") != 1 or lock.get("targetRepository") != "../verified-maven":
        fail("Unsupported lock schema or target repository")
    artifacts = lock.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 9:
        fail("The public lock must contain exactly nine artifacts")

    seen: set[tuple[str, str, str]] = set()
    for item in artifacts:
        if not isinstance(item, dict):
            fail("Malformed artifact entry in lock")
        item["group"] = safe_coordinate_part(item.get("group"), "group")
        item["artifact"] = safe_coordinate_part(item.get("artifact"), "artifact")
        item["version"] = safe_coordinate_part(item.get("version"), "version")
        coordinate = (item["group"], item["artifact"], item["version"])
        if coordinate in seen:
            fail(f"Duplicate artifact coordinate: {':'.join(coordinate)}")
        seen.add(coordinate)
        if item.get("kind") not in ("download", "embedded"):
            fail(f"Unsupported artifact kind for {':'.join(coordinate)}")
        if not isinstance(item.get("sizeBytes"), int) or item["sizeBytes"] <= 0:
            fail(f"Invalid sizeBytes for {':'.join(coordinate)}")
        digest = item.get("sha256")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9A-Fa-f]{64}", digest):
            fail(f"Invalid SHA-256 for {':'.join(coordinate)}")
        if item["kind"] == "download":
            parsed = urllib.parse.urlsplit(item.get("url", ""))
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
                fail(f"Only fixed HTTPS URLs are allowed: {item.get('url')!r}")
        else:
            parent = item.get("parent")
            entry = item.get("entry")
            if not isinstance(parent, str) or not isinstance(entry, str):
                fail(f"Embedded artifact lacks parent/entry: {':'.join(coordinate)}")
            parts = Path(entry).parts
            if not entry.startswith("META-INF/jarjar/") or ".." in parts or Path(entry).is_absolute():
                fail(f"Unsafe embedded JAR entry: {entry!r}")
    if sum(item["kind"] == "download" for item in artifacts) != 6 or sum(
            item["kind"] == "embedded" for item in artifacts) != 3:
        fail("The public lock must contain six downloads and three Create-bundled artifacts")
    parents = {":".join((a["group"], a["artifact"], a["version"])): a for a in artifacts}
    for item in artifacts:
        if item["kind"] == "embedded":
            parent = parents.get(item["parent"])
            if parent is None or parent["kind"] != "download":
                fail(f"Embedded artifact parent is not a locked downloadable artifact: {item['parent']}")
    return lock, artifacts


def artifact_dir(item: dict) -> Path:
    return TARGET_ROOT.joinpath(*item["group"].split("."), item["artifact"], item["version"])


def jar_path(item: dict) -> Path:
    return artifact_dir(item) / f"{item['artifact']}-{item['version']}.jar"


def pom_path(item: dict) -> Path:
    return artifact_dir(item) / f"{item['artifact']}-{item['version']}.pom"


def ensure_in_target(path: Path) -> None:
    target_root_resolved = TARGET_ROOT.resolve()
    try:
        resolved = path.resolve()
        resolved.relative_to(target_root_resolved)
    except (OSError, ValueError):
        fail(f"Refusing to touch a path outside verified-maven: {path}")


def hash_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(CHUNK_SIZE)
            if not block:
                break
            size += len(block)
            digest.update(block)
    return size, digest.hexdigest().upper()


def verify_jar(path: Path, item: dict) -> None:
    ensure_in_target(path)
    if not path.is_file():
        fail(f"Missing locked JAR: {path}")
    size, digest = hash_file(path)
    if size != item["sizeBytes"] or digest != item["sha256"].upper():
        fail(f"JAR does not match lock: {path} (size={size}, sha256={digest})")
    if not zipfile.is_zipfile(path):
        fail(f"Locked JAR is not a valid ZIP archive: {path}")


def maven_pom_bytes(item: dict) -> bytes:
    # No dependency graph is synthesized; all nine artifacts are resolved explicitly.
    return ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            "<modelVersion>4.0.0</modelVersion>"
            f"<groupId>{item['group']}</groupId>"
            f"<artifactId>{item['artifact']}</artifactId>"
            f"<version>{item['version']}</version>"
            "<packaging>jar</packaging></project>\n").encode("utf-8")


def pom_coordinate(path: Path) -> tuple[str, str, str, str]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        fail(f"Invalid Maven POM {path}: {error}")
    namespace = ""
    if root.tag.startswith("{"):
        namespace = root.tag.split("}", 1)[0] + "}"
    if root.tag != namespace + "project":
        fail(f"Not a Maven project POM: {path}")

    def child_text(name: str) -> str:
        child = root.find(namespace + name)
        return child.text.strip() if child is not None and child.text else ""

    dependencies = root.find(namespace + "dependencies")
    if dependencies is not None and list(dependencies):
        fail(f"Public bootstrap POM must not add transitive dependencies: {path}")
    packaging = child_text("packaging") or "jar"
    return child_text("groupId"), child_text("artifactId"), child_text("version"), packaging


def verify_pom(path: Path, item: dict) -> None:
    ensure_in_target(path)
    if not path.is_file():
        fail(f"Missing Maven POM: {path}")
    actual = pom_coordinate(path)
    expected = (item["group"], item["artifact"], item["version"], "jar")
    if actual != expected:
        fail(f"POM coordinate mismatch in {path}: {actual!r} != {expected!r}")


def atomic_write(path: Path, blocks) -> tuple[int, str, Path]:
    path.parent.mkdir(parents=True, exist_ok=True)
    ensure_in_target(path)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".part", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        digest = hashlib.sha256()
        size = 0
        with os.fdopen(descriptor, "wb") as output:
            for block in blocks:
                if not block:
                    continue
                size += len(block)
                digest.update(block)
                output.write(block)
            output.flush()
            os.fsync(output.fileno())
        return size, digest.hexdigest().upper(), temporary
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def commit_verified(path: Path, item: dict, blocks) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    ensure_in_target(path)
    if path.exists():
        verify_jar(path, item)
        print(f"verified existing {item['group']}:{item['artifact']}:{item['version']}")
        return

    size, digest, temporary = atomic_write(path, blocks)
    try:
        if size != item["sizeBytes"] or digest != item["sha256"].upper():
            fail(f"Downloaded/extracted bytes failed lock validation for {item['artifact']}: size={size}, sha256={digest}")
        if not zipfile.is_zipfile(temporary):
            fail(f"Downloaded/extracted JAR is not a valid ZIP: {item['artifact']}")
        if path.exists():
            verify_jar(path, item)
            print(f"verified existing {item['group']}:{item['artifact']}:{item['version']}")
            return
        ensure_in_target(path)
        os.replace(temporary, path)
        print(f"created {item['group']}:{item['artifact']}:{item['version']}")
    finally:
        temporary.unlink(missing_ok=True)


def download_blocks(item: dict):
    source = item["url"]
    requested_host = urllib.parse.urlsplit(source).hostname
    allowed_hosts = {requested_host}
    if requested_host == "github.com":
        allowed_hosts.update({"release-assets.githubusercontent.com", "objects.githubusercontent.com"})
    request = urllib.request.Request(source, headers={"User-Agent": USER_AGENT})
    try:
        response = urllib.request.urlopen(request, timeout=60)
    except (urllib.error.URLError, TimeoutError) as error:
        fail(f"Download failed for {item['artifact']}: {error}")
    with response:
        final = urllib.parse.urlsplit(response.geturl())
        if final.scheme != "https" or final.hostname not in allowed_hosts:
            fail(f"Unexpected download redirect for {item['artifact']}: {response.geturl()}")
        total = 0
        while True:
            block = response.read(min(CHUNK_SIZE, item["sizeBytes"] + 1 - total))
            if not block:
                break
            total += len(block)
            if total > item["sizeBytes"]:
                fail(f"Download exceeded locked size for {item['artifact']}")
            yield block


def embedded_blocks(parent_item: dict, item: dict):
    parent_path = jar_path(parent_item)
    verify_jar(parent_path, parent_item)
    try:
        with zipfile.ZipFile(parent_path) as archive:
            matches = [info for info in archive.infolist() if info.filename == item["entry"]]
            if len(matches) != 1:
                fail(f"Expected exactly one locked nested entry {item['entry']} in Create JAR")
            info = matches[0]
            if info.file_size != item["sizeBytes"]:
                fail(f"Nested JAR size disagrees with lock: {item['entry']}")
            with archive.open(info) as nested:
                while True:
                    block = nested.read(CHUNK_SIZE)
                    if not block:
                        break
                    yield block
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        fail(f"Cannot read locked nested JAR {item['entry']}: {error}")


def ensure_pom(item: dict, verify_only: bool) -> None:
    path = pom_path(item)
    if path.exists():
        verify_pom(path, item)
        return
    if verify_only:
        fail(f"Missing Maven POM: {path}")
    data = maven_pom_bytes(item)

    # The POM is created atomically and only when absent; malformed existing files
    # are rejected rather than silently overwritten.
    path.parent.mkdir(parents=True, exist_ok=True)
    ensure_in_target(path)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".part", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        if path.exists():
            verify_pom(path, item)
        else:
            os.replace(temporary, path)
        verify_pom(path, item)
    finally:
        temporary.unlink(missing_ok=True)


def verify_embedded_parent(parent_item: dict, item: dict) -> None:
    digest = hashlib.sha256()
    size = 0
    try:
        with zipfile.ZipFile(jar_path(parent_item)) as archive:
            matches = [info for info in archive.infolist() if info.filename == item["entry"]]
            if len(matches) != 1:
                fail(f"Expected exactly one locked nested entry {item['entry']} in parent JAR")
            info = matches[0]
            if info.file_size != item["sizeBytes"]:
                fail(f"Nested JAR size mismatch in parent: {item['entry']}")
            with archive.open(info) as nested:
                while True:
                    block = nested.read(CHUNK_SIZE)
                    if not block:
                        break
                    size += len(block)
                    digest.update(block)
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        fail(f"Cannot verify parent entry {item['entry']}: {error}")
    if size != item["sizeBytes"] or digest.hexdigest().upper() != item["sha256"].upper():
        fail(f"Embedded JAR differs from its lock: {item['entry']}")


def run(verify_only: bool) -> None:
    _, artifacts = validate_lock()
    by_coordinate = {f"{item['group']}:{item['artifact']}:{item['version']}": item for item in artifacts}
    downloads = [item for item in artifacts if item["kind"] == "download"]
    embedded = [item for item in artifacts if item["kind"] == "embedded"]

    if verify_only:
        if TARGET_ROOT.is_symlink():
            fail(f"Refusing symlinked target repository: {TARGET_ROOT}")
        for item in artifacts:
            verify_jar(jar_path(item), item)
            verify_pom(pom_path(item), item)
        for item in embedded:
            verify_embedded_parent(by_coordinate[item["parent"]], item)
        print(f"verified {len(artifacts)} locked JAR/POM pairs offline in {TARGET_ROOT}")
        return

    TARGET_ROOT.mkdir(parents=True, exist_ok=True)
    if TARGET_ROOT.is_symlink():
        fail(f"Refusing symlinked target repository: {TARGET_ROOT}")
    for item in downloads:
        path = jar_path(item)
        path.parent.mkdir(parents=True, exist_ok=True)
        ensure_in_target(path)
        if path.exists():
            verify_jar(path, item)
            print(f"verified existing {item['group']}:{item['artifact']}:{item['version']}")
        else:
            commit_verified(path, item, download_blocks(item))

    for item in embedded:
        parent_item = by_coordinate[item["parent"]]
        verify_embedded_parent(parent_item, item)
        path = jar_path(item)
        path.parent.mkdir(parents=True, exist_ok=True)
        ensure_in_target(path)
        if path.exists():
            verify_jar(path, item)
            print(f"verified existing {item['group']}:{item['artifact']}:{item['version']}")
        else:
            commit_verified(path, item, embedded_blocks(parent_item, item))

    for item in artifacts:
        ensure_pom(item, verify_only=False)
    print(f"prepared {len(artifacts)} locked JAR/POM pairs in {TARGET_ROOT}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="check existing JARs/POMs without network or writes")
    args = parser.parse_args()
    try:
        run(args.verify_only)
    except BootstrapError as error:
        print(f"dependency bootstrap failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
