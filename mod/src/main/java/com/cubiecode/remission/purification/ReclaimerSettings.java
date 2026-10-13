package com.cubiecode.remission.purification;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * Reclaimer settings ({@code [reclaimers]} in {@code remission-common.toml}, P7.4).
 *
 * @param radius             chunks a reclaimer drains around its own, in a square
 * @param intervalTicks      game ticks between operations
 * @param drainStep          Strain level the drained chunk loses per operation
 * @param energyPerOperation FE one operation uses
 * @param energyCapacity     FE a reclaimer stores
 * @param maxEnergyInput     FE a reclaimer accepts per insert
 * @param fuelPerItem        operations one purifier fuel item runs
 * @param fuelPerBucket      operations 1000 mB of a purifier fuel fluid runs (0 refuses fluid fuel)
 */
public record ReclaimerSettings(int radius, int intervalTicks, int drainStep, int energyPerOperation, int energyCapacity,
                                int maxEnergyInput, int fuelPerItem, int fuelPerBucket) {
    /** The largest radius the config allows; the screen's map is sized for it. */
    public static final int MAX_RADIUS = 8;

    public static ReclaimerSettings fromConfig() {
        return new ReclaimerSettings(
                RemissionCommonConfig.RECLAIMER_RADIUS.get(),
                RemissionCommonConfig.RECLAIMER_INTERVAL_TICKS.get(),
                RemissionCommonConfig.RECLAIMER_DRAIN_STEP.get(),
                RemissionCommonConfig.RECLAIMER_ENERGY_PER_OPERATION.get(),
                RemissionCommonConfig.RECLAIMER_ENERGY_CAPACITY.get(),
                RemissionCommonConfig.RECLAIMER_MAX_ENERGY_INPUT.get(),
                RemissionCommonConfig.RECLAIMER_FUEL_PER_ITEM.get(),
                RemissionCommonConfig.RECLAIMER_FUEL_PER_BUCKET.get());
    }
}
