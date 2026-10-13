package com.cubiecode.remission.api;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A started surge ended (see {@link SurgeStartedEvent}). Posted after spread acceleration and host spawning stopped and
 * after the survivors were awarded the {@code remission:surge_survived} trigger and stat. Miasma clouds it released keep
 * drifting until they dissipate. Not cancellable.
 */
public class SurgeEndedEvent extends FesteringLandEvent {
    /** Why a surge ended. */
    public enum Reason {
        /** Its duration ran out. */
        ELAPSED,
        /** Its nidus was destroyed. */
        NIDUS_DESTROYED,
        /** {@code /remission surge stop} or another caller stopped it. */
        STOPPED,
        /** Another surge took its place: a nidus under attack surged at once (P4.10). */
        SUPERSEDED
    }

    private final BlockPos origin;
    private final int intensity;
    private final Reason reason;
    private final List<ServerPlayer> survivors;

    public SurgeEndedEvent(ServerLevel level, BlockPos origin, int intensity, Reason reason, List<ServerPlayer> survivors) {
        super(level);
        this.origin = origin.immutable();
        this.intensity = intensity;
        this.reason = reason;
        this.survivors = List.copyOf(survivors);
    }

    /** Position of the nidus the surge came from (it may be gone, see {@link Reason#NIDUS_DESTROYED}). */
    public BlockPos getOrigin() {
        return origin;
    }

    /** Intensity, 1 to 10. */
    public int getIntensity() {
        return intensity;
    }

    public Reason getReason() {
        return reason;
    }

    /**
     * Players who were within the surge's area while it ran, did not die since, and were online when it ended; the
     * players who earned {@code remission:surge_survived} (fake players excluded). Immutable.
     */
    public List<ServerPlayer> getSurvivors() {
        return survivors;
    }
}
