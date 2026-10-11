package com.cubiecode.remission.behaviour;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * Wires {@link HostBehaviours} to the game: host goals on join, the attributes attack goals read, and the rule that
 * hosts never target hosts.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class HostBehaviourEvents {
    private HostBehaviourEvents() {
    }

    /** Re-applies host goals to mobs that load or spawn as hosts. Low priority, so other mods' goals are in first. */
    @SubscribeEvent(priority = EventPriority.LOW)
    static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob mob) {
            HostBehaviours.update(mob);
        }
    }

    /**
     * Gives every entity type the attributes host attack and target goals read, at their defaults, where missing:
     * {@code attack_damage} (2) and {@code follow_range} (32; mobs normally get 16 from {@code createMobAttributes}).
     */
    @SubscribeEvent
    static void addAttackAttributes(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            addIfMissing(event, type, Attributes.ATTACK_DAMAGE);
            addIfMissing(event, type, Attributes.FOLLOW_RANGE);
        }
    }

    /**
     * Hosts never target hosts, whatever goal or behaviour picked the target (this also covers hosts with behaviour
     * {@code none}, brains choosing their own targets, and targets that become hosts mid-fight, since target goals re-set
     * their target every tick they continue).
     */
    @SubscribeEvent
    static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target != null && Corruption.isHost(target) && Corruption.isHost(event.getEntity())) {
            event.setNewAboutToBeSetTarget(null);
        }
    }

    private static void addIfMissing(EntityAttributeModificationEvent event, EntityType<? extends LivingEntity> type,
            Holder<Attribute> attribute) {
        if (!event.has(type, attribute)) {
            event.add(type, attribute);
        }
    }
}
