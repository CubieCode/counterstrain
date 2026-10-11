package com.cubiecode.remission.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * One entry of the {@link NidusIndex}.
 *
 * @param pos   position of the {@code remission:nidus} block
 * @param since level game time the nidus was indexed; catch-up never credits spread from before it
 */
public record Nidus(BlockPos pos, long since) {
    public long chunk() {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
