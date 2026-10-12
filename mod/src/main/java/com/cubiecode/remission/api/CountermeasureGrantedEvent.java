package com.cubiecode.remission.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * A player gained a countermeasure (P5.7). Posted on the server after it is stored, so {@link StrainHazards#holds}
 * already returns true, once per player who gained it: a protocol or command grant posts it for the player, then once
 * for each online teammate who gained it with cause {@link Cause#TEAM}. Not posted for a countermeasure the player
 * already held. Not cancellable.
 */
public class CountermeasureGrantedEvent extends PlayerEvent {
    /** How the player gained it. */
    public enum Cause {
        /** They used a countermeasure protocol. */
        PROTOCOL,
        /** A teammate gained it, or they shared their team's when they logged in or joined a team. */
        TEAM,
        /** {@code /remission countermeasure grant}. */
        COMMAND,
        /** Anything else: other mods through {@link StrainHazards#grant}. */
        OTHER
    }

    private final ResourceLocation hazard;
    private final Cause cause;

    public CountermeasureGrantedEvent(ServerPlayer player, ResourceLocation hazard, Cause cause) {
        super(player);
        this.hazard = hazard;
        this.cause = cause;
    }

    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }

    /** The hazard the player now holds the countermeasure for. */
    public ResourceLocation getHazard() {
        return hazard;
    }

    public Cause getCause() {
        return cause;
    }
}
