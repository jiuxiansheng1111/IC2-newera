# New Era Ponder Fix source

Small Forge 1.20.1 client compatibility mod for Create More Recipes' Block Spout Ponder scene.

## Contents

- `java/com/ic2newera/ponderfix/PonderConfigPlugin.java`: source/version gate and exact ASM `preApply` edit, kept outside the Mixin package.
- `java/com/ic2newera/ponderfix/NewEraPonderFixMod.java`: minimal Forge entry point with no gameplay or network behavior.
- `java/com/ic2newera/ponderfix/mixin/BlockSpoutPonderMarkerMixin.java` and `resources/newera_ponder_fix.mixins.json`: empty client-only marker that enables the gated transformation.
- `resources/META-INF/mods.toml`: mod identity, Minecraft/Forge version bounds, Chinese display name, and `displayTest="IGNORE_ALL_VERSION"`.
- `resources/META-INF/MANIFEST.MF`: registers the Mixin configuration with Forge's Mixin bootstrap.

The marker is listed only under the Mixin configuration's `client` section. The plugin changes `fr/iglee42/cmr/ponder/CustomPonderScenes.blockSpout(SceneBuilder, SceneBuildingUtil)` only when the loading mod list contains the exact CMR `1.2.1-fix1` and PonderJS `2.1.0` versions. It verifies that local 3 is initialized exactly once by the `RandomSource` factory and is never reassigned, then matches the exact `builder.getScene().getWorld().random`, `1.5F`, and `VecHelper.offsetRandomly` sequence. The four world-random chain instructions are replaced with `ALOAD 3`; the offset and `1.5F` argument remain intact. If target bytecode differs, it logs a warning and leaves the class unchanged.

This source contains no CMR/PonderJS classes and opens no network channel. `IGNORE_ALL_VERSION` is declared inside the `[[mods]]` section so a server that does not install this client-only helper does not show a compatibility mismatch.

## Local compile inputs

For Java compilation, use the local Forge 1.20.1-47.4.28 development artifacts already present in the Gradle cache, including `fmlcore`, `fmlloader`, and the Forge universal artifact, plus the Mixin and ASM classes exposed by the Forge development classpath. Do not package those dependencies or third-party mod classes in the resulting JAR.

## Static review points

Before packaging, inspect the target CMR class and confirm the `blockSpout` descriptor, the `RandomSource` factory followed by `ASTORE 3`, no later write to local 3, and exactly one executable instruction sequence:

`ALOAD 0` → `SceneBuilder.getScene()` → `PonderScene.getWorld()` → `PonderLevel.f_46441_` → `1.5F` → `VecHelper.offsetRandomly(Vec3, RandomSource, float)`

The plugin's `LoadingModList.getModFileById`/`IModInfo` version check runs during Mixin configuration application, outside Forge CoreModEngine's restricted JavaScript `Java.type` sandbox. Then inspect the transformed method to confirm that only that chain is replaced by `ALOAD 3`; the `VecHelper.offsetRandomly` call and its `1.5F` argument must remain. No game launch or automated test is part of this source review.
