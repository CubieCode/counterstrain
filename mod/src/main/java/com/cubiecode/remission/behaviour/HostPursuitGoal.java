package com.cubiecode.remission.behaviour;

import java.util.EnumSet;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.phys.Vec3;

/**
 * The attack goal of {@code flying} and {@code swimming} hosts: closes on the target through the air or water and hits it
 * in melee reach ({@link Mob#doHurtTarget}, so the {@code attack_damage} attribute sets the damage). A swimming host
 * pursues only while it and its target are both in water.
 *
 * <p>Movement uses the mob's flying or water-bound navigation when it has one and a path exists; otherwise (bats and
 * other mobs that steer themselves, or no path) it steers the mob's velocity straight at the target. Vanilla squids move
 * only along their movement vector, so a squid is steered through that.
 */
public final class HostPursuitGoal extends Goal {
    private static final int ATTACK_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    private static final double PATH_SPEED = 1.2;
    /** Blocks per tick a self-steered host approaches; velocity eases halfway towards it each tick. */
    private static final double STEER_SPEED = 0.35;
    private static final float SQUID_THRUST = 0.25F;

    private final Mob mob;
    private final boolean swimming;
    private int attackCooldown;
    private int repathDelay;
    private boolean pathing;

    public HostPursuitGoal(Mob mob, boolean swimming) {
        this.mob = mob;
        this.swimming = swimming;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && HostBehaviours.isPrey(target)
                && (!swimming || (mob.isInWater() && target.isInWater()));
    }

    @Override
    public void start() {
        repathDelay = 0;
        pathing = false;
        if (mob instanceof Bat bat) {
            bat.setResting(false);
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (mob instanceof Squid squid) {
            squid.setMovementVector(0.0F, 0.0F, 0.0F);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        move(target);
        attackCooldown = Math.max(attackCooldown - 1, 0);
        if (attackCooldown == 0 && mob.isWithinMeleeAttackRange(target) && mob.getSensing().hasLineOfSight(target)) {
            attackCooldown = adjustedTickDelay(ATTACK_INTERVAL);
            mob.swing(InteractionHand.MAIN_HAND);
            mob.doHurtTarget(target);
        }
    }

    private void move(LivingEntity target) {
        Vec3 offset = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0)
                .subtract(mob.position().add(0.0, mob.getBbHeight() * 0.5, 0.0));
        Vec3 direction = offset.lengthSqr() < 1.0E-4 ? Vec3.ZERO : offset.normalize();
        if (mob instanceof Squid squid) {
            squid.setMovementVector((float) direction.x * SQUID_THRUST, (float) direction.y * SQUID_THRUST,
                    (float) direction.z * SQUID_THRUST);
            return;
        }
        PathNavigation navigation = mob.getNavigation();
        if (navigation instanceof FlyingPathNavigation || navigation instanceof WaterBoundPathNavigation) {
            if (--repathDelay <= 0) {
                repathDelay = adjustedTickDelay(REPATH_INTERVAL);
                pathing = navigation.moveTo(target, PATH_SPEED);
            }
            if (pathing && !navigation.isDone()) {
                return;
            }
        }
        mob.setDeltaMovement(mob.getDeltaMovement().scale(0.5).add(direction.scale(STEER_SPEED * 0.5)));
        float yaw = (float) (Mth.atan2(offset.z, offset.x) * Mth.RAD_TO_DEG) - 90.0F;
        mob.setYRot(yaw);
        mob.setYBodyRot(yaw);
    }
}
