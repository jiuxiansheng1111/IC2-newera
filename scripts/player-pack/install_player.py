#!/usr/bin/env python3
"""Install an IC2: New Era player pack into an existing dedicated Forge instance.

Standard-library only. This file is copied into the pack's installer/ directory.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tomllib
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO
from java_runtime import choose_existing_java17, deploy_bundled_java17
from java_launcher import (inspect_launcher, bounded_java_candidates,
                           configure_launcher_java, pcl_settings_for_runtime)


CHUNK_SIZE = 1024 * 1024
MIN_FREE_BYTES = 10 * 1024**3
MAX_VOICE_MANIFEST_BYTES = 128 * 1024**2
MAX_VOICE_FILES = 250_000
MAX_VOICE_TOTAL_BYTES = 64 * 1024**3
INSTALL_DIR_NAME = "IC2-NewEra-install"
ALLOWED_HOSTS = {
    "edge.forgecdn.net",
    "mediafilez.forgecdn.net",
    "cdn.modrinth.com",
    "api.modrinth.com",
    "maven.theillusivec4.top",
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
}
WINDOWS_REPARSE_POINT = 0x0400


class InstallError(Exception):
    pass


def package_root() -> Path:
    here = Path(__file__).absolute()
    if here.parent.name.casefold() == "installer":
        return here.parent.parent
    # Source checkout: scripts/player-pack/install_player.py
    return here.parent.parent.parent


def load_json(path: Path, label: str) -> Any:
    try:
        with path.open("r", encoding="utf-8-sig") as f:
            return json.load(f)
    except FileNotFoundError as exc:
        raise InstallError(f"找不到{label}：{path}") from exc
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise InstallError(f"无法读取{label}：{path}（{exc}）") from exc


def safe_absolute(path: Path, must_exist: bool = False) -> Path:
    """Return an absolute lexical path after rejecting reparse points in its ancestry."""
    full = Path(os.path.abspath(os.fspath(path.expanduser())))
    anchor = Path(full.anchor)
    current = anchor
    parts = full.parts[1:] if full.anchor else full.parts
    for part in parts:
        current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError:
            if must_exist:
                raise InstallError(f"路径不存在：{current}")
            break
        except OSError as exc:
            raise InstallError(f"无法检查路径：{current}（{exc}）") from exc
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & WINDOWS_REPARSE_POINT:
            raise InstallError(f"路径或其上级包含符号链接/重解析点，已拒绝：{current}")
    if must_exist and not full.exists():
        raise InstallError(f"路径不存在：{full}")
    return full


def relative_posix(value: str, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise InstallError(f"{label}必须是非空的POSIX相对路径")
    if value.startswith("/") or re.match(r"^[A-Za-z]:", value):
        raise InstallError(f"{label}不能是绝对路径：{value}")
    p = PurePosixPath(value)
    if any(x in ("", ".", "..") for x in p.parts) or p.is_absolute():
        raise InstallError(f"{label}包含不安全路径段：{value}")
    for part in p.parts:
        stem = part.split(".", 1)[0].upper()
        if (
            any(ord(ch) < 32 or ch in '<>:"|?*' for ch in part)
            or part.endswith((" ", "."))
            or stem in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}
        ):
            raise InstallError(f"{label}包含Windows不安全的路径段：{value}")
    return p.as_posix()


def contained_path(root: Path, relative: str, label: str, allow_missing: bool = True) -> Path:
    rel = relative_posix(relative, label)
    target = root.joinpath(*PurePosixPath(rel).parts)
    # Check the root and every existing path segment, including the final file.
    safe_absolute(root, must_exist=True)
    safe_absolute(target, must_exist=not allow_missing)
    try:
        if os.path.commonpath((os.path.abspath(root), os.path.abspath(target))) != os.path.abspath(root):
            raise InstallError(f"{label}越出所选目录：{relative}")
    except ValueError as exc:
        raise InstallError(f"{label}不在所选目录内：{relative}") from exc
    return target


def mkdir_safe(root: Path, directory: Path) -> None:
    safe_absolute(root, must_exist=True)
    safe_absolute(directory)
    try:
        directory.relative_to(root)
    except ValueError as exc:
        raise InstallError(f"创建目录目标越出实例：{directory}") from exc
    current = root
    for component in directory.relative_to(root).parts:
        current = current / component
        if current.exists():
            safe_absolute(current, must_exist=True)
            if not current.is_dir():
                raise InstallError(f"应为目录但发现文件：{current}")
        else:
            current.mkdir()


def sha256_stream(stream: BinaryIO, sink: BinaryIO | None = None, limit: int | None = None) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    while True:
        data = stream.read(CHUNK_SIZE)
        if not data:
            break
        size += len(data)
        if limit is not None and size > limit:
            raise InstallError("数据超过清单声明大小")
        digest.update(data)
        if sink is not None:
            sink.write(data)
    return digest.hexdigest().upper(), size


def file_identity(path: Path) -> tuple[int, str]:
    safe_absolute(path, must_exist=True)
    if not path.is_file():
        raise InstallError(f"不是普通文件：{path}")
    size = path.stat().st_size
    with path.open("rb") as f:
        digest, counted = sha256_stream(f)
    if size != counted:
        raise InstallError(f"文件读取期间大小发生变化：{path}")
    return size, digest


def verify_file(path: Path, size_bytes: int, sha256: str, label: str) -> None:
    if not isinstance(size_bytes, int) or size_bytes < 0:
        raise InstallError(f"{label}的sizeBytes无效")
    if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", sha256):
        raise InstallError(f"{label}的SHA256无效")
    size, actual_hash = file_identity(path)
    if size != size_bytes or actual_hash != sha256.upper():
        raise InstallError(f"{label}校验失败：{path}\n  清单大小/哈希：{size_bytes} / {sha256}\n  实际大小/哈希：{size} / {actual_hash}")


def json_write_atomic(path: Path, value: Any) -> None:
    safe_absolute(path.parent, must_exist=True)
    safe_absolute(path)
    tmp = path.with_name(path.name + f".tmp-{os.getpid()}")
    try:
        with tmp.open("x", encoding="utf-8", newline="\n") as f:
            json.dump(value, f, ensure_ascii=False, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        safe_absolute(tmp, must_exist=True)
        os.replace(tmp, path)
    except FileExistsError:
        raise InstallError(f"临时文件已存在，拒绝覆盖：{tmp}")
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass


def write_state(state_path: Path, state: dict[str, Any]) -> None:
    state["updatedAtLocal"] = time.strftime("%Y-%m-%d %H:%M:%S")
    json_write_atomic(state_path, state)


def validate_manifest(root: Path) -> dict[str, Any]:
    manifest_path = contained_path(root, "install-manifest.json", "安装清单", allow_missing=False)
    manifest = load_json(manifest_path, "安装清单")
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
        raise InstallError("安装清单schemaVersion必须为1")
    if manifest.get("minecraft") != "1.20.1" or manifest.get("forge") != "47.4.28" or manifest.get("java") != 17:
        raise InstallError("安装清单平台必须是Minecraft 1.20.1 / Forge 47.4.28 / Java 17")
    if not isinstance(manifest.get("packVersion"), str) or not manifest["packVersion"].strip():
        raise InstallError("安装清单缺少packVersion")
    bundled_java = manifest.get("bundledJava")
    if not isinstance(bundled_java, dict) or bundled_java.get("architecture") != "x64":
        raise InstallError("安装清单缺少内置Java17 x64。")
    for field in ("archive", "filesManifest", "sourceArchive"):
        relative_posix(bundled_java.get(field), "bundledJava." + field)
    for field in ("sha256", "filesManifestSha256", "sourceArchiveSha256"):
        if not re.fullmatch(r"[a-fA-F0-9]{64}", str(bundled_java.get(field, ""))):
            raise InstallError("内置Java清单缺少有效校验码。")
    if not re.match(r"^17(?:\.|$)", str(bundled_java.get("javaVersion", ""))):
        raise InstallError("内置Java必须为17版。")
    for field in ("sizeBytes", "filesManifestSizeBytes", "uncompressedBytes"):
        if not isinstance(bundled_java.get(field), int) or bundled_java[field] <= 0:
            raise InstallError("内置Java清单大小无效。")
    mods = manifest.get("mods")
    if not isinstance(mods, list) or len(mods) != 14:
        raise InstallError("安装清单必须列出14个模组（13个依赖和本项目JAR）")
    seen_ids: set[str] = set()
    seen_files: set[str] = set()
    bundled_count = 0
    for index, item in enumerate(mods):
        if not isinstance(item, dict):
            raise InstallError(f"mods[{index}]不是对象")
        mod_id = item.get("modId")
        filename = item.get("file")
        size = item.get("sizeBytes")
        digest = item.get("sha256")
        if not isinstance(mod_id, str) or not re.fullmatch(r"[a-zA-Z0-9_.-]+", mod_id):
            raise InstallError(f"mods[{index}].modId无效")
        if mod_id in seen_ids:
            raise InstallError(f"安装清单中重复modId：{mod_id}")
        seen_ids.add(mod_id)
        if not isinstance(filename, str) or Path(filename).name != filename or not filename.lower().endswith(".jar"):
            raise InstallError(f"mods[{index}].file必须是单层JAR文件名")
        if filename.casefold() in seen_files:
            raise InstallError(f"安装清单中重复JAR文件名：{filename}")
        seen_files.add(filename.casefold())
        if not isinstance(size, int) or size <= 0 or not isinstance(digest, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", digest):
            raise InstallError(f"mods[{index}]缺少有效sizeBytes/SHA256")
        if "bundled" in item:
            if "source" in item:
                raise InstallError(f"{mod_id}不能同时声明source和bundled")
            relative_posix(item["bundled"], f"{mod_id}.bundled")
            bundled_count += 1
        elif "source" in item:
            validate_download_url(item["source"])
        else:
            raise InstallError(f"{mod_id}必须有官方HTTPS source或包内bundled路径")
    if bundled_count != 1:
        raise InstallError("清单必须恰好包含1个本项目bundled JAR")
    if not any(item.get("modId") == "ic2newera" and "bundled" in item for item in mods):
        raise InstallError("bundled JAR必须是本项目ic2newera模组")
    managed = manifest.get("managedFiles", [])
    if not isinstance(managed, list):
        raise InstallError("managedFiles必须为数组")
    for item in managed:
        if not isinstance(item, dict):
            raise InstallError("managedFiles条目格式无效")
        relative_posix(item.get("source"), "managedFiles.source")
        relative_posix(item.get("path"), "managedFiles.path")
        if item.get("policy") not in ("create_only", "merge_emc"):
            raise InstallError("managedFiles.policy仅支持create_only或merge_emc")
        if not isinstance(item.get("sizeBytes"), int) or not re.fullmatch(r"[a-fA-F0-9]{64}", str(item.get("sha256", ""))):
            raise InstallError("managedFiles条目缺少有效大小或SHA256")
    voice = manifest.get("voice")
    if not isinstance(voice, dict):
        raise InstallError("安装清单缺少voice对象")
    relative_posix(voice.get("archive"), "voice.archive")
    relative_posix(voice.get("manifestEntry"), "voice.manifestEntry")
    prefix = voice.get("rootPrefix")
    if prefix != "tools/robot_voice/":
        raise InstallError("voice.rootPrefix必须严格为tools/robot_voice/")
    if voice.get("manifestEntry") != "voice-files.json":
        raise InstallError("voice.manifestEntry必须严格为voice-files.json")
    relative_posix(prefix.rstrip("/"), "voice.rootPrefix")
    if not isinstance(voice.get("sizeBytes"), int) or voice["sizeBytes"] <= 0:
        raise InstallError("voice.sizeBytes无效")
    if not isinstance(voice.get("uncompressedBytes"), int) or voice["uncompressedBytes"] < 0:
        raise InstallError("voice.uncompressedBytes无效")
    if voice["uncompressedBytes"] > MAX_VOICE_TOTAL_BYTES:
        raise InstallError("voice.uncompressedBytes超过64GiB安全上限")
    if not re.fullmatch(r"[a-fA-F0-9]{64}", str(voice.get("sha256", ""))):
        raise InstallError("voice.sha256无效")
    return manifest


def validate_download_url(url: Any) -> urllib.parse.SplitResult:
    if not isinstance(url, str):
        raise InstallError("下载来源必须是HTTPS URL")
    parsed = urllib.parse.urlsplit(url)
    try:
        port = parsed.port
    except ValueError as exc:
        raise InstallError("清单下载URL端口格式无效") from exc
    if parsed.scheme.lower() != "https" or parsed.username or parsed.password or not parsed.hostname or parsed.query or parsed.fragment or port not in (None, 443):
        raise InstallError("清单下载URL必须是无凭证、无查询参数的HTTPS地址")
    if parsed.hostname.lower() not in ALLOWED_HOSTS:
        raise InstallError(f"下载域名不在作者官方域名允许列表：{parsed.hostname}")
    if parsed.hostname.lower() == "github.com" and not parsed.path.startswith("/craftorio/ic2/releases/download/"):
        raise InstallError("GitHub下载仅允许IC2R官方release asset路径")
    return parsed


def validate_redirect_url(url: str) -> None:
    """Validate redirect targets without ever including their signed query in diagnostics."""
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError as exc:
        raise InstallError("官方下载重定向地址格式无效") from exc
    host = (parsed.hostname or "").lower()
    if parsed.scheme.lower() != "https" or parsed.username or parsed.password or not host or parsed.fragment or port not in (None, 443):
        raise InstallError("官方下载重定向不是安全的HTTPS地址")
    if host not in ALLOWED_HOSTS:
        raise InstallError(f"官方下载重定向到了未允许域名：{host or '(unknown)'}")
    if parsed.query and host != "release-assets.githubusercontent.com":
        raise InstallError("只允许GitHub官方release-assets域名使用签名查询参数")
    if host == "github.com" and not parsed.path.startswith("/craftorio/ic2/releases/download/"):
        raise InstallError("GitHub重定向仅允许IC2R官方release asset路径")


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_redirect_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_download(url: str):
    validate_download_url(url)
    opener = urllib.request.build_opener(SafeRedirectHandler())
    request = urllib.request.Request(url, headers={"User-Agent": "IC2-New-Era-Player-Installer/1"})
    try:
        return opener.open(request, timeout=60)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        # URL errors may contain a GitHub signed redirect query; do not echo them.
        raise InstallError(f"官方文件下载失败：{url}（{type(exc).__name__}）") from exc


def download_verified(item: dict[str, Any], package: Path, cache: Path) -> Path:
    filename = item["file"]
    target = contained_path(cache, filename, "下载缓存")
    expected_size = item["sizeBytes"]
    expected_hash = item["sha256"].upper()
    if target.exists():
        try:
            verify_file(target, expected_size, expected_hash, f"缓存模组{item['modId']}")
            return target
        except InstallError:
            raise InstallError(f"缓存文件不匹配，拒绝覆盖：{target}。请手动移走该文件后重试。")
    partial = target.with_name(target.name + ".part")
    if partial.exists():
        raise InstallError(f"发现未完成下载文件，拒绝自动覆盖：{partial}。请手动移走后重试。")
    if "source" in item:
        offline_cache = contained_path(package, f"downloads/{filename}", f"离线模组缓存{item['modId']}")
        if offline_cache.exists():
            verify_file(offline_cache, expected_size, expected_hash, f"离线模组缓存{item['modId']}")
            with offline_cache.open("rb") as inp, partial.open("xb") as out:
                shutil.copyfileobj(inp, out, length=CHUNK_SIZE)
                out.flush()
                os.fsync(out.fileno())
            verify_file(partial, expected_size, expected_hash, f"离线缓存暂存模组{item['modId']}")
            os.replace(partial, target)
            return target
    if "bundled" in item:
        source = contained_path(package, item["bundled"], f"包内模组{item['modId']}", allow_missing=False)
        verify_file(source, expected_size, expected_hash, f"包内模组{item['modId']}")
        with source.open("rb") as inp, partial.open("xb") as out:
            shutil.copyfileobj(inp, out, length=CHUNK_SIZE)
            out.flush()
            os.fsync(out.fileno())
    else:
        response = open_download(item["source"])
        try:
            with partial.open("xb") as out:
                actual_hash, actual_size = sha256_stream(response, out, expected_size)
                out.flush()
                os.fsync(out.fileno())
            if actual_size != expected_size or actual_hash != expected_hash:
                raise InstallError(f"下载文件校验失败：{item['modId']}（期望{expected_size}/{expected_hash}，实际{actual_size}/{actual_hash}）")
        finally:
            response.close()
    verify_file(partial, expected_size, expected_hash, f"暂存模组{item['modId']}")
    os.replace(partial, target)
    return target


def jar_mod_ids(path: Path) -> set[str]:
    ids: set[str] = set()
    try:
        with zipfile.ZipFile(path) as zf:
            for name in zf.namelist():
                if name.lower().endswith("meta-inf/mods.toml"):
                    try:
                        data = tomllib.loads(zf.read(name).decode("utf-8-sig"))
                    except (OSError, RuntimeError, UnicodeError, tomllib.TOMLDecodeError, zipfile.BadZipFile):
                        continue
                    mod_tables = data.get("mods", [])
                    if isinstance(mod_tables, list):
                        ids.update(m["modId"] for m in mod_tables if isinstance(m, dict) and isinstance(m.get("modId"), str))
                    break
                if name.lower().endswith("fabric.mod.json"):
                    try:
                        data = json.loads(zf.read(name).decode("utf-8"))
                        if isinstance(data.get("id"), str):
                            ids.add(data["id"])
                    except Exception:
                        pass
                    break
    except (OSError, zipfile.BadZipFile, RuntimeError):
        return set()
    return ids


def scan_existing_mods(mods_dir: Path, mods: list[dict[str, Any]]) -> dict[str, list[Path]]:
    expected = {x["modId"]: x for x in mods}
    found: dict[str, list[Path]] = {mod_id: [] for mod_id in expected}
    if not mods_dir.exists():
        return found
    safe_absolute(mods_dir, must_exist=True)
    if not mods_dir.is_dir():
        raise InstallError(f"mods路径不是目录：{mods_dir}")
    for path in mods_dir.iterdir():
        if not path.is_file() or path.suffix.lower() != ".jar":
            continue
        safe_absolute(path, must_exist=True)
        for mod_id in jar_mod_ids(path).intersection(expected):
            found[mod_id].append(path)
    for mod_id, paths in found.items():
        item = expected[mod_id]
        for path in paths:
            if path.name.casefold() != item["file"].casefold() and "bundled" not in item:
                raise InstallError(f"已安装同modId不同文件名的模组，拒绝自动替换：{mod_id}\n  现有：{path.name}\n  包内：{item['file']}\n请先由玩家手动移走旧文件。")
        if len(paths) > 1 and "bundled" not in item:
            raise InstallError(f"mods目录存在多个{mod_id} JAR，拒绝自动处理：{', '.join(x.name for x in paths)}")
    return found


def backup_file(source: Path, backups: Path, prefix: str) -> Path:
    mkdir_safe(backups.parents[0], backups)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = backups / f"{stamp}-{prefix}-{source.name}"
    suffix = 1
    while dest.exists():
        dest = backups / f"{stamp}-{prefix}-{suffix}-{source.name}"
        suffix += 1
    safe_absolute(dest)
    shutil.copy2(source, dest)
    verify_file(dest, source.stat().st_size, file_identity(source)[1], f"备份{source.name}")
    return dest


def validate_custom_emc(document: Any, label: str) -> list[dict[str, Any]]:
    if not isinstance(document, dict):
        raise InstallError(f"{label}必须是JSON对象")
    entries = document.get("entries")
    if not isinstance(entries, list):
        raise InstallError(f"{label}.entries必须是数组")
    seen: set[str] = set()
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise InstallError(f"{label}.entries[{index}]必须是对象")
        item_id = entry.get("item")
        emc = entry.get("emc")
        if not isinstance(item_id, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", item_id):
            raise InstallError(f"{label}.entries[{index}].item必须是有效的命名空间物品ID")
        if item_id in seen:
            raise InstallError(f"{label}.entries中物品ID重复：{item_id}")
        seen.add(item_id)
        if not isinstance(emc, int) or isinstance(emc, bool) or emc < 0 or emc > 2**63 - 1:
            raise InstallError(f"{label}.entries[{index}].emc必须是非负整数")
    return entries


def merge_custom_emc(current: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
    current_entries = validate_custom_emc(current, "现有EMC配置")
    incoming_entries = validate_custom_emc(incoming, "包内EMC配置")
    incoming_by_id = {entry["item"]: entry for entry in incoming_entries}
    consumed: set[str] = set()
    merged_entries: list[dict[str, Any]] = []
    for old in current_entries:
        item_id = old["item"]
        update = incoming_by_id.get(item_id)
        if update is None:
            merged_entries.append(dict(old))
            continue
        updated = dict(old)
        updated["emc"] = update["emc"]
        merged_entries.append(updated)
        consumed.add(item_id)
    current_ids = {row["item"] for row in current_entries}
    for new in incoming_entries:
        if new["item"] not in consumed and new["item"] not in current_ids:
            merged_entries.append(dict(new))
    result = dict(current)
    result["entries"] = merged_entries
    return result


def install_mods(game_dir: Path, mods: list[dict[str, Any]], staged: dict[str, Path], backups: Path) -> None:
    mods_dir = game_dir / "mods"
    if not mods_dir.exists():
        mkdir_safe(game_dir, mods_dir)
    safe_absolute(mods_dir, must_exist=True)
    existing = scan_existing_mods(mods_dir, mods)
    for item in mods:
        mod_id = item["modId"]
        desired = contained_path(mods_dir, item["file"], f"目标JAR {mod_id}")
        source = staged[mod_id]
        matches = existing[mod_id]
        if matches and matches[0].name.casefold() == desired.name.casefold():
            # A correct current project JAR must not leave a second old project JAR.
            for extra in matches[1:]:
                safe_absolute(extra, must_exist=True)
                backup_file(extra, backups, "old-project-mod")
                extra.unlink()
            matches = matches[:1]
            current = matches[0]
            size, digest = file_identity(current)
            if size == item["sizeBytes"] and digest == item["sha256"].upper():
                continue
            if "bundled" not in item:
                raise InstallError(f"已有同名第三方JAR但哈希不同，拒绝替换：{current}")
            backup_file(current, backups, "old-project-mod")
        elif matches:
            for current in matches:
                backup_file(current, backups, "old-project-mod")
                current.unlink()
        if desired.exists():
            safe_absolute(desired, must_exist=True)
            if not desired.is_file():
                raise InstallError(f"JAR目标被非普通文件占用：{desired}")
            if "bundled" not in item:
                raise InstallError(f"拒绝覆盖同名第三方文件：{desired}")
            if not any(existing_path == desired for existing_path in matches):
                raise InstallError(f"目标文件名已被未识别文件占用，拒绝覆盖以保留未知JAR：{desired}")
        temp = desired.with_name(desired.name + f".install-{os.getpid()}")
        if temp.exists():
            raise InstallError(f"安装临时文件已存在，拒绝覆盖：{temp}")
        shutil.copy2(source, temp)
        verify_file(temp, item["sizeBytes"], item["sha256"], f"待安装模组{mod_id}")
        os.replace(temp, desired)


def install_managed_files(package: Path, game_dir: Path, manifest: dict[str, Any], backups: Path) -> None:
    for item in manifest.get("managedFiles", []):
        source = contained_path(package, item["source"], "受管文件包内来源", allow_missing=False)
        verify_file(source, item["sizeBytes"], item["sha256"], f"受管文件{item['path']}")
        target = contained_path(game_dir, item["path"], "受管文件目标")
        mkdir_safe(game_dir, target.parent)
        policy = item["policy"]
        if policy == "create_only":
            if target.exists():
                safe_absolute(target, must_exist=True)
                if not target.is_file():
                    raise InstallError(f"受管文件目标不是普通文件：{target}")
                continue
            shutil.copy2(source, target)
            verify_file(target, item["sizeBytes"], item["sha256"], f"新建{item['path']}")
        elif policy == "merge_emc":
            incoming = load_json(source, f"受管EMC文件{item['source']}")
            validate_custom_emc(incoming, "包内EMC配置")
            if target.exists():
                safe_absolute(target, must_exist=True)
                if not target.is_file():
                    raise InstallError(f"EMC目标不是普通文件：{target}")
                current = load_json(target, f"现有EMC配置{item['path']}")
                # Validate both complete documents before making a backup or writing.
                validate_custom_emc(current, "现有EMC配置")
                merged = merge_custom_emc(current, incoming)
                backup_file(target, backups, "custom-emc")
            else:
                merged = dict(incoming)
            json_write_atomic(target, merged)


def voice_expected(manifest: dict[str, Any], archive_path: Path) -> tuple[dict[str, Any], dict[str, str], dict[str, int]]:
    voice = manifest["voice"]
    verify_file(archive_path, voice["sizeBytes"], voice["sha256"], "语音独立ZIP")
    try:
        zf = zipfile.ZipFile(archive_path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise InstallError(f"语音文件不是有效ZIP：{archive_path}（{exc}）") from exc
    with zf:
        infos = zf.infolist()
        if len(infos) > MAX_VOICE_FILES + 1:
            raise InstallError(f"语音ZIP条目数超过{MAX_VOICE_FILES + 1:,}项安全上限")
        names: dict[str, zipfile.ZipInfo] = {}
        for info in infos:
            name = relative_posix(info.filename.rstrip("/"), "语音ZIP条目")
            if info.is_dir():
                raise InstallError(f"语音ZIP不允许未声明目录项：{info.filename}")
            if name.casefold() in names:
                raise InstallError(f"语音ZIP存在重复条目：{info.filename}")
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode) or stat.S_ISCHR(mode) or stat.S_ISBLK(mode) or stat.S_ISFIFO(mode):
                raise InstallError(f"语音ZIP包含链接或特殊文件：{info.filename}")
            if info.flag_bits & 0x1:
                raise InstallError(f"语音ZIP条目不能加密：{info.filename}")
            names[name.casefold()] = info
        manifest_entry = relative_posix(voice["manifestEntry"], "voice.manifestEntry")
        info = names.get(manifest_entry.casefold())
        if info is None:
            raise InstallError(f"语音ZIP缺少声明清单：{manifest_entry}")
        if info.file_size > MAX_VOICE_MANIFEST_BYTES:
            raise InstallError("voice-files.json超过128MiB安全上限")
        try:
            voice_manifest = json.loads(zf.read(info).decode("utf-8-sig"))
        except (UnicodeError, json.JSONDecodeError, OSError, RuntimeError) as exc:
            raise InstallError(f"无法读取语音清单：{exc}") from exc
        if not isinstance(voice_manifest, dict) or voice_manifest.get("schemaVersion") != 1:
            raise InstallError("voice-files.json schemaVersion必须为1")
        files = voice_manifest.get("files")
        if not isinstance(files, list) or not files:
            raise InstallError("voice-files.json files必须是非空数组")
        if len(files) > MAX_VOICE_FILES:
            raise InstallError(f"语音文件条目数超过{MAX_VOICE_FILES:,}项安全上限")
        root_prefix = voice["rootPrefix"]
        declared: dict[str, str] = {}
        declared_names: set[str] = set()
        expected_sizes: dict[str, int] = {}
        total = 0
        for entry in files:
            if not isinstance(entry, dict):
                raise InstallError("voice-files.json存在格式错误条目")
            rel = relative_posix(entry.get("path"), "voice file path")
            full = rel if rel.startswith(root_prefix) else root_prefix + rel
            full = relative_posix(full, "语音目标路径")
            if not full.startswith(root_prefix):
                raise InstallError(f"语音文件越出rootPrefix：{full}")
            if full.casefold() in declared_names or full.casefold() == manifest_entry.casefold():
                raise InstallError(f"语音清单存在重复或冲突路径：{full}")
            size = entry.get("sizeBytes")
            digest = entry.get("sha256")
            if not isinstance(size, int) or size < 0 or not isinstance(digest, str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", digest):
                raise InstallError(f"语音文件大小/哈希无效：{full}")
            declared_names.add(full.casefold())
            declared[full] = digest.upper()
            expected_sizes[full] = size
            total += size
            if total > MAX_VOICE_TOTAL_BYTES:
                raise InstallError("语音解压总字节数超过64GiB安全上限")
        if voice_manifest.get("totalBytes") != total or voice.get("uncompressedBytes") != total:
            raise InstallError("语音压缩包清单、主清单的解压总字节数不一致")
        actual_entries = {k for k in names if k != manifest_entry.casefold()}
        if actual_entries != {p.casefold() for p in declared}:
            extra = actual_entries - {p.casefold() for p in declared}
            missing = {p.casefold() for p in declared} - actual_entries
            raise InstallError(f"语音ZIP与voice-files.json不一致（未声明文件={sorted(extra)}；缺失={sorted(missing)}）")
        for rel, expected_size in expected_sizes.items():
            if names[rel.casefold()].file_size != expected_size:
                raise InstallError(f"语音ZIP声明大小与ZIP目录不一致：{rel}")
        return voice_manifest, declared, expected_sizes


def preflight_voice_target(game_dir: Path, manifest: dict[str, Any], declared: dict[str, str], expected_sizes: dict[str, int]) -> dict[str, tuple[int, int, int]]:
    root_prefix = manifest["voice"]["rootPrefix"]
    voice_root = contained_path(game_dir, root_prefix.rstrip("/"), "语音目录")
    declared_case = {p.casefold(): p for p in declared}
    # CPython honors Windows long-path support only when it is enabled by the OS.
    # Check before modifying the instance, rather than failing halfway through extraction.
    longest_relative = max(declared, key=lambda value: len(value.encode("utf-16-le")))
    longest_target = str(game_dir.joinpath(*PurePosixPath(longest_relative).parts))
    if len(longest_target.encode("utf-16-le")) // 2 >= 260:
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Control\FileSystem") as key:
                long_paths_enabled = bool(winreg.QueryValueEx(key, "LongPathsEnabled")[0])
        except (OSError, ImportError):
            long_paths_enabled = False
        if not long_paths_enabled:
            raise InstallError(r"语音文件目标路径超过Windows的260字符限制，且系统未开启长路径支持。请在较短位置新建隔离版本，例如D:\MC\.minecraft\versions\1.20.1-Forge_47.4.28；本安装器不会修改系统注册表。")
    allowed_dirs = set()
    for path in declared:
        for parent in PurePosixPath(path).parents:
            allowed_dirs.add(parent.as_posix().casefold())
    verified = {}
    missing_bytes = sum(expected_sizes.values())
    if voice_root.exists():
        safe_absolute(voice_root, must_exist=True)
        if not voice_root.is_dir():
            raise InstallError(f"语音目标不是目录：{voice_root}")
        for folder, dirs, files in os.walk(voice_root, followlinks=False):
            directory = Path(folder)
            for name in dirs:
                child = directory / name
                safe_absolute(child, must_exist=True)
                rel = child.relative_to(game_dir).as_posix().casefold()
                if rel not in allowed_dirs:
                    raise InstallError(f"语音目录含本安装器不管理的目录，请选新实例或先手动处理：{child}")
            for name in files:
                found = directory / name
                safe_absolute(found, must_exist=True)
                rel = found.relative_to(game_dir).as_posix()
                key = rel.casefold()
                if key not in declared_case:
                    raise InstallError(f"语音目录含不受本安装器管理的文件，拒绝覆盖：{found}")
                canonical = declared_case[key]
                verify_file(found, expected_sizes[canonical], declared[canonical], f"续装语音{canonical}")
                info = found.stat()
                verified[canonical] = (info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                missing_bytes -= expected_sizes[canonical]
    required_free = max(0, missing_bytes) + MIN_FREE_BYTES
    free = shutil.disk_usage(game_dir).free
    if free < required_free:
        raise InstallError(f"实例磁盘空间不足：需至少{required_free:,}字节（剩余语音文件+10GiB），当前可用{free:,}字节")
    return verified


def install_voice(game_dir: Path, archive_path: Path, manifest: dict[str, Any], state: dict[str, Any], state_path: Path,
                  prepared: tuple, verified: dict[str, tuple[int, int, int]]) -> None:
    voice = manifest["voice"]
    _voice_manifest, declared, expected_sizes = prepared
    completed = 0
    last_notice = time.monotonic()
    with zipfile.ZipFile(archive_path) as zf:
        info_map = {x.filename.casefold(): x for x in zf.infolist()}
        for rel in sorted(declared):
            if completed % 1000 == 0 and running_java_processes():
                raise InstallError("安装期间Minecraft/Java已启动，请正常关闭后重新运行续装。")
            dest = contained_path(game_dir, rel, "语音文件目标")
            if dest.exists():
                info = dest.stat()
                snapshot = (info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                if verified.get(rel) != snapshot:
                    verify_file(dest, expected_sizes[rel], declared[rel], f"现有语音{rel}")
                completed += 1
                continue
            mkdir_safe(game_dir, dest.parent)
            zip_info = info_map.get(rel.casefold())
            if zip_info is None:
                raise InstallError(f"语音ZIP内部条目丢失：{rel}")
            temp = dest.with_name(dest.name + f".install-{os.getpid()}")
            safe_absolute(temp)
            if temp.exists():
                raise InstallError(f"安装临时文件已存在，拒绝覆盖：{temp}")
            try:
                with zf.open(zip_info, "r") as inp, temp.open("xb") as out:
                    digest, size = sha256_stream(inp, out, expected_sizes[rel])
                    out.flush()
                    os.fsync(out.fileno())
                if size != expected_sizes[rel] or digest != declared[rel]:
                    raise InstallError(f"语音文件SHA256/大小不符：{rel}")
                os.replace(temp, dest)
            finally:
                try:
                    temp.unlink()
                except FileNotFoundError:
                    pass
            completed += 1
            if time.monotonic() - last_notice >= 10:
                print(f"正在安装语音文件：{completed:,}/{len(declared):,}", flush=True)
                last_notice = time.monotonic()
    state["voiceInstalled"] = True
    state["voiceArchiveSha256"] = voice["sha256"].upper()
    state["voiceUncompressedBytes"] = voice["uncompressedBytes"]
    state["voiceInferenceVerified"] = False
    state["voiceStatus"] = "files_installed_inference_not_verified"
    write_state(state_path, state)

def verify_forge_instance(game_dir: Path) -> dict[str, Any]:
    game_dir = safe_absolute(game_dir, must_exist=True)
    if not game_dir.is_dir() or game_dir.name.casefold() in {".minecraft", "mods", "saves"}:
        raise InstallError("请选择独立Forge版本目录，不要选共享.minecraft、mods或saves。")
    profile = contained_path(game_dir, game_dir.name + ".json", "所选版本JSON", allow_missing=False)
    data = load_json(profile, "所选Forge版本JSON")
    if not isinstance(data, dict):
        raise InstallError("所选版本JSON必须为对象。")
    base = str(data.get("inheritsFrom") or data.get("id") or game_dir.name)
    if not re.search(r"(^|[^0-9])1\.20\.1([^0-9]|$)", base):
        raise InstallError("版本不是Minecraft 1.20.1；旧世界与1.19.2实例不会被修改。")
    libraries = data.get("libraries", [])
    names = {row.get("name") for row in libraries if isinstance(row, dict)} if isinstance(libraries, list) else set()
    supported = {"net.minecraftforge:fmlloader:1.20.1-47.4.28", "net.minecraftforge:forge:1.20.1-47.4.28"}
    if not names.intersection(supported) or data.get("mainClass") != "cpw.mods.bootstraplauncher.BootstrapLauncher":
        raise InstallError("请选择Minecraft 1.20.1的Forge 47.4.28版本目录。")
    isolation = game_dir / "PCL" / "Setup.ini"
    if isolation.is_file():
        safe_absolute(isolation, must_exist=True)
        # Only interpret this explicit isolation flag; never copy launcher settings.
        enabled = any(line.strip().casefold() == "versionargumentindiev2:true"
                      for line in isolation.read_text(encoding="utf-8-sig", errors="replace").splitlines())
        if not enabled:
            raise InstallError("请先在PCL为所选版本开启版本隔离，再运行安装器。")
    return {"profileId": data.get("id"), "profileJson": str(profile),
            "minecraft": "1.20.1", "forge": "47.4.28"}

def running_java_processes() -> list[str]:
    if os.name != "nt":
        raise InstallError("此安装器仅支持Windows")
    class PROCESSENTRY32W(ctypes.Structure):
        _fields_ = [
            ("dwSize", ctypes.c_ulong),
            ("cntUsage", ctypes.c_ulong),
            ("th32ProcessID", ctypes.c_ulong),
            ("th32DefaultHeapID", ctypes.c_size_t),
            ("th32ModuleID", ctypes.c_ulong),
            ("cntThreads", ctypes.c_ulong),
            ("th32ParentProcessID", ctypes.c_ulong),
            ("pcPriClassBase", ctypes.c_long),
            ("dwFlags", ctypes.c_ulong),
            ("szExeFile", ctypes.c_wchar * 260),
        ]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    snapshot = kernel32.CreateToolhelp32Snapshot
    snapshot.argtypes = [ctypes.c_ulong, ctypes.c_ulong]
    snapshot.restype = ctypes.c_void_p
    first = kernel32.Process32FirstW
    first.argtypes = [ctypes.c_void_p, ctypes.POINTER(PROCESSENTRY32W)]
    first.restype = ctypes.c_int
    nxt = kernel32.Process32NextW
    nxt.argtypes = [ctypes.c_void_p, ctypes.POINTER(PROCESSENTRY32W)]
    nxt.restype = ctypes.c_int
    close = kernel32.CloseHandle
    close.argtypes = [ctypes.c_void_p]
    close.restype = ctypes.c_int
    invalid = ctypes.c_void_p(-1).value
    handle = snapshot(0x00000002, 0)
    if handle == invalid:
        raise InstallError("无法枚举Windows进程；为避免替换运行中的JAR，安装已停止")
    found: list[str] = []
    try:
        entry = PROCESSENTRY32W()
        entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
        ok = first(handle, ctypes.byref(entry))
        while ok:
            name = entry.szExeFile.lower()
            if name in ("java.exe", "javaw.exe"):
                found.append(name)
            ok = nxt(handle, ctypes.byref(entry))
    finally:
        close(handle)
    return found


def choose_game_directory(value: str | None) -> Path:
    raw = value
    while not raw:
        raw = strip_one_quote_pair(input("请输入已安装Forge 1.20.1-47.4.28的专用PCL实例目录："))
        if not raw:
            print("目录不能为空。")
    return safe_absolute(Path(raw), must_exist=True)


def strip_one_quote_pair(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] == '"':
        return value[1:-1].strip()
    return value


def choose_voice_archive(value: str | None, package: Path, voice_entry: dict[str, Any]) -> Path:
    if value:
        return safe_absolute(Path(value), must_exist=True)
    default = contained_path(package, voice_entry["archive"], "默认语音ZIP")
    if default.is_file():
        return safe_absolute(default, must_exist=True)
    # Explorer's default Extract All adds one extra enclosing directory.
    for parent in (package.parent, package.parent.parent):
        sibling = safe_absolute(parent / voice_entry["archive"])
        if sibling.is_file():
            return safe_absolute(sibling, must_exist=True)
    raw = strip_one_quote_pair(input(f"请输入与核心包分开的语音ZIP路径（默认位置不存在：{default}）："))
    if not raw:
        raise InstallError("未提供语音ZIP；安装保持未完成。需要语音时不能跳过。可再次运行或明确使用--without-voice。")
    return safe_absolute(Path(raw), must_exist=True)


def prepare_java17(package: Path, game_dir: Path, manifest: dict[str, Any], plan: dict,
                   state: dict[str, Any], state_path: Path, backups: Path) -> None:
    bundled = manifest["bundledJava"]
    files_manifest = contained_path(package, bundled["filesManifest"], "内置Java文件清单", allow_missing=False)
    verify_file(files_manifest, bundled["filesManifestSizeBytes"], bundled["filesManifestSha256"], "内置Java文件清单")
    explicit = list(plan["configuredJava"])
    owned = game_dir / "IC2-NewEra-runtime/java17/bin/java.exe"
    if owned.is_file():
        explicit.append(str(owned))
    print("正在检测当前实例及已安装的Java17（64位）……", flush=True)
    info = choose_existing_java17({"current_instance": explicit,
                                   "launcher_runtime": bounded_java_candidates(game_dir)})
    if info and os.path.normcase(os.path.abspath(info["javaHome"])) == os.path.normcase(
            os.path.abspath(game_dir / "IC2-NewEra-runtime/java17")):
        # This installer owns the portable tree: reusing it requires every
        # declared file, not merely a java.exe that can print its version.
        info = deploy_bundled_java17(package, game_dir, bundled)
    current_selected = False
    if info:
        candidate_key = os.path.normcase(os.path.abspath(info["java"]))
        current_selected = any(candidate_key == os.path.normcase(os.path.abspath(path))
                               for path in plan["configuredJava"])
    # New PCL's folder mode deliberately stays inside this version. A current
    # explicit working selection may be kept; otherwise use the isolated bundle
    # rather than editing PCL's global Java list to register an external JDK.
    inside_instance = False
    if info:
        try:
            Path(info["java"]).relative_to(game_dir)
            inside_instance = True
        except ValueError:
            pass
    legacy_four_part = bool(info and plan["kind"] == "pcl" and not plan.get("newJavaModePresent")
                            and re.match(r"^\d+\.\d+\.\d+\.\d+", str(info["javaVersion"])))
    if info and not legacy_four_part and (current_selected or plan["kind"] == "hmcl" or inside_instance):
        info["status"] = "reused_existing"
        print("已找到可用的Java17，复用现有环境。", flush=True)
    else:
        if legacy_four_part:
            print("旧版PCL可能误判当前Java补丁号，改用包内兼容的Java17。", flush=True)
        elif info:
            state["java17ExternalDetected"] = True
            print("检测到外部Java17；为兼容PCL新旧版本，使用本实例内的便携Java。", flush=True)
        else:
            print("未找到可直接使用的Java17，正在从安装包部署，无需下载。", flush=True)
        def update_java(progress: dict) -> None:
            if progress.get("status") != "installing":
                state["java17Progress"] = progress
                write_state(state_path, state)
        info = deploy_bundled_java17(package, game_dir, bundled, update_java)
        current_selected = False
    state["java17"] = info
    state["java17Ready"] = True
    write_state(state_path, state)
    if current_selected:
        configuration = {"kind": plan["kind"], "configured": True, "modified": False,
                         "preservedVerifiedCurrentSelection": True, "restartLauncherRequired": False}
    else:
        values = pcl_settings_for_runtime(info) if plan["kind"] == "pcl" else None
        configuration = configure_launcher_java(game_dir, info, backups, plan, values)
    state["java17Launcher"] = configuration
    state["java17Configured"] = bool(configuration.get("configured"))
    write_state(state_path, state)


def install(args: argparse.Namespace) -> int:
    package = safe_absolute(package_root(), must_exist=True)
    manifest = validate_manifest(package)
    game_dir = choose_game_directory(args.game_directory)
    profile = verify_forge_instance(game_dir)
    try:
        launcher_plan = inspect_launcher(game_dir, args.launcher)
    except (OSError, ValueError) as exc:
        raise InstallError("无法读取当前版本的启动器Java设置：" + str(exc)) from exc
    java = running_java_processes()
    if java:
        raise InstallError("检测到java/javaw进程正在运行。请由玩家正常关闭所有Java/Minecraft进程后重新运行；安装器不会结束进程。")
    install_dir = contained_path(game_dir, INSTALL_DIR_NAME, "安装回执目录")
    mkdir_safe(game_dir, install_dir)
    state_path = contained_path(install_dir, "install-state.json", "安装回执")
    state: dict[str, Any] = {
        "schemaVersion": 1,
        "packVersion": manifest["packVersion"],
        "minecraft": "1.20.1",
        "forge": "47.4.28",
        "profile": profile,
        "status": "incomplete",
        "voiceInstalled": False,
        "voiceInferenceVerified": False,
        "voiceStatus": "pending",
        "modsInstalled": False,
        "managedFilesApplied": False,
        "java17Ready": False,
        "java17Configured": False,
    }
    write_state(state_path, state)

    voice_path: Path | None = None
    voice_plan = None
    verified_voice = {}
    if not args.without_voice:
        try:
            voice_path = choose_voice_archive(args.voice_archive, package, manifest["voice"])
            voice_plan = voice_expected(manifest, voice_path)
            _voice_manifest, declared, expected_sizes = voice_plan
            verified_voice = preflight_voice_target(game_dir, manifest, declared, expected_sizes)
            state["voiceStatus"] = "archive_verified"
            state["voiceArchive"] = voice_path.name
        except Exception as exc:
            state["voiceStatus"] = "missing_or_invalid_archive"
            state["error"] = str(exc)
            write_state(state_path, state)
            raise
    else:
        state["voiceStatus"] = "explicitly_disabled_by_user"
        state["voiceInstalled"] = False
        state["voiceInferenceVerified"] = False
        write_state(state_path, state)

    cache = contained_path(install_dir, "cache", "缓存目录")
    backups = contained_path(install_dir, "backups", "备份目录")
    mkdir_safe(game_dir, cache)
    mkdir_safe(game_dir, backups)
    downloads = contained_path(cache, "mods", "模组缓存目录")
    mkdir_safe(game_dir, downloads)

    state["status"] = "validating_and_downloading_mods"
    write_state(state_path, state)
    staged: dict[str, Path] = {}
    try:
        state["status"] = "configuring_java17"
        write_state(state_path, state)
        try:
            prepare_java17(package, game_dir, manifest, launcher_plan, state, state_path, backups)
        except (OSError, ValueError) as exc:
            raise InstallError("Java17自动配置未完成：" + str(exc)) from exc
        if not state.get("java17Ready") or not state.get("java17Configured"):
            raise InstallError("Java17尚未配置好，停止安装。")
        state["status"] = "validating_and_downloading_mods"
        write_state(state_path, state)
        for item in manifest["mods"]:
            installed = contained_path(game_dir, "mods/" + item["file"], "已有模组")
            if installed.is_file():
                size, digest = file_identity(installed)
                if size == item["sizeBytes"] and digest == item["sha256"].upper():
                    staged[item["modId"]] = installed
                    continue
            staged[item["modId"]] = download_verified(item, package, downloads)
        # Confirm the selected dependency files actually declare the matching Forge mod ids.
        for item in manifest["mods"]:
            if item["modId"] not in jar_mod_ids(staged[item["modId"]]):
                raise InstallError(f"下载文件中未找到期望modId，拒绝安装：{item['modId']} ({item['file']})")
        # Recheck Java immediately before any files are changed.
        if running_java_processes():
            raise InstallError("下载期间检测到java/javaw进程启动。已保留缓存和未完成回执，停止修改游戏文件。")
        state["status"] = "installing_mods"
        write_state(state_path, state)
        install_mods(game_dir, manifest["mods"], staged, backups)
        state["modsInstalled"] = True
        write_state(state_path, state)
        install_managed_files(package, game_dir, manifest, backups)
        state["managedFilesApplied"] = True
        write_state(state_path, state)
        if voice_path is not None:
            state["voiceStatus"] = "installing"
            write_state(state_path, state)
            install_voice(game_dir, voice_path, manifest, state, state_path, voice_plan, verified_voice)
            voice_options_path = contained_path(game_dir, "config/ic2newera-robot-voice.json", "语音开关")
            voice_options = load_json(voice_options_path, "语音开关") if voice_options_path.exists() else {}
            if not isinstance(voice_options, dict):
                raise InstallError("语音开关配置必须是JSON对象。")
            voice_options["enabled"] = True
            json_write_atomic(voice_options_path, voice_options)
        state["status"] = "complete" if state["java17Configured"] and (state["voiceInstalled"] or args.without_voice) else "incomplete"
        state["voiceInferenceVerified"] = False
        state["finishedAtLocal"] = time.strftime("%Y-%m-%d %H:%M:%S")
        write_state(state_path, state)
    except KeyboardInterrupt:
        state["status"] = "incomplete"
        state["error"] = "用户中断；缓存和已校验文件保留，可再次运行续装。"
        write_state(state_path, state)
        raise
    except Exception as exc:
        state["status"] = "incomplete"
        state["error"] = str(exc)
        write_state(state_path, state)
        raise
    if args.without_voice:
        print("核心模组已安装，但语音按你的--without-voice参数明确未安装。")
    else:
        print("核心模组与语音文件已安装。语音推理/模型运行尚未验证。")
    print(f"回执：{state_path}")
    print("Java17已就绪并配置到所选版本；无需额外下载或设置系统JAVA_HOME。")
    if state.get("java17Launcher", {}).get("restartLauncherRequired"):
        print("请先退出并重新打开启动器，让它重新读取本版本的Java设置。")
    print("请从PCL启动此专用Minecraft 1.20.1 Forge 47.4.28实例。")
    print("安装器不会自动启动游戏。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="IC2：新纪元 1.20.1 Forge玩家安装器")
    parser.add_argument("--game-directory", help="已安装Forge 1.20.1-47.4.28的专用实例目录")
    parser.add_argument("--voice-archive", help="独立语音ZIP路径；默认查找安装清单的相邻路径")
    voice_group = parser.add_mutually_exclusive_group()
    voice_group.add_argument("--without-voice", action="store_true", help="只安装游戏，不需要语音ZIP")
    voice_group.add_argument("--with-voice", action="store_true", help="安装独立八角色三语语音ZIP")
    parser.add_argument("--launcher", choices=("auto", "pcl", "hmcl"), default="auto",
                        help="默认识别版本配置；没有标记时按PCL2处理")
    args = parser.parse_args()
    try:
        if not args.without_voice and not args.with_voice:
            if args.voice_archive:
                args.with_voice = True
            else:
                choice = input("安装选项：1 只装游戏（默认，不需要语音包）；2 同时安装八角色语音。请输入1或2：").strip()
                if choice not in {"", "1", "2"}:
                    raise InstallError("无效选择，请重新运行并输入1或2。")
                args.without_voice = choice in {"", "1"}
                args.with_voice = choice == "2"
        return install(args)
    except (InstallError, KeyboardInterrupt) as exc:
        if isinstance(exc, KeyboardInterrupt):
            print("\n安装被中断；已写入的缓存与回执保留，可校验后续装。", file=sys.stderr)
            return 130
        print(f"安装失败：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
