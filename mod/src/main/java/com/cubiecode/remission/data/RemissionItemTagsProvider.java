package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.purification.HandheldPurifierItem;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.ScanItem;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class RemissionItemTagsProvider extends ItemTagsProvider {
    public RemissionItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
            CompletableFuture<TagsProvider.TagLookup<Block>> blockTags, ExistingFileHelper files) {
        super(output, lookup, blockTags, Remission.MOD_ID, files);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // Machine recipes (P7.2): either kind of scan.
        tag(ScanItem.SCANS).add(RemissionItems.HOST_SCAN.get(), RemissionItems.BLOCK_SCAN.get());
        // Purifier fuel (P7.3): repairs the handheld purifier; the reclaimer's fuel (P7.4).
        tag(HandheldPurifierItem.FUEL).add(RemissionItems.PURIFIER_FUEL.get());
    }
}
