#!/usr/bin/env python3
"""Rebuild the r72 standalone New Era JAR from immutable r71 and audited first-party deltas."""
from __future__ import annotations
import hashlib, json, os, tempfile
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "dist/Create-Delight-New-Era-r71/ic2newera-0.2.0-migration1201-all.jar"
BASE_SHA256 = "14FFA3775A1121DE76350AF45069051FD864B5D3D719DCFAA4B05C1457BA30FB"
REOBF = ROOT / "build-isolated/migration-1201/newera/build-isolated/libs/ic2newera-0.2.0-migration1201.jar"
OVERLAY = ROOT / "build-isolated/player-packaging/20261114-r72-standalone/handbook-overlay/first-party-jar"
ACTIVE_RESOURCES = ROOT / "build-isolated/migration-1201/newera/src/main/resources"
OUTPUT = ROOT / "build-isolated/player-packaging/20261114-r72-standalone/native/ic2newera-0.2.0-migration1201-all.jar"
REPLACED_MEMBERS = (
    "META-INF/MANIFEST.MF",
    "data/ic2newera/ai/ui_operations_zh.md",
    "data/ic2newera/ai/migration_runtime_zh.md",
    "data/ic2newera/pe_custom_conversions/ingots.json",
    "com/ic2newera/field/EcologyAlbum.class",
    "com/ic2newera/field/EcologyAlbum$AlbumData.class",
    "com/ic2newera/field/EcologyAlbum$Candidate.class",
    "com/ic2newera/field/EcologyAlbum$PhoneCaptureTicket.class",
    "com/ic2newera/field/EcologyAlbum$PhonePhoto.class",
    "com/ic2newera/field/EcologyAlbum$PhotoEvidence.class",
    "com/ic2newera/field/EcologyAlbum$Species.class",
    "com/ic2newera/field/FieldRoutes.class",
    "com/ic2newera/field/FieldRoutes$Terrain.class",
    "com/ic2newera/field/FieldRoutes$Trip.class",
    "com/ic2newera/field/nav/FieldPath.class",
    "com/ic2newera/field/board/BoardBlockEntity.class",
    "com/ic2newera/wormhole/WormholeDeviceBlockEntity.class",
    "com/ic2newera/field/board/FieldBoardBlock.class",
    "com/ic2newera/field/board/FieldBoardContent.class",
    "com/ic2newera/field/camera/PhoneCameraView.class",
    "com/ic2newera/client/robot/FieldRouteScreen.class",
    "com/ic2newera/client/robot/FieldJournalScreen.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController$1.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController$CaptureMetadata.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController$CaptureRequest.class",
    "com/ic2newera/client/robot/camera/PhoneCameraEvents.class",
    "com/ic2newera/client/robot/IndustrialPhoneScreen.class",
    "com/ic2newera/client/robot/PhoneRobotChatScreen.class",
    "com/ic2newera/industrial/IndustrialMobileAccess.class",
    "com/ic2newera/client/robot/camera/PhoneGalleryScreen.class",
    "ic2newera.mixins.json",
    "assets/ic2newera/lang/zh_cn.json",
    "assets/ic2newera/lang/en_us.json",
)
NEW_CLASSES = (
    "com/ic2newera/compat/SoulSandBalancePack.class",
    "com/ic2newera/client/LocalMipPack.class",
    "com/ic2newera/mixin/GuideSubmitLabelMixin.class",
    "com/ic2newera/compat/OptionalJeiCompatMixinPlugin.class",
    "com/ic2newera/compat/JeiEntityFocusLinkCompat.class",
    "com/ic2newera/mixin/TconstructPotionBucketJeiGuardMixin.class",
    "com/ic2newera/mixin/TconstructPartRecipeJeiGuardMixin.class",
    "com/ic2newera/mixin/TconstructEntityMeltingFocusMixin.class",
    "com/ic2newera/mixin/TconstructSeveringFocusMixin.class",
    "com/ic2newera/mixin/SpawnArmorRepairMaterialMixin.class",
    "com/ic2newera/compat/OptionalSpawnCompatMixinPlugin.class",
    "com/ic2newera/client/robot/camera/PhoneEntityNames.class",
)
NEW_RESOURCES = ("ic2newera.jei-compat.mixins.json", "ic2newera.spawn-compat.mixins.json",
                 "data/ic2newera/pe_custom_conversions/r72_runtime_cycle_closures.json",
                 "resourcepacks/r72_andesite_balance/pack.mcmeta",
                 "resourcepacks/r72_andesite_balance/data/minecraft/recipes/andesite.json",
                 "resourcepacks/r72_avaritia_balance/pack.mcmeta",
                 "resourcepacks/r72_avaritia_balance/data/minecraft/recipes/soul_sand.json")
SURVEY = "data/ic2newera/photography/survey.json"
AI_UI_OPERATIONS = "data/ic2newera/ai/ui_operations_zh.md"
EMC_RUNTIME = "data/ic2newera/pe_custom_conversions/r72_runtime_cycle_closures.json"
VISUAL_ASSET_FILES = (
    "assets/ic2newera/blockstates/field_notice_board.json",
    "assets/ic2newera/models/block/field_notice_board.json",
    "assets/ic2newera/models/block/field_notice_board_on.json",
    "assets/ic2newera/models/item/field_notice_board.json",
    "assets/ic2newera/models/item/industrial_phone.json",
    "assets/ic2newera/textures/gui/industrial_phone/home_icons.png",
    "assets/ic2newera/textures/gui/industrial_phone/home_wallpaper.png",
    "assets/ic2newera/textures/gui/industrial_phone/home_icons.png.mcmeta",
    "assets/ic2newera/textures/gui/industrial_phone/home_wallpaper.png.mcmeta",
)
VISUAL_ASSET_GLOBS = (
    "assets/ic2newera/textures/block/notice_lcd*.png",
    "assets/ic2newera/textures/item/industrial_phone*.png",
)
DYNAMIC_CLASS_PREFIXES = (
    "com/ic2newera/client/robot/PhoneHomeView",
    "com/ic2newera/client/robot/IndustrialPhoneScreen",
    "com/ic2newera/client/robot/camera/PhoneCamera",
    "com/ic2newera/client/robot/camera/PhoneGalleryScreen",
)
REQUIRED_NEW_MEMBERS = (
    "com/ic2newera/field/board/FieldBoardBlock$1.class",
    "com/ic2newera/client/robot/PhoneHomeView.class",
    "assets/ic2newera/textures/gui/industrial_phone/home_icons.png",
    "assets/ic2newera/textures/gui/industrial_phone/home_wallpaper.png",
)


def active_visual_assets() -> dict[str, bytes]:
    """Read only the explicitly approved 1.20.1 notice-board and phone resources."""
    selected = {name: ACTIVE_RESOURCES / name for name in VISUAL_ASSET_FILES}
    for pattern in VISUAL_ASSET_GLOBS:
        matches = sorted(ACTIVE_RESOURCES.glob(pattern))
        if not matches:
            raise RuntimeError(f"no active visual resources match {pattern}")
        selected.update({path.relative_to(ACTIVE_RESOURCES).as_posix(): path for path in matches})
    missing = [name for name, path in selected.items() if not path.is_file()]
    if missing:
        raise RuntimeError(f"missing required active visual resources: {missing}")
    return {f"{name}": path.read_bytes() for name, path in selected.items()}


def validate_visual_assets(payloads: dict[str, bytes], jar_names: set[str]) -> None:
    """Check four-way day/night board models and every first-party model texture reference."""
    prefix = "assets/ic2newera/"
    blockstate_path = prefix + "blockstates/field_notice_board.json"
    blockstate = json.loads(payloads[blockstate_path].decode("utf-8"))
    variants = blockstate.get("variants", {})
    expected_facings = {"north", "east", "south", "west"}
    referenced_models: set[str] = set()
    facing_lit_models: dict[tuple[str, str], list[str]] = {}
    for variant_key, value in variants.items():
        properties = dict(part.split("=", 1) for part in variant_key.split(",") if "=" in part)
        choices = value if isinstance(value, list) else [value]
        model_ids = []
        for choice in choices:
            model_id = choice.get("model", "")
            model_ids.append(model_id)
            namespace, separator, model_path = model_id.partition(":")
            if not separator:
                namespace, model_path = "minecraft", namespace
            if namespace == "ic2newera":
                referenced_models.add(prefix + "models/" + model_path + ".json")
        if "facing" in properties and "lit" in properties:
            facing_lit_models[(properties["facing"], properties["lit"])] = model_ids
    expected_states = {(facing, lit) for facing in expected_facings for lit in ("false", "true")}
    if set(facing_lit_models) != expected_states:
        raise RuntimeError(f"field_notice_board blockstate must cover all 4 facings in both lit states; missing={sorted(expected_states - set(facing_lit_models))}, extra={sorted(set(facing_lit_models) - expected_states)}")
    for facing in expected_facings:
        if not facing_lit_models[(facing, "false")] or set(facing_lit_models[(facing, "false")]) != {"ic2newera:block/field_notice_board"}:
            raise RuntimeError(f"field_notice_board {facing}/lit=false must use the off model")
        if not facing_lit_models[(facing, "true")] or set(facing_lit_models[(facing, "true")]) != {"ic2newera:block/field_notice_board_on"}:
            raise RuntimeError(f"field_notice_board {facing}/lit=true must use the on model")
    expected_day_night = {prefix + "models/block/field_notice_board.json",
                          prefix + "models/block/field_notice_board_on.json"}
    if not expected_day_night.issubset(referenced_models):
        raise RuntimeError("field_notice_board blockstate must reference both day and powered/night models")
    for model_path in expected_day_night | {prefix + "models/item/field_notice_board.json",
                                           prefix + "models/item/industrial_phone.json"}:
        raw = payloads.get(model_path)
        if raw is None:
            raise RuntimeError(f"missing active model resource {model_path}")
        model = json.loads(raw.decode("utf-8"))
        parent = model.get("parent")
        if isinstance(parent, str) and parent.startswith("ic2newera:"):
            parent_path = prefix + "models/" + parent.split(":", 1)[1] + ".json"
            if parent_path not in jar_names:
                raise RuntimeError(f"model parent is missing from final JAR: {model_path} -> {parent_path}")
        textures = model.get("textures", {})
        for key, value in textures.items():
            seen: set[str] = set()
            while isinstance(value, str) and value.startswith("#"):
                alias = value[1:]
                if alias in seen or alias not in textures:
                    raise RuntimeError(f"invalid texture alias in {model_path}: #{alias}")
                seen.add(alias)
                value = textures[alias]
            if not isinstance(value, str):
                continue
            namespace, separator, texture_path = value.partition(":")
            if not separator:
                namespace, texture_path = "minecraft", namespace
            if namespace == "ic2newera":
                texture_member = prefix + "textures/" + texture_path + ".png"
                if texture_member not in jar_names:
                    raise RuntimeError(f"model texture is missing from final JAR: {model_path} -> {texture_member}")
    for gui_path in (prefix + "textures/gui/industrial_phone/home_icons.png",
                     prefix + "textures/gui/industrial_phone/home_wallpaper.png"):
        settings = json.loads(payloads[gui_path + ".mcmeta"])["texture"]
        if settings.get("blur") is not True or settings.get("clamp") is not True:
            raise RuntimeError(f"phone UI linear sampling and clamping required for {gui_path}")
        payload = payloads.get(gui_path)
        if payload is None or not payload.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError(f"missing or invalid phone home GUI PNG: {gui_path}")
        if gui_path not in jar_names:
            raise RuntimeError(f"phone home GUI image is missing from final JAR: {gui_path}")


def discover_related_class_changes(old_names: set[str], built_names: set[str], old: ZipFile,
                                   built: ZipFile) -> tuple[dict[str, bytes], dict[str, bytes]]:
    """Diff approved phone families and all nested classes of fixed replacement classes."""
    fixed_stems = {name[:-6] for name in REPLACED_MEMBERS
                   if name.endswith(".class") and "$" not in name}

    def matches(name: str) -> bool:
        if any(name.startswith(prefix) for prefix in DYNAMIC_CLASS_PREFIXES):
            return True
        return any(name == stem + ".class" or name.startswith(stem + "$") for stem in fixed_stems)

    candidates = {name for name in built_names if name.endswith(".class") and matches(name)}
    if not any(name.startswith(DYNAMIC_CLASS_PREFIXES[0]) for name in candidates):
        raise RuntimeError("reobfuscated JAR has no PhoneHomeView*.class; phone home app build is not ready")
    if not any(name.startswith(DYNAMIC_CLASS_PREFIXES[1]) for name in candidates):
        raise RuntimeError("reobfuscated JAR has no IndustrialPhoneScreen*.class")
    added = {name: built.read(name) for name in candidates if name not in old_names}
    replaced = {name: built.read(name) for name in candidates if name in old_names
                and old.read(name) != built.read(name) and name not in REPLACED_MEMBERS}
    return replaced, added

def sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()

def main() -> None:
    if not BASE.is_file() or sha256(BASE).lower() != BASE_SHA256.lower(): raise RuntimeError('r71 source jar missing or fingerprint changed')
    if not REOBF.is_file(): raise RuntimeError(f'missing reobfuscated jar: {REOBF}')
    survey_path=OVERLAY / SURVEY
    if not survey_path.is_file(): raise RuntimeError(f'missing survey assignment: {survey_path}')
    assignments=json.loads(survey_path.read_text(encoding='utf-8')).get('assignments', [])
    if len(assignments) != 24: raise RuntimeError(f'expected 24 survey assignments, got {len(assignments)}')
    resources={p.relative_to(OVERLAY).as_posix():p.read_bytes() for p in OVERLAY.rglob('*') if p.is_file()}
    if SURVEY not in resources: raise RuntimeError('survey resource is not included')
    adv=[name for name in resources if name.startswith('data/ic2newera/advancements/photography/survey_') and name.endswith('.json')]
    if len(adv) != 24: raise RuntimeError(f'expected 24 photography advancement JSON files, got {len(adv)}')
    visual_assets = active_visual_assets()
    ai_ui_path = ACTIVE_RESOURCES / AI_UI_OPERATIONS
    if not ai_ui_path.is_file(): raise RuntimeError(f'missing active AI UI operations text: {ai_ui_path}')
    ai_ui_payload = ai_ui_path.read_bytes()
    if set(resources) & set(visual_assets):
        raise RuntimeError('handbook overlay must not shadow active visual assets')
    with ZipFile(BASE) as old, ZipFile(REOBF) as built:
        old_names=set(old.namelist()); built_names=set(built.namelist())
        if any(name not in built_names for name in REQUIRED_NEW_MEMBERS):
            missing=[name for name in REQUIRED_NEW_MEMBERS if name not in built_names]
            raise RuntimeError(f'reobfuscated JAR is missing required r72 appearance members: {missing}')
        if any(name not in built_names or built.read(name) != payload for name,payload in resources.items()):
            raise RuntimeError('standard Gradle JAR photography resources are missing or differ from the audited overlay')
        if any(name not in built_names or built.read(name) != payload for name,payload in visual_assets.items()):
            raise RuntimeError('compiled JAR visual resources are missing or differ from active 1.20.1 src/main/resources')
        if AI_UI_OPERATIONS not in built_names or built.read(AI_UI_OPERATIONS) != ai_ui_payload:
            raise RuntimeError('compiled AI UI operations text differs from active 1.20.1 src/main/resources')
        for item in (EMC_RUNTIME, "data/ic2newera/pe_custom_conversions/ingots.json",
                     "resourcepacks/r72_avaritia_balance/data/minecraft/recipes/soul_sand.json",
                     "resourcepacks/r72_andesite_balance/data/minecraft/recipes/andesite.json",
                     "data/ic2newera/ai/migration_runtime_zh.md"):
            if built.read(item) != (ACTIVE_RESOURCES / item).read_bytes():
                raise ValueError("compiled pack resource differs from active source: " + item)
        if any(name not in old_names or name not in built_names for name in REPLACED_MEMBERS): raise RuntimeError('required replaced member missing')
        if any(name in old_names or name not in built_names for name in (*NEW_CLASSES,*NEW_RESOURCES)): raise RuntimeError('expected new first-party compatibility member absent or already present in r71')
        if any(n.upper().endswith(('.SF','.RSA','.DSA')) for n in old.namelist()): raise RuntimeError('refusing to modify signed JAR')
        phone_class_replacements, phone_class_additions = discover_related_class_changes(old_names, built_names, old, built)
        replacements={name:built.read(name) for name in REPLACED_MEMBERS}
        asset_replacements={name:payload for name,payload in visual_assets.items() if name in old_names}
        asset_additions={name:payload for name,payload in visual_assets.items() if name not in old_names}
        new_classes={name:built.read(name) for name in NEW_CLASSES}
        new_classes.update(phone_class_additions)
        new_resources={name:built.read(name) for name in NEW_RESOURCES}
        original={n:old.read(n) for n in old.namelist()}
        order=old.namelist()
    if any(name in resources for name in (*REPLACED_MEMBERS,*NEW_CLASSES,*NEW_RESOURCES)): raise RuntimeError('overlay must not replace compiled members')
    replacements.update(phone_class_replacements)
    replacements.update({name:visual_assets[name] for name in asset_replacements})
    added_members={**resources,**new_classes,**new_resources,**asset_additions}
    collisions=set(resources)&set(order)
    # These assignments are newly introduced and must never shadow an unrelated r71 resource.
    if collisions: raise RuntimeError(f'overlay unexpectedly shadows r71 entries: {sorted(collisions)}')
    replaced_members=set(REPLACED_MEMBERS)|set(phone_class_replacements)|set(asset_replacements)
    validate_visual_assets(visual_assets, set(order)|set(added_members))
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    fd,temp_name=tempfile.mkstemp(prefix=OUTPUT.name+'.',suffix='.tmp',dir=OUTPUT.parent)
    os.close(fd)
    temp=Path(temp_name)
    try:
        with ZipFile(BASE) as old, ZipFile(temp,'w',ZIP_DEFLATED,compresslevel=9) as out:
            out.comment=old.comment
            for info in old.infolist(): out.writestr(info,replacements.get(info.filename,original[info.filename]))
            for name,payload in sorted(added_members.items()): out.writestr(name,payload)
        changed=[]
        with ZipFile(BASE) as old, ZipFile(temp) as new:
            new_order=new.namelist()
            if new_order[:len(order)] != order: raise RuntimeError('r71 JAR member order changed')
            for n in order:
                if old.read(n) != new.read(n): changed.append(n)
            added=new_order[len(order):]
            if set(added) != set(added_members): raise RuntimeError('unexpected added JAR members')
            if any(name not in new_order for name in REQUIRED_NEW_MEMBERS):
                raise RuntimeError('r72 native artifact omitted a required board or phone appearance member')
            actual_changed={n for n in order if old.read(n)!=new.read(n)}
            unexpected=actual_changed-replaced_members
            allow_identical_rebuilds={"com/ic2newera/field/board/FieldBoardBlock.class",
                                      "com/ic2newera/field/board/FieldBoardContent.class"}
            missing_classes=set(REPLACED_MEMBERS)-actual_changed-allow_identical_rebuilds
            if unexpected: raise RuntimeError(f'unexpected modified r71 members: {sorted(unexpected)}')
            if missing_classes: raise RuntimeError(f'fixed rebuilt members did not change from r71: {sorted(missing_classes)}')
            if any(new.read(name) != payload for name,payload in visual_assets.items()):
                raise RuntimeError('final JAR visual assets differ from active 1.20.1 resource files')
        os.replace(temp,OUTPUT)
    finally:
        temp.unlink(missing_ok=True)
    result={'source_jar':str(BASE),'source_sha256':BASE_SHA256,'reobfuscated_build_jar':str(REOBF),'result_jar':str(OUTPUT),'result_sha256':sha256(OUTPUT),
            'replaced_members':sorted(replaced_members),'added_members':sorted(added_members),
            'replaced_phone_classes':sorted(phone_class_replacements),
            'added_phone_classes':sorted(phone_class_additions),
            'replaced_visual_assets':sorted(asset_replacements),'added_visual_assets':sorted(asset_additions),
            'visual_asset_source':'build-isolated/migration-1201/newera/src/main/resources',
            'visual_asset_sha256':{name:hashlib.sha256(payload).hexdigest().upper() for name,payload in sorted(visual_assets.items())},
            'ai_ui_operations_resource':AI_UI_OPERATIONS,'ai_ui_operations_sha256':hashlib.sha256(ai_ui_payload).hexdigest().upper(),
            'runtime_emc_conversion_resource':EMC_RUNTIME,'runtime_emc_conversion_sha256':hashlib.sha256((ACTIVE_RESOURCES/EMC_RUNTIME).read_bytes()).hexdigest().upper(),
            'unchanged_r71_members_byte_verified':len(order)-len(replaced_members)}
    OUTPUT.with_suffix('.manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__': main()
