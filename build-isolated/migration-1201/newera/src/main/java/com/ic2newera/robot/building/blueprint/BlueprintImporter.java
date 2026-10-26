package com.ic2newera.robot.building.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ic2newera.robot.building.RobotBuildPlan;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.Iterator;

/**
 * Bounded, side-effect-free importer for vanilla structure NBT, Sponge schematics, Litematica,
 * Blockbench cuboids, and the compact blueprint JSON described in art/robot-blueprints/README.md.
 * It only returns a proposal; it never places blocks or applies a production program.
 */
public final class BlueprintImporter {
    public static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_NBT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FINAL_PARTS = 16_384;
    public static final int MAX_SOURCE_CELLS = 1_000_000;
    public static final int MAX_OPERATION_SAMPLES = 1_000_000;
    public static final int MAX_JSON_DEPTH = 96;
    private static final int MAX_PALETTE = 4096;
    private static final int MAX_REGIONS = 128;
    private static final int MAX_OPS = 512;
    private static final int MAX_BB_ELEMENTS = 16_384;
    private static final int MAX_TEXTURE_COUNT = 64;
    private static final long MAX_TEXTURE_PIXELS = 16L * 1024L * 1024L;
    private static final double EPSILON = 1.0E-6;

    private BlueprintImporter() { }

    /** Imports a user-authored structure as a non-executable house/blueprint proposal. */
    public static RobotBuildPlan read(String filename, byte[] content) {
        return read(filename, content, "house", titleFromFilename(filename));
    }

    /**
     * The only allowed kind for file imports is house. Production plans require the server's own
     * approved planner and cannot be smuggled in through an uploaded blueprint.
     */
    public static RobotBuildPlan read(String filename, byte[] content, String kind, String title) {
        if (!"house".equals(kind)) throw new IllegalArgumentException("上传文件只能生成房屋蓝图提案");
        if (filename == null || filename.isBlank() || filename.length() > 256)
            throw new IllegalArgumentException("蓝图文件名无效");
        if (content == null || content.length == 0) throw new IllegalArgumentException("蓝图文件为空");
        if (content.length > MAX_INPUT_BYTES) throw new IllegalArgumentException("蓝图文件超过 8 MiB 上传上限");
        String extension = extension(filename);
        try {
            return switch (extension) {
                case "nbt" -> finish(filename, title, readStructureNbt(content));
                case "schem" -> finish(filename, title, readSponge(content));
                case "litematic" -> finish(filename, title, readLitematic(content));
                case "bbmodel" -> finish(filename, title, readBlockbench(content));
                case "json" -> finish(filename, title, readCompactJson(content));
                case "png", "jpg", "jpeg" -> throw new IllegalArgumentException(
                        "参考图片不会直接转换成体素，请先让外观规划器生成紧凑蓝图 JSON");
                default -> throw new IllegalArgumentException("不支持蓝图格式：." + extension
                        + "（支持 .nbt、.schem v2/v3、.litematic、.bbmodel 和紧凑 .json）");
            };
        } catch (IllegalArgumentException exception) {
            if (containsChinese(exception.getMessage())) throw exception;
            throw new IllegalArgumentException("蓝图包含无效数据，或使用了不支持、不可放置的方块状态", exception);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("蓝图文件结构无效或字段类型不正确", exception);
        }
    }

    private static boolean containsChinese(String message) {
        if (message == null) return false;
        for (int index = 0; index < message.length(); index++) {
            char character = message.charAt(index);
            if (character >= '\u4E00' && character <= '\u9FFF') return true;
        }
        return false;
    }

    private static Draft readStructureNbt(byte[] bytes) {
        CompoundTag root = readNbt(bytes);
        if (root.contains("structure", Tag.TAG_COMPOUND)) root = root.getCompound("structure");
        int[] size = readIntVector(root, "size", null);
        long volume = checkedVolume(size[0], size[1], size[2], "结构 NBT");
        ListTag paletteTags;
        if (root.contains("palette", Tag.TAG_LIST)) paletteTags = root.getList("palette", Tag.TAG_COMPOUND);
        else if (root.contains("palettes", Tag.TAG_LIST)) {
            ListTag alternatives = root.getList("palettes", Tag.TAG_LIST);
            if (alternatives.isEmpty()) throw new IllegalArgumentException("结构 NBT 的 palettes 列表为空");
            paletteTags = alternatives.getList(0);
        } else throw new IllegalArgumentException("结构 NBT 缺少 palette 列表");
        if (paletteTags.isEmpty() || paletteTags.size() > MAX_PALETTE)
            throw new IllegalArgumentException("结构 NBT 的 palette 必须包含 1 到 " + MAX_PALETTE + " 个方块状态");
        List<BlueprintPalette.Material> palette = new ArrayList<>(paletteTags.size());
        for (int index = 0; index < paletteTags.size(); index++) palette.add(BlueprintPalette.fromNbt(paletteTags.getCompound(index)));
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        if (blocks.size() > MAX_FINAL_PARTS * 4) throw new IllegalArgumentException("结构 NBT 的方块记录过多");
        Draft result = new Draft();
        result.sourceCells = volume;
        result.notes.add("已忽略结构实体以及方块实体、物品栏和命令数据。");
        if (root.contains("palettes", Tag.TAG_LIST)) result.notes.add("结构文件包含多个 palette，本次仅导入第一个。");
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag record = blocks.getCompound(index);
            int[] pos = readIntVector(record, "pos", null);
            int stateIndex = record.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.size()) throw new IllegalArgumentException("结构方块引用了无效的 palette 序号");
            if (pos[0] < 0 || pos[0] >= size[0] || pos[1] < 0 || pos[1] >= size[1] || pos[2] < 0 || pos[2] >= size[2])
                throw new IllegalArgumentException("结构方块坐标超出声明的尺寸");
            result.put(pos[0], pos[1], pos[2], palette.get(stateIndex));
        }
        return result;
    }

    private static Draft readSponge(byte[] bytes) {
        CompoundTag root = readNbt(bytes);
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) root = root.getCompound("Schematic");
        int version = root.getInt("Version");
        if (version != 2 && version != 3)
            throw new IllegalArgumentException("Sponge schematic 版本 " + version + " 不受支持，目前仅支持 v2 和 v3");
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        long volume = checkedVolume(width, height, length, "Sponge schematic");
        CompoundTag blockContainer = version == 3 ? root.getCompound("Blocks") : root;
        CompoundTag rawPalette = blockContainer.getCompound("Palette");
        if (rawPalette.isEmpty() || rawPalette.size() > MAX_PALETTE)
            throw new IllegalArgumentException("Sponge palette 必须包含 1 到 " + MAX_PALETTE + " 个方块状态");
        Map<Integer, BlueprintPalette.Material> palette = new HashMap<>();
        for (String key : rawPalette.getAllKeys()) {
            int id = rawPalette.getInt(key);
            if (id < 0 || palette.putIfAbsent(id, BlueprintPalette.parseStateString(key)) != null)
                throw new IllegalArgumentException("Sponge palette 序号无效");
        }
        String dataKey = version == 3 ? "Data" : "BlockData";
        byte[] blockData = blockContainer.getByteArray(dataKey);
        if (blockData.length > MAX_NBT_BYTES) throw new IllegalArgumentException("Sponge BlockData 超过解压数据上限");
        Draft result = new Draft();
        result.sourceCells = volume;
        result.notes.add("已忽略 Sponge 方块实体、物品栏、实体和命令数据。");
        VarInts indexes = new VarInts(blockData);
        for (int flat = 0; flat < volume; flat++) {
            int paletteIndex = indexes.next();
            BlueprintPalette.Material material = palette.get(paletteIndex);
            if (material == null) throw new IllegalArgumentException("Sponge BlockData 含有无效的 palette 序号");
            if (!material.isAir()) {
                int x = flat % width;
                int z = (flat / width) % length;
                int y = flat / (width * length);
                result.put(x, y, z, material);
            }
        }
        if (indexes.hasRemaining()) throw new IllegalArgumentException("Sponge BlockData 含有多余数据");
        return result;
    }

    private static Draft readLitematic(byte[] bytes) {
        CompoundTag root = readNbt(bytes);
        if (root.contains("LitematicaSchematic", Tag.TAG_COMPOUND)) root = root.getCompound("LitematicaSchematic");
        CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty() || regions.size() > MAX_REGIONS)
            throw new IllegalArgumentException("Litematica 文件必须包含 1 到 " + MAX_REGIONS + " 个区域");
        Draft result = new Draft();
        result.notes.add("已忽略 Litematica 实体、方块实体、物品栏、待处理 tick 和命令数据。");
        List<String> names = regions.getAllKeys().stream().sorted().toList();
        List<RegionBounds> occupiedRegions = new ArrayList<>();
        for (String name : names) {
            CompoundTag region = regions.getCompound(name);
            int[] position = readVector(region.get("Position"), "区域 Position");
            int[] signedSize = readVector(region.get("Size"), "区域 Size");
            int sx = absDimension(signedSize[0]), sy = absDimension(signedSize[1]), sz = absDimension(signedSize[2]);
            long volume = checkedVolume(sx, sy, sz, "Litematica 区域 " + name);
            result.sourceCells += volume;
            if (result.sourceCells > MAX_SOURCE_CELLS) throw new IllegalArgumentException("Litematica 源文件体素数量超过上限");
            RegionBounds regionBounds = RegionBounds.of(position, signedSize, sx, sy, sz);
            if (occupiedRegions.stream().anyMatch(regionBounds::intersects))
                throw new IllegalArgumentException("暂不支持互相重叠的 Litematica 区域");
            occupiedRegions.add(regionBounds);
            ListTag paletteTags = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (paletteTags.isEmpty() || paletteTags.size() > MAX_PALETTE)
                throw new IllegalArgumentException("Litematica 区域 palette 必须包含 1 到 " + MAX_PALETTE + " 个方块状态");
            BlueprintPalette.Material[] palette = new BlueprintPalette.Material[paletteTags.size()];
            for (int i = 0; i < paletteTags.size(); i++) palette[i] = BlueprintPalette.fromNbt(paletteTags.getCompound(i));
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            long[] packed = region.getLongArray("BlockStates");
            long bitCount = volume * bits;
            int requiredLongs = (int) ((bitCount + 63L) >>> 6);
            if (packed.length != requiredLongs) throw new IllegalArgumentException("Litematica BlockStates 长度与区域尺寸或 palette 不匹配");
            for (int flat = 0; flat < volume; flat++) {
                int paletteIndex = packedValue(packed, flat, bits);
                if (paletteIndex < 0 || paletteIndex >= palette.length) throw new IllegalArgumentException("Litematica BlockStates 含有无效的 palette 序号");
                BlueprintPalette.Material material = palette[paletteIndex];
                if (material.isAir()) continue;
                int x = flat % sx;
                int z = (flat / sx) % sz;
                int y = flat / (sx * sz);
                int worldX = position[0] + (signedSize[0] < 0 ? -x : x);
                int worldY = position[1] + (signedSize[1] < 0 ? -y : y);
                int worldZ = position[2] + (signedSize[2] < 0 ? -z : z);
                result.put(worldX, worldY, worldZ, material);
            }
        }
        return result;
    }

    private static Draft readCompactJson(byte[] bytes) {
        String json = decodeUtf8(bytes);
        checkJsonDepth(json);
        JsonElement parsed;
        try { parsed = JsonParser.parseString(json); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("紧凑蓝图 JSON 格式无效", exception); }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("紧凑蓝图 JSON 根节点必须是对象");
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("version") || root.get("version").getAsInt() != 1)
            throw new IllegalArgumentException("紧凑蓝图 JSON 的 version 必须为 1");
        if (root.has("kind") && !root.get("kind").getAsString().equals("house"))
            throw new IllegalArgumentException("紧凑蓝图 JSON 只能描述房屋建筑，不能提交生产计划");
        JsonObject paletteJson = root.getAsJsonObject("palette");
        if (paletteJson == null || paletteJson.size() == 0 || paletteJson.size() > MAX_PALETTE)
            throw new IllegalArgumentException("紧凑蓝图 palette 必须包含 1 到 " + MAX_PALETTE + " 项");
        Map<String, BlueprintPalette.Material> palette = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : paletteJson.entrySet()) {
            JsonElement value = entry.getValue();
            BlueprintPalette.Material material;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                material = BlueprintPalette.parseStateString(value.getAsString());
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                String block = object.has("block") ? object.get("block").getAsString() : object.has("Name") ? object.get("Name").getAsString() : "";
                Map<String, String> properties = new LinkedHashMap<>();
                JsonObject props = object.has("properties") ? object.getAsJsonObject("properties") : object.has("Properties") ? object.getAsJsonObject("Properties") : null;
                if (props != null) for (Map.Entry<String, JsonElement> prop : props.entrySet()) properties.put(prop.getKey(), prop.getValue().getAsString());
                material = new BlueprintPalette.Material(block, properties);
            } else throw new IllegalArgumentException("紧凑蓝图 palette 的值必须是方块状态字符串或对象");
            palette.put(entry.getKey(), material);
        }
        Draft result = new Draft();
        result.allowTransientExpansion = true;
        if (root.has("title")) result.title = boundedTitle(root.get("title").getAsString());
        JsonArray ops = root.has("ops") ? root.getAsJsonArray("ops") : new JsonArray();
        if (ops.size() > MAX_OPS) throw new IllegalArgumentException("紧凑蓝图最多支持 " + MAX_OPS + " 个操作");
        long samples = 0;
        for (JsonElement opElement : ops) {
            if (!opElement.isJsonObject()) throw new IllegalArgumentException("每个紧凑蓝图操作都必须是对象");
            JsonObject op = opElement.getAsJsonObject();
            String type = stringValue(op, "type", "").toLowerCase(Locale.ROOT);
            if (type.equals("clear")) {
                int[] from = readJsonVector(op.getAsJsonArray("from"), "clear.from");
                int[] to = readJsonVector(op.getAsJsonArray("to"), "clear.to");
                long count = boxVolume(from, to);
                samples += count;
                if (samples > MAX_OPERATION_SAMPLES) throw new IllegalArgumentException("紧凑蓝图操作的累计采样位置超过 1,000,000 个");
                result.clearBox(from, to);
                continue;
            }
            if (!type.equals("fill") && !type.equals("box") && !type.equals("ellipsoid"))
                throw new IllegalArgumentException("不支持的蓝图操作类型：" + type);
            int[] from = readJsonVector(op.getAsJsonArray("from"), type + ".from");
            int[] to = readJsonVector(op.getAsJsonArray("to"), type + ".to");
            long count = boxVolume(from, to);
            samples += count;
            if (samples > MAX_OPERATION_SAMPLES) throw new IllegalArgumentException("紧凑蓝图操作的累计采样位置超过 1,000,000 个");
            String paletteKey = paletteReference(op.get("palette"));
            BlueprintPalette.Material material = palette.get(paletteKey);
            if (material == null) throw new IllegalArgumentException("操作引用了未知的 palette 键：" + paletteKey);
            boolean hollow = op.has("hollow") && op.get("hollow").getAsBoolean();
            result.expandShape(type, from, to, material, hollow);
        }
        JsonArray blocks = root.has("blocks") ? root.getAsJsonArray("blocks") : new JsonArray();
        if (blocks.size() > MAX_SOURCE_CELLS) throw new IllegalArgumentException("紧凑蓝图的细节方块过多");
        samples += blocks.size();
        if (samples > MAX_OPERATION_SAMPLES) throw new IllegalArgumentException("紧凑蓝图操作与细节方块的累计采样位置超过 1,000,000 个");
        for (JsonElement blockElement : blocks) {
            if (!blockElement.isJsonObject()) throw new IllegalArgumentException("每个蓝图细节方块都必须是对象");
            JsonObject block = blockElement.getAsJsonObject();
            int[] pos = readJsonVector(block.getAsJsonArray("pos"), "block.pos");
            BlueprintPalette.Material material;
            if (block.has("block")) material = BlueprintPalette.parseStateString(block.get("block").getAsString());
            else {
                String key = paletteReference(block.get("palette"));
                material = palette.get(key);
                if (material == null) throw new IllegalArgumentException("方块引用了未知的 palette 键：" + key);
            }
            result.put(pos[0], pos[1], pos[2], material);
        }
        result.sourceCells = samples;
        result.allowTransientExpansion = false;
        result.notes.add("紧凑 JSON 操作只生成建筑提案；clear 仅移除提案中的方块。");
        result.notes.add("上传文件不能提交生产程序或直接执行机器人任务。");
        return result;
    }

    private static Draft readBlockbench(byte[] bytes) {
        String json = decodeUtf8(bytes);
        checkJsonDepth(json);
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Blockbench 工程根节点必须是对象");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("Blockbench JSON 格式无效", exception);
        }
        JsonArray elementsJson = root.has("elements") && root.get("elements").isJsonArray()
                ? root.getAsJsonArray("elements") : new JsonArray();
        if (elementsJson.isEmpty() || elementsJson.size() > MAX_BB_ELEMENTS)
            throw new IllegalArgumentException("Blockbench 工程必须包含 1 到 " + MAX_BB_ELEMENTS + " 个模型元素");
        Map<String, JsonObject> elements = new LinkedHashMap<>();
        List<JsonObject> elementOrder = new ArrayList<>(elementsJson.size());
        int sequential = 0;
        for (JsonElement element : elementsJson) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Blockbench 模型元素不是对象");
            JsonObject object = element.getAsJsonObject();
            String type = stringValue(object, "type", "cube");
            if (!type.equals("cube")) throw new IllegalArgumentException("Blockbench 导入仅支持长方体，当前元素类型不受支持：" + type);
            String uuid = stringValue(object, "uuid", "#" + sequential);
            if (elements.putIfAbsent(uuid, object) != null) throw new IllegalArgumentException("Blockbench 模型元素 UUID 重复");
            elementOrder.add(object);
            sequential++;
        }
        List<BufferedImage> textures = readEmbeddedTextures(root);
        Map<String, Integer> textureIndexes = readTextureIndexes(root);
        List<BbElement> transformed = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Map<String, JsonObject> groups = new HashMap<>();
        if (root.has("groups") && root.get("groups").isJsonArray()) {
            for (JsonElement groupElement : root.getAsJsonArray("groups")) {
                if (!groupElement.isJsonObject()) continue;
                JsonObject group = groupElement.getAsJsonObject();
                String uuid = stringValue(group, "uuid", "");
                if (!uuid.isBlank()) groups.put(uuid, group);
            }
        }
        if (root.has("outliner") && root.get("outliner").isJsonArray()) {
            for (JsonElement node : root.getAsJsonArray("outliner"))
                collectOutliner(node, Matrix.identity(), elements, groups, textures, textureIndexes, visited, transformed, 0);
        }
        if (transformed.isEmpty()) {
            for (int elementIndex = 0; elementIndex < elementOrder.size(); elementIndex++) {
                JsonObject element = elementOrder.get(elementIndex);
                String uuid = stringValue(element, "uuid", "#" + elementIndex);
                if (elementVisible(element) && visited.add(uuid)) transformed.add(makeBbElement(element, Matrix.identity(), textures, textureIndexes));
            }
        }
        if (transformed.isEmpty()) throw new IllegalArgumentException("Blockbench outliner 中没有可见长方体");

        Bounds bounds = new Bounds();
        for (BbElement element : transformed) bounds.include(element.worldCorners());
        if (!bounds.valid()) throw new IllegalArgumentException("Blockbench 模型边界无效或为空");
        double maxExtent = Math.max(bounds.maxX - bounds.minX, Math.max(bounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ));
        double scale = maxExtent > 48.0 ? 48.0 / maxExtent : 1.0;
        Matrix resize = Matrix.scaleAround(bounds.centerX(), bounds.centerY(), bounds.centerZ(), scale);
        if (scale < 1.0) {
            for (int i = 0; i < transformed.size(); i++) transformed.set(i, transformed.get(i).withParent(resize));
        }

        Draft draft = new Draft();
        if (scale < 1.0) draft.notes.add("Blockbench 模型已等比例缩小，最长边不超过 48 格。");
        draft.notes.add("Blockbench 长方体已按 Minecraft 方块采样；透明贴图采样点已跳过。");
        long samples = 0;
        for (BbElement element : transformed) {
            double[][] corners = element.worldCorners();
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (double[] point : corners) {
                minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
                minZ = Math.min(minZ, point[2]); maxZ = Math.max(maxZ, point[2]);
            }
            int fromX = safeFloor(minX), toX = safeCeil(maxX) - 1;
            int fromY = safeFloor(minY), toY = safeCeil(maxY) - 1;
            int fromZ = safeFloor(minZ), toZ = safeCeil(maxZ) - 1;
            long candidateCount = boundedVoxelVolume(fromX, toX, fromY, toY, fromZ, toZ,
                    "Blockbench 长方体累计体素采样位置超过 1,000,000 个");
            if (samples + candidateCount > MAX_OPERATION_SAMPLES)
                throw new IllegalArgumentException("Blockbench 长方体累计体素采样位置超过 1,000,000 个");
            long before = draft.blocks.size();
            boolean emittedThisElement = false;
            for (int y = fromY; y <= toY; y++) for (int z = fromZ; z <= toZ; z++) for (int x = fromX; x <= toX; x++) {
                samples++;
                double[] local = element.transform.inversePoint(x + 0.5, y + 0.5, z + 0.5);
                if (!inside(local, element.from, element.to)) continue;
                BlueprintPalette.Material material = element.materialAt(local);
                if (material != null) {
                    draft.put(x, y, z, material);
                    emittedThisElement = true;
                }
            }
            if (!emittedThisElement) {
                // Keep sub-block cuboids represented by one block instead of silently deleting them.
                double[] center = element.transform.point((element.from[0] + element.to[0]) / 2.0,
                        (element.from[1] + element.to[1]) / 2.0, (element.from[2] + element.to[2]) / 2.0);
                int x = safeFloor(center[0]), y = safeFloor(center[1]), z = safeFloor(center[2]);
                double[] local = element.transform.inversePoint(x + 0.5, y + 0.5, z + 0.5);
                BlueprintPalette.Material material = element.materialAt(inside(local, element.from, element.to)
                        ? local : new double[]{(element.from[0] + element.to[0]) / 2.0,
                        (element.from[1] + element.to[1]) / 2.0, (element.from[2] + element.to[2]) / 2.0});
                if (material != null) draft.put(x, y, z, material);
            }
        }
        draft.sourceCells = samples;
        return draft;
    }

    private static List<BufferedImage> readEmbeddedTextures(JsonObject root) {
        List<BufferedImage> result = new ArrayList<>();
        if (!root.has("textures") || !root.get("textures").isJsonArray()) return result;
        JsonArray textures = root.getAsJsonArray("textures");
        if (textures.size() > MAX_TEXTURE_COUNT)
            throw new IllegalArgumentException("Blockbench 工程的贴图数量超过 " + MAX_TEXTURE_COUNT + " 张");
        long[] remainingPixels = {MAX_TEXTURE_PIXELS};
        for (JsonElement element : textures) {
            if (!element.isJsonObject()) { result.add(null); continue; }
            JsonObject texture = element.getAsJsonObject();
            String source = stringValue(texture, "source", "");
            if (source.isBlank()) source = stringValue(texture, "data", "");
            if (!source.startsWith("data:") || !source.contains(",")) {
                // Preserve texture indexes so face references stay stable; an external path is never read.
                result.add(null);
                continue;
            }
            String encoded = source.substring(source.indexOf(',') + 1);
            if (encoded.length() > MAX_INPUT_BYTES * 2) throw new IllegalArgumentException("Blockbench 内嵌贴图数据过大");
            byte[] imageBytes;
            try { imageBytes = Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Blockbench 内嵌贴图数据无效", exception); }
            result.add(readBoundedImage(imageBytes, remainingPixels));
        }
        return result;
    }

    private static Map<String, Integer> readTextureIndexes(JsonObject root) {
        Map<String, Integer> result = new HashMap<>();
        if (!root.has("textures") || !root.get("textures").isJsonArray()) return result;
        JsonArray textures = root.getAsJsonArray("textures");
        for (int index = 0; index < textures.size(); index++) {
            if (!textures.get(index).isJsonObject()) continue;
            JsonObject texture = textures.get(index).getAsJsonObject();
            String uuid = stringValue(texture, "uuid", "");
            if (!uuid.isBlank() && result.putIfAbsent(uuid, index) != null)
                throw new IllegalArgumentException("Blockbench 贴图 UUID 重复");
        }
        return result;
    }

    private static BufferedImage readBoundedImage(byte[] bytes, long[] remainingPixels) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IllegalArgumentException("不支持此 Blockbench 内嵌图片格式");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("无法识别 Blockbench 内嵌图片格式");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long decodedPixels = (long) width * height;
                if (width < 1 || height < 1 || decodedPixels > remainingPixels[0])
                    throw new IllegalArgumentException("Blockbench 内嵌贴图累计解码像素超过 16,777,216 个");
                // Debit the shared budget before ImageReader allocates the decoded raster.
                remainingPixels[0] -= decodedPixels;
                BufferedImage image = reader.read(0);
                if (image == null) throw new IllegalArgumentException("无法解码 Blockbench 内嵌贴图");
                return image;
            } finally { reader.dispose(); }
        } catch (IOException exception) { throw new IllegalArgumentException("Blockbench 内嵌图片无效", exception); }
    }

    private static void collectOutliner(JsonElement node, Matrix parent, Map<String, JsonObject> elements,
                                        Map<String, JsonObject> groups,
                                        List<BufferedImage> textures, Map<String, Integer> textureIndexes, Set<String> visited,
                                        List<BbElement> output, int depth) {
        if (depth > MAX_JSON_DEPTH) throw new IllegalArgumentException("Blockbench outliner 嵌套层数超过上限");
        if (node == null || node.isJsonNull()) return;
        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
            String uuid = node.getAsString();
            JsonObject element = elements.get(uuid);
            if (element != null && elementVisible(element) && visited.add(uuid)) output.add(makeBbElement(element, parent, textures, textureIndexes));
            return;
        }
        if (!node.isJsonObject()) throw new IllegalArgumentException("Blockbench outliner 节点格式不受支持");
        JsonObject object = node.getAsJsonObject();
        if (object.has("visibility") && !object.get("visibility").getAsBoolean()) return;
        JsonArray children = object.has("children") && object.get("children").isJsonArray()
                ? object.getAsJsonArray("children") : null;
        if (children == null) {
            String uuid = stringValue(object, "uuid", "");
            JsonObject element = elements.get(uuid);
            if (element != null && elementVisible(element) && visited.add(uuid)) output.add(makeBbElement(element, parent, textures, textureIndexes));
            return;
        }
        JsonObject groupData = groups.getOrDefault(stringValue(object, "uuid", ""), object);
        if (groupData.has("visibility") && !groupData.get("visibility").getAsBoolean()) return;
        double[] origin = readJsonVectorD(groupData.has("origin") ? groupData.getAsJsonArray("origin") : null, new double[]{0, 0, 0}, "group origin");
        double[] rotation = readJsonVectorD(groupData.has("rotation") ? groupData.getAsJsonArray("rotation") : null, new double[]{0, 0, 0}, "group rotation");
        Matrix transformed = parent.multiply(Matrix.rotationAround(origin, rotation));
        for (JsonElement child : children) collectOutliner(child, transformed, elements, groups, textures, textureIndexes, visited, output, depth + 1);
    }

    private static BbElement makeBbElement(JsonObject element, Matrix parent, List<BufferedImage> textures,
                                           Map<String, Integer> textureIndexes) {
        double[] from = readJsonVectorD(element.getAsJsonArray("from"), null, "cuboid.from");
        double[] to = readJsonVectorD(element.getAsJsonArray("to"), null, "cuboid.to");
        double inflate = element.has("inflate") ? element.get("inflate").getAsDouble() : 0.0;
        if (!Double.isFinite(inflate) || Math.abs(inflate) > 16.0) throw new IllegalArgumentException("Blockbench 长方体 inflate 数值无效");
        for (int axis = 0; axis < 3; axis++) {
            from[axis] -= inflate;
            to[axis] += inflate;
            if (to[axis] < from[axis]) { double swap = to[axis]; to[axis] = from[axis]; from[axis] = swap; }
            if (to[axis] - from[axis] < EPSILON) throw new IllegalArgumentException("Blockbench 长方体尺寸无效");
        }
        double[] origin = readJsonVectorD(element.has("origin") ? element.getAsJsonArray("origin") : null,
                new double[]{0, 0, 0}, "cuboid.origin");
        double[] rotation = readJsonVectorD(element.has("rotation") ? element.getAsJsonArray("rotation") : null,
                new double[]{0, 0, 0}, "cuboid.rotation");
        Matrix transform = parent.multiply(Matrix.rotationAround(origin, rotation));
        Map<String, FaceTexture> faces = new HashMap<>();
        JsonObject rawFaces = element.has("faces") && element.get("faces").isJsonObject() ? element.getAsJsonObject("faces") : null;
        if (rawFaces != null) for (Map.Entry<String, JsonElement> faceEntry : rawFaces.entrySet()) {
            if (!faceEntry.getValue().isJsonObject()) continue;
            JsonObject face = faceEntry.getValue().getAsJsonObject();
            if (face.has("enabled") && !face.get("enabled").getAsBoolean()) continue;
            int textureIndex = textureIndex(face.get("texture"), textureIndexes);
            if (textureIndex < 0 || textureIndex >= textures.size() || textures.get(textureIndex) == null) continue;
            JsonArray uv = face.has("uv") ? face.getAsJsonArray("uv") : null;
            if (uv == null || uv.size() != 4) continue;
            double u0 = boundedUv(uv.get(0).getAsDouble()), v0 = boundedUv(uv.get(1).getAsDouble());
            double u1 = boundedUv(uv.get(2).getAsDouble()), v1 = boundedUv(uv.get(3).getAsDouble());
            int uvRotation = face.has("rotation") ? face.get("rotation").getAsInt() : 0;
            if (uvRotation != 0 && uvRotation != 90 && uvRotation != 180 && uvRotation != 270)
                throw new IllegalArgumentException("Blockbench 贴图旋转角度只能是 0、90、180 或 270 度");
            faces.put(faceEntry.getKey(), new FaceTexture(textures.get(textureIndex), u0, v0, u1, v1, uvRotation));
        }
        if (faces.isEmpty()) throw new IllegalArgumentException("Blockbench 长方体必须关联内嵌贴图；编辑器颜色编号不能作为方块材质颜色");
        return new BbElement(from, to, transform, faces, null);
    }

    private static int textureIndex(JsonElement reference, Map<String, Integer> textureIndexes) {
        if (reference == null || reference.isJsonNull()) return -1;
        if (!reference.isJsonPrimitive()) throw new IllegalArgumentException("Blockbench 贴图引用格式无效");
        if (reference.getAsJsonPrimitive().isBoolean()) {
            if (!reference.getAsBoolean()) return -1;
            throw new IllegalArgumentException("Blockbench 贴图引用格式无效");
        }
        if (reference.getAsJsonPrimitive().isNumber()) {
            double value = reference.getAsDouble();
            if (!Double.isFinite(value) || value != Math.rint(value) || value < -1 || value > 255)
                throw new IllegalArgumentException("Blockbench 贴图序号无效");
            return (int) value;
        }
        if (reference.getAsJsonPrimitive().isString()) {
            String key = reference.getAsString();
            if (key.startsWith("#")) key = key.substring(1);
            Integer index = textureIndexes.get(key);
            if (index != null) return index;
            try { return Integer.parseInt(key); }
            catch (NumberFormatException ignored) { throw new IllegalArgumentException("Blockbench 方块面引用了不存在的贴图"); }
        }
        throw new IllegalArgumentException("Blockbench 贴图引用格式无效");
    }

    private static boolean elementVisible(JsonObject element) {
        return !element.has("visibility") || element.get("visibility").getAsBoolean();
    }

    private static double boundedUv(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 1_000_000.0)
            throw new IllegalArgumentException("Blockbench 贴图 UV 坐标无效或超出范围");
        return value;
    }

    private static double[] readJsonVectorD(JsonArray array, double[] fallback, String label) {
        if (array == null) {
            if (fallback != null) return fallback.clone();
            throw new IllegalArgumentException(label + " 必须是包含三个数字的数组");
        }
        if (array.size() != 3) throw new IllegalArgumentException(label + " 必须是包含三个数字的数组");
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) {
            result[i] = array.get(i).getAsDouble();
            if (!Double.isFinite(result[i]) || Math.abs(result[i]) > 100_000)
                throw new IllegalArgumentException(label + " 含有无效坐标");
        }
        return result;
    }

    private static boolean inside(double[] point, double[] from, double[] to) {
        return point[0] >= from[0] - EPSILON && point[0] <= to[0] + EPSILON
                && point[1] >= from[1] - EPSILON && point[1] <= to[1] + EPSILON
                && point[2] >= from[2] - EPSILON && point[2] <= to[2] + EPSILON;
    }

    private static int safeFloor(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE + 1.0 || value > Integer.MAX_VALUE - 1.0)
            throw new IllegalArgumentException("Blockbench 变换后的坐标超出范围");
        return (int) Math.floor(value);
    }

    private static int safeCeil(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE + 1.0 || value > Integer.MAX_VALUE - 1.0)
            throw new IllegalArgumentException("Blockbench 变换后的坐标超出范围");
        return (int) Math.ceil(value);
    }

    private record FaceTexture(BufferedImage image, double u0, double v0, double u1, double v1, int rotation) { }

    private static final class BbElement {
        final double[] from, to;
        final Matrix transform;
        final Map<String, FaceTexture> faces;
        final BlueprintPalette.Material solidColor;
        BbElement(double[] from, double[] to, Matrix transform, Map<String, FaceTexture> faces, BlueprintPalette.Material solidColor) {
            this.from = from; this.to = to; this.transform = transform; this.faces = faces; this.solidColor = solidColor;
        }
        BbElement withParent(Matrix parent) { return new BbElement(from, to, parent.multiply(transform), faces, solidColor); }
        double[][] worldCorners() {
            double[][] result = new double[8][3]; int index = 0;
            for (double x : new double[]{from[0], to[0]}) for (double y : new double[]{from[1], to[1]}) for (double z : new double[]{from[2], to[2]})
                result[index++] = transform.point(x, y, z);
            return result;
        }
        BlueprintPalette.Material materialAt(double[] local) {
            if (faces.isEmpty()) return solidColor;
            String[] names = {"north", "south", "east", "west", "up", "down"};
            double[] distance = {Math.abs(local[2] - from[2]), Math.abs(local[2] - to[2]),
                    Math.abs(local[0] - to[0]), Math.abs(local[0] - from[0]),
                    Math.abs(local[1] - to[1]), Math.abs(local[1] - from[1])};
            int bestIndex = -1; double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < names.length; i++) if (faces.containsKey(names[i]) && distance[i] < best) { best = distance[i]; bestIndex = i; }
            if (bestIndex < 0) return solidColor;
            FaceTexture face = faces.get(names[bestIndex]);
            double tx, ty;
            double dx = Math.max(EPSILON, to[0] - from[0]);
            double dy = Math.max(EPSILON, to[1] - from[1]);
            double dz = Math.max(EPSILON, to[2] - from[2]);
            switch (names[bestIndex]) {
                case "north" -> { tx = (local[0] - from[0]) / dx; ty = (to[1] - local[1]) / dy; }
                case "south" -> { tx = (to[0] - local[0]) / dx; ty = (to[1] - local[1]) / dy; }
                case "east" -> { tx = (to[2] - local[2]) / dz; ty = (to[1] - local[1]) / dy; }
                case "west" -> { tx = (local[2] - from[2]) / dz; ty = (to[1] - local[1]) / dy; }
                case "up" -> { tx = (local[0] - from[0]) / dx; ty = (local[2] - from[2]) / dz; }
                default -> { tx = (local[0] - from[0]) / dx; ty = (to[2] - local[2]) / dz; }
            }
            double rotatedX = tx, rotatedY = ty;
            switch (face.rotation) {
                case 90 -> { rotatedX = ty; rotatedY = 1.0 - tx; }
                case 180 -> { rotatedX = 1.0 - tx; rotatedY = 1.0 - ty; }
                case 270 -> { rotatedX = 1.0 - ty; rotatedY = tx; }
                default -> { }
            }
            double u = face.u0 + (face.u1 - face.u0) * rotatedX;
            double v = face.v0 + (face.v1 - face.v0) * rotatedY;
            return BlueprintPalette.fromTexture(face.image, u, v);
        }
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        void include(double[][] points) {
            for (double[] point : points) {
                minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
                minZ = Math.min(minZ, point[2]); maxZ = Math.max(maxZ, point[2]);
            }
        }
        boolean valid() { return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                && minX < maxX && minY < maxY && minZ < maxZ; }
        double centerX() { return (minX + maxX) / 2.0; }
        double centerY() { return (minY + maxY) / 2.0; }
        double centerZ() { return (minZ + maxZ) / 2.0; }
    }

    private record RegionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static RegionBounds of(int[] position, int[] signedSize, int sx, int sy, int sz) {
            int[] size = {sx, sy, sz};
            int[] min = new int[3], max = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                long end = (long) position[axis] + (signedSize[axis] < 0 ? -(long) (size[axis] - 1) : size[axis] - 1L);
                long low = Math.min(position[axis], end), high = Math.max(position[axis], end);
                if (low < Integer.MIN_VALUE || high > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Litematica 区域坐标发生整数溢出");
                min[axis] = (int) low; max[axis] = (int) high;
            }
            return new RegionBounds(min[0], min[1], min[2], max[0], max[1], max[2]);
        }
        boolean intersects(RegionBounds other) {
            return minX <= other.maxX && maxX >= other.minX
                    && minY <= other.maxY && maxY >= other.minY
                    && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }

    private static final class Matrix {
        private final double[][] m;
        private Matrix(double[][] values) { m = values; }
        static Matrix identity() {
            return new Matrix(new double[][]{{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}});
        }
        static Matrix translation(double x, double y, double z) {
            Matrix result = identity(); result.m[0][3] = x; result.m[1][3] = y; result.m[2][3] = z; return result;
        }
        static Matrix scale(double value) {
            return new Matrix(new double[][]{{value,0,0,0},{0,value,0,0},{0,0,value,0},{0,0,0,1}});
        }
        static Matrix scaleAround(double x, double y, double z, double scale) {
            return translation(x, y, z).multiply(scale(scale)).multiply(translation(-x, -y, -z));
        }
        static Matrix rotationAround(double[] origin, double[] degrees) {
            double rx = Math.toRadians(degrees[0]), ry = Math.toRadians(degrees[1]), rz = Math.toRadians(degrees[2]);
            Matrix x = new Matrix(new double[][]{{1,0,0,0},{0,Math.cos(rx),-Math.sin(rx),0},{0,Math.sin(rx),Math.cos(rx),0},{0,0,0,1}});
            Matrix y = new Matrix(new double[][]{{Math.cos(ry),0,Math.sin(ry),0},{0,1,0,0},{-Math.sin(ry),0,Math.cos(ry),0},{0,0,0,1}});
            Matrix z = new Matrix(new double[][]{{Math.cos(rz),-Math.sin(rz),0,0},{Math.sin(rz),Math.cos(rz),0,0},{0,0,1,0},{0,0,0,1}});
            return translation(origin[0], origin[1], origin[2]).multiply(z).multiply(y).multiply(x)
                    .multiply(translation(-origin[0], -origin[1], -origin[2]));
        }
        Matrix multiply(Matrix other) {
            double[][] out = new double[4][4];
            for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++)
                for (int k = 0; k < 4; k++) out[row][col] += m[row][k] * other.m[k][col];
            return new Matrix(out);
        }
        double[] point(double x, double y, double z) {
            return new double[]{m[0][0]*x+m[0][1]*y+m[0][2]*z+m[0][3],
                    m[1][0]*x+m[1][1]*y+m[1][2]*z+m[1][3],m[2][0]*x+m[2][1]*y+m[2][2]*z+m[2][3]};
        }
        double[] inversePoint(double x, double y, double z) {
            double px=x-m[0][3], py=y-m[1][3], pz=z-m[2][3];
            double a=m[0][0],b=m[0][1],c=m[0][2],d=m[1][0],e=m[1][1],f=m[1][2],g=m[2][0],h=m[2][1],i=m[2][2];
            double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
            if (!Double.isFinite(det) || Math.abs(det) < 1.0E-12) throw new IllegalArgumentException("Blockbench 变换矩阵无法求逆");
            double inv=1.0/det;
            return new double[]{((e*i-f*h)*px+(c*h-b*i)*py+(b*f-c*e)*pz)*inv,
                    ((f*g-d*i)*px+(a*i-c*g)*py+(c*d-a*f)*pz)*inv,
                    ((d*h-e*g)*px+(b*g-a*h)*py+(a*e-b*d)*pz)*inv};
        }
    }

    private static int packedValue(long[] packed, int index, int bits) {
        long bitIndex = (long) index * bits;
        int word = (int) (bitIndex >>> 6);
        int offset = (int) (bitIndex & 63L);
        long mask = (1L << bits) - 1L;
        long value = packed[word] >>> offset;
        if (offset + bits > 64) value |= packed[word + 1] << (64 - offset);
        return (int) (value & mask);
    }

    private static RobotBuildPlan finish(String filename, String title, Draft draft) {
        draft.removePairedDoorUpperHalves();
        if (draft.blocks.isEmpty()) throw new IllegalArgumentException("蓝图没有可导入的不透明方块");
        if (draft.blocks.size() > MAX_FINAL_PARTS) throw new IllegalArgumentException("蓝图最终方块数超过 " + MAX_FINAL_PARTS + " 个");
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Coord coordinate : draft.blocks.keySet()) {
            minX = Math.min(minX, coordinate.x); maxX = Math.max(maxX, coordinate.x);
            minY = Math.min(minY, coordinate.y); maxY = Math.max(maxY, coordinate.y);
            minZ = Math.min(minZ, coordinate.z); maxZ = Math.max(maxZ, coordinate.z);
        }
        if (maxX - minX + 1 > 97 || maxZ - minZ + 1 > 97 || maxY - minY + 1 > 65)
            throw new IllegalArgumentException("蓝图尺寸不能超过 97×65×97 格");
        int centerX = (int) (((long) minX + maxX) >> 1), centerZ = (int) (((long) minZ + maxZ) >> 1);
        List<RobotBuildPlan.Part> parts = new ArrayList<>(draft.blocks.size());
        boolean normalizedDerivedStates = false;
        boolean hasSourceLiquid = false;
        for (Map.Entry<Coord, BlueprintPalette.Material> entry : draft.blocks.entrySet()) {
            Coord coordinate = entry.getKey();
            BlockPos offset = new BlockPos(coordinate.x - centerX, coordinate.y - minY, coordinate.z - centerZ);
            BlueprintPalette.Material original = entry.getValue();
            BlueprintPalette.Material material = BlueprintPalette.normalize(original);
            normalizedDerivedStates |= !material.equals(original);
            hasSourceLiquid |= BlueprintPalette.isSourceLiquid(material);
            BlueprintPalette.resolve(material);
            parts.add(new RobotBuildPlan.Part(offset, material.blockId(), material.properties(), "main"));
        }
        if (parts.size() > RobotBuildPlan.MAX_PARTS)
            throw new IllegalArgumentException("蓝图方块数超过当前施工计划上限 " + RobotBuildPlan.MAX_PARTS + " 个");
        parts.sort(Comparator.comparingInt((RobotBuildPlan.Part part) -> part.offset().getY())
                .thenComparingInt(part -> part.offset().getZ()).thenComparingInt(part -> part.offset().getX()));
        if (normalizedDerivedStates)
            draft.notes.add("已移除阶梯转角、栅栏/墙/铁栏杆连接、树叶距离及红石运行状态；相关连接与阶梯转角由原版邻居更新生成。");
        if (hasSourceLiquid)
            draft.notes.add("施工仅支持 level=0 的水源方块；放置后的流动遵循 Minecraft 原版规则。");
        CompoundTag metadata = new CompoundTag();
        metadata.putString("SourceFormat", extension(filename));
        metadata.putString("SourceFile", filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1));
        metadata.putInt("SourceCells", (int) Math.min(Integer.MAX_VALUE, draft.sourceCells));
        metadata.putInt("ImportedParts", parts.size());
        RobotBuildPlan plan = new RobotBuildPlan("house", boundedTitle(draft.title == null ? title : draft.title),
                parts, new ArrayList<>(draft.notes), metadata);
        // Leave room for task ownership, team ids and progress below the existing doll recovery bound.
        if (plan.save().toString().length() > 1_750_000)
            throw new IllegalArgumentException("图纸状态数据过大，请分成较小图纸；施工计划序列化上限为1,750,000字符，以保证收回机器人后能够恢复任务。");
        return plan;
    }

    private static CompoundTag readNbt(byte[] bytes) {
        try {
            InputStream raw = new ByteArrayInputStream(bytes);
            InputStream decoded = bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B
                    ? new GZIPInputStream(raw) : raw;
            try (InputStream bounded = new BoundedInputStream(decoded, MAX_NBT_BYTES);
                 DataInputStream input = new DataInputStream(bounded)) {
                CompoundTag root = NbtIo.read(input, new NbtAccounter(MAX_NBT_BYTES));
                if (root == null) throw new IllegalArgumentException("NBT 根节点为空");
                return root;
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("NBT 文件无效，或解压后超过大小上限", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("蓝图 JSON 必须使用有效的 UTF-8 编码", exception);
        }
    }

    private static void checkJsonDepth(String json) {
        int depth = 0; boolean inString = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
            } else if (current == '"') inString = true;
            else if (current == '{' || current == '[') {
                if (++depth > MAX_JSON_DEPTH) throw new IllegalArgumentException("紧凑蓝图 JSON 嵌套层数超过上限");
            } else if (current == '}' || current == ']') depth--;
        }
    }

    private static int[] readJsonVector(JsonArray array, String field) {
        if (array == null || array.size() != 3) throw new IllegalArgumentException(field + " 必须是整数 [x,y,z] 数组");
        int[] result = new int[3];
        try {
            for (int axis = 0; axis < 3; axis++) {
                JsonElement element = array.get(axis);
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException(field + " 必须包含整数");
                double value = element.getAsDouble();
                if (!Double.isFinite(value) || value != Math.rint(value) || value < -4096 || value > 4096)
                    throw new IllegalArgumentException(field + " 坐标必须是 -4096 到 4096 范围内的整数");
                result[axis] = (int) value;
            }
            return result;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException(field + " 必须包含整数", exception);
        }
    }

    private static int[] readVector(Tag raw, String field) {
        if (raw instanceof CompoundTag compound) {
            return boundedRegionPosition(new int[]{compound.getInt("X"), compound.getInt("Y"), compound.getInt("Z")}, field);
        }
        if (raw instanceof net.minecraft.nbt.IntArrayTag array && array.getAsIntArray().length == 3) return boundedRegionPosition(array.getAsIntArray(), field);
        if (raw instanceof ListTag list && list.size() == 3 && list.getElementType() == Tag.TAG_INT)
            return boundedRegionPosition(new int[]{list.getInt(0), list.getInt(1), list.getInt(2)}, field);
        throw new IllegalArgumentException(field + " 必须是 NBT compound、整数数组或三个整数构成的列表");
    }

    private static int[] boundedRegionPosition(int[] value, String field) {
        for (int coordinate : value) if (Math.abs((long) coordinate) > 30_000_000L)
            throw new IllegalArgumentException(field + " 超出支持的世界坐标范围");
        return value;
    }

    private static int[] readIntVector(CompoundTag tag, String key, int[] fallback) {
        Tag raw = tag.get(key);
        if (raw == null && fallback != null) return fallback;
        if (raw instanceof net.minecraft.nbt.IntArrayTag array && array.getAsIntArray().length == 3) return array.getAsIntArray();
        if (raw instanceof ListTag list && list.size() == 3 && list.getElementType() == Tag.TAG_INT)
            return new int[]{list.getInt(0), list.getInt(1), list.getInt(2)};
        throw new IllegalArgumentException("NBT " + key + " 必须包含三个整数");
    }

    private static long checkedVolume(int x, int y, int z, String source) {
        if (x < 1 || y < 1 || z < 1 || x > 4096 || y > 4096 || z > 4096)
            throw new IllegalArgumentException(source + " 尺寸无效或过大");
        long volume = (long) x * y * z;
        if (volume > MAX_SOURCE_CELLS) throw new IllegalArgumentException(source + " 超过 1,000,000 个源体素的上限");
        return volume;
    }

    private static int absDimension(int dimension) {
        if (dimension == 0 || dimension == Integer.MIN_VALUE) throw new IllegalArgumentException("区域尺寸为零或发生溢出");
        return Math.abs(dimension);
    }

    private static long boxVolume(int[] from, int[] to) {
        long x = Math.abs((long) to[0] - from[0]) + 1L;
        long y = Math.abs((long) to[1] - from[1]) + 1L;
        long z = Math.abs((long) to[2] - from[2]) + 1L;
        long volume;
        try { volume = Math.multiplyExact(Math.multiplyExact(x, y), z); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("操作范围过大", exception); }
        if (volume > MAX_OPERATION_SAMPLES) throw new IllegalArgumentException("单个操作的采样位置超过 1,000,000 个");
        return volume;
    }

    private static long boundedVoxelVolume(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, String message) {
        long x = (long) maxX - minX + 1L, y = (long) maxY - minY + 1L, z = (long) maxZ - minZ + 1L;
        if (x < 1 || y < 1 || z < 1 || x > MAX_OPERATION_SAMPLES || y > MAX_OPERATION_SAMPLES || z > MAX_OPERATION_SAMPLES)
            throw new IllegalArgumentException(message);
        try {
            long volume = Math.multiplyExact(Math.multiplyExact(x, y), z);
            if (volume > MAX_OPERATION_SAMPLES) throw new IllegalArgumentException(message);
            return volume;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static String paletteReference(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("缺少 palette 引用");
        return element.getAsString();
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String titleFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return "建筑蓝图";
        String base = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.'); if (dot > 0) base = base.substring(0, dot);
        return boundedTitle(base);
    }

    private static String boundedTitle(String title) {
        String value = title == null || title.isBlank() ? "建筑蓝图" : title.trim();
        return value.length() > 96 ? value.substring(0, 96) : value;
    }

    private static final class VarInts {
        private final byte[] data;
        private int offset;
        VarInts(byte[] data) { this.data = data; }
        int next() {
            int value = 0, shift = 0;
            while (shift < 35) {
                if (offset >= data.length) throw new IllegalArgumentException("Sponge BlockData 在解码完所有方块前已结束");
                int next = data[offset++] & 0xFF;
                if (shift == 28 && (next & 0xF0) != 0) throw new IllegalArgumentException("Sponge BlockData 中的 VarInt 超出 32 位范围");
                value |= (next & 0x7F) << shift;
                if ((next & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IllegalArgumentException("Sponge BlockData 中的 VarInt 无效");
        }
        boolean hasRemaining() { return offset < data.length; }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long read;
        BoundedInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++read > limit) throw new IOException("NBT 解压数据超过大小上限");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (read >= limit) {
                int extra = super.read();
                if (extra < 0) return -1;
                throw new IOException("NBT 解压数据超过大小上限");
            }
            int allowed = (int) Math.min(length, limit - read);
            int count = super.read(bytes, offset, allowed);
            if (count > 0) read += count;
            return count;
        }
    }

    private record Coord(int x, int y, int z) { }
    private static final class Draft {
        private final LinkedHashMap<Coord, BlueprintPalette.Material> blocks = new LinkedHashMap<>();
        private final LinkedHashSet<String> notes = new LinkedHashSet<>();
        private final Set<BlueprintPalette.Material> validatedMaterials = new HashSet<>();
        private long sourceCells;
        private String title;
        private long operationSamples;
        private boolean allowTransientExpansion;

        void put(int x, int y, int z, BlueprintPalette.Material material) {
            if (material == null || material.isAir()) return;
            if (validatedMaterials.add(material)) BlueprintPalette.resolve(material);
            blocks.put(new Coord(x, y, z), material);
            int limit = allowTransientExpansion ? MAX_OPERATION_SAMPLES : MAX_FINAL_PARTS;
            if (blocks.size() > limit) throw new IllegalArgumentException("蓝图中间方块数量超过 " + limit + " 个上限");
        }

        void clearBox(int[] from, int[] to) {
            int minX = Math.min(from[0], to[0]), maxX = Math.max(from[0], to[0]);
            int minY = Math.min(from[1], to[1]), maxY = Math.max(from[1], to[1]);
            int minZ = Math.min(from[2], to[2]), maxZ = Math.max(from[2], to[2]);
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++)
                blocks.remove(new Coord(x, y, z));
        }

        void expandShape(String type, int[] from, int[] to, BlueprintPalette.Material material, boolean hollow) {
            if (validatedMaterials.add(material)) BlueprintPalette.resolve(material);
            int minX = Math.min(from[0], to[0]), maxX = Math.max(from[0], to[0]);
            int minY = Math.min(from[1], to[1]), maxY = Math.max(from[1], to[1]);
            int minZ = Math.min(from[2], to[2]), maxZ = Math.max(from[2], to[2]);
            double cx = (minX + (double) maxX + 1.0) / 2.0;
            double cy = (minY + (double) maxY + 1.0) / 2.0;
            double cz = (minZ + (double) maxZ + 1.0) / 2.0;
            double rx = Math.max(0.5, (maxX - minX + 1.0) / 2.0);
            double ry = Math.max(0.5, (maxY - minY + 1.0) / 2.0);
            double rz = Math.max(0.5, (maxZ - minZ + 1.0) / 2.0);
            long samples = 0;
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                if (++samples > MAX_OPERATION_SAMPLES) throw new IllegalArgumentException("操作采样位置超过 1,000,000 个");
                boolean surface = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                if (type.equals("ellipsoid")) {
                    double dx = (x + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry, dz = (z + 0.5 - cz) / rz;
                    double radial = dx * dx + dy * dy + dz * dz;
                    if (hollow ? radial > 1.0 || radial < 0.66 : radial > 1.0) continue;
                } else if (hollow && !surface) continue;
                put(x, y, z, material);
            }
            operationSamples += samples;
            if (operationSamples > MAX_OPERATION_SAMPLES) throw new IllegalArgumentException("紧凑蓝图操作累计采样位置超过 1,000,000 个");
        }

        void removePairedDoorUpperHalves() {
            List<Coord> upperHalves = new ArrayList<>();
            for (Map.Entry<Coord, BlueprintPalette.Material> entry : blocks.entrySet()) {
                BlockState state = BlueprintPalette.resolve(entry.getValue());
                if (state.getBlock() instanceof BedBlock) throw new IllegalArgumentException("机器人建筑蓝图暂不支持床");
                if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)
                        && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) upperHalves.add(entry.getKey());
            }
            for (Coord upper : upperHalves) {
                Coord lower = new Coord(upper.x, upper.y - 1, upper.z);
                BlueprintPalette.Material upperMaterial = blocks.get(upper), lowerMaterial = blocks.get(lower);
                if (lowerMaterial == null || !sameDoorExceptHalf(upperMaterial, lowerMaterial))
                    throw new IllegalArgumentException("门的上半部分缺少相配的下半部分");
                blocks.remove(upper);
            }
        }

        private boolean sameDoorExceptHalf(BlueprintPalette.Material first, BlueprintPalette.Material second) {
            if (!first.blockId().equals(second.blockId())) return false;
            Map<String, String> a = new LinkedHashMap<>(first.properties()), b = new LinkedHashMap<>(second.properties());
            a.remove("half"); b.remove("half");
            return a.equals(b) && "lower".equalsIgnoreCase(second.properties().get("half"));
        }
    }
}
