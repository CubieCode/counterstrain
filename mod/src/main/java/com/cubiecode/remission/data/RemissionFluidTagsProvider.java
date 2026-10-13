package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.purification.ReclaimerBlockEntity;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.FluidTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class RemissionFluidTagsProvider extends FluidTagsProvider {
    public RemissionFluidTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
        super(output, lookup, Remission.MOD_ID, files);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // Reclaimer fuel fluids (P7.4): empty, so the tag exists for packs to fill (a Mekanism or Create fluid).
        tag(ReclaimerBlockEntity.FLUID_FUEL);
    }
}
