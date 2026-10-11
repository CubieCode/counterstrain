package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Recipe types and serializers (planned: analyzer, sequencer, synthesizer; editable from KubeJS). */
public final class RemissionRecipeTypes {
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, Remission.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Remission.MOD_ID);

    private RemissionRecipeTypes() {
    }
}
