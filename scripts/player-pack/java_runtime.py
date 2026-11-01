"""Java 17 discovery and verified portable-runtime deployment for the player pack.

The module uses only the Python standard library.  Candidate discovery accepts
either a mapping with ``current_instance`` and ``launcher_runtime`` entries, or
a sequence whose first item is the explicitly configured instance Java path
and whose remaining items are launcher/runtime candidates.  Each entry may be
a path or a mapping containing ``path``.  A directory is resolved to its
``bin/java.exe``; an executable path may be ``java.exe`` or ``javaw.exe``.

``manifest_java`` for :func:`deploy_bundled_java17` has these fields:

* ``archive``: package-relative ZIP path
* ``sha256`` and ``sizeBytes``: complete ZIP identity
* ``rootDirectory``: the single top-level JDK directory in that ZIP
* ``javaVersion``: a Java 17 version string
* ``filesManifest``: package-relative JSON path or ZIP-relative metadata path

The files manifest is either ``{"files": [{"path": "bin/java.exe",
"sizeBytes": 1, "sha256": "..."}, ...]}`` or a mapping from relative file
paths to objects with ``sizeBytes`` and ``sha256``. File paths may be relative
to ``rootDirectory`` or include that root prefix. The files-manifest file, if
stored in the ZIP, is metadata and is not installed as part of the JDK.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import time
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable, Mapping


_WINDOWS_REPARSE_POINT = 0x0400
_MAX_FILES = 100_000
_MAX_UNCOMPRESSED_BYTES = 4 * 1024**3
_CHUNK_SIZE = 1024 * 1024
_CHILD_ENV_ALLOWLIST = {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP"}


def _safe_relative(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError(f"{label} must be a non-empty POSIX relative path")
    if value.startswith("/") or re.match(r"^[A-Za-z]:", value):
        raise ValueError(f"{label} must be relative")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise ValueError(f"{label} contains an unsafe path")
    for part in path.parts:
        if any(ord(char) < 32 or char in '<>:"|?*' for char in part) or part.endswith((" ", ".")):
            raise ValueError(f"{label} contains a Windows-unsafe path")
        if part.split(".", 1)[0].upper() in {
            "CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
            *(f"LPT{i}" for i in range(1, 10)),
        }:
            raise ValueError(f"{label} contains a reserved Windows path")
    return path.as_posix()


def _check_existing_ancestors(path: Path) -> Path:
    """Reject symlink/reparse ancestry without resolving away the lexical path."""
    full = Path(os.path.abspath(os.fspath(path)))
    current = Path(full.anchor)
    for part in full.parts[1:] if full.anchor else full.parts:
        current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError:
            break
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & _WINDOWS_REPARSE_POINT:
            raise ValueError("path contains a symbolic link or reparse point")
    return full


def _contained(root: Path, relative: str, label: str) -> Path:
    rel = _safe_relative(relative, label)
    root_full = _check_existing_ancestors(root)
    target = _check_existing_ancestors(root_full.joinpath(*PurePosixPath(rel).parts))
    try:
        if os.path.commonpath((os.path.normcase(str(root_full)), os.path.normcase(str(target)))) != os.path.normcase(str(root_full)):
            raise ValueError(f"{label} escapes its root")
    except ValueError as exc:
        raise ValueError(f"{label} escapes its root") from exc
    return target


def _file_identity(path: Path) -> tuple[int, str]:
    _check_existing_ancestors(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError("expected a regular file")
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(_CHUNK_SIZE)
            if not block:
                break
            total += len(block)
            digest.update(block)
    if total != info.st_size:
        raise ValueError("file changed while being checked")
    return total, digest.hexdigest().upper()


def _hash_matches(path: Path, size: int, digest: str) -> bool:
    try:
        actual_size, actual_digest = _file_identity(path)
    except (OSError, ValueError):
        return False
    return actual_size == size and actual_digest == digest.upper()


def _java_executable(path: os.PathLike[str] | str) -> Path | None:
    candidate = Path(path).expanduser()
    try:
        candidate = _check_existing_ancestors(candidate)
        if candidate.is_dir():
            candidate = candidate / "bin" / "java.exe"
        elif candidate.name.casefold() == "javaw.exe":
            candidate = candidate.with_name("java.exe")
        elif candidate.name.casefold() != "java.exe":
            return None
        candidate = _check_existing_ancestors(candidate)
        if candidate.is_file() and candidate.name.casefold() == "java.exe":
            return candidate
    except (OSError, ValueError):
        return None
    return None


def inspect_java17(path: os.PathLike[str] | str) -> dict[str, Any] | None:
    """Return verified Java 17 x64 details, or ``None`` for an unusable candidate.

    The candidate is executed with a sanitized environment, no shell, hidden
    console window on Windows, and an eight-second timeout. Captured output is
    parsed only for the two required properties and is never returned.
    """
    executable = _java_executable(path)
    if executable is None:
        return None
    # Build a minimal child environment. Never inherit arbitrary user or launcher
    # variables, including JVM option injection variables or credentials.
    env: dict[str, str] = {}
    for key in _CHILD_ENV_ALLOWLIST:
        if key in os.environ:
            env[key] = os.environ[key]

    def query(candidate: Path) -> dict[str, str] | None:
        kwargs: dict[str, Any] = {
            "args": [str(candidate), "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
                     "-Dsun.stderr.encoding=UTF-8", "-XshowSettings:properties", "-version"],
            "stdin": subprocess.DEVNULL,
            "stdout": subprocess.PIPE,
            "stderr": subprocess.PIPE,
            "shell": False,
            "env": env,
            "timeout": 8,
            "check": False,
        }
        if os.name == "nt":
            kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            kwargs["startupinfo"] = startupinfo
        try:
            result = subprocess.run(**kwargs)
            if result.returncode != 0:
                return None
            output = (result.stdout or b"") + b"\n" + (result.stderr or b"")
            output_text = output.decode("utf-8", errors="replace")
        except (OSError, subprocess.SubprocessError):
            return None
        properties: dict[str, str] = {}
        for prop in ("java.specification.version", "sun.arch.data.model", "os.arch",
                     "java.version", "java.runtime.version", "java.home"):
            match = re.search(rf"(?m)^\s*{re.escape(prop)}\s*=\s*(.*?)\s*$", output_text)
            if match:
                properties[prop] = match.group(1).strip().strip('"')
        return properties

    properties = query(executable)
    if properties is None:
        return None

    def is_java17_x64(values: Mapping[str, str]) -> bool:
        spec = values.get("java.specification.version")
        model = values.get("sun.arch.data.model", "").casefold()
        arch = values.get("os.arch", "").casefold()
        actual = values.get("java.version") or values.get("java.runtime.version", "")
        return (
            spec == "17"
            and model in {"64", "amd64"}
            and (not arch or arch in {"amd64", "x86_64", "x64"})
            and bool(re.match(r"^17(?:\.|$)", actual))
            and bool(values.get("java.home"))
        )

    if not is_java17_x64(properties):
        return None
    try:
        reported_home = _check_existing_ancestors(Path(properties["java.home"]).expanduser())
        if not reported_home.is_dir():
            return None
        real_java = _check_existing_ancestors(reported_home / "bin" / "java.exe")
        real_javaw = _check_existing_ancestors(reported_home / "bin" / "javaw.exe")
        if not real_java.is_file() or not real_javaw.is_file():
            return None
        # A launcher shim can print its own parent's path. Verify a distinct
        # java.home executable directly and base the returned paths on that home.
        if os.path.normcase(os.path.abspath(real_java)) != os.path.normcase(os.path.abspath(executable)):
            second = query(real_java)
            if second is None or not is_java17_x64(second):
                return None
            second_home = _check_existing_ancestors(Path(second["java.home"]).expanduser())
            if os.path.normcase(os.path.abspath(second_home)) != os.path.normcase(os.path.abspath(reported_home)):
                return None
            properties = second
    except (OSError, ValueError):
        return None
    actual_version = properties.get("java.version") or properties.get("java.runtime.version")
    if not actual_version:
        return None
    return {
        "java": str(real_java),
        "javaw": str(real_javaw),
        "javaHome": str(reported_home),
        "javaVersion": actual_version,
        "architecture": "x64",
        "source": "candidate",
    }


def _candidate_paths(candidates: Any) -> tuple[list[tuple[Any, str]], list[tuple[Any, str]]]:
    """Normalize candidate sources into explicit-instance and launcher groups."""
    if isinstance(candidates, Mapping):
        explicit_value = candidates.get("current_instance", candidates.get("instance"))
        launcher_value = candidates.get("launcher_runtime", candidates.get("launcher", []))
        explicit = _as_candidate_list(explicit_value, "current_instance")
        launcher = _as_candidate_list(launcher_value, "launcher_runtime")
        return explicit, launcher
    values = _as_candidate_list(candidates, "candidates")
    # A bare list's first item has the strongest contract: the current instance's
    # configured Java. Remaining items are bounded launcher/runtime candidates.
    return values[:1], values[1:]


def _as_candidate_list(value: Any, source: str) -> list[tuple[Any, str]]:
    if value is None:
        return []
    if isinstance(value, (str, os.PathLike, Mapping)):
        values = [value]
    else:
        try:
            values = list(value)
        except TypeError:
            values = [value]
    result: list[tuple[Any, str]] = []
    for row in values:
        if isinstance(row, Mapping):
            path = row.get("path")
            label = str(row.get("source") or source)
        else:
            path, label = row, source
        if path:
            result.append((path, label))
    return result


def choose_existing_java17(candidates: Any) -> dict[str, Any] | None:
    """Find Java 17 x64 by configured-instance, JAVA_HOME, PATH, then launcher.

    ``candidates`` may be ``{"current_instance": path,
    "launcher_runtime": [paths...]}``. A bare sequence treats its first item as
    the configured instance path and the rest as launcher/runtime candidates.
    """
    explicit, launcher = _candidate_paths(candidates)
    ordered: list[tuple[Any, str]] = [(path, "current_instance") for path, _ in explicit]
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        ordered.append((Path(java_home) / "bin" / "java.exe", "JAVA_HOME"))
    path_java = shutil.which("java")
    if path_java:
        ordered.append((path_java, "PATH"))
    ordered.extend(launcher)
    seen: set[str] = set()
    for candidate, source in ordered:
        try:
            key = os.path.normcase(os.path.abspath(os.fspath(candidate)))
        except (TypeError, ValueError, OSError):
            continue
        if key in seen:
            continue
        seen.add(key)
        info = inspect_java17(candidate)
        if info is not None:
            info["source"] = source
            return info
    return None


def _parse_files_manifest(data: Any, root_directory: str) -> dict[str, tuple[int, str]]:
    if isinstance(data, dict) and isinstance(data.get("files"), list):
        rows = data["files"]
    elif isinstance(data, dict):
        rows = []
        for file_path, identity in data.items():
            if file_path in {"schemaVersion", "rootDirectory", "totalBytes"}:
                continue
            if not isinstance(identity, dict):
                raise ValueError("files manifest entries must be objects")
            rows.append({"path": file_path, **identity})
    else:
        raise ValueError("files manifest must be an object")
    allowed: dict[str, tuple[int, str]] = {}
    folded: set[str] = set()
    total = 0
    root_fold = root_directory.casefold() + "/"
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("files manifest contains a non-object entry")
        rel = _safe_relative(row.get("path"), "files manifest path")
        if rel.casefold().startswith(root_fold):
            rel = rel[len(root_directory) + 1:]
        if rel.casefold() == root_directory.casefold():
            raise ValueError("files manifest cannot declare the JDK root as a file")
        size = row.get("sizeBytes")
        digest = row.get("sha256")
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise ValueError("files manifest contains an invalid sizeBytes")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
            raise ValueError("files manifest contains an invalid SHA-256")
        key = rel.casefold()
        if key in folded:
            raise ValueError("files manifest contains duplicate/case-colliding paths")
        folded.add(key)
        allowed[rel] = (size, digest.upper())
        total += size
        if len(allowed) > _MAX_FILES or total > _MAX_UNCOMPRESSED_BYTES:
            raise ValueError("files manifest exceeds safe deployment limits")
    required = {"bin/java.exe", "bin/javaw.exe", "release"}
    if not required.issubset({name.casefold() for name in allowed}):
        raise ValueError("files manifest must declare bin/java.exe, bin/javaw.exe, and release")
    return allowed


def _safe_zip_index(
    zf: zipfile.ZipFile,
    root_directory: str,
    manifest_name: str | None,
    allowed_files: Mapping[str, tuple[int, str]],
) -> dict[str, zipfile.ZipInfo]:
    index: dict[str, zipfile.ZipInfo] = {}
    total = 0
    root_prefix = root_directory.casefold() + "/"
    legal_directories = {root_directory.casefold()}
    for rel in allowed_files:
        for parent in PurePosixPath(rel).parents:
            if parent.as_posix() != ".":
                legal_directories.add(f"{root_directory}/{parent.as_posix()}".casefold())
    for info in zf.infolist():
        raw = info.filename
        if not raw or "\\" in raw:
            raise ValueError("ZIP contains an invalid path")
        is_dir = raw.endswith("/")
        canonical = _safe_relative(raw[:-1] if is_dir else raw, "ZIP entry")
        folded = canonical.casefold()
        if folded in index:
            raise ValueError("ZIP contains duplicate or case-colliding paths")
        mode = (info.external_attr >> 16) & 0xFFFF
        file_type = stat.S_IFMT(mode)
        if file_type == stat.S_IFLNK or file_type not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise ValueError("ZIP contains a link or special file")
        is_control_manifest = manifest_name is not None and folded == manifest_name.casefold()
        if not is_control_manifest and folded != root_directory.casefold() and not folded.startswith(root_prefix):
            raise ValueError("ZIP entry is outside rootDirectory")
        if is_dir and not is_control_manifest and folded not in legal_directories:
            raise ValueError("ZIP contains a directory outside declared file prefixes")
        if not is_dir:
            total += info.file_size
            if total > _MAX_UNCOMPRESSED_BYTES:
                raise ValueError("ZIP exceeds safe uncompressed size limit")
        index[folded] = info
    if manifest_name:
        name = _safe_relative(manifest_name, "filesManifest")
        if name.casefold() not in index:
            raise ValueError("filesManifest entry is missing from ZIP")
    return index


def _read_json_bytes(raw: bytes, label: str) -> Any:
    try:
        return json.loads(raw.decode("utf-8-sig"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is not valid UTF-8 JSON") from exc


def _manifest_relative(manifest_java: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        value = manifest_java.get(key)
        if value is not None:
            return _safe_relative(value, key)
    raise ValueError(f"manifest_java is missing {keys[0]}")


def _make_directory(root: Path, target: Path) -> None:
    root_full = _check_existing_ancestors(root)
    target_full = _check_existing_ancestors(target)
    try:
        target_full.relative_to(root_full)
    except ValueError as exc:
        raise ValueError("directory target escapes game directory") from exc
    current = root_full
    if not current.exists():
        current.mkdir(parents=True)
    for part in target_full.relative_to(root_full).parts:
        current = current / part
        if current.exists():
            _check_existing_ancestors(current)
            if not current.is_dir():
                raise ValueError("expected a directory but found a file")
        else:
            current.mkdir()


def _release_is_java17(path: Path, expected_version: str) -> bool:
    try:
        _check_existing_ancestors(path)
        if not path.is_file() or path.stat().st_size > 1024 * 1024:
            return False
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    match = re.search(r'(?m)^JAVA_VERSION="([^"]+)"\s*$', text)
    if not match:
        return False
    actual = match.group(1)
    return actual.split(".", 1)[0] == "17" and expected_version.split(".", 1)[0] == "17"


def _target_state(
    target: Path,
    allowed: Mapping[str, tuple[int, str]],
    expected_version: str,
) -> tuple[bool, bool, list[Path]]:
    """Return validity, conflict state, and verified interrupted temp files."""
    if not target.exists():
        return False, False, []
    _check_existing_ancestors(target)
    if not target.is_dir():
        return False, True, []
    expected = {name.casefold(): identity for name, identity in allowed.items()}
    allowed_directories = set()
    for name in allowed:
        for parent in PurePosixPath(name).parents:
            if parent.as_posix() != ".":
                allowed_directories.add(parent.as_posix().casefold())
    found: set[str] = set()
    managed_temps: list[Path] = []
    conflict = False
    for folder, dirs, files in os.walk(target, followlinks=False):
        directory = Path(folder)
        for dirname in dirs:
            child = directory / dirname
            try:
                _check_existing_ancestors(child)
                if child.relative_to(target).as_posix().casefold() not in allowed_directories:
                    conflict = True
            except (OSError, ValueError):
                conflict = True
        for filename in files:
            child = directory / filename
            try:
                _check_existing_ancestors(child)
                rel = child.relative_to(target).as_posix()
                identity = expected.get(rel.casefold())
                temporary = re.fullmatch(r"(.+)\.install-[0-9]+-[0-9]+", rel, flags=re.IGNORECASE)
                if temporary:
                    base = temporary.group(1).casefold()
                    temp_identity = expected.get(base)
                    if temp_identity is None or not _hash_matches(child, *temp_identity):
                        conflict = True
                    else:
                        managed_temps.append(child)
                    continue
                if identity is None:
                    conflict = True
                    continue
                found.add(rel.casefold())
                if not _hash_matches(child, *identity):
                    conflict = True
            except (OSError, ValueError):
                conflict = True
    complete = not conflict and found == set(expected)
    if complete:
        complete = _release_is_java17(target / "release", expected_version)
        # Independently confirm the executable reports the required version and architecture.
        complete = complete and inspect_java17(target / "bin" / "java.exe") is not None
    return complete, conflict, managed_temps


def deploy_bundled_java17(
    package: os.PathLike[str] | str,
    game_dir: os.PathLike[str] | str,
    manifest_java: Mapping[str, Any],
    state_callback: Callable[[dict[str, Any]], None] | None = None,
) -> dict[str, Any]:
    """Verify and deploy the manifest-authorized Java 17 x64 runtime.

    Files are written through a sibling temporary file and committed one at a
    time with ``os.replace``. Existing matching files are retained, which lets
    an interrupted install resume; conflicting content is never overwritten.
    ``state_callback``, when supplied, receives small status dictionaries and
    is never given archive contents or environment details.
    """
    if not isinstance(manifest_java, Mapping):
        raise ValueError("manifest_java must be an object")
    archive_rel = _manifest_relative(manifest_java, "archive", "archivePath", "file")
    files_manifest_rel = _manifest_relative(manifest_java, "filesManifest", "filesManifestPath", "files_manifest")
    root_directory = _safe_relative(manifest_java.get("rootDirectory"), "rootDirectory")
    if "/" in root_directory:
        raise ValueError("rootDirectory must be a single top-level directory")
    expected_version = manifest_java.get("javaVersion")
    archive_digest = manifest_java.get("sha256")
    archive_size = manifest_java.get("sizeBytes")
    if not isinstance(expected_version, str) or not re.match(r"^17(?:\.|$)", expected_version):
        raise ValueError("manifest javaVersion must identify Java 17")
    if isinstance(archive_size, bool) or not isinstance(archive_size, int) or archive_size < 0:
        raise ValueError("manifest sizeBytes is invalid")
    if not isinstance(archive_digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", archive_digest):
        raise ValueError("manifest sha256 is invalid")
    package_root = _check_existing_ancestors(Path(package))
    game_root = _check_existing_ancestors(Path(game_dir))
    if not package_root.is_dir() or not game_root.is_dir():
        raise ValueError("package and game_dir must be existing directories")
    archive_path = _contained(package_root, archive_rel, "Java archive")
    archive_actual_size, archive_actual_hash = _file_identity(archive_path)
    if archive_actual_size != archive_size or archive_actual_hash != archive_digest.upper():
        raise ValueError("Java archive size or SHA-256 does not match the installation manifest")

    root_parent = game_root / "IC2-NewEra-runtime"
    target = root_parent / "java17"
    target = _check_existing_ancestors(target)

    with zipfile.ZipFile(archive_path, "r") as zf:
        # The files manifest can be a package-side companion or ZIP metadata.
        external_manifest: Path | None = None
        try:
            external_manifest = _contained(package_root, files_manifest_rel, "Java files manifest")
            if not external_manifest.is_file():
                external_manifest = None
        except (OSError, ValueError):
            external_manifest = None
        zip_names = {info.filename.casefold(): info for info in zf.infolist()}
        zip_manifest_name = files_manifest_rel if files_manifest_rel.casefold() in zip_names else None
        if zip_manifest_name:
            control_info = zip_names[zip_manifest_name.casefold()]
            if control_info.file_size > 16 * 1024 * 1024:
                raise ValueError("Java files manifest is too large")
            files_data = _read_json_bytes(zf.read(control_info), "Java files manifest")
        elif external_manifest is not None:
            if external_manifest.stat().st_size > 16 * 1024 * 1024:
                raise ValueError("Java files manifest is too large")
            files_data = _read_json_bytes(external_manifest.read_bytes(), "Java files manifest")
        else:
            raise ValueError("Java files manifest was not found")
        allowed = _parse_files_manifest(files_data, root_directory)
        index = _safe_zip_index(zf, root_directory, zip_manifest_name, allowed)
        declared_archive = {f"{root_directory}/{rel}".casefold(): identity for rel, identity in allowed.items()}
        control_key = zip_manifest_name.casefold() if zip_manifest_name else None
        archive_files: dict[str, zipfile.ZipInfo] = {}
        for key, info in index.items():
            if info.is_dir():
                continue
            if key == control_key:
                continue
            if key not in declared_archive:
                raise ValueError("Java ZIP contains an undeclared file")
            archive_files[key] = info
        if set(archive_files) != set(declared_archive):
            raise ValueError("Java ZIP and files manifest do not declare the same payload files")
        for key, info in archive_files.items():
            expected_size, _ = declared_archive[key]
            if info.file_size != expected_size:
                raise ValueError("Java ZIP entry size differs from files manifest")

        complete, conflict, interrupted_temps = _target_state(target, allowed, expected_version)
        # Only remove interrupted files carrying this module's exact temp-name
        # pattern after their bytes match the corresponding declared payload.
        for temporary in interrupted_temps:
            temporary.unlink()
        if interrupted_temps:
            complete, conflict, _ = _target_state(target, allowed, expected_version)
        if complete:
            result = {"status": "reused", "java": str(target / "bin" / "java.exe"),
                      "javaw": str(target / "bin" / "javaw.exe"), "javaHome": str(target),
                      "javaVersion": expected_version, "architecture": "x64"}
            if state_callback:
                state_callback({"status": "reused", "filesCompleted": len(allowed), "filesTotal": len(allowed)})
            return result
        if conflict:
            raise ValueError("Java runtime target contains conflicting or unmanaged files; refusing to overwrite")

        missing_bytes = 0
        for rel, (size, digest) in allowed.items():
            destination = _contained(target, rel, "Java runtime file")
            if not destination.exists():
                missing_bytes += size
            elif not _hash_matches(destination, size, digest):
                raise ValueError("Java runtime destination has conflicting content; refusing to overwrite")
        required_free = missing_bytes + 1024**3
        free_bytes = shutil.disk_usage(game_root).free
        if free_bytes < required_free:
            raise ValueError("not enough free disk space for the missing Java runtime files plus 1 GiB")

        _make_directory(game_root, root_parent)
        _make_directory(game_root, target)
        completed = 0
        for rel, (size, digest) in allowed.items():
            destination = _contained(target, rel, "Java runtime file")
            parent_rel = PurePosixPath(rel).parent.as_posix()
            if parent_rel != ".":
                _make_directory(target, target.joinpath(*PurePosixPath(parent_rel).parts))
            if destination.exists():
                if _hash_matches(destination, size, digest):
                    completed += 1
                    continue
                raise ValueError("Java runtime destination has conflicting content; refusing to overwrite")
            temp = destination.with_name(destination.name + f".install-{os.getpid()}-{time.time_ns()}")
            entry = archive_files[f"{root_directory}/{rel}".casefold()]
            file_digest = hashlib.sha256()
            written = 0
            try:
                with zf.open(entry, "r") as source, temp.open("xb") as sink:
                    while True:
                        block = source.read(_CHUNK_SIZE)
                        if not block:
                            break
                        written += len(block)
                        if written > size:
                            raise ValueError("Java ZIP entry exceeded its declared size")
                        file_digest.update(block)
                        sink.write(block)
                    sink.flush()
                    os.fsync(sink.fileno())
                if written != size or file_digest.hexdigest().upper() != digest:
                    raise ValueError("Java runtime file failed size or SHA-256 verification")
                _check_existing_ancestors(temp)
                if destination.exists():
                    raise ValueError("Java runtime destination appeared during deployment; refusing to overwrite")
                os.replace(temp, destination)
            finally:
                try:
                    temp.unlink()
                except FileNotFoundError:
                    pass
            completed += 1
            if state_callback:
                state_callback({"status": "installing", "filesCompleted": completed, "filesTotal": len(allowed)})

    if not _release_is_java17(target / "release", expected_version):
        raise ValueError("deployed runtime release file does not identify Java 17")
    verified = inspect_java17(target / "bin" / "java.exe")
    if verified is None:
        raise ValueError("deployed java.exe did not verify as Java 17 x64")
    if not (target / "bin" / "javaw.exe").is_file():
        raise ValueError("deployed runtime is missing javaw.exe")
    result = {"status": "deployed", "java": str(target / "bin" / "java.exe"),
              "javaw": str(target / "bin" / "javaw.exe"), "javaHome": str(target),
              "javaVersion": verified["javaVersion"], "architecture": "x64"}
    if state_callback:
        state_callback({"status": "complete", "filesCompleted": len(allowed), "filesTotal": len(allowed)})
    return result

