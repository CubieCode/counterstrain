package com.cubiecode.remission.integration.jei;

import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.tech.MachineRecipe;
import java.util.Arrays;
import java.util.Locale;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * One machine recipe: input tank, item inputs, an arrow, item outputs and output tank, with the energy and time below.
 * Tanks are drawn full at the recipe's amount.
 */
final class MachineRecipeCategory extends AbstractRecipeCategory<RecipeHolder<MachineRecipe>> {
    private static final int WIDTH = 150;
    private static final int HEIGHT = 56;
    private static final int TANK_WIDTH = 16;
    private static final int TANK_HEIGHT = 40;
    private static final int INPUTS_X = 20;
    private static final int ROW_Y = 12;
    private static final int ARROW_X = INPUTS_X + 3 * 18 + 4;
    private static final int OUTPUTS_X = ARROW_X + 28;
    private static final int OUTPUT_TANK_X = OUTPUTS_X + 22;

    /** A static arrow: an animated one follows wall-clock time, which would make harness shots differ between runs. */
    private final IDrawable arrow;

    MachineRecipeCategory(MachineKind kind, RecipeType<RecipeHolder<MachineRecipe>> type, IGuiHelper gui) {
        super(type, Component.translatable("gui.remission.recipe." + kind.recipeName()),
                gui.createDrawableItemLike(kind.block()), WIDTH, HEIGHT);
        this.arrow = gui.getRecipeArrow();
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<MachineRecipe> holder, IFocusGroup focuses) {
        MachineRecipe recipe = holder.value();
        recipe.fluidIngredient().ifPresent(fluid -> builder.addInputSlot(0, 0)
                .addIngredients(NeoForgeTypes.FLUID_STACK, Arrays.asList(fluid.getFluids()))
                .setFluidRenderer(fluid.amount(), false, TANK_WIDTH, TANK_HEIGHT));
        for (int i = 0; i < recipe.ingredients().size(); i++) {
            SizedIngredient ingredient = recipe.ingredients().get(i);
            builder.addInputSlot(INPUTS_X + 1 + i * 18, ROW_Y + 1)
                    .setStandardSlotBackground()
                    .addItemStacks(Arrays.asList(ingredient.getItems()));
        }
        for (int i = 0; i < recipe.results().size(); i++) {
            builder.addOutputSlot(OUTPUTS_X + 1, 2 + i * 18 + (recipe.results().size() == 1 ? 10 : 1))
                    .setOutputSlotBackground()
                    .addItemStack(recipe.results().get(i).copy());
        }
        FluidStack fluid = recipe.fluidResult();
        if (!fluid.isEmpty()) {
            builder.addOutputSlot(OUTPUT_TANK_X, 0)
                    .addIngredient(NeoForgeTypes.FLUID_STACK, fluid.copy())
                    .setFluidRenderer(fluid.getAmount(), false, TANK_WIDTH, TANK_HEIGHT);
        }
    }

    /**
     * Drawn here rather than as recipe extras (widgets), because EMI's JEI compatibility calls {@code draw} but does not
     * show JEI widgets.
     */
    @Override
    public void draw(RecipeHolder<MachineRecipe> holder, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        MachineRecipe recipe = holder.value();
        arrow.draw(graphics, ARROW_X, ROW_Y + 1);
        Component cost = Component.translatable("gui.remission.recipe.cost", recipe.energy(),
                String.format(Locale.ROOT, "%.1f", recipe.time() / 20.0F));
        graphics.drawString(Minecraft.getInstance().font, cost, 0, HEIGHT - 9, 0xFF404040, false);
    }
}
