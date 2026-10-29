"""Create the shareable r60 PCL/Modrinth client package from the verified r53 baseline."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "dist/IC2-New-Era-Windows-1.20.1-r53-Minimap.mrpack"
if not BASE.is_file():
    BASE = ROOT / "build-isolated/player-packaging/20261030-r55-complete/previous-packages/IC2-New-Era-Windows-1.20.1-r53-Minimap.mrpack"
BASE_HASH = "F5B9D50E2BAA58E51A871AD13A0E0C78BBC6D836728C5A2744B1108D53D1B5AA"
OLD_OWN_HASH = "AB37FDC221399C2FD31AA6B3F0CA85A89A8D06369181DBB5CC20669F21AC0DDF"
OWN = "overrides/mods/ic2newera-0.2.0-migration1201-all.jar"
NAME = "IC2-New-Era-Windows-1.20.1-r60-Complete.mrpack"


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest().upper()


def build(jar: Path, expected: str) -> None:
    if sha(BASE) != BASE_HASH or sha(jar) != expected.upper():
        raise ValueError("Client baseline or New Era JAR has changed")
    output = ROOT / "dist" / NAME
    partial = output.with_suffix(output.suffix + ".partial")
    if output.exists() or partial.exists():
        raise FileExistsError("Refusing to overwrite client release")
    own = jar.read_bytes()
    with ZipFile(BASE) as source:
        if digest_bytes(source.read(OWN)) != OLD_OWN_HASH:
            raise ValueError("Unexpected r53 New Era JAR")
        index = json.loads(source.read("modrinth.index.json"))
        if len(index["files"]) != 38 or index["dependencies"] != {"minecraft": "1.20.1", "forge": "47.4.28"}:
            raise ValueError("Unexpected r53 download manifest")
        resource_path = "resourcepacks/Bare Bones 1.20.zip"
        if sum(item["path"] == resource_path for item in index["files"]) != 1:
            raise ValueError("Unexpected old resource pack entry")
        index["files"] = [item for item in index["files"] if item["path"] != resource_path]
        options = source.read("overrides/options.txt")
        previous = b'resourcePacks:["vanilla", "file/Bare Bones 1.20.zip"]'
        if options.count(previous) != 1:
            raise ValueError("Unexpected old resource pack selection")
        options = options.replace(previous, b'resourcePacks:["vanilla"]')
        index.update({"versionId": "0.2.0-windows.20261030-r60-complete",
                      "name": "IC2：新纪元 Windows 完整整合包",
                      "summary": "37个模组，含 YSM、小地图、ITT 光影、动力转换和崩溃修复。"})
        info = ("IC2：新纪元 Windows 完整客户端 r60\n"
                "Minecraft 1.20.1 / Forge 47.4.28，共37个模组；使用PCL导入本 .mrpack。\n"
                "第三方模组和ITT 3.2光影通过原作者或发行平台的清单下载，首次导入需联网；默认使用原版材质。\n"
                "含YSM 2.6.3、Xaero小地图、MC百科搜索、Freecam、输入法修复和Cloth Config。\n"
                "新增初级/高级EU电动机、初级/高级机械发电机、万能变压器和68个新纪元进度。\n"
                "合金炉/高级电炉掉落及IC2堆叠流体单元兼容已修正；客户端和服务器须同时更新新纪元。\n"
                "服务器完整包为 IC2-New-Era-Server-1.20.1-r60-Complete.zip，包含YSM 2.6.3。\n"
                "游戏共同运行、联机、光影和新增转换设备仍需实机确认。零雾构想材质未提供。\n").encode("utf-8")
        english = ("IC2: New Era Windows r60: Minecraft 1.20.1 / Forge 47.4.28, 37 mods. "
                   "Import this .mrpack with PCL or another compatible launcher and allow the pinned third-party downloads. "
                   "Install the matching r60 New Era server; gameplay and multiplayer still require verification.\n").encode("utf-8")
        old_notes = source.read("overrides/IC2-NewEra-info/WINDOWS-MINIMAP-r53.txt")
        notices = source.read("overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt")
        if old_notes not in notices:
            raise ValueError("Expected r53 info in third-party notices")
        replacements = {"modrinth.index.json": (json.dumps(index, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
                        OWN: own,
                        "overrides/options.txt": options,
                        "overrides/IC2-NewEra-info/WINDOWS-COMPLETE-r60.txt": info,
                        "overrides/IC2-NewEra-info/README-zh_CN.txt": info,
                        "overrides/IC2-NewEra-info/README-en_US.txt": english,
                        "overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt": notices.replace(old_notes, info)}
        removed = {"overrides/IC2-NewEra-info/WINDOWS-MINIMAP-r53.txt"}
        with ZipFile(partial, "w", compression=ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
            for entry in source.infolist():
                if entry.filename in replacements or entry.filename in removed:
                    continue
                info_entry = ZipInfo(entry.filename, date_time=entry.date_time)
                info_entry.compress_type = entry.compress_type
                info_entry.external_attr = entry.external_attr
                info_entry.create_system = entry.create_system
                with source.open(entry) as src, target.open(info_entry, "w", force_zip64=True) as dst:
                    shutil.copyfileobj(src, dst, 4 * 1024 * 1024)
            for name, data in replacements.items():
                target.writestr(name, data)
    with ZipFile(partial) as archive, ZipFile(BASE) as baseline:
        if archive.testzip() is not None:
            raise ValueError("Client archive integrity failed")
        names = archive.namelist()
        if len(names) != len({n.casefold() for n in names}):
            raise ValueError("Duplicate client archive path")
        if json.loads(archive.read("modrinth.index.json")) != index or digest_bytes(archive.read(OWN)) != expected.upper():
            raise ValueError("Client manifest or New Era JAR differs")
        for name in baseline.namelist():
            if name in removed or name in replacements:
                continue
            if digest_bytes(archive.read(name)) != digest_bytes(baseline.read(name)):
                raise ValueError("Unchanged client payload differs: " + name)
        if any(n.startswith("overrides/mods/") and n.endswith(".jar") and n != OWN for n in names):
            raise ValueError("Unexpected embedded third-party JAR")
        if len(index["files"]) != 37 or any(v["path"] == resource_path for v in index["files"]):
            raise ValueError("Unexpected resource pack manifest")
        if archive.read("overrides/options.txt") != options:
            raise ValueError("Default resource pack selection differs")
        if not any(v["path"] == "shaderpacks/iterationT 3.2.0.zip" for v in index["files"]):
            raise ValueError("ITT shader entry missing")
    partial.replace(output)
    checksum = sha(output)
    output.with_name(NAME + ".sha256").write_text(checksum + "  " + NAME + "\n", encoding="ascii")
    print(json.dumps({"client": str(output), "sha256": checksum, "sizeBytes": output.stat().st_size,
                      "mods": 37, "downloads": 37, "newEraSha256": expected.upper()}, ensure_ascii=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    build(args.jar, args.sha256)
