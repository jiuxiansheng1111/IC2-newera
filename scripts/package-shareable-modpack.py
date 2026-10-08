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
OUTPUT = ROOT / "dist" / "IC2-New-Era-Test-2026.10.08.zip"
PACK_VERSION = "0.1.0-test.20261008-player-update"
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
            name, version, purpose = "IC2：新纪元", "0.1.0-dev", "核心附属；服务器/终端机/玩家 AI 最新修复"
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

    readme = f"""IC2：新纪元 四核心测试整合包
版本：{PACK_VERSION} / 2026-10-08

直接把这个 ZIP 发给测试玩家。首次导入需要联网下载 Minecraft、Forge 和七个官方模组；
本包已内置最新新纪元 JAR，包含电动机、WiFi增幅器、服务器权限、联网设备与运行诊断AI。
新版替换旧分享包；完整更改清单见实例 IC2-New-Era-Test/player-update-2026-10-08.md。

开始游玩
1. 使用支持 CurseForge ZIP 的启动器，选择“导入整合包/从文件导入”，选中本 ZIP。
   PCL 可将 ZIP 拖入启动器；HMCL、CurseForge 等使用各自的整合包导入入口。
2. 导入为新实例，保持 Minecraft 1.19.2、Forge 43.5.0，选择 Java 17。
   不要把本 ZIP 放入游戏“资源包”菜单。正常导入后共有八个顶层模组 JAR。
3. 等待下载完成，登录自己的 Minecraft 账号，启动并创建测试世界。
   现有世界由玩家自行备份；本包不包含任何玩家存档。
4. 按 I 打开助手，选择 AI 页；配方可在 JEI 查询。详细指南在实例
   IC2-New-Era-Test 文件夹，测试后填写 feedback-template-zh_CN.txt。

四个核心
IC2：新纪元 0.1.0-dev、IC2 Classic 2.1.3.4、Create 0.5.1.i、ProjectE 1.1.0。
另带 Carbon Config 2.0.2、Curios 5.1.6.4、The One Probe 6.2.2、JEI 11.8.1.1034。
Create 已含 Flywheel/Registrate，不要另装重复版本。
七个官方模组按 manifest.json 的固定版本下载，来源链接在 modlist.html 和 mod-lock.json。

测试重点
EU电动机：背面接IC2低压、前端接Create轴；32/64/128 RPM、1024/2048/4096 SU，
固定消耗10/20/40 EU/t。检查缺电恢复、红石、反转、外部动力优先与Create过载。
交换机装入1个WiFi增幅器，核对水平40×40格（dx,dz=-20..19）、上下各16格。
服务器AI按钮旁有三档权限按钮，只有房主/服主能切换；两名玩家检查即时授权/撤销。
服务器“概览/联网设备”两页，显示已发现设备/运行/异常数；每页3台，从设备行启停，无需坐标。
仅实际网卡或光纤接入机器入列。所有玩家可询问AI运行/故障，配置者才可确认提案。
AI异常优先采样最多16台，不代表无限全厂；对照实际缺电/过载/堵塞与未知状态。
右侧 AI 核心槽，助手按钮/聊天布局；
切换全屏、窗口和 GUI 比例，检查核心空槽/已有核心说明不重叠。
服务器的 AI 聊天不暂停单人世界；真实 API/网络错误按提示排查。
终端机名称只有“终端机”，无附加物品描述，144 格容量（12×3 显示、四页）。
终端配方要求箱子、PCB、集成电路、CPU、GPU、工业 SSD、光纤、黄铜机壳、暗物质。
四矿杂质粉必须清洗；钛/钨净粉需批准的 IC2 高级炉。
完整操作与待验收项目见随包指南。本包已做结构/哈希检查，启动器实际导入和游戏表现待玩家验收。

AI 与多人
外部 AI 默认关闭；每个玩家在“模型设置”填写自己的地址、模型和密钥并自行启用。
本包没有作者/其他玩家的模型密钥、账号、服务器地址或存档。
无需模型 API 时，终端本地托管仍可在同网、服务器装核心且启用控制后运行。
多人请使用相同整合包版本，按服务器规则加入队伍；同名网络不会自动授予权限。
所有玩家都能查看服务器；默认仅房主/服主及授权名单配置，另可切全员配置或仅房主配置。
专服控制台先执行 ic2newera bind_controller 在线玩家名；房主可用
/ic2newera grant_controller 玩家名 授权，revoke_controller 撤销；授权玩家不能再授权。
终端库存权限独立保留，服务器全员配置不会解锁别人的库存。
模型生成的终端规则需要玩家审阅确认，服务端会重新校验权限和网络。

安装故障
下载失败：在启动器查看具体文件失败项，按 modlist.html 中固定版本链接重试；
不切换到另一 Minecraft/加载器版本。不要额外安装另一份 IC2/Create/Flywheel。
首次导入需要网络；这是启动器整合包，不含 Minecraft 游戏本体、Java 或启动器。
反馈附复现步骤、界面截图及相关错误片段；发送前移除密钥、账号令牌和服务器凭据。

格式依据
https://support.curseforge.com/support/solutions/articles/9000198500-exporting-a-modpack-for-curseforge-project-submission
https://gdlauncher.com/docs/modpack-manifest-format/
"""
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
                   "motor-wifi-access-delivery-2026-10-08.md", "server-device-list-delivery-2026-10-08.md",
                   "player-update-2026-10-08.md")
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
