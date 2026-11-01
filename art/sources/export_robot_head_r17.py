"""Export the visually reviewed R17 cuboids with the existing exact UV/transform converter.

No model or texture is synthesized here. Source fingerprints must be supplied by
the review manifest, so this script cannot silently pick up an unreviewed edit.
"""
import argparse
import json
from pathlib import Path
import export_robot_runtime_models as pipeline


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--review", type=Path, required=True)
    args = parser.parse_args()
    review = json.loads(args.review.read_text(encoding="utf-8"))
    sources = review["sources"]
    if set(sources) != set(pipeline.CHARACTERS):
        raise ValueError("A review must identify all eight characters")
    pipeline.MODEL_DIR = pipeline.ASSETS / "robot_models/r17"
    pipeline.TEXTURE_DIR = pipeline.ASSETS / "textures/entity/robot/r17"
    facts = []
    for key in pipeline.CHARACTERS:
        source = (pipeline.ROOT / sources[key]["path"]).resolve()
        if not source.is_relative_to(pipeline.ROOT / "art/robot-mcp-workspace/characters/head-r17"):
            raise ValueError("R17 source must stay within its dedicated work folder")
        facts.append(pipeline.export_character(key, source, sources[key]["sha256"]))
    manifest = dict(format="NER16M01", revision="r17", stride=8, parts=pipeline.PARTS,
                    byte_order="big-endian", original_sources_unchanged=True, characters=facts)
    (pipeline.MODEL_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"characters": len(facts), "cubes": sum(f["cubes"] for f in facts),
                      "review": str(args.review), "source_hashes_unchanged": True}))


if __name__ == "__main__":
    main()
