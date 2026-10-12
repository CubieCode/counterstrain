package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.tech.MachineRecipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/** Builds a machine recipe for datagen; saved as {@code remission:<recipe type>/<name>}. */
final class MachineRecipeBuilder {
    private final MachineKind kind;
    private final int energy;
    private final int time;
    private final List<SizedIngredient> ingredients = new ArrayList<>();
    private final List<ItemStack> results = new ArrayList<>();
    private Optional<SizedFluidIngredient> fluid = Optional.empty();
    private FluidStack fluidResult = FluidStack.EMPTY;

    private MachineRecipeBuilder(MachineKind kind, int energy, int time) {
        this.kind = kind;
        this.energy = energy;
        this.time = time;
    }

    static MachineRecipeBuilder of(MachineKind kind, int energy, int time) {
        return new MachineRecipeBuilder(kind, energy, time);
    }

    MachineRecipeBuilder ingredient(ItemLike item) {
        ingredients.add(SizedIngredient.of(item, 1));
        return this;
    }

    MachineRecipeBuilder ingredient(TagKey<Item> tag) {
        ingredients.add(SizedIngredient.of(tag, 1));
        return this;
    }

    MachineRecipeBuilder ingredient(ItemLike item, int count) {
        ingredients.add(SizedIngredient.of(item, count));
        return this;
    }

    MachineRecipeBuilder ingredient(TagKey<Item> tag, int count) {
        ingredients.add(SizedIngredient.of(tag, count));
        return this;
    }

    MachineRecipeBuilder fluid(Fluid fluid, int amount) {
        this.fluid = Optional.of(SizedFluidIngredient.of(fluid, amount));
        return this;
    }

    MachineRecipeBuilder result(ItemStack stack) {
        results.add(stack);
        return this;
    }

    MachineRecipeBuilder fluidResult(Fluid fluid, int amount) {
        this.fluidResult = new FluidStack(fluid, amount);
        return this;
    }

    void save(RecipeOutput output, String name) {
        output.accept(Remission.id(kind.recipeName() + "/" + name),
                new MachineRecipe(kind, List.copyOf(ingredients), fluid, List.copyOf(results), fluidResult, energy, time), null);
    }
}
