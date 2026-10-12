package com.cubiecode.remission.viralload;

import com.mojang.serialization.Codec;

/**
 * A player's viral load, 0 to {@link #MAX}, and which way it moved in its last update. Only the load is saved; the trend
 * is transient (it is what the HUD shows next to the meter) and reads {@link Trend#STEADY} after a load.
 */
public record ViralLoadState(float load, Trend trend) {
    /** Highest viral load. */
    public static final int MAX = 100;
    public static final ViralLoadState NONE = new ViralLoadState(0.0F, Trend.STEADY);
    /** The saved form: the load alone. */
    public static final Codec<ViralLoadState> CODEC = Codec.floatRange(0.0F, MAX)
            .xmap(load -> new ViralLoadState(load, Trend.STEADY), ViralLoadState::load);

    public ViralLoadState {
        load = Math.clamp(load, 0.0F, MAX);
    }

    /** Which way viral load moved in its last update. */
    public enum Trend {
        STEADY,
        RISING,
        FALLING;

        private static final Trend[] VALUES = values();

        public static Trend byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : STEADY;
        }
    }
}
