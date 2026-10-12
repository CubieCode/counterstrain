package com.cubiecode.remission.integration.journeymap;

import com.cubiecode.remission.network.FesteringMapCache;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import journeymap.api.v2.client.IClientAPI;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Optional JourneyMap integration, client only (P8.2): map layers drawn through JourneyMap's client API. The client
 * entrypoint calls {@link #init()} only when {@code ModList} reports JourneyMap; {@link RemissionJourneyMapPlugin} hands
 * over the API. Each {@link MapLayer} is updated on client ticks once the API is there. Layers: {@link FesteringOverlay}
 * (festering chunks by stage, and remission zones). Later map features add their layer in {@link #init()}.
 */
public final class JourneyMapIntegration {
    public static final String MOD_ID = "journeymap";

    /** One kind of map content, kept in step with client state on each client tick. */
    interface MapLayer {
        void update(IClientAPI api);
    }

    private static final List<MapLayer> LAYERS = new CopyOnWriteArrayList<>();
    @Nullable
    private static volatile IClientAPI api;

    private JourneyMapIntegration() {
    }

    /** From the client entrypoint, after {@code ModList} reports JourneyMap. */
    public static void init() {
        FesteringMapCache.enable();
        LAYERS.add(new FesteringOverlay());
        NeoForge.EVENT_BUS.addListener(JourneyMapIntegration::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> FesteringMapCache.clear());
    }

    static void attach(IClientAPI clientApi) {
        api = clientApi;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        IClientAPI current = api;
        if (current != null) {
            LAYERS.forEach(layer -> layer.update(current));
        }
    }
}
