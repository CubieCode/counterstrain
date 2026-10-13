package com.cubiecode.remission.serum;

import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.registry.RemissionMobEffects;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Serums (P7.3): what a dose of {@code remission:serum} does to whoever takes it. Drinking one calls {@link #apply};
 * later ways of giving a serum (the injector, P7.6) call it too, so every dose has the same effect.
 */
public final class Serums {
    private Serums() {
    }

    /**
     * One dose: a player loses {@code viralLoadDrop} viral load at once (cause {@link ViralLoadChangedEvent.Cause#SERUM}),
     * and any living target gets {@code remission:inoculated} for {@code inoculationTicks}, replacing a shorter one.
     * Server only.
     */
    public static void apply(LivingEntity target, SerumSettings settings) {
        if (target instanceof ServerPlayer player && settings.viralLoadDrop() > 0) {
            float load = ViralLoad.get(player);
            if (load > 0) {
                float next = (float) Math.max(0.0, load - settings.viralLoadDrop());
                ViralLoad.set(player, next, next > 0 ? ViralLoadState.Trend.FALLING : ViralLoadState.Trend.STEADY,
                        ViralLoadChangedEvent.Cause.SERUM);
            }
        }
        if (settings.inoculationTicks() > 0) {
            target.addEffect(new MobEffectInstance(RemissionMobEffects.INOCULATED, settings.inoculationTicks()));
        }
    }

    /** Whether the player is inoculated. */
    public static boolean isInoculated(Player player) {
        return player.hasEffect(RemissionMobEffects.INOCULATED);
    }

    /** The factor viral load gain is multiplied by for this player now: {@code inoculationFactor} while inoculated, else 1. */
    public static double gainFactor(Player player, SerumSettings settings) {
        return isInoculated(player) ? settings.inoculationFactor() : 1.0;
    }
}
