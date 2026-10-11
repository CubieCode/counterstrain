package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Custom payload registration. None yet: corruption state reaches clients through NeoForge attachment sync
 * ({@code RemissionAttachments.CORRUPTION}), which needs no payload here.
 * Bump {@link #PROTOCOL_VERSION} whenever a payload's wire format changes.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class RemissionNetwork {
    public static final String PROTOCOL_VERSION = "1";

    private RemissionNetwork() {
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
    }
}
