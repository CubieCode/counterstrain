package com.cubiecode.remission.integration.journeymap;

import com.cubiecode.remission.Remission;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

/**
 * JourneyMap client plugin (P8.2). JourneyMap finds this class by its {@link JourneyMapPlugin} annotation only when it is
 * loaded, and calls {@link #initialize} on the client; the overlays themselves start from {@link JourneyMapIntegration},
 * which the client entrypoint reaches only after a {@code ModList} check. Remission loads without JourneyMap
 * ({@code compileOnly}).
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class RemissionJourneyMapPlugin implements IClientPlugin {
    @Override
    public String getModId() {
        return Remission.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI api) {
        JourneyMapIntegration.attach(api);
    }
}
