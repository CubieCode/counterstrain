package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.EntityTypeTagsProvider;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/** Entity type tags; contracts in the remission-data-formats skill. */
public final class RemissionEntityTypeTagsProvider extends EntityTypeTagsProvider {
    public RemissionEntityTypeTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
        super(output, lookup, Remission.MOD_ID, files);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // Bosses and the player never become hosts; players carry viral load instead (P5.1).
        tag(Corruption.IMMUNE).addTag(Tags.EntityTypes.BOSSES).add(EntityType.PLAYER);
    }
}
