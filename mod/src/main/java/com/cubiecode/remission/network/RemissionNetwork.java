package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Custom payload registration. Corruption state and bestiary progress reach clients through NeoForge attachment sync and
 * need no payload here; {@link ViralLoadPayload} (P5.1) carries a player's own viral load to their HUD, and
 * {@link FesteringMapPayload} (P8.2, sent by {@link FesteringMapSync}) the festering stages of chunks to map overlays.
 * Bump {@link #PROTOCOL_VERSION} whenever a payload is added or its wire format changes.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class RemissionNetwork {
    /** 2: {@code remission:viral_load} added (P5.1). 3: {@code remission:festering_map} added (P8.2). */
    public static final String PROTOCOL_VERSION = "3";

    private RemissionNetwork() {
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(ViralLoadPayload.TYPE, ViralLoadPayload.STREAM_CODEC, ViralLoadPayload::handle);
        registrar.playToClient(FesteringMapPayload.TYPE, FesteringMapPayload.STREAM_CODEC, FesteringMapPayload::handle);
    }
}
