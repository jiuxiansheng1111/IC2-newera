#!/usr/bin/env python3
"""Build the R72 Forge caller-wrapper overlay for Valkyrien Skies 2.4.11."""
from __future__ import annotations

import hashlib
import json
import os
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "build-isolated/migration-1201/dependencies/valkyrienskies-120-2.4.11.jar"
MAPPING = ROOT / "build-isolated/migration-1201/newera/build-isolated/createMcpToSrg/output.tsrg"
OUT_DIR = ROOT / "build-isolated/player-packaging/20261114-r72-standalone/vs-compat"
OUTPUT = OUT_DIR / "valkyrienskies-120-2.4.11-r72-forge-sculk-callers.jar"
SOURCE_BUNDLE = OUT_DIR / "valkyrienskies-r72-sculk-callers-source.zip"
REOBF_CLASSES = OUT_DIR / "classes-srg.jar"
EXPECTED_SOURCE_SHA256 = "f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53"
COMMON_CONFIG = "valkyrienskies-common.mixins.json"
FORGE_CONFIG = "valkyrienskies-forge.mixins.json"
DISABLED_INTERFACE_MIXIN = "feature.sculk.MixinVibrationSystemTicker"
FORGE_MIXINS = [
    "feature.sculk.MixinCalibratedSculkSensorBlock",
    "feature.sculk.MixinSculkSensorBlock",
    "feature.sculk.MixinSculkShriekerBlock",
    "feature.sculk.MixinVibrationSystemEntities",
]
CLASS_ROOT = "org/valkyrienskies/mod/forge/"
LOCAL_NOTICE = "META-INF/newera-vs-compat.txt"
LOCAL_SOURCE_BUNDLE = "META-INF/newera-vs-compat-source.zip"
INJECTED_CLASSES = [
    CLASS_ROOT + "common/ShipVibrationUser.class",
    *(CLASS_ROOT + "mixin/feature/sculk/" + name.rsplit(".", 1)[1] + ".class" for name in FORGE_MIXINS),
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def build_source_bundle() -> str:
    source_root = ROOT / "scripts/standalone-r72/vs-compat"
    selected = [
        source_root / "README.md",
        source_root / "UPSTREAM-LICENSE-LGPL-3.0.txt",
        source_root / "compile_vs_sculk_callers.ps1",
        source_root / "patch_vs_sculk_callers.py",
        source_root / "print-cp.init.gradle",
        *sorted((source_root / "src/main/java").rglob("*.java")),
    ]
    with zipfile.ZipFile(SOURCE_BUNDLE, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for path in selected:
            if not path.is_file():
                raise SystemExit(f"missing source bundle file: {path}")
            rel = path.relative_to(ROOT).as_posix()
            info = zipfile.ZipInfo(rel, date_time=(2026, 11, 16, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            bundle.writestr(info, path.read_bytes())
    return sha256(SOURCE_BUNDLE)


def class_bytes(archive: zipfile.ZipFile, binary_name: str) -> bytes:
    try:
        data = archive.read(binary_name)
    except KeyError as exc:
        raise SystemExit(f"missing reobfuscated compatibility class: {binary_name}") from exc
    if not data.startswith(b"\xca\xfe\xba\xbe"):
        raise SystemExit(f"not a JVM class file: {binary_name}")
    return data


def main() -> None:
    if not SOURCE.is_file():
        raise SystemExit(f"missing locked VS source jar: {SOURCE}")
    actual_source_hash = sha256(SOURCE)
    if actual_source_hash != EXPECTED_SOURCE_SHA256:
        raise SystemExit(f"unexpected source VS SHA-256: {actual_source_hash}")
    if not MAPPING.is_file():
        raise SystemExit(f"missing ForgeGradle createMcpToSrg mapping: {MAPPING}")
    mapping_hash = sha256(MAPPING)
    source_bundle_hash = build_source_bundle()

    if not REOBF_CLASSES.is_file():
        raise SystemExit(f"missing FART/SRG-remapped class archive: {REOBF_CLASSES}")
    with zipfile.ZipFile(REOBF_CLASSES, "r") as compiled:
        injected = {name: class_bytes(compiled, name) for name in INJECTED_CLASSES}
    for name in INJECTED_CLASSES[1:]:
        # Class-file constant pools retain the annotation descriptor and target SRG names.
        raw = injected[name]
        if b"com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation" not in raw:
            raise SystemExit(f"missing @WrapOperation metadata in {name}")
        if b"VibrationSystem$Ticker;m_280259_" not in raw:
            raise SystemExit(f"wrong/missing ticker-call target in {name}")
    helper = injected[INJECTED_CLASSES[0]]
    for srg_method in (b"m_280010_", b"m_280351_", b"m_280080_", b"m_280271_", b"m_280028_",
                       b"m_280076_", b"m_280215_", b"m_280576_", b"m_280612_", b"m_280022_",
                       b"m_142502_", b"m_274446_"):
        if srg_method not in helper:
            raise SystemExit(f"FART output is missing mapped VibrationSystem.User/PositionSource method {srg_method.decode()}")
    entity_mixin = injected[INJECTED_CLASSES[-1]]
    if b"m_20183_" not in entity_mixin:
        raise SystemExit("FART output is missing mapped Entity.blockPosition call (m_20183_)")
    for named in (b"getPositionSource", b"getListenerRadius", b"canReceiveVibration", b"onReceiveVibration", b"blockPosition"):
        if named in helper + entity_mixin:
            raise SystemExit(f"named-Mojmap method reference survived FART output: {named.decode()}")

    with zipfile.ZipFile(SOURCE, "r") as original:
        names = original.namelist()
        for name in (COMMON_CONFIG, FORGE_CONFIG):
            if name not in names:
                raise SystemExit(f"missing expected VS mixin config: {name}")
        if any(name in names for name in injected):
            raise SystemExit("compatibility classes already exist in source Jar")

        common = json.loads(original.read(COMMON_CONFIG))
        if common["mixins"].count(DISABLED_INTERFACE_MIXIN) != 1:
            raise SystemExit("expected exactly one unsupported Ticker interface mixin entry")
        common["mixins"].remove(DISABLED_INTERFACE_MIXIN)

        forge = json.loads(original.read(FORGE_CONFIG))
        mixins = forge["mixins"]
        for name in FORGE_MIXINS:
            if name in mixins:
                raise SystemExit(f"caller wrapper already configured: {name}")
            mixins.append(name)
        # Stable order makes the overlay deterministic and reviewable.
        mixins.sort()

        replacements = {
            COMMON_CONFIG: (json.dumps(common, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
            FORGE_CONFIG: (json.dumps(forge, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
            LOCAL_NOTICE: (
                "R72 local Forge compatibility derivative for Valkyrien Skies 2.4.11.\n"
                "Upstream: https://github.com/ValkyrienSkies/Valkyrien-Skies-2/tree/release/2.4.11\n"
                "The exact release/2.4.11 source LICENSE is GNU LGPL v3.0; a copy is included in META-INF/newera-vs-compat-source.zip. The repository homepage identifies the project as LGPL-3.0-only.\n"
                "The locked Forge JAR's META-INF/mods.toml separately declares Apache-2.0. Its original Apache LICENSE/NOTICE entries are preserved byte-for-byte. This metadata discrepancy is disclosed; this derivative does not resolve or alter upstream licensing declarations.\n"
                "The nested VS API artifact (api-1.1.0+cf208d8b56.jar) has no license/notice entry or embedded source attribution in the locked JAR. Its published Maven POM and Gradle module metadata also declare no license. No separate license conclusion is made for that split artifact.\n"
                "Compatibility source basis: Valkyrien Skies PR #1982 by BonsUnleashed (commit 9bd961b); local Forge caller-wrapper port, not an official VS release.\n"
                "Local changes: remove the unsupported common Ticker interface mixin entry; add four Forge caller-wrapper mixins and ShipVibrationUser. Complete local source and rebuild instructions are embedded in META-INF/newera-vs-compat-source.zip.\n"
                "Base JAR SHA-256: " + actual_source_hash + "\n"
                "This local artifact is intended to replace the matching base JAR; do not install both copies.\n"
            ).encode("utf-8"),
            LOCAL_SOURCE_BUNDLE: SOURCE_BUNDLE.read_bytes(),
            **injected,
        }
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(OUTPUT, "w") as output:
            output.comment = original.comment
            for info in original.infolist():
                data = replacements.pop(info.filename, None)
                if data is None:
                    data = original.read(info.filename)
                output.writestr(info, data)
            for name, data in replacements.items():
                info = zipfile.ZipInfo(name, date_time=(2026, 11, 15, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                output.writestr(info, data)

    output_sha = sha256(OUTPUT)
    with zipfile.ZipFile(SOURCE, "r") as original, zipfile.ZipFile(OUTPUT, "r") as patched:
        source_names = set(original.namelist())
        patched_names = set(patched.namelist())
        expected_names = source_names | set(INJECTED_CLASSES) | {LOCAL_NOTICE, LOCAL_SOURCE_BUNDLE}
        if patched_names != expected_names:
            raise SystemExit("unexpected Jar entry set after patch")
        changed = {COMMON_CONFIG, FORGE_CONFIG, *INJECTED_CLASSES, LOCAL_NOTICE, LOCAL_SOURCE_BUNDLE}
        for name in source_names - changed:
            if original.read(name) != patched.read(name):
                raise SystemExit(f"unexpected modified VS entry: {name}")
        common = json.loads(patched.read(COMMON_CONFIG))
        forge = json.loads(patched.read(FORGE_CONFIG))
        if DISABLED_INTERFACE_MIXIN in common["mixins"]:
            raise SystemExit("unsupported interface injector remains in common config")
        if any(name not in forge["mixins"] for name in FORGE_MIXINS):
            raise SystemExit("missing Forge caller wrapper config entry")

    sha_file = OUTPUT.with_suffix(OUTPUT.suffix + ".sha256")
    sha_file.write_text(f"{output_sha}  {OUTPUT.name}\n", encoding="ascii")
    with zipfile.ZipFile(OUTPUT, "r") as finished:
        local_notice_hash = hashlib.sha256(finished.read(LOCAL_NOTICE)).hexdigest()
    manifest = {
        "format": 1,
        "artifact": OUTPUT.name,
        "sha256": output_sha,
        "source": SOURCE.name,
        "source_sha256": actual_source_hash,
        "method": "forge_sculk_caller_wrappers",
        "class_remapper": "ForgeAutoRenamingTool 1.0.2 with newera createMcpToSrg/reobfJar mappings.tsrg",
        "mapping_file": str(MAPPING.relative_to(ROOT)).replace("\\", "/"),
        "mapping_sha256": mapping_hash,
        "compatibility": "Minecraft 1.20.1 Forge 47.4.28 / Valkyrien Skies 2.4.11",
        "upstream_reference": "https://github.com/ValkyrienSkies/Valkyrien-Skies-2/pull/1982",
        "upstream_release_ref": "release/2.4.11",
        "upstream_project_license": "LGPL-3.0-only (official repository declaration)",
        "release_source_license": "LGPL-3.0; source LICENSE fetched from exact release/2.4.11 ref and included in source bundle",
        "binary_mods_toml_license": "Apache-2.0 (as declared by locked 2.4.11 JAR; discrepancy disclosed, original entries preserved)",
        "nested_api_license_check": "api-1.1.0+cf208d8b56.jar and its published POM/module metadata contain no separate license declaration",
        "preserved_license_notice_entries": ["META-INF/LICENSE.txt", "META-INF/NOTICE.md", "META-INF/NOTICE.txt"],
        "local_notice_entry": LOCAL_NOTICE,
        "local_source_bundle_entry": LOCAL_SOURCE_BUNDLE,
        "local_notice_sha256": local_notice_hash,
        "source_bundle": SOURCE_BUNDLE.name,
        "source_bundle_sha256": source_bundle_hash,
        "disabled_common_mixin": DISABLED_INTERFACE_MIXIN,
        "forge_mixins": FORGE_MIXINS,
        "injected_classes": INJECTED_CLASSES,
        "preserved_original_entries": len(source_names - changed),
        "patch_scope": "two mixin configs plus five FART-remapped compatibility classes; all other source entries byte-identical after decompression",
    }
    (OUT_DIR / "valkyrienskies-120-2.4.11-r72-forge-sculk-callers.manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
