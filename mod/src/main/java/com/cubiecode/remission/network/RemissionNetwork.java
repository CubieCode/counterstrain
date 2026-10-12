package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Custom payload registration. Corruption state and bestiary progress reach clients through NeoForge attachment sync and
 * need no payload here; {@link ViralLoadPayload} (P5.1) carries a player's own viral load to their HUD.
 * Bump {@link #PROTOCOL_VERSION} whenever a payload is added or its wire format changes.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class RemissionNetwork {
    /** 2: {@code remission:viral_load} added (P5.1). */
    public static final String PROTOCOL_VERSION = "2";

    private RemissionNetwork() {
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(ViralLoadPayload.TYPE, ViralLoadPayload.STREAM_CODEC, ViralLoadPayload::handle);
    }
}
