package com.cubiecode.remission.tech;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Lists the machines in the vanilla Functional Blocks tab and the strain sequence in Ingredients (P7.2), and the
 * synthesizer's products (P7.3): purifier fuel in Ingredients, the serum in Food and Drinks, the Counterstrain prototype
 * in Combat, next to splash potions.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class TechTabs {
    private TechTabs() {
    }

    @SubscribeEvent
    static void buildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(RemissionItems.ANALYZER);
            event.accept(RemissionItems.SEQUENCER);
            event.accept(RemissionItems.SYNTHESIZER);
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(RemissionItems.STRAIN_SEQUENCE);
            event.accept(RemissionItems.PURIFIER_FUEL);
        } else if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            event.accept(RemissionItems.SERUM);
        } else if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(RemissionItems.COUNTERSTRAIN_PROTOTYPE);
        }
    }
}
