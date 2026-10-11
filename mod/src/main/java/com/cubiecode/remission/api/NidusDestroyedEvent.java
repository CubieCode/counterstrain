package com.cubiecode.remission.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A nidus stopped existing and left the level's index, so its spread has stopped. Posted after removal, whether the
 * block was broken, replaced or edited away (an index entry found without its block on a spread pass also counts). Land
 * it festered keeps its level. Not cancellable; to protect a nidus, cancel the block break instead.
 */
public class NidusDestroyedEvent extends FesteringLandEvent {
    private final BlockPos pos;

    public NidusDestroyedEvent(ServerLevel level, BlockPos pos) {
        super(level);
        this.pos = pos.immutable();
    }

    /** Position of the nidus block that was removed. */
    public BlockPos getPos() {
        return pos;
    }
}
