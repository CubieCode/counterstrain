package com.cubiecode.remission.integration.jei;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.client.tech.MachineScreen;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.tech.MachineRecipe;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiClickableArea;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Recipe viewer integration (P7.2): one JEI category per machine recipe type, with the machine as its catalyst and
 * the screen's progress arrow opening it. EMI shows these categories through its JEI compatibility. JEI finds this
 * class by its {@link JeiPlugin} annotation only when JEI is loaded, so nothing else references it and Remission
 * loads without JEI ({@code compileOnly}).
 */
@JeiPlugin
public final class RemissionJeiPlugin implements IModPlugin {
    /** JEI recipe types, with the same ids as the vanilla recipe types ({@code remission:analyzing} and so on). */
    public static final Map<MachineKind, RecipeType<RecipeHolder<MachineRecipe>>> TYPES = new EnumMap<>(MachineKind.class);

    static {
        for (MachineKind kind : MachineKind.values()) {
            TYPES.put(kind, RecipeType.createRecipeHolderType(Remission.id(kind.recipeName())));
        }
    }

    @Override
    public ResourceLocation getPluginUid() {
        return Remission.id("jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        for (MachineKind kind : MachineKind.values()) {
            registration.addRecipeCategories(new MachineRecipeCategory(kind, TYPES.get(kind), registration.getJeiHelpers().getGuiHelper()));
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (MachineKind kind : MachineKind.values()) {
            registration.addRecipes(TYPES.get(kind), level.getRecipeManager().getAllRecipesFor(kind.recipeType()));
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (MachineKind kind : MachineKind.values()) {
            registration.addRecipeCatalyst(kind.block(), TYPES.get(kind));
        }
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // One screen class serves all three machines, so the clickable arrow picks the type from the open menu.
        registration.addGuiContainerHandler(MachineScreen.class, new IGuiContainerHandler<>() {
            @Override
            public Collection<IGuiClickableArea> getGuiClickableAreas(MachineScreen screen, double mouseX, double mouseY) {
                return List.of(IGuiClickableArea.createBasic(MachineScreen.ARROW_X, MachineScreen.ARROW_Y,
                        MachineScreen.ARROW_WIDTH, MachineScreen.ARROW_HEIGHT, TYPES.get(screen.getMenu().kind())));
            }
        });
    }
}
