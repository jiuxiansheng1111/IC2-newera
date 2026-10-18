"""Export a CurseForge-format private playtest pack from the verified runtime lock.

Only the project release, public guides and newly generated defaults are bundled.
Player folders/configuration are never traversed. Third-party mods are referenced
by their official project/file IDs and downloaded by the recipient's launcher.
"""
from __future__ import annotations

import hashlib
import html
import io
import json
import re
import tomllib
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "dist" / "IC2-New-Era-Test-2026.10.18-Phone-Chat.zip"
PACK_VERSION = "0.1.0-test.20261018-phone-chat"
# IDs and original filenames verified against each author's CurseForge file page.
OFFICIAL = {
    "ic2": (242942, 8545995, "ic2-classic", "IC2Classic-1.19.2-2.1.3.4.jar",
            "IC2 Classic", "2.1.3.4", "核心工业模组"),
    "create": (328085, 5797604, "create", "create-1.19.2-0.5.1.i.jar",
               "机械动力 Create", "0.5.1.i", "核心机械模组"),
    "projecte": (226410, 4860858, "projecte", "ProjectE-1.19.2-PE1.1.0.jar",
                 "等价交换 ProjectE", "1.1.0", "核心等价交换模组；提供终端配方暗物质"),
    "carbonconfig": (898104, 8018088, "carbon-config", "CarbonConfig-1.19.2-2.0.2.jar",
                     "Carbon Config", "2.0.2", "IC2 Classic 必需前置"),
    "curios": (309927, 5843737, "curios", "curios-forge-1.19.2-5.1.6.4.jar",
               "Curios API", "5.1.6.4", "保留当前配套饰品接口"),
    "theoneprobe": (245211, 3965693, "the-one-probe", "theoneprobe-1.19-6.2.2.jar",
                    "The One Probe", "6.2.2", "保留当前设备信息显示"),
    "jei": (238222, 5846858, "jei", "jei-1.19.2-forge-11.8.1.1034.jar",
             "JEI 物品与配方查询", "11.8.1.1034", "测试配方及加工路线查询"),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def source_for(row: dict) -> Path:
    if "projectFile" in row:
        candidate = (ROOT / row["projectFile"]).resolve()
        if not candidate.is_relative_to(ROOT):
            raise ValueError("Release source must remain inside the project")
        return candidate
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1" / row["cacheDirectory"]
    candidates = list(cache.rglob(row["cacheFileName"]))
    if len(candidates) != 1:
        raise ValueError("Expected one original cached JAR for " + row["modId"])
    return candidates[0]


def jar_metadata(data: bytes) -> tuple[dict, list[dict]]:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        metadata = tomllib.loads(archive.read("META-INF/mods.toml").decode("utf-8-sig"))
        nested = []
        if "META-INF/jarjar/metadata.json" in archive.namelist():
            for row in json.loads(archive.read("META-INF/jarjar/metadata.json"))["jars"]:
                nested_path = row["path"]
                with zipfile.ZipFile(io.BytesIO(archive.read(nested_path))) as child:
                    if "META-INF/mods.toml" in child.namelist():
                        child_meta = tomllib.loads(child.read("META-INF/mods.toml").decode("utf-8-sig"))
                        nested.extend({"modId": mod["modId"], "version": row["version"]["artifactVersion"]}
                                      for mod in child_meta["mods"])
        return metadata, nested


def main() -> None:
    runtime = json.loads((ROOT / "scripts/runtime-mods.json").read_text(encoding="utf-8-sig"))
    expected_ids = {"ic2newera", *OFFICIAL}
    if {row["modId"] for row in runtime["mods"]} != expected_ids:
        raise ValueError("Runtime list changed; review export metadata before packaging")
    release_info = {
        "packName": "IC2新纪元-四核心测试包",
        "packVersion": PACK_VERSION,
        "exportedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(),
        "minecraft": runtime["minecraft"],
        "forge": runtime["forge"],
        "javaMajorVersion": 17,
        "coreMods": ["ic2newera", "ic2", "create", "projecte"],
        "mods": [],
    }
    manifest = {
        "minecraft": {"version": runtime["minecraft"],
                      "modLoaders": [{"id": "forge-" + runtime["forge"], "primary": True}]},
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": release_info["packName"],
        "version": PACK_VERSION,
        "author": "IC2：新纪元开发组",
        "files": [],
        "overrides": "overrides",
    }
    entries: dict[str, bytes] = {}
    dependencies = []
    known_ids = {"forge", "minecraft"}
    for row in runtime["mods"]:
        mod_id = row["modId"]
        data = source_for(row).read_bytes()
        if sha256(data) != row["sha256"].upper():
            raise ValueError("Source hash mismatch: " + mod_id)
        metadata, nested = jar_metadata(data)
        declared = {mod["modId"] for mod in metadata["mods"]}
        if mod_id not in declared:
            raise ValueError("JAR does not declare expected mod: " + mod_id)
        known_ids.update(declared)
        known_ids.update(child["modId"] for child in nested)
        dependencies.extend(dep["modId"] for dep in metadata.get("dependencies", {}).get(mod_id, [])
                            if dep.get("mandatory"))
        if mod_id == "ic2newera":
            with zipfile.ZipFile(io.BytesIO(data)) as project_archive:
                embedded = json.loads(project_archive.read("META-INF/jarjar/metadata.json"))["jars"]
                mp3 = [library for library in embedded if library["identifier"] == {"group": "javazoom", "artifact": "jlayer"}]
                if len(mp3) != 1 or sha256(project_archive.read(mp3[0]["path"])) != "850508C837454A1B06017C32A36876FAE516DE1E89A829F725FEE1E6DCC52000":
                    raise ValueError("Complete release must include the verified JLayer MP3 dependency")
            name, version, purpose = "IC2：新纪元", "0.1.0-dev", "八角色机器人、充电宝、手机、原生点歌与可选独立语音"
            filename = row["fileName"]
            url = ""
            entries["overrides/mods/" + filename] = data
            origin = {"bundled": True}
        else:
            project, file_id, slug, filename, name, version, purpose = OFFICIAL[mod_id]
            url = f"https://www.curseforge.com/minecraft/mc-mods/{slug}/files/{file_id}"
            manifest["files"].append({"projectID": project, "fileID": file_id, "required": True})
            origin = {"bundled": False, "projectID": project, "fileID": file_id, "sourceUrl": url}
        release_info["mods"].append({
            "modId": mod_id, "name": name, "version": version, "purpose": purpose,
            "fileName": filename, "sha256": sha256(data), "bytes": len(data),
            "licenseDeclaredByJar": metadata.get("license", ""), "includedLibraries": nested, **origin,
        })
    missing = sorted(set(dependencies) - known_ids)
    if missing:
        raise ValueError("Required dependencies missing from pack and nested JARs: " + ", ".join(missing))

    readme = (ROOT / "docs/player-test-2026-10-18.md").read_text(encoding="utf-8")
    feedback = f"""IC2：新纪元测试反馈
整合包版本：{PACK_VERSION}
问题名称：
启动器及版本：
Java 版本：
单人 / 多人（当前队伍角色）：
窗口大小、全屏状态、GUI 比例：
相关方块/物品 ID：
复现步骤：
1.
2.
3.
预期表现：
实际表现：
是否每次发生：
相关截图 / 错误片段：

请勿附带 AI 密钥、账号令牌或未经处理的私人配置。
"""
    public_docs = ("new-industrial-stage-guide.md", "terminal-design-and-guide.md", "ai-assistant-setup.md",
                   "player-test-2026-10-18.md")
    for filename in public_docs:
        data = (ROOT / "docs" / filename).read_bytes()
        # Prevent personal absolute paths in the public documentation export.
        if re.search(r"[A-Za-z]:[/\\]Users[/\\]", data.decode("utf-8")):
            raise ValueError("Public guide contains a personal absolute path: " + filename)
        entries["overrides/IC2-New-Era-Test/" + filename] = data
    entries["README-zh_CN.txt"] = readme.replace("\n", "\r\n").encode("utf-8-sig")
    entries["overrides/IC2-New-Era-Test/README-zh_CN.txt"] = entries["README-zh_CN.txt"]
    entries["overrides/IC2-New-Era-Test/feedback-template-zh_CN.txt"] = feedback.replace("\n", "\r\n").encode("utf-8-sig")
    entries["overrides/options.txt"] = b"lang:zh_cn\nguiScale:0\nforceUnicodeFont:false\n"
    entries["manifest.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    entries["overrides/IC2-New-Era-Test/mod-lock.json"] = (
        json.dumps(release_info, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    mod_rows = []
    for mod in release_info["mods"]:
        name = html.escape(mod["name"] + " " + mod["version"])
        title = f'<a href="{html.escape(mod["sourceUrl"])}">{name}</a>' if "sourceUrl" in mod else name
        mod_rows.append(f'<li>{title} — {html.escape(mod["purpose"])}</li>')
    entries["modlist.html"] = ('<!doctype html><html lang="zh-CN"><meta charset="UTF-8">'
                              '<title>IC2新纪元测试包模组清单</title><h1>IC2新纪元测试包</h1><ul>'
                              + "".join(mod_rows) + '</ul><p>七个官方模组由启动器按固定清单下载；'
                              '新纪元为开发组提供的测试版，已随包内置。</p></html>').encode("utf-8")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            if name.startswith("/") or ".." in Path(name).parts:
                raise ValueError("Unsafe archive path")
            archive.writestr(name, data)
    # Delivery integrity check: reopen and compare exact archived bytes.
    with zipfile.ZipFile(OUTPUT) as archive:
        for name, data in entries.items():
            if archive.read(name) != data:
                raise ValueError("Archive byte mismatch: " + name)
        if json.loads(archive.read("manifest.json")) != manifest:
            raise ValueError("Archive manifest mismatch")
    pack_hash = sha256(OUTPUT.read_bytes())
    (OUTPUT.parent / (OUTPUT.name + ".sha256")).write_text(pack_hash + "  " + OUTPUT.name + "\n", encoding="ascii")
    (OUTPUT.parent / "README-zh_CN.txt").write_bytes(entries["README-zh_CN.txt"])
    audit = {
        "archive": OUTPUT.name, "bytes": OUTPUT.stat().st_size, "sha256": pack_hash,
        "format": "CurseForge minecraftModpack manifestVersion 1", "version": PACK_VERSION,
        "coreModCount": 4, "totalTopLevelMods": 8, "officialDownloadEntries": len(manifest["files"]),
        "bundledModCount": 1, "allSourceHashesMatchRuntimeLock": True,
        "requiredDependencyIdsCovered": True, "jarBytesReopenedAndMatched": True,
        "archiveFiles": sorted(entries), "playerConfigRead": False,
        "accountsOrSavesBundled": False, "launcherImportObserved": False,
        "gameStarted": False, "testsRun": False,
    }
    (ROOT / "build-isolated/shareable-modpack-audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit, ensure_ascii=True))


if __name__ == "__main__":
    main()
