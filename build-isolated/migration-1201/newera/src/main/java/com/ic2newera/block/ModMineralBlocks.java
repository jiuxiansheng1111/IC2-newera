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
    public static final RegistryObject<Block> SILVER_ORE = ore("silver_ore", Blocks.IRON_ORE);
    public static final RegistryObject<Block> DEEPSLATE_SILVER_ORE = ore("deepslate_silver_ore", Blocks.DEEPSLATE_IRON_ORE);
    public static final RegistryObject<Block> SILVER_ORE_NETHER = ore("silver_ore_nether", Blocks.NETHER_GOLD_ORE);
    public static final RegistryObject<Block> ALUMINIUM_ORE_NETHER = ore("aluminium_ore_nether", Blocks.NETHER_GOLD_ORE);
    public static final RegistryObject<Block> BRONZE_DUST_BLOCK = BLOCKS.register("bronze_dust_block",
            () -> new Block(Block.Properties.copy(Blocks.COPPER_BLOCK).strength(3.0F, 6.0F).requiresCorrectToolForDrops()));

    private ModMineralBlocks() { }

    private static RegistryObject<Block> ore(String id) {
        return ore(id, Blocks.IRON_ORE);
    }

    private static RegistryObject<Block> ore(String id, Block base) {
        return BLOCKS.register(id, () -> new Block(Block.Properties.copy(base).requiresCorrectToolForDrops()));
    }
}
