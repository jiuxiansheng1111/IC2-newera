"""Pre-render the current home greetings; no server credentials or live services."""
from __future__ import annotations
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import re
import time

from configuration import VoiceConfiguration
from engine import VoiceEngine, validate_audio


async def generate(source: Path, output: Path, languages: list[str], limit: int | None):
    output.mkdir(parents=True, exist_ok=True)
    code = source.read_text(encoding="utf-8")
    manifest_path = output / "manifest.json"
    previous = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else None
    if previous and previous["source_sha256"] != hashlib.sha256(code.encode()).hexdigest():
        raise ValueError("Greeting source changed; use a new output folder")
    previous_entries = {entry["file"]: entry for entry in previous["entries"]} if previous else {}
    constants = dict(re.findall(r'public static final String (\w+) = "([^"]+)";', code))
    phrases = []
    for language in languages:
        block = re.search(r'case ' + language.upper() + r' -> switch \(relationship\) \{(.*?)\};', code, re.S).group(1)
        for tier, left, right in re.findall(r'case (\w+) -> departure \? (.*?)\s*:\s*(.*?);', block, re.S):
            for event, expression in (("leave", left), ("return", right)):
                expression = expression.strip()
                text = json.loads(expression) if expression.startswith('"') else constants[expression]
                phrases.append({"id": f"{language}-{tier.lower()}-{event}", "language": language,
                                "tier": tier.lower(), "event": event, "text": text})
    source_config = Path(__file__).with_name("ic2newera-voice-runtime.json")
    data = json.loads(source_config.read_text(encoding="utf-8-sig"))
    data.pop("singing", None)
    data["data_directory"] = "generation-runtime"
    data["gpu_lock_file"] = str(source_config.parent.resolve() / "runtime/gpu.lock")
    config_path = output / "generation.private.json"
    config_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    config = VoiceConfiguration(config_path)
    engine = VoiceEngine(config)
    entries = []
    started = time.monotonic()
    total = len(phrases) * len(config.characters)
    try:
        for phrase in phrases:
            for character in config.characters:
                if limit is not None and len(entries) >= limit:
                    return
                target = output / "audio" / character / (phrase["id"] + ".wav")
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.is_file():
                    raw = target.read_bytes()
                    validate_audio(raw)
                    old = previous_entries.get(target.relative_to(output).as_posix())
                    if old and (old["text"] != phrase["text"] or old["sha256"] != hashlib.sha256(raw).hexdigest()):
                        raise ValueError("A completed clip changed after generation")
                else:
                    print(f"GENERATING {len(entries)+1}/{total} {character} {phrase['id']}", flush=True)
                    raw = await engine.synthesize(character, phrase["text"])
                    target.write_bytes(raw)
                    audit = output / "audit" / character
                    audit.mkdir(parents=True, exist_ok=True)
                    (audit / (phrase["id"] + ".json")).write_bytes((config.storage / "last-request.json").read_bytes())
                entries.append({**phrase, "character": character, "file": target.relative_to(output).as_posix(),
                                "bytes": len(raw), "seconds": validate_audio(raw),
                                "sha256": hashlib.sha256(raw).hexdigest()})
                manifest = {"protocol": 1, "mode": "prerecorded_fixed_phrases", "source": source.name,
                            "source_sha256": hashlib.sha256(code.encode()).hexdigest(),
                            "expected_count": total, "completed_count": len(entries), "entries": entries}
                manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"SAVED {len(entries)}/{total} {character} {phrase['id']} {len(raw)} bytes; elapsed={time.monotonic()-started:.1f}s", flush=True)
    finally:
        await engine.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--languages", nargs="+", choices=["ja", "zh", "en"], default=["ja", "zh"])
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    asyncio.run(generate(args.source, args.output, args.languages, args.limit))
