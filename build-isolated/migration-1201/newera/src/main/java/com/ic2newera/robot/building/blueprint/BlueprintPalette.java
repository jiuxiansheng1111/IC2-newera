package com.ic2newera.robot.building.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resolves imported block states and turns sampled image colors into real Minecraft materials. */
public final class BlueprintPalette {
    private static final String[] DYES = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    // Approximate diffuse colors sampled from the 1.20.1 vanilla block textures.
    // Color matching only chooses a real placeable Minecraft block; it never emits a raw RGB voxel.
    private static final int[] CONCRETE = {
            0xD1D2D4, 0xE06101, 0xA9309F, 0x2389C7, 0xF0AF15, 0x5E7C16, 0xED8DAC, 0x414141,
            0x8E8E86, 0x158991, 0x64209C, 0x2C2E8F, 0x603B20, 0x495B24, 0x8E2020, 0x080A0F
    };
    private static final int[] TERRACOTTA = {
            0xD1B1A1, 0xA15325, 0x95576C, 0x706C8A, 0xBA8524, 0x677535, 0xA14E4E, 0x392A24,
            0x876B62, 0x565B5B, 0x764656, 0x4A3B5B, 0x4D3323, 0x4E522A, 0x8E3C2F, 0x251610
    };
    private static final int[] WOOL = {
            0xE9ECEC, 0xF07613, 0xBD44B3, 0x3AAFD9, 0xF8C627, 0x70B919, 0xED8DAC, 0x3E4447,
            0x8E9699, 0x158991, 0x792AAC, 0x35399D, 0x724728, 0x546D1B, 0xA12722, 0x1D1D21
    };

    private static final List<ColorBlock> COLOR_BLOCKS = createColorBlocks();

    private BlueprintPalette() { }

    public record Material(String blockId, Map<String, String> properties) {
        public Material {
            if (blockId == null || blockId.isBlank()) throw new IllegalArgumentException("palette 中缺少方块 ID");
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        public boolean isAir() {
            return blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air")
                    || blockId.equals("minecraft:void_air");
        }
    }

    /** Parses a vanilla block-state palette key, e.g. minecraft:oak_stairs[facing=north,half=bottom]. */
    public static Material parseStateString(String input) {
        if (input == null || input.isBlank() || input.length() > 512)
            throw new IllegalArgumentException("方块状态 palette 条目无效");
        String value = input.trim();
        int bracket = value.indexOf('[');
        String id = bracket < 0 ? value : value.substring(0, bracket).trim();
        if (id.indexOf(']') >= 0 || id.indexOf('[') >= 0) throw new IllegalArgumentException("方块状态格式错误：" + input);
        Map<String, String> properties = new LinkedHashMap<>();
        if (bracket >= 0) {
            if (!value.endsWith("]")) throw new IllegalArgumentException("方块状态属性格式错误：" + input);
            String body = value.substring(bracket + 1, value.length() - 1);
            if (!body.isBlank()) {
                for (String pair : body.split(",", -1)) {
                    int equals = pair.indexOf('=');
                    if (equals <= 0 || equals == pair.length() - 1)
                        throw new IllegalArgumentException("方块状态属性格式错误：" + pair);
                    String key = pair.substring(0, equals).trim();
                    String propertyValue = pair.substring(equals + 1).trim();
                    if (properties.putIfAbsent(key, propertyValue) != null)
                        throw new IllegalArgumentException("方块状态属性重复：" + key);
                }
            }
        }
        return new Material(normalizeId(id), properties);
    }

    /** Reads a Litematica/structure palette entry containing Name and optional Properties tags. */
    public static Material fromNbt(net.minecraft.nbt.CompoundTag paletteEntry) {
        if (paletteEntry == null || !paletteEntry.contains("Name", net.minecraft.nbt.Tag.TAG_STRING))
                    throw new IllegalArgumentException("palette 条目缺少 Name 字符串");
        Map<String, String> properties = new LinkedHashMap<>();
        if (paletteEntry.contains("Properties", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            net.minecraft.nbt.CompoundTag raw = paletteEntry.getCompound("Properties");
            for (String key : raw.getAllKeys()) {
                if (!raw.contains(key, net.minecraft.nbt.Tag.TAG_STRING))
                    throw new IllegalArgumentException("palette 中的方块属性不是字符串：" + key);
                properties.put(key, raw.getString(key));
            }
        }
        return new Material(normalizeId(paletteEntry.getString("Name")), properties);
    }

    /** Resolves a palette entry against the active block registry, rejecting unknown or unsafe blocks. */
    public static BlockState resolve(Material material) {
        Material normalized = normalize(material);
        ResourceLocation id = ResourceLocation.tryParse(normalized.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
            throw new IllegalArgumentException("蓝图 palette 中包含不支持的方块：" + normalized.blockId());
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR)
            return block.defaultBlockState();
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : normalized.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("方块 " + id + " 不包含属性 " + entry.getKey());
            state = applyProperty(state, property, entry.getValue(), id.toString());
        }
        if (state.getBlock() instanceof BedBlock) throw new IllegalArgumentException("机器人建筑蓝图暂不支持床");
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            // The importer pairs and removes upper halves before constructing RobotBuildPlan parts.
        }
        // Reuse RobotBuildPlan's denylist and placeable-item validation without changing its implementation.
        new com.ic2newera.robot.building.RobotBuildPlan.Part(BlockPos.ZERO, id.toString(), normalized.properties(), "main").state();
        return state;
    }

    /** Removes only block states that vanilla recomputes from neighbors or runtime signals. */
    public static Material normalize(Material material) {
        if (material == null) throw new IllegalArgumentException("蓝图 palette 条目为空");
        ResourceLocation id = ResourceLocation.tryParse(material.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id))
            throw new IllegalArgumentException("蓝图 palette 中包含不支持的方块：" + material.blockId());
        Block block = BuiltInRegistries.BLOCK.get(id);
        LinkedHashMap<String, String> properties = new LinkedHashMap<>(material.properties());

        if (block instanceof LiquidBlock) {
            String level = properties.get("level");
            if (level != null && !"0".equals(level))
                throw new IllegalArgumentException("蓝图暂不支持流动液体；水和熔岩只接受 level=0 的液源方块");
        }
        if (block instanceof SlabBlock && "double".equalsIgnoreCase(properties.get("type")))
            throw new IllegalArgumentException("蓝图暂不支持 double 双层台阶；请改用对应的完整方块或上下半台阶");

        boolean changed = false;
        if (block instanceof StairBlock) changed |= properties.remove("shape") != null;
        if (block instanceof FenceBlock || block instanceof IronBarsBlock) {
            changed |= removeAll(properties, "north", "east", "south", "west");
        }
        if (block instanceof WallBlock) {
            changed |= removeAll(properties, "north", "east", "south", "west", "up");
        }
        if (block instanceof LeavesBlock) changed |= properties.remove("distance") != null;
        if (block instanceof RedStoneWireBlock) changed |= properties.remove("power") != null;
        if ((block instanceof RedstoneLampBlock || block instanceof RedstoneTorchBlock)
                && properties.containsKey("lit")) {
            properties.remove("lit"); changed = true;
        }
        if (block instanceof DispenserBlock && properties.remove("triggered") != null) changed = true;
        if ((block instanceof ObserverBlock || block instanceof PressurePlateBlock || block instanceof ButtonBlock
                || block instanceof LeverBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock)
                && properties.remove("powered") != null) changed = true;

        return changed ? new Material(id.toString(), properties) : material;
    }

    public static boolean isSourceLiquid(Material material) {
        if (material == null) return false;
        ResourceLocation id = ResourceLocation.tryParse(material.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return false;
        if (!(BuiltInRegistries.BLOCK.get(id) instanceof LiquidBlock)) return false;
        String level = material.properties().get("level");
        return level == null || "0".equals(level);
    }

    private static boolean removeAll(Map<String, String> properties, String... names) {
        boolean changed = false;
        for (String name : names) changed |= properties.remove(name) != null;
        return changed;
    }

    /** Chooses the closest real concrete/terracotta/wool material for an opaque texture pixel. */
    public static Material fromRgb(int rgb) {
        int red = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255, blue = rgb & 255;
        ColorBlock best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ColorBlock candidate : COLOR_BLOCKS) {
            int r = (candidate.rgb >>> 16) & 255, g = (candidate.rgb >>> 8) & 255, b = candidate.rgb & 255;
            long dr = (long) red - r, dg = (long) green - g, db = (long) blue - b;
            // Green contributes more to perceived brightness than red/blue in sRGB textures.
            long distance = 3L * dr * dr + 4L * dg * dg + 2L * db * db;
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        if (best == null) throw new IllegalStateException("Minecraft 颜色方块 palette 为空");
        return new Material("minecraft:" + best.blockName, Map.of());
    }

    /** Samples a texture texel; fully transparent pixels are intentionally omitted from the voxel model. */
    public static Material fromTexture(BufferedImage image, double u, double v) {
        if (image == null || !Double.isFinite(u) || !Double.isFinite(v))
            throw new IllegalArgumentException("贴图或采样坐标无效");
        int width = image.getWidth(), height = image.getHeight();
        int x = Math.max(0, Math.min(width - 1, (int) Math.floor(u)));
        int y = Math.max(0, Math.min(height - 1, (int) Math.floor(v)));
        int color = image.getRGB(x, y);
        if (((color >>> 24) & 255) < 16) return null;
        return fromRgb(color);
    }

    private static List<ColorBlock> createColorBlocks() {
        List<ColorBlock> result = new ArrayList<>(DYES.length * 3);
        for (int i = 0; i < DYES.length; i++) {
            result.add(new ColorBlock(DYES[i] + "_concrete", CONCRETE[i]));
            result.add(new ColorBlock(DYES[i] + "_terracotta", TERRACOTTA[i]));
            result.add(new ColorBlock(DYES[i] + "_wool", WOOL[i]));
        }
        return List.copyOf(result);
    }

    private static String normalizeId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.indexOf(':') < 0) value = "minecraft:" + value;
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException("palette 中的方块 ID 无效：" + raw);
        return id.toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(BlockState state, Property property, String value, String blockId) {
        Optional parsed = property.getValue(value);
        if (parsed.isEmpty()) throw new IllegalArgumentException("方块 " + blockId + " 的属性 " + property.getName() + " 不支持值 " + value);
        return state.setValue(property, (Comparable) parsed.get());
    }

    private record ColorBlock(String blockName, int rgb) { }
}
