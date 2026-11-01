"""Per-instance launcher Java settings; never reads accounts or global settings."""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import time
from pathlib import Path
from typing import Any, Mapping


def _safe(path: Path) -> Path:
    full = Path(os.path.abspath(path))
    current = Path(full.anchor)
    for part in full.parts[1:]:
        current /= part
        try:
            info = current.lstat()
        except FileNotFoundError:
            break
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("启动器配置路径含符号链接或重解析点，拒绝修改。")
    return full


def _inside(root: Path, relative: str) -> Path:
    target = _safe(root / relative)
    target.relative_to(_safe(root))
    return target


def _read_text(path: Path) -> tuple[str, str, bytes]:
    if not path.exists():
        return "", "utf-8", b""
    _safe(path)
    if not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("启动器单版本配置文件无效或过大。")
    original = path.read_bytes()
    for marker, codec in ((b"\xef\xbb\xbf", "utf-8-sig"),
                          (b"\xff\xfe", "utf-16"), (b"\xfe\xff", "utf-16")):
        if original.startswith(marker):
            return original.decode(codec), codec, original
    try:
        return original.decode("utf-8"), "utf-8", original
    except UnicodeError:
        return original.decode("gb18030"), "gb18030", original


def _read_json(path: Path) -> dict:
    text, _encoding, _original = _read_text(path)
    if not text.strip():
        return {}
    value = json.loads(text)
    if not isinstance(value, dict):
        raise ValueError("启动器单版本配置必须是JSON对象。")
    return value


def _ini_value(text: str, name: str) -> str:
    values = []
    for line in text.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            if key.strip().casefold() == name.casefold():
                values.append(value.strip())
    return values[-1] if values else ""


def _java_path(value: Any) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    value = value.strip().strip('"')
    path = Path(value)
    if not path.is_absolute():
        return None
    if path.name.casefold() in {"java.exe", "javaw.exe"}:
        return str(path.with_name("java.exe"))
    if (path / "java.exe").is_file():
        return str(path / "java.exe")
    return str(path / "bin" / "java.exe")


def inspect_launcher(game_dir: Path, requested: str = "auto") -> dict:
    """Read only the selected version's launcher files and Java fields."""
    root = _safe(game_dir)
    pcl_ini = _inside(root, "PCL/Setup.ini")
    pcl_json = _inside(root, "PCL/config.json")
    hmcl_json = _inside(root, ".hmcl/config/instance-game-settings.json")
    hmcl_legacy = _inside(root, "hmclversion.cfg")
    if requested not in {"auto", "pcl", "hmcl"}:
        raise ValueError("启动器类型仅支持auto、pcl、hmcl。")
    if requested != "auto":
        kind = requested
    elif pcl_ini.exists() or pcl_json.exists():
        kind = "pcl"
    elif hmcl_json.exists() or hmcl_legacy.exists() or (root / ".hmcl").is_dir():
        kind = "hmcl"
    else:
        # This installer asks for a PCL version directory by default.
        kind = "pcl"
    plan: dict[str, Any] = {"kind": kind, "configuredJava": [], "configFiles": []}
    if kind == "pcl":
        if root.parent.name.casefold() != "versions":
            raise ValueError("PCL自动配置需要选择.minecraft/versions/下的实际版本文件夹。")
        text, _encoding, _original = _read_text(pcl_ini)
        mode = _ini_value(text, "VersionArgumentJavaV2")
        plan["mode"] = mode
        plan["legacyJavaSelect"] = _ini_value(text, "VersionArgumentJavaSelect")
        plan["newJavaModePresent"] = bool(mode)
        plan["configFiles"] = [str(pcl_ini)]
        # New mode 3 consumes InstanceForcedJava; legacy configurations use Path.
        if mode == "3" and pcl_json.exists():
            forced = _read_json(pcl_json).get("InstanceForcedJava")
            if isinstance(forced, dict):
                candidate = _java_path(forced.get("Folder"))
                if candidate:
                    plan["configuredJava"].append(candidate)
        legacy = plan["legacyJavaSelect"]
        if not mode and legacy.startswith("{"):
            selected = json.loads(legacy)
            if isinstance(selected, dict):
                candidate = _java_path(selected.get("Path"))
                if candidate:
                    plan["configuredJava"].append(candidate)
    else:
        plan["modernConfigPresent"] = hmcl_json.exists()
        plan["legacyConfigPresent"] = hmcl_legacy.exists()
        plan["configFiles"] = [str(hmcl_json)]
        if hmcl_json.exists():
            config = _read_json(hmcl_json)
            schema = config.get("$schema", "")
            if not isinstance(schema, str) or not re.fullmatch(
                    r"https://schemas\.glavo\.site/hmcl/instance-game-settings/1\.0\.\d+", schema):
                raise ValueError("HMCL实例配置schema不在已核实范围，未修改。")
            plan["schema"] = schema
            overrides = config.get("overrideProperties", [])
            if not isinstance(overrides, list):
                raise ValueError("HMCL实例overrideProperties格式无效。")
            if "javaType" in overrides and config.get("javaType") == "CUSTOM":
                candidate = _java_path(config.get("customJavaPath"))
                if candidate:
                    plan["configuredJava"].append(candidate)
        elif hmcl_legacy.exists():
            config = _read_json(hmcl_legacy)
            plan["legacyUsesGlobal"] = bool(config.get("usesGlobal", False))
            mode = config.get("javaVersionType")
            if not plan["legacyUsesGlobal"] and mode in ("CUSTOM", 4):
                candidate = _java_path(config.get("javaDir"))
                if candidate:
                    plan["configuredJava"].append(candidate)
    return plan


def pcl_settings_for_runtime(java_info: Mapping[str, Any]) -> dict[str, str]:
    """Native legacy JSON plus mode 2; PCL itself handles first-run migration."""
    version = str(java_info["javaVersion"])
    parts = re.match(r"^17(?:\.(\d+))?(?:\.(\d+))?", version)
    if not parts or java_info.get("architecture") != "x64":
        raise ValueError("PCL配置只接受实际核实的Java17 x64。")
    binary = _safe(Path(str(java_info["java"])))
    selected = {
        "Path": str(binary.parent).rstrip("\\/") + "\\",
        # Legacy PCL puts Java's major in .NET Version.Minor: 17.0.20 -> 1.17.0.20.
        "VersionString": "1.17." + (parts.group(1) or "0") + "." + (parts.group(2) or "0"),
        "IsJre": not (binary.parent / "javac.exe").is_file(),
        "Is64Bit": True,
        "IsUserImport": False,
    }
    return {"VersionArgumentJavaSelect": json.dumps(selected, ensure_ascii=False, separators=(",", ":")),
            "VersionArgumentJavaV2": "2"}


def bounded_java_candidates(game_dir: Path) -> list[str]:
    """Search a small number of known runtime directories, never the whole disk."""
    root = _safe(game_dir)
    candidates: list[str] = [str(root / "IC2-NewEra-runtime/java17/bin/java.exe")]
    minecraft_root = root.parent.parent
    bases = [root / "runtime", root / "java", root / "jre", minecraft_root / "runtime",
             minecraft_root / "PCL/Java", minecraft_root.parent / "PCL/Java", minecraft_root.parent / "Java",
             minecraft_root.parent / "runtime"]
    for key in ("ProgramFiles", "ProgramFiles(x86)"):
        value = os.environ.get(key)
        if value:
            for suffix in ("Java", "Microsoft", "Eclipse Adoptium", "Eclipse Foundation",
                           "Zulu", "Amazon Corretto", "BellSoft"):
                bases.append(Path(value) / suffix)
    for base in bases:
        if not base.is_dir():
            continue
        # JDK homes and common launcher runtime/bin layouts only, up to three levels.
        candidates.append(str(base / "bin/java.exe"))
        for pattern in ("*/bin/java.exe", "*/*/bin/java.exe", "*/*/*/bin/java.exe"):
            for path in base.glob(pattern):
                try:
                    _safe(path)
                except (OSError, ValueError):
                    continue
                candidates.append(str(path))
                if len(candidates) >= 48:
                    return candidates
    return candidates


def _write_config(root: Path, path: Path, payload: bytes, backups: Path) -> bool:
    path = _safe(path)
    path.relative_to(_safe(root))
    old = path.read_bytes() if path.is_file() else None
    if old == payload:
        return False
    _safe(backups).relative_to(_safe(root))
    backups.mkdir(parents=True, exist_ok=True)
    if old is not None:
        name = time.strftime("%Y%m%d-%H%M%S") + "-" + str(time.time_ns()) + "-" + path.name
        backup = _safe(backups / name)
        with backup.open("xb") as stream:
            stream.write(old)
        if hashlib.sha256(backup.read_bytes()).digest() != hashlib.sha256(old).digest():
            raise ValueError("启动器单版本配置备份校验失败。")
    path.parent.mkdir(parents=True, exist_ok=True)
    _safe(path.parent)
    temporary = _safe(path.with_name(path.name + ".ic2-java-" + str(os.getpid())))
    try:
        with temporary.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
    return True


def configure_launcher_java(game_dir: Path, java_info: Mapping[str, Any], backups: Path,
                            plan: Mapping[str, Any], pcl_values: Mapping[str, str] | None = None) -> dict:
    root = _safe(game_dir)
    javaw = _safe(Path(str(java_info["javaw"])))
    if not javaw.is_file():
        raise ValueError("选定Java17缺少javaw.exe。")
    if plan["kind"] == "pcl":
        if not pcl_values:
            raise ValueError("没有提供已核实的PCL单版本Java设置。")
        path = _inside(root, "PCL/Setup.ini")
        text, encoding, _original = _read_text(path)
        lines = text.splitlines()
        for key, value in pcl_values.items():
            if key not in {"VersionArgumentJavaSelect", "VersionArgumentJavaV2", "VersionArgumentJavaRange"}:
                raise ValueError("安装器只修改PCL的当前版本Java设置。")
            if "\n" in value or "\r" in value:
                raise ValueError("PCL设置值不能包含换行。")
            found = False
            for index, line in enumerate(lines):
                if ":" in line and line.split(":", 1)[0].strip().casefold() == key.casefold():
                    lines[index] = key + ":" + value
                    found = True
            if not found:
                lines.append(key + ":" + value)
        modified = _write_config(root, path, ("\r\n".join(lines) + "\r\n").encode(encoding), backups)
        result = {"kind": "pcl", "configured": True, "modified": modified,
                  "configurationFile": str(path), "restartLauncherRequired": modified}
    else:
        if not plan.get("modernConfigPresent"):
            raise ValueError("此HMCL实例使用旧版/未知配置格式，未修改其全局设置。Java17已准备好；请使用支持实例Java设置的新版HMCL或PCL2完成配置。")
        path = _inside(root, ".hmcl/config/instance-game-settings.json")
        config = _read_json(path)
        overrides = config.get("overrideProperties", [])
        if not isinstance(overrides, list) or any(not isinstance(value, str) for value in overrides):
            raise ValueError("HMCL实例overrideProperties格式无效。")
        config["overrideProperties"] = sorted(set(overrides) | {"javaType", "customJavaPath"})
        config["javaType"] = "CUSTOM"
        config["customJavaPath"] = str(javaw)
        payload = (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        modified = _write_config(root, path, payload, backups)
        result = {"kind": "hmcl", "configured": True, "modified": modified,
                  "configurationFile": str(path), "restartLauncherRequired": modified}
    return result
