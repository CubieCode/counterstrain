package com.cubiecode.remission.api;

import net.minecraft.world.entity.LivingEntity;

/**
 * An entity's Strain level rose (or, at the same level, its stage rose after a threshold change). Posted for every
 * increase, not only the first; {@link #becameHost()} picks out the one that turned it into a host.
 */
public class HostCorruptedEvent extends HostEvent {
    public HostCorruptedEvent(LivingEntity entity, int oldLevel, int oldStage, int newLevel, int newStage, CorruptionCause cause) {
        super(entity, oldLevel, oldStage, newLevel, newStage, cause);
    }

    /** Whether this change turned a non-host into a host. */
    public boolean becameHost() {
        return !wasHost() && isHost();
    }
}
