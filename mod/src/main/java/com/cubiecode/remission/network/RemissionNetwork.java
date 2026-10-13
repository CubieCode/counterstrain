package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Custom payload registration. Corruption state and bestiary progress reach clients through NeoForge attachment sync and
 * need no payload here; {@link ViralLoadPayload} (P5.1) carries a player's own viral load to their HUD, and
 * {@link FesteringMapPayload} (P8.2, sent by {@link FesteringMapSync}) the festering stages of chunks to map overlays,
 * {@link HazardNoticePayload} hazard notices and {@link HazardStatusPayload} the hazards tab's status, which the client
 * asks for with {@link HazardStatusRequestPayload} (P5.8), and {@link VariantLandPayload} (P7.5, sent by
 * {@link VariantLandSync}) the variants of chunks, for the land's tint.
 * Bump {@link #PROTOCOL_VERSION} whenever a payload is added or its wire format changes.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class RemissionNetwork {
    /**
     * 2: {@code remission:viral_load} added (P5.1). 3: {@code remission:festering_map} added (P8.2). 4:
     * {@code remission:hazard_notice}, {@code remission:hazard_status} and {@code remission:hazard_status_request} added
     * (P5.8). 5: {@code remission:variant_land} added, and the synced {@code corruption} attachment carries the host's
     * variant (P7.5).
     */
    public static final String PROTOCOL_VERSION = "5";

    private RemissionNetwork() {
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(ViralLoadPayload.TYPE, ViralLoadPayload.STREAM_CODEC, ViralLoadPayload::handle);
        registrar.playToClient(FesteringMapPayload.TYPE, FesteringMapPayload.STREAM_CODEC, FesteringMapPayload::handle);
        registrar.playToClient(HazardNoticePayload.TYPE, HazardNoticePayload.STREAM_CODEC, HazardNoticePayload::handle);
        registrar.playToClient(HazardStatusPayload.TYPE, HazardStatusPayload.STREAM_CODEC, HazardStatusPayload::handle);
        registrar.playToClient(VariantLandPayload.TYPE, VariantLandPayload.STREAM_CODEC, VariantLandPayload::handle);
        registrar.playToServer(HazardStatusRequestPayload.TYPE, HazardStatusRequestPayload.STREAM_CODEC,
                HazardStatusRequestPayload::handle);
    }
}
