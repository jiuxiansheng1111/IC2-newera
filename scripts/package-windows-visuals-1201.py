"""Build the Windows r50 visual pack from the reviewed r49 client archive.

Third-party visual ZIPs stay unmodified and are fetched through the Modrinth
index. --install-to installs the two assets into an existing, closed profile.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlparse
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build-isolated/player-packaging/20261029-r50-windows-visuals"
LOCK = ROOT / "scripts/player-visuals-lock-1201.json"
BASE = ROOT / "dist/IC2-New-Era-1.20.1-Forge-47.4.28-r49.mrpack"
BASE_SHA256 = "B8DF785FC99FC66838B37EB158201113720413C1B58AC71D317842BD6554E4AD"
OWN_JAR = "ic2newera-0.2.0-migration1201-all.jar"
OWN_SHA256 = "62F360868F18BF738E0F5CBD30302F569CDDBA33FAD793CBB15566F6F3962EE9"
PACK_NAME = "IC2-New-Era-Windows-1.20.1-r50-Visuals.mrpack"


def sha(path: Path, algorithm: str = "sha256") -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def visual_data() -> tuple[dict, dict[str, bytes]]:
    lock = json.loads(LOCK.read_text(encoding="utf-8-sig"))
    if lock["target"] != {"minecraft": "1.20.1", "forge": "47.4.28", "platform": "Windows x64"}:
        raise ValueError("Unexpected visual pack target")
    if sorted(row["kind"] for row in lock["assets"]) != ["resourcepack", "shader"]:
        raise ValueError("Expected exactly one shader and one resource pack")
    source = ROOT / lock["sourceDirectory"]
    for row in lock["assets"]:
        if Path(row["file"]).name != row["file"] or not row["file"].endswith(".zip"):
            raise ValueError("Invalid visual ZIP name")
        uri = urlparse(row["source"])
        if uri.scheme != "https" or uri.hostname != "cdn.modrinth.com" or uri.username:
            raise ValueError("Visual asset must use its official Modrinth CDN URL")
        path = source / row["file"]
        if path.stat().st_size != row["sizeBytes"]:
            raise ValueError("Visual ZIP size mismatch")
        for algorithm in ("sha1", "sha256", "sha512"):
            if sha(path, algorithm).lower() != row[algorithm].lower():
                raise ValueError("Visual ZIP checksum mismatch")
        with ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError("Corrupt visual ZIP")
            if row["kind"] == "resourcepack":
                if json.loads(archive.read("pack.mcmeta"))["pack"]["pack_format"] != 15:
                    raise ValueError("Resource pack must target the Minecraft 1.20.1 format")
            else:
                props = archive.read("shaders/shaders.properties").decode("utf-8-sig")
                if "profile.MEDIUM" not in props or "iris.features.required" in props:
                    raise ValueError("Unexpected shader feature requirements or profile")
    shader = next(r for r in lock["assets"] if r["kind"] == "shader")
    resource = next(r for r in lock["assets"] if r["kind"] == "resourcepack")
    oculus = ("# IC2 New Era Windows visual defaults\n"
              f"shaderPack={shader['file']}\nenableShaders=true\n"
              "maxShadowRenderDistance=12\ncolorSpace=SRGB\n")
    settings = ("# Complementary Reimagined MEDIUM preset; unmodified upstream ZIP\n"
                "SHADOW_QUALITY=2\nshadowDistance=128.0\nWATER_REFLECT_QUALITY=2\n"
                "BLOCK_REFLECT_QUALITY=1\nLIGHTSHAFT_QUALI_DEFINE=1\nSSAO_QUALI_DEFINE=2\n"
                "FXAA_DEFINE=1\nDETAIL_QUALITY=2\nCLOUD_QUALITY=2\nANISOTROPIC_FILTER=0\n"
                "COLORED_LIGHTING=0\nWORLD_SPACE_REFLECTIONS=-1\nENTITY_SHADOW=-1\nRAIN_PUDDLES=0\n")
    return lock, {
        "config/oculus.properties": oculus.encode("utf-8"),
        "shaderpacks/" + shader["file"] + ".txt": settings.encode("utf-8"),
        "options.txt": ("lang:zh_cn\nguiScale:0\nresourcePacks:"
                        + json.dumps(["vanilla", "file/" + resource["file"]], ensure_ascii=False)
                        + "\n").encode("utf-8")
    }


def build_pack() -> dict:
    lock, defaults = visual_data()
    if sha(BASE).upper() != BASE_SHA256:
        raise ValueError("The reviewed base r49 package has changed")
    output = OUT / PACK_NAME
    partial = output.with_suffix(".mrpack.partial")
    if output.exists() or partial.exists():
        raise FileExistsError("Output already exists; preserve the previous delivery")
    with ZipFile(BASE) as base:
        index = json.loads(base.read("modrinth.index.json"))
        if len(index["files"]) != 30 or index["dependencies"] != {"minecraft": "1.20.1", "forge": "47.4.28"}:
            raise ValueError("Unexpected base dependency set")
        for row in lock["assets"]:
            folder = "shaderpacks" if row["kind"] == "shader" else "resourcepacks"
            index["files"].append({"path": folder + "/" + row["file"],
                "hashes": {"sha1": row["sha1"], "sha512": row["sha512"]},
                "env": {"client": "required", "server": "unsupported"},
                "downloads": [row["source"]], "fileSize": row["sizeBytes"]})
        index["versionId"] = "0.2.0-windows.20261029-r50-visuals"
        index["name"] = "IC2：新纪元 Windows（互补光影＋平滑材质）"
        index["summary"] = "31个模组；AE2仓储、连锁采集、新纪元原生进度；Complementary Reimagined r5.9.3＋Bare Bones 1.1.7；Windows Java 17。"
        replacements = {"overrides/" + k: v for k, v in defaults.items()}
        replacements["modrinth.index.json"] = json_bytes(index)
        replacements["overrides/IC2-NewEra-info/WINDOWS-VISUALS-r50.txt"] = (
            "Windows客户端r50：保留r49全部31个模组、AE2、连锁采集和原生进度。\n"
            "Complementary Reimagined r5.9.3（Complementary Development）：https://www.complementary.dev/\n"
            "Bare Bones 1.1.7（RobotPants）：https://modrinth.com/resourcepack/bare-bones\n"
            "两项均使用Modrinth清单从作者CDN下载，未修改或重新上传原始ZIP。图形整合问题由本整合包维护者负责。\n"
            "默认启用材质和光影中档预设，实际表现视显卡、分辨率和场景而定；本轮未启动游戏进行性能验收。\n"
            "光影开关：选项→视频设置→光影包；材质开关：选项→资源包。\n"
            "首次导入需联网下载30个模组依赖和2个视觉包，包内包含新纪元本体及Windows便携Java17。\n"
            "服务器继续使用r49；本轮只改变Windows客户端的视觉资源。\n").encode("utf-8")
        replacements["overrides/IC2-NewEra-info/README-zh_CN.txt"] = (
            replacements["overrides/IC2-NewEra-info/WINDOWS-VISUALS-r50.txt"]
            + b"\n----- r48 gameplay reference (historical status) -----\n"
            + base.read("overrides/IC2-NewEra-info/README-zh_CN.txt"))
        with ZipFile(partial, "w", compression=ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
            for info in base.infolist():
                if info.filename in replacements:
                    continue
                with base.open(info) as source, target.open(info, "w", force_zip64=True) as dest:
                    shutil.copyfileobj(source, dest, 4 * 1024 * 1024)
            for name, value in replacements.items():
                target.writestr(name, value)
    with ZipFile(partial) as archive:
        if archive.testzip() is not None:
            raise ValueError("Package archive integrity check failed")
        if json.loads(archive.read("modrinth.index.json")) != index:
            raise ValueError("Modrinth index read-back mismatch")
        names = archive.namelist()
        if len(names) != len(set(n.casefold() for n in names)):
            raise ValueError("Case-insensitive duplicate archive entries")
        if any(n.startswith(("overrides/shaderpacks/", "overrides/resourcepacks/")) and n.endswith(".zip") for n in names):
            raise ValueError("Third-party visual ZIP must not be reuploaded")
        if hashlib.sha256(archive.read("overrides/mods/" + OWN_JAR)).hexdigest().upper() != OWN_SHA256:
            raise ValueError("The New Era JAR differs from the installed server")
    partial.replace(output)
    receipt = {"archive": output.name, "sizeBytes": output.stat().st_size, "sha256": sha(output).upper(),
        "baseSha256": BASE_SHA256, "modCount": 31, "downloadEntries": 32,
        "assets": lock["assets"], "shaderProfile": "MEDIUM", "thirdPartyVisualZipRedistributed": False,
        "archiveReadBackVerified": True, "gameExecuted": False, "testsRun": False,
        "createdAt": datetime.now(timezone(timedelta(hours=8))).isoformat()}
    (OUT / "windows-visual-pack-audit.json").write_bytes(json_bytes(receipt))
    print(json.dumps(receipt, ensure_ascii=False, indent=2))
    return receipt


def install_to(profile: Path) -> None:
    profile = profile.absolute()
    for candidate in [profile, *profile.parents]:
        if candidate.stat().st_file_attributes & 0x400:
            raise ValueError("Profile path contains a reparse point")
    config = json.loads((profile / (profile.name + ".json")).read_text(encoding="utf-8-sig"))
    if not any(r.get("name") == "net.minecraftforge:fmlloader:1.20.1-47.4.28" for r in config["libraries"]):
        raise ValueError("Target is not the approved 1.20.1 Forge profile")
    if sha(profile / "mods" / OWN_JAR).upper() != OWN_SHA256:
        raise ValueError("Install the matching r49 gameplay JAR first")
    process = subprocess.run(["powershell.exe", "-NoProfile", "-Command",
        "@(Get-Process -Name java,javaw -ErrorAction SilentlyContinue).Count"],
        capture_output=True, text=True, check=True)
    if int(process.stdout.strip()) != 0:
        raise RuntimeError("Close Minecraft normally before installing visual resources")
    lock, defaults = visual_data()
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = OUT / "installation-backups" / stamp
    backup.mkdir(parents=True)
    writes = {k: v for k, v in defaults.items() if k != "options.txt"}
    options_path = profile / "options.txt"
    options = options_path.read_text(encoding="utf-8-sig")
    resource = next(r for r in lock["assets"] if r["kind"] == "resourcepack")
    line = re.search(r"^resourcePacks:(.*)$", options, re.M)
    selected = json.loads(line[1]) if line else []
    if "vanilla" not in selected:
        selected.insert(0, "vanilla")
    item = "file/" + resource["file"]
    if item not in selected:
        selected.append(item)
    updated_line = "resourcePacks:" + json.dumps(selected, ensure_ascii=False, separators=(",", ":"))
    options = re.sub(r"^resourcePacks:.*$", lambda _: updated_line, options, flags=re.M) if line else options + "\n" + updated_line + "\n"
    writes["options.txt"] = options.encode("utf-8")
    for row in lock["assets"]:
        folder = "shaderpacks" if row["kind"] == "shader" else "resourcepacks"
        writes[folder + "/" + row["file"]] = ROOT / lock["sourceDirectory"] / row["file"]
    for relative, data in writes.items():
        destination = profile / relative
        for candidate in [destination, *destination.parents]:
            if candidate.exists() and candidate.stat().st_file_attributes & 0x400:
                raise ValueError("Target contains a reparse point")
        if destination.exists():
            previous = backup / relative
            previous.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(destination, previous)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, bytes):
            destination.write_bytes(data)
            assert destination.read_bytes() == data
        else:
            shutil.copy2(data, destination)
            assert sha(destination) == sha(data)
    receipt = {"installedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(), "profile": str(profile),
        "backup": str(backup), "installedFiles": list(writes), "assets": lock["assets"],
        "shaderProfile": "MEDIUM", "enabledInConfiguration": True, "gameplayVerified": False}
    (OUT / "windows-visual-installation.json").write_bytes(json_bytes(receipt))
    print(json.dumps({"installedFiles": len(writes), "profile": str(profile), "backup": str(backup)}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-to", type=Path)
    parser.add_argument("--install-only", action="store_true")
    args = parser.parse_args()
    if not args.install_only:
        build_pack()
    if args.install_to:
        install_to(args.install_to)
