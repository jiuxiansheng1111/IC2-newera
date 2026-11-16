package com.ic2newera.compat.xaero.mixin;

import com.ic2newera.client.robot.FieldRouteScreen;
import com.ic2newera.compat.xaero.XaeroRouteMapOverlay;
import com.ic2newera.field.FieldRoutes;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import xaero.common.graphics.CustomVertexConsumers;
import xaero.common.minimap.MinimapProcessor;
import xaero.common.minimap.render.MinimapSafeModeRenderer;
import xaero.common.settings.ModSettings;
import xaero.hud.entity.EntityUtils;
import xaero.hud.minimap.module.MinimapSession;

@Pseudo
@Mixin(targets = "xaero.common.minimap.render.MinimapSafeModeRenderer", remap = false)
public abstract class XaeroSafeModeRouteMixin {
    @Unique private ByteBuffer ic2newera$routePixels;

    // Only the argument to the GL upload changes. Xaero keeps its own clean terrain and radar buffer.
    @ModifyExpressionValue(method = "renderChunks", at = @At(value = "FIELD",
            target = "Lxaero/common/graphics/MinimapTexture;buffer:Ljava/nio/ByteBuffer;"), remap = false)
    private ByteBuffer ic2newera$overlayRoute(ByteBuffer original, MinimapSession session, GuiGraphics graphics,
            MinimapProcessor processor, Vec3 mapCenter, ResourceKey<Level> dimension, double dimensionScale,
            int mapSize, int bufferSize, float sizeFix, float partial, int lightLevel, boolean useWorldMap,
            boolean northLocked, int shape, double sin, double cos, boolean caveMode, boolean circle,
            ModSettings settings, CustomVertexConsumers consumers) {
        FieldRoutes.Snapshot route = FieldRouteScreen.currentRoute();
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null || original == null || !route.active() || route.points().size() < 2
                || !route.dimension().equals(camera.level().dimension().location().toString())) return original;
        int length = bufferSize * bufferSize * 3;
        if (bufferSize < 1 || bufferSize > 2048 || mapSize < 1 || mapSize > bufferSize
                || original.remaining() < length) return original;
        if (ic2newera$routePixels == null || ic2newera$routePixels.capacity() != length)
            ic2newera$routePixels = ByteBuffer.allocateDirect(length);
        var source = original.duplicate();
        source.limit(source.position() + length);
        ic2newera$routePixels.clear();
        ic2newera$routePixels.put(source).flip();
        XaeroRouteMapOverlay.drawSafeMode(ic2newera$routePixels, route, bufferSize, mapSize,
                EntityUtils.getEntityX(camera, partial), EntityUtils.getEntityZ(camera, partial),
                sin, cos, ((MinimapSafeModeRenderer)(Object)this).getZoom());
        return ic2newera$routePixels;
    }
}
