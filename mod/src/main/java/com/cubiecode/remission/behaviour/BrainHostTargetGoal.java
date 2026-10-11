package com.cubiecode.remission.behaviour;

import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;

/**
 * Target selection for brain-based hosts. Brain mobs fight through their brain's fight activity, which runs while the
 * {@code attack_target} memory is set, so this goal (ticked by the target selector every brain mob still has) keeps that
 * memory on the nearest visible prey: players first, then any other living non-host. Where the brain also has
 * {@code angry_at} (piglins, whose fight activity only keeps a target they are angry at or otherwise choose), it is set
 * too. Movement and attacks stay the brain's own.
 */
public final class BrainHostTargetGoal extends Goal {
    private static final int INTERVAL = 10;
    private static final long ANGER_TICKS = 600L;

    private final Mob mob;
    private int delay;

    public BrainHostTargetGoal(Mob mob) {
        this.mob = mob;
    }

    /** Whether {@code mob}'s brain has an {@code attack_target} memory, which brain fight activities act on. */
    public static boolean canFight(Mob mob) {
        return mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED);
    }

    @Override
    public boolean canUse() {
        // A profile can give an attacking behaviour to a brain that cannot fight; such a host keeps its own AI.
        return canFight(mob);
    }

    @Override
    public void tick() {
        if (--delay > 0) {
            return;
        }
        delay = INTERVAL;
        Brain<?> brain = mob.getBrain();
        Optional<LivingEntity> current = brain.getMemory(MemoryModuleType.ATTACK_TARGET);
        if (current.isPresent() && current.get().isAlive() && HostBehaviours.isPrey(current.get())) {
            return;
        }
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        LivingEntity prey = findPrey();
        if (prey != null) {
            brain.setMemory(MemoryModuleType.ATTACK_TARGET, prey);
            if (brain.checkMemory(MemoryModuleType.ANGRY_AT, MemoryStatus.REGISTERED)) {
                brain.setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, prey.getUUID(), ANGER_TICKS);
            }
        }
    }

    private LivingEntity findPrey() {
        double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        TargetingConditions conditions = TargetingConditions.forCombat().range(range).selector(HostBehaviours::isPrey);
        Player player = mob.level().getNearestPlayer(conditions, mob);
        if (player != null) {
            return player;
        }
        return mob.level().getNearestEntity(LivingEntity.class, conditions, mob, mob.getX(), mob.getEyeY(), mob.getZ(),
                mob.getBoundingBox().inflate(range, 4.0, range));
    }
}
