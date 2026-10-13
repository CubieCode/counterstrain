package com.cubiecode.remission.serum;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * The {@code [serums]} config (P7.3).
 *
 * @param viralLoadDrop viral load a serum takes off at once
 * @param inoculationTicks ticks of {@code remission:inoculated} a serum gives (0 gives none)
 * @param inoculationFactor fraction of viral load gain an inoculated player still takes
 */
public record SerumSettings(double viralLoadDrop, int inoculationTicks, double inoculationFactor) {
    /** Lowest {@code inoculationFactor}: inoculation slows viral load, it never grants immunity. */
    public static final double MIN_INOCULATION_FACTOR = 0.1;

    public SerumSettings {
        inoculationFactor = Math.clamp(inoculationFactor, MIN_INOCULATION_FACTOR, 1.0);
    }

    public static SerumSettings fromConfig() {
        return new SerumSettings(RemissionCommonConfig.SERUM_VIRAL_LOAD_DROP.get(),
                RemissionCommonConfig.SERUM_INOCULATION_TICKS.get(), RemissionCommonConfig.SERUM_INOCULATION_FACTOR.get());
    }
}
