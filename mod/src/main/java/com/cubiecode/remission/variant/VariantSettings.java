package com.cubiecode.remission.variant;

import com.cubiecode.remission.config.RemissionCommonConfig;
import org.jetbrains.annotations.Nullable;

/**
 * {@code [variants]} (P7.5). Read from the config on every use (cheap); gametests replace it for their batch with
 * {@link #override}.
 *
 * @param thresholdScale multiplies each variant's threshold
 * @param minShare       share of a nidus's total pressure a method needs besides its threshold
 * @param counterTicks   how long a variant's serum or fuel counters it
 */
public record VariantSettings(boolean enabled, double thresholdScale, double minShare, int counterTicks) {
    @Nullable
    private static volatile VariantSettings override;

    public static VariantSettings current() {
        VariantSettings settings = override;
        return settings != null ? settings : new VariantSettings(
                RemissionCommonConfig.VARIANTS_ENABLED.get(),
                RemissionCommonConfig.VARIANTS_THRESHOLD_SCALE.get(),
                RemissionCommonConfig.VARIANTS_MIN_SHARE.get(),
                RemissionCommonConfig.VARIANTS_COUNTER_TICKS.get());
    }

    /** Replaces the config settings until called again with null (tests). */
    public static void override(@Nullable VariantSettings settings) {
        override = settings;
    }
}
