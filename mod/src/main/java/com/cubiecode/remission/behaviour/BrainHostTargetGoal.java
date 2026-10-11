package com.cubiecode.remission.behaviour;

import java.util.Optional;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;

/**
 * Target selection for brain-based hosts. Brain mobs fight through their brain's fight activity, which runs while the
 * {@code attack_target} memory is set, so this goal (ticked by the target selector every brain mob still has) keeps that
 * memory on the nearest visible prey within {@code follow_range}, capped at {@link #MAX_RANGE} (the range vanilla brain
 * sensors and target checks use): players first, then any other living non-host. Fight activities drop targets their own
 * checks reject, so the goal also feeds what those checks read, and refreshes it while the target stays: the
 * {@code angry_at} memory where the brain has it (piglins and piglin brutes keep only a target they are angry at or chose
 * themselves) and a warden's anger (a warden fights only while angry at its target). Movement and attacks stay the
 * brain's own.
 */
public final class BrainHostTargetGoal extends Goal {
    private static final int INTERVAL = 10;
    private static final long ANGER_TICKS = 600L;
    private static final long WARDEN_DIG_COOLDOWN = 1200L;
    /** Blocks; vanilla brain sensors see 16 blocks and piglin and breeze target checks drop anything further. */
    private static final double MAX_RANGE = 16.0;

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
    public boolean requiresUpdateEveryTick() {
        // Otherwise the selector ticks the goal every other tick and the interval doubles.
        return true;
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
            provoke(brain, current.get());
            return;
        }
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        LivingEntity prey = findPrey();
        if (prey != null) {
            provoke(brain, prey);
            brain.setMemory(MemoryModuleType.ATTACK_TARGET, prey);
        }
    }

    /** Makes the brain's fight checks accept {@code prey}: anger it, where the brain tracks anger. */
    private void provoke(Brain<?> brain, LivingEntity prey) {
        if (brain.checkMemory(MemoryModuleType.ANGRY_AT, MemoryStatus.REGISTERED)) {
            brain.setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, prey.getUUID(), ANGER_TICKS);
        }
        if (mob instanceof Warden warden) {
            warden.increaseAngerAt(prey, AngerLevel.ANGRY.getMinimumAnger(), false);
            // A warden digs back underground whenever it has no dig cooldown; WardenAi only renews an existing one, and a
            // warden that did not emerge (spawned or converted) has none, so it would dig instead of fighting.
            brain.setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, WARDEN_DIG_COOLDOWN);
        }
    }

    private LivingEntity findPrey() {
        double range = Math.min(mob.getAttributeValue(Attributes.FOLLOW_RANGE), MAX_RANGE);
        TargetingConditions conditions = TargetingConditions.forCombat().range(range).selector(HostBehaviours::isPrey);
        Player player = mob.level().getNearestPlayer(conditions, mob);
        if (player != null) {
            return player;
        }
        return mob.level().getNearestEntity(LivingEntity.class, conditions, mob, mob.getX(), mob.getEyeY(), mob.getZ(),
                mob.getBoundingBox().inflate(range, 4.0, range));
    }
}
