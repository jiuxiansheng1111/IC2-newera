package com.ic2newera.technology;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

public record TechnologyNode(ResourceLocation id, Component title, Component description, String branch,
                             ResourceLocation icon, List<ResourceLocation> prerequisites,
                             List<Objective> objectives, List<Component> steps, int x, int y) {
    public TechnologyNode {
        prerequisites = List.copyOf(prerequisites);
        objectives = List.copyOf(objectives);
        steps = List.copyOf(steps);
    }
    public record Objective(String type, ResourceLocation target, int count, ResourceLocation profile) {
        public Objective(String type, ResourceLocation target, int count) { this(type, target, count, null); }
    }
}
