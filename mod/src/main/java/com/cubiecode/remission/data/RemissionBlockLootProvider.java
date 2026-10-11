package com.cubiecode.remission.data;

import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
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
        // A broken nidus drops its core (the sample item), never itself, so a nidus cannot be carried and replanted.
        add(RemissionBlocks.NIDUS.get(), createSingleItemTable(RemissionItems.NIDUS_CORE.get()));
    }

    // Every Remission block must have a loot table; datagen fails if one is missing.
    @Override
    protected Iterable<Block> getKnownBlocks() {
        return RemissionBlocks.BLOCKS.getEntries().stream().<Block>map(DeferredHolder::get).toList();
    }
}
