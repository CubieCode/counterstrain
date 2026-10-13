package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.purification.MendrootBlock;
import com.cubiecode.remission.purification.QuarantinePylonBlock;
import com.cubiecode.remission.purification.ReclaimerBlock;
import com.cubiecode.remission.world.FesteringPlantBlock;
import com.cubiecode.remission.world.FesteringVeinBlock;
import com.cubiecode.remission.world.FesteringVeins;
import com.cubiecode.remission.tech.MachineBlock;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.world.NidusBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
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
                    .noOcclusion()
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
    /** Grass block at festering stage 3. Keeps the snowy state of the grass it replaced; does not spread. */
    public static final DeferredBlock<SnowyDirtBlock> FESTERING_GRASS = BLOCKS.registerBlock("festering_grass", SnowyDirtBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(0.6F)
                    .sound(SoundType.GRASS));
    /**
     * Short grass, tall grass, ferns and large ferns at festering stage 2 (P4.16), one block for each, so each cleanses
     * back into the plant it was. Copies of the vanilla plants (replaceable, no collision, offset) with festering colour.
     */
    public static final DeferredBlock<FesteringPlantBlock> FESTERING_SHORT_GRASS = BLOCKS.registerBlock("festering_short_grass",
            FesteringPlantBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.SHORT_GRASS).mapColor(MapColor.COLOR_PURPLE));
    public static final DeferredBlock<DoublePlantBlock> FESTERING_TALL_GRASS = BLOCKS.registerBlock("festering_tall_grass",
            DoublePlantBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.TALL_GRASS).mapColor(MapColor.COLOR_PURPLE));
    public static final DeferredBlock<FesteringPlantBlock> FESTERING_FERN = BLOCKS.registerBlock("festering_fern",
            FesteringPlantBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.FERN).mapColor(MapColor.COLOR_PURPLE));
    public static final DeferredBlock<DoublePlantBlock> FESTERING_LARGE_FERN = BLOCKS.registerBlock("festering_large_fern",
            DoublePlantBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.LARGE_FERN).mapColor(MapColor.COLOR_PURPLE));
    /** Overworld stone near the surface at festering stage 3. */
    public static final DeferredBlock<Block> FESTERED_STONE = BLOCKS.registerSimpleBlock("festered_stone",
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).mapColor(MapColor.TERRACOTTA_PURPLE));
    /** Deepslate facing cave air at festering stage 3 (P4.6); a pillar with {@code axis} like deepslate. */
    public static final DeferredBlock<RotatedPillarBlock> FESTERED_DEEPSLATE = BLOCKS.registerBlock("festered_deepslate",
            RotatedPillarBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE).mapColor(MapColor.TERRACOTTA_PURPLE));

    /**
     * Festering veins (P4.8): a multiface growth on the solid faces of blocks in festering chunks, grown by
     * {@link FesteringVeins}. No collision and replaceable, like glow lichen; drops only with silk touch.
     */
    public static final DeferredBlock<FesteringVeinBlock> FESTERING_VEIN = BLOCKS.registerBlock("festering_vein",
            FesteringVeinBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .replaceable()
                    .noCollission()
                    .strength(0.2F)
                    .sound(SoundType.GLOW_LICHEN)
                    .pushReaction(PushReaction.DESTROY));
    // Machines (P7.2)
    public static final DeferredBlock<MachineBlock> ANALYZER = machine(MachineKind.ANALYZER);
    public static final DeferredBlock<MachineBlock> SEQUENCER = machine(MachineKind.SEQUENCER);
    public static final DeferredBlock<MachineBlock> SYNTHESIZER = machine(MachineKind.SYNTHESIZER);

    // Purification (P5.2)
    /** Wards the chunks around it: no spread, festering, veins or contact infection, and their land drains to remission. */
    public static final DeferredBlock<QuarantinePylonBlock> QUARANTINE_PYLON = BLOCKS.registerBlock("quarantine_pylon",
            QuarantinePylonBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 10)
                    .sound(SoundType.METAL)
                    .pushReaction(PushReaction.BLOCK));
    /** Cleansing plant: cleanses festered blocks around it and drains its chunk. Plantable on dirt, festering grass included. */
    public static final DeferredBlock<MendrootBlock> MENDROOT = BLOCKS.registerBlock("mendroot", MendrootBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT)
                    .noCollission()
                    .instabreak()
                    .sound(SoundType.GRASS)
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .pushReaction(PushReaction.DESTROY));

    // Reclaimers (P7.4)
    /** Drains the land around it, nearest chunks first, while fed energy and purifier fuel. Does not ward. */
    public static final DeferredBlock<ReclaimerBlock> RECLAIMER = BLOCKS.registerBlock("reclaimer", ReclaimerBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 10)
                    .sound(SoundType.METAL)
                    .pushReaction(PushReaction.BLOCK));

    private static DeferredBlock<MachineBlock> machine(MachineKind kind) {
        return BLOCKS.registerBlock(kind.blockName(), properties -> new MachineBlock(kind, properties),
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.METAL)
                        .strength(3.5F, 6.0F)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.METAL));
    }

    private RemissionBlocks() {
    }
}
