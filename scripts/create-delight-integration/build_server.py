#!/usr/bin/env python3
"""Create a dedicated-server ZIP from the verified r70 server baseline.

This script is intentionally not run during preparation. Run it only when the
joint-pack workspace is declared ready for packaging.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import fnmatch
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

CLIENT_DENY = (
    "resourcepacks/", "shaderpacks/", "options.txt", "kubejs/assets/",
    "kubejs/client_scripts/", "config/crash_assistant/", "config/modpack_defaults/config/crash_assistant/",
)
SECRET_NAME = (".env", "secret", "credential", "token", "api_key", "apikey", "private_key", "player-config")
SERVER_LISTS = (
    "defaultconfigsServerTemplates",
    "kubejsServerScripts", "kubejsStartupScripts", "kubejsDataPackFiles",
)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): h.update(block)
    return h.hexdigest()


def clean_rel(value: str) -> PurePosixPath:
    p = PurePosixPath(value.replace("\\", "/"))
    if p.is_absolute() or not p.parts or any(x in ("", ".", "..") for x in p.parts):
        raise ValueError(f"不安全的归档路径：{value}")
    return p


def safe_extract(archive: Path, dest: Path) -> str:
    with zipfile.ZipFile(archive) as zf:
        roots = {PurePosixPath(name).parts[0] for name in zf.namelist() if PurePosixPath(name).parts}
        if len(roots) != 1: raise ValueError("基准服务端 ZIP 必须只有一个根目录")
        root = next(iter(roots))
        for info in zf.infolist():
            rel = clean_rel(info.filename)
            if rel.parts[0] != root: raise ValueError("基准包存在多根路径")
            # Unix symlinks are not needed in the server distribution.
            if stat.S_ISLNK(info.external_attr >> 16): raise ValueError(f"基准包包含符号链接：{info.filename}")
            target = dest.joinpath(*rel.parts)
            if info.is_dir(): target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as src, target.open("wb") as dst: shutil.copyfileobj(src, dst)
    return root


def side_manifest(work: Path) -> Path:
    options = (work / "side-work/server-sides.json", Path(__file__).with_name("server-sides.json"))
    for candidate in options:
        if candidate.is_file(): return candidate
    raise FileNotFoundError("缺少 side-work/server-sides.json 或脚本旁的 server-sides.json")


def overlay_path(overlay: Path, declared: str) -> tuple[Path, PurePosixPath]:
    rel = clean_rel(declared)
    if rel.parts[0] == "overrides": rel = PurePosixPath(*rel.parts[1:])
    return overlay.joinpath(*rel.parts), rel


def is_denied(rel: PurePosixPath) -> bool:
    text = str(rel).lower().replace("\\", "/")
    name = PurePosixPath(text).name
    return text.startswith(CLIENT_DENY) or any(marker in name for marker in SECRET_NAME)


def copy_declared(overlay: Path, target: Path, declared: str, copied: list[str], conflicts: list[str], *, replace=False, overwritten=None):
    src, rel = overlay_path(overlay, declared)
    if is_denied(rel) or not src.is_file(): return
    dst = target.joinpath(*rel.parts)
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.is_file():
        if sha256(dst) == sha256(src): return
        if not replace:
            conflicts.append(str(rel)); return
        if overwritten is not None: overwritten.append(str(rel))
    shutil.copy2(src, dst)
    copied.append(str(rel))


def selected_mods(selection_path: Path) -> list[dict]:
    data = json.loads(selection_path.read_text(encoding="utf-8-sig"))
    entries = data.get("list") if isinstance(data, dict) else data
    if not isinstance(entries, list): raise ValueError("selection.json 须包含 list")
    chosen, seen = [], {}
    for entry in entries:
        if not isinstance(entry, dict) or type(entry.get("server")) is not bool: raise ValueError("selection 项必须有布尔 server 字段")
        if entry["server"] is not True: continue
        source = Path(entry.get("path", "")).expanduser()
        if not source.is_absolute() or not source.is_file(): raise ValueError(f"server=true 模组缺少绝对存在文件路径：{entry.get('path')}")
        filename = entry.get("name") or source.name
        if Path(filename).name != filename or not filename.lower().endswith(".jar"): raise ValueError(f"无效模组文件名：{filename}")
        digest = sha256(source)
        prior = seen.get(filename.lower())
        if prior and prior != digest: raise ValueError(f"同名服务端模组哈希冲突：{filename}")
        if prior: continue
        seen[filename.lower()] = digest
        chosen.append({"path": source, "name": filename, "sha256": digest})
    if not chosen: raise ValueError("selection.json 没有任何 server=true 模组")
    return chosen


def readme_text(mod_count: int) -> str:
    return (
        "# Create Delight Remake × IC2：新纪元联合服务端\n\n"
        "Minecraft 1.20.1 Forge 专用服务端包。此压缩包保留 r70 的 Forge 启动文件、库、语音组件、第三方许可与 notices；"
        f"已按本次选择清单安装 {mod_count} 个 server=true 模组，并加入联合任务、服务端配置和 KubeJS 数据。\n\n"
        "首次使用请在新目录解压，核对 `eula.txt` 并按适用规则修改；本包不含世界、玩家档案或客户端材质/光影。"
        "请先备份已有服务端，再将该包作为全新联合包实例使用。\n"
    )


def known_generated_output(output: Path, audit_path: Path) -> bool:
    """Allow an atomic rebuild only over this script's verified prior output."""
    if not output.exists(): return True
    if not audit_path.is_file(): return False
    try:
        old = json.loads(audit_path.read_text(encoding="utf-8"))
        return (old.get("builder") == "build_server.py" and
                Path(old.get("output_path", "")).resolve() == output.resolve() and
                old.get("output_sha256") == sha256(output))
    except (OSError, ValueError, TypeError):
        return False


def copy_tree_overlay(overlay: Path, server: Path, rel_root: str, copied: list[str]):
    """Copy server-side KubeJS trees from the final overlay, including additions."""
    source = overlay / rel_root
    if not source.is_dir(): return
    for src in source.rglob("*"):
        if not src.is_file(): continue
        rel = PurePosixPath(src.relative_to(overlay).as_posix())
        if is_denied(rel): continue
        dst = server.joinpath(*rel.parts)
        dst.parent.mkdir(parents=True, exist_ok=True)
        if not dst.is_file() or sha256(dst) != sha256(src):
            shutil.copy2(src, dst)
            copied.append(str(rel))


def copy_pack_notices(overlay: Path, server: Path, copied: list[str]):
    """Keep the base pack's independent license and New Era notices at ZIP root."""
    sources = {
        "LICENSE-Create-Delight-Remake.txt": overlay / "LICENSE",
        "LICENSE-first-party-MIT.txt": overlay / "IC2-NewEra-info/LICENSE-first-party-MIT.txt",
        "THIRD-PARTY-NOTICES.txt": overlay / "IC2-NewEra-info/THIRD-PARTY-NOTICES.txt",
    }
    for name, src in sources.items():
        if not src.is_file(): raise FileNotFoundError(f"联合包缺少独立许可/通知文件：{src}")
        dst = server / name
        if not dst.is_file() or sha256(dst) != sha256(src): shutil.copy2(src, dst)
        copied.append(name)


def _strip_overrides(value: str) -> str:
    value = value.replace("\\", "/").lstrip("/")
    return value.removeprefix("overrides/")


def load_config_policy() -> dict:
    path = Path(__file__).with_name("server-config-policy.json")
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if data.get("schemaVersion") != 1:
        raise ValueError(f"不支持的服务端 config 分类版本：{path}")
    return data


def config_exclusion_sets(side_config: dict) -> tuple[set[str], list[str]]:
    exact = {_strip_overrides(p) for p in side_config.get("clientConfigExcluded", [])}
    patterns = [_strip_overrides(p) for p in side_config.get("clientConfigExcludedSpecial", [])]
    return exact, patterns


def config_classification(relative: str, side_config: dict, policy: dict) -> str:
    """Classify a base-pack config attachment by exact exclusions and safe defaults.

    Gameplay/shared modpack overrides are included by default; client UI, visual,
    personal/runtime and explicitly client-only files are excluded.
    """
    rel = relative.replace("\\", "/").strip("/")
    config_rel = f"config/{rel}"
    exact, manifest_patterns = config_exclusion_sets(side_config)
    if config_rel in exact:
        return "excluded_manifest_client"
    for pattern in manifest_patterns:
        if fnmatch.fnmatchcase(config_rel, pattern):
            return "excluded_manifest_client_special"
    top = PurePosixPath(rel).parts[0].lower() if PurePosixPath(rel).parts else ""
    if top in {name.lower() for name in policy.get("excludedTopLevelDirectories", [])}:
        return "excluded_client_visual"
    all_patterns = policy.get("excludedPathPatterns", []) + policy.get("excludedRuntimeFiles", [])
    if any(fnmatch.fnmatchcase(config_rel, pattern.replace("\\", "/")) for pattern in all_patterns):
        return "excluded_personal_or_client"
    name = PurePosixPath(rel).name.lower()
    if any(fnmatch.fnmatchcase(name, pattern.lower()) for pattern in policy.get("excludedFilenamePatterns", [])):
        return "excluded_client_filename"
    if "client-config" in name or name in {"client.json", "client.toml", "client.properties", "client.snbt"}:
        return "excluded_client_filename"
    return "included_gameplay_or_shared"


def copy_gameplay_config(overlay: Path, server: Path, side_config: dict, policy: dict,
                         copied: list[str], baseline_overrides: list[str]) -> dict:
    """Preserve gameplay settings from the MRPack without copying visual/UI state."""
    source = overlay / "config"
    if not source.is_dir(): raise FileNotFoundError("client-overlay/config 缺失")
    counts: dict[str, int] = {}
    roots: dict[str, int] = {}
    excluded: dict[str, int] = {}
    for src in sorted(source.rglob("*")):
        if not src.is_file(): continue
        rel = PurePosixPath(src.relative_to(source).as_posix())
        relative = rel.as_posix()
        # The task data is reconstructed by merge_quests.py and copied below.
        if relative == "ftbquests/quests" or relative.startswith("ftbquests/quests/"):
            continue
        category = config_classification(relative, side_config, policy)
        counts[category] = counts.get(category, 0) + 1
        if category != "included_gameplay_or_shared":
            excluded[category] = excluded.get(category, 0) + 1
            continue
        server_rel = PurePosixPath("config") / rel
        if is_denied(server_rel):
            excluded["excluded_sensitive_name"] = excluded.get("excluded_sensitive_name", 0) + 1
            continue
        root = rel.parts[0]
        roots[root] = roots.get(root, 0) + 1
        dst = server.joinpath(*server_rel.parts)
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.is_file():
            if sha256(dst) == sha256(src): continue
            baseline_overrides.append(str(server_rel))
        shutil.copy2(src, dst)
        copied.append(str(server_rel))
    return {"policy_file": "scripts/create-delight-integration/server-config-policy.json",
            "policy": policy["policy"], "file_counts_by_class": counts,
            "included_gameplay_files_by_top_level": dict(sorted(roots.items())),
            "excluded_files_by_class": excluded,
            "base_pack_overrides_take_precedence": policy.get("overlayWinsOnGameplayConfigConflict", True)}


def copy_ldlib_server_definitions(work: Path, server: Path, copied: list[str]) -> dict:
    """Copy server-loaded MBD definitions, not LDLib UI/model/texture assets."""
    source = work / "repair-overlay/ldlib"
    if not source.is_dir():
        raise FileNotFoundError("缺少已核验的 work/repair-overlay/ldlib；MBD 服务端定义不能省略")
    required = {"assets/mbd2/machine": ".sm", "assets/mbd2/multiblock": ".mb",
                "assets/mbd2/recipe_type": ".rt", "assets/mbd2/kinetic_machine": ".km"}
    counts = {}
    ignored_non_runtime = 0
    for rel_root, extension in required.items():
        tree = source / rel_root
        if not tree.is_dir(): raise FileNotFoundError(f"缺少 LDLib/MBD 服务端定义：{tree}")
        count = 0
        for src in sorted(tree.rglob("*")):
            if not src.is_file(): continue
            if src.suffix.lower() != extension:
                ignored_non_runtime += 1
                continue
            rel = PurePosixPath(src.relative_to(source).as_posix())
            dst = server / "ldlib" / Path(*rel.parts)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            copied.append(str(PurePosixPath("ldlib") / rel))
            count += 1
        if count == 0: raise ValueError(f"LDLib/MBD 服务端定义目录为空：{tree}")
        counts[rel_root] = count
    # virtual_satellite_navigation.js creates this interface in a server-side
    # item interaction through UIProject.loadUIFromFile.
    navigation_ui = Path("assets/ldlib/projects/ui/satellite_navigation_data_card.ui")
    ui_target = server / "ldlib" / navigation_ui
    ui_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / navigation_ui, ui_target)
    copied.append("ldlib/" + navigation_ui.as_posix())
    return {"source": "work/repair-overlay/ldlib", "destination": "ldlib/assets/mbd2/",
            "server_runtime_definition_files": counts,
            "server_loaded_ui": navigation_ui.as_posix(),
            "ignored_non_runtime_files_in_definition_trees": ignored_non_runtime,
            "excluded_client_visual_resources": ["assets/mbd2/models/**", "assets/mbd2/textures/**",
                                                  "assets/mbd2/animations/**", "assets/mbd2/geo/**",
                                                  ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--baseline", required=True, type=Path)
    args = parser.parse_args()
    work, output, baseline = (p.resolve() for p in (args.work, args.output, args.baseline))
    overlay = work / "client-overlay"
    selection_path = work / "selection.json"
    if not overlay.is_dir() or not selection_path.is_file(): raise FileNotFoundError("W 须包含最终 client-overlay/ 与 selection.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    report_path = output.with_suffix(output.suffix + ".audit.json")
    if not known_generated_output(output, report_path):
        raise FileExistsError(f"目标已存在且不是本脚本可验证的先前 r71 生成件，拒绝覆盖：{output}")
    side = json.loads(side_manifest(work).read_text(encoding="utf-8-sig"))
    config = side.get("configuration", {})
    config_policy = load_config_policy()
    mods = selected_mods(selection_path)
    newera_jars = [m for m in mods if m["name"].lower().startswith("ic2newera")]
    if len(newera_jars) != 1:
        raise ValueError("selection.json 必须恰有一个 server=true 的新纪元 JAR，确保新 AI 索引随服务端更新")
    # Keep temporary paths short: some of the base pack's valid KubeJS paths
    # are already near Win32's path length limit.
    stage_parent = Path(tempfile.mkdtemp(prefix="r71s", dir=output.parent))
    temp_output = output.parent / f".{output.name}.tmp-{os.getpid()}"
    temp_report = report_path.parent / f".{report_path.name}.tmp-{os.getpid()}"
    root = None
    try:
        baseline_root = safe_extract(baseline, stage_parent)
        root = "s"
        archive_root = "Create-Delight-New-Era-r71-Server"
        old_root = stage_parent / baseline_root
        server = stage_parent / root
        if old_root != server: old_root.rename(server)
        mods_dir = server / "mods"
        if mods_dir.exists(): shutil.rmtree(mods_dir)
        mods_dir.mkdir(parents=True)
        for mod in mods: shutil.copy2(mod["path"], mods_dir / mod["name"])

        copied, conflicts, config_overrides = [], [], []
        # Server templates are explicit and take precedence over stale baseline
        # defaults. Client templates are filtered by the same policy used below.
        for list_name in SERVER_LISTS:
            for declared in config.get(list_name, []):
                _, rel = overlay_path(overlay, declared)
                if list_name == "defaultconfigsServerTemplates" and config_classification(
                        rel.as_posix().removeprefix("defaultconfigs/"), config, config_policy) != "included_gameplay_or_shared":
                    continue
                copy_declared(overlay, server, declared, copied, conflicts,
                              replace=list_name == "defaultconfigsServerTemplates",
                              overwritten=config_overrides if list_name == "defaultconfigsServerTemplates" else None)
        for declared in config.get("kubejsConfigFiles", []):
            if declared.endswith("/common.properties"):
                copy_declared(overlay, server, declared, copied, conflicts)
        config_audit = copy_gameplay_config(overlay, server, config, config_policy,
                                            copied, config_overrides)
        # KubeJS assets are excluded by the audited file-read analysis; FTB quest
        # language and all other client assets remain client-only.
        for prefix in ("overrides/hotai/", "overrides/tacz/"):
            source_dir = overlay / prefix.removeprefix("overrides/")
            if source_dir.is_dir():
                for src in source_dir.rglob("*"):
                    if not src.is_file(): continue
                    rel = PurePosixPath(src.relative_to(overlay).as_posix())
                    if is_denied(rel): continue
                    dst = server.joinpath(*rel.parts)
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(src, dst); copied.append(str(rel))

        # The full merged FTB book is an intentional replacement for r70's
        # quest definitions; retain any other baseline config untouched.
        quest_src = overlay / "config/ftbquests/quests"
        if not quest_src.is_dir(): raise FileNotFoundError("client-overlay 缺少合并后的 config/ftbquests/quests")
        quest_dst = server / "config/ftbquests/quests"
        if quest_dst.exists(): shutil.rmtree(quest_dst)
        shutil.copytree(quest_src, quest_dst)
        copied.append("config/ftbquests/quests/** (merged replacement)")
        # New server datapack/script files may be outside the r70 side manifest.
        # The final joint overlay is authoritative for these three server trees.
        for rel_root in ("kubejs/data", "kubejs/server_scripts", "kubejs/startup_scripts"):
            copy_tree_overlay(overlay, server, rel_root, copied)
        ldlib_audit = copy_ldlib_server_definitions(work, server, copied)

        # The service reads the same exact path shipped with the client. Record
        # both hashes so drift is visible in the independent build receipt.
        knowledge_src = overlay / "config/ic2newera/quest_knowledge.json"
        if not knowledge_src.is_file():
            raise FileNotFoundError("client-overlay/config/ic2newera/quest_knowledge.json 缺失")
        knowledge_dst = server / "config/ic2newera/quest_knowledge.json"
        knowledge_dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(knowledge_src, knowledge_dst)
        client_knowledge_sha = sha256(knowledge_src)
        server_knowledge_sha = sha256(knowledge_dst)
        if client_knowledge_sha != server_knowledge_sha:
            raise ValueError("客户端与服务端 quest_knowledge.json SHA-256 不一致")
        copied.append("config/ic2newera/quest_knowledge.json")
        copy_pack_notices(overlay, server, copied)
        # Do not ship a world, cached player state, client content, or a stale
        # distribution README. Preserve LICENSE and THIRD-PARTY-NOTICES.
        for name in ("world", "worlds", "playerdata", "advancements"):
            p = server / name
            if p.is_dir(): shutil.rmtree(p)
        for p in list(server.glob("README*")):
            if p.is_file(): p.unlink()
        # Remove r70 distribution identity and stale rollout/verification notes.
        for name in ("server-manifest.json", "artifact-audit.json", "UPDATE-r68.md",
                     "UPDATE-r68.txt", "UPDATE-r68.zh-CN.txt", "PLAYTEST-STATUS.md", "PLAYTEST-STATUS.txt"):
            stale = server / name
            if stale.is_file(): stale.unlink()
        (server / "README-Joint-Server.md").write_text(readme_text(len(mods)), encoding="utf-8")
        if not (server / "server.properties").is_file():
            raise FileNotFoundError("r70 基准包缺少 server.properties；不自行生成服务器/世界默认值")

        forbidden = []
        for p in server.rglob("*"):
            if not p.is_file(): continue
            rel = PurePosixPath(p.relative_to(server).as_posix())
            if is_denied(rel): forbidden.append(str(rel))
        if forbidden: raise ValueError(f"检测到禁止随服发布的客户端/敏感路径：{forbidden[:30]}")
        manifest = {"builder": "build_server.py", "output_path": str(output),
                    "baseline": baseline.name, "baseline_sha256": sha256(baseline),
                    "mods_server_true": [{"name": m["name"], "sha256": m["sha256"]} for m in mods],
                    "copied_side_audited_files": sorted(set(copied)),
                    "r70_config_conflicts_preserved": sorted(set(conflicts)),
                    "base_server_config_overrides": sorted(set(config_overrides)),
                    "config_classification": config_audit,
                    "ldlib_mbd_server_definitions": ldlib_audit,
                    "quest_knowledge": {"client_overlay_path": "config/ic2newera/quest_knowledge.json",
                                        "server_path": "config/ic2newera/quest_knowledge.json",
                                        "client_overlay_sha256": client_knowledge_sha,
                                        "server_sha256": server_knowledge_sha},
                    "preserved": ["Forge libraries and launchers", "voice component", "LICENSE", "THIRD-PARTY-NOTICES.txt", "server.properties"],
                    "excluded": ["worlds/player data", "resource packs/shaders", "KubeJS assets and client scripts", "client mods (server=false)"]}
        (stage_parent / root / "SERVER-BUILD-MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        with zipfile.ZipFile(temp_output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=5, allowZip64=True) as zf:
            for p in sorted((stage_parent / root).rglob("*")):
                if p.is_file():
                    compressed = zipfile.ZIP_STORED if p.suffix.lower() in (".jar", ".zip", ".png", ".ogg") else zipfile.ZIP_DEFLATED
                    zf.write(p, (Path(archive_root) / p.relative_to(server)).as_posix(), compress_type=compressed)
        manifest["output_sha256"] = sha256(temp_output)
        manifest["output_bytes"] = temp_output.stat().st_size
        temp_report.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # Replace only a previously verified output from this builder; temp files
        # are in the same directory so the replacement is atomic on Windows.
        os.replace(temp_output, output)
        os.replace(temp_report, report_path)
        print(json.dumps({"output": str(output), "sha256": manifest["output_sha256"], "mods": len(mods), "copied_server_files": len(copied)}, ensure_ascii=True))
    finally:
        for temp in (temp_output, temp_report):
            try: temp.unlink(missing_ok=True)
            except OSError: pass
        shutil.rmtree(stage_parent, ignore_errors=True)


if __name__ == "__main__": main()
