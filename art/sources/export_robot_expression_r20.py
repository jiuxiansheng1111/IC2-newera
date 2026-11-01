"""Pack reviewed R19 heads + R20 smiles into the isolated 1.20.1 runtime.

Reuses the original UV/ZYX converter. Only named mouth elements are separated;
no vertex-coordinate heuristics, texture painting or source-project mutation.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path
import uuid

import export_robot_runtime_models as pipeline

ROOT = pipeline.ROOT
REVIEW = ROOT / "art/robot-mcp-workspace/characters/head-r20-smile/review"
TARGET = ROOT / "build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera"
DERIVED = ROOT / "build-isolated/migration-1201/robot-expression-r20"


def read_exact(path, expected):
    raw = Path(path).read_bytes()
    if pipeline.digest(raw) != expected:
        raise ValueError(f"Reviewed source changed: {path}")
    return json.loads(raw)


def remove_node(nodes, identifier):
    for i, node in enumerate(nodes):
        if node == identifier:
            nodes.pop(i)
            return nodes
        if isinstance(node, dict):
            found = remove_node(node.get("children", []), identifier)
            if found is not None:
                return found
    return None


def combine(key, neutral, smile, ids):
    old_id = ids["original_neutral_mouth_uuid"]
    smile_ids = [ids["r20_center_stroke_uuid"], ids["r20_added_smile_left_uuid"], ids["r20_added_smile_right_uuid"]]
    old = {e["uuid"]: e for e in neutral["elements"]}
    new = {e["uuid"]: e for e in smile["elements"]}
    if {k: v for k, v in old.items() if k != old_id} != {k: v for k, v in new.items() if k not in smile_ids}:
        raise ValueError(f"Non-mouth geometry differs for {key}")
    for field in ("textures", "groups", "resolution", "animations"):
        if neutral.get(field) != smile.get(field):
            raise ValueError(f"Non-mouth {field} differs for {key}")
    _, neutral_chain = pipeline.hierarchy(neutral)
    _, smile_chain = pipeline.hierarchy(smile)
    ancestors = neutral_chain(old_id)
    head = next(g for g in ancestors if g["name"] == "head")
    if any(smile_chain(i) != ancestors for i in smile_ids):
        raise ValueError(f"Mouth hierarchy differs for {key}")
    combined = copy.deepcopy(neutral)
    # Both expression groups stay under the original mouth ancestors, with no
    # added rotation. Their vertices therefore retain exact authoring positions.
    parent_nodes = remove_node(combined["outliner"], old_id)
    if parent_nodes is None:
        raise ValueError("Neutral mouth is missing from outliner")
    for name, elements in (("mouth_neutral", [copy.deepcopy(old[old_id])]),
                           ("mouth_smile", [copy.deepcopy(new[i]) for i in smile_ids])):
        group_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"ic2newera/r20/{key}/{name}"))
        if name == "mouth_smile":
            for e in elements:
                # R19 and R20 center share a UUID; the combined file cannot.
                e["uuid"] = str(uuid.uuid5(uuid.NAMESPACE_URL, f"ic2newera/r20/{key}/smile/{e['uuid']}"))
                combined["elements"].append(e)
        group = dict(uuid=group_id, name=name, origin=copy.deepcopy(head["origin"]),
                     rotation=[0, 0, 0], export=True, visibility=True)
        combined["groups"].append(group)
        parent_nodes.append(dict(uuid=group_id, children=[e["uuid"] for e in elements]))
    return combined


def main():
    receipts = json.loads((REVIEW / "render-receipts.json").read_text(encoding="utf-8"))["characters"]
    ids = json.loads((REVIEW / "mouth-element-uuids.json").read_text(encoding="utf-8"))["characters"]
    if set(receipts) != set(pipeline.CHARACTERS) or set(ids) != set(pipeline.CHARACTERS):
        raise ValueError("All eight reviewed expressions are required")
    pipeline.MODEL_DIR = TARGET / "robot_models/r20"
    pipeline.TEXTURE_DIR = TARGET / "textures/entity/robot/r20"
    pipeline.PARTS = (*pipeline.PARTS, "mouth_neutral", "mouth_smile")
    pipeline.MAGIC = b"NER20M01"
    DERIVED.mkdir(parents=True, exist_ok=True)
    facts = []
    for key in pipeline.CHARACTERS:
        receipt = receipts[key]
        neutral = read_exact(receipt["r19_model"], receipt["r19_sha256"])
        smile = read_exact(receipt["r20_model"], receipt["r20_sha256"])
        model = combine(key, neutral, smile, ids[key])
        source = DERIVED / f"{key}-expression-runtime.bbmodel"
        source.write_text(json.dumps(model, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        fact = pipeline.export_character(key, source, pipeline.digest(source.read_bytes()))
        pivots = fact["pivots_model_blocks"]
        if pivots["mouth_neutral"] != pivots["head"] or pivots["mouth_smile"] != pivots["head"]:
            raise ValueError(f"Expression pivot differs from head: {key}")
        fact.update(neutral_source_sha256=receipt["r19_sha256"], smile_source_sha256=receipt["r20_sha256"],
                    non_mouth_geometry_identical=True, textures_identical=True)
        facts.append(fact)
    manifest = dict(format="NER20M01", revision="r20", stride=8, parts=pipeline.PARTS,
                    byte_order="big-endian", neutral_source="R19", smile_source="R20",
                    runtime_expression="Exactly one mouth part; both inherit the head transform",
                    source_projects_unchanged=True, characters=facts)
    (pipeline.MODEL_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"characters": len(facts), "parts": pipeline.PARTS,
                      "vertices": sum(f["vertices"] for f in facts), "source_projects_unchanged": True}))


if __name__ == "__main__":
    main()
