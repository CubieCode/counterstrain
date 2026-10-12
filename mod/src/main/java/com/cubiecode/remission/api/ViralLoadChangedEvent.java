package com.cubiecode.remission.api;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * A player's viral load changed (P5.1). Posted on the server after the new value is stored, so
 * {@link CorruptionQueries#viralLoad} already returns it, at most once per player per update (once a second) plus once per
 * drink. Viral load is 0 to {@link CorruptionQueries#MAX_VIRAL_LOAD}. Not posted when only the trend changes, nor for the
 * reset to 0 on death (the respawned player starts clean). Not cancellable.
 */
public class ViralLoadChangedEvent extends PlayerEvent {
    /** What changed the viral load. */
    public enum Cause {
        /** Festering land, water in festering land or a miasma cloud added to it. */
        EXPOSURE,
        /** A water bottle drunk in festering land. */
        DRINK,
        /** It fell off while nothing added to it. */
        DECAY,
        /** A nidus under attack lashed the player (P4.10). */
        NIDUS,
        /** A serum was drunk or given (P7.3). */
        SERUM,
        /** Anything else: commands, the harness, other mods through Remission's internals. */
        OTHER
    }

    private final float oldLoad;
    private final float newLoad;
    private final Cause cause;

    public ViralLoadChangedEvent(ServerPlayer player, float oldLoad, float newLoad, Cause cause) {
        super(player);
        this.oldLoad = oldLoad;
        this.newLoad = newLoad;
        this.cause = cause;
    }

    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }

    public float getOldLoad() {
        return oldLoad;
    }

    public float getNewLoad() {
        return newLoad;
    }

    public Cause getCause() {
        return cause;
    }

    /** Whether the viral load went up. */
    public boolean isRising() {
        return newLoad > oldLoad;
    }
}
