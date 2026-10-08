package com.ic2newera.technology;

import com.ic2newera.IC2NewEra;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Complements authored guides with actual registered recipe products, never recipe-book advancements. */
final class ProductTechnologyCatalog {
    private static final Set<String> ACTIVE_ADDON_OUTPUTS = Set.of(
            "zinc_dust", "aluminium_bronze_ingot", "carbon_steel_billet", "high_carbon_steel_billet",
            "tempered_steel_ingot", "tempered_high_carbon_steel_ingot",
            "aluminium_bronze_helmet", "aluminium_bronze_chestplate", "aluminium_bronze_leggings",
            "aluminium_bronze_boots", "aluminium_bronze_sword", "aluminium_bronze_axe",
            "tempered_steel_helmet", "tempered_steel_chestplate", "tempered_steel_leggings",
            "tempered_steel_boots", "tempered_steel_sword", "tempered_steel_axe",
            "tempered_high_carbon_steel_helmet", "tempered_high_carbon_steel_chestplate",
            "tempered_high_carbon_steel_leggings", "tempered_high_carbon_steel_boots",
            "tempered_high_carbon_steel_sword", "tempered_high_carbon_steel_axe"
    );
    private ProductTechnologyCatalog() {}

    static int append(MinecraftServer server, Map<ResourceLocation, TechnologyNode> nodes) {
        Map<ResourceLocation, TechnologyNode> guided = new HashMap<>();
        nodes.values().stream().filter(node -> !node.id().getPath().startsWith("native/"))
                .sorted(Comparator.comparingInt(TechnologyNode::x).thenComparing(node -> node.id().toString()))
                .forEach(node -> {
                    if (node.objectives().stream().anyMatch(o -> o.type().equals("item") && o.target().equals(node.icon())))
                        guided.putIfAbsent(node.icon(), node);
                });
        Map<ResourceLocation, List<Recipe<?>>> recipes = new TreeMap<>();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            ItemStack result = recipe.getResultItem();
            if (result.isEmpty()) continue;
            ResourceLocation item = ForgeRegistries.ITEMS.getKey(result.getItem());
            if (item != null && (item.getNamespace().equals("ic2")
                    || item.getNamespace().equals(IC2NewEra.MOD_ID) && ACTIVE_ADDON_OUTPUTS.contains(item.getPath()))
                    && !guided.containsKey(item))
                recipes.computeIfAbsent(item, ignored -> new ArrayList<>()).add(recipe);
        }
        TechnologyNode start = nodes.get(new ResourceLocation(IC2NewEra.MOD_ID, "raw_materials"));
        int added = 0;
        for (var entry : recipes.entrySet()) {
            ResourceLocation item = entry.getKey();
            List<Recipe<?>> alternatives = entry.getValue();
            alternatives.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
            // Prefer a route whose industrial ingredients already have authored guides.
            Recipe<?> primary = alternatives.stream().max(Comparator.comparingInt(recipe -> guidedInputs(recipe, guided).size()))
                    .orElseThrow();
            List<ResourceLocation> parents = new ArrayList<>(guidedInputs(primary, guided));
            if (parents.isEmpty() && start != null) parents.add(start.id());
            List<Component> steps = new ArrayList<>();
            for (int i = 0; i < Math.min(3, alternatives.size()); i++) {
                Recipe<?> recipe = alternatives.get(i);
                steps.add(Component.literal("生产路线 " + (i + 1) + "：").append(workstation(recipe))
                        .append(" → ").append(recipe.getResultItem().getHoverName())
                        .append(" × " + recipe.getResultItem().getCount()));
                steps.add(ingredients(recipe));
            }
            if (alternatives.size() > 3) steps.add(Component.literal("还有 " + (alternatives.size() - 3) + " 条已注册配方；可在 JEI 中查看完整排列与替代原料。"));
            steps.add(Component.literal("先沿前置节点取得原料，再按对应配方加工。取得成品会记录进度，不消耗任务物品。"));
            ResourceLocation id = new ResourceLocation(IC2NewEra.MOD_ID, "product/" + item.getNamespace() + "/" + item.getPath());
            ItemStack icon = new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(item)));
            String branch = parents.stream().map(nodes::get).filter(Objects::nonNull).map(TechnologyNode::branch)
                    .filter(value -> !value.equals("矿物分析")).findFirst().orElse("资源与冶金");
            nodes.put(id, new TechnologyNode(id, icon.getHoverName(), Component.literal("工业成品：")
                    .append(icon.getHoverName()).append("。已收录 " + alternatives.size() + " 条本世界注册配方。"),
                    branch, item, parents, List.of(new TechnologyNode.Objective("item", item, 1)), steps, 0, 500 + added));
            added++;
        }
        return added;
    }

    private static Set<ResourceLocation> guidedInputs(Recipe<?> recipe, Map<ResourceLocation, TechnologyNode> guided) {
        Set<ResourceLocation> parents = new LinkedHashSet<>();
        for (CountedIngredient counted : requirements(recipe)) {
            Ingredient ingredient = counted.ingredient;
            // A tag is an alternative, not a requirement to obtain every member of that tag.
            for (ItemStack stack : ingredient.getItems()) {
                TechnologyNode node = guided.get(ForgeRegistries.ITEMS.getKey(stack.getItem()));
                if (node != null) { parents.add(node.id()); break; }
            }
        }
        return parents;
    }
    private static Component ingredients(Recipe<?> recipe) {
        Map<String, IngredientCount> counts = new LinkedHashMap<>();
        for (CountedIngredient counted : requirements(recipe)) {
            Ingredient ingredient = counted.ingredient;
            ItemStack[] choices = ingredient.getItems();
            if (choices.length == 0) continue;
            String signature = Arrays.stream(choices).map(stack -> Objects.toString(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                    .sorted().reduce("", (left, right) -> left + "|" + right);
            IngredientCount value = counts.get(signature);
            if (value == null) counts.put(signature, new IngredientCount(choices, counted.count));
            else value.count += counted.count;
        }
        var text = Component.literal("原料：");
        if (counts.isEmpty()) return text.append("该配方使用动态原料或内部加工数据，请查看 JEI 的具体用法。");
        boolean first = true;
        for (IngredientCount value : counts.values()) {
            if (!first) text.append("；"); first = false;
            if (value.choices.length > 1) text.append("任选（");
            for (int i = 0; i < Math.min(3, value.choices.length); i++) {
                if (i > 0) text.append(" / ");
                text.append(value.choices[i].getHoverName());
            }
            if (value.choices.length > 3) text.append("等 " + value.choices.length + " 种");
            if (value.choices.length > 1) text.append("）");
            text.append(" × " + value.count);
        }
        return text;
    }
    /** Custom counted recipes do not encode quantities in vanilla getIngredients(). */
    private static List<CountedIngredient> requirements(Recipe<?> recipe) {
        return recipe.getIngredients().stream().map(ingredient -> new CountedIngredient(ingredient, 1)).toList();
    }
    private record CountedIngredient(Ingredient ingredient, int count) { }
    private static Component workstation(Recipe<?> recipe) {
        ResourceLocation type = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        if (type == null) return Component.literal("对应加工设备");
        return switch (type.toString()) {
            case "minecraft:crafting" -> Component.literal("工作台");
            case "minecraft:smelting" -> Component.literal("熔炉 / 电炉");
            case "minecraft:blasting" -> Component.literal("高炉");
            case "minecraft:smoking" -> Component.literal("烟熏炉");
            case "ic2:mixing_furnace" -> Component.literal("IC2 合金熔炼机");
            default -> recipe.getToastSymbol().isEmpty() ? Component.literal("对应机器（JEI 配方分类）")
                    : recipe.getToastSymbol().getHoverName();
        };
    }
    private static final class IngredientCount {
        final ItemStack[] choices; int count;
        IngredientCount(ItemStack[] choices, int count) { this.choices = choices; this.count = count; }
    }
}
