package com.cubiecode.remission.behaviour;

import com.cubiecode.remission.behaviour.AppliedHostGoals.Slot;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionAttachments;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.BegGoal;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FollowBoatGoal;
import net.minecraft.world.entity.ai.goal.FollowMobGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LandOnOwnersShoulderGoal;
import net.minecraft.world.entity.ai.goal.LlamaFollowCaravanGoal;
import net.minecraft.world.entity.ai.goal.LookAtTradingPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.ai.goal.RunAroundLikeCrazyGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.TradeWithPlayerGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;

/**
 * Host behaviour. A host {@link Mob} whose behaviour is anything but {@code none} loses its passive goals and its whole
 * target selector. A goal-based mob then gains Remission's target goals (never other hosts) and an attack goal for its
 * behaviour; a brain-based mob gains {@link BrainHostTargetGoal}, which feeds its brain's fight activity. Cleansing puts
 * the original goals back.
 *
 * <p>Applied only when host state can change: on {@code EntityJoinLevelEvent} and from {@link Corruption#set}; never
 * per tick. Goals are not saved, so a reloaded mob rebuilds its own goals and is re-applied on join if still a host.
 */
public final class HostBehaviours {
    private static final int ATTACK_PRIORITY = 1;
    private static final int HURT_BY_PRIORITY = 1;
    private static final int PLAYER_TARGET_PRIORITY = 2;
    private static final int PREY_TARGET_PRIORITY = 3;
    private static final int BRAIN_TARGET_PRIORITY = 2;
    private static final double MELEE_SPEED = 1.2;
    private static final double RANGED_SPEED = 1.0;
    private static final int RANGED_INTERVAL = 40;
    private static final float RANGED_RADIUS = 12.0F;

    private HostBehaviours() {
    }

    /**
     * How {@code mob} fights as a host: the {@code behaviour} of its {@code remission:mob_profile}
     * ({@link MobProfiles#find}) when the profile sets one, else the category default. The single seam for host behaviour.
     */
    public static MobBehaviour behaviourFor(Mob mob) {
        return behaviourFor(mob, MobProfiles.find(mob));
    }

    /** {@link #behaviourFor(Mob)} with an explicit profile. */
    public static MobBehaviour behaviourFor(Mob mob, Optional<MobProfile> profile) {
        return profile.flatMap(MobProfile::behaviour).orElseGet(() -> categoryDefault(mob));
    }

    /**
     * The behaviour of a mob with no profile behaviour, checked in this order:
     * <ol>
     * <li>Brain-based mobs (any memory in their {@code Brain}): {@code melee} when the brain has an
     * {@code attack_target} memory, which its fight activity acts on (piglins, hoglins, axolotls, zoglins, breezes,
     * wardens; frogs too, but their default profile marks them {@code none}), else {@code none} (villagers, allays,
     * goats...).</li>
     * <li>{@code flying}: {@link FlyingMob}, {@link FlyingAnimal}, {@link AmbientCreature}, category {@code ambient}
     * or flying navigation.</li>
     * <li>{@code swimming}: {@link WaterAnimal}, the water categories or water-bound navigation.</li>
     * <li>{@code ranged}: {@link RangedAttackMob}.</li>
     * <li>{@code melee}: everything else.</li>
     * </ol>
     */
    public static MobBehaviour categoryDefault(Mob mob) {
        if (isBrainBased(mob)) {
            return BrainHostTargetGoal.canFight(mob) ? MobBehaviour.MELEE : MobBehaviour.NONE;
        }
        MobCategory category = mob.getType().getCategory();
        if (mob instanceof FlyingMob || mob instanceof FlyingAnimal || mob instanceof AmbientCreature
                || category == MobCategory.AMBIENT || mob.getNavigation() instanceof FlyingPathNavigation) {
            return MobBehaviour.FLYING;
        }
        if (mob instanceof WaterAnimal || category == MobCategory.WATER_CREATURE
                || category == MobCategory.UNDERGROUND_WATER_CREATURE || category == MobCategory.WATER_AMBIENT
                || mob.getNavigation() instanceof WaterBoundPathNavigation) {
            return MobBehaviour.SWIMMING;
        }
        if (mob instanceof RangedAttackMob) {
            return MobBehaviour.RANGED;
        }
        return MobBehaviour.MELEE;
    }

    /**
     * Brings {@code mob}'s goals in line with its host state: applies host goals to a host that has none, restores the
     * original goals of a cleansed mob. Idempotent and cheap when nothing changes. Server only.
     */
    public static void update(Mob mob) {
        if (mob.level().isClientSide()) {
            return;
        }
        update(mob, MobProfiles.find(mob));
    }

    /** {@link #update(Mob)} with an explicit profile. Server only. */
    public static void update(Mob mob, Optional<MobProfile> profile) {
        if (mob.level().isClientSide()) {
            return;
        }
        boolean applied = hasHostGoals(mob);
        boolean host = Corruption.isHost(mob);
        if (host && !applied) {
            apply(mob, behaviourFor(mob, profile));
        } else if (!host && applied) {
            restore(mob);
        }
    }

    /** Whether {@code mob} currently has host goals applied. */
    public static boolean hasHostGoals(Mob mob) {
        return mob.hasData(RemissionAttachments.HOST_GOALS);
    }

    /** Whether {@code mob} runs its AI through a {@code Brain} (any registered memory) rather than goals alone. */
    @SuppressWarnings("deprecation") // Brain#getMemories is marked debug-only; it is the only way to see registered memories
    public static boolean isBrainBased(Mob mob) {
        return !mob.getBrain().getMemories().isEmpty();
    }

    /** Whether a host may pick {@code target}: anything attackable that is not itself a host. */
    public static boolean isPrey(LivingEntity target) {
        return target.attackable() && !Corruption.isHost(target);
    }

    private static void apply(Mob mob, MobBehaviour behaviour) {
        boolean brain = isBrainBased(mob);
        if (behaviour == MobBehaviour.NONE || (brain && behaviour == MobBehaviour.PASSIVE_HOSTILE)) {
            // none keeps the mob's own AI; a brain mob already retaliates through its brain. Host-on-host targeting is
            // still blocked for every host by HostBehaviourEvents.
            return;
        }
        List<Slot> strippedGoals = strip(mob.goalSelector, HostBehaviours::isPassive);
        List<Slot> strippedTargets = strip(mob.targetSelector, goal -> true);
        List<Slot> addedGoals = new ArrayList<>();
        List<Slot> addedTargets = new ArrayList<>();

        if (brain) {
            // Every attacking behaviour means the same to a brain mob: its brain moves and attacks, Remission picks prey.
            addedTargets.add(new Slot(BRAIN_TARGET_PRIORITY, new BrainHostTargetGoal(mob)));
        } else {
            if (mob instanceof PathfinderMob pathfinder) {
                addedTargets.add(new Slot(HURT_BY_PRIORITY, new HurtByTargetGoal(pathfinder)));
            }
            if (behaviour != MobBehaviour.PASSIVE_HOSTILE) {
                addedTargets.add(new Slot(PLAYER_TARGET_PRIORITY,
                        new NearestAttackableTargetGoal<>(mob, Player.class, true, HostBehaviours::isPrey)));
                addedTargets.add(new Slot(PREY_TARGET_PRIORITY,
                        new NearestAttackableTargetGoal<>(mob, LivingEntity.class, true, HostBehaviours::isPrey)));
            }
            Goal attack = attackGoal(mob, behaviour);
            if (attack != null) {
                addedGoals.add(new Slot(ATTACK_PRIORITY, attack));
            }
        }

        addedGoals.forEach(slot -> mob.goalSelector.addGoal(slot.priority(), slot.goal()));
        addedTargets.forEach(slot -> mob.targetSelector.addGoal(slot.priority(), slot.goal()));
        mob.setData(RemissionAttachments.HOST_GOALS,
                new AppliedHostGoals(strippedGoals, strippedTargets, addedGoals, addedTargets));
    }

    private static void restore(Mob mob) {
        AppliedHostGoals applied = mob.removeData(RemissionAttachments.HOST_GOALS);
        applied.addedGoals().forEach(slot -> mob.goalSelector.removeGoal(slot.goal()));
        applied.addedTargets().forEach(slot -> mob.targetSelector.removeGoal(slot.goal()));
        applied.strippedGoals().forEach(slot -> mob.goalSelector.addGoal(slot.priority(), slot.goal()));
        applied.strippedTargets().forEach(slot -> mob.targetSelector.addGoal(slot.priority(), slot.goal()));
        mob.setTarget(null);
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
    }

    /**
     * The attack goal a goal-based host gains, or null. Flying and swimming hosts get {@link HostPursuitGoal}, except
     * monsters ({@link Enemy}: ghasts, phantoms, guardians), which fight their target with their own goals. Otherwise a
     * mob that has no attack goal gets a ranged one if it is a ranged mob and its behaviour is ranged, else melee if it
     * is a pathfinder.
     */
    private static Goal attackGoal(Mob mob, MobBehaviour behaviour) {
        if (behaviour == MobBehaviour.FLYING || behaviour == MobBehaviour.SWIMMING) {
            return mob instanceof Enemy ? null : new HostPursuitGoal(mob, behaviour == MobBehaviour.SWIMMING);
        }
        if (hasAttackGoal(mob)) {
            return null;
        }
        if (behaviour == MobBehaviour.RANGED && mob instanceof RangedAttackMob ranged) {
            return new RangedAttackGoal(ranged, RANGED_SPEED, RANGED_INTERVAL, RANGED_RADIUS);
        }
        if (mob instanceof PathfinderMob pathfinder) {
            return new MeleeAttackGoal(pathfinder, MELEE_SPEED, true);
        }
        // Mobs that are not pathfinders (slimes, for example) attack their target through their own goals.
        return null;
    }

    private static boolean hasAttackGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (goal instanceof MeleeAttackGoal || goal instanceof RangedAttackGoal
                    || goal instanceof RangedBowAttackGoal<?> || goal instanceof RangedCrossbowAttackGoal<?>) {
                return true;
            }
        }
        return false;
    }

    /** Goals a host drops: breeding, temptation, fleeing and panic, following parents, owners and caravans, trading. */
    static boolean isPassive(Goal goal) {
        return goal instanceof BreedGoal || goal instanceof TemptGoal || goal instanceof AvoidEntityGoal<?>
                || goal instanceof PanicGoal || goal instanceof FollowParentGoal || goal instanceof FollowOwnerGoal
                || goal instanceof SitWhenOrderedToGoal || goal instanceof LandOnOwnersShoulderGoal
                || goal instanceof BegGoal || goal instanceof RunAroundLikeCrazyGoal || goal instanceof FollowBoatGoal
                || goal instanceof FollowMobGoal || goal instanceof LlamaFollowCaravanGoal
                || goal instanceof TradeWithPlayerGoal || goal instanceof LookAtTradingPlayerGoal;
    }

    private static List<Slot> strip(GoalSelector selector, Predicate<Goal> filter) {
        List<Slot> stripped = new ArrayList<>();
        for (WrappedGoal wrapped : selector.getAvailableGoals()) {
            if (filter.test(wrapped.getGoal())) {
                stripped.add(new Slot(wrapped.getPriority(), wrapped.getGoal()));
            }
        }
        stripped.forEach(slot -> selector.removeGoal(slot.goal()));
        return stripped;
    }
}
