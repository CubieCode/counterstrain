package com.cubiecode.remission.client.tech;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.tech.MachineKind;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Binds each machine menu type to {@link MachineScreen}. */
@EventBusSubscriber(modid = Remission.MOD_ID, value = Dist.CLIENT)
public final class MachineScreens {
    private MachineScreens() {
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        for (MachineKind kind : MachineKind.values()) {
            event.register(kind.menuType(), MachineScreen::new);
        }
    }
}
