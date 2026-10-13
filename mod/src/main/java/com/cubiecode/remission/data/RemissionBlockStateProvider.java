package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionBlocks;
import net.minecraft.core.Direction;
import com.cubiecode.remission.tech.MachineKind;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class RemissionBlockStateProvider extends BlockStateProvider {
    public RemissionBlockStateProvider(PackOutput output, ExistingFileHelper files) {
        super(output, Remission.MOD_ID, files);
    }

    @Override
    protected void registerStatesAndModels() {
        // The nidus's block entity renderer draws it at its health stage (client/NidusRenderer); the cube model only
        // supplies its break particles and the item, for every damage state.
        ModelFile nidus = cubeAll(RemissionBlocks.NIDUS.get());
        getVariantBuilder(RemissionBlocks.NIDUS.get()).forAllStates(state -> ConfiguredModel.builder().modelFile(nidus).build());
        simpleBlockItem(RemissionBlocks.NIDUS.get(), nidus);
        // Fluid blocks render through the fluid renderer; the model only supplies the break particle.
        simpleBlock(RemissionBlocks.MIASMA.get(), models().getBuilder("miasma").texture("particle", modLoc("block/miasma_still")));

        // Festering stages (P4.2). Untinted: festering colour is in the textures, not the biome.
        simpleBlockWithItem(RemissionBlocks.FESTERING_GRASS.get(), models().cubeBottomTop("festering_grass",
                modLoc("block/festering_grass_side"), mcLoc("block/dirt"), modLoc("block/festering_grass_top")));
        // Festering plants (P4.16): cross models like the vanilla plants, untinted; tall ones a cross per half.
        simpleBlock(RemissionBlocks.FESTERING_SHORT_GRASS.get(), models().cross("festering_short_grass",
                modLoc("block/festering_short_grass")).renderType("cutout"));
        simpleBlock(RemissionBlocks.FESTERING_FERN.get(), models().cross("festering_fern",
                modLoc("block/festering_fern")).renderType("cutout"));
        doublePlant(RemissionBlocks.FESTERING_TALL_GRASS.get(), "festering_tall_grass");
        doublePlant(RemissionBlocks.FESTERING_LARGE_FERN.get(), "festering_large_fern");
        simpleBlockWithItem(RemissionBlocks.FESTERED_STONE.get(), cubeAll(RemissionBlocks.FESTERED_STONE.get()));
        // Cave festering (P4.6).
        axisBlock(RemissionBlocks.FESTERED_DEEPSLATE.get(), modLoc("block/festered_deepslate"), modLoc("block/festered_deepslate_top"));
        simpleBlockItem(RemissionBlocks.FESTERED_DEEPSLATE.get(), models().getExistingFile(modLoc("block/festered_deepslate")));
        // Festering veins (P4.8): one flat face model, rotated onto each face the state covers, as vanilla sculk veins.
        ModelFile vein = models().getBuilder("festering_vein")
                .ao(false)
                .renderType("cutout")
                .texture("particle", modLoc("block/festering_vein"))
                .texture("vein", modLoc("block/festering_vein"))
                .element().from(0, 0, 0.1F).to(16, 16, 0.1F)
                .face(Direction.NORTH).uvs(0, 0, 16, 16).texture("#vein").end()
                .face(Direction.SOUTH).uvs(0, 0, 16, 16).texture("#vein").end()
                .end();
        MultiPartBlockStateBuilder veins = getMultipartBuilder(RemissionBlocks.FESTERING_VEIN.get());
        veins.part().modelFile(vein).addModel().condition(MultifaceBlock.getFaceProperty(Direction.NORTH), true);
        veins.part().modelFile(vein).rotationY(90).uvLock(true).addModel().condition(MultifaceBlock.getFaceProperty(Direction.EAST), true);
        veins.part().modelFile(vein).rotationY(180).uvLock(true).addModel().condition(MultifaceBlock.getFaceProperty(Direction.SOUTH), true);
        veins.part().modelFile(vein).rotationY(270).uvLock(true).addModel().condition(MultifaceBlock.getFaceProperty(Direction.WEST), true);
        veins.part().modelFile(vein).rotationX(270).uvLock(true).addModel().condition(MultifaceBlock.getFaceProperty(Direction.UP), true);
        veins.part().modelFile(vein).rotationX(90).uvLock(true).addModel().condition(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        // Purification (P5.2): the pylon is a column with its own top; mendroot is a cross plant like a sapling.
        simpleBlockWithItem(RemissionBlocks.QUARANTINE_PYLON.get(), models().cubeColumn("quarantine_pylon",
                modLoc("block/quarantine_pylon_side"), modLoc("block/quarantine_pylon_top")));
        simpleBlock(RemissionBlocks.MENDROOT.get(), models().cross("mendroot", modLoc("block/mendroot")).renderType("cutout"));
        // Reclaimers (P7.4): its own sides and emitter top on the machine casing's base.
        simpleBlockWithItem(RemissionBlocks.RECLAIMER.get(), models().cubeBottomTop("reclaimer",
                modLoc("block/reclaimer_side"), modLoc("block/machine_top"), modLoc("block/reclaimer_top")));
        // Machines (P7.2): a shared casing with each machine's own front, facing the player who placed it.
        for (MachineKind kind : MachineKind.values()) {
            ModelFile model = models().orientable(kind.blockName(), modLoc("block/machine_side"),
                    modLoc("block/" + kind.blockName() + "_front"), modLoc("block/machine_top"));
            horizontalBlock(kind.block(), model);
            simpleBlockItem(kind.block(), model);
        }
    }

    /** A two-block plant: {@code <name>_bottom} and {@code <name>_top} cross models on its lower and upper half. */
    private void doublePlant(DoublePlantBlock block, String name) {
        ModelFile lower = models().cross(name + "_bottom", modLoc("block/" + name + "_bottom")).renderType("cutout");
        ModelFile upper = models().cross(name + "_top", modLoc("block/" + name + "_top")).renderType("cutout");
        getVariantBuilder(block).forAllStates(state -> ConfiguredModel.builder()
                .modelFile(state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? lower : upper).build());
    }
}
