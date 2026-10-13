package com.cubiecode.remission.purification;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Lists the handheld purifier in Tools and Utilities, the quarantine pylon and reclaimer (P7.4) in Functional Blocks and mendroot in Natural Blocks (P5.2). */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class PurificationTabs {
    private PurificationTabs() {
    }

    @SubscribeEvent
    static void buildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RemissionItems.HANDHELD_PURIFIER);
        } else if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(RemissionItems.QUARANTINE_PYLON);
            event.accept(RemissionItems.RECLAIMER);
        } else if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(RemissionItems.MENDROOT);
        }
    }
}
