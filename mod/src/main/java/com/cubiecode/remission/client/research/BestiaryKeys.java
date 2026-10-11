package com.cubiecode.remission.client.research;

import com.cubiecode.remission.Remission;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * The "Open Bestiary" key mapping, in game only. Default M: free in the dev runtime and the pack's mods (B is taken by
 * JourneyMap and Mekanism).
 */
@EventBusSubscriber(modid = Remission.MOD_ID, value = Dist.CLIENT)
public final class BestiaryKeys {
    public static final String CATEGORY = "key.categories.remission";
    public static final KeyMapping OPEN_BESTIARY = new KeyMapping("key.remission.bestiary", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);

    private BestiaryKeys() {
    }

    @SubscribeEvent
    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_BESTIARY);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_BESTIARY.consumeClick()) {
            if (mc.player != null && mc.screen == null) {
                mc.setScreen(new BestiaryScreen());
            }
        }
    }
}
