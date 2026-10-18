"""Export reviewed Git files with the original IC2-newera directory name.

Run after staging the intended public files. Local runtime/configuration and
unverified reference assets are never traversed. The archive excludes itself.
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "dist/IC2-newera.zip"
CHECKSUM = OUTPUT.with_suffix(".zip.sha256")
ALLOWED_DIRS = {"src", "docs", "art", "gradle", "scripts", "dist", "releases", "tools"}
ALLOWED_ROOT = {".gitignore", "AGENTS.md", "README.md", "LICENSE", "build.gradle",
                "gradle.properties", "gradlew", "gradlew.bat", "settings.gradle",
                "IC2_New_Era_Development_Goals_and_Progress_2026-10-08.md"}
EXCLUDED = {"dist/IC2-newera.zip", "dist/IC2-newera.zip.sha256"}
PUBLIC_DIST = {"dist/IC2-New-Era-Test-2026.10.18-Phone-Chat.zip",
               "dist/IC2-New-Era-Test-2026.10.18-Phone-Chat.zip.sha256", "dist/README-zh_CN.txt"}
PRIVATE_PATHS = {"tools/robot_voice/ic2newera-voice-runtime.json", "tools/robot_voice/static-verification.json"}
SECRET_PATTERN = re.compile(
    rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
    rb"\bgh[pousr]_[A-Za-z0-9]{30,}|\bgithub_pat_[A-Za-z0-9_]{40,}|"
    rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}"
)


def main() -> None:
    result = subprocess.run(["git", "ls-files", "--cached", "-z"], cwd=ROOT,
                            check=True, stdout=subprocess.PIPE)
    names = sorted({name.decode("utf-8") for name in result.stdout.split(b"\0") if name})
    entries = {}
    for name in names:
        if name in EXCLUDED:
            continue
        if name.startswith("dist/") and name not in PUBLIC_DIST:
            raise ValueError("Outdated or unreviewed distribution file: " + name)
        parts = PurePosixPath(name).parts
        if not parts or ".." in parts or (len(parts) == 1 and name not in ALLOWED_ROOT):
            raise ValueError("Unreviewed root file: " + name)
        if len(parts) > 1 and parts[0] not in ALLOWED_DIRS:
            raise ValueError("Unreviewed directory: " + name)
        if ("config" in parts or "references" in parts or "runtime" in parts or "__pycache__" in parts
                or name in PRIVATE_PATHS or name.endswith((".log", ".env", ".pth", ".ckpt", ".onnx", ".index"))
                or name.startswith(("art/robot-rework/", "art/robot-mcp-workspace/", "art/robot-image-reference/", "art/robot-dolls/"))):
            raise ValueError("Private or unverified file: " + name)
        if name.startswith("tools/") and not name.startswith("tools/robot_voice/"):
            raise ValueError("Unreviewed tools directory: " + name)
        source = (ROOT / name).resolve()
        if not source.is_relative_to(ROOT) or source.is_symlink():
            raise ValueError("Source escaped project: " + name)
        data = source.read_bytes()
        if source.suffix not in {".jar", ".zip", ".png"} and SECRET_PATTERN.search(data):
            raise ValueError("Credential-like value in public file: " + name)
        entries["IC2-newera/" + name] = data
    if not entries or "IC2-newera/README.md" not in entries:
        raise ValueError("Project files have not been staged")
    OUTPUT.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(OUTPUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in entries.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 18, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith("/gradlew") else 0o100644) << 16
            archive.writestr(info, data)
    with zipfile.ZipFile(OUTPUT) as archive:
        if archive.testzip() is not None or set(archive.namelist()) != set(entries):
            raise ValueError("Archive CRC or file list mismatch")
        for name, data in entries.items():
            if archive.read(name) != data:
                raise ValueError("Archive content differs: " + name)
    digest = hashlib.sha256(OUTPUT.read_bytes()).hexdigest().upper()
    CHECKSUM.write_text(digest + "  " + OUTPUT.name + "\n", encoding="utf-8")
    audit = {"archive": str(OUTPUT.relative_to(ROOT)), "topDirectory": "IC2-newera",
             "files": len(entries), "bytes": OUTPUT.stat().st_size, "sha256": digest,
             "paths": list(entries), "crcAndContentVerified": True}
    audit_path = ROOT / "build-isolated/github-source-package-audit.json"
    audit_path.parent.mkdir(exist_ok=True)
    audit_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in audit.items() if key != "paths"}))


if __name__ == "__main__":
    main()
