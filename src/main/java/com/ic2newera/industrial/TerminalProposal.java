package com.ic2newera.industrial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A narrow schema shared by the preview and server validator. It cannot execute arbitrary model text. */
public record TerminalProposal(BlockPos terminal, Direction face, int mode, int reserve, int limit,
                               List<ResourceLocation> filters, boolean aiManaged, String json) {
    public static TerminalProposal parse(String json) {
        if (json == null || json.length() > 2048) throw new IllegalArgumentException("提案过长");
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (!object.keySet().equals(Set.of("terminal", "face", "mode", "reserve", "limit", "filters", "ai_managed")))
            throw new IllegalArgumentException("只接受终端面规则提案");
        var coordinates = object.getAsJsonArray("terminal");
        if (coordinates.size() != 3) throw new IllegalArgumentException("坐标无效");
        int[] xyz = new int[3];
        for (int i = 0; i < 3; i++) xyz[i] = integer(coordinates.get(i), -30_000_000, 30_000_000);
        if (xyz[1] < -2048 || xyz[1] > 2048) throw new IllegalArgumentException("高度无效");
        Direction face = Direction.valueOf(object.get("face").getAsString());
        int mode = switch (object.get("mode").getAsString()) {
            case "CLOSED" -> 0; case "INPUT" -> 1; case "OUTPUT" -> 2; case "BOTH" -> 3;
            default -> throw new IllegalArgumentException("模式无效");
        };
        int reserve = integer(object.get("reserve"), 0, 9216), limit = integer(object.get("limit"), 1, 64);
        var entries = object.getAsJsonArray("filters");
        if (entries.size() > 9) throw new IllegalArgumentException("白名单最多9项");
        List<ResourceLocation> filters = new ArrayList<>();
        for (var entry : entries) {
            String name = entry.getAsString();
            ResourceLocation id = name.length() <= 256 ? ResourceLocation.tryParse(name) : null;
            if (id == null || id.toString().equals("minecraft:air")) throw new IllegalArgumentException("物品ID无效");
            if (!filters.contains(id)) filters.add(id);
        }
        if (!object.get("ai_managed").isJsonPrimitive() || !object.getAsJsonPrimitive("ai_managed").isBoolean())
            throw new IllegalArgumentException("托管开关无效");
        return new TerminalProposal(new BlockPos(xyz[0], xyz[1], xyz[2]), face, mode, reserve, limit,
                List.copyOf(filters), object.get("ai_managed").getAsBoolean(), object.toString());
    }
    private static int integer(com.google.gson.JsonElement value, int min, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("数量必须为整数");
        int number = value.getAsInt();
        if (value.getAsDouble() != number || number < min || number > max) throw new IllegalArgumentException("数量超出范围");
        return number;
    }
    public String description() {
        String side = switch (face) { case DOWN -> "下方"; case UP -> "上方"; case NORTH -> "北面"; case SOUTH -> "南面"; case WEST -> "西面"; case EAST -> "东面"; };
        String direction = switch (mode) { case 0 -> "关闭"; case 1 -> "输入"; case 2 -> "输出"; default -> "双向"; };
        return "终端 " + terminal.toShortString() + "，" + side + "改为" + direction + "；每种物品保留 " + reserve
                + " 个，主动搬运每秒最多 " + limit + " 个；白名单：" + (filters.isEmpty() ? "全部物品" : filters)
                + "；AI托管：" + (aiManaged ? "开启" : "关闭") + "。仅替换这一面设置。";
    }
}
