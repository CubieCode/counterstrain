package com.cubiecode.remission.api;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * A surge started: its warning ran out and, until it ends, spread from its nidus is accelerated, hosts spawn near
 * players within that nidus's reach and miasma clouds drift above its festering land. Posted after the surge's miasma
 * clouds were released. A surge stopped during its warning never starts and posts nothing. Not cancellable; stop a
 * surge with {@code /remission surge stop}. {@link #getCause()} tells a sympathetic surge, set off by a nidus under
 * attack ({@link #getAttackedNidus()}), from the rest (P4.13).
 */
public class SurgeStartedEvent extends FesteringLandEvent {
    private final BlockPos origin;
    private final int intensity;
    private final long endTime;
    private final Cause cause;
    @Nullable
    private final BlockPos attackedNidus;

    /** Why a surge started. */
    public enum Cause {
        /** Its own nidus surged: on schedule, by command or API, or fighting back while under attack. */
        DIRECT,
        /** Another nidus nearby was under attack and passed a vitality threshold (a sympathetic surge, P4.13). */
        SYMPATHETIC
    }

    /** A surge with cause {@link Cause#DIRECT}. */
    public SurgeStartedEvent(ServerLevel level, BlockPos origin, int intensity, long endTime) {
        this(level, origin, intensity, endTime, Cause.DIRECT, null);
    }

    /** A surge with {@code cause}; {@code attackedNidus} is the nidus under attack for a sympathetic surge, else null. */
    public SurgeStartedEvent(ServerLevel level, BlockPos origin, int intensity, long endTime, Cause cause,
                             @Nullable BlockPos attackedNidus) {
        super(level);
        this.origin = origin.immutable();
        this.intensity = intensity;
        this.endTime = endTime;
        this.cause = cause;
        this.attackedNidus = attackedNidus == null ? null : attackedNidus.immutable();
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

    /** Why the surge started: {@link Cause#DIRECT} unless it is a sympathetic surge. */
    public Cause getCause() {
        return cause;
    }

    /** For a sympathetic surge, the position of the nidus under attack that set it off; empty otherwise. */
    public Optional<BlockPos> getAttackedNidus() {
        return Optional.ofNullable(attackedNidus);
    }
}
