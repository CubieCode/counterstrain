package com.cubiecode.remission.research;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.countermeasure.CountermeasureProtocolItem;
import com.cubiecode.remission.registry.RemissionItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Lists the scanner and the contact countermeasure protocol (P5.7) in the vanilla Tools and Utilities tab until
 * Remission has its own tab. Scans are not listed: they mean nothing without their {@code scan_data} component.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class ResearchTabs {
    private ResearchTabs() {
    }

    @SubscribeEvent
    static void buildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RemissionItems.SCANNER);
            event.accept(CountermeasureProtocolItem.of(RemissionItems.COUNTERMEASURE_PROTOCOL.get(), StrainHazards.CONTACT));
        }
    }
}
