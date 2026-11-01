"""Build a full local server package with the matching user-supplied YSM version."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STEM = "IC2-New-Era-Server-1.20.1-r60-Complete"
WORK = ROOT / "build-isolated/server-packaging/20261030-r60-complete"
LAUNCHER = ROOT / "build-isolated/server-packaging/20261028-r48-full-server/launcher/server.jar"
LAUNCHER_HASH = "603D3BC2C0C52C87E0C61D128514FF33D373E50AE5CDE917AEABA90C77292590"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main(jar: Path, expected: str) -> None:
    if sha(jar) != expected.upper() or sha(LAUNCHER) != LAUNCHER_HASH:
        raise ValueError("New Era or launcher JAR has changed")
    WORK.mkdir(parents=True, exist_ok=True)
    template = WORK / "template"
    if template.exists():
        raise FileExistsError("Preserve the existing server packaging workpoint")
    shutil.copytree(ROOT / "scripts/server-pack-1201", template)
    old = (template / "README.zh-CN.md").read_text(encoding="utf-8-sig")
    marker = "## 推荐启动"
    if marker not in old:
        raise ValueError("Unexpected server README")
    header = ("# IC2：新纪元 1.20.1 Forge 完整服务器 r60\n\n"
              "本包包括新纪元、Create、IC2 Refactored、Create: Propulsion 和 YSM 2.6.3，"
              "共 26 个服务端模组。新纪元新增初级/高级电动机、初级/高级机械发电机、万能变压器和原版进度，"
              "并包含合金炉掉落与堆叠流体单元兼容修复。客户端与服务器必须同时使用 r60 新纪元。\n\n"
              "更新已有服务器前请正常停服并备份；只替换 mods 内的新纪元 JAR。若 YSM 已安装，"
              "保留且核对它是 2.6.3，绝不要同时放两个 YSM 版本。已有 world、config、server.properties、"
              "语音和自行安装的其他模组不应被本包默认文件覆盖。客户端专用小地图、光影等不放服务器。"
              "修复、机器运行与多人联机尚未实机验收。\n\n"
              "全新部署需要 Java 17；此归档含 Forge 运行文件，但不含 Java，也不含世界或用户凭据。\n\n")
    (template / "README.zh-CN.md").write_text(header + old[old.index(marker):], encoding="utf-8")
    lock = json.loads((ROOT / "scripts/player-enhancements-lock-1201.json").read_text(encoding="utf-8-sig"))
    rows = [r for r in lock["mods"] if r.get("modId") == "yes_steve_model"]
    if len(rows) != 1 or rows[0]["version"] != "2.6.3-forge+mc1.20.1":
        raise ValueError("Unexpected YSM lock")
    rows[0]["environment"] = "both"
    lock_path = WORK / "server-enhancements-lock.json"
    lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    spec = importlib.util.spec_from_file_location("server_builder", ROOT / "scripts/package-dedicated-server-1201.py")
    if spec is None or spec.loader is None:
        raise RuntimeError("Missing dedicated server builder")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    builder.ENHANCEMENTS_LOCK = lock_path
    builder.TEMPLATE = template
    builder.OUT_DIR = WORK / "output"
    builder.ARCHIVE_STEM = STEM
    builder.TOP = STEM
    builder.TAR_NAME = STEM + ".tar.gz"
    builder.ZIP_NAME = STEM + ".zip"
    sys.argv = ["package-dedicated-server-1201.py", "--own-jar", str(jar), "--own-sha256", expected,
                "--launcher-jar", str(LAUNCHER), "--launcher-sha256", LAUNCHER_HASH,
                "--launcher-source", "tools/server-launcher/src/com/ic2newera/serverlaunch/ServerLauncher.java"]
    builder.main()
    archive = builder.OUT_DIR / builder.ZIP_NAME
    target = ROOT / "dist" / archive.name
    if target.exists():
        raise FileExistsError("Refusing to overwrite final server package")
    shutil.copy2(archive, target)
    digest = sha(target)
    if digest != sha(archive):
        raise ValueError("Copied server ZIP differs")
    target.with_name(target.name + ".sha256").write_text(digest + "  " + target.name + "\n", encoding="ascii")
    print(json.dumps({"serverZip": str(target), "sha256": digest, "sizeBytes": target.stat().st_size}, ensure_ascii=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    main(args.jar, args.sha256)
