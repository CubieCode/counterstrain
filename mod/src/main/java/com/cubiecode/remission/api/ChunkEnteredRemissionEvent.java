package com.cubiecode.remission.api;

import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * A festering chunk was purified to Strain level 0 and entered remission ({@link CorruptionQueries#isInRemission}).
 * Posted once per chunk, after the level and the remission mark are stored and before the chunk's remaining festered
 * blocks are cleansed (that cleanse is budgeted and posts {@link BlockPurifiedEvent}s over the following ticks). A
 * chunk that festers again leaves remission silently and can enter it again later. Not cancellable.
 */
public class ChunkEnteredRemissionEvent extends FesteringLandEvent {
    private final ChunkPos chunk;
    private final PurificationCause cause;
    @Nullable
    private final ServerPlayer player;

    public ChunkEnteredRemissionEvent(ServerLevel level, ChunkPos chunk, PurificationCause cause, @Nullable ServerPlayer player) {
        super(level);
        this.chunk = chunk;
        this.cause = cause;
        this.player = player;
    }

    public ChunkPos getChunk() {
        return chunk;
    }

    public PurificationCause getCause() {
        return cause;
    }

    /** The player whose handheld purifier finished the chunk; empty for pylons, mendroot and other causes. */
    public Optional<ServerPlayer> getPlayer() {
        return Optional.ofNullable(player);
    }
}
