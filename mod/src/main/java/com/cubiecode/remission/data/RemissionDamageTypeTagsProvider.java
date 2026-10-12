package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.viralload.ViralLoad;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/** Damage type tags: viral load damage ignores armour (P5.1). */
public final class RemissionDamageTypeTagsProvider extends TagsProvider<DamageType> {
    public RemissionDamageTypeTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
                                           ExistingFileHelper files) {
        super(output, Registries.DAMAGE_TYPE, lookup, Remission.MOD_ID, files);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(DamageTypeTags.BYPASSES_ARMOR).add(ViralLoad.DAMAGE_TYPE);
    }
}
