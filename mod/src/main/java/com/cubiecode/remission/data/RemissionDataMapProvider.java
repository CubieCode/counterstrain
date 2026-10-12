package com.cubiecode.remission.data;

import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.FesterMaps;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.data.DataMapProvider;

/**
 * Remission's default {@code festers_into} entries: grass at stage 1, trees and foliage at stage 2, stone and deepslate
 * (with tuff) at stage 3. Every entry also festers in caves from stage 3 (the default {@code underground_stage}). No {@code fester_variant} entries: every biome flips to {@code remission:fester} unless a pack adds one.
 */
public final class RemissionDataMapProvider extends DataMapProvider {
    public RemissionDataMapProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup);
    }

    @Override
    protected void gather(HolderLookup.Provider provider) {
        builder(FesterMaps.FESTERS_INTO)
                .add(Blocks.GRASS_BLOCK.builtInRegistryHolder(), festers(RemissionBlocks.FESTERING_GRASS.get(), 1), false)
                .add(BlockTags.LOGS, festers(RemissionBlocks.FESTERING_LOG.get(), 2), false)
                .add(BlockTags.LEAVES, festers(RemissionBlocks.FESTERING_LEAVES.get(), 2), false)
                // Two disjoint ore-replaceable tags (P4.6) instead of #minecraft:base_stone_overworld, which holds both
                // and could not be overridden for deepslate in the same file.
                .add(BlockTags.STONE_ORE_REPLACEABLES, festers(RemissionBlocks.FESTERED_STONE.get(), 3), false)
                .add(BlockTags.DEEPSLATE_ORE_REPLACEABLES, festers(RemissionBlocks.FESTERED_DEEPSLATE.get(), 3), false);
        // Purification (P5.2): each festering block turns back into the most common block that festers into it. The
        // source is not recorded, so a festered birch log cleanses into an oak log and festered tuff into deepslate.
        builder(Cleansing.CLEANSES_INTO)
                .add(RemissionBlocks.FESTERING_GRASS, cleanses(Blocks.GRASS_BLOCK), false)
                .add(RemissionBlocks.FESTERING_LOG, cleanses(Blocks.OAK_LOG), false)
                .add(RemissionBlocks.FESTERING_LEAVES, cleanses(Blocks.OAK_LEAVES), false)
                .add(RemissionBlocks.FESTERED_STONE, cleanses(Blocks.STONE), false)
                .add(RemissionBlocks.FESTERED_DEEPSLATE, cleanses(Blocks.DEEPSLATE), false);
    }

    private static Cleansing.CleanseBlock cleanses(Block block) {
        return new Cleansing.CleanseBlock(block);
    }

    private static FesterMaps.FesterBlock festers(Block block, int stage) {
        return new FesterMaps.FesterBlock(block, stage);
    }
}
