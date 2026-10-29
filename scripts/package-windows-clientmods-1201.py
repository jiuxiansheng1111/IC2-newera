"""Create the r52 client package from r51 and five user-specified mods.

Third-party JARs are referenced by original Modrinth downloads and checksums,
not included in the archive. All non-targeted r51 files are preserved.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
from datetime import datetime
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile
from itt_shader_1201 import configure_oculus, shader_data

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "dist/IC2-New-Era-Windows-1.20.1-r51-YSM.mrpack"
if not BASE.is_file():
    BASE = ROOT / "build-isolated/player-packaging/20261029-r53-minimap/previous-clients/IC2-New-Era-Windows-1.20.1-r51-YSM.mrpack"
BASE_SHA = "5A7FAA0E5FB5E05C9E9BEC175F808C954CC5E71B4A5D8F045206FADBBA22F05C"
NEWERA = "ic2newera-0.2.0-migration1201-all.jar"
OLD_NEWERA_SHA = "ECF0AE12AC41A685BAF72C7343F380233715F1769BE3129309EB063C68D9D1A7"
NEW_NEWERA_SHA = "461F72EB1335C8DE64FC00D0719FFE75689B63A4E2D327A8FB2B76F49AF1916F"
NEWERA_SOURCE = ROOT / "build-isolated/migration-1201/newera/build/libs" / NEWERA
DEPS = ROOT / "build-isolated/migration-1201/dependencies"
OUT = ROOT / "build-isolated/player-packaging/20261029-r52-clientmods"
LOCK = ROOT / "scripts/player-enhancements-lock-1201.json"
NAME = "IC2-New-Era-Windows-1.20.1-r52-ClientMods.mrpack"
INTERIM_SHA = "A93012A659F9C98249F861BF72F83161F66D3730862A685B88926AF3A38E94A4"
NEW_IDS = {"searchonmcmod", "freecam", "yes_steve_model", "imblocker", "cloth_config"}
OLD_YSM = "mods/ysm-2.6.5-forge+mc1.20.1-release.jar"


def sha(path: Path, algorithm: str = "sha256") -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def jbytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def build() -> None:
    shader, _, settings = shader_data()
    if sha(BASE).upper() != BASE_SHA or sha(NEWERA_SOURCE).upper() != NEW_NEWERA_SHA:
        raise ValueError("The reviewed base or New Era build differs")
    lock = json.loads(LOCK.read_text(encoding="utf-8-sig"))
    if lock["revision"] not in ("r52", "r53") or lock["target"] != {"minecraft": "1.20.1", "forge": "47.4.28", "java": 17}:
        raise ValueError("Wrong client dependency lock")
    mods = [row for row in lock["mods"] if row["modId"] in NEW_IDS]
    if len(mods) != 5 or {row["modId"] for row in mods} != NEW_IDS:
        raise ValueError("Expected exactly the five requested mods")
    if next(row for row in mods if row["modId"] == "yes_steve_model")["version"] != "2.6.3-forge+mc1.20.1":
        raise ValueError("The r52 YSM version differs")
    for row in mods:
        path = DEPS / row["file"]
        if path.name != row["file"] or path.stat().st_size != row["sizeBytes"]:
            raise ValueError("Invalid dependency file")
        if any(sha(path, alg).lower() != row[alg].lower() for alg in ("sha1", "sha256", "sha512")):
            raise ValueError("Dependency hash mismatch: " + row["modId"])
        if not row["source"].startswith("https://cdn.modrinth.com/data/"):
            raise ValueError("Dependency is not pinned to the original Modrinth CDN")
    OUT.mkdir(parents=True, exist_ok=True)
    final_dir = OUT / "final"
    final_dir.mkdir(parents=True, exist_ok=True)
    output, partial = final_dir / NAME, final_dir / (NAME + ".partial")
    published = ROOT / "dist" / NAME
    if output.exists() or partial.exists():
        raise FileExistsError("Refusing to overwrite an existing r52 artifact")
    if published.exists() and sha(published).upper() != INTERIM_SHA:
        raise ValueError("The interim r52 package changed")
    with ZipFile(BASE) as base:
        index = json.loads(base.read("modrinth.index.json"))
        if len(index["files"]) != 33 or index["dependencies"] != {"minecraft": "1.20.1", "forge": "47.4.28"}:
            raise ValueError("Unexpected r51 manifest")
        if hashlib.sha256(base.read("overrides/mods/" + NEWERA)).hexdigest().upper() != OLD_NEWERA_SHA:
            raise ValueError("The r51 New Era JAR differs")
        entries = [row for row in index["files"] if row["path"] != OLD_YSM]
        if len(entries) != 32:
            raise ValueError("Expected exactly one previous YSM")
        for mod in mods:
            entries.append({"path": "mods/" + mod["file"],
                            "hashes": {"sha1": mod["sha1"], "sha512": mod["sha512"]},
                            "env": {"client": "required", "server": "unsupported"},
                            "downloads": [mod["source"]], "fileSize": mod["sizeBytes"]})
        old_shader = "shaderpacks/ComplementaryReimagined_r5.9.3.zip"
        if len([row for row in entries if row["path"] == old_shader]) != 1:
            raise ValueError("Expected exactly one previous shader")
        entries = [row for row in entries if row["path"] != old_shader]
        entries.append({"path": "shaderpacks/" + shader["file"],
                        "hashes": {"sha1": shader["sha1"], "sha512": shader["sha512"]},
                        "env": {"client": "required", "server": "unsupported"},
                        "downloads": [shader["source"]], "fileSize": shader["sizeBytes"]})
        index.update({"files": entries, "versionId": "0.2.0-windows.20261029-r52-clientmods",
                      "name": "IC2：新纪元 Windows（客户端便捷模组）",
                      "summary": "36个模组，新增MC百科搜索、自由视角、输入法修复与配置库，YSM切换至2.6.3。"})
        info = (
            "Windows客户端r52，Minecraft 1.20.1 / Forge 47.4.28：共36个模组。\n"
            "加入MC百科搜索0.1.0、Freecam 1.2.1、IMBlocker 5.6.2.1、Cloth Config 11.1.136；"
            "YSM按用户指定改为2.6.3。五个作者原始JAR通过Modrinth清单下载。\n"
            "鼠标指向物品按B搜索MC百科；F4切换自由视角；Alt+Y打开YSM模型选择。"
            "可在控制设置中修改按键。\n"
            "多人完整YSM同步须核对客户端和服务器端的模组版本；服务器由用户自行维护，尚未核验。\n"
            "光影：iterationT 3.2.0（Tahnass），默认Medium预设，由清单从公开的CurseForge CDN下载。\n"
            "作者发布页：https://www.minegraph.cn/shaderpacks/12\n"
            "零雾构想材质包仍待用户提供；当前保留Bare Bones材质。\n"
            "游戏启动、模组联用、光影和多人同步尚未实机验收。\n"
        ).encode("utf-8")
        notices = base.read("overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt")
        old_info = base.read("overrides/IC2-NewEra-info/WINDOWS-YSM-r51.txt")
        if old_info not in notices:
            raise ValueError("Expected r51 notes in third-party notices")
        notices = notices.replace(old_info, info)
        replacements = {"modrinth.index.json": jbytes(index),
                        "overrides/mods/" + NEWERA: NEWERA_SOURCE.read_bytes(),
                        "overrides/config/oculus.properties": configure_oculus(
                            base.read("overrides/config/oculus.properties").decode("utf-8"), shader["file"]).encode("utf-8"),
                        "overrides/shaderpacks/" + shader["file"] + ".txt": settings,
                        "overrides/IC2-NewEra-info/WINDOWS-CLIENTMODS-r52.txt": info,
                        "overrides/IC2-NewEra-info/README-zh_CN.txt": info,
                        "overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt": notices}
        removed = {"overrides/IC2-NewEra-info/WINDOWS-YSM-r51.txt",
                   "overrides/shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt"}
        with ZipFile(partial, "w", compression=ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
            for entry in base.infolist():
                if entry.filename in replacements or entry.filename in removed:
                    continue
                with base.open(entry) as source, target.open(entry, "w", force_zip64=True) as dest:
                    shutil.copyfileobj(source, dest, 4 * 1024 * 1024)
            for name, data in replacements.items():
                target.writestr(name, data)
    with ZipFile(partial) as z:
        if z.testzip() is not None or json.loads(z.read("modrinth.index.json")) != index:
            raise ValueError("Archive verification failed")
        names = z.namelist()
        if len(names) != len({name.casefold() for name in names}):
            raise ValueError("Duplicate archive entries")
        if OLD_YSM in [row["path"] for row in index["files"]]:
            raise ValueError("Old YSM still present")
        if any(n.startswith("overrides/mods/") and n.endswith(".jar") and n != "overrides/mods/" + NEWERA for n in names):
            raise ValueError("Third-party JAR must not be embedded")
        if any(n.startswith(("overrides/shaderpacks/", "overrides/resourcepacks/")) and n.endswith(".zip") for n in names):
            raise ValueError("Third-party visual ZIP must not be embedded")
        if hashlib.sha256(z.read("overrides/mods/" + NEWERA)).hexdigest().upper() != NEW_NEWERA_SHA:
            raise ValueError("New Era JAR read-back failed")
        if len(index["files"]) != 37:
            raise ValueError("Unexpected download count")
    partial.replace(output)
    checksum = sha(output).upper()
    replacement = published.with_name(published.name + ".partial")
    if replacement.exists():
        raise FileExistsError("A published partial file already exists")
    shutil.copy2(output, replacement)
    if sha(replacement).upper() != checksum:
        raise ValueError("Published archive differs")
    if published.exists():
        shutil.copy2(published, OUT / "r52-before-itt.mrpack")
        previous_sum = published.parent / (NAME + ".sha256")
        if previous_sum.exists():
            shutil.copy2(previous_sum, OUT / "r52-before-itt.mrpack.sha256")
    os.replace(replacement, published)
    (published.parent / (NAME + ".sha256")).write_text(checksum + "  " + NAME + "\n", encoding="ascii")
    receipt = {"archive": str(published), "sha256": checksum, "sizeBytes": published.stat().st_size,
               "baseSha256": BASE_SHA, "newEraSha256": NEW_NEWERA_SHA, "modCount": 36,
               "downloadEntries": len(entries), "newMods": mods,
               "shader": {"title": shader["title"], "version": shader["version"], "sha256": shader["sha256"],
                          "source": shader["source"], "profile": "Medium", "authorChecksumVerified": False},
               "thirdPartyAssetsEmbedded": False, "gameExecuted": False, "testsRun": False,
               "createdAt": datetime.now().astimezone().isoformat()}
    (OUT / "package-receipt.json").write_bytes(jbytes(receipt))
    print(json.dumps(receipt, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    build()
