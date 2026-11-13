package com.ic2newera.field;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import com.ic2newera.field.nav.RouteDirections;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Standalone mapped-runtime checks. Run explicitly; this source is outside all Gradle source sets. */
public final class FieldRoutesIntegrationChecks {
    private FieldRoutesIntegrationChecks() { }
    private record DimensionPosition(ResourceLocation dimension, BlockPos position) { }

    public static void main(String[] args) throws Exception {
        checkPhotoLocationPairing();
        checkSnapshotRoundTrips();
        checkSnapshotRejectsMalformedLists();
        checkSharedRouteProjection();
        System.out.println("FieldRoutesIntegrationChecks: 4 groups passed");
    }

    private static void checkPhotoLocationPairing() throws Exception {
        Method selectionKey = EcologyAlbum.class.getDeclaredMethod("selectionKey", CompoundTag.class);
        Method parseSelection = EcologyAlbum.class.getDeclaredMethod("parseSelection", String.class);
        Method describe = EcologyAlbum.class.getDeclaredMethod("describe", CompoundTag.class);
        selectionKey.setAccessible(true);
        parseSelection.setAccessible(true);
        describe.setAccessible(true);

        CompoundTag first = photo("frameA_100", "spawn:tuna", "minecraft:overworld", -31, 72, 84,
                "ad_astra:moon", 401, 93, -204);
        CompoundTag second = photo("frameB_200", "spawn:tuna", "minecraft:overworld", 111, 66, -9,
                "minecraft:the_nether", -17, 48, 202);
        String firstSelection = (String) selectionKey.invoke(null, first);
        String secondSelection = (String) selectionKey.invoke(null, second);
        check(!firstSelection.equals(secondSelection), "same-species photographs need distinct selection keys");
        checkSelection(parseSelection, firstSelection, "frameA_100", "spawn:tuna");
        checkSelection(parseSelection, secondSelection, "frameB_200", "spawn:tuna");

        Map<String, CompoundTag> bySelection = Map.of(firstSelection, first, secondSelection, second);
        CompoundTag selectedFirst = bySelection.get(firstSelection);
        CompoundTag selectedSecond = bySelection.get(secondSelection);
        DimensionPosition firstCamera = location(selectedFirst, "CameraDimension", "CameraPos");
        DimensionPosition firstSubject = location(selectedFirst, "SubjectDimension", "SubjectPos");
        DimensionPosition secondCamera = location(selectedSecond, "CameraDimension", "CameraPos");
        DimensionPosition secondSubject = location(selectedSecond, "SubjectDimension", "SubjectPos");
        check(firstCamera.position().equals(new BlockPos(-31, 72, 84)), "first camera position");
        check(firstSubject.position().equals(new BlockPos(401, 93, -204)), "first subject position");
        check(firstCamera.dimension().equals(id("minecraft:overworld")), "first camera dimension");
        check(firstSubject.dimension().equals(id("ad_astra:moon")), "first subject dimension");
        check(secondCamera.position().equals(new BlockPos(111, 66, -9)), "second camera position");
        check(secondSubject.position().equals(new BlockPos(-17, 48, 202)), "second subject position");
        check(secondCamera.dimension().equals(id("minecraft:overworld")), "second camera dimension");
        check(secondSubject.dimension().equals(id("minecraft:the_nether")), "second subject dimension");
        check(!firstCamera.equals(firstSubject), "camera and subject positions must remain separate");
        check(!secondCamera.equals(secondSubject), "second camera and subject positions must remain separate");

        String summary = (String) describe.invoke(null, first);
        check(summary.contains("拍摄点 minecraft:overworld -31,72,84"), "archive summary camera label/coordinates");
        check(summary.contains("被摄点 ad_astra:moon 401,93,-204"), "archive summary subject label/coordinates");
    }

    private static CompoundTag photo(String id, String species, String cameraDimension, int cx, int cy, int cz,
                                     String subjectDimension, int sx, int sy, int sz) {
        CompoundTag row = new CompoundTag();
        row.putString("PhotoId", id);
        row.putString("Species", species);
        row.putString("CameraDimension", cameraDimension);
        row.putLong("CameraPos", new BlockPos(cx, cy, cz).asLong());
        row.putString("SubjectDimension", subjectDimension);
        row.putLong("SubjectPos", new BlockPos(sx, sy, sz).asLong());
        row.putString("Photographer", "NavCheck");
        return row;
    }

    private static void checkSelection(Method parseSelection, String value, String photo, String species) throws Exception {
        String[] parsed = (String[]) parseSelection.invoke(null, value);
        check(parsed != null && parsed.length == 2, "selection parses: " + value);
        check(photo.equals(parsed[0]), "selection photo id: " + value);
        check(species.equals(parsed[1]), "selection species: " + value);
    }

    private static DimensionPosition location(CompoundTag row, String dimensionKey, String positionKey) {
        ResourceLocation dimension = id(row.getString(dimensionKey));
        return new DimensionPosition(dimension, BlockPos.of(row.getLong(positionKey)));
    }

    private static void checkSnapshotRoundTrips() {
        FieldRoutes.Snapshot signed = new FieldRoutes.Snapshot("ad_astra:moon", new BlockPos(-123, -9, 508),
                "拍摄点", "已规划到目标坐标", "向北上行  -120, -8, 508",
                List.of(new BlockPos(-123, -9, 508), new BlockPos(-123, -8, 508), new BlockPos(-123, -7, 508)), true);
        check(signed.equals(roundTrip(signed)), "signed coordinates, dimension and status survive packet round trip");

        FieldRoutes.Snapshot vertical = new FieldRoutes.Snapshot("minecraft:overworld", new BlockPos(5, 80, -7),
                "垂直目标", "分段路线", "上行  5, 81, -7",
                List.of(new BlockPos(5, 79, -7), new BlockPos(5, 80, -7), new BlockPos(5, 81, -7)), false);
        check(vertical.equals(roundTrip(vertical)), "pure vertical path survives packet round trip");

        FieldRoutes.Snapshot empty = FieldRoutes.Snapshot.empty();
        check(empty.equals(roundTrip(empty)), "empty snapshot survives packet round trip");
        check(!roundTrip(signed).dimension().equals(roundTrip(vertical).dimension()), "dimension ids remain exact");
    }

    private static FieldRoutes.Snapshot roundTrip(FieldRoutes.Snapshot snapshot) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FieldRoutes.encode(snapshot, buffer);
            FieldRoutes.Snapshot decoded = FieldRoutes.decode(buffer);
            check(!buffer.isReadable(), "packet decoder consumes exactly one snapshot");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void checkSnapshotRejectsMalformedLists() {
        FriendlyByteBuf truncated = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FieldRoutes.encode(new FieldRoutes.Snapshot("ad_astra:moon", new BlockPos(-3, 4, 5), "截断", "", "",
                    List.of(new BlockPos(0, 0, 0)), false), truncated);
            truncated.writerIndex(truncated.writerIndex() - 1);
            expectRejected(truncated, "truncated packet");
        } finally {
            truncated.release();
        }

        for (int count : new int[]{-1, 193}) {
            FriendlyByteBuf malformed = new FriendlyByteBuf(Unpooled.buffer());
            try {
                malformed.writeUtf("ad_astra:moon", 256);
                malformed.writeBlockPos(new BlockPos(-1, -2, -3));
                malformed.writeUtf("坏数据", 64);
                malformed.writeUtf("", 128);
                malformed.writeUtf("", 128);
                malformed.writeBoolean(false);
                malformed.writeVarInt(count);
                expectRejected(malformed, "point count " + count);
            } finally {
                malformed.release();
            }
        }
    }

    private static void checkSharedRouteProjection() {
        check("向北".equals(RouteDirections.heading(0, 0, -1)), "negative Z points north");
        check("向东".equals(RouteDirections.heading(1, 0, 0)), "positive X points east");
        check("向南".equals(RouteDirections.heading(0, 0, 1)), "positive Z points south");
        check("向西".equals(RouteDirections.heading(-1, 0, 0)), "negative X points west");
        check("上行".equals(RouteDirections.heading(0, 1, 0)), "pure vertical ascent has no compass direction");
        check("下行".equals(RouteDirections.heading(0, -1, 0)), "pure vertical descent has no compass direction");
        check("向东北上行".equals(RouteDirections.heading(1, 1, -1)), "diagonal ascent combines bearing and elevation");
        check("原地".equals(RouteDirections.heading(0, 0, 0)), "zero displacement is local");

        check(RouteDirections.mapX(0, 1, 100, 10.0) == 110, "east is right on the map");
        check(RouteDirections.mapX(0, -1, 100, 10.0) == 90, "west is left on the map");
        check(RouteDirections.mapY(0, -1, 100, 10.0) == 90, "north is up on the map");
        check(RouteDirections.mapY(0, 1, 100, 10.0) == 110, "south is down on the map");
        check(RouteDirections.mapX(-100, -103, 200, 8.0) == 176, "negative world coordinates project by relative offset");
        check(RouteDirections.mapY(-40, -42, 200, 8.0) == 184, "negative Z projects upward by relative offset");
    }

    private static void expectRejected(FriendlyByteBuf buffer, String description) {
        try {
            FieldRoutes.decode(buffer);
            throw new AssertionError(description + " must be rejected");
        } catch (IndexOutOfBoundsException | IllegalArgumentException expected) {
            // The decoder must reject incomplete and out-of-range route packets.
        }
    }

    private static ResourceLocation id(String text) {
        return java.util.Objects.requireNonNull(ResourceLocation.tryParse(text), text);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
