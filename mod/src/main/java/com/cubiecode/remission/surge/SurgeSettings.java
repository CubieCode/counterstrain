package com.cubiecode.remission.surge;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * Surge settings ({@code [surges]} in {@code remission-common.toml}). Counts and the spread multiplier are per unit of
 * intensity.
 *
 * @param scheduled         whether surges come due on their own every {@code intervalTicks}
 * @param intervalTicks     game ticks from the end of one surge until the next comes due
 * @param warningTicks      game ticks of warning before a surge starts
 * @param durationTicks     game ticks a started surge lasts
 * @param spreadMultiplier  factor on the origin nidus's spread steps while the surge runs
 * @param hostsPerWave      hosts tried near each player in the area per wave
 * @param hostLevel         Strain level of spawned hosts
 * @param maxHosts          most hosts one surge keeps alive at once
 * @param clouds            miasma clouds released when the surge starts
 */
public record SurgeSettings(boolean scheduled, int intervalTicks, int warningTicks, int durationTicks,
                            int spreadMultiplier, int hostsPerWave, int hostLevel, int maxHosts, int clouds) {
    public static SurgeSettings fromConfig() {
        return new SurgeSettings(
                RemissionCommonConfig.SURGES_SCHEDULED.get(),
                RemissionCommonConfig.SURGE_INTERVAL_TICKS.get(),
                RemissionCommonConfig.SURGE_WARNING_TICKS.get(),
                RemissionCommonConfig.SURGE_DURATION_TICKS.get(),
                RemissionCommonConfig.SURGE_SPREAD_MULTIPLIER.get(),
                RemissionCommonConfig.SURGE_HOSTS_PER_WAVE.get(),
                RemissionCommonConfig.SURGE_HOST_LEVEL.get(),
                RemissionCommonConfig.SURGE_MAX_HOSTS.get(),
                RemissionCommonConfig.SURGE_CLOUDS.get());
    }
}
