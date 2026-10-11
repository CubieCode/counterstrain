package com.cubiecode.remission.infection;

import com.cubiecode.remission.config.RemissionCommonConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Festering land infection settings ({@code [infection]} in {@code remission-common.toml}) plus the fixed check cadence.
 * A pass over a level's mobs starts every {@code intervalTicks}; each server tick checks up to {@code mobBudget} of them
 * and turns at most {@code infectionBudget} into hosts, so a pass with more mobs, or more mobs at the threshold, continues
 * on later ticks.
 *
 * @param enabled       whether festering land builds exposure at all (off freezes it)
 * @param minStage      lowest festering stage that builds exposure
 * @param exposureTicks exposure at which a mob becomes a host
 */
public record ExposureSettings(boolean enabled, int minStage, int exposureTicks, int intervalTicks, int mobBudget,
                               int infectionBudget) {
    /** Ticks between exposure passes. */
    public static final int INTERVAL_TICKS = 20;
    /** Mobs checked per server tick and level. */
    public static final int MOB_BUDGET = 256;
    /** Mobs turned into hosts per server tick and level; conversion costs far more than a check. */
    public static final int INFECTION_BUDGET = 32;

    public static ExposureSettings fromConfig() {
        return new ExposureSettings(
                RemissionCommonConfig.INFECTION_FESTERING_LAND.get(),
                RemissionCommonConfig.INFECTION_FESTERING_LAND_MIN_STAGE.get(),
                RemissionCommonConfig.INFECTION_FESTERING_LAND_EXPOSURE_TICKS.get(),
                INTERVAL_TICKS, MOB_BUDGET, INFECTION_BUDGET);
    }

    /**
     * The lowest chunk Strain level at {@link #minStage} under the current festering stage thresholds, or
     * {@link Integer#MAX_VALUE} when there are fewer stages than that.
     */
    public int minLevel() {
        List<Integer> thresholds = new ArrayList<>(RemissionCommonConfig.FESTERING_STAGE_THRESHOLDS.get());
        if (minStage > thresholds.size()) {
            return Integer.MAX_VALUE;
        }
        thresholds.sort(null);
        return thresholds.get(minStage - 1);
    }
}
