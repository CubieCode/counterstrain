package com.cubiecode.remission.world;

import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.StrainSpreadAttemptEvent;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Internal points where the public festering-land events ({@code com.cubiecode.remission.api}) are posted on
 * {@code NeoForge.EVENT_BUS}. Server thread only. Each is called by the code that owns the state change, after it is
 * applied (except the cancellable spread attempt).
 */
final class SpreadHooks {
    private SpreadHooks() {
    }

    /**
     * Before one chunk-to-chunk step, live or catch-up (one call per chunk and nidus during catch-up). Posts
     * {@link StrainSpreadAttemptEvent} and returns the amount scaled by its rate multiplier, 0 if cancelled.
     */
    static int spreadAttempt(ServerLevel level, long from, long to, int amount, long gameTime, boolean catchUp) {
        StrainSpreadAttemptEvent event = NeoForge.EVENT_BUS.post(
                new StrainSpreadAttemptEvent(level, new ChunkPos(from), new ChunkPos(to), amount, catchUp));
        if (event.isCanceled()) {
            return 0;
        }
        return scale(amount, event.getRateMultiplier(), from, to, gameTime);
    }

    /**
     * {@code amount * multiplier}, its fraction rounded up with that probability from a hash of the step and game time,
     * so slow rates still spread on average and the same state and ticks give the same levels.
     */
    static int scale(int amount, double multiplier, long from, long to, long gameTime) {
        if (multiplier == 1.0) {
            return amount;
        }
        double scaled = amount * multiplier;
        if (scaled >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        int whole = (int) scaled;
        double fraction = scaled - whole;
        if (fraction > 0) {
            long hash = HashCommon.mix(from * 31 + HashCommon.mix(to * 31 + gameTime));
            if ((hash >>> 11) * 0x1.0p-53 < fraction) {
                whole++;
            }
        }
        return whole;
    }

    /**
     * After a chunk's level changed (once per chunk per change; catch-up applies a whole fast-forward as one change).
     * Posts {@link ChunkStageChangedEvent} when the festering stage of the level differs.
     */
    static void chunkLevelChanged(ServerLevel level, ChunkPos chunk, int oldLevel, int newLevel) {
        int oldStage = CorruptionQueries.chunkStageFor(oldLevel);
        int newStage = CorruptionQueries.chunkStageFor(newLevel);
        if (oldStage != newStage) {
            NeoForge.EVENT_BUS.post(new ChunkStageChangedEvent(level, chunk, oldLevel, newLevel, oldStage, newStage));
        }
    }

    /** After a nidus left the index because its block was removed. Posts {@link NidusDestroyedEvent}. */
    static void nidusDestroyed(ServerLevel level, BlockPos pos) {
        nidusDestroyed(level, pos, null, List.of());
    }

    /** As {@link #nidusDestroyed(ServerLevel, BlockPos)}, for a nidus a player destroyed with these contributors. */
    static void nidusDestroyed(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player, List<UUID> contributors) {
        NeoForge.EVENT_BUS.post(new NidusDestroyedEvent(level, pos, player, contributors));
    }
}
