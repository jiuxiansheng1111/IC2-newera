"""Build the first-party Java 17 entry point without downloading any dependency."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build-isolated/server-packaging/20261028-r48-full-server/launcher"
SOURCE = ROOT / "tools/server-launcher/src/com/ic2newera/serverlaunch/ServerLauncher.java"


def main():
    java_home = os.environ.get("JAVA_HOME")
    javac = Path(java_home) / "bin/javac.exe" if java_home and os.name == "nt" else (
        Path(java_home) / "bin/javac" if java_home else Path(shutil.which("javac") or "javac"))
    jar_tool = javac.with_name("jar.exe" if os.name == "nt" else "jar")
    OUT.mkdir(parents=True, exist_ok=True)
    output = OUT / "server.jar"
    if output.exists():
        raise FileExistsError("Review the existing launcher before rebuilding: " + str(output))
    classes = OUT / "classes"
    classes.mkdir(exist_ok=True)
    subprocess.run([str(javac), "--release", "17", "-encoding", "UTF-8", "-d", str(classes), str(SOURCE)], check=True)
    shutil.copy2(ROOT / "LICENSE", classes / "LICENSE-first-party-MIT.txt")
    subprocess.run([str(jar_tool), "--create", "--file", str(output), "--main-class",
                    "com.ic2newera.serverlaunch.ServerLauncher", "-C", str(classes), "."], check=True)
    with zipfile.ZipFile(output) as packed:
        cls = packed.read("com/ic2newera/serverlaunch/ServerLauncher.class")
        assert cls[:4] == bytes.fromhex("cafebabe") and int.from_bytes(cls[6:8], "big") == 61
        assert "Main-Class: com.ic2newera.serverlaunch.ServerLauncher" in packed.read("META-INF/MANIFEST.MF").decode()
    receipt = {"artifact": str(output.relative_to(ROOT).as_posix()), "sizeBytes": output.stat().st_size,
               "sha256": hashlib.sha256(output.read_bytes()).hexdigest().upper(),
               "source": str(SOURCE.relative_to(ROOT).as_posix()),
               "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest().upper(),
               "javaRelease": 17, "testsRun": False}
    (OUT / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
