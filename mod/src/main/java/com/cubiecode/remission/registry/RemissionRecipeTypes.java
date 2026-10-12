package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.tech.MachineRecipe;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Recipe types and serializers: the machine recipes (P7.2), editable from datapacks and KubeJS. */
public final class RemissionRecipeTypes {
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, Remission.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Remission.MOD_ID);

    public static final Supplier<RecipeType<MachineRecipe>> ANALYZING = type(MachineKind.ANALYZER);
    public static final Supplier<RecipeType<MachineRecipe>> SEQUENCING = type(MachineKind.SEQUENCER);
    public static final Supplier<RecipeType<MachineRecipe>> SYNTHESIZING = type(MachineKind.SYNTHESIZER);
    public static final Supplier<RecipeSerializer<MachineRecipe>> ANALYZING_SERIALIZER = serializer(MachineKind.ANALYZER);
    public static final Supplier<RecipeSerializer<MachineRecipe>> SEQUENCING_SERIALIZER = serializer(MachineKind.SEQUENCER);
    public static final Supplier<RecipeSerializer<MachineRecipe>> SYNTHESIZING_SERIALIZER = serializer(MachineKind.SYNTHESIZER);

    private RemissionRecipeTypes() {
    }

    private static Supplier<RecipeType<MachineRecipe>> type(MachineKind kind) {
        return RECIPE_TYPES.register(kind.recipeName(), () -> RecipeType.simple(Remission.id(kind.recipeName())));
    }

    private static Supplier<RecipeSerializer<MachineRecipe>> serializer(MachineKind kind) {
        return RECIPE_SERIALIZERS.register(kind.recipeName(), () -> new MachineRecipe.Serializer(kind));
    }
}
