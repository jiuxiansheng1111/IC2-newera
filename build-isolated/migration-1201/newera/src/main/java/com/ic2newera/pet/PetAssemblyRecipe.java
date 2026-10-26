package com.ic2newera.pet;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;

/** Shapeless pet assembly that transfers the consumed real re-battery's full NBT. */
public final class PetAssemblyRecipe extends ShapelessRecipe {
    public static final String BATTERY_DATA = "MechanicalPetBattery";
    private static final ResourceLocation BATTERY_ID = new ResourceLocation("ic2", "re_battery");
    private final ResourceLocation id;
    private final List<IngredientCount> ingredients;
    private final ItemStack result;

    public PetAssemblyRecipe(ResourceLocation id, List<IngredientCount> ingredients, ItemStack result) {
        super(id, "", CraftingBookCategory.MISC, result.copy(), toIngredientList(ingredients));
        this.id = id;
        this.ingredients = List.copyOf(ingredients);
        this.result = result.copy();
    }

    public record IngredientCount(Ingredient ingredient, int count, boolean battery) { }

    private static NonNullList<Ingredient> toIngredientList(List<IngredientCount> source) {
        NonNullList<Ingredient> list = NonNullList.create();
        source.forEach(entry -> list.add(entry.ingredient()));
        return list;
    }

    @Override public boolean matches(CraftingContainer container, Level level) {
        if (container.getContainerSize() > 9 || ingredients.isEmpty()) return false;
        int batteryIngredient = 0;
        for (IngredientCount ingredient : ingredients) if (ingredient.battery()) batteryIngredient++;
        if (batteryIngredient != 1) return false;
        boolean[] used = new boolean[container.getContainerSize()];
        return matchIngredient(container, 0, used);
    }

    private boolean matchIngredient(CraftingContainer container, int index, boolean[] used) {
        if (index == ingredients.size()) {
            for (int slot = 0; slot < container.getContainerSize(); slot++)
                if (!used[slot] && !container.getItem(slot).isEmpty()) return false;
            return true;
        }
        IngredientCount required = ingredients.get(index);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (used[slot]) continue;
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || stack.getCount() < required.count() || !required.ingredient().test(stack)) continue;
            if (required.battery() && (!PetInventory.isRechargeableBattery(stack)
                    || !BATTERY_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem())))) continue;
            used[slot] = true;
            if (matchIngredient(container, index + 1, used)) return true;
            used[slot] = false;
        }
        return false;
    }

    private ItemStack assembleWithBattery(CraftingContainer container) {
        ItemStack output = result.copy();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (PetInventory.isRechargeableBattery(stack)
                    && BATTERY_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) {
                output.getOrCreateTag().put(BATTERY_DATA, stack.copyWithCount(1).save(new net.minecraft.nbt.CompoundTag()));
                break;
            }
        }
        return output;
    }

    @Override public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        return assembleWithBattery(container);
    }
    @Override public ItemStack getResultItem(RegistryAccess registryAccess) { return result.copy(); }
    @Override public NonNullList<Ingredient> getIngredients() { return toIngredientList(ingredients); }
    @Override public boolean canCraftInDimensions(int width, int height) {
        return width * height >= ingredients.size() && width * height <= 9;
    }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return PetRegistry.ASSEMBLY_SERIALIZER.get(); }
    @Override public boolean isSpecial() { return false; }
    @Override public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            // The battery itself has moved into the pet; never also return it as a remainder.
            if (!stack.isEmpty() && !PetInventory.isRechargeableBattery(stack) && stack.hasCraftingRemainingItem())
                remaining.set(slot, stack.getCraftingRemainingItem());
        }
        return remaining;
    }

    public static final class Serializer implements RecipeSerializer<PetAssemblyRecipe> {
        @Override public PetAssemblyRecipe fromJson(ResourceLocation recipeId, JsonObject json) {
            JsonElement ingredientsElement = json.get("ingredients");
            if (ingredientsElement == null || !ingredientsElement.isJsonArray())
                throw new JsonParseException("pet assembly ingredients must be an array");
            JsonArray array = ingredientsElement.getAsJsonArray();
            if (array.size() < 2 || array.size() > 9) throw new JsonParseException("pet assembly requires 2..9 ingredients");
            List<IngredientCount> ingredients = new ArrayList<>(array.size());
            int batteries = 0;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) throw new JsonParseException("pet assembly ingredient must be an object");
                JsonObject object = element.getAsJsonObject();
                boolean battery = object.has("battery") && object.get("battery").getAsBoolean();
                Ingredient ingredient = Ingredient.fromJson(object);
                int count = object.has("count") ? object.get("count").getAsInt() : 1;
                if (count != 1) throw new JsonParseException("pet assembly ingredients must have count one");
                if (battery) {
                    batteries++;
                    if (!object.has("item") || !BATTERY_ID.toString().equals(object.get("item").getAsString()) || count != 1)
                        throw new JsonParseException("the battery ingredient must be exactly one ic2:re_battery");
                }
                ingredients.add(new IngredientCount(ingredient, count, battery));
            }
            if (batteries != 1) throw new JsonParseException("pet assembly requires exactly one battery:true ingredient");
            JsonElement resultElement = json.get("result");
            if (resultElement == null || !resultElement.isJsonObject()) throw new JsonParseException("pet assembly result must be an object");
            JsonObject output = resultElement.getAsJsonObject();
            if (!output.has("item")) throw new JsonParseException("pet assembly result item is missing");
            ResourceLocation resultId = ResourceLocation.tryParse(output.get("item").getAsString());
            Item item = resultId == null ? Items.AIR : ForgeRegistries.ITEMS.getValue(resultId);
            if (item == null || item == Items.AIR || (item != PetRegistry.CAT_ITEM.get() && item != PetRegistry.DOG_ITEM.get()))
                throw new JsonParseException("pet assembly result must be a mechanical cat or dog item");
            int count = output.has("count") ? output.get("count").getAsInt() : 1;
            if (count != 1) throw new JsonParseException("pet assembly result must have count one");
            return new PetAssemblyRecipe(recipeId, ingredients, new ItemStack(item));
        }

        @Override public PetAssemblyRecipe fromNetwork(ResourceLocation recipeId, FriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 2 || size > 9) throw new JsonParseException("invalid pet assembly ingredient count");
            List<IngredientCount> ingredients = new ArrayList<>(size);
            int batteries = 0;
            for (int i = 0; i < size; i++) {
                Ingredient ingredient = Ingredient.fromNetwork(buffer);
                int count = buffer.readVarInt();
                boolean battery = buffer.readBoolean();
                if (count != 1) throw new JsonParseException("invalid pet assembly ingredient amount");
                if (battery) batteries++;
                ingredients.add(new IngredientCount(ingredient, count, battery));
            }
            ItemStack result = buffer.readItem();
            if (batteries != 1 || result.isEmpty() || result.getCount() != 1
                    || (result.getItem() != PetRegistry.CAT_ITEM.get() && result.getItem() != PetRegistry.DOG_ITEM.get()))
                throw new JsonParseException("invalid pet assembly network recipe");
            return new PetAssemblyRecipe(recipeId, ingredients, result);
        }

        @Override public void toNetwork(FriendlyByteBuf buffer, PetAssemblyRecipe recipe) {
            buffer.writeVarInt(recipe.ingredients.size());
            for (IngredientCount entry : recipe.ingredients) {
                entry.ingredient().toNetwork(buffer);
                buffer.writeVarInt(entry.count());
                buffer.writeBoolean(entry.battery());
            }
            buffer.writeItem(recipe.result);
        }
    }
}
