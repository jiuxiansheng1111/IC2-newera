package com.ic2newera.technology;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Client view holds only revealed nodes. Locked descriptions are never sent by the server. */
public final class TechnologyClientState {
    private static volatile List<NodeView> nodes = List.of();
    private static volatile int total, completed, hidden;
    private static long pendingRevision = Long.MIN_VALUE, installedRevision = Long.MIN_VALUE, pendingSince;
    private static CompoundTag[] pendingParts;
    private static int receivedParts, pendingNodeCount;
    private TechnologyClientState() {}
    public static List<NodeView> nodes() { return nodes; }
    public static int totalCount() { return total; }
    public static int completedCount() { return completed; }
    public static int hiddenCount() { return hidden; }
    public static void clear() {
        nodes = List.of(); total = completed = hidden = 0;
        pendingParts = null; pendingRevision = installedRevision = Long.MIN_VALUE; receivedParts = pendingNodeCount = 0;
    }
    public static void acceptPart(long revision, int index, int count, CompoundTag part) {
        if (revision <= installedRevision || count < 1 || count > 1024 || index < 0 || index >= count) return;
        long now = System.currentTimeMillis();
        if (pendingParts != null && revision < pendingRevision) return;
        if (pendingParts == null || revision != pendingRevision || now - pendingSince > 30000) {
            pendingRevision = revision; pendingParts = new CompoundTag[count]; pendingSince = now;
            receivedParts = pendingNodeCount = 0;
        }
        if (pendingParts.length != count || pendingParts[index] != null) return;
        pendingNodeCount += part.getList("nodes", Tag.TAG_COMPOUND).size();
        if (pendingNodeCount > 4096) { pendingParts = null; return; }
        pendingParts[index] = part.copy(); receivedParts++;
        if (receivedParts != count) return;
        CompoundTag snapshot = pendingParts[0].copy(); ListTag all = new ListTag();
        for (CompoundTag value : pendingParts)
            for (Tag node : value.getList("nodes", Tag.TAG_COMPOUND)) all.add(node.copy());
        snapshot.put("nodes", all);
        install(snapshot); installedRevision = revision; pendingParts = null;
    }
    public record ObjectiveView(Component text, int progress, int required) {}
    public record NodeView(ResourceLocation id, Component title, Component description, String branch,
                           ItemStack icon, List<ResourceLocation> prerequisites, List<Component> steps,
                           int status, int hiddenDescendants, int x, int y, List<ObjectiveView> objectives) {
        public NodeView {
            icon = icon.copy(); prerequisites = List.copyOf(prerequisites);
            steps = List.copyOf(steps); objectives = List.copyOf(objectives);
        }
    }
    public static void install(CompoundTag snapshot) {
        List<NodeView> candidate = new ArrayList<>();
        ListTag values = snapshot.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(values.size(), 4096); i++) {
            CompoundTag value = values.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(value.getString("id"));
            if (id == null) continue;
            List<ResourceLocation> parents = new ArrayList<>();
            ListTag parentTags = value.getList("parents", Tag.TAG_STRING);
            for (int p = 0; p < parentTags.size(); p++) {
                ResourceLocation parent = ResourceLocation.tryParse(parentTags.getString(p));
                if (parent != null) parents.add(parent);
            }
            List<Component> steps = new ArrayList<>();
            ListTag stepTags = value.getList("steps", Tag.TAG_STRING);
            for (int s = 0; s < stepTags.size(); s++) steps.add(component(stepTags.getString(s)));
            List<ObjectiveView> objectives = new ArrayList<>();
            ListTag objectiveTags = value.getList("objectives", Tag.TAG_COMPOUND);
            for (int o = 0; o < objectiveTags.size(); o++) {
                CompoundTag objective = objectiveTags.getCompound(o);
                objectives.add(new ObjectiveView(component(objective.getString("text")), objective.getInt("progress"), objective.getInt("required")));
            }
            candidate.add(new NodeView(id, component(value.getString("title")), component(value.getString("description")),
                    value.getString("branch"), ItemStack.of(value.getCompound("icon")), parents, steps, value.getInt("status"),
                    value.getInt("hidden_after"), value.getInt("x"), value.getInt("y"), objectives));
        }
        nodes = List.copyOf(candidate);
        total = Math.max(0, snapshot.getInt("total"));
        completed = Math.max(0, snapshot.getInt("completed"));
        hidden = Math.max(0, snapshot.getInt("hidden"));
    }
    private static Component component(String json) {
        try { Component result = Component.Serializer.fromJson(json); if (result != null) return result; }
        catch (RuntimeException ignored) {}
        return Component.empty();
    }
}
