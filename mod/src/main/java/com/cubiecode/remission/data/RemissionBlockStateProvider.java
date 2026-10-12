package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionBlocks;
import net.minecraft.core.Direction;
import com.cubiecode.remission.tech.MachineKind;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.MultifaceBlock;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;
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
        // Machines (P7.2): a shared casing with each machine's own front, facing the player who placed it.
        for (MachineKind kind : MachineKind.values()) {
            ModelFile model = models().orientable(kind.blockName(), modLoc("block/machine_side"),
                    modLoc("block/" + kind.blockName() + "_front"), modLoc("block/machine_top"));
            horizontalBlock(kind.block(), model);
            simpleBlockItem(kind.block(), model);
        }
    }
}
