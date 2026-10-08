package com.ic2newera.research;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Saved evidence for the mineral, alloy and equipment guide. */
public final class PlayerResearchData {
    private final Set<ResourceLocation> completedTasks = new HashSet<>();
    private final Map<ResourceLocation, Integer> acquiredItems = new HashMap<>();

    public static PlayerResearchData get(Player player) {
        return player.getCapability(ResearchCapabilities.PLAYER_RESEARCH)
                .orElseThrow(() -> new IllegalStateException("Player progress capability is unavailable"));
    }

    public int acquiredCount(ResourceLocation item) { return acquiredItems.getOrDefault(item, 0); }
    public boolean isTaskComplete(ResourceLocation task) { return completedTasks.contains(task); }
    public boolean completeTask(ResourceLocation task) { return completedTasks.add(task); }

    public void recordAcquired(ResourceLocation item, int count) {
        if (item != null && count > acquiredCount(item)) acquiredItems.put(item, Math.min(1_000_000, count));
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag tasks = new ListTag();
        completedTasks.stream().sorted().forEach(id -> tasks.add(StringTag.valueOf(id.toString())));
        tag.put("completed_tasks", tasks);
        CompoundTag acquired = new CompoundTag();
        acquiredItems.forEach((id, count) -> acquired.putInt(id.toString(), count));
        tag.put("acquired_items", acquired);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        completedTasks.clear(); acquiredItems.clear();
        ListTag tasks = tag.getList("completed_tasks", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(tasks.size(), 16384); i++) {
            ResourceLocation id = ResourceLocation.tryParse(tasks.getString(i));
            if (id != null) completedTasks.add(id);
        }
        CompoundTag acquired = tag.getCompound("acquired_items");
        for (String key : acquired.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            int count = acquired.getInt(key);
            if (id != null && count > 0 && count <= 1_000_000) acquiredItems.put(id, count);
        }
    }
}
