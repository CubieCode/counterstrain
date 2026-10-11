package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.loot.IsFesteringCondition;
import com.cubiecode.remission.registry.RemissionBlocks;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class RemissionBlockTagsProvider extends BlockTagsProvider {
    public RemissionBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
        super(output, lookup, Remission.MOD_ID, files);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(BlockTags.MINEABLE_WITH_HOE).add(RemissionBlocks.NIDUS.get(), RemissionBlocks.FESTERING_LEAVES.get());
        tag(IsFesteringCondition.FESTERED_SOIL_SOURCES).addTag(BlockTags.DIRT);
        // Festering stages (P4.2) behave like the blocks they replaced: plants stay on festering grass, leaves around a
        // festering log do not decay.
        tag(BlockTags.MINEABLE_WITH_SHOVEL).add(RemissionBlocks.FESTERING_GRASS.get());
        tag(BlockTags.MINEABLE_WITH_AXE).add(RemissionBlocks.FESTERING_LOG.get());
        tag(BlockTags.MINEABLE_WITH_PICKAXE).add(RemissionBlocks.FESTERED_STONE.get());
        tag(BlockTags.DIRT).add(RemissionBlocks.FESTERING_GRASS.get());
        tag(BlockTags.LOGS).add(RemissionBlocks.FESTERING_LOG.get());
        tag(BlockTags.LEAVES).add(RemissionBlocks.FESTERING_LEAVES.get());
    }
}
