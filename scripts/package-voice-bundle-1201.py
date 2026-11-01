"""Combine the verified voice installer and unchanged voice ZIP into one download.

The inner ZIP is stored without recompressing its models. No installer, model,
launcher or game is executed; only archives, hashes and static source are read.
"""
from __future__ import annotations

import ast
import hashlib
import json
import time
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
WORK = ROOT / "build-isolated/player-packaging/20261024-voice-bundle"
PRIOR = ROOT / "build-isolated/player-packaging/20261024-voice-slim/exports-integrity-audit.json"
VOICE = "IC2-NewEra-Voice-Slim-Win64-2026.10.24.zip"
INSTALLER = "IC2-NewEra-Voice-Installer-Win64-2026.10.24.zip"
PACK = "IC2-New-Era-1.20.1-Forge-47.4.28-r41.mrpack"
OLD_PREFIX = "IC2-NewEra-Voice-Installer-Win64/"
PREFIX = "IC2-NewEra-Voice-Bundle-Win64/"
OUTPUT = DIST / "IC2-NewEra-Voice-Bundle-Win64-2026.10.24.zip"
LIMIT = 10_000_000_000


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def file_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def safe_name(name):
    if (not name or name.startswith("/") or ":" in name or "\\" in name
            or any(part in {"", ".", ".."} for part in name.split("/"))):
        raise ValueError("Unsafe bundle path")
    return PurePosixPath(name).as_posix()


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    prior = json.loads(PRIOR.read_text(encoding="utf-8"))
    identities = {row["file"]: row for row in prior["artifacts"]}
    revised_pack_audit = ROOT / "build-isolated/player-packaging/20261024-pcl-import-bundledvoice/pcl-import-audit.json"
    if revised_pack_audit.is_file():
        revised = json.loads(revised_pack_audit.read_text(encoding="utf-8"))
        if revised["archive"] != PACK or not revised["staticArchiveRoundTripVerified"]:
            raise ValueError("Revised ordinary pack was not archive-verified")
        identities[PACK] = {"file": PACK, "sizeBytes": revised["sizeBytes"], "sha256": revised["sha256"]}
    for name in (VOICE, INSTALLER, PACK):
        source = DIST / name
        expected = identities[name]
        if source.stat().st_size != expected["sizeBytes"] or file_digest(source) != expected["sha256"]:
            raise ValueError("Previously verified input changed: " + name)
    partial = OUTPUT.with_suffix(OUTPUT.suffix + ".partial")
    if OUTPUT.exists() or partial.exists():
        raise FileExistsError("Review existing output or exact partial before rebuilding")

    entries = {}
    with zipfile.ZipFile(DIST / INSTALLER) as old:
        for entry in old.infolist():
            if entry.is_dir() or not entry.filename.startswith(OLD_PREFIX):
                raise ValueError("Unexpected installer archive layout")
            relative = safe_name(entry.filename[len(OLD_PREFIX):])
            if relative == "installer-files.json":
                continue
            if relative in entries:
                raise ValueError("Duplicate installer entry")
            entries[relative] = old.read(entry)

    zh = '''IC2：新纪元 — 八角色三语语音合并包（Windows x64）

先在PCL2安装普通游戏包（无语音、含Java17），再添加本附件。
本合并ZIP已同时包含安装工具和语音ZIP，不用再下载或手动配对两份文件。

使用方法
1. 完整解压本合并ZIP，打开 IC2-NewEra-Voice-Bundle-Win64 文件夹。
2. 正常关闭Minecraft，双击里面的 Install.cmd。
3. 按提示选择已安装的 Minecraft1.20.1 / Forge47.4.28 隔离版本目录。
   应选择 .minecraft/versions/版本名，里面有同名JSON；不要选mods、saves或整个.minecraft。
工具会自动找到旁边的 Voice-Slim 语音ZIP；内层ZIP保持原样，不必再次手动解压。
Install.cmd可从下载目录运行，不用把本文件夹搬进游戏目录。
完成后从PCL2启动对应实例；本工具只添加语音和开启语音开关，不重复安装模组或Java。

普通包与老PCL2
普通游戏包是 IC2-New-Era-1.20.1-Forge-47.4.28-r41.mrpack，可拖入PCL2。
拖入无反应时，使用启动器内的整合包安装入口手动选择mrpack文件。
已读取本机桌面PCL2为2.13.0.1；另核对官方2.12.8.2源码也支持mrpack及实例目录Java搜索。更早未知版本没有逐版验收。
若提示找不到Java17，可手动选择导入后版本目录中的 IC2-NewEra-runtime/java17/bin/javaw.exe，无需另下Java。
不装语音也能正常文字交互、派任务和游玩；自由LLM聊天仍需玩家自行配置模型服务。

资源与空间
保留八角色、中/日/英语言资源、原音色/索引和翻唱。TTS/RVC/Demucs共用一套Python3.11/Torch cu128。
语音需要兼容CUDA12.8的NVIDIA显卡和驱动。合并下载小于10GB；解压外层后仍有约6.77GB内层ZIP，安装语音再占约10.62GB，并需临时空间。
游戏与安装工具宜放较短路径，磁盘和解压工具需支持超过4GB的ZIP64文件。
此安装器适用于新的独立实例，不自动覆盖旧语音或自定义文件。中途失败请保留回执并按报错处理。

验收与权利
本次只合并外层封装；内层语音ZIP与此前逐项哈希核对的版本完全一致。新共享环境实际推理、安装和扬声器效果仍待验收；详见PLAYTEST-STATUS.txt。
基于Minecraft的非官方项目；非商业迁移测试附件。严禁据本附件商用，使用后果自行承担；第三方模型/录音的公开再分发许可未核实，不上传公开GitHub。
自有代码仍MIT，第三方许可独立适用，见THIRD-PARTY-NOTICES.txt。
'''
    en = '''IC2: New Era — combined eight-character, three-language voice add-on

Import the ordinary game mrpack in PCL2 first. This combined ZIP includes both the installer and the unchanged slim voice ZIP.
Extract the outer archive, open IC2-NewEra-Voice-Bundle-Win64, close Minecraft normally and run Install.cmd.
Select the existing isolated Minecraft1.20.1 / Forge47.4.28 profile directory containing its matching version JSON, not mods, saves or the shared .minecraft root.
The tool finds the inner Voice-Slim ZIP beside Install.cmd automatically. Do not extract that inner ZIP manually. The tool can run from an ordinary downloads folder and does not reinstall Java or game mods.

PCL2 and Java
The no-voice game mrpack can be dragged into PCL2. If dragging does nothing, select the mrpack through the launcher's modpack-install entry.
The local desktop binary reports PCL2 2.13.0.1. Official 2.12.8.2 source also supports mrpack and Java discovery inside the selected profile; unknown earlier versions have not each been verified.
If Java is not found, select IC2-NewEra-runtime/java17/bin/javaw.exe inside the imported profile. Java17 is already included in the ordinary game pack.
Ordinary text interaction and tasks do not require voice. Free-form LLM conversation requires the player's own model-service configuration.

The add-on preserves eight profiles, Chinese/Japanese/English resources and singing, using one shared Python3.11/Torch cu128 environment.
Voice requires an NVIDIA GPU and driver compatible with CUDA12.8. The download stays below10GB; after extracting the outer archive, keep space for the6.77GB inner ZIP, about10.62GB installed voice files and temporary space. Use short paths and ZIP64-capable storage/tools.
Existing custom or legacy voice files are not overwritten. Keep the installation receipt if a failure occurs.
This change only combines packaging. The inner voice ZIP is byte-for-byte identical to the previously hash-reviewed export. Actual shared-environment inference, installation and speaker acceptance remain pending; see PLAYTEST-STATUS.txt.
Unofficial Minecraft project. Assets are for private noncommercial migration playtests, not a commercial-use grant. Users bear responsibility for their use; do not upload models or recordings to public GitHub. First-party code remains MIT and third-party licenses apply separately.
'''
    entries["README-zh_CN.txt"] = zh.encode("utf-8")
    entries["README-en_US.txt"] = en.encode("utf-8")
    (WORK / "README-zh_CN.txt").write_bytes(entries["README-zh_CN.txt"])
    (WORK / "README-en_US.txt").write_bytes(entries["README-en_US.txt"])
    manifest = json.loads(entries["install-manifest.json"])
    assert manifest["voice"]["archive"] == VOICE
    assert manifest["voice"]["sha256"] == identities[VOICE]["sha256"]
    assert manifest["voice"]["sizeBytes"] == identities[VOICE]["sizeBytes"]
    assert b"install_voice_only.py" in entries["Install.cmd"]
    assert b"../installer" in entries["bootstrap/python311._pth"]
    for name, data in entries.items():
        if name.startswith("installer/") and name.endswith(".py"):
            ast.parse(data.decode("utf-8-sig"), filename=name)

    records = []
    with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
        for name, data in sorted(entries.items()):
            archive.writestr(PREFIX + safe_name(name), data)
            records.append({"path": name, "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest().upper()})
        entry = zipfile.ZipInfo(PREFIX + VOICE)
        entry.compress_type = zipfile.ZIP_STORED
        checksum = hashlib.sha256()
        size = 0
        last = time.monotonic()
        with (DIST / VOICE).open("rb") as source, archive.open(entry, "w", force_zip64=True) as target:
            for block in iter(lambda: source.read(4 * 1024 * 1024), b""):
                target.write(block)
                checksum.update(block)
                size += len(block)
                if time.monotonic() - last >= 15:
                    print(json.dumps({"voiceZipBytesBundled": size, "total": identities[VOICE]["sizeBytes"]}), flush=True)
                    last = time.monotonic()
        assert size == identities[VOICE]["sizeBytes"] and checksum.hexdigest().upper() == identities[VOICE]["sha256"]
        records.append({"path": VOICE, "sizeBytes": size, "sha256": checksum.hexdigest().upper()})
        archive.writestr(PREFIX + "installer-files.json", json_bytes({"schemaVersion": 1, "files": records}))
    if partial.stat().st_size + (DIST / PACK).stat().st_size >= LIMIT:
        raise ValueError("Combined final downloads exceed10GB")

    with zipfile.ZipFile(partial) as archive:
        names = archive.namelist()
        assert len(names) == len(records) + 1 == len(set(name.casefold() for name in names))
        for row in records:
            digest = hashlib.sha256()
            count = 0
            with archive.open(PREFIX + row["path"]) as stream:
                for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
                    digest.update(block)
                    count += len(block)
            assert count == row["sizeBytes"] and digest.hexdigest().upper() == row["sha256"], row["path"]
        assert archive.getinfo(PREFIX + VOICE).compress_type == zipfile.ZIP_STORED
        assert json.loads(archive.read(PREFIX + "installer-files.json"))["files"] == records
    partial.replace(OUTPUT)
    sha = file_digest(OUTPUT)
    (DIST / (OUTPUT.name + ".sha256")).write_text(sha + "  " + OUTPUT.name + "\n", encoding="ascii")
    report = {"archive": OUTPUT.name, "sizeBytes": OUTPUT.stat().st_size, "sha256": sha,
              "rootDirectory": PREFIX.rstrip("/"), "installerAndVoiceTogether": True,
              "nestedVoice": identities[VOICE], "installerInput": identities[INSTALLER],
              "ordinaryGamePack": identities[PACK], "fileCount": len(records) + 1,
              "totalTwoDownloadsBytes": OUTPUT.stat().st_size + (DIST / PACK).stat().st_size,
              "bothDownloadsUnder10GBCombined": True, "allOuterFilesRoundTripSha256Verified": True,
              "innerVoiceByteForByteUnchanged": True, "gameJarChanged": False,
              "installerExecuted": False, "inferenceExecuted": False,
              "launcherOrGameExecuted": False, "testsRun": False,
              "pclDesktopExeVersionRead": "2.13.0.1", "olderOfficialSourceReviewed": "2.12.8.2"}
    (WORK / "bundle-audit.json").write_bytes(json_bytes(report))
    print(json.dumps(report, ensure_ascii=True), flush=True)


if __name__ == "__main__":
    main()
