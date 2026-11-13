"""Offline registry/resource checks for the TaCZ and Immersive Aircraft fieldwork data."""

import json
from pathlib import Path
import zipfile

NEWERA = Path(__file__).resolve().parents[1]
RESOURCES = NEWERA / "src" / "main" / "resources"
DEPENDENCIES = NEWERA.parent / "dependencies"


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    tacz = zipfile.ZipFile(DEPENDENCIES / "tacz-forge-1.20.1-1.2.0.jar")
    aircraft = zipfile.ZipFile(DEPENDENCIES / "immersive_aircraft-1.5.0+1.20.1-forge.jar")
    ic2 = zipfile.ZipFile(DEPENDENCIES / "ic2-forge-2.10.33-ex120.jar")
    create = zipfile.ZipFile(DEPENDENCIES / "create-1201-6.0.8.jar")
    checks = 0

    def ok(condition: bool, message: str) -> None:
        nonlocal checks
        require(condition, message)
        checks += 1

    aircraft_lang_path = RESOURCES / "assets/immersive_aircraft/lang/zh_cn.json"
    create_gun_path = RESOURCES / "data/create/recipes/sequenced_assembly/fieldwork_mk18.json"
    create_awp_path = RESOURCES / "data/create/recipes/sequenced_assembly/fieldwork_ai_awp.json"
    create_aircraft_path = RESOURCES / "data/create/recipes/sequenced_assembly/field_exploration_gyrodyne.json"
    external_meta_path = NEWERA / "tacz/ic2newera_fieldwork/gunpack.meta.json"
    external_info_path = NEWERA / "tacz/ic2newera_fieldwork/assets/tacz/gunpack_info.json"
    external_gun_recipes = [
        NEWERA / "tacz/ic2newera_fieldwork/data/tacz/recipes/gun/fieldwork_mk18.json",
        NEWERA / "tacz/ic2newera_fieldwork/data/tacz/recipes/gun/fieldwork_ai_awp.json",
    ]
    external_lang_path = NEWERA / "tacz/ic2newera_fieldwork/assets/tacz/lang/zh_cn.json"
    new_json = [aircraft_lang_path, create_gun_path,
                create_awp_path, create_aircraft_path, external_meta_path, external_info_path,
                *external_gun_recipes, external_lang_path]
    parsed = {path: read_json(path) for path in new_json}
    ok(True, "all added JSON parses")

    ok(parsed[external_meta_path].get("namespace") == "tacz", "external gunpack declares the TaCZ namespace")
    ok(parsed[external_info_path].get("name") == "pack.ic2newera_fieldwork.name",
       "external gunpack has TaCZ display metadata")
    for path in external_gun_recipes:
        recipe = parsed[path]
        gun_id = recipe["result"]["id"].split(":", 1)[1]
        output_path = f"data/tacz/recipes/gun/{path.name}"
        ok(recipe["type"] == "tacz:gun_smith_table_crafting", f"{path.name}: TaCZ native table recipe format")
        ok(output_path not in tacz.namelist(), f"{path.name}: no conflict with installed TaCZ recipe path")
        ok(recipe["result"].get("type") == "gun", f"{path.name}: native TaCZ gun result handler")
        ok(f"assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/{gun_id}.json" in tacz.namelist(),
           f"{gun_id}: registered in installed TaCZ 1.2.0 gun index")
        ok(f"assets/tacz/custom/tacz_default_gun/data/tacz/recipes/gun/{gun_id}.json" in tacz.namelist(),
           f"{gun_id}: installed TaCZ recipe format verified; custom uses a unique recipe ID")

    create_gun = parsed[create_gun_path]["results"][0]
    ok(parsed[create_gun_path]["conditions"] == [{"type": "forge:mod_loaded", "modid": "tacz"}],
       "Create MK18 recipe is gated on TaCZ being installed")
    nbt = create_gun["nbt"]
    ok(create_gun["item"] == "tacz:modern_kinetic_gun" and nbt.get("GunId") == "tacz:mk18",
       "Create assembly emits TaCZ item with the MK18 GunId")
    ok(nbt.get("GunFireMode") == "AUTO" and nbt.get("GunCurrentAmmoCount") == 0
       and nbt.get("HasBulletInBarrel") is False, "TaCZ output NBT uses valid accessor names and starts empty")
    ok("assets/tacz/models/item/modern_kinetic_gun.json" in tacz.namelist(),
       "modern_kinetic_gun item model exists in installed TaCZ")
    ok("assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/mk18.json" in tacz.namelist(),
       "Create GunId points to an installed TaCZ gun index")
    accessor = tacz.read("com/tacz/guns/api/item/nbt/GunItemDataAccessor.class")
    ok(b"GunId" in accessor and b"GunFireMode" in accessor and b"GunCurrentAmmoCount" in accessor
       and b"HasBulletInBarrel" in accessor, "TaCZ bytecode exposes the exact NBT accessor names")
    create_awp = parsed[create_awp_path]["results"][0]
    ok(parsed[create_awp_path]["conditions"] == [{"type": "forge:mod_loaded", "modid": "tacz"}],
       "Create AWP recipe is gated on TaCZ being installed")
    ok(create_awp["item"] == "tacz:modern_kinetic_gun" and create_awp["nbt"].get("GunId") == "tacz:ai_awp",
       "second Create output carries the registered TaCZ AI AWP GunId")
    ok(create_awp["nbt"].get("GunFireMode") == "SEMI" and create_awp["nbt"].get("GunCurrentAmmoCount") == 0
       and create_awp["nbt"].get("HasBulletInBarrel") is False, "AI AWP starts empty with valid TaCZ NBT keys")
    ok("assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/ai_awp.json" in tacz.namelist(),
       "Create AI AWP GunId points to installed TaCZ gun index")

    create_aircraft = parsed[create_aircraft_path]["results"][0]["item"]
    ok(parsed[create_aircraft_path]["conditions"] == [{"type": "forge:mod_loaded", "modid": "immersive_aircraft"}],
       "Create gyrodyne recipe is gated on Immersive Aircraft being installed")
    ok(create_aircraft == "immersive_aircraft:gyrodyne", "Create assembly output ID is the gyrodyne")
    ok("data/immersive_aircraft/aircraft/gyrodyne.json" in aircraft.namelist(),
       "gyrodyne aircraft definition exists in installed Immersive Aircraft")
    ok("data/immersive_aircraft/recipes/gyrodyne.json" in aircraft.namelist(),
       "existing gyrodyne recipe has a separate ID; Create route is additive")
    ok("assets/immersive_aircraft/models/item/gyrodyne.json" in aircraft.namelist(),
       "gyrodyne inventory item model exists in installed Aircraft")
    for path in (create_gun_path, create_awp_path, create_aircraft_path):
        resource_path = "data/create/recipes/sequenced_assembly/" + path.name
        ok(resource_path not in create.namelist(), f"{path.name}: no same-path collision in installed Create data")

    for jar, needle, label in [
        (ic2, "advanced_circuit", "IC2 advanced circuit"),
        (ic2, "carbon_plate", "IC2 carbon plate"),
        (create, "precision_mechanism", "Create precision mechanism"),
        (aircraft, "hull", "Immersive Aircraft hull"),
    ]:
        ok(any(needle in name for name in jar.namelist()), f"{label} exists in installed dependency data")

    for jar, english_path, chinese_path, overlay_path in [
        (tacz, "assets/tacz/custom/tacz_default_gun/assets/tacz/lang/en_us.json",
         "assets/tacz/custom/tacz_default_gun/assets/tacz/lang/zh_cn.json", external_lang_path),
        (aircraft, "assets/immersive_aircraft/lang/en_us.json", "assets/immersive_aircraft/lang/zh_cn.json",
         aircraft_lang_path),
    ]:
        english = json.loads(jar.read(english_path).decode("utf-8"))
        chinese = json.loads(jar.read(chinese_path).decode("utf-8"))
        overlay = parsed[overlay_path]
        missing = set(english) - set(chinese)
        extra = set(overlay) - missing
        allowed_extra = {"pack.ic2newera_fieldwork.name", "pack.ic2newera_fieldwork.desc"} if jar is tacz else set()
        ok(missing <= set(overlay) and extra == allowed_extra,
           f"{overlay_path.name}: fills {sorted(missing)} and contains only project pack labels beyond gaps")

    print(f"FieldworkDataChecks: {checks} groups passed against installed dependency jars.")


if __name__ == "__main__":
    main()
