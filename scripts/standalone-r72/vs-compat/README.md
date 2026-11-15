# Valkyrien Skies 2.4.11 Forge sculk compatibility

This is an unofficial local Forge compatibility derivative of the locked Valkyrien Skies 2.4.11 release (`release/2.4.11`, upstream artifact SHA-256 `f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53`). The official repository homepage identifies the project as LGPL-3.0-only, and the exact `release/2.4.11` source `LICENSE` is GNU LGPL v3.0. A verbatim copy of that release license is included in the source bundle.

The locked Forge JAR's own `META-INF/mods.toml` separately declares `Apache-2.0`. Its original Apache LICENSE/NOTICE entries are preserved byte-for-byte. This is an upstream metadata discrepancy; this derivative discloses both declarations and does not change or resolve them. The nested `api-1.1.0+cf208d8b56.jar` contains no license/notice or source-attribution entries, and its published Maven POM and Gradle module metadata declare no license. No separate license conclusion is made for that split artifact.

The caller-wrapper design follows Valkyrien Skies PR [#1982](https://github.com/ValkyrienSkies/Valkyrien-Skies-2/pull/1982), authored by BonsUnleashed (commit `9bd961b`). The PR is not an official release. These five Java files are a local Forge port of that design. The final JAR contains `META-INF/newera-vs-compat.txt` plus the complete source/rebuild archive at `META-INF/newera-vs-compat-source.zip`; a duplicate source ZIP and manifest are also supplied beside the JAR for convenient review. The source archive contains no third-party binaries. Do not install the derivative alongside the base VS JAR; it replaces the matching base JAR. Keep the original JAR available for rollback.

## Changes

- Remove `feature.sculk.MixinVibrationSystemTicker` from the common mixin list on this Forge-only artifact. Mixin 0.8.5 rejects injection handlers in interface mixins; `MixinApplicatorInterface.prepareInjections` throws, and `applyInjections` does not apply those handlers.
- Add four Forge class mixins that wrap the ticker calls from the regular/calibrated sculk sensor, shrieker, and Warden/Allay entity ticker callers.
- Add `ShipVibrationUser`, which keeps non-ship users unchanged and delegates all user behavior while adapting listener positions for ship coordinates.

The caller methods and ticker target use the Forge 1.20.1 SRG mappings: `m_279962_`, `m_288182_`, `m_279963_`, `m_8119_`, and `m_280259_`. The three block caller wrappers append the enclosing lambda's `Level` and `BlockPos` in declared argument order; they do not use `@Local` capture. The wrappers were compiled against the actual nested MixinExtras 0.4.1 artifact from the locked VS JAR, then reobfuscated with ForgeGradle's `createMcpToSrg` mapping through FART.

## Rebuild

Unpack the source archive under `scripts/standalone-r72/vs-compat` in a clean copy of this repository. Supply the locked original base JAR at `build-isolated/migration-1201/dependencies/valkyrienskies-120-2.4.11.jar` (the source archive intentionally excludes third-party binaries), then:

1. Run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/standalone-r72/vs-compat/compile_vs_sculk_callers.ps1`. Each run creates a new `work-<GUID>` directory under the dedicated output folder and never recursively deletes prior class directories. It compiles against the installed Forge 47.4.28/Minecraft 1.20.1 classpath and extracts the actual VS-nested API/MixinExtras JARs.
2. Run `python scripts/standalone-r72/vs-compat/patch_vs_sculk_callers.py`. It verifies the locked source SHA, expected classes/mixins, SRG symbols, exact output entry set, and byte-for-byte identity of every unchanged original entry after decompression. It also records license/source metadata and output hashes.
3. Verify the adjacent manifest and `.sha256` file. The final JAR preserves all original entries except the two mixin configs and adds five classes plus the two `META-INF/newera-vs-compat*` entries.

Outputs:

- JAR: `build-isolated/player-packaging/20261114-r72-standalone/vs-compat/valkyrienskies-120-2.4.11-r72-forge-sculk-callers.jar`
- Source bundle: `build-isolated/player-packaging/20261114-r72-standalone/vs-compat/valkyrienskies-r72-sculk-callers-source.zip`

This build work does not launch Minecraft or modify a world. Verify clean startup, Forge wrapper application, and ship-to-world/world-to-ship vibration behavior in a disposable world before treating it as accepted.
