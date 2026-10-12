package com.cubiecode.remission.infection;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * Contact infection settings ({@code [infection] contact} and {@code contactTicks} in {@code remission-common.toml}) plus
 * the fixed check cadence (P4.8). A pass over a level's mobs starts every {@code intervalTicks}; each server tick checks
 * up to {@code mobBudget} of them and turns at most {@code infectionBudget} into hosts, so a pass with more mobs, or more
 * mobs at the threshold, continues on later ticks.
 *
 * @param enabled       whether contact builds up at all (off freezes it)
 * @param contactTicks  contact at which a mob becomes a host; 0 infects on the first pass that finds it in contact
 * @param waterMinStage lowest festering stage of a chunk whose water infects ({@code festeringLandMinStage})
 */
public record ContactSettings(boolean enabled, int contactTicks, int waterMinStage, int intervalTicks, int mobBudget,
                              int infectionBudget) {
    /** Ticks between contact passes. A touch shorter than this can go unseen. */
    public static final int INTERVAL_TICKS = 10;
    /** Mobs checked per server tick and level; a check reads the blocks around the mob, so passes are spread out. */
    public static final int MOB_BUDGET = 64;
    /** Mobs turned into hosts per server tick and level; conversion costs far more than a check. */
    public static final int INFECTION_BUDGET = ExposureSettings.INFECTION_BUDGET;

    public static ContactSettings fromConfig() {
        return new ContactSettings(
                RemissionCommonConfig.INFECTION_CONTACT.get(),
                RemissionCommonConfig.INFECTION_CONTACT_TICKS.get(),
                RemissionCommonConfig.INFECTION_FESTERING_LAND_MIN_STAGE.get(),
                INTERVAL_TICKS, MOB_BUDGET, INFECTION_BUDGET);
    }

    /** The lowest chunk Strain level whose water infects, under the current festering stage thresholds. */
    public int waterMinLevel() {
        return ExposureSettings.levelForStage(waterMinStage);
    }
}
