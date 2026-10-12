package com.cubiecode.remission.purification;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * Purification settings ({@code [purification]} in {@code remission-common.toml}).
 *
 * @param intervalTicks         game ticks between quarantine pylon passes
 * @param pylonRadius           chunks a pylon wards around its own, in a square
 * @param pylonStep             Strain level each warded chunk loses per pass
 * @param purifierRadius        blocks around the used block a handheld purifier cleanses (a cube)
 * @param purifierStep          Strain level the chunk of that block loses per use
 * @param purifierHostStep      Strain level a host loses when a handheld purifier is used on it
 * @param mendrootRadius        blocks around a mendroot it cleanses (a cube)
 * @param mendrootStep          Strain level a mendroot's chunk loses per cleanse
 * @param mendrootIntervalTicks game ticks between a mendroot's cleanses
 */
public record PurificationSettings(int intervalTicks, int pylonRadius, int pylonStep, int purifierRadius, int purifierStep,
                                   int purifierHostStep, int mendrootRadius, int mendrootStep, int mendrootIntervalTicks) {
    public static PurificationSettings fromConfig() {
        return new PurificationSettings(
                RemissionCommonConfig.PURIFICATION_INTERVAL_TICKS.get(),
                RemissionCommonConfig.PURIFICATION_PYLON_RADIUS.get(),
                RemissionCommonConfig.PURIFICATION_PYLON_STEP.get(),
                RemissionCommonConfig.PURIFICATION_PURIFIER_RADIUS.get(),
                RemissionCommonConfig.PURIFICATION_PURIFIER_STEP.get(),
                RemissionCommonConfig.PURIFICATION_PURIFIER_HOST_STEP.get(),
                RemissionCommonConfig.PURIFICATION_MENDROOT_RADIUS.get(),
                RemissionCommonConfig.PURIFICATION_MENDROOT_STEP.get(),
                RemissionCommonConfig.PURIFICATION_MENDROOT_INTERVAL_TICKS.get());
    }
}
