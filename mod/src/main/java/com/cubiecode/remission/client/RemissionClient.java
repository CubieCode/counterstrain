package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.config.RemissionClientConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client entrypoint: renderers, render layers, client payload handlers and client config. */
@Mod(value = Remission.MOD_ID, dist = Dist.CLIENT)
public final class RemissionClient {
    public RemissionClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RemissionClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(HostRenderTypes::registerShaders);
        modBus.addListener((EntityRenderersEvent.AddLayers event) -> HostOverlayLayer.addLayers(event));
    }
}
