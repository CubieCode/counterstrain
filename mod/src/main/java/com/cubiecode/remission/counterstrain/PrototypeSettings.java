package com.cubiecode.remission.counterstrain;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * The {@code [prototypes]} config (P7.3).
 *
 * @param radius blocks around the landing point whose festered blocks and veins are cleansed (a cube)
 * @param step Strain level the landing chunk loses
 * @param backfireChance chance a prototype backfires instead
 * @param backfireViralLoad viral load a backfire gives every player within {@code radius} blocks
 */
public record PrototypeSettings(int radius, int step, double backfireChance, double backfireViralLoad) {
    public static PrototypeSettings fromConfig() {
        return new PrototypeSettings(RemissionCommonConfig.PROTOTYPE_RADIUS.get(), RemissionCommonConfig.PROTOTYPE_STEP.get(),
                RemissionCommonConfig.PROTOTYPE_BACKFIRE_CHANCE.get(), RemissionCommonConfig.PROTOTYPE_BACKFIRE_VIRAL_LOAD.get());
    }
}
