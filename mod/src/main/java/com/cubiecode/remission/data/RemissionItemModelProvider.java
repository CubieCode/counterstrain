package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class RemissionItemModelProvider extends ItemModelProvider {
    public RemissionItemModelProvider(PackOutput output, ExistingFileHelper files) {
        super(output, Remission.MOD_ID, files);
    }

    @Override
    protected void registerModels() {
    }
}
