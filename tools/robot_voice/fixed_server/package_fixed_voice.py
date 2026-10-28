"""Build a Java-only voice playback JAR from completed, audited local generation."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def build(root: Path, jdk: Path):
    root = root.resolve(strict=True)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    entries = manifest["entries"]
    if len(entries) != manifest["expected_count"] or manifest["completed_count"] != len(entries):
        raise ValueError("Voice generation is incomplete; no deliverable will be built")
    if {e["language"] for e in entries} != {"ja", "zh"}:
        raise ValueError("This release requires Chinese and Japanese greetings")
    characters = {e["character"] for e in entries}
    expected = {(character, lang, tier, event) for character in characters
                for lang in ("ja", "zh") for tier in ("stranger", "familiar", "close", "devoted")
                for event in ("leave", "return")}
    if len(characters) != 8 or {(e["character"], e["language"], e["tier"], e["event"]) for e in entries} != expected:
        raise ValueError("Voice coverage is incomplete")
    classes = root / "classes"
    classes.mkdir(exist_ok=True)
    source = Path(__file__).with_name("FixedVoiceAgent.java").resolve()
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "17", "--add-modules", "jdk.httpserver",
                    "-encoding", "UTF-8", "-d", str(classes), str(source)], check=True)
    index = []
    audio_bytes = 0
    for entry in entries:
        clip = (root / entry["file"]).resolve(strict=True)
        if not clip.is_relative_to(root / "audio"):
            raise ValueError("Audio resource escapes the generation folder")
        raw = clip.read_bytes()
        if len(raw) != entry["bytes"] or hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            raise ValueError("Generated audio changed after validation")
        index.append("\t".join([entry["character"], base64.b64encode(entry["text"].encode()).decode(),
                                entry["file"], entry["sha256"], str(len(raw))]))
        audio_bytes += len(raw)
    index_data = ("\n".join(index) + "\n").encode()
    artifact = root / "ic2newera-fixed-voice.jar"
    with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nPremain-Class: com.ic2newera.fixedvoice.FixedVoiceAgent\r\nMain-Class: com.ic2newera.fixedvoice.FixedVoiceAgent\r\n\r\n")
        for compiled in sorted(classes.rglob("*.class")):
            jar.write(compiled, compiled.relative_to(classes).as_posix())
        jar.writestr("fixed-voice/index.tsv", index_data)
        jar.writestr("fixed-voice/manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2).encode())
        for entry in entries:
            jar.write(root / entry["file"], entry["file"])
    report = {"artifact": artifact.name, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
              "bytes": artifact.stat().st_size, "clip_count": len(entries), "character_count": len(characters),
              "audio_bytes": audio_bytes, "index_sha256": hashlib.sha256(index_data).hexdigest(),
              "java_minimum": 17, "requires_python": False, "requires_gpu": False,
              "realtime_synthesis": False, "singing": False, "installed": False}
    (root / "build-receipt.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    original_args = root / "original-forge-unix-args.txt"
    if original_args.exists():
        original = original_args.read_text(encoding="utf-8")
        if not original.startswith("-p libraries/") or "cpw.mods.bootstraplauncher.BootstrapLauncher" not in original:
            raise ValueError("Original Forge startup arguments are not recognized")
        if "-javaagent:" in original:
            raise ValueError("Existing Java agents require a separately reviewed startup file")
        (root / "unix_args.txt").write_text("--add-modules=jdk.httpserver\n-javaagent:ic2newera-fixed-voice.jar\n" + original,
                                             encoding="utf-8", newline="\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    args = parser.parse_args()
    build(args.root, args.jdk)
