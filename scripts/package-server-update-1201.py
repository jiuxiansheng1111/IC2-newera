"""Produce an updated local server archive without changing any live server or world."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "dist/IC2-New-Era-Server-1.20.1-r49-Full.zip"
if not BASE.is_file():
    BASE = ROOT / "build-isolated/player-packaging/20261030-r55-complete/previous-packages/IC2-New-Era-Server-1.20.1-r49-Full.zip"
BASE_HASH = "E4DA909EF73D0DCC45CA50FC1C72CD7C8863E5D153554C36AB0A1FA76E29B4DE"
OLD_ROOT = "IC2-New-Era-Server-1.20.1-r49-Full/"
OWN = "mods/ic2newera-0.2.0-migration1201-all.jar"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def file_sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def build(jar: Path, revision: str, description: str) -> None:
    if not revision.replace("-", "").isalnum() or file_sha(BASE) != BASE_HASH:
        raise ValueError("Unexpected source server archive or revision")
    stem = f"IC2-New-Era-Server-1.20.1-{revision}-Full"
    output = ROOT / "dist" / f"{stem}.zip"
    partial = output.with_suffix(".zip.partial")
    if output.exists() or partial.exists():
        raise FileExistsError("Refusing to overwrite an existing server package")
    new_jar = jar.read_bytes()
    new_hash = sha(new_jar)
    with ZipFile(jar) as project:
        if project.testzip() or b'modId="ic2newera"' not in project.read("META-INF/mods.toml"):
            raise ValueError("Invalid New Era JAR")
    with ZipFile(BASE) as source:
        manifest = json.loads(source.read(OLD_ROOT + "server-manifest.json"))
        if manifest["package"] != OLD_ROOT[:-1] or len(manifest["mods"]) != 25:
            raise ValueError("Unexpected baseline manifest")
        if not {"ic2", "create", "createpropulsion"}.issubset({m["modId"] for m in manifest["mods"]}):
            raise ValueError("Expected IC2 and Create")
        mods = [m for m in manifest["mods"] if m["modId"] == "ic2newera"]
        if len(mods) != 1 or mods[0]["file"] != Path(OWN).name:
            raise ValueError("Unexpected own mod entry")
        mods[0].update(sizeBytes=len(new_jar), sha256=new_hash)
        old_readme = source.read(OLD_ROOT + "README.zh-CN.md").decode("utf-8-sig").replace("\r\n", "\n")
        paragraphs = old_readme.split("\n\n", 2)
        if len(paragraphs) != 3:
            raise ValueError("Unexpected README format")
        readme = (f"# IC2：新纪元 1.20.1 Forge 专用服务器 {revision}\n\n"
                  f"{description}\n\n"
                  "本包基于 r49 完整服务器，包含 Create、IC2 Refactored、Create: Propulsion 和新纪元。"
                  "它不包含你自行安装的 YSM；已有服务器保留自己已安装的 YSM，并确认与客户端版本一致。"
                  "更新已有服务器时先正常停服并备份，只替换 mods 中的新纪元 JAR；"
                  "不要用本包的默认配置覆盖已有 world、config、server.properties 或其他自装模组。"
                  "新纪元客户端和服务器应使用配套版本。游戏中的修复与兼容性仍待实测。\n\n"
                  + paragraphs[2]).encode("utf-8")
        replacements = {OWN: new_jar, "README.zh-CN.md": readme}
        for entry in manifest["files"]:
            path = entry["path"]
            if path in replacements:
                entry.update(sizeBytes=len(replacements[path]), sha256=sha(replacements[path]))
        manifest["package"] = stem
        manifest["manifestNote"] = "The complete ZIP has a separate SHA-256 file; this manifest does not hash itself."
        replacements["server-manifest.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        with ZipFile(partial, "w", compression=ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
            for entry in source.infolist():
                if not entry.filename.startswith(OLD_ROOT):
                    raise ValueError("Unexpected ZIP root")
                rel = entry.filename[len(OLD_ROOT):]
                name = stem + "/" + rel
                info = ZipInfo(name, date_time=entry.date_time)
                info.compress_type = entry.compress_type
                info.external_attr = entry.external_attr
                info.create_system = entry.create_system
                if rel in replacements:
                    target.writestr(info, replacements[rel])
                else:
                    with source.open(entry) as src, target.open(info, "w", force_zip64=True) as dst:
                        shutil.copyfileobj(src, dst, 4 * 1024 * 1024)
    with ZipFile(partial) as verified:
        if verified.testzip() is not None:
            raise ValueError("Server ZIP integrity failed")
        names = verified.namelist()
        if len(names) != len({n.casefold() for n in names}):
            raise ValueError("Duplicate server ZIP path")
        for entry in manifest["files"]:
            data = verified.read(stem + "/" + entry["path"])
            if len(data) != entry["sizeBytes"] or sha(data) != entry["sha256"].upper():
                raise ValueError("Server manifest mismatch: " + entry["path"])
        if sha(verified.read(stem + "/" + OWN)) != new_hash:
            raise ValueError("New Era server JAR differs")
    partial.replace(output)
    checksum = file_sha(output)
    output.with_name(output.name + ".sha256").write_text(f"{checksum}  {output.name}\n", encoding="ascii")
    print(json.dumps({"path": str(output), "sha256": checksum, "sizeBytes": output.stat().st_size,
                      "newEraSha256": new_hash, "mods": 25, "ysmIncluded": False}, ensure_ascii=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--description", required=True)
    options = parser.parse_args()
    build(options.jar, options.revision, options.description)
