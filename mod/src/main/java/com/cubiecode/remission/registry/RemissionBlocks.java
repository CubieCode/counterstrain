package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.NidusBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block registrations. */
public final class RemissionBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Remission.MOD_ID);

    /** Source of the Strain (P4.1). Pistons cannot move it, so the index never goes stale through a push. */
    public static final DeferredBlock<NidusBlock> NIDUS = BLOCKS.registerBlock("nidus", NidusBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.SCULK_CATALYST)
                    .pushReaction(PushReaction.BLOCK));

    /** Miasma in the world (P7.1). */
    public static final DeferredBlock<LiquidBlock> MIASMA = BLOCKS.registerBlock("miasma",
            properties -> new LiquidBlock(RemissionFluids.MIASMA.get(), properties),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .replaceable()
                    .noCollission()
                    .strength(100.0F)
                    .pushReaction(PushReaction.DESTROY)
                    .noLootTable()
                    .liquid()
                    .sound(SoundType.EMPTY));

    private RemissionBlocks() {
    }
}
