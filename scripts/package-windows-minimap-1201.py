"""Add the official Xaero minimap to the reviewed r52 client package."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "dist/IC2-New-Era-Windows-1.20.1-r52-ClientMods.mrpack"
if not BASE.is_file():
    BASE = ROOT / "build-isolated/player-packaging/20261029-r53-minimap/previous-clients/IC2-New-Era-Windows-1.20.1-r52-ClientMods.mrpack"
BASE_SHA = "0916F2C85D173904BA546D49B271B9130BD49438D60ED10282234C81F438A815"
OLD_OWN_SHA = "461F72EB1335C8DE64FC00D0719FFE75689B63A4E2D327A8FB2B76F49AF1916F"
OWN_NAME = "ic2newera-0.2.0-migration1201-all.jar"
OWN_SOURCE = ROOT / "build-isolated/migration-1201/newera/build/libs" / OWN_NAME
DEPS = ROOT / "build-isolated/migration-1201/dependencies"
LOCK = ROOT / "scripts/player-enhancements-lock-1201.json"
OUT = ROOT / "build-isolated/player-packaging/20261029-r53-minimap"
NAME = "IC2-New-Era-Windows-1.20.1-r53-Minimap.mrpack"
AI_RESOURCE = "data/ic2newera/ai/runtime_enhancements_zh.md"


def sha(path: Path, algorithm: str = "sha256") -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def jbytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def assert_ai_only(previous: bytes, current: Path) -> None:
    from io import BytesIO
    with ZipFile(BytesIO(previous)) as a, ZipFile(current) as b:
        old, new = set(a.namelist()), set(b.namelist())
        if old != new:
            raise ValueError("New Era archive entries changed")
        changed = [n for n in old if not n.endswith("/") and hashlib.sha256(a.read(n)).digest() != hashlib.sha256(b.read(n)).digest()]
        if changed != [AI_RESOURCE]:
            raise ValueError("Unexpected New Era content differences: " + str(changed))


def build(own_sha: str) -> None:
    if sha(BASE).upper() != BASE_SHA or sha(OWN_SOURCE).upper() != own_sha.upper():
        raise ValueError("Reviewed input archives changed")
    lock = json.loads(LOCK.read_text(encoding="utf-8-sig"))
    matches = [x for x in lock["mods"] if x["modId"] == "xaerominimap"]
    if lock["revision"] != "r53" or len(matches) != 1:
        raise ValueError("Unexpected minimap lock")
    mod = matches[0]
    source = DEPS / mod["file"]
    if (mod["version"] != "26.6.0" or mod["source"] != "https://cdn.modrinth.com/data/1bokaNcj/versions/dB6E0CY9/xaerominimap-forge-1.20.1-26.6.0.jar"
            or source.stat().st_size != mod["sizeBytes"] or any(sha(source, alg).lower() != mod[alg].lower() for alg in ("sha1", "sha256", "sha512"))):
        raise ValueError("Minimap source differs from the official lock")
    with ZipFile(source) as jar:
        if jar.testzip() is not None or b'modId = "xaerominimap"' not in jar.read("META-INF/mods.toml"):
            raise ValueError("Invalid minimap archive")
    OUT.mkdir(parents=True, exist_ok=True)
    output, partial = OUT / NAME, OUT / (NAME + ".partial")
    published = ROOT / "dist" / NAME
    if output.exists() or partial.exists() or published.exists():
        raise FileExistsError("Preserve an existing r53 output")
    with ZipFile(BASE) as base:
        old_jar = base.read("overrides/mods/" + OWN_NAME)
        if hashlib.sha256(old_jar).hexdigest().upper() != OLD_OWN_SHA:
            raise ValueError("Unexpected r52 New Era archive")
        assert_ai_only(old_jar, OWN_SOURCE)
        index = json.loads(base.read("modrinth.index.json"))
        if len(index["files"]) != 37 or index["dependencies"] != {"minecraft": "1.20.1", "forge": "47.4.28"}:
            raise ValueError("Unexpected r52 manifest")
        if any("xaerominimap" in r["path"].lower() for r in index["files"]):
            raise ValueError("Minimap already exists in base")
        index["files"].append({"path": "mods/" + mod["file"],
                               "hashes": {"sha1": mod["sha1"], "sha512": mod["sha512"]},
                               "env": {"client": "required", "server": "unsupported"},
                               "downloads": [mod["source"]], "fileSize": mod["sizeBytes"]})
        index.update({"versionId": "0.2.0-windows.20261029-r53-minimap",
                      "name": "IC2：新纪元 Windows（Xaero小地图）",
                      "summary": "37个模组，包含Xaero小地图、MC百科搜索、自由视角、YSM与iterationT光影。"})
        info = (
            "Windows客户端r53：Minecraft 1.20.1 / Forge 47.4.28，共37个模组。\n"
            "新增Xaero's Minimap 26.6.0：B新建路径点，U查看路径点，Z放大小地图。\n"
            "MC百科搜索默认B用于物品界面；Freecam默认F4；YSM 2.6.3默认Alt+Y。按键可在控制设置调整。\n"
            "光影为iterationT 3.2.0（Tahnass），中档预设，由CurseForge CDN下载。\n"
            "本包只含Xaero小地图；全屏世界地图是另一个可选模组。\n"
            "第三方模组及光影由启动器通过清单下载，原始ZIP/JAR没有重新上传。首次导入需联网。\n"
            "用户表示服务器已自行安装YSM；客户端现为2.6.3，服务器的实际版本和多人同步未核验。\n"
            "零雾构想Java材质尚未提供，暂时保留原有材质。游戏启动、功能、画面及FPS尚未实机验收。\n"
            "小地图作者页面：https://modrinth.com/mod/xaeros-minimap/version/dB6E0CY9\n"
            "光影作者页面：https://www.minegraph.cn/shaderpacks/12\n"
        ).encode("utf-8")
        old_info = base.read("overrides/IC2-NewEra-info/WINDOWS-CLIENTMODS-r52.txt")
        notices = base.read("overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt")
        if old_info not in notices:
            raise ValueError("Expected r52 notes in notices")
        english = ("Windows client r53: 37 mods including Xaero's Minimap 26.6.0, YSM 2.6.3 and iterationT 3.2.0. "
                   "Download third-party files from the pinned manifest. Actual gameplay remains unverified.\n\n").encode("utf-8")
        replacements = {"modrinth.index.json": jbytes(index),
                        "overrides/mods/" + OWN_NAME: OWN_SOURCE.read_bytes(),
                        "overrides/IC2-NewEra-info/WINDOWS-MINIMAP-r53.txt": info,
                        "overrides/IC2-NewEra-info/README-zh_CN.txt": info,
                        "overrides/IC2-NewEra-info/README-en_US.txt": english + base.read("overrides/IC2-NewEra-info/README-en_US.txt"),
                        "overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt": notices.replace(old_info, info)}
        removed = {"overrides/IC2-NewEra-info/WINDOWS-CLIENTMODS-r52.txt", "overrides/IC2-NewEra-info/WINDOWS-VISUALS-r50.txt"}
        with ZipFile(partial, "w", compression=ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
            for entry in base.infolist():
                if entry.filename in replacements or entry.filename in removed:
                    continue
                with base.open(entry) as source_file, target.open(entry, "w", force_zip64=True) as dest:
                    shutil.copyfileobj(source_file, dest, 4 * 1024 * 1024)
            for name, data in replacements.items():
                target.writestr(name, data)
    with ZipFile(partial) as z:
        if z.testzip() is not None or json.loads(z.read("modrinth.index.json")) != index:
            raise ValueError("Archive read-back failed")
        names = z.namelist()
        if len(names) != len({n.casefold() for n in names}):
            raise ValueError("Duplicate archive paths")
        if any(n.startswith("overrides/mods/") and n.endswith(".jar") and n != "overrides/mods/" + OWN_NAME for n in names):
            raise ValueError("Third-party JAR must not be embedded")
        if any(n.startswith(("overrides/shaderpacks/", "overrides/resourcepacks/")) and n.endswith(".zip") for n in names):
            raise ValueError("Third-party visual ZIP must not be embedded")
        if hashlib.sha256(z.read("overrides/mods/" + OWN_NAME)).hexdigest().upper() != own_sha.upper():
            raise ValueError("New Era JAR differs")
        if len(index["files"]) != 38 or not any(r["path"] == "shaderpacks/iterationT 3.2.0.zip" for r in index["files"]):
            raise ValueError("Shader or download count differs")
    partial.replace(output)
    shutil.copy2(output, published)
    checksum = sha(output).upper()
    if sha(published).upper() != checksum:
        raise ValueError("Published archive hash differs")
    (published.parent / (NAME + ".sha256")).write_text(checksum + "  " + NAME + "\n", encoding="ascii")
    receipt = {"archive": str(published), "sha256": checksum, "sizeBytes": published.stat().st_size,
               "baseSha256": BASE_SHA, "newEraSha256": own_sha.upper(), "modCount": 37,
               "downloadEntries": len(index["files"]), "minimap": mod, "shader": "iterationT 3.2.0 Medium",
               "thirdPartyAssetsEmbedded": False, "gameExecuted": False, "testsRun": False,
               "createdAt": datetime.now().astimezone().isoformat()}
    (OUT / "package-receipt.json").write_bytes(jbytes(receipt))
    print(json.dumps(receipt, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--own-sha256", required=True)
    build(parser.parse_args().own_sha256)
