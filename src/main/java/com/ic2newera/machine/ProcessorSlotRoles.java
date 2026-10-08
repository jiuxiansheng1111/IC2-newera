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
            case OUTPUT -> Component.literal("产物输出槽：只能取出加工产物，不能放入物品。");
            case INPUT -> kind == ProcessorKind.NON_METAL
                    ? Component.literal("非金属原料输入槽：仅接受已登记的非金属处理配方在此槽要求的物品。")
                    : Component.literal("电子元件输入槽：仅接受已登记的电子加工配方在此槽要求的物品。");
        };
    }
}
