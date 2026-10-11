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
        tag(BlockTags.MINEABLE_WITH_HOE).add(RemissionBlocks.NIDUS.get());
        tag(IsFesteringCondition.FESTERED_SOIL_SOURCES).addTag(BlockTags.DIRT);
    }
}
