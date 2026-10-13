package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class RemissionItemModelProvider extends ItemModelProvider {
    public RemissionItemModelProvider(PackOutput output, ExistingFileHelper files) {
        super(output, Remission.MOD_ID, files);
    }

    @Override
    protected void registerModels() {
        basicItem(RemissionItems.SCANNER.get());
        basicItem(RemissionItems.HOST_SCAN.get());
        basicItem(RemissionItems.BLOCK_SCAN.get());
        basicItem(RemissionItems.HOST_TISSUE.get());
        basicItem(RemissionItems.MIASMA_BUCKET.get());
        basicItem(RemissionItems.NIDUS_CORE.get());
        basicItem(RemissionItems.FESTERED_SOIL.get());
        // Festering veins (P4.8): a flat item from the block texture, as sculk veins.
        withExistingParent("festering_vein", mcLoc("item/generated")).texture("layer0", modLoc("block/festering_vein"));
        basicItem(RemissionItems.STRAIN_SEQUENCE.get());
        // Purification (P5.2): mendroot's item is its flat block texture, as saplings.
        basicItem(RemissionItems.HANDHELD_PURIFIER.get());
        withExistingParent("mendroot", mcLoc("item/generated")).texture("layer0", modLoc("block/mendroot"));
        // Countermeasures (P5.7)
        basicItem(RemissionItems.COUNTERMEASURE_PROTOCOL.get());
        // Synthesizer products (P7.3)
        basicItem(RemissionItems.SERUM.get());
        basicItem(RemissionItems.PURIFIER_FUEL.get());
        basicItem(RemissionItems.COUNTERSTRAIN_PROTOTYPE.get());
    }
}
