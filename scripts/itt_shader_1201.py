"""Verify and install the requested iterationT 3.2.0 shader on a closed client."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from datetime import datetime
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "scripts/player-itt32-lock-1201.json"
OUT = ROOT / "build-isolated/player-packaging/20261029-r52-clientmods"


def digest(path: Path, algorithm: str) -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def shader_data() -> tuple[dict, Path, bytes]:
    lock = json.loads(LOCK.read_text(encoding="utf-8-sig"))
    if lock["target"] != {"minecraft": "1.20.1", "forge": "47.4.28", "shaderLoader": "Oculus 1.8.0"}:
        raise ValueError("Unexpected shader target")
    if lock["file"] != "iterationT 3.2.0.zip" or lock["source"] != "https://edge.forgecdn.net/files/6825/165/iterationT%203.2.0.zip":
        raise ValueError("Unexpected shader source")
    local = ROOT / lock["localSource"]
    if local.stat().st_size != lock["sizeBytes"]:
        raise ValueError("Shader size mismatch")
    for algorithm in ("sha1", "sha256", "sha512"):
        if digest(local, algorithm).lower() != lock[algorithm].lower():
            raise ValueError("Shader hash mismatch")
    with ZipFile(local) as z:
        if z.testzip() is not None:
            raise ValueError("Shader ZIP failed integrity check")
        lang = z.read("shaders/lang/en_us.lang").decode("utf-8", "replace")
        properties = z.read("shaders/shaders.properties").decode("utf-8", "replace")
        if "option.iterationT_VERSION=iterationT by Tahnass" not in lang or "value.iterationT_VERSION.AT=3.2.0" not in lang:
            raise ValueError("Shader version/author marker mismatch")
        if re.search(r"^\s*iris\.features\.required\s*=", properties, re.M):
            raise ValueError("Shader declares additional required Iris features")
        match = re.search(r"^profile\.Medium\s*=\s*(.+)$", properties, re.M)
        if not match:
            raise ValueError("The expected Medium profile is absent")
        settings = match[1].split()
        if not settings or not all(re.fullmatch(r"[A-Z][A-Z0-9_]*=[A-Za-z0-9_.-]+", x) for x in settings):
            raise ValueError("Unexpected Medium profile syntax")
        keys = [x.split("=", 1)[0] for x in settings]
        if len(keys) != len(set(keys)):
            raise ValueError("Duplicate settings in Medium profile")
    result = ("# iterationT 3.2.0 Medium preset from original shaders.properties\n" + "\n".join(settings) + "\n").encode("utf-8")
    return lock, local, result


def configure_oculus(text: str, name: str) -> str:
    for key, value in (("shaderPack", name), ("enableShaders", "true")):
        pattern = r"^" + key + r"=.*$"
        text = re.sub(pattern, lambda _: key + "=" + value, text, flags=re.M) if re.search(pattern, text, re.M) else text.rstrip("\n") + "\n" + key + "=" + value + "\n"
    return text


def is_running() -> bool:
    result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", "@(Get-Process -Name java,javaw -ErrorAction SilentlyContinue).Count"], capture_output=True, text=True, check=True)
    return int(result.stdout.strip()) != 0


def install(profile: Path) -> None:
    lock, local, settings = shader_data()
    profile = profile.absolute()
    if profile.name != "1.20.1-Forge_47.4.28":
        raise ValueError("Unexpected profile")
    for p in (profile, *profile.parents):
        if p.stat().st_file_attributes & 0x400:
            raise ValueError("Profile path contains a reparse point")
    launch = json.loads((profile / (profile.name + ".json")).read_text(encoding="utf-8-sig"))
    if not any(x.get("name") == "net.minecraftforge:fmlloader:1.20.1-47.4.28" for x in launch["libraries"]):
        raise ValueError("Forge profile does not match")
    if is_running():
        raise RuntimeError("Save and close Minecraft before installing shader files")
    config = profile / "config/oculus.properties"
    writes: dict[Path, bytes | Path] = {
        profile / "shaderpacks" / lock["file"]: local,
        profile / "shaderpacks" / (lock["file"] + ".txt"): settings,
        config: configure_oculus(config.read_text(encoding="utf-8-sig"), lock["file"]).encode("utf-8"),
    }
    for dest, source in writes.items():
        for p in (dest, *dest.parents):
            if p.exists() and p.stat().st_file_attributes & 0x400:
                raise ValueError("Destination contains a reparse point")
        if isinstance(source, Path) and dest.exists() and digest(dest, "sha256") != digest(source, "sha256"):
            raise ValueError("An unrecognized shader file already exists")
    backup = OUT / "shader-installation-backups" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup.mkdir(parents=True)
    existed: dict[Path, bool] = {}
    for dest in writes:
        existed[dest] = dest.exists()
        if dest.exists():
            previous = backup / dest.relative_to(profile)
            previous.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(dest, previous)
    if is_running():
        raise RuntimeError("Minecraft started during preflight; no profile files changed")
    completed: list[Path] = []
    try:
        for dest, source in writes.items():
            dest.parent.mkdir(parents=True, exist_ok=True)
            fd, name = tempfile.mkstemp(prefix=".ic2newera-itt-", dir=dest.parent)
            os.close(fd)
            temp = Path(name)
            try:
                if isinstance(source, Path):
                    shutil.copyfile(source, temp)
                else:
                    temp.write_bytes(source)
                os.replace(temp, dest)
                completed.append(dest)
            finally:
                if temp.exists():
                    temp.unlink()
        for dest, source in writes.items():
            if isinstance(source, Path):
                if digest(dest, "sha256") != digest(source, "sha256"):
                    raise ValueError("Installed shader failed hash check")
            elif dest.read_bytes() != source:
                raise ValueError("Installed shader settings differ")
    except Exception:
        for dest in reversed(completed):
            if existed[dest]:
                shutil.copy2(backup / dest.relative_to(profile), dest)
            else:
                dest.unlink(missing_ok=True)
        raise
    receipt = {"installedAt": datetime.now().astimezone().isoformat(), "profile": str(profile),
               "backup": str(backup), "files": [str(p.relative_to(profile)) for p in writes],
               "sha256": lock["sha256"], "preset": "Medium", "gameplayVerified": False}
    (OUT / "shader-installation-receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-to", type=Path, required=True)
    install(parser.parse_args().install_to)
