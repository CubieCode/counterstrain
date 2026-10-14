package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.config.RemissionCommonConfig;
import java.util.Comparator;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Nidus encounter settings ({@code [nidi]} in {@code remission-common.toml}; P4.10).
 *
 * @param vitality               vitality of a new nidus fought by one player; 0 restores plain mining
 * @param growthTicks            game ticks until a nidus is full-grown
 * @param growthBonus            extra vitality of a full-grown nidus, as a fraction of {@code vitality}
 * @param radius                 blocks within which players count toward the fight and see its boss bar
 * @param playerFactor           extra vitality and surge intensity per counted player beyond the first
 * @param playerCap              most players counted
 * @param distanceFactor         extra vitality per 1000 blocks from world spawn, as a fraction; 0 is off
 * @param distanceMaxBonus       most extra vitality from distance, as a fraction
 * @param contributorWindowTicks game ticks within which a player's damage earns credit for the destruction
 * @param regenDelayTicks        game ticks without damage before vitality regenerates
 * @param regenRate              fraction of full vitality regained per second
 * @param thresholds             percent of vitality left at which the nidus fights back, highest first
 * @param projectileFraction     fraction of arrow damage a nidus takes
 * @param explosionFraction      fraction of mob explosion damage a nidus takes
 * @param lashRadius             blocks within which tentacles track and lash players
 * @param lashViralLoad          viral load a lash adds
 * @param lashKnockback          knockback strength of a lash
 * @param strikeCooldownTicks    game ticks after a player's strike during which their further strikes deal nothing
 * @param lashIntervalTicks      game ticks between a fighting nidus's lashes besides those at thresholds; 0 is off
 * @param tentacleDamageFactor   fraction of a strike's or projectile's damage a nidus takes through a tentacle
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public record NidusSettings(double vitality, int growthTicks, double growthBonus, int radius, double playerFactor,
                            int playerCap, double distanceFactor, double distanceMaxBonus, int contributorWindowTicks,
                            int regenDelayTicks, double regenRate, List<Integer> thresholds, double projectileFraction,
                            double explosionFraction, double lashRadius, double lashViralLoad, double lashKnockback,
                            int strikeCooldownTicks, int lashIntervalTicks, double tentacleDamageFactor) {
    @Nullable
    private static volatile NidusSettings override;
    /** The config settings, built once per config load or reload. */
    @Nullable
    private static volatile NidusSettings cached;

    public NidusSettings {
        thresholds = thresholds.stream().distinct().sorted(Comparator.reverseOrder()).toList();
    }

    public static NidusSettings fromConfig() {
        return new NidusSettings(
                RemissionCommonConfig.NIDUS_VITALITY.get(),
                RemissionCommonConfig.NIDUS_GROWTH_TICKS.get(),
                RemissionCommonConfig.NIDUS_GROWTH_BONUS.get(),
                RemissionCommonConfig.NIDUS_RADIUS.get(),
                RemissionCommonConfig.NIDUS_PLAYER_FACTOR.get(),
                RemissionCommonConfig.NIDUS_PLAYER_CAP.get(),
                RemissionCommonConfig.NIDUS_DISTANCE_FACTOR.get(),
                RemissionCommonConfig.NIDUS_DISTANCE_MAX_BONUS.get(),
                RemissionCommonConfig.NIDUS_CONTRIBUTOR_WINDOW_TICKS.get(),
                RemissionCommonConfig.NIDUS_REGEN_DELAY_TICKS.get(),
                RemissionCommonConfig.NIDUS_REGEN_RATE.get(),
                List.copyOf(RemissionCommonConfig.NIDUS_THRESHOLDS.get()),
                RemissionCommonConfig.NIDUS_PROJECTILE_FRACTION.get(),
                RemissionCommonConfig.NIDUS_EXPLOSION_FRACTION.get(),
                RemissionCommonConfig.NIDUS_LASH_RADIUS.get(),
                RemissionCommonConfig.NIDUS_LASH_VIRAL_LOAD.get(),
                RemissionCommonConfig.NIDUS_LASH_KNOCKBACK.get(),
                RemissionCommonConfig.NIDUS_STRIKE_COOLDOWN_TICKS.get(),
                RemissionCommonConfig.NIDUS_LASH_INTERVAL_TICKS.get(),
                RemissionCommonConfig.NIDUS_TENTACLE_DAMAGE_FACTOR.get());
    }

    /** The settings in force: the config (cached until it next loads or reloads), unless a gametest replaced them. */
    public static NidusSettings current() {
        NidusSettings replaced = override;
        if (replaced != null) {
            return replaced;
        }
        NidusSettings settings = cached;
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
    public static void override(@Nullable NidusSettings settings) {
        override = settings;
    }

    /** Whether nidi are fought rather than mined. */
    public boolean encounters() {
        return vitality > 0;
    }

    /** Factor on vitality and surge intensity for {@code players} players in the fight. */
    public double playerMultiplier(int players) {
        return 1.0 + playerFactor * (Math.clamp(players, 1, playerCap) - 1);
    }

    /** Factor on vitality for a nidus {@code age} game ticks old. */
    public double growthMultiplier(long age) {
        return 1.0 + growthBonus * Math.clamp((double) age / growthTicks, 0.0, 1.0);
    }

    /** Factor on vitality for a nidus {@code blocks} blocks (horizontal) from world spawn. */
    public double distanceMultiplier(double blocks) {
        return 1.0 + Math.min(distanceMaxBonus, distanceFactor * blocks / 1000.0);
    }
}
