package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.world.NidusBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
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

    // Festering stages (P4.2): what land in a festering chunk turns into (data map remission:festers_into). They never
    // spread or revert on their own; the chunk's Strain level drives them.
    /** Grass block at festering stage 1. Keeps the snowy state of the grass it replaced; does not spread. */
    public static final DeferredBlock<SnowyDirtBlock> FESTERING_GRASS = BLOCKS.registerBlock("festering_grass", SnowyDirtBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(0.6F)
                    .sound(SoundType.GRASS));
    /** Logs and wood at festering stage 2. In {@code #minecraft:logs}, so festering leaves around it do not decay. */
    public static final DeferredBlock<RotatedPillarBlock> FESTERING_LOG = BLOCKS.registerBlock("festering_log", RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG).mapColor(MapColor.COLOR_GRAY));
    /** Leaves at festering stage 2; decay like vanilla leaves. */
    public static final DeferredBlock<LeavesBlock> FESTERING_LEAVES = BLOCKS.registerBlock("festering_leaves", LeavesBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES).mapColor(MapColor.COLOR_PURPLE));
    /** Overworld stone near the surface at festering stage 3. */
    public static final DeferredBlock<Block> FESTERED_STONE = BLOCKS.registerSimpleBlock("festered_stone",
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).mapColor(MapColor.TERRACOTTA_PURPLE));
    /** Deepslate facing cave air at festering stage 3 (P4.6); a pillar with {@code axis} like deepslate. */
    public static final DeferredBlock<RotatedPillarBlock> FESTERED_DEEPSLATE = BLOCKS.registerBlock("festered_deepslate",
            RotatedPillarBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE).mapColor(MapColor.TERRACOTTA_PURPLE));

    private RemissionBlocks() {
    }
}
