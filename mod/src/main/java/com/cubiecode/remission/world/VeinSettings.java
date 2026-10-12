package com.cubiecode.remission.world;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * Festering vein settings ({@code [festering] veins} and {@code veinMinStage} in {@code remission-common.toml}; P4.8).
 *
 * @param enabled  whether veins grow at all (off leaves grown veins in place and grows no more)
 * @param minStage lowest festering stage of a chunk that grows veins
 */
public record VeinSettings(boolean enabled, int minStage) {
    public static VeinSettings fromConfig() {
        return new VeinSettings(RemissionCommonConfig.FESTERING_VEINS.get(), RemissionCommonConfig.FESTERING_VEIN_MIN_STAGE.get());
    }
}
