"""Author the approved optional-washing mineral graph, never edit a running world.

Reads the locked IC2R recipe resources only to record exact exclusions and native
heat metadata. Uranium recipes are deliberately outside this migration.
"""
from __future__ import annotations

import copy
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRAPH = ROOT / "src/main/resources/data/ic2newera/process_graph"
NATIVE_METALS = ("iron", "gold", "copper", "tin", "silver", "lead")
APPROVED = "APPROVED_INDUSTRIAL_PROCESS"


def item(name, count=1):
    return {"item": name, "count": count}


def route(name, material, source, output, count, fmt, machine, *, ticks=0, eu=-1, priority=5, heat=None):
    value = dict(id="ic2newera:" + name, material=material, classification=APPROVED,
                 machines=[machine], inputs=[item(source)], output=item(output, count),
                 duration_ticks=ticks, eu_per_tick=eu, priority=priority, format=fmt)
    if heat is not None:
        value["min_heat"] = heat
    return value


def main():
    documents = {p: json.loads(p.read_text(encoding="utf-8")) for p in sorted(GRAPH.glob("*.json"))}
    main_doc = documents[GRAPH / "main.json"]
    if not any(m["id"] == "lead" for m in main_doc["materials"]):
        # Use real registered Refactored lead resources; no duplicate ore or ingot.
        main_doc["materials"].append(dict(id="lead", advanced=False, stages={
            "RAW": "ic2:lead_ore", "IMPURE_DUST": "ic2newera:impure_lead_dust",
            "CLEAN_DUST": "ic2:lead_dust", "FINAL_METAL": "ic2:lead_ingot"},
            raw_variants=["ic2:deepslate_lead_ore", "ic2:raw_lead", "create:crushed_raw_lead"], grinding_inputs=[]))
        # Lead follows the existing tin pattern, including single-yield early smelting/recovery.
        for old in list(main_doc["routes"]):
            if old["material"] != "tin" or not old["id"].startswith("ic2newera:"):
                continue
            text = json.dumps(old).replace('"tin"', '"lead"').replace(":tin", ":lead").replace("_tin", "_lead")
            main_doc["routes"].append(json.loads(text))

    with zipfile.ZipFile(ROOT.parent / "dependencies/ic2-forge-2.10.33-ex120.jar") as native:
        exclusions = {r["id"]: r for r in main_doc.get("native_recipe_exclusions", [])}
        heats = {m: json.loads(native.read(f"data/ic2/recipes/centrifuge/purified_{m}_to_{m}_dust.json"))["minHeat"]
                 for m in NATIVE_METALS}
        # Exact known intermediate transitions, not a broad 'contains ore' rule.
        for metal in NATIVE_METALS:
            paths = [f"ore_washer/crushed_{metal}_to_purified_{metal}"]
            for stage in ("crushed", "purified"):
                paths.extend((f"centrifuge/{stage}_{metal}_to_{metal}_dust",
                              f"macerator/{stage}_{metal}_to_{metal}_dust",
                              f"smelting/{metal}_ingot_from_{stage}_{metal}"))
            if metal == "iron":
                paths.extend(("blast_furnace/crushed_iron_to_steel_ingot", "blast_furnace/purified_iron_to_steel_ingot"))
            for path in paths:
                old = json.loads(native.read(f"data/ic2/recipes/{path}.json"))
                result = old["result"]
                result = result if isinstance(result, list) else [result]
                outputs = [item(r, 1) if isinstance(r, str) else item(r["item"], r.get("count", 1)) for r in result]
                ingredient = old["ingredient"]
                exclusions["ic2:" + path] = dict(id="ic2:" + path, required_mods=["ic2"],
                    inputs=[dict(ingredient, count=ingredient.get("count", 1))], original_output=outputs[0],
                    original_outputs=outputs,
                    reason="统一普通矿物工艺：洗后中间粉离心1→3，未洗杂质粉离心1→1；取消该旧中间物直烧、直磨或副产旁路，铀核燃料链保持原生。")
        main_doc["native_recipe_exclusions"] = list(exclusions.values())

        for path, document in documents.items():
            by_id = {r["id"]: r for r in document["routes"]}
            for material in document["materials"]:
                name, stages = material["id"], material["stages"]
                direct_dust = name == "diamond"
                washed = f"ic2:purified_{name}" if name in NATIVE_METALS else f"ic2newera:washed_{name}_dust"
                material["stages"] = {k: stages[k] for k in ("RAW", "IMPURE_DUST")}
                material["stages"]["WASHED_DUST"] = washed
                material["stages"].update({k: stages[k] for k in ("CLEAN_DUST", "FINAL_METAL") if k in stages})
                material["impure_variants"] = [f"ic2:crushed_{name}"] if name in NATIVE_METALS else []
                if direct_dust:
                    # Native IC2R gem ratio is one diamond -> one ordinary diamond dust.
                    # Ore/deepslate ore keeps the graph's two-powder mineral yield.
                    material["direct_dust"] = True
                for r in document["routes"]:
                    source = r["inputs"][0].get("item") if len(r.get("inputs", [])) == 1 else None
                    direct_sources = ({stages["RAW"]} | set(material.get("raw_variants", []))
                                      | set(material.get("grinding_inputs", [])))
                    approved_grinding = (r["material"] == name
                            and r["classification"] == APPROVED and len(r["inputs"]) == 1
                            and r["format"] in ("macerator", "crushing")
                            and source in direct_sources)
                    if direct_dust and approved_grinding:
                        # Only canonical ores, their raw variants, and declared gem feedstocks
                        # are direct-powder sources. Never reinterpret dust stages as feedstocks.
                        r["output"] = item(stages["CLEAN_DUST"],
                                            1 if source in material.get("grinding_inputs", []) else 2)
                    elif (r["material"] == name and r["format"] in ("macerator", "crushing")
                            and r["classification"] == APPROVED and len(r["inputs"]) == 1
                            and r["inputs"][0].get("item") in material.get("grinding_inputs", [])
                            and r["output"]["item"] == stages["IMPURE_DUST"]):
                        r["output"]["count"] = r["inputs"][0]["count"] * 2
                    if r["material"] == name and r["format"] == "splashing" and r["classification"] == APPROVED:
                        if r["inputs"] == [item(stages["IMPURE_DUST"])]:
                            r["output"] = item(washed)
                if direct_dust:
                    # Keep the registered old dust IDs for existing inventories, but do not
                    # leave a new-game crushing/washing route that manufactures them.
                    document["routes"] = [r for r in document["routes"]
                                           if r["id"] != "ic2newera:diamond_wash"]
                additions = [route(f"{name}_washed_centrifuge", name, washed, stages["CLEAN_DUST"], 3,
                                    "centrifuge", "ic2:centrifuge", ticks=500, eu=48, heat=heats.get(name, 1500)),
                             route(f"{name}_unwashed_centrifuge", name, stages["IMPURE_DUST"], stages["CLEAN_DUST"], 1,
                                    "centrifuge", "ic2:centrifuge", ticks=500, eu=48, priority=20, heat=heats.get(name, 1500))]
                if name in NATIVE_METALS:
                    additions.append(route(f"{name}_legacy_crushed_to_impure", name, f"ic2:crushed_{name}",
                                           stages["IMPURE_DUST"], 1, "crafting", "minecraft:crafting_table", eu=0, priority=50))
                for r in additions:
                    if r["id"] in by_id:
                        if by_id[r["id"]] != r:
                            raise ValueError("Authored route differs: " + r["id"])
                    else:
                        document["routes"].append(r)
                        by_id[r["id"]] = r
            document["mineral_policy_revision"] = "2026-10-23-optional-washing-1-to-3"
            path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"materials": sum(len(d["materials"]) for d in documents.values()),
                      "routes": sum(len(d["routes"]) for d in documents.values()),
                      "exact_native_exclusions": len(main_doc["native_recipe_exclusions"])}, ensure_ascii=True))


if __name__ == "__main__":
    main()
