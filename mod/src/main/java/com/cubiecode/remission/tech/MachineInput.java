package com.cubiecode.remission.tech;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.neoforge.fluids.FluidStack;

/** A machine's input slots and the contents of its input tank, as a recipe sees them. */
public record MachineInput(List<ItemStack> items, FluidStack fluid) implements RecipeInput {
    @Override
    public ItemStack getItem(int index) {
        return items.get(index);
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        return RecipeInput.super.isEmpty() && fluid.isEmpty();
    }
}
