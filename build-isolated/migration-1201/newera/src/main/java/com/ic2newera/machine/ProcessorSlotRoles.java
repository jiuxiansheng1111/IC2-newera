package com.ic2newera.machine;

import net.minecraft.network.chat.Component;

/** One declaration drives menu predicates, automation faces, and Chinese slot help. */
public final class ProcessorSlotRoles {
    public enum Role { INPUT, OUTPUT }

    private ProcessorSlotRoles() { }

    public static Role role(ProcessorKind kind, int slot) {
        if (slot < 0 || slot >= kind.machineSlots()) throw new IndexOutOfBoundsException(slot);
        return slot < kind.inputSlots() ? Role.INPUT : Role.OUTPUT;
    }

    public static boolean mayPlace(ProcessorKind kind, int slot) {
        return role(kind, slot) == Role.INPUT;
    }
    public static java.util.List<com.ic2newera.recipe.ProcessorRecipe.IngredientCount> acceptedInputs(
            net.minecraft.world.level.Level level, ProcessorKind kind, int slot) {
        if (level==null || !mayPlace(kind,slot)) return java.util.List.of();
        return level.getRecipeManager().getAllRecipesFor(com.ic2newera.recipe.ModProcessorRecipes.PROCESSOR.get()).stream()
                .filter(recipe->recipe.kind()==kind && slot<recipe.countedIngredients().size())
                .map(recipe->recipe.countedIngredients().get(slot)).toList();
    }

    public static Component description(ProcessorKind kind, int slot) {
        return switch (role(kind, slot)) {
            case OUTPUT -> Component.literal("成品输出槽：取走加工结果；不能手动放入物品。空间不足或有不同产物时会暂停加工。");
            case INPUT -> switch (kind) {
                case NON_METAL -> Component.literal("非金属原料槽：放入JEI本机配方所需原料，数量必须足够；可接受的物品见下方。");
                case ELECTRONIC -> Component.literal("电子加工原料槽 " + (slot + 1) + "：从左到右、从上到下对应JEI配方；种类和数量都要匹配。");
                case ALLOY -> Component.literal("合金配料槽 " + (slot + 1) + "：按JEI配方从左到右放置不同原料，数量必须足够。");
                case ADVANCED -> Component.literal("高级熔炼原料槽：放入JEI本机配方中的原料；本槽不接受其他炉子的专用配方原料。");
            };
        };
    }
}
