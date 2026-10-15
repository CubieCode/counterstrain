package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.client.notice.HazardNoticeDisplay;
import com.cubiecode.remission.config.RemissionClientConfig;
import com.cubiecode.remission.integration.journeymap.JourneyMapIntegration;
import com.cubiecode.remission.registry.RemissionBlockEntities;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client entrypoint: renderers (the nidus's animated block entity renderer among them), render layers, HUD layers, festering fog and music, hazard notices, client config and client-side integrations
 * (JourneyMap).
 */
@Mod(value = Remission.MOD_ID, dist = Dist.CLIENT)
public final class RemissionClient {
    public RemissionClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RemissionClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(HostRenderTypes::registerShaders);
        modBus.addListener((EntityRenderersEvent.AddLayers event) -> HostOverlayLayer.addLayers(event));
        modBus.addListener(EventPriority.LOWEST,
                (EntityRenderersEvent.AddLayers event) -> HostLayerOverlay.addLayers(event));
        modBus.addListener(MiasmaClientExtensions::register);
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(RemissionEntityTypes.MIASMA_CLOUD.get(), MiasmaCloudRenderer::new));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(RemissionEntityTypes.REVENANT.get(), RevenantRenderer::new));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(RemissionEntityTypes.COUNTERSTRAIN_PROTOTYPE.get(), ThrownItemRenderer::new));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(RemissionBlockEntities.NIDUS.get(), NidusRenderer::new));
        // The nidus's tentacle hitboxes are invisible: the nidus's own renderer draws the tentacles.
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(RemissionEntityTypes.NIDUS_TENTACLE.get(), NoopRenderer::new));
        modBus.addListener(ViralLoadHud::register);
        FesterFog.init();
        FesterMusic.init();
        HazardNoticeDisplay.init();
        // Optional integrations: each class is touched only when its mod is loaded.
        if (ModList.get().isLoaded(JourneyMapIntegration.MOD_ID)) {
            JourneyMapIntegration.init();
        }
    }
}
