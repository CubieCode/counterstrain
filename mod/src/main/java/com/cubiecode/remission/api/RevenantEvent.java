package com.cubiecode.remission.api;

import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

/**
 * Base of the events about a revenant (P5.3): a host risen from a player who died to the Strain, holding that player's
 * items and experience. {@link #getEntity()} is the revenant ({@code remission:revenant}); {@link #getOwner()} is the
 * player who died. Server side, not cancellable. Abstract, so the bus only accepts listeners for
 * {@link RevenantRisenEvent} and {@link RevenantSlainEvent}.
 */
public abstract class RevenantEvent extends LivingEvent {
    private final UUID owner;

    protected RevenantEvent(LivingEntity revenant, UUID owner) {
        super(revenant);
        this.owner = owner;
    }

    /** UUID of the player the revenant rose from. */
    public UUID getOwner() {
        return owner;
    }
}
