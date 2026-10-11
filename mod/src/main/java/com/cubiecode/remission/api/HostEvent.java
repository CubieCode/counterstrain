package com.cubiecode.remission.api;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

/**
 * Base of the events posted when an entity's Strain level or host stage changes. Server side, after the change is
 * applied (attachment, host attributes and host goals included), so {@link CorruptionQueries} already reports the new
 * state. Levels are 0 to {@link CorruptionQueries#MAX_LEVEL}; a stage above 0 means the entity is a host. Not
 * cancellable. Abstract, so the bus only accepts listeners for {@link HostCorruptedEvent} and {@link HostCleansedEvent}.
 */
public abstract class HostEvent extends LivingEvent {
    private final int oldLevel;
    private final int oldStage;
    private final int newLevel;
    private final int newStage;
    private final CorruptionCause cause;

    protected HostEvent(LivingEntity entity, int oldLevel, int oldStage, int newLevel, int newStage, CorruptionCause cause) {
        super(entity);
        this.oldLevel = oldLevel;
        this.oldStage = oldStage;
        this.newLevel = newLevel;
        this.newStage = newStage;
        this.cause = cause;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getOldStage() {
        return oldStage;
    }

    public int getNewLevel() {
        return newLevel;
    }

    public int getNewStage() {
        return newStage;
    }

    public CorruptionCause getCause() {
        return cause;
    }

    /** Whether the entity was a host before the change. */
    public boolean wasHost() {
        return oldStage > 0;
    }

    /** Whether the entity is a host after the change. */
    public boolean isHost() {
        return newStage > 0;
    }
}
