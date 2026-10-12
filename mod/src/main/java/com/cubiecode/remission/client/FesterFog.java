package com.cubiecode.remission.client;

import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.config.RemissionClientConfig;
import com.cubiecode.remission.network.FesteringMapCache;
import com.cubiecode.remission.world.FesterMaps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Fog that thickens with the festering stage of the chunk the camera is in (P4.11). The stage comes from the chunk
 * states the server already sends every client for map overlays ({@link FesteringMapCache}, P8.2), which this enables;
 * a chunk the cache has no entry for counts as the last stage when its biome is {@code remission:fester}. The fog eases
 * toward its target over {@link #EASE_TICKS} client ticks and is scaled by the client option {@code festerFog}. It only
 * pulls the fog planes in; colours are the biome's.
 */
public final class FesterFog {
    /** Client ticks the fog takes to go from none to full. */
    static final int EASE_TICKS = 20;
    /** Fog start and end, in blocks, at full strength (the last stage, option at 1). */
    static final float FULL_START = 2.0F;
    static final float FULL_END = 40.0F;

    private static float previous;
    private static float current;
    private static boolean suppressed;

    private FesterFog() {
    }

    /** From the client entrypoint. */
    public static void init() {
        FesteringMapCache.enable();
        NeoForge.EVENT_BUS.addListener(FesterFog::onClientTick);
        NeoForge.EVENT_BUS.addListener(FesterFog::onRenderFog);
        // Idempotent; the JourneyMap integration clears the cache on logout too.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            FesteringMapCache.clear();
            previous = current = 0.0F;
        });
    }

    /** Turns the fog off regardless of the option, in memory only (harness scenes that do not opt in). */
    public static void setSuppressed(boolean value) {
        suppressed = value;
    }

    /** The fog strength, from 0 to 1, for a chunk at festering {@code stage} of {@code maxStage}: their ratio. */
    public static float strengthFor(int stage, int maxStage) {
        if (stage <= 0 || maxStage <= 0) {
            return 0.0F;
        }
        return Math.min(1.0F, stage / (float) maxStage);
    }

    /** The festering stage of the chunk {@code entity} is in, as far as this client knows. */
    static int stageAt(Entity entity) {
        ChunkPos chunk = entity.chunkPosition();
        int stage = FesteringMapCache.stageOf(FesteringMapCache.state(entity.level().dimension(), chunk.toLong()));
        if (stage == 0 && entity.level().getBiome(entity.blockPosition()).is(FesterMaps.FESTER)) {
            return CorruptionQueries.maxChunkStage();
        }
        return stage;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        float target = 0.0F;
        if (camera != null && mc.level != null && !suppressed) {
            target = strengthFor(stageAt(camera), CorruptionQueries.maxChunkStage())
                    * RemissionClientConfig.FESTER_FOG.get().floatValue();
        }
        previous = current;
        float step = 1.0F / EASE_TICKS;
        current = Mth.clamp(target, current - step, current + step);
    }

    private static void onRenderFog(ViewportEvent.RenderFog event) {
        float strength = Mth.lerp((float) event.getPartialTick(), previous, current);
        if (strength <= 0.0F || event.getType() != FogType.NONE) {
            return;
        }
        // Blindness and darkness set their own fog; leave it to them.
        if (event.getCamera().getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        float start = event.getNearPlaneDistance();
        float end = event.getFarPlaneDistance();
        if (event.getMode() == FogRenderer.FogMode.FOG_SKY) {
            event.setFarPlaneDistance(Mth.lerp(strength, end, Math.min(end, FULL_END)));
        } else {
            event.setNearPlaneDistance(Mth.lerp(strength, start, Math.min(start, FULL_START)));
            event.setFarPlaneDistance(Mth.lerp(strength, end, Math.min(end, FULL_END)));
        }
        event.setCanceled(true);
    }
}
