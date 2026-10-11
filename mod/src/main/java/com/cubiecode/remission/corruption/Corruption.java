package com.cubiecode.remission.corruption;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.HostBehaviours;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.infection.HostAttributes;
import com.cubiecode.remission.registry.RemissionAttachments;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Server API for an entity's {@link CorruptionState}. Reads work on either side (clients hold the synced copy); writes are
 * server only and sync to every player tracking the entity, and to the entity itself if it is a player.
 */
public final class Corruption {
    /** Entity types that never become hosts ({@code #remission:immune}). */
    public static final TagKey<EntityType<?>> IMMUNE = TagKey.create(Registries.ENTITY_TYPE, Remission.id("immune"));

    private Corruption() {
    }

    /** The entity's state, or {@link CorruptionState#NONE}. Never creates an attachment. */
    public static CorruptionState get(LivingEntity entity) {
        CorruptionState state = entity.getExistingDataOrNull(RemissionAttachments.CORRUPTION);
        return state == null ? CorruptionState.NONE : state;
    }

    /**
     * Sets the entity's level (clamped to {@code [0, MAX_LEVEL]}) and restages it from the common config. Level 0 removes
     * the attachment. Returns the new state. An {@linkplain #isImmune immune} entity ignores any level above 0 and keeps
     * (and returns) its current state; level 0 always cleanses. A mob that becomes or stops being a host gets or loses its
     * mob profile's attribute modifiers ({@link HostAttributes}), then its host goals ({@link HostBehaviours}), at once.
     */
    public static CorruptionState set(LivingEntity entity, int level) {
        if (entity.level().isClientSide()) {
            throw new IllegalStateException("Corruption state is server-authoritative; set it on the server");
        }
        CorruptionState old = get(entity);
        if (level > 0 && isImmune(entity)) {
            return old;
        }
        CorruptionState state = CorruptionState.of(level, RemissionCommonConfig.STAGE_THRESHOLDS.get());
        if (state.level() == 0) {
            entity.removeData(RemissionAttachments.CORRUPTION);
        } else if (!state.equals(old)) {
            entity.setData(RemissionAttachments.CORRUPTION, state);
        }
        if (state.isHost() != old.isHost()) {
            HostAttributes.update(entity);
        }
        if (entity instanceof Mob mob) {
            HostBehaviours.update(mob);
        }
        return state;
    }

    /** Whether the entity's type is in {@code #remission:immune}, so it can never become a host. */
    public static boolean isImmune(LivingEntity entity) {
        return entity.getType().is(IMMUNE);
    }

    /** Whether the entity is a host (stage 1 or higher). */
    public static boolean isHost(LivingEntity entity) {
        return get(entity).isHost();
    }
}
