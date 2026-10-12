package com.cubiecode.remission.tech;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.jetbrains.annotations.Nullable;

/**
 * A recipe of one of the machine recipe types ({@code remission:analyzing}, {@code remission:sequencing},
 * {@code remission:synthesizing}). Schema: remission-data-formats, "Machine recipes".
 *
 * <p>Ingredients are shapeless: each takes its own input slot, in any order, and every filled input slot must be
 * taken by one. {@code energy} is the total FE one craft uses, spread over {@code time} ticks.
 */
public record MachineRecipe(MachineKind kind, List<SizedIngredient> ingredients, Optional<SizedFluidIngredient> fluidIngredient,
                            List<ItemStack> results, FluidStack fluidResult, int energy, int time) implements Recipe<MachineInput> {

    /** The recipe JSON for one machine kind: at most {@link MachineKind#inputSlots()} ingredients. */
    public static MapCodec<MachineRecipe> codec(MachineKind kind) {
        return RecordCodecBuilder.<MachineRecipe>mapCodec(i -> i.group(
                SizedIngredient.FLAT_CODEC.listOf().fieldOf("ingredients").forGetter(MachineRecipe::ingredients),
                SizedFluidIngredient.FLAT_CODEC.optionalFieldOf("fluid_ingredient").forGetter(MachineRecipe::fluidIngredient),
                ItemStack.CODEC.listOf().optionalFieldOf("results", List.of()).forGetter(MachineRecipe::results),
                FluidStack.CODEC.optionalFieldOf("fluid_result")
                        .forGetter(r -> r.fluidResult().isEmpty() ? Optional.empty() : Optional.of(r.fluidResult())),
                ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("energy", 0).forGetter(MachineRecipe::energy),
                ExtraCodecs.POSITIVE_INT.fieldOf("time").forGetter(MachineRecipe::time)
        ).apply(i, (ingredients, fluid, results, fluidResult, energy, time) ->
                new MachineRecipe(kind, ingredients, fluid, results, fluidResult.orElse(FluidStack.EMPTY), energy, time)))
                .validate(MachineRecipe::validate);
    }

    public static StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> streamCodec(MachineKind kind) {
        return StreamCodec.composite(
                SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineRecipe::ingredients,
                ByteBufCodecs.optional(SizedFluidIngredient.STREAM_CODEC), MachineRecipe::fluidIngredient,
                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineRecipe::results,
                FluidStack.OPTIONAL_STREAM_CODEC, MachineRecipe::fluidResult,
                ByteBufCodecs.VAR_INT, MachineRecipe::energy,
                ByteBufCodecs.VAR_INT, MachineRecipe::time,
                (ingredients, fluid, results, fluidResult, energy, time) ->
                        new MachineRecipe(kind, ingredients, fluid, results, fluidResult, energy, time));
    }

    private static DataResult<MachineRecipe> validate(MachineRecipe recipe) {
        int slots = recipe.kind().inputSlots();
        if (recipe.ingredients().isEmpty() || recipe.ingredients().size() > slots) {
            return DataResult.error(() -> recipe.kind().recipeName() + " needs 1 to " + slots + " ingredients, got "
                    + recipe.ingredients().size());
        }
        if (recipe.results().size() > MachineKind.OUTPUT_SLOTS) {
            return DataResult.error(() -> "at most " + MachineKind.OUTPUT_SLOTS + " results, got " + recipe.results().size());
        }
        if (recipe.results().isEmpty() && recipe.fluidResult().isEmpty()) {
            return DataResult.error(() -> "a machine recipe needs results or a fluid_result");
        }
        if (recipe.fluidIngredient().map(f -> f.amount() > MachineKind.TANK_CAPACITY).orElse(false)
                || recipe.fluidResult().getAmount() > MachineKind.TANK_CAPACITY) {
            return DataResult.error(() -> "fluid amounts are limited to the tank capacity, " + MachineKind.TANK_CAPACITY + " mB");
        }
        return DataResult.success(recipe);
    }

    @Override
    public boolean matches(MachineInput input, Level level) {
        return fluidMatches(input.fluid()) && slotsFor(input) != null;
    }

    /** Whether the input tank holds enough of the fluid ingredient (always true without one). */
    public boolean fluidMatches(FluidStack fluid) {
        return fluidIngredient.map(f -> f.test(fluid)).orElse(true);
    }

    /**
     * The input slot each ingredient takes, by ingredient index, or {@code null} if the filled slots do not match:
     * every ingredient needs its own slot with at least its count, and no filled slot may be left over.
     */
    public int @Nullable [] slotsFor(MachineInput input) {
        int filled = 0;
        for (ItemStack stack : input.items()) {
            if (!stack.isEmpty()) {
                filled++;
            }
        }
        if (filled != ingredients.size()) {
            return null;
        }
        int[] slots = new int[ingredients.size()];
        return assign(input, 0, slots, new boolean[input.size()]) ? slots : null;
    }

    private boolean assign(MachineInput input, int ingredient, int[] slots, boolean[] used) {
        if (ingredient == ingredients.size()) {
            return true;
        }
        for (int slot = 0; slot < input.size(); slot++) {
            if (!used[slot] && ingredients.get(ingredient).test(input.getItem(slot))) {
                used[slot] = true;
                slots[ingredient] = slot;
                if (assign(input, ingredient + 1, slots, used)) {
                    return true;
                }
                used[slot] = false;
            }
        }
        return false;
    }

    /** Energy to use in the given tick of a craft (0-based), so a whole craft uses exactly {@link #energy}. */
    public int energyForTick(int tick) {
        return (int) ((long) energy * (tick + 1) / time - (long) energy * tick / time);
    }

    @Override
    public ItemStack assemble(MachineInput input, HolderLookup.Provider registries) {
        return getResultItem(registries).copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return results.isEmpty() ? ItemStack.EMPTY : results.getFirst();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ingredients.forEach(sized -> list.add(sized.ingredient()));
        return list;
    }

    /** Keeps machine recipes out of the vanilla recipe book. */
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(kind.block());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return kind.serializer();
    }

    @Override
    public RecipeType<?> getType() {
        return kind.recipeType();
    }

    /** The serializer of one machine kind's recipe type. */
    public record Serializer(MachineKind kind, MapCodec<MachineRecipe> codec,
                             StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> streamCodec) implements RecipeSerializer<MachineRecipe> {
        public Serializer(MachineKind kind) {
            this(kind, MachineRecipe.codec(kind), MachineRecipe.streamCodec(kind));
        }
    }
}
