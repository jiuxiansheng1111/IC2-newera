package com.ic2newera.robot.building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a small, bounded vanilla house plan from explicit Chinese parameters. */
public final class RobotHousePlanner {
    private static final Pattern SIZE_CROSS = Pattern.compile("(?<!\\d)(\\d+)\\s*[×xX]\\s*(\\d+)(?!\\d)");
    private static final Pattern SIZE_WORDS = Pattern.compile("长\\s*(\\d+)\\s*(?:格)?\\s*(?:宽|深)\\s*(\\d+)");
    private static final Pattern HEIGHT = Pattern.compile("高\\s*(\\d+)");
    private static final Pattern NON_BLOCK_UNIT = Pattern.compile("\\d+\\s*(?:米|m|英尺|尺)");
    private static final Pattern THREE_SIZES = Pattern.compile("\\d+\\s*[×xX]\\s*\\d+\\s*[×xX]\\s*\\d+");
    private static final Pattern THREE_WORD_SIZES = Pattern.compile("长\\s*\\d+.*?(?:宽|深)\\s*\\d+.*?(?:宽|深|长)\\s*\\d+");
    private static final Pattern UNSUPPORTED_MATERIAL = Pattern.compile(
            "红色|蓝色|黑色|绿色|黄色|紫色|粉色|灰色|深色|浅色|云杉|白桦|桦木|樱花木|丛林木|深色橡木|金合欢木|红树木|竹制|竹子");
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "(?:二|两|三|四|五|六|七|八|九|[2-9]|[1-9]\\d*)层|多层|大城堡|城堡|地下室|地窖|地下|阁楼|二楼|楼梯|塔楼|城墙|护城河|庭院|院子|车库|阳台|露台|烟囱|烟筒|水池|泳池|自动门|红石|指令|刷怪|传送门|电梯|围墙|多栋|隔间|独立房间|卧室|卫生间|浴室|厨房|客厅|储藏室|复杂|环形|圆形|八角形|不要(?:窗|门|家具|照明|工作台|箱子|熔炉|屋顶|地板|柱|灯)|不需要(?:窗|门|家具|照明|工作台|箱子|熔炉|屋顶|地板|柱|灯)");

    private RobotHousePlanner() {}

    /**
     * Plans a one-storey vanilla house. Supported choices are deliberately finite so
     * the returned blocks and states can be checked by the server before execution.
     */
    public static RobotBuildPlan plan(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("请描述房屋类型或尺寸，例如：建一间9×7的木屋，高3格。");
        }
        if (NON_BLOCK_UNIT.matcher(description).find()) {
            throw new IllegalArgumentException("尺寸只接受方块格数，请不要使用米、英尺等现实单位。");
        }
        Matcher unsupported = UNSUPPORTED.matcher(description);
        if (unsupported.find()) {
            throw new IllegalArgumentException("暂不支持“" + unsupported.group() + "”这类多层或复杂建筑要求。");
        }

        Dimensions dimensions = parseDimensions(description);
        Style style = parseStyle(description);
        Roof roof = parseRoof(description);

        List<RobotBuildPlan.Part> parts = new ArrayList<>();
        Set<BlockPos> occupied = new HashSet<>();
        addFloor(parts, occupied, dimensions.width, dimensions.depth, style.floor);
        addRoofAccess(parts, occupied, dimensions, style.floor);
        addWallsAndWindows(parts, occupied, dimensions, style);
        if (!roof.flat) addGableSupports(parts, occupied, dimensions, style.wall);
        if (roof.flat) {
            addFlatRoof(parts, occupied, dimensions, style.roofBlock);
        } else {
            addLowSlopeRoof(parts, occupied, dimensions, style.roofBlock);
        }
        addFurnitureAndLights(parts, occupied, dimensions, style);
        addDoor(parts, occupied, dimensions, style.door);

        if (parts.size() > RobotBuildPlan.MAX_PARTS) {
            throw new IllegalArgumentException("房屋方案超过机器人单次施工上限。");
        }

        CompoundTag metadata = new CompoundTag();
        metadata.putInt("width", dimensions.width);
        metadata.putInt("depth", dimensions.depth);
        metadata.putInt("height", dimensions.height);

        List<String> notes = new ArrayList<>();
        notes.add("占地：" + dimensions.width + "×" + dimensions.depth + "格，墙高" + dimensions.height + "格；施工起点正前方为南侧入口。");
        notes.add("西侧附带一格宽、向外延伸" + dimensions.height + "格的屋顶台阶；含台阶总占地"
                + (dimensions.width + dimensions.height) + "×" + dimensions.depth + "格。台阶计入材料，施工后保留作检修通道。");
        notes.add("款式：" + style.name + "；屋顶：" + roof.label + "；单层结构。");
        if (dimensions.defaultedSize) notes.add("未指定长宽，采用默认尺寸9×7格。");
        if (dimensions.defaultedHeight) notes.add("未指定墙高，采用默认3格。");
        if (style.defaulted) notes.add("未指定房屋材质，采用木屋款式。");
        if (roof.defaulted) notes.add("未指定屋顶，采用完整平顶。");
        notes.add("地板在y=0铺设，要求目标位置已有可承托地面；规划不会清场或破坏原方块。");
        notes.add("门仅列出下半格，由原版门放置逻辑生成上半格；入口位于南侧正中。");
        notes.add("包含玻璃窗、照明、工作台、箱子和熔炉。");
        return new RobotBuildPlan("house", style.name + "（" + dimensions.width + "×" + dimensions.depth + "）", parts, notes, metadata);
    }

    private static Dimensions parseDimensions(String description) {
        if (THREE_SIZES.matcher(description).find() || THREE_WORD_SIZES.matcher(description).find()) {
            throw new IllegalArgumentException("房屋只接受长和宽两个尺寸，不支持第三个尺寸轴。");
        }
        Matcher cross = SIZE_CROSS.matcher(description);
        Matcher words = SIZE_WORDS.matcher(description);
        Integer width = null;
        Integer depth = null;
        int sizeMatches = 0;
        while (cross.find()) {
            sizeMatches++;
            width = parseNumber(cross.group(1), "长宽");
            depth = parseNumber(cross.group(2), "长宽");
        }
        while (words.find()) {
            sizeMatches++;
            width = parseNumber(words.group(1), "长");
            depth = parseNumber(words.group(2), "宽");
        }
        if (sizeMatches > 1) throw new IllegalArgumentException("描述里有多组长宽尺寸，请只指定一组。");

        boolean hasDimensionCue = description.matches("(?s).*(?:长|宽|深|尺寸|占地).*\\d+.*")
                || description.matches("(?s).*\\d+\\s*[*×xX]\\s*\\d+.*");
        if (hasDimensionCue && sizeMatches == 0) {
            throw new IllegalArgumentException("长宽尺寸需要成对说明，例如“9×7”或“长9宽7”。");
        }
        boolean defaultedSize = sizeMatches == 0;
        if (defaultedSize) {
            width = 9;
            depth = 7;
        }

        Matcher heightMatcher = HEIGHT.matcher(description);
        Integer height = null;
        int heightMatches = 0;
        while (heightMatcher.find()) {
            heightMatches++;
            height = parseNumber(heightMatcher.group(1), "高");
        }
        if (heightMatches > 1) throw new IllegalArgumentException("描述里有多个墙高，请只指定一个高度。");
        boolean defaultedHeight = height == null;
        if (defaultedHeight) height = 3;

        checkRange("长", width, 5, 13);
        checkRange("宽", depth, 5, 13);
        checkRange("墙高", height, 3, 4);
        return new Dimensions(width, depth, height, defaultedSize, defaultedHeight);
    }

    private static Style parseStyle(String description) {
        boolean modern = description.contains("白色现代混凝土") || description.contains("现代混凝土")
                || description.contains("白色混凝土") || description.contains("现代风")
                || description.contains("现代房屋") || description.contains("现代房子");
        boolean stone = description.contains("石屋") || description.contains("石头屋") || description.contains("石头房")
                || description.contains("石制房");
        boolean brick = description.contains("砖屋") || description.contains("砖房") || description.contains("砖块");
        boolean wood = description.contains("木屋") || description.contains("木房") || description.contains("木制房");
        int choices = (modern ? 1 : 0) + (stone ? 1 : 0) + (brick ? 1 : 0) + (wood ? 1 : 0);
        if (choices > 1) throw new IllegalArgumentException("房屋材质有冲突，请只选择木屋、石屋、砖屋或白色现代混凝土中的一种。");
        Matcher unsupportedMaterial = UNSUPPORTED_MATERIAL.matcher(description);
        if (unsupportedMaterial.find()) {
            throw new IllegalArgumentException("暂不支持“" + unsupportedMaterial.group() + "”材质，请改用木屋、石屋、砖屋或白色现代混凝土。");
        }
        if (description.contains("白色") && !modern) {
            throw new IllegalArgumentException("白色方案目前只支持现代混凝土房屋，请明确写“白色现代混凝土”。");
        }
        if (modern) return new Style("白色现代混凝土", "minecraft:white_concrete", "minecraft:light_gray_concrete",
                "minecraft:quartz_block", "minecraft:quartz_block", "minecraft:birch_door", false);
        if (stone) return new Style("石屋", "minecraft:stone_bricks", "minecraft:stone", "minecraft:stone_bricks",
                "minecraft:stone_bricks", "minecraft:oak_door", false);
        if (brick) return new Style("砖屋", "minecraft:bricks", "minecraft:bricks", "minecraft:bricks",
                "minecraft:bricks", "minecraft:oak_door", false);
        if (wood) return new Style("木屋", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_log",
                "minecraft:oak_planks", "minecraft:oak_door", false);
        return new Style("木屋", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_log",
                "minecraft:oak_planks", "minecraft:oak_door", true);
    }

    private static Roof parseRoof(String description) {
        boolean flat = description.contains("平顶") || description.contains("平屋顶");
        boolean slope = description.contains("斜顶") || description.contains("斜屋顶")
                || description.contains("坡顶") || description.contains("低斜")
                || description.contains("尖顶") || description.contains("尖屋顶");
        if (flat && slope) throw new IllegalArgumentException("屋顶要求有冲突，请选择平顶或低斜屋顶。");
        if (slope) return new Roof(false, "低斜屋顶", false);
        return new Roof(true, "平顶", !flat);
    }

    private static void addFloor(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                 int width, int depth, String block) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) add(parts, occupied, x, 0, z, block, Map.of(), "floor");
        }
    }

    private static void addWallsAndWindows(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                           Dimensions d, Style style) {
        int mid = d.width / 2;
        for (int y = 1; y <= d.height; y++) {
            for (int x = 0; x < d.width; x++) {
                for (int z = 0; z < d.depth; z++) {
                    boolean boundary = x == 0 || x == d.width - 1 || z == 0 || z == d.depth - 1;
                    if (!boundary) continue;
                    if (z == d.depth - 1 && x == mid && y <= 2) continue;
                    boolean pillar = (x == 0 || x == d.width - 1) && (z == 0 || z == d.depth - 1);
                    boolean window = y == 2 && isWindowPosition(x, z, d);
                    if (window) {
                        add(parts, occupied, x, y, z, "minecraft:glass_pane", Map.of(), "walls_windows");
                    } else {
                        String block = pillar ? style.pillar : style.wall;
                        add(parts, occupied, x, y, z, block, Map.of(), "walls_windows");
                    }
                }
            }
        }
    }

    /** A compact, supported access flight lets the worker reach upper courses using normal walking. */
    private static void addRoofAccess(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                      Dimensions d, String block) {
        int z = d.depth / 2;
        // The main floor is itself one block above the supporting ground. Add a
        // low side landing so the first solid step is not a two-block jump.
        add(parts, occupied, -d.height, 0, z + 1, block, Map.of(), "roof_access");
        for (int y = 0; y <= d.height; y++) {
            for (int step = Math.max(1, y); step <= d.height; step++) {
                add(parts, occupied, step - d.height - 1, y, z, block, Map.of(), "roof_access");
            }
        }
    }

    private static void addGableSupports(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                         Dimensions d, String wallBlock) {
        // Front and back gable courses support the raised row's first placement
        // point at (x=1, y=height+2, z=0) and close the gaps beneath the roof.
        for (int x = 1; x < d.width - 1; x++) {
            add(parts, occupied, x, d.height + 1, 0, wallBlock, Map.of(), "walls_windows");
            add(parts, occupied, x, d.height + 1, d.depth - 1, wallBlock, Map.of(), "walls_windows");
        }
    }

    private static boolean isWindowPosition(int x, int z, Dimensions d) {
        if (z == 0 || z == d.depth - 1) {
            return x > 0 && x < d.width - 1 && (x == 1 || x == d.width - 2);
        }
        if (x == 0 || x == d.width - 1) {
            return z > 0 && z < d.depth - 1 && (z == 1 || z == d.depth - 2);
        }
        return false;
    }

    private static void addFlatRoof(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                    Dimensions d, String block) {
        int y = d.height + 1;
        for (int x = 0; x < d.width; x++) {
            for (int z = 0; z < d.depth; z++) add(parts, occupied, x, y, z, block, Map.of(), "roof");
        }
    }

    /** A sealed, one-block-pitch stepped gable; every roof column has a full block. */
    private static void addLowSlopeRoof(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                        Dimensions d, String block) {
        // The gable courses support the first high block. Extend the raised row
        // from the front to the back before placing either low eave.
        for (int z = 0; z < d.depth; z++) {
            for (int x = 1; x < d.width - 1; x++) {
                add(parts, occupied, x, d.height + 2, z, block, Map.of(), "roof");
            }
        }
        for (int z = 0; z < d.depth; z++) {
            add(parts, occupied, 0, d.height + 1, z, block, Map.of(), "roof");
            add(parts, occupied, d.width - 1, d.height + 1, z, block, Map.of(), "roof");
        }
    }

    private static void addFurnitureAndLights(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                              Dimensions d, Style style) {
        int farZ = d.depth - 2;
        int farX = d.width - 2;
        add(parts, occupied, 1, 1, 1, "minecraft:crafting_table", Map.of(), "furniture_lighting");
        add(parts, occupied, farX, 1, 1, "minecraft:chest", Map.of(), "furniture_lighting");
        add(parts, occupied, 1, 1, farZ, "minecraft:furnace", Map.of("facing", "north"), "furniture_lighting");
        int mid = d.width / 2;
        int lightZ = d.depth / 2;
        add(parts, occupied, mid, 1, lightZ, "minecraft:torch", Map.of(), "furniture_lighting");
        if (style.name.startsWith("白色现代")) {
            add(parts, occupied, farX, 1, farZ, "minecraft:lantern", Map.of("hanging", "false"), "furniture_lighting");
        }
    }

    private static void addDoor(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied,
                                Dimensions d, String door) {
        add(parts, occupied, d.width / 2, 1, d.depth - 1, door,
                Map.of("facing", "south", "half", "lower"),
                "door_last");
    }

    private static void add(List<RobotBuildPlan.Part> parts, Set<BlockPos> occupied, int x, int y, int z,
                            String block, Map<String, String> properties, String phase) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!occupied.add(pos)) throw new IllegalStateException("房屋内部布局出现重复坐标：" + x + "," + y + "," + z);
        parts.add(new RobotBuildPlan.Part(pos, block, properties, phase));
    }

    private static int parseNumber(String number, String label) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "尺寸不是有效整数。", exception);
        }
    }

    private static void checkRange(String label, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + "必须在" + min + "到" + max + "格之间；收到" + value + "格。");
        }
    }

    private record Dimensions(int width, int depth, int height, boolean defaultedSize, boolean defaultedHeight) {}
    private record Style(String name, String wall, String floor, String pillar, String roofBlock, String door,
                         boolean defaulted) {}
    private record Roof(boolean flat, String label, boolean defaulted) {}
}
