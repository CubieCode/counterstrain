package com.cubiecode.remission.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Internal points where P4.4 posts the public festering-land events on {@code NeoForge.EVENT_BUS}. Server thread only.
 * Each is called by the code that owns the state change, after it is applied (except the cancellable spread attempt).
 */
final class SpreadHooks {
    private SpreadHooks() {
    }

    /**
     * Before one chunk-to-chunk step, live or catch-up (one call per chunk and nidus during catch-up). P4.4: post the
     * cancellable Strain spread attempt event with a rate multiplier and return the scaled amount, 0 if cancelled.
     */
    static int spreadAttempt(ServerLevel level, ChunkPos from, ChunkPos to, int amount) {
        return amount;
    }

    /**
     * After a chunk's level changed (once per chunk per change; catch-up applies a whole fast-forward as one change).
     * P4.4: post the chunk stage changed event when the festering stage of the level (P4.2) differs.
     */
    static void chunkLevelChanged(ServerLevel level, ChunkPos chunk, int oldLevel, int newLevel) {
    }

    /** After a nidus left the index because its block was removed. P4.4: post the nidus destroyed event. */
    static void nidusDestroyed(ServerLevel level, BlockPos pos) {
    }
}
