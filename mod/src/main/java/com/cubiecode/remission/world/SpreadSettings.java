package com.cubiecode.remission.world;

import com.cubiecode.remission.config.RemissionCommonConfig;

/**
 * Rates and budgets of the spread engine ({@code [spread]} in {@code remission-common.toml}). A pass runs every
 * {@code intervalTicks}; the chunk holding a nidus gains {@code nidusGrowth} per pass up to {@link ChunkCorruption#MAX_LEVEL},
 * and a chunk {@code d} chunks away (Manhattan distance) gains {@code spreadStep} per pass from its neighbour one step
 * closer to the nidus, up to {@code MAX_LEVEL - d * falloff}, once that neighbour has reached {@code spreadThreshold}.
 *
 * @param chunkBudget   chunk visits per server tick for the live pass; a pass continues on later ticks when it runs out
 * @param catchUpBudget loaded chunks fast-forwarded per server tick
 */
public record SpreadSettings(int intervalTicks, int nidusGrowth, int spreadStep, int falloff, int spreadThreshold,
                             int chunkBudget, int catchUpBudget) {
    public static SpreadSettings fromConfig() {
        return new SpreadSettings(
                RemissionCommonConfig.SPREAD_INTERVAL_TICKS.get(),
                RemissionCommonConfig.SPREAD_NIDUS_GROWTH.get(),
                RemissionCommonConfig.SPREAD_STEP.get(),
                RemissionCommonConfig.SPREAD_FALLOFF.get(),
                RemissionCommonConfig.SPREAD_THRESHOLD.get(),
                RemissionCommonConfig.SPREAD_CHUNK_BUDGET.get(),
                RemissionCommonConfig.SPREAD_CATCH_UP_BUDGET.get());
    }

    /** Highest level a chunk {@code distance} chunks from a nidus can reach through that nidus. */
    public int target(int distance) {
        return distance == 0 ? ChunkCorruption.MAX_LEVEL : Math.max(0, ChunkCorruption.MAX_LEVEL - distance * falloff);
    }

    /** Furthest distance a nidus reaches: its target is above 0 and the chunk before it can reach the threshold. */
    public int radius() {
        int distance = 0;
        while (target(distance + 1) > 0 && target(distance) >= spreadThreshold) {
            distance++;
        }
        return distance;
    }
}
