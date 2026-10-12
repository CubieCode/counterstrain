package com.cubiecode.remission.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A surge started: its warning ran out and, until it ends, spread from its nidus is accelerated, hosts spawn near
 * players within that nidus's reach and miasma clouds drift above its festering land. Posted after the surge's miasma
 * clouds were released. A surge stopped during its warning never starts and posts nothing. Not cancellable; stop a
 * surge with {@code /remission surge stop}.
 */
public class SurgeStartedEvent extends FesteringLandEvent {
    private final BlockPos origin;
    private final int intensity;
    private final long endTime;

    public SurgeStartedEvent(ServerLevel level, BlockPos origin, int intensity, long endTime) {
        super(level);
        this.origin = origin.immutable();
        this.intensity = intensity;
        this.endTime = endTime;
    }

    /** Position of the nidus the surge comes from; its reach is the surge's area. */
    public BlockPos getOrigin() {
        return origin;
    }

    /** Intensity, 1 to 10: scales the spread acceleration, hosts per wave and clouds. */
    public int getIntensity() {
        return intensity;
    }

    /** Level game time at which the surge is due to end, unless it is cut short. */
    public long getEndTime() {
        return endTime;
    }
}
