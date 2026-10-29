"""Add the requested YSM release to the existing r50 Windows client package.

Third-party files remain with their authors and are downloaded via the
Modrinth index. This program does not install or change a running game.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from datetime import datetime
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "dist/IC2-New-Era-Windows-1.20.1-r50-Visuals.mrpack"
BASE_SHA = "D49AD74AE7644146F42359ABD1E0F22B0B9B1DFEE9566E1AED91E3277ED53014"
OWN_JAR = "ic2newera-0.2.0-migration1201-all.jar"
BASE_OWN_SHA = "62F360868F18BF738E0F5CBD30302F569CDDBA33FAD793CBB15566F6F3962EE9"
INTERIM_SHA = "2E0B4372A927B60D2A7F8FB1D1011D41B9A954159010594014DDD20A98096B63"
OWN_SOURCE = ROOT / "build-isolated/migration-1201/newera/build/libs/ic2newera-0.2.0-migration1201-all.jar"
OUT = ROOT / "build-isolated/player-packaging/20261029-r51-ysm"
NAME = "IC2-New-Era-Windows-1.20.1-r51-YSM.mrpack"
LOCK = ROOT / "scripts/player-enhancements-lock-1201.json"
BARE = "Bare Bones 1.20.zip"
YSM_URL = "https://cdn.modrinth.com/data/86xjpqqS/versions/Zqooxsd2/ysm-2.6.5-forge%2Bmc1.20.1-release.jar"
YSM_SHA1 = "151ac7b24da8beeca1a20864565743cfd77af286"
YSM_SHA512 = "f3b2c5e7a6928582c7af58c1011a7347558af221389d32d3727af93cdf21f8971de634be3ce62a03859f60f6272f767e037b5ae22918d6dcec37548f56664aa7"
YSM_SHA256 = "25B5E902B96F4C298690208F8B433CBC31737C23F87590354DBD86F00207BC8F"


def sha(path: Path, algorithm: str = "sha256") -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def js(value: object) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def build(vanilla: bool, own_sha: str) -> None:
    if sha(BASE).upper() != BASE_SHA:
        raise ValueError("Reviewed r50 base pack differs")
    if len(own_sha) != 64 or sha(OWN_SOURCE).upper() != own_sha.upper():
        raise ValueError("The reviewed New Era build differs")
    lock = json.loads(LOCK.read_text(encoding="utf-8-sig"))
    ysm_rows = [x for x in lock["mods"] if x["modId"] == "yes_steve_model"]
    if len(ysm_rows) != 1:
        raise ValueError("YSM must have one enhancement lock entry")
    ysm = ysm_rows[0]
    if (ysm["version"] != "2.6.5-forge+mc1.20.1" or ysm["sha256"] != YSM_SHA256
            or ysm["source"] != YSM_URL or ysm["sizeBytes"] != 63269843):
        raise ValueError("Unexpected YSM lock")
    source = ROOT / "build-isolated/migration-1201/dependencies" / ysm["file"]
    if source.stat().st_size != ysm["sizeBytes"] or sha(source).upper() != YSM_SHA256:
        raise ValueError("The supplied YSM file failed verification")
    for alg, expected in (("sha1", YSM_SHA1), ("sha512", YSM_SHA512)):
        if sha(source, alg) != expected:
            raise ValueError("YSM file differs from the author's Modrinth release")
    with ZipFile(source) as z:
        if z.testzip() is not None or b'modId="yes_steve_model"' not in z.read("META-INF/mods.toml"):
            raise ValueError("Invalid YSM archive")
    final_dir = OUT / "final"
    final_dir.mkdir(parents=True, exist_ok=True)
    output = final_dir / NAME
    partial = final_dir / (NAME + ".partial")
    published = ROOT / "dist" / NAME
    if output.exists() or partial.exists():
        raise FileExistsError("Preserve the previous final r51 package")
    if published.exists() and sha(published).upper() != INTERIM_SHA:
        raise ValueError("The interim package changed; refusing to replace it")
    with ZipFile(BASE) as base:
        index = json.loads(base.read("modrinth.index.json"))
        if len(index["files"]) != 32 or index["dependencies"] != {"minecraft": "1.20.1", "forge": "47.4.28"}:
            raise ValueError("Unexpected r50 dependency set")
        if hashlib.sha256(base.read("overrides/mods/" + OWN_JAR)).hexdigest().upper() != BASE_OWN_SHA:
            raise ValueError("Base New Era JAR differs")
        if vanilla:
            index["files"] = [x for x in index["files"] if x["path"] != "resourcepacks/" + BARE]
        index["files"].append({"path": "mods/" + ysm["file"],
                               "hashes": {"sha1": YSM_SHA1, "sha512": YSM_SHA512},
                               "env": {"client": "required", "server": "unsupported"},
                               "downloads": [YSM_URL], "fileSize": ysm["sizeBytes"]})
        index["versionId"] = "0.2.0-windows.20261029-r51-ysm"
        index["name"] = "IC2：新纪元 Windows（YSM）"
        index["summary"] = "32个模组，新增是，史蒂夫模型；Windows Java 17。"
        texture_text = "原版材质" if vanilla else "Bare Bones 平滑材质（可在选项→资源包切换）"
        info = (
            "Windows客户端r51：保留r50的31个模组，加入是，史蒂夫模型 2.6.5 Forge。\n"
            "新纪元客户端 JAR 更新内置 AI 的 YSM 使用说明，游戏代码与服务器 r49 相同。\n"
            f"视觉设置：{texture_text}；保留 Complementary Reimagined r5.9.3 中档光影。\n"
            "作者原始模组与光影通过 Modrinth 清单下载，未打包重传。\n"
            "模型选择：进入游戏后按 Alt+Y，若无响应请检查按键冲突。\n"
            "多人模型完整同步需服务器也安装相同版本；服务器模型由服主管理。\n"
            "实际启动、导入、模型渲染、多人同步和光影帧率仍需实机确认。\n"
            "YSM：https://modrinth.com/mod/yes-steve-model/version/2.6.5-forge%2Bmc1.20.1\n"
            "说明：https://ysm.cfpa.team/wiki/install/\n"
        ).encode("utf-8")
        replacements = {"modrinth.index.json": js(index),
                        "overrides/mods/" + OWN_JAR: OWN_SOURCE.read_bytes(),
                        "overrides/IC2-NewEra-info/WINDOWS-YSM-r51.txt": info,
                        "overrides/IC2-NewEra-info/README-zh_CN.txt": info,
                        "overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt":
                            base.read("overrides/IC2-NewEra-info/THIRD-PARTY-NOTICES.txt") + b"\n" + info}
        if vanilla:
            options = base.read("overrides/options.txt").decode("utf-8")
            options = options.replace('resourcePacks:["vanilla", "file/' + BARE + '"]',
                                      'resourcePacks:["vanilla"]')
            if BARE in options or 'resourcePacks:["vanilla"]' not in options:
                raise ValueError("Unexpected r50 options structure")
            replacements["overrides/options.txt"] = options.encode("utf-8")
        removed = {"overrides/IC2-NewEra-info/WINDOWS-VISUALS-r50.txt"} if vanilla else set()
        with ZipFile(partial, "w", compression=ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
            for entry in base.infolist():
                if entry.filename in replacements or entry.filename in removed:
                    continue
                with base.open(entry) as src, target.open(entry, "w", force_zip64=True) as dst:
                    shutil.copyfileobj(src, dst, 4 * 1024 * 1024)
            for name, value in replacements.items():
                target.writestr(name, value)
    with ZipFile(partial) as z:
        if z.testzip() is not None or json.loads(z.read("modrinth.index.json")) != index:
            raise ValueError("Package failed read-back verification")
        names = z.namelist()
        if len(names) != len(set(n.casefold() for n in names)):
            raise ValueError("Duplicate archive paths")
        if any(n.startswith("overrides/mods/") and n.endswith(".jar") and n != "overrides/mods/" + OWN_JAR for n in names):
            raise ValueError("Third-party mod JAR must not be embedded")
        if any(n.startswith(("overrides/resourcepacks/", "overrides/shaderpacks/")) and n.endswith(".zip") for n in names):
            raise ValueError("Third-party visual ZIP must not be embedded")
        if hashlib.sha256(z.read("overrides/mods/" + OWN_JAR)).hexdigest().upper() != own_sha.upper():
            raise ValueError("The New Era JAR changed")
    partial.replace(output)
    checksum = sha(output).upper()
    replacement = published.with_name(published.name + ".partial")
    if replacement.exists():
        raise FileExistsError("A published partial file already exists")
    shutil.copy2(output, replacement)
    if sha(replacement).upper() != checksum:
        raise ValueError("Published file hash mismatch")
    if published.exists():
        shutil.copy2(published, OUT / "r51-before-ai.mrpack")
        previous_sum = published.parent / (published.name + ".sha256")
        if previous_sum.exists():
            shutil.copy2(previous_sum, OUT / "r51-before-ai.mrpack.sha256")
    os.replace(replacement, published)
    (published.parent / (published.name + ".sha256")).write_text(checksum + "  " + published.name + "\n", encoding="ascii")
    receipt = {"archive": str(published), "sha256": checksum, "sizeBytes": published.stat().st_size,
               "baseSha256": BASE_SHA, "modCount": 32, "downloadEntries": len(index["files"]),
               "vanillaTexture": vanilla, "ysmSha256": YSM_SHA256, "ysmSource": YSM_URL,
               "newEraSha256": own_sha.upper(),
               "thirdPartyAssetsEmbedded": False, "gameExecuted": False, "testsRun": False,
               "createdAt": datetime.now().astimezone().isoformat()}
    (OUT / "package-receipt.json").write_bytes(js(receipt))
    print(json.dumps(receipt, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vanilla", action="store_true", help="disable the r50 Bare Bones resource pack")
    parser.add_argument("--own-sha256", required=True, help="SHA-256 of the reviewed New Era client build")
    args = parser.parse_args()
    build(args.vanilla, args.own_sha256)
