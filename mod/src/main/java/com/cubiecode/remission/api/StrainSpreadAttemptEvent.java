package com.cubiecode.remission.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * The Strain is about to spread from one loaded chunk into its neighbour {@link #getTo()}, the chunk one step further
 * from a nidus. Posted before the step, for every live spread step and for every nidus a catch-up credits a reloaded
 * chunk with (then {@link #isCatchUp()} is true, {@link #getFrom()} may be unloaded and {@link #getAmount()} covers all
 * the passes it missed). The growth of a nidus's own chunk is not a spread attempt.
 *
 * <p>Cancel it to stop this step (a firebreak); spread past a blocked chunk stops too, because every chunk is reached
 * through its parent. Or set {@link #setRateMultiplier rate multiplier} to scale it: the level gained is
 * {@code amount * multiplier}, its fraction resolved deterministically per chunk and game time, capped at the chunk's
 * target. Listeners compose by multiplying ({@code event.setRateMultiplier(event.getRateMultiplier() * 0.5)}).
 */
public class StrainSpreadAttemptEvent extends FesteringLandEvent implements ICancellableEvent {
    private final ChunkPos from;
    private final ChunkPos to;
    private final int amount;
    private final boolean catchUp;
    private double rateMultiplier = 1.0;

    public StrainSpreadAttemptEvent(ServerLevel level, ChunkPos from, ChunkPos to, int amount, boolean catchUp) {
        super(level);
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.catchUp = catchUp;
    }

    /** The chunk the Strain spreads from (the neighbour of {@link #getTo()} one step closer to the nidus). */
    public ChunkPos getFrom() {
        return from;
    }

    /** The chunk that would gain level. */
    public ChunkPos getTo() {
        return to;
    }

    /** Level {@link #getTo()} would gain at multiplier 1, already capped at its target. */
    public int getAmount() {
        return amount;
    }

    /** Whether this step is part of a reloaded chunk's catch-up rather than a live pass. */
    public boolean isCatchUp() {
        return catchUp;
    }

    public double getRateMultiplier() {
        return rateMultiplier;
    }

    /** Scales this step; 1 by default, 0 has the same effect as cancelling. Negative or NaN values are treated as 0. */
    public void setRateMultiplier(double rateMultiplier) {
        this.rateMultiplier = rateMultiplier > 0 ? rateMultiplier : 0;
    }
}
