package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.config.RemissionCommonConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Nidus defence settings (P4.13; the rally and sympathetic keys of {@code [nidi]} in {@code remission-common.toml}).
 *
 * @param rally                whether hosts rally to a nidus under attack
 * @param rallyRadius          blocks from the nidus within which hosts in festering chunks rally
 * @param rallyBatch           hosts sent for (or sent on again) per {@link NidusRally#RALLY_TICKS}, at most
 * @param sympathetic          whether a threshold passed may set off a sympathetic surge
 * @param sympatheticRadius    blocks (horizontal) within which another nidus may surge in sympathy
 * @param sympatheticChance    chance a threshold passed sets one off
 * @param sympatheticIntensity a sympathetic surge's intensity as a fraction of the threshold surge's (at least 1)
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public record NidusDefenceSettings(boolean rally, int rallyRadius, int rallyBatch, boolean sympathetic,
                                   int sympatheticRadius, double sympatheticChance, double sympatheticIntensity) {
    @Nullable
    private static volatile NidusDefenceSettings override;
    @Nullable
    private static volatile NidusDefenceSettings cached;

    public static NidusDefenceSettings fromConfig() {
        return new NidusDefenceSettings(
                RemissionCommonConfig.NIDUS_RALLY.get(),
                RemissionCommonConfig.NIDUS_RALLY_RADIUS.get(),
                RemissionCommonConfig.NIDUS_RALLY_BATCH.get(),
                RemissionCommonConfig.NIDUS_SYMPATHETIC.get(),
                RemissionCommonConfig.NIDUS_SYMPATHETIC_RADIUS.get(),
                RemissionCommonConfig.NIDUS_SYMPATHETIC_CHANCE.get(),
                RemissionCommonConfig.NIDUS_SYMPATHETIC_INTENSITY.get());
    }

    /** The settings in force: the config (cached until it next loads or reloads), unless a gametest replaced them. */
    public static NidusDefenceSettings current() {
        NidusDefenceSettings replaced = override;
        if (replaced != null) {
            return replaced;
        }
        NidusDefenceSettings settings = cached;
        if (settings == null) {
            settings = fromConfig();
            cached = settings;
        }
        return settings;
    }

    @SubscribeEvent
    static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == RemissionCommonConfig.SPEC) {
            cached = null;
        }
    }

    /** Replaces the config settings for gametests (null restores the config). Affects every level. */
    @VisibleForTesting
    public static void override(@Nullable NidusDefenceSettings settings) {
        override = settings;
    }

    /** Intensity of a sympathetic surge set off by a threshold surge of {@code intensity}. */
    public int sympatheticIntensityFor(int intensity) {
        return Math.max(1, (int) Math.round(intensity * sympatheticIntensity));
    }
}
