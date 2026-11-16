package com.ic2newera.compat.vs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.valkyrienskies.core.impl.game.ships.ShipDataCommon;
import org.valkyrienskies.core.impl.game.ships.ShipObjectClientWorld;
import org.valkyrienskies.core.impl.shadow.EO;
import org.valkyrienskies.core.impl.shadow.Er;

/**
 * Coalesces only duplicate ship-create records for client ships that are already loaded.
 * The base VS 2.4.11 duplicate branch updates Er.b and then throws, aborting the rest of
 * that create batch. This applies the same kinematics update, removes that redundant record
 * from the iterator input, and lets all other ship records pass through the original handler.
 */
@Pseudo
@Mixin(targets = "org.valkyrienskies.core.impl.shadow.EO$e", remap = false)
public abstract class MixinDuplicateShipCreateIdempotence {
    @Unique private static final Logger NEWERA_VS_LOG = LogManager.getLogger("ic2newera-vs-respawn-compat");
    @Unique private static final AtomicBoolean NEWERA_VS_FIRST_DUPLICATE_LOGGED = new AtomicBoolean();

    @Shadow(remap = false) private EO c;

    @ModifyExpressionValue(
            method = "invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/valkyrienskies/core/impl/networking/impl/PacketShipDataCreate;getToCreate()Ljava/util/List;",
                    remap = false),
            remap = false)
    private List<ShipDataCommon> ic2newera$coalesceLoadedShipCreates(List<ShipDataCommon> packetShips) {
        if (packetShips == null || packetShips.isEmpty()) return packetShips;

        ShipObjectClientWorld clientShips = ((MixinVSClientShipWorldAccess) (Object) this.c)
                .ic2newera$getClientShipWorld();
        Map<Long, Er> loadedById = clientShips.getShipObjects();
        LinkedHashMap<String, ShipDataCommon> newShips = new LinkedHashMap<>();
        int coalesced = 0;

        for (ShipDataCommon incoming : packetShips) {
            long id = incoming.getId();
            Er loaded = loadedById.get(id);
            if (loaded != null
                    && Objects.equals(loaded.getChunkClaimDimension(), incoming.getChunkClaimDimension())) {
                // This is the same live-state write performed by the exact 2.4.11 duplicate branch
                // immediately before it throws. Keep the existing client Ship object and its indexes.
                loaded.a(incoming.getKinematics());
                coalesced++;
                continue;
            }

            // A malformed/repeated ID inside one packet must not make the second record abort
            // creation of unrelated ships. Keep the last authoritative record for that ship.
            String key = incoming.getChunkClaimDimension() + '\u0000' + id;
            newShips.put(key, incoming);
        }

        if (coalesced == 0 && newShips.size() == packetShips.size()) return packetShips;
        if (coalesced > 0 && NEWERA_VS_FIRST_DUPLICATE_LOGGED.compareAndSet(false, true)) {
            NEWERA_VS_LOG.info(
                    "Handled first duplicate VS ship-create sync: coalesced {} loaded record(s); passed {} other ship record(s) to the native handler",
                    coalesced, newShips.size());
        }
        return new ArrayList<>(newShips.values());
    }
}
