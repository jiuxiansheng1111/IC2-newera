package com.ic2newera.compat.xaero.mixin;

import com.ic2newera.compat.xaero.XaeroRouteMapOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;

/** Optional integration with Xaero 26.6.0's in-minimap element layer. */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler", remap = false)
public abstract class XaeroRouteMapLayerMixin {
    @Shadow(remap = false) private double ps;
    @Shadow(remap = false) private double pc;
    @Shadow(remap = false) private double zoom;
    @Shadow(remap = false) private float halfWView;

    @Inject(method = "beforeRender", at = @At("TAIL"), remap = false, require = 1)
    private void ic2newera$drawNavigationRoute(GuiGraphics graphics, MinimapElementRenderInfo info,
                                               MultiBufferSource.BufferSource buffers, CallbackInfo ci) {
        XaeroRouteMapOverlay.draw(graphics, info, ps, pc, zoom, halfWView);
    }
}
