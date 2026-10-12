package com.cubiecode.remission.tech;

import com.cubiecode.remission.Remission;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers the standard item, fluid and energy block capabilities of every machine, so pipes, cables and storage
 * networks (AE2, Mekanism, Create) can feed and empty them. Every side, and the null side, gets the same handler.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class MachineCapabilities {
    private MachineCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        for (MachineKind kind : MachineKind.values()) {
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, kind.blockEntityType(),
                    (machine, side) -> machine.automationItems());
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, kind.blockEntityType(),
                    (machine, side) -> machine.automationFluids());
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, kind.blockEntityType(),
                    (machine, side) -> machine.energy());
        }
    }
}
