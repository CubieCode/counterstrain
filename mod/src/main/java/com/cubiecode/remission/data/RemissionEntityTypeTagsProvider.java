package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.EntityTypeTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/** Entity type tags (planned: {@code #remission:immune}; see remission-data-formats skill). */
public final class RemissionEntityTypeTagsProvider extends EntityTypeTagsProvider {
    public RemissionEntityTypeTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
        super(output, lookup, Remission.MOD_ID, files);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
    }
}
