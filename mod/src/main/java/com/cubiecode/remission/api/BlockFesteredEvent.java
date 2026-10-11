package com.cubiecode.remission.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block in festering land turned into its festering form because its chunk reached the festering stage the
 * {@code remission:festers_into} data map names for it. Posted after the block is set, once per block (block-scoped, so
 * a chunk's bounded stage batch posts one per changed block). Not cancellable; listeners must not set the block at
 * {@link #getPos()} again in the listener.
 */
public class BlockFesteredEvent extends FesteringLandEvent {
    private final BlockPos pos;
    private final BlockState oldState;
    private final BlockState newState;

    public BlockFesteredEvent(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        super(level);
        this.pos = pos.immutable();
        this.oldState = oldState;
        this.newState = newState;
    }

    public BlockPos getPos() {
        return pos;
    }

    /** The block state before it festered. */
    public BlockState getOldState() {
        return oldState;
    }

    /** The festering block state now at {@link #getPos()}. */
    public BlockState getNewState() {
        return newState;
    }
}
