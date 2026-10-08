package com.ic2newera.block;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Stone-based ore blocks; depth is controlled by placement data, not block variants. */
public final class ModMineralBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IC2NewEra.MOD_ID);

    public static final RegistryObject<Block> NICKEL_ORE = ore("nickel_ore");
    public static final RegistryObject<Block> CHROMIUM_ORE = ore("chromium_ore");
    public static final RegistryObject<Block> TITANIUM_ORE = ore("titanium_ore");
    public static final RegistryObject<Block> TUNGSTEN_ORE = ore("tungsten_ore");

    private ModMineralBlocks() { }

    private static RegistryObject<Block> ore(String id) {
        return BLOCKS.register(id, () -> new Block(Block.Properties.copy(Blocks.IRON_ORE)));
    }
}
