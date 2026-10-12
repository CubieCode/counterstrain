package com.cubiecode.remission.harness;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;

/** Opens a recipe category in EMI's recipe screen. Reached only when EMI is loaded. */
final class EmiRecipes {
    private static boolean logged;

    private EmiRecipes() {
    }

    /** False until EMI has loaded a category with this id (its reload runs after the world joins). */
    static boolean open(Minecraft mc, ResourceLocation id) {
        var categories = EmiApi.getRecipeManager().getCategories();
        EmiRecipeCategory category = categories.stream().filter(c -> id.equals(c.getId())).findFirst().orElse(null);
        if (category == null || EmiApi.getRecipeManager().getRecipes(category).isEmpty()) {
            if (!categories.isEmpty() && !logged) {
                logged = true;
                RemissionHarness.LOGGER.info("EMI has no recipes in {} yet; categories: {}", id,
                        categories.stream().map(EmiRecipeCategory::getId).toList());
            }
            return false;
        }
        // EMI's recipe screen returns to the screen it was opened from; open it from the inventory, as a player would.
        mc.setScreen(new InventoryScreen(mc.player));
        EmiApi.displayRecipeCategory(category);
        return true;
    }
}
