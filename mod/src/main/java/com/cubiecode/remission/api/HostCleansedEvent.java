package com.cubiecode.remission.api;

import net.minecraft.world.entity.LivingEntity;

/**
 * An entity's Strain level fell (or, at the same level, its stage fell after a threshold change). Posted for partial
 * cleansing too; {@link #isFullyCleansed()} picks out the change to level 0.
 */
public class HostCleansedEvent extends HostEvent {
    public HostCleansedEvent(LivingEntity entity, int oldLevel, int oldStage, int newLevel, int newStage, CorruptionCause cause) {
        super(entity, oldLevel, oldStage, newLevel, newStage, cause);
    }

    /** Whether the entity is clean (level 0) after the change. */
    public boolean isFullyCleansed() {
        return getNewLevel() == 0;
    }
}
