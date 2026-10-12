package com.cubiecode.remission.harness;

import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;

/**
 * Opens a recipe category in JEI's recipe screen. A JEI plugin of the harness mod, so it gets JEI's runtime; JEI finds
 * it only when JEI is loaded, and the harness calls it only then.
 */
@JeiPlugin
public final class JeiRecipes implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RemissionHarness.MOD_ID, "recipes");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** False until JEI's runtime is up and knows the category. */
    static boolean open(Minecraft mc, ResourceLocation id) {
        IJeiRuntime current = runtime;
        if (current == null) {
            return false;
        }
        RecipeType<?> type = current.getJeiHelpers().getRecipeType(id).orElse(null);
        if (type == null) {
            return false;
        }
        mc.setScreen(new InventoryScreen(mc.player));
        current.getRecipesGui().showTypes(List.of(type));
        return true;
    }
}
