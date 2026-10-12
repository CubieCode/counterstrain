package com.cubiecode.remission.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A festered block was cleansed back into a clean block through the {@code remission:cleanses_into} data map, or a
 * festering vein was cleared (new state air). Posted after the block is set, once per block (block-scoped, so a chunk's
 * bounded cleanse posts one per changed block). Not cancellable; listeners must not set the block at {@link #getPos()}
 * again in the listener.
 */
public class BlockPurifiedEvent extends FesteringLandEvent {
    private final BlockPos pos;
    private final BlockState oldState;
    private final BlockState newState;
    private final PurificationCause cause;

    public BlockPurifiedEvent(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState, PurificationCause cause) {
        super(level);
        this.pos = pos.immutable();
        this.oldState = oldState;
        this.newState = newState;
        this.cause = cause;
    }

    public BlockPos getPos() {
        return pos;
    }

    /** The festered block state before it was cleansed. */
    public BlockState getOldState() {
        return oldState;
    }

    /** The clean block state now at {@link #getPos()} (air for a cleared vein). */
    public BlockState getNewState() {
        return newState;
    }

    public PurificationCause getCause() {
        return cause;
    }
}
