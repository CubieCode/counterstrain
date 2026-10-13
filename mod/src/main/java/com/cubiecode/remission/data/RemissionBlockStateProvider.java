package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.tech.MachineKind;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelBuilder;
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

        // Festering stages (P4.2). Festering colour is in the textures, not the biome; every festering model has tint index
        // 0 on every face, which is white except in a variant's land (P7.5, client/VariantTints).
        ModelFile tintedCube = tintedCube("tinted_cube", false);
        ModelFile tintedCubeHorizontal = tintedCube("tinted_cube_horizontal", true);
        simpleBlockWithItem(RemissionBlocks.FESTERING_GRASS.get(), models().getBuilder("festering_grass").parent(tintedCube)
                .texture("particle", modLoc("block/festering_grass_side"))
                .texture("down", mcLoc("block/dirt"))
                .texture("up", modLoc("block/festering_grass_top"))
                .texture("north", modLoc("block/festering_grass_side")).texture("south", modLoc("block/festering_grass_side"))
                .texture("west", modLoc("block/festering_grass_side")).texture("east", modLoc("block/festering_grass_side")));
        // Festering plants (P4.16): tinted cross models like the vanilla plants; tall ones a cross per half.
        simpleBlock(RemissionBlocks.FESTERING_SHORT_GRASS.get(), tintedCross("festering_short_grass"));
        simpleBlock(RemissionBlocks.FESTERING_FERN.get(), tintedCross("festering_fern"));
        doublePlant(RemissionBlocks.FESTERING_TALL_GRASS.get(), "festering_tall_grass");
        doublePlant(RemissionBlocks.FESTERING_LARGE_FERN.get(), "festering_large_fern");
        simpleBlockWithItem(RemissionBlocks.FESTERED_STONE.get(), column("festered_stone", tintedCube,
                modLoc("block/festered_stone"), modLoc("block/festered_stone")));
        // Cave festering (P4.6).
        ModelFile deepslate = column("festered_deepslate", tintedCube,
                modLoc("block/festered_deepslate"), modLoc("block/festered_deepslate_top"));
        axisBlock(RemissionBlocks.FESTERED_DEEPSLATE.get(), deepslate, column("festered_deepslate_horizontal", tintedCubeHorizontal,
                modLoc("block/festered_deepslate"), modLoc("block/festered_deepslate_top")));
        simpleBlockItem(RemissionBlocks.FESTERED_DEEPSLATE.get(), deepslate);
        // Festering veins (P4.8): one flat face model, rotated onto each face the state covers, as vanilla sculk veins.
        ModelFile vein = models().getBuilder("festering_vein")
                .ao(false)
                .renderType("cutout")
                .texture("particle", modLoc("block/festering_vein"))
                .texture("vein", modLoc("block/festering_vein"))
                .element().from(0, 0, 0.1F).to(16, 16, 0.1F)
                .face(Direction.NORTH).uvs(0, 0, 16, 16).texture("#vein").tintindex(0).end()
                .face(Direction.SOUTH).uvs(0, 0, 16, 16).texture("#vein").tintindex(0).end()
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

    /** A two-block plant: {@code <name>_bottom} and {@code <name>_top} tinted cross models on its lower and upper half. */
    private void doublePlant(DoublePlantBlock block, String name) {
        ModelFile lower = tintedCross(name + "_bottom");
        ModelFile upper = tintedCross(name + "_top");
        getVariantBuilder(block).forAllStates(state -> ConfiguredModel.builder()
                .modelFile(state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? lower : upper).build());
    }

    /** Vanilla's tinted cross with {@code block/<name>}, cut out. */
    private ModelFile tintedCross(String name) {
        return models().singleTexture(name, mcLoc("block/tinted_cross"), "cross", modLoc("block/" + name)).renderType("cutout");
    }

    /**
     * A full cube with tint index 0 on every face and a texture per face ({@code #down} ... {@code #east}), as vanilla's
     * {@code cube} (or, {@code horizontal}, {@code cube_column_horizontal}, whose top is turned) has none.
     */
    private ModelFile tintedCube(String name, boolean horizontal) {
        var element = models().getBuilder(name).parent(models().getExistingFile(mcLoc("block/block"))).element();
        for (Direction direction : Direction.values()) {
            var face = element.face(direction).texture("#" + direction.getSerializedName()).cullface(direction).tintindex(0);
            if (horizontal && direction == Direction.UP) {
                face.rotation(ModelBuilder.FaceRotation.UPSIDE_DOWN);
            }
            face.end();
        }
        return element.end();
    }

    /** A {@code parent} tinted cube with {@code side} around and {@code end} on top and bottom. */
    private ModelFile column(String name, ModelFile parent, ResourceLocation side, ResourceLocation end) {
        return models().getBuilder(name).parent(parent)
                .texture("particle", side).texture("down", end).texture("up", end)
                .texture("north", side).texture("south", side).texture("west", side).texture("east", side);
    }
}
