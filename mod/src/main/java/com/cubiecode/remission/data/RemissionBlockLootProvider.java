package com.cubiecode.remission.data;

import com.cubiecode.remission.registry.RemissionBlocks;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class RemissionBlockLootProvider extends BlockLootSubProvider {
    public RemissionBlockLootProvider(HolderLookup.Provider lookup) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), lookup);
    }

    @Override
    protected void generate() {
        // A nidus drops nothing: nidus cores (the sample item) come with the research items.
        add(RemissionBlocks.NIDUS.get(), noDrop());
    }

    // Every Remission block must have a loot table; datagen fails if one is missing.
    @Override
    protected Iterable<Block> getKnownBlocks() {
        return RemissionBlocks.BLOCKS.getEntries().stream().<Block>map(DeferredHolder::get).toList();
    }
}
