"""Generate distinct one-block Create/IC2 converters and their approved recipes."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "build-isolated/migration-1201/newera/src/main/resources"


def write(relative: str, value: object) -> None:
    path = RES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def box(start: list[int], end: list[int], texture: str) -> dict:
    assert all(0 <= x < y <= 16 for x, y in zip(start, end))
    return {"from": start, "to": end, "shade": True,
            "faces": {face: {"texture": "#" + texture, "uv": [0, 0, 16, 16]}
                      for face in ("north", "south", "west", "east", "up", "down")}}


def block(name: str, textures: dict[str, str], elements: list[dict]) -> None:
    model = {"parent": "minecraft:block/block", "textures": {"particle": "#shell", **{
        key: "ic2newera:block/" + value for key, value in textures.items()}}, "elements": elements}
    write(f"assets/ic2newera/models/block/{name}.json", model)
    write(f"assets/ic2newera/models/item/{name}.json", {"parent": f"ic2newera:block/{name}"})
    variant = {"facing=south": {"model": f"ic2newera:block/{name}"},
               "facing=north": {"model": f"ic2newera:block/{name}", "y": 180},
               "facing=east": {"model": f"ic2newera:block/{name}", "y": 270},
               "facing=west": {"model": f"ic2newera:block/{name}", "y": 90},
               "facing=down": {"model": f"ic2newera:block/{name}", "x": 90},
               "facing=up": {"model": f"ic2newera:block/{name}", "x": 270}}
    write(f"assets/ic2newera/blockstates/{name}.json", {"variants": variant})
    write(f"data/ic2newera/loot_tables/blocks/{name}.json", {"type": "minecraft:block", "pools": [{
        "rolls": 1, "entries": [{"type": "minecraft:item", "name": "ic2newera:" + name}],
        "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


block("advanced_eu_motor", {
    "shell": "industrial_control_server_shell", "coil": "eu_motor_coil", "frame": "alloy_smelter_trim",
    "vent": "industrial_control_server_vent", "port": "eu_motor_ports"}, [
    box([0, 0, 1], [16, 3, 15], "frame"), box([2, 3, 2], [14, 12, 14], "shell"),
    box([0, 4, 4], [2, 11, 12], "coil"), box([14, 4, 4], [16, 11, 12], "coil"),
    box([2, 12, 3], [14, 14, 5], "vent"), box([2, 12, 7], [14, 14, 9], "vent"),
    box([2, 12, 11], [14, 14, 13], "vent"),
    box([4, 4, 14], [6, 12, 16], "frame"), box([10, 4, 14], [12, 12, 16], "frame"),
    box([6, 4, 14], [10, 6, 16], "frame"), box([6, 10, 14], [10, 12, 16], "frame"),
    box([5, 5, 0], [11, 11, 2], "port")])

block("kinetic_generator", {
    "shell": "eu_motor_shell", "coil": "eu_motor_coil", "frame": "alloy_smelter_trim",
    "vent": "eu_motor_vent", "port": "industrial_network_switch_ports"}, [
    box([1, 0, 2], [15, 2, 14], "frame"), box([2, 2, 3], [14, 13, 13], "shell"),
    box([3, 13, 4], [13, 15, 12], "coil"), box([0, 4, 5], [2, 11, 11], "vent"),
    box([14, 4, 5], [16, 11, 11], "vent"),
    box([4, 4, 13], [6, 12, 16], "frame"), box([10, 4, 13], [12, 12, 16], "frame"),
    box([6, 4, 13], [10, 6, 16], "frame"), box([6, 10, 13], [10, 12, 16], "frame"),
    box([5, 5, 0], [11, 11, 3], "port")])

block("advanced_kinetic_generator", {
    "shell": "industrial_control_server_shell", "coil": "eu_motor_coil", "frame": "industrial_network_switch_trim",
    "vent": "industrial_control_server_mesh", "port": "industrial_control_server_ports"}, [
    box([0, 0, 0], [16, 2, 16], "frame"), box([3, 2, 2], [13, 13, 14], "shell"),
    box([0, 3, 3], [3, 11, 7], "coil"), box([0, 3, 9], [3, 11, 13], "coil"),
    box([13, 3, 3], [16, 11, 7], "coil"), box([13, 3, 9], [16, 11, 13], "coil"),
    box([2, 13, 3], [14, 15, 13], "vent"), box([5, 15, 5], [11, 16, 11], "port"),
    box([4, 4, 14], [6, 12, 16], "frame"), box([10, 4, 14], [12, 12, 16], "frame"),
    box([6, 4, 14], [10, 6, 16], "frame"), box([6, 10, 14], [10, 12, 16], "frame"),
    box([5, 4, 0], [11, 12, 2], "port")])

block("universal_transformer", {
    "shell": "industrial_control_server_shell", "coil": "eu_motor_coil", "frame": "alloy_smelter_trim",
    "vent": "industrial_control_server_mesh", "port": "industrial_network_switch_ports",
    "output": "alloy_smelter_hearth"}, [
    box([0, 0, 0], [16, 2, 16], "frame"), box([1, 2, 1], [15, 12, 15], "shell"),
    box([0, 3, 3], [1, 11, 13], "coil"), box([15, 3, 3], [16, 11, 13], "coil"),
    box([2, 12, 2], [14, 14, 14], "vent"), box([4, 14, 4], [12, 16, 12], "frame"),
    box([4, 4, 15], [12, 10, 16], "output"), box([5, 4, 0], [11, 10, 1], "port")])

# Preserve the buffered EU and rate credits when the transformer itself drops.
copied_fields = ["OutputTier"] + [f"Input{tier}{suffix}" for tier in range(1, 5) for suffix in ("EU", "Credit")]
write("data/ic2newera/loot_tables/blocks/universal_transformer.json", {"type": "minecraft:block", "pools": [{
    "rolls": 1, "entries": [{"type": "minecraft:item", "name": "ic2newera:universal_transformer",
        "functions": [{"function": "minecraft:copy_nbt", "source": "block_entity", "ops": [
            {"source": key, "target": "BlockEntityTag." + key, "op": "replace"} for key in copied_fields]}]}],
    "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


def route(name: str, ingredients: list[tuple[str, int]]) -> dict:
    return {"id": "ic2newera:" + name, "material": "", "classification": "APPROVED_INDUSTRIAL_PROCESS",
            "machines": ["minecraft:crafting_table"],
            "inputs": [{"item": item, "count": count} for item, count in ingredients],
            "output": {"item": "ic2newera:" + name, "count": 1},
            "duration_ticks": 0, "eu_per_tick": 0, "priority": 10, "format": "crafting"}


write("data/ic2newera/process_graph/power_conversion.json", {"materials": [], "routes": [
    route("kinetic_generator", [("ic2newera:eu_motor", 1), ("create:shaft", 1),
                                ("ic2:circuit", 1), ("ic2:insulated_copper_cable", 2), ("minecraft:iron_ingot", 2)]),
    route("advanced_eu_motor", [("ic2newera:eu_motor", 1), ("ic2:advanced_circuit", 1),
                               ("ic2:lapotron_crystal", 2), ("create:precision_mechanism", 1),
                               ("ic2newera:cpu", 1), ("ic2newera:titanium_alloy_ingot", 2)]),
    route("advanced_kinetic_generator", [("ic2newera:kinetic_generator", 1), ("ic2:advanced_circuit", 1),
                                         ("ic2:lapotron_crystal", 2), ("create:precision_mechanism", 1),
                                         ("ic2newera:industrial_ssd", 1), ("ic2newera:titanium_alloy_ingot", 2)]),
    route("universal_transformer", [("ic2newera:advanced_power_bank", 1), ("ic2:lapotron_crystal", 2),
                                   ("ic2:advanced_circuit", 1), ("create:precision_mechanism", 1),
                                   ("ic2newera:integrated_circuit", 1), ("ic2newera:titanium_alloy_ingot", 2)])]})

tags = RES / "data/minecraft/tags/blocks/mineable/pickaxe.json"
value = json.loads(tags.read_text(encoding="utf-8"))
for name in ("advanced_eu_motor", "kinetic_generator", "advanced_kinetic_generator", "universal_transformer"):
    item = "ic2newera:" + name
    if item not in value["values"]:
        value["values"].append(item)
write("data/minecraft/tags/blocks/mineable/pickaxe.json", value)

for language, titles in {"zh_cn": ["初级EU电动机", "高级EU电动机", "初级机械发电机", "高级机械发电机", "万能变压器"],
                         "en_us": ["Basic EU Motor", "Advanced EU Motor", "Basic Mechanical Generator", "Advanced Mechanical Generator", "Universal Transformer"]}.items():
    path = RES / f"assets/ic2newera/lang/{language}.json"
    lang = json.loads(path.read_text(encoding="utf-8"))
    for name, title in zip(("eu_motor", "advanced_eu_motor", "kinetic_generator", "advanced_kinetic_generator", "universal_transformer"), titles):
        lang["block.ic2newera." + name] = title
    write(f"assets/ic2newera/lang/{language}.json", lang)
