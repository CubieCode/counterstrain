package com.cubiecode.remission.tech;

import com.cubiecode.remission.registry.RemissionBlockEntities;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionMenus;
import com.cubiecode.remission.registry.RemissionRecipeTypes;
import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The three tech chain machines (P7.2). They share one block, block entity, menu and recipe class and differ only in
 * their recipe type and how many input slots they have.
 */
public enum MachineKind implements StringRepresentable {
    ANALYZER("analyzer", "analyzing", 1),
    SEQUENCER("sequencer", "sequencing", 2),
    SYNTHESIZER("synthesizer", "synthesizing", 3);

    public static final Codec<MachineKind> CODEC = StringRepresentable.fromEnum(MachineKind::values);

    /** Output slots of every machine; a recipe has at most this many item results. */
    public static final int OUTPUT_SLOTS = 2;
    /** Capacity of each tank (input and output), in mB. */
    public static final int TANK_CAPACITY = 4000;
    /** Energy buffer, in FE. */
    public static final int ENERGY_CAPACITY = 20000;
    /** Most energy the machine accepts per call (per tick from most cables), in FE. */
    public static final int MAX_ENERGY_INPUT = 1000;

    private final String blockName;
    private final String recipeName;
    private final int inputSlots;

    MachineKind(String blockName, String recipeName, int inputSlots) {
        this.blockName = blockName;
        this.recipeName = recipeName;
        this.inputSlots = inputSlots;
    }

    /** Block, item, block entity and menu path, e.g. {@code analyzer}. */
    public String blockName() {
        return blockName;
    }

    /** Recipe type and serializer path, e.g. {@code analyzing}. */
    public String recipeName() {
        return recipeName;
    }

    @Override
    public String getSerializedName() {
        return blockName;
    }

    public int inputSlots() {
        return inputSlots;
    }

    public int slots() {
        return inputSlots + OUTPUT_SLOTS;
    }

    public RecipeType<MachineRecipe> recipeType() {
        return switch (this) {
            case ANALYZER -> RemissionRecipeTypes.ANALYZING.get();
            case SEQUENCER -> RemissionRecipeTypes.SEQUENCING.get();
            case SYNTHESIZER -> RemissionRecipeTypes.SYNTHESIZING.get();
        };
    }

    public RecipeSerializer<MachineRecipe> serializer() {
        return switch (this) {
            case ANALYZER -> RemissionRecipeTypes.ANALYZING_SERIALIZER.get();
            case SEQUENCER -> RemissionRecipeTypes.SEQUENCING_SERIALIZER.get();
            case SYNTHESIZER -> RemissionRecipeTypes.SYNTHESIZING_SERIALIZER.get();
        };
    }

    public MachineBlock block() {
        return switch (this) {
            case ANALYZER -> RemissionBlocks.ANALYZER.get();
            case SEQUENCER -> RemissionBlocks.SEQUENCER.get();
            case SYNTHESIZER -> RemissionBlocks.SYNTHESIZER.get();
        };
    }

    public BlockEntityType<MachineBlockEntity> blockEntityType() {
        return switch (this) {
            case ANALYZER -> RemissionBlockEntities.ANALYZER.get();
            case SEQUENCER -> RemissionBlockEntities.SEQUENCER.get();
            case SYNTHESIZER -> RemissionBlockEntities.SYNTHESIZER.get();
        };
    }

    public MenuType<MachineMenu> menuType() {
        return switch (this) {
            case ANALYZER -> RemissionMenus.ANALYZER.get();
            case SEQUENCER -> RemissionMenus.SEQUENCER.get();
            case SYNTHESIZER -> RemissionMenus.SYNTHESIZER.get();
        };
    }
}
