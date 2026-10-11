package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionBlocks;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class RemissionBlockStateProvider extends BlockStateProvider {
    public RemissionBlockStateProvider(PackOutput output, ExistingFileHelper files) {
        super(output, Remission.MOD_ID, files);
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlockWithItem(RemissionBlocks.NIDUS.get(), cubeAll(RemissionBlocks.NIDUS.get()));
        // Fluid blocks render through the fluid renderer; the model only supplies the break particle.
        simpleBlock(RemissionBlocks.MIASMA.get(), models().getBuilder("miasma").texture("particle", modLoc("block/miasma_still")));

        // Festering stages (P4.2). Untinted: festering colour is in the textures, not the biome.
        simpleBlockWithItem(RemissionBlocks.FESTERING_GRASS.get(), models().cubeBottomTop("festering_grass",
                modLoc("block/festering_grass_side"), mcLoc("block/dirt"), modLoc("block/festering_grass_top")));
        logBlock(RemissionBlocks.FESTERING_LOG.get());
        simpleBlockItem(RemissionBlocks.FESTERING_LOG.get(), models().getExistingFile(modLoc("block/festering_log")));
        simpleBlockWithItem(RemissionBlocks.FESTERING_LEAVES.get(), models().cubeAll("festering_leaves",
                modLoc("block/festering_leaves")).renderType("cutout_mipped"));
        simpleBlockWithItem(RemissionBlocks.FESTERED_STONE.get(), cubeAll(RemissionBlocks.FESTERED_STONE.get()));
    }
}
