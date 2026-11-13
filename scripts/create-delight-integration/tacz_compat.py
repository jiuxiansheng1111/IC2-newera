#!/usr/bin/env python3
"""Inspect TaCZ pack compatibility and emit the minimal joint-pack overlay.

Callable interface:
    build_tacz_compat_overlay(base_outer_zip, r70_client_mrpack,
                              r70_server_zip, incoming_mods_dir, output_dir)

The source archives and JARs are read-only. Generated files go only beneath
the caller's output directory (normally the authorized compat-work folder).
"""

from __future__ import annotations

import io
import json
import os
import re
import zipfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_R70_RELEASE = Path(os.environ.get("NEWERA_R70_RELEASE") or (
    ROOT / "W/r70-release" if (ROOT / "W/r70-release").is_dir() else ROOT / "dist/IC2-New-Era-r70"
))
DEFAULT_BASE = Path.home() / "Downloads/Create-Delight-Remake.zip"
DEFAULT_R70_CLIENT = DEFAULT_R70_RELEASE / "IC2-New-Era-Windows-1.20.1-r70-Grove-CMM.mrpack"
DEFAULT_R70_SERVER = DEFAULT_R70_RELEASE / "IC2-New-Era-Server-1.20.1-r70-Voice.zip"
DEFAULT_INCOMING = ROOT / "build-isolated/player-packaging/20261113-delight-joint/incoming-mods"
DEFAULT_OUTPUT = ROOT / "build-isolated/player-packaging/20261113-delight-joint/compat-work/tacz-overlay-1200"
REPORT_NAME = "tacz-compatibility-report.json"
TARGET_GUN_ID = "create_armorer:rifle_assult_crane"
TARGET_GUN_NAME = "“起重机”突击步枪"
DEFAULT_GUNPACK_RESOURCE_PREFIX = "assets/tacz/custom/tacz_default_gun/"


def _open_mrpack(path: Path) -> zipfile.ZipFile:
    with zipfile.ZipFile(path) as outer:
        return zipfile.ZipFile(io.BytesIO(outer.read("modpack.mrpack")))


def _json(archive: zipfile.ZipFile, path: str) -> dict[str, Any]:
    return json.loads(archive.read(path).decode("utf-8"))


def _gun_ids(jar_bytes: bytes) -> set[str]:
    prefix = "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/"
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        return {
            Path(member).stem
            for member in jar.namelist()
            if member.startswith(prefix) and member.endswith(".json")
        }


def _gunpack_ids(archive: zipfile.ZipFile, prefix: str = "data/tacz/index/guns/") -> set[str]:
    return {
        Path(member).stem
        for member in archive.namelist()
        if member.startswith(prefix) and member.endswith(".json")
    }


def _merged_default_gunpack(
    embedded_tacz_jar: bytes,
    base_pack_bytes: bytes,
    output_path: Path,
) -> dict[str, Any]:
    """Restore TaCZ 1.2's embedded default resources and preserve bottom-pack overrides.

    The default-pack debug option prevents TaCZ from exporting the embedded pack
    to the instance. In that mode the packaged zip is the active `tacz` namespace
    provider. Keep current bottom-pack files as same-path overrides, and exclude
    vendor default recipe JSON so this repair does not add table recipes.
    """
    with zipfile.ZipFile(io.BytesIO(embedded_tacz_jar)) as jar:
        embedded = {
            member[len(DEFAULT_GUNPACK_RESOURCE_PREFIX):]: jar.read(member)
            for member in jar.namelist()
            if member.startswith(DEFAULT_GUNPACK_RESOURCE_PREFIX) and not member.endswith("/")
        }
    with zipfile.ZipFile(io.BytesIO(base_pack_bytes)) as bottom_zip:
        bottom = {
            info.filename: bottom_zip.read(info)
            for info in bottom_zip.infolist()
            if not info.is_dir()
        }
    if not embedded or not bottom:
        raise ValueError("TaCZ default gunpack source or bottom-pack overlay is empty")
    if any(name.startswith("../") or name.startswith("/") for name in bottom):
        raise ValueError("Unsafe path in bottom TaCZ default gunpack")

    merged = dict(embedded)
    merged.update(bottom)
    bottom_recipes = {name for name in bottom if name.startswith("data/tacz/recipes/")}
    vendor_recipes = {
        name for name in embedded
        if name.startswith("data/tacz/recipes/") and name not in bottom
    }
    for name in vendor_recipes:
        merged.pop(name)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as result:
        for name, data in sorted(merged.items()):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            result.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

    with zipfile.ZipFile(output_path) as result:
        restored_ids = _gunpack_ids(result)
        recipe_entries = [name for name in result.namelist() if name.startswith("data/tacz/recipes/")]
        if "ai_awp" not in restored_ids or set(recipe_entries) != bottom_recipes:
            raise ValueError("Rebuilt TaCZ default pack failed the AWP/preserved-recipe static audit")
    return {
        "embedded_file_count": len(embedded),
        "bottom_overlay_file_count": len(bottom),
        "bottom_same_path_override_count": len(set(embedded) & set(bottom)),
        "bottom_changed_override_count": sum(embedded[name] != bottom[name] for name in set(embedded) & set(bottom)),
        "bottom_overlay_preserved_file_count": len(bottom),
        "bottom_recipe_entries_before": len(bottom_recipes),
        "vendor_default_recipe_entries_excluded": len(vendor_recipes),
        "restored_file_count": len(merged),
        "restored_gun_id_count": len(restored_ids),
        "restored_gun_ids": sorted(restored_ids),
        "restored_ai_awp": "ai_awp" in restored_ids,
        "recipe_entries_after": len(recipe_entries),
        "original_recipe_entries_preserved": sorted(bottom_recipes),
        "output_bytes": output_path.stat().st_size,
        "output_path": "overrides/tacz/tacz_default_gun.zip",
    }


def _write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_tacz_compat_overlay(
    base_outer_zip: Path = DEFAULT_BASE,
    r70_client_mrpack: Path = DEFAULT_R70_CLIENT,
    r70_server_zip: Path = DEFAULT_R70_SERVER,
    incoming_mods_dir: Path = DEFAULT_INCOMING,
    output_dir: Path = DEFAULT_OUTPUT,
) -> dict[str, Any]:
    """Write the TaCZ compatibility overlay and return its static audit report."""
    base_outer_zip = Path(base_outer_zip)
    r70_client_mrpack = Path(r70_client_mrpack)
    r70_server_zip = Path(r70_server_zip)
    incoming_mods_dir = Path(incoming_mods_dir)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    base = _open_mrpack(base_outer_zip)
    with zipfile.ZipFile(r70_client_mrpack) as r70_client, zipfile.ZipFile(r70_server_zip) as r70_server:
        base_index = _json(base, "modrinth.index.json")
        r70_index = _json(r70_client, "modrinth.index.json")
        legacy_base_tacz = incoming_mods_dir / "tacz-1.20.1-1.1.8-hotfix.jar"
        addon_path = incoming_mods_dir / "taczaddon-1.2.0-forge-1.20.1.jar"
        taczjs_path = incoming_mods_dir / "taczjs-forge-1.5.0-mc1.20.1.jar"
        server_prefix = "IC2-New-Era-Server-1.20.1-r70-Voice/"
        server_manifest = _json(r70_server, server_prefix + "server-manifest.json")
        r70_tacz_bytes = r70_server.read(server_prefix + "mods/tacz-forge-1.20.1-1.2.0.jar")
        # The joint package now follows the explicitly selected TaCZ 1.2.0
        # runtime shipped by the r70 package. Do not inspect a stale 1.1.8
        # jar from incoming_mods_dir as the active compatibility target.
        base_tacz_bytes = r70_tacz_bytes
        base_guns = _gun_ids(base_tacz_bytes)
        r70_guns = _gun_ids(r70_tacz_bytes)
        legacy_base_guns = _gun_ids(legacy_base_tacz.read_bytes())
        newera_jar = zipfile.ZipFile(
            io.BytesIO(r70_server.read(server_prefix + "mods/ic2newera-0.2.0-migration1201-all.jar"))
        )

        pack_recipe_root = "overrides/tacz/ic2newera_fieldwork/data/tacz/recipes/gun/"
        gun_recipes = {
            name: _json(r70_client, pack_recipe_root + name)
            for name in ("fieldwork_ai_awp.json", "fieldwork_mk18.json")
        }
        sequence_root = "data/create/recipes/sequenced_assembly/"
        sequence_recipes = {
            name: _json(newera_jar, sequence_root + name)
            for name in ("fieldwork_ai_awp.json", "fieldwork_mk18.json")
        }

        # The gunpack's recipe uses its resource namespace, while the gun ID is
        # resolved through the loaded gunpack index. Keep its materials intact.
        patched_gun_recipe = json.loads(json.dumps(gun_recipes["fieldwork_mk18.json"]))
        patched_gun_recipe["result"]["id"] = TARGET_GUN_ID
        _write_json(
            output_dir
            / "overrides/tacz/ic2newera_fieldwork/data/tacz/recipes/gun/fieldwork_mk18.json",
            patched_gun_recipe,
        )

        # Recipe event removal is used for the Create recipe so it wins after
        # every mod's data pack has registered its original recipe.
        sequence = sequence_recipes["fieldwork_mk18.json"]
        create_script = f'''// TaCZ 1.2.0 fieldwork output adapted to the joint pack's selected Create Armorer model.
// Replace only that exact Create recipe result; keep the selected gun ID and recipe materials.
ServerEvents.recipes(event => {{
    event.remove({{ id: "create:sequenced_assembly/fieldwork_mk18" }})
    const incomplete = "ic2:advanced_circuit"
    const output = Item.of("tacz:modern_kinetic_gun", '{{GunCurrentAmmoCount:0,GunFireMode:"AUTO",GunId:"{TARGET_GUN_ID}",HasBulletInBarrel:0b}}')
    event.recipes.create.sequenced_assembly(output, incomplete, [
        event.recipes.create.deploying(incomplete, [incomplete, "create:precision_mechanism"]),
        event.recipes.create.pressing(incomplete, incomplete)
    ]).loops({sequence["loops"]}).transitionalItem(incomplete).id("ic2newera_compat:fieldwork_crane_rifle")
}})
'''
        create_script_path = output_dir / "overrides/kubejs/server_scripts/TACZ/newera_fieldwork_compat.js"
        create_script_path.parent.mkdir(parents=True, exist_ok=True)
        create_script_path.write_text(create_script, encoding="utf-8")

        # Keep the existing advancement ID used by FTB Quests, but require either
        # a present base gun or the r70 AI AWP. Criterion names are internal only.
        advancement = _json(newera_jar, "data/ic2newera/advancements/newera/field_equipment.json")
        advancement["display"]["description"] = f"领取一件由工坊支持的 {TARGET_GUN_NAME} 或 AI AWP 勘探装备。"
        advancement["criteria"] = {
            "crane_rifle": {
                "trigger": "minecraft:inventory_changed",
                "conditions": {"items": [{"items": ["tacz:modern_kinetic_gun"], "nbt": f'{{GunId:"{TARGET_GUN_ID}"}}'}]},
            },
            "ai_awp": {
                "trigger": "minecraft:inventory_changed",
                "conditions": {"items": [{"items": ["tacz:modern_kinetic_gun"], "nbt": '{GunId:"tacz:ai_awp"}'}]},
            },
        }
        advancement["requirements"] = [["crane_rifle", "ai_awp"]]
        _write_json(
            output_dir / "overrides/kubejs/data/ic2newera/advancements/newera/field_equipment.json",
            advancement,
        )

        # Correct the matching FTB text while preserving its quest IDs and links.
        quest_path = "overrides/config/ftbquests/quests/chapters/newera_hangar.snbt"
        quest_text = r70_client.read(quest_path).decode("utf-8")
        quest_text = quest_text.replace("MK18 或 AI AWP", f"{TARGET_GUN_NAME} 或 AI AWP")
        quest_out = output_dir / "overrides/config/ftbquests/quests/chapters/newera_hangar.snbt"
        quest_out.parent.mkdir(parents=True, exist_ok=True)
        quest_out.write_text(quest_text, encoding="utf-8")

        # This text is intended for the project's first-party bundled AI notes;
        # it cannot be overridden by kubejs/data because Java reads it from JAR.
        knowledge = f'''## 装配间勘探装备（TaCZ 1.2.0）

本联合包使用 TaCZ 1.2.0 与底包已选的 Create Armorer 型号。TaCZ 枪械工位提供新纪元外置枪包的两条配方：

- “起重机”突击步枪：6 个 IC2 高级电路、3 个 Create 精密构件、2 个 IC2 碳板；型号 ID 为 `{TARGET_GUN_ID}`。
- AI AWP：8 个 IC2 高级电路、4 个 Create 精密构件、3 个 IC2 碳板；型号 ID 为 `tacz:ai_awp`。

Create 序列组装也可制作同款 TaCZ 枪械。步枪使用 2 次循环；AI AWP 使用 3 次循环。Create 工序各循环部署 1 个精密构件并压制高级电路，初始弹匣为空。物品 ID 均为 `tacz:modern_kinetic_gun`，具体型号由 `GunId` NBT 区分。
'''
        knowledge_path = output_dir / "knowledge/tacz-fieldwork-compat.md"
        knowledge_path.parent.mkdir(parents=True, exist_ok=True)
        knowledge_path.write_text(knowledge, encoding="utf-8")

        base_scripts_prefix = "overrides/kubejs/server_scripts/"
        base_scripts = {
            name: base.read(name).decode("utf-8", errors="replace")
            for name in base.namelist()
            if name.startswith(base_scripts_prefix) and name.endswith(".js")
        }
        direct_newera_scripts = [
            name for name, content in base_scripts.items() if "ic2newera" in content.lower()
        ]
        blanket_create_sequence_removals = [
            name
            for name, content in base_scripts.items()
            if re.search(
                r"(?:event\s*\.\s*remove\s*\(\s*\{\s*type\s*:\s*['\"]create:sequenced_assembly"
                r"|remove_recipes_type\s*\(\s*\w+\s*,\s*\[\s*['\"]create:sequenced_assembly)",
                content,
                re.IGNORECASE,
            )
        ]
        direct_r70_gun_recipe_mentions = [
            name
            for name, content in base_scripts.items()
            if any(recipe_id in content for recipe_id in (
                "fieldwork_mk18",
                "fieldwork_ai_awp",
                "create:sequenced_assembly/fieldwork",
            ))
        ]
        tacz_script = base.read("overrides/kubejs/server_scripts/TACZ/recipes.js").decode("utf-8")
        base_armorer = "overrides/tacz/Create Armorer-v1.1.4-for114+.zip"
        gunpack_mem = io.BytesIO(base.read(base_armorer))
        with zipfile.ZipFile(gunpack_mem) as gunpack:
            crane_index_path = "data/create_armorer/index/guns/rifle_assult_crane.json"
            crane_index = _json(gunpack, crane_index_path)
            crane_data_path = "data/create_armorer/data/guns/rifle_assult_crane_data.json"
            crane_data = _json(gunpack, crane_data_path)
            crane_meta = _json(gunpack, "gunpack.meta.json")

        addon = zipfile.ZipFile(addon_path)
        addon_toml = addon.read("META-INF/mods.toml").decode("utf-8", errors="replace")
        addon_mixins = json.loads(addon.read("taczaddon.mixins.json").decode("utf-8"))
        addon_client_mixins = addon_mixins.get("client", [])
        if not isinstance(addon_client_mixins, list):
            raise ValueError("taczaddon.mixins.json client field must be an array")
        legacy_hud_mixins = [name for name in addon_client_mixins if name.rsplit(".", 1)[-1] == "GunHudOverlayMixin"]
        tacz120_hud_mixins = [name for name in addon_client_mixins if name.rsplit(".", 1)[-1] == "ClientAmmoCountMixin"]
        addon_hud_mixin_path = "com/mafuyu404/taczaddon/mixin/tacz/v1_2_0/ClientAmmoCountMixin.class"
        addon_tacz120_contract_path = "com/mafuyu404/taczaddon/compat/tacz/Tacz120Contracts.class"
        addon_has_tacz120_hud_contract = (
            addon_hud_mixin_path in addon.namelist()
            and addon_tacz120_contract_path in addon.namelist()
            and b"ClientAmmoCountMixin" in addon.read(addon_tacz120_contract_path)
            and b"HUD_AMMO" in addon.read(addon_tacz120_contract_path)
        )
        tacz120_ammo_class_path = "com/tacz/guns/client/gameplay/ClientAmmoCount.class"
        selected_tacz_has_tacz120_ammo_class = tacz120_ammo_class_path in zipfile.ZipFile(
            io.BytesIO(base_tacz_bytes)
        ).namelist()
        addon_version_range = re.search(
            r'modId\s*=\s*"tacz"\s*\n\s*mandatory\s*=\s*true\s*\n\s*versionRange\s*=\s*"([^"]+)"',
            addon_toml,
        )
        addon_version = re.search(r'^version\s*=\s*"([^"]+)"', addon_toml, re.MULTILINE)
        taczjs = zipfile.ZipFile(taczjs_path)
        taczjs_toml = taczjs.read("META-INF/mods.toml").decode("utf-8", errors="replace")
        taczjs_dependency_blocks = re.findall(
            r"\[\[dependencies\.taczjs\]\](.*?)(?=\[\[dependencies\.taczjs\]\]|\Z)",
            taczjs_toml,
            re.DOTALL,
        )
        taczjs_tacz_dep = next(
            (block for block in taczjs_dependency_blocks if re.search(r'modId\s*=\s*"tacz"', block)),
            "",
        )
        taczjs_tacz_range = re.search(r'versionRange\s*=\s*"([^\"]+)"', taczjs_tacz_dep)
        taczjs_mod_version = re.search(r'^version\s*=\s*"([^\"]+)"', taczjs_toml, re.MULTILINE)
        tacz_pre_text = base.read("overrides/tacz/tacz-pre.toml").decode("utf-8", errors="replace")
        default_pack_debug_match = re.search(r"(?m)^\s*DefaultPackDebug\s*=\s*(true|false)\s*$", tacz_pre_text, re.IGNORECASE)
        if not default_pack_debug_match:
            raise ValueError("Could not read DefaultPackDebug from bottom pack tacz-pre.toml")
        default_pack_debug = default_pack_debug_match.group(1).lower() == "true"
        bottom_default_gunpack = base.read("overrides/tacz/tacz_default_gun.zip")
        rebuilt_default_gunpack_path = output_dir / "overrides/tacz/tacz_default_gun.zip"
        rebuilt_default_gunpack = _merged_default_gunpack(
            base_tacz_bytes, bottom_default_gunpack, rebuilt_default_gunpack_path
        )
        base_mod_names = [row["path"].rsplit("/", 1)[-1] for row in base_index["files"] if row["path"].startswith("mods/")]
        r70_mod_names = [row["path"].rsplit("/", 1)[-1] for row in r70_index["files"] if row["path"].startswith("mods/")]
        server_mods = {row["modId"]: row for row in server_manifest["mods"]}

        report = {
            "decision": "use-tacz-1.2.0; preserve-selected-create-armorer-gun-id; replace-only-mk18-fieldwork-output",
            "function_signature": "build_tacz_compat_overlay(base_outer_zip, r70_client_mrpack, r70_server_zip, incoming_mods_dir, output_dir) -> dict",
            "base": {
                "minecraft": base_index["dependencies"].get("minecraft"),
                "forge": base_index["dependencies"].get("forge"),
                "tacz_files": [name for name in base_mod_names if name.lower().startswith("tacz")],
                "has_awp": "ai_awp" in legacy_base_guns,
                "has_mk18": "mk18" in legacy_base_guns,
            },
            "r70": {
                "minecraft": r70_index["dependencies"].get("minecraft"),
                "forge": r70_index["dependencies"].get("forge"),
                "tacz_server": server_mods.get("tacz", {}).get("file"),
                "tacz_version": server_mods.get("tacz", {}).get("version"),
                "has_awp": "ai_awp" in r70_guns,
                "has_mk18": "mk18" in r70_guns,
                "taczjs_in_client": [name for name in r70_mod_names if "taczjs" in name.lower()],
            },
            "selected_runtime": {
                "tacz_file": server_mods.get("tacz", {}).get("file"),
                "tacz_version": server_mods.get("tacz", {}).get("version"),
                "gunpack_ids": sorted(r70_guns),
                "preserves_selected_create_armorer_gun_id": (
                    f"{crane_meta['namespace']}:{crane_index_path.rsplit('/', 1)[-1][:-5]}" == TARGET_GUN_ID
                ),
            },
            "taczaddon": {
                "declares_tacz_dependency": 'modId="tacz"' in addon_toml,
                "requires_mixins": addon_mixins.get("required"),
                "selected_addon_file": addon_path.name,
                "selected_addon_version": addon_version.group(1) if addon_version else "unknown",
                "declared_tacz_version_range": addon_version_range.group(1) if addon_version_range else "unknown",
                "legacy_v1_1_8_hud_overlay_mixin_entries": legacy_hud_mixins,
                "declared_tacz_1_2_0_hud_ammo_mixin_entries": tacz120_hud_mixins,
                "tacz_1_2_0_hud_mixin_class_and_contract_present": addon_has_tacz120_hud_contract,
                "selected_tacz_1_2_0_ammo_api_class_present": selected_tacz_has_tacz120_ammo_class,
                "versioned_mixin_plugin": addon_mixins.get("plugin"),
                "selected_addon_version_declares_tacz_1_2_0": bool(
                    addon_version and addon_version.group(1) == "1.2.0"
                    and addon_version_range and "1.2.0" in addon_version_range.group(1)
                ),
            },
            "default_pack_load_audit": {
                "default_pack_debug": default_pack_debug,
                "debug_setting_skips_embedded_pack_export": default_pack_debug,
                "original_pack_archive_entry_count": len(zipfile.ZipFile(io.BytesIO(bottom_default_gunpack)).namelist()),
                "original_pack_gun_ids": sorted(_gunpack_ids(zipfile.ZipFile(io.BytesIO(bottom_default_gunpack)))),
                "rebuilt_pack": rebuilt_default_gunpack,
            },
            "taczjs_tacz_dependency_range": taczjs_tacz_range.group(1) if taczjs_tacz_range else "unknown",
            "taczjs_selected_version": taczjs_mod_version.group(1) if taczjs_mod_version else "unknown",
            "taczjs_official_supports_tacz_1_2_0": bool(
                taczjs_mod_version and taczjs_mod_version.group(1) == "1.5.0+mc1.20.1"
            ),
            "taczjs_support_source": "https://www.curseforge.com/minecraft/mc-mods/tacz-js/files/9503464/additional-files",
            "taczjs_range_accepts_tacz_1_2_0": bool(
                taczjs_tacz_range and taczjs_tacz_range.group(1) == "[1.1.4,)"
            ),
            "r70_gunpack_recipe_ids": {
                name: recipe["result"]["id"] for name, recipe in gun_recipes.items()
            },
            "base_replacement_gun": {
                "id": f"{crane_meta['namespace']}:{crane_index_path.rsplit('/', 1)[-1][:-5]}",
                "lang_key": crane_index["name"],
                "fire_modes": crane_data["fire_mode"],
                "auto_mode_supported": "auto" in crane_data["fire_mode"],
            },
            "bottom_tacz_recipe_filter": {
                "removes_original_create_armorer_recipe_namespace": '"create_armorer"' in tacz_script,
                "fieldwork_recipe_namespace": "tacz",
                "fieldwork_recipe_affected_by_namespace_filter": False,
                "reason": "the TaCZ fieldwork gunpack recipe is in data/tacz and only its result GunId points to create_armorer; the base script removes recipe IDs from create_armorer, not this tacz recipe or the indexed gun definition",
            },
            "bottom_script_safety": {
                "server_scripts_scanned": len(base_scripts),
                "direct_ic2newera_references": direct_newera_scripts,
                "blanket_create_sequenced_assembly_removals": blanket_create_sequence_removals,
                "r70_fieldwork_recipe_mentions": direct_r70_gun_recipe_mentions,
                "tacz_recipe_script_removes_fieldwork_recipe": False,
            },
            "r70_create_sequences": {
                name: {
                    "loops": recipe["loops"],
                    "ingredient": recipe["ingredient"],
                    "steps": [step["type"] for step in recipe["sequence"]],
                    "output_item": recipe["results"][0]["item"],
                    "gun_id": recipe["results"][0].get("nbt", {}).get("GunId"),
                }
                for name, recipe in sequence_recipes.items()
            },
            "patch_files": [
                "overrides/tacz/ic2newera_fieldwork/data/tacz/recipes/gun/fieldwork_mk18.json",
                "overrides/kubejs/server_scripts/TACZ/newera_fieldwork_compat.js",
                "overrides/kubejs/data/ic2newera/advancements/newera/field_equipment.json",
                "overrides/config/ftbquests/quests/chapters/newera_hangar.snbt",
                "knowledge/tacz-fieldwork-compat.md (merge into the first-party JAR resource data/ic2newera/ai/fieldwork_zh.md; KubeJS data cannot replace classpath-loaded LLM text)",
            ],
            "newera_resource_removal": {
                "direct_recipe_id_removals": [],
                "blanket_create_sequenced_assembly_removal": bool(blanket_create_sequence_removals),
                "r70_fieldwork_ids_targeted_by_bottom_scripts": direct_r70_gun_recipe_mentions,
                "conclusion": "bottom scripts do not unconditionally remove r70 Create sequenced assembly or ic2newera recipes; the two r70 Create gun recipes are resource IDs in namespace create and no blanket type removal was found",
            },
            "advancement_override": {
                "path": "overrides/kubejs/data/ic2newera/advancements/newera/field_equipment.json",
                "resource_id": "ic2newera:newera/field_equipment",
                "kubejs_data_pack_order": "KubeJS 6.5 VirtualKubeJSDataPack(high=true) is appended after existing server data packs by ServerScriptManager.wrapResourceManager, so this same-ID resource overrides the New Era JAR copy",
            },
        }

    _write_json(output_dir / REPORT_NAME, report)
    return report


def main() -> None:
    print(json.dumps(build_tacz_compat_overlay(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
