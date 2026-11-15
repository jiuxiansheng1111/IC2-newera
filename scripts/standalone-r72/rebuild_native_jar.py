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
OUTPUT = ROOT / "build-isolated/player-packaging/20261114-r72-standalone/native/ic2newera-0.2.0-migration1201-all.jar"
REPLACED_MEMBERS = (
    "META-INF/MANIFEST.MF",
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
    "com/ic2newera/field/camera/PhoneCameraView.class",
    "com/ic2newera/client/robot/FieldRouteScreen.class",
    "com/ic2newera/client/robot/FieldJournalScreen.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController$1.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController$CaptureMetadata.class",
    "com/ic2newera/client/robot/camera/PhoneCameraController$CaptureRequest.class",
    "com/ic2newera/client/robot/camera/PhoneCameraEvents.class",
    "com/ic2newera/client/robot/IndustrialPhoneScreen.class",
    "com/ic2newera/client/robot/camera/PhoneGalleryScreen.class",
    "ic2newera.mixins.json",
    "assets/ic2newera/lang/zh_cn.json",
    "assets/ic2newera/lang/en_us.json",
)
NEW_CLASSES = (
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
NEW_RESOURCES = ("ic2newera.jei-compat.mixins.json", "ic2newera.spawn-compat.mixins.json")
SURVEY = "data/ic2newera/photography/survey.json"

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
    with ZipFile(BASE) as old, ZipFile(REOBF) as built:
        old_names=set(old.namelist()); built_names=set(built.namelist())
        if any(name not in built_names or built.read(name) != payload for name,payload in resources.items()):
            raise RuntimeError('standard Gradle JAR photography resources are missing or differ from the audited overlay')
        if any(name not in old_names or name not in built_names for name in REPLACED_MEMBERS): raise RuntimeError('required replaced member missing')
        if any(name in old_names or name not in built_names for name in (*NEW_CLASSES,*NEW_RESOURCES)): raise RuntimeError('expected new first-party compatibility member absent or already present in r71')
        if any(n.upper().endswith(('.SF','.RSA','.DSA')) for n in old.namelist()): raise RuntimeError('refusing to modify signed JAR')
        replacements={name:built.read(name) for name in REPLACED_MEMBERS}
        new_classes={name:built.read(name) for name in NEW_CLASSES}
        new_resources={name:built.read(name) for name in NEW_RESOURCES}
        original={n:old.read(n) for n in old.namelist()}
        order=old.namelist()
    if any(name in resources for name in (*REPLACED_MEMBERS,*NEW_CLASSES,*NEW_RESOURCES)): raise RuntimeError('overlay must not replace compiled members')
    added_members={**resources,**new_classes,**new_resources}
    collisions=set(resources)&set(order)
    # These assignments are newly introduced and must never shadow an unrelated r71 resource.
    if collisions: raise RuntimeError(f'overlay unexpectedly shadows r71 entries: {sorted(collisions)}')
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
            actual_changed={n for n in order if old.read(n)!=new.read(n)}
            if actual_changed != set(REPLACED_MEMBERS): raise RuntimeError(f'unexpected modified r71 members: {sorted(actual_changed)}')
        os.replace(temp,OUTPUT)
    finally:
        temp.unlink(missing_ok=True)
    result={'source_jar':str(BASE),'source_sha256':BASE_SHA256,'reobfuscated_build_jar':str(REOBF),'result_jar':str(OUTPUT),'result_sha256':sha256(OUTPUT),'replaced_members':list(REPLACED_MEMBERS),'added_members':sorted(added_members),'unchanged_r71_members_byte_verified':len(order)-len(REPLACED_MEMBERS)}
    OUTPUT.with_suffix('.manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__': main()
