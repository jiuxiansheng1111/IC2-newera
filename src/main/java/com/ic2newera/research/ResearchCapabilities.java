package com.ic2newera.research;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

public final class ResearchCapabilities {
    public static final Capability<PlayerResearchData> PLAYER_RESEARCH =
            CapabilityManager.get(new CapabilityToken<>() {});

    private ResearchCapabilities() {}
}
