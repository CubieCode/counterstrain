package com.cubiecode.remission.corruption;

import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.registry.RemissionAttachments;
import net.minecraft.world.entity.LivingEntity;

/**
 * Server API for an entity's {@link CorruptionState}. Reads work on either side (clients hold the synced copy); writes are
 * server only and sync to every player tracking the entity, and to the entity itself if it is a player.
 */
public final class Corruption {
    private Corruption() {
    }

    /** The entity's state, or {@link CorruptionState#NONE}. Never creates an attachment. */
    public static CorruptionState get(LivingEntity entity) {
        CorruptionState state = entity.getExistingDataOrNull(RemissionAttachments.CORRUPTION);
        return state == null ? CorruptionState.NONE : state;
    }

    /**
     * Sets the entity's level (clamped to {@code [0, MAX_LEVEL]}) and restages it from the common config. Level 0 removes
     * the attachment. Returns the new state.
     */
    public static CorruptionState set(LivingEntity entity, int level) {
        if (entity.level().isClientSide()) {
            throw new IllegalStateException("Corruption state is server-authoritative; set it on the server");
        }
        CorruptionState state = CorruptionState.of(level, RemissionCommonConfig.STAGE_THRESHOLDS.get());
        if (state.level() == 0) {
            entity.removeData(RemissionAttachments.CORRUPTION);
        } else if (!state.equals(get(entity))) {
            entity.setData(RemissionAttachments.CORRUPTION, state);
        }
        return state;
    }

    /** Whether the entity is a host (stage 1 or higher). */
    public static boolean isHost(LivingEntity entity) {
        return get(entity).isHost();
    }
}
