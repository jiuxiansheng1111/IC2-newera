package com.ic2newera.robot.building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.regex.Pattern;

/** Verified IC2 Classic 2.1.3.4 cable variants; placement always uses the native CableItem. */
public enum RobotPowerCable {
    COPPER("copper", "绝缘铜缆", "copper_insulated_cable_item", 1, 32, 200,
            "绝缘铜电缆", "绝缘铜缆", "绝缘铜线", "铜电缆", "铜缆", "铜线", "copper cable"),
    GLASS("glassfiber", "玻璃纤维电缆", "glassfiber_cable_item", -1, 512, 25,
            "玻璃纤维电缆", "玻璃纤维导线", "玻璃纤维线", "玻璃纤维", "玻纤电缆", "玻纤线", "玻纤", "glassfiber cable", "glass fibre cable", "glass fiber cable"),
    GOLD("gold", "双重绝缘金缆", "gold_double_insulated_cable_item", 2, 128, 400,
            "双重绝缘金电缆", "双重绝缘金缆", "绝缘金电缆", "绝缘金缆", "金电缆", "金缆", "金线", "gold cable"),
    BRONZE("bronze", "双重绝缘青铜缆", "bronze_double_insulated_cable_item", 2, 128, 600,
            "双重绝缘青铜电缆", "双重绝缘青铜缆", "绝缘青铜电缆", "绝缘青铜缆", "青铜电缆", "青铜缆", "青铜线", "bronze cable"),
    IRON("iron", "三重绝缘铁缆", "iron_triple_insulated_cable_item", 3, 2048, 800,
            "三重绝缘铁电缆", "三重绝缘铁缆", "绝缘铁电缆", "绝缘铁缆", "铁电缆", "铁缆", "铁线", "iron cable"),
    ALUMINIUM("aluminium", "四重绝缘铝缆", "aluminium_quadruple_insulated_cable_item", 4, 8192, 1000,
            "四重绝缘铝电缆", "四重绝缘铝缆", "绝缘铝电缆", "绝缘铝缆", "铝电缆", "铝缆", "铝线", "aluminium cable", "aluminum cable"),
    SUPER("super", "超导电缆", "super_cable_item", -1, 8192, 1,
            "超导电缆", "超导线", "super cable", "superconductor cable"),
    PLASMA("plasma", "等离子电缆", "plasma_cable_item", -1, 32768, 1200,
            "等离子电缆", "等离子线", "plasma cable"),
    TIN("tin", "锡电缆", "tin_cable_item", -1, 5, 25,
            "锡电缆", "锡缆", "锡线", "tin cable");

    private final String block, label, item;
    private final int insulation, packetLimit, lossMilli;
    private final List<String> aliases;
    RobotPowerCable(String base, String label, String item, int insulation, int packetLimit, int lossMilli, String... aliases) {
        this.block = "ic2:" + base + "_cable"; this.label = label; this.item = "ic2:" + item;
        this.insulation = insulation; this.packetLimit = packetLimit; this.lossMilli = lossMilli;
        List<String> names = new ArrayList<>(List.of(aliases)); names.add(block); names.add(this.item);
        this.aliases = List.copyOf(names);
    }
    public String blockId() { return block; }
    public String itemId() { return item; }
    public String label() { return label; }
    public int packetLimit() { return packetLimit; }
    public double loss() { return lossMilli / 1000.0; }
    public int conservativeLoss(int segments) { return (segments * lossMilli + 999) / 1000; }
    public Map<String, String> properties() { return insulation < 0 ? Map.of() : Map.of("insulation", Integer.toString(insulation)); }
    public RobotBuildPlan.Part part(BlockPos offset) { return new RobotBuildPlan.Part(offset, block, properties(), "power_cable"); }
    public Item materialItem() {
        Item result = ForgeRegistries.ITEMS.getValue(new ResourceLocation(item));
        if (!(result instanceof BlockItem nativeItem) || nativeItem.getBlock() != ForgeRegistries.BLOCKS.getValue(new ResourceLocation(block)))
            throw new IllegalArgumentException("IC2电缆放置物品不可用：" + item);
        return result;
    }
    public static RobotPowerCable forPart(RobotBuildPlan.Part part) {
        for (RobotPowerCable type : values()) if (type.block.equals(part.blockId())
                && Objects.equals(type.properties().get("insulation"), part.properties().get("insulation"))) return type;
        return null;
    }
    public static RobotPowerCable from(CompoundTag metadata) {
        if (!metadata.contains("PowerCable")) return COPPER;
        String selected = metadata.getString("PowerCable");
        for (RobotPowerCable cable : values()) if (cable.item.equals(selected)) return cable;
        throw new IllegalArgumentException("保存的电缆型号不受支持：" + selected);
    }
    public record Selection(RobotPowerCable cable, String productionDescription, boolean explicit) { }
    public static Selection select(String description) {
        if (description == null) throw new IllegalArgumentException("请填写生产线与电缆描述");
        var insulationRequest = Pattern.compile("(单|一|双|二|三|四|[0-9]+)(?:重|层)绝缘(青铜|铜|金|铁|铝)").matcher(description);
        while (insulationRequest.find()) {
            String level = insulationRequest.group(1);
            int requested = switch (level) { case "单", "一" -> 1; case "双", "二" -> 2; case "三" -> 3; case "四" -> 4;
                default -> level.length() < 3 ? Integer.parseInt(level) : -1; };
            int supported = switch (insulationRequest.group(2)) { case "铜" -> 1; case "金", "青铜" -> 2; case "铁" -> 3; default -> 4; };
            if (requested != supported) throw new IllegalArgumentException("当前规划器支持该材质的" + supported + "层绝缘型号，未按请求更换：" + insulationRequest.group());
        }
        if (description.matches("(?s).*(?:裸|未绝缘)(?:铜|金|青铜|铁|铝).*"))
            throw new IllegalArgumentException("本规划器仅自动铺设已核实的绝缘金属线型号，不把裸线请求悄悄改成绝缘线。");
        String remaining = description.toLowerCase(Locale.ROOT);
        // Longest token first prevents 青铜线 from also matching 铜线, and IDs from matching their prefixes.
        record Alias(String text, RobotPowerCable cable) { }
        List<Alias> aliases = new ArrayList<>();
        for (RobotPowerCable cable : values()) for (String alias : cable.aliases) aliases.add(new Alias(alias, cable));
        aliases.sort(Comparator.comparingInt((Alias a) -> a.text.length()).reversed());
        Set<RobotPowerCable> selected = new LinkedHashSet<>();
        for (Alias alias : aliases) {
            String expression = Pattern.quote(alias.text);
            if (alias.text.charAt(0) < 128) expression = "(?<![a-z0-9_])" + expression + "(?![a-z0-9_])";
            Pattern token = Pattern.compile(expression);
            if (token.matcher(remaining).find()) { selected.add(alias.cable); remaining = token.matcher(remaining).replaceAll(" "); }
        }
        if (selected.size() > 1) throw new IllegalArgumentException("请只指定一种电缆；整条干线及分支使用同一型号。");
        if (remaining.matches("(?s).*ic2:[a-z0-9_]*(?:cable|wire)[a-z0-9_]*.*"))
            throw new IllegalArgumentException("该电缆型号或绝缘变体暂不支持；请使用绝缘铜缆、玻璃纤维电缆等完整型号。");
        if (selected.isEmpty() && remaining.matches("(?s).*(?:用|使用|选用|换成|改成|改为)\\s*[^，。；,;]{1,16}(?:电缆|缆线|导线|cable).*"))
            throw new IllegalArgumentException("未识别电缆型号；可指定绝缘铜缆、玻璃纤维、双重绝缘金缆、双重绝缘青铜缆、三重绝缘铁缆、四重绝缘铝缆或超导电缆。");
        return new Selection(selected.isEmpty() ? COPPER : selected.iterator().next(), remaining, !selected.isEmpty());
    }
}
