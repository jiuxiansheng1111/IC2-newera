package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Indexes only industrial block entities in already loaded chunks. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class IndustrialNetworkIndex {
    private static final int MAX_RADIUS = 24;
    private static final int MAX_CANDIDATES = 512;
    private static final Map<ServerLevel, Map<Long, Set<BlockPos>>> LOADED_TILES = new WeakHashMap<>();

    private IndustrialNetworkIndex() { }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            indexChunk(level, chunk);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            synchronized (LOADED_TILES) {
                Map<Long, Set<BlockPos>> index = LOADED_TILES.get(level);
                if (index != null) index.remove(chunk.getPos().toLong());
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor accessor = event.getLevel();
        if (accessor instanceof ServerLevel level) {
            synchronized (LOADED_TILES) { LOADED_TILES.remove(level); }
        }
    }

    static void onEntityLoad(BlockEntity entity) {
        if (entity.getLevel() instanceof ServerLevel level && isIndexed(entity)) {
            add(level, entity.getBlockPos());
        }
    }

    static void onEntityRemoved(BlockEntity entity) {
        if (entity.getLevel() instanceof ServerLevel level) remove(level, entity.getBlockPos());
    }

    private static void indexChunk(ServerLevel level, LevelChunk chunk) {
        Set<BlockPos> positions = new HashSet<>();
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (isIndexed(entity)) positions.add(entity.getBlockPos().immutable());
        }
        synchronized (LOADED_TILES) {
            Map<Long, Set<BlockPos>> index = LOADED_TILES.computeIfAbsent(level, ignored -> new HashMap<>());
            if (positions.isEmpty()) index.remove(chunk.getPos().toLong());
            else index.put(chunk.getPos().toLong(), positions);
        }
    }

    private static boolean isIndexed(BlockEntity entity) {
        return entity instanceof OwnedIndustrialBlockEntity || entity instanceof IndustrialFiberBlockEntity;
    }

    private static void add(ServerLevel level, BlockPos pos) {
        synchronized (LOADED_TILES) {
            LOADED_TILES.computeIfAbsent(level, ignored -> new HashMap<>())
                    .computeIfAbsent(new ChunkPos(pos).toLong(), ignored -> new HashSet<>()).add(pos.immutable());
        }
    }

    private static void remove(ServerLevel level, BlockPos pos) {
        synchronized (LOADED_TILES) {
            Map<Long, Set<BlockPos>> index = LOADED_TILES.get(level);
            if (index == null) return;
            long key = new ChunkPos(pos).toLong();
            Set<BlockPos> positions = index.get(key);
            if (positions != null) {
                positions.remove(pos);
                if (positions.isEmpty()) index.remove(key);
            }
        }
    }

    static List<BlockEntity> nearby(ServerLevel level, BlockPos center, int radius, int limit) {
        int boundedRadius = Math.max(0, Math.min(MAX_RADIUS, radius));
        int boundedLimit = Math.max(0, Math.min(MAX_CANDIDATES, limit));
        int minChunkX = Math.floorDiv(center.getX() - boundedRadius, 16);
        int maxChunkX = Math.floorDiv(center.getX() + boundedRadius, 16);
        int minChunkZ = Math.floorDiv(center.getZ() - boundedRadius, 16);
        int maxChunkZ = Math.floorDiv(center.getZ() + boundedRadius, 16);
        List<BlockPos> positions = new ArrayList<>();
        synchronized (LOADED_TILES) {
            Map<Long, Set<BlockPos>> index = LOADED_TILES.get(level);
            if (index == null) return List.of();
            for (int x = minChunkX; x <= maxChunkX && positions.size() < boundedLimit; x++) {
                for (int z = minChunkZ; z <= maxChunkZ && positions.size() < boundedLimit; z++) {
                    Set<BlockPos> entries = index.get(ChunkPos.asLong(x, z));
                    if (entries == null) continue;
                    for (BlockPos pos : entries) {
                        if (positions.size() >= boundedLimit) break;
                        if (Math.abs(pos.getX() - center.getX()) <= boundedRadius
                                && Math.abs(pos.getZ() - center.getZ()) <= boundedRadius
                                && Math.abs(pos.getY() - center.getY()) <= boundedRadius) {
                            positions.add(pos);
                        }
                    }
                }
            }
        }
        List<BlockEntity> result = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (!level.hasChunkAt(pos)) continue;
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null) result.add(entity);
        }
        return result;
    }
}
