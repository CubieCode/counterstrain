package com.cubiecode.remission;

import com.cubiecode.remission.commands.RemissionCommands;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.registry.RemissionRegistries;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Common entrypoint. Client-only and server-only setup live in {@code client/} and {@code server/}. */
@Mod(Remission.MOD_ID)
public final class Remission {
    public static final String MOD_ID = "remission";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Remission(IEventBus modBus, ModContainer container) {
        RemissionRegistries.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, RemissionCommonConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(RemissionCommands::register);
        LOGGER.info("Remission loading");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
