package com.ic2newera.field;

import com.ic2newera.IC2NewEra;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** The native advancement screen, toast, and FTB advancement tasks see the same event. */
public final class FieldMilestones {
    private FieldMilestones() {}
    public static void award(ServerPlayer p, String id) {
        var advancement = p.server.getAdvancements().getAdvancement(new ResourceLocation(IC2NewEra.MOD_ID, "newera/" + id));
        if (advancement != null) p.getAdvancements().award(advancement, "event");
    }
}
