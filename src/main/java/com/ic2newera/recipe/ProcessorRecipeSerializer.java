package com.ic2newera.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.ic2newera.machine.ProcessorKind;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Validates and synchronizes the intentionally small processor recipe schema. */
public final class ProcessorRecipeSerializer implements RecipeSerializer<ProcessorRecipe> {
    @Override
    public ProcessorRecipe fromJson(ResourceLocation recipeId, JsonObject json) {
        ProcessorKind kind = parseKind(requiredString(json, "kind"));
        JsonElement ingredientElement = json.get("ingredients");
        if (ingredientElement == null || !ingredientElement.isJsonArray()) {
            throw new JsonParseException("processor ingredients must be an array");
        }
        JsonArray array = ingredientElement.getAsJsonArray();
        if (array.isEmpty() || array.size() > kind.inputSlots() || array.size() > 6) {
            throw new JsonParseException("processor recipes require 1.." + kind.inputSlots() + " input ingredients for " + kind.serializedName());
        }
        List<ProcessorRecipe.IngredientCount> ingredients = new ArrayList<>(array.size());
        for (JsonElement element : array) ingredients.add(parseIngredientCount(element));

        JsonObject resultObject = requiredObject(json, "result");
        Item resultItem = resolveItem(requiredString(resultObject, "item"), "result item");
        int resultCount = boundedInteger(resultObject, "count", 1, 64);
        int duration = boundedInteger(json, "duration_ticks", 1, 72000);
        int euPerTick = boundedInteger(json, "eu_per_tick", 1, 512);
        return new ProcessorRecipe(recipeId, kind, ingredients, new ItemStack(resultItem, resultCount), duration, euPerTick);
    }

    private static ProcessorRecipe.IngredientCount parseIngredientCount(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw new JsonParseException("processor ingredient must be an object");
        JsonObject object = element.getAsJsonObject();
        boolean hasItem = object.has("item");
        boolean hasTag = object.has("tag");
        if (hasItem == hasTag) throw new JsonParseException("processor ingredient must define exactly one of item or tag");
        Ingredient ingredient;
        if (hasItem) {
            ingredient = Ingredient.of(resolveItem(requiredString(object, "item"), "ingredient item"));
        } else {
            ResourceLocation tagId = parseId(requiredString(object, "tag"), "ingredient tag");
            ingredient = Ingredient.of(TagKey.create(Registry.ITEM_REGISTRY, tagId));
        }
        int count = boundedInteger(object, "count", 1, 64);
        return new ProcessorRecipe.IngredientCount(ingredient, count);
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) throw new JsonParseException("processor field '" + key + "' must be an object");
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("processor field '" + key + "' must be a string");
        }
        return value.getAsString();
    }

    private static int boundedInteger(JsonObject object, String key, int min, int max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("processor field '" + key + "' must be an integer");
        }
        try {
            double decimal = value.getAsDouble();
            int number = value.getAsInt();
            if (decimal != number || number < min || number > max) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException invalid) {
            throw new JsonParseException("processor field '" + key + "' must be between " + min + " and " + max, invalid);
        }
    }

    private static ProcessorKind parseKind(String name) {
        try { return ProcessorKind.fromSerializedName(name); }
        catch (IllegalArgumentException invalid) { throw new JsonParseException(invalid.getMessage(), invalid); }
    }

    private static ResourceLocation parseId(String name, String field) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) throw new JsonParseException("invalid " + field + " ID: " + name);
        return id;
    }

    private static Item resolveItem(String name, String field) {
        Item item = ForgeRegistries.ITEMS.getValue(parseId(name, field));
        if (item == null || item == Items.AIR) throw new JsonParseException("unknown " + field + ": " + name);
        return item;
    }

    @Override
    public ProcessorRecipe fromNetwork(ResourceLocation recipeId, FriendlyByteBuf buffer) {
        ProcessorKind kind = parseKind(buffer.readUtf(32));
        int count = buffer.readVarInt();
        if (count < 1 || count > Math.min(6, kind.inputSlots())) throw new JsonParseException("invalid processor ingredient count");
        List<ProcessorRecipe.IngredientCount> ingredients = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Ingredient ingredient = Ingredient.fromNetwork(buffer);
            int amount = buffer.readVarInt();
            if (amount < 1 || amount > 64) throw new JsonParseException("invalid processor ingredient amount");
            ingredients.add(new ProcessorRecipe.IngredientCount(ingredient, amount));
        }
        ItemStack result = buffer.readItem();
        if (result.isEmpty() || result.getCount() > 64) throw new JsonParseException("invalid processor result");
        int duration = buffer.readVarInt();
        int euPerTick = buffer.readVarInt();
        if (duration < 1 || duration > 72000 || euPerTick < 1 || euPerTick > 512) {
            throw new JsonParseException("invalid processor time or EU cost");
        }
        return new ProcessorRecipe(recipeId, kind, ingredients, result, duration, euPerTick);
    }

    @Override
    public void toNetwork(FriendlyByteBuf buffer, ProcessorRecipe recipe) {
        buffer.writeUtf(recipe.kind().serializedName());
        buffer.writeVarInt(recipe.countedIngredients().size());
        for (ProcessorRecipe.IngredientCount ingredient : recipe.countedIngredients()) {
            ingredient.ingredient().toNetwork(buffer);
            buffer.writeVarInt(ingredient.count());
        }
        buffer.writeItem(recipe.getResultItem());
        buffer.writeVarInt(recipe.durationTicks());
        buffer.writeVarInt(recipe.euPerTick());
    }
}
