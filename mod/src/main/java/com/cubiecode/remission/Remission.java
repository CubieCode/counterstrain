package com.cubiecode.remission;

import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.commands.RemissionCommands;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.integration.ftbteams.FtbTeamsIntegration;
import com.cubiecode.remission.integration.kubejs.KubeJSIntegration;
import com.cubiecode.remission.integration.mekanism.MekanismIntegration;
import com.cubiecode.remission.registry.RemissionRegistries;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/** Common entrypoint. Client-only and server-only setup live in {@code client/} and {@code server/}. */
@Mod(Remission.MOD_ID)
public final class Remission {
    public static final String MOD_ID = "remission";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Remission(IEventBus modBus, ModContainer container) {
        RemissionRegistries.register(modBus);
        // Optional integrations: each class is touched only when its mod is loaded.
        if (ModList.get().isLoaded(MekanismIntegration.MOD_ID)) {
            MekanismIntegration.register(modBus);
        }
        if (ModList.get().isLoaded(FtbTeamsIntegration.MOD_ID)) {
            FtbTeamsIntegration.register(modBus);
        }
        if (ModList.get().isLoaded(KubeJSIntegration.MOD_ID)) {
            KubeJSIntegration.register(modBus);
        }
        container.registerConfig(ModConfig.Type.COMMON, RemissionCommonConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(RemissionCommands::register);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> TeamQueries.invalidateAll());
        LOGGER.info("Remission loading");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
