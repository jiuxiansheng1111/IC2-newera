package com.ic2newera.robot.building;

import ic2.core.block.wiring.CableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.regex.Pattern;

/** IC2R cable block variants verified from the selected 1.20.1 runtime JAR. */
public enum RobotPowerCable {
    COPPER("insulated_copper_cable", "绝缘铜缆", CableType.copper, 1,
            "绝缘铜电缆", "绝缘铜缆", "绝缘铜线", "铜电缆", "铜缆", "铜线", "copper cable"),
    GLASS("glass_fibre_cable", "玻璃纤维电缆", CableType.glass, 0,
            "玻璃纤维电缆", "玻璃纤维导线", "玻璃纤维线", "玻璃纤维", "玻纤电缆", "玻纤线", "玻纤", "glass fibre cable", "glass fiber cable"),
    GOLD("double_insulated_gold_cable", "双重绝缘金缆", CableType.gold, 2,
            "双重绝缘金电缆", "双重绝缘金缆", "绝缘金电缆", "绝缘金缆", "金电缆", "金缆", "金线", "gold cable"),
    IRON("triple_insulated_iron_cable", "三重绝缘铁缆", CableType.iron, 3,
            "三重绝缘铁电缆", "三重绝缘铁缆", "绝缘铁电缆", "绝缘铁缆", "铁电缆", "铁缆", "铁线", "iron cable"),
    TIN("insulated_tin_cable", "绝缘锡缆", CableType.tin, 1,
            "绝缘锡电缆", "绝缘锡缆", "锡电缆", "锡缆", "锡线", "tin cable"),
    BARE_COPPER("copper_cable", "裸铜缆", CableType.copper, 0),
    BARE_GOLD("gold_cable", "裸金缆", CableType.gold, 0),
    INSULATED_GOLD("insulated_gold_cable", "绝缘金缆", CableType.gold, 1),
    BARE_IRON("iron_cable", "裸铁缆", CableType.iron, 0),
    INSULATED_IRON("insulated_iron_cable", "绝缘铁缆", CableType.iron, 1),
    DOUBLE_INSULATED_IRON("double_insulated_iron_cable", "双重绝缘铁缆", CableType.iron, 2),
    BARE_TIN("tin_cable", "裸锡缆", CableType.tin, 0);

    private final String block, label;
    private final CableType cableType;
    private final int insulation;
    private final List<String> aliases;

    RobotPowerCable(String id, String label, CableType cableType, int insulation, String... aliases) {
        this.block = "ic2:" + id;
        this.label = label;
        this.cableType = cableType;
        this.insulation = insulation;
        List<String> names = new ArrayList<>(List.of(aliases));
        names.add(block);
        this.aliases = List.copyOf(names);
    }

    public String blockId() { return block; }
    public String itemId() { return block; }
    public String label() { return label; }
    public int packetLimit() { return cableType.capacity; }
    public double loss() { return cableType.loss; }
    public int conservativeLoss(int segments) { return (int)Math.ceil(Math.max(0, segments) * cableType.loss); }
    /** IC2R encodes insulation as distinct block IDs, not a blockstate property. */
    public Map<String, String> properties() { return Map.of(); }
    public RobotBuildPlan.Part part(BlockPos offset) { return new RobotBuildPlan.Part(offset, block, properties(), "power_cable"); }

    public Item materialItem() {
        Item result = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId()));
        if (!(result instanceof BlockItem nativeItem)
                || nativeItem.getBlock() != BuiltInRegistries.BLOCK.get(ResourceLocation.parse(block)))
            throw new IllegalArgumentException("IC2R原生电缆放置物品不可用：" + itemId());
        return result;
    }

    public static RobotPowerCable forPart(RobotBuildPlan.Part part) {
        for (RobotPowerCable type : values()) if (type.block.equals(part.blockId())) return type;
        return null;
    }

    public static RobotPowerCable from(CompoundTag metadata) {
        if (!metadata.contains("PowerCable")) return COPPER;
        String selected = metadata.getString("PowerCable");
        for (RobotPowerCable cable : values()) if (cable.itemId().equals(selected)) return cable;
        throw new IllegalArgumentException("保存的电缆型号不属于当前IC2R实际型号或已被移除：" + selected);
    }

    public record Selection(RobotPowerCable cable, String productionDescription, boolean explicit) { }

    public static Selection select(String description) {
        if (description == null) throw new IllegalArgumentException("请填写生产线与电缆描述");
        var insulationRequest = Pattern.compile("(单|一|双|二|三|四|[0-9]+)(?:重|层)绝缘(铜|金|铁|锡)").matcher(description);
        while (insulationRequest.find()) {
            String level = insulationRequest.group(1);
            int requested = switch (level) { case "单", "一" -> 1; case "双", "二" -> 2; case "三" -> 3; case "四" -> 4;
                default -> Integer.parseInt(level); };
            int supported = switch (insulationRequest.group(2)) { case "铜", "锡" -> 1; case "金" -> 2; default -> 3; };
            if (requested != supported) throw new IllegalArgumentException("当前 IC2R 该材质的规划型号为" + supported + "层绝缘：" + insulationRequest.group());
        }
        if (description.matches("(?s).*(?:裸|未绝缘)(?:铜|金|铁|锡).*"))
            throw new IllegalArgumentException("当前生产线规划只自动铺设已核实的绝缘金属缆；裸缆仍可识别既有线路，但不会自动选用。");
        String remaining = description.toLowerCase(Locale.ROOT);
        record Alias(String text, RobotPowerCable cable) { }
        List<Alias> aliases = new ArrayList<>();
        for (RobotPowerCable cable : values()) for (String alias : cable.aliases) aliases.add(new Alias(alias, cable));
        aliases.sort(Comparator.comparingInt((Alias a) -> a.text.length()).reversed());
        Set<RobotPowerCable> selected = new LinkedHashSet<>();
        for (Alias alias : aliases) {
            String expression = Pattern.quote(alias.text);
            if (alias.text.charAt(0) < 128) expression = "(?<![a-z0-9_])" + expression + "(?![a-z0-9_])";
            Pattern token = Pattern.compile(expression);
            if (token.matcher(remaining).find()) {
                selected.add(alias.cable);
                remaining = token.matcher(remaining).replaceAll(" ");
            }
        }
        if (selected.size() > 1) throw new IllegalArgumentException("请只指定一种电缆；整条干线及分支使用同一型号。");
        if (remaining.matches("(?s).*ic2:[a-z0-9_]*(?:cable|wire)[a-z0-9_]*.*"))
            throw new IllegalArgumentException("该电缆型号或绝缘变体未在目标 IC2R JAR 中核实。");
        if (selected.isEmpty() && remaining.matches("(?s).*(?:用|使用|选用|换成|改成|改为)\\s*[^，。；,;]{1,16}(?:电缆|缆线|导线|cable).*"))
            throw new IllegalArgumentException("未识别电缆型号；可指定绝缘铜缆、玻璃纤维电缆、双重绝缘金缆、三重绝缘铁缆或绝缘锡缆。");
        return new Selection(selected.isEmpty() ? COPPER : selected.iterator().next(), remaining, !selected.isEmpty());
    }
}
