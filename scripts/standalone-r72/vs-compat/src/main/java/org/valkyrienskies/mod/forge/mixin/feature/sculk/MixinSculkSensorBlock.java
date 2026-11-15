package org.valkyrienskies.mod.forge.mixin.feature.sculk;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.forge.common.ShipVibrationUser;

@Mixin(SculkSensorBlock.class)
public abstract class MixinSculkSensorBlock {
    @WrapOperation(method = "m_279962_", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/gameevent/vibrations/VibrationSystem$Ticker;m_280259_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/gameevent/vibrations/VibrationSystem$Data;Lnet/minecraft/world/level/gameevent/vibrations/VibrationSystem$User;)V"))
    private static void tickWithShipUser(Level level, VibrationSystem.Data data, VibrationSystem.User user,
            Operation<Void> original, Level enclosingLevel, BlockPos pos) {
        original.call(level, data, ShipVibrationUser.wrap(level, pos, user));
    }
}

