package com.cubiecode.remission.purification;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * Settings of the remission zone passes (P5.5, {@code [remission_zones]} in {@code remission-common.toml}).
 *
 * @param regrowth              whether cleansed surfaces in chunks in remission regrow plants
 * @param regrowthTicks         game ticks from entering remission until regrowth has reached every column of a chunk
 * @param regrowthDensity       share of a chunk's columns that regrow a plant
 * @param saplingChance         share of regrowing columns that get a sapling of the biome's trees instead
 * @param wildlife              whether passive animals return to chunks in remission
 * @param wildlifeIntervalTicks game ticks between wildlife attempts in each chunk in remission
 * @param wildlifeCap           passive animals a chunk in remission holds before no more return
 * @param budget                work units per tick and level: one per chunk visited, column regrown or wildlife attempt
 */
public record RemissionZoneSettings(boolean regrowth, int regrowthTicks, double regrowthDensity, double saplingChance,
                                    boolean wildlife, int wildlifeIntervalTicks, int wildlifeCap, int budget) {
    public static RemissionZoneSettings fromConfig() {
        return new RemissionZoneSettings(
                RemissionCommonConfig.REMISSION_REGROWTH.get(),
                RemissionCommonConfig.REMISSION_REGROWTH_TICKS.get(),
                RemissionCommonConfig.REMISSION_REGROWTH_DENSITY.get(),
                RemissionCommonConfig.REMISSION_SAPLING_CHANCE.get(),
                RemissionCommonConfig.REMISSION_WILDLIFE.get(),
                RemissionCommonConfig.REMISSION_WILDLIFE_INTERVAL_TICKS.get(),
                RemissionCommonConfig.REMISSION_WILDLIFE_CAP.get(),
                RemissionCommonConfig.REMISSION_ZONE_BUDGET.get());
    }
}
