package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.BrainHostTargetGoal;
import com.cubiecode.remission.behaviour.HostBehaviours;
import com.cubiecode.remission.behaviour.HostPursuitGoal;
import com.cubiecode.remission.behaviour.MobBehaviour;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.behaviour.MobProfiles;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.data.RemissionMobProfileProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Brain, flying and swimming fallbacks (P3.2): each category's hosts attack an uninfected mob, or are marked unsupported
 * by Remission's default profile. Fights run in {@code remission:host_arena} in their own batch, as in
 * {@link HostBehaviourTests}; swimmers get the arena flooded (interior x and z 1..5, y 2..4).
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostFallbackTests {
    private static final String ARENA = "host_arena";
    private static final String BATCH = "host_fallbacks";
    private static final int HOST_LEVEL = 50;

    private HostFallbackTests() {
    }

    // Flying

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostBatAttacksUninfectedMob(GameTestHelper helper) {
        // A bat steers itself (no navigation), so the pursuit goal steers its velocity.
        assertAttacks(helper, helper.spawn(EntityType.BAT, 1, 3, 1), helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostParrotAttacksUninfectedMob(GameTestHelper helper) {
        // A parrot has flying navigation, so the pursuit goal paths through the air.
        assertAttacks(helper, helper.spawn(EntityType.PARROT, 1, 2, 1), helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5));
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void flyingMonstersKeepTheirOwnAttack(GameTestHelper helper) {
        // Ghasts and phantoms attack their target with their own goals; they only get Remission's target goals.
        for (EntityType<? extends Mob> type : List.of(EntityType.PHANTOM, EntityType.GHAST)) {
            Mob mob = helper.spawnWithNoFreeWill(type, 1, 3, 1);
            Corruption.set(mob, HOST_LEVEL);
            helper.assertValueEqual(HostBehaviours.behaviourFor(mob), MobBehaviour.FLYING, "behaviour of " + type);
            helper.assertTrue(HostBehaviours.hasHostGoals(mob), type + " host has no host goals");
            helper.assertTrue(has(mob.targetSelector, NearestAttackableTargetGoal.class), type + " host has no target goal");
            helper.assertFalse(has(mob.goalSelector, HostPursuitGoal.class), type + " host got the pursuit goal");
            mob.discard();
        }
        helper.succeed();
    }

    // Swimming

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostCodAttacksUninfectedFish(GameTestHelper helper) {
        flood(helper);
        assertAttacks(helper, helper.spawn(EntityType.COD, 1, 3, 1), helper.spawnWithNoFreeWill(EntityType.SALMON, 5, 3, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostSquidAttacksUninfectedFish(GameTestHelper helper) {
        // A squid moves only along its movement vector, which the pursuit goal sets.
        flood(helper);
        assertAttacks(helper, helper.spawn(EntityType.SQUID, 1, 3, 1), helper.spawnWithNoFreeWill(EntityType.SALMON, 5, 3, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostDolphinAttacksUninfectedFish(GameTestHelper helper) {
        flood(helper);
        assertAttacks(helper, helper.spawn(EntityType.DOLPHIN, 1, 3, 1), helper.spawnWithNoFreeWill(EntityType.SALMON, 5, 3, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void hostSwimmerIgnoresPreyOutOfWater(GameTestHelper helper) {
        // The arena is dry: the cod flops and the pig is out of reach, so the pursuit goal never runs.
        Mob cod = helper.spawn(EntityType.COD, 1, 2, 1);
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 2, 1);
        Corruption.set(cod, HOST_LEVEL);
        helper.assertTrue(has(cod.goalSelector, HostPursuitGoal.class), "host cod has no pursuit goal");
        helper.onEachTick(() -> helper.assertFalse(running(cod.goalSelector, HostPursuitGoal.class),
                "host cod pursued prey out of water"));
        helper.runAtTickTime(80, () -> {
            helper.assertTrue(pig.getLastHurtByMob() == null, "host cod hurt a pig out of water");
            helper.succeed();
        });
    }

    // Brain-based

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostPiglinAttacksUninfectedMob(GameTestHelper helper) {
        Piglin piglin = helper.spawn(EntityType.PIGLIN, 1, 2, 1);
        piglin.setImmuneToZombification(true);
        assertAttacks(helper, piglin, helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostHoglinAttacksUninfectedMob(GameTestHelper helper) {
        Hoglin hoglin = helper.spawn(EntityType.HOGLIN, 1, 2, 1);
        hoglin.setImmuneToZombification(true);
        assertAttacks(helper, hoglin, helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostAxolotlAttacksUninfectedMob(GameTestHelper helper) {
        // Axolotls hunt fish and squid on their own, so the prey is a dolphin, which they leave alone.
        flood(helper);
        assertAttacks(helper, helper.spawn(EntityType.AXOLOTL, 1, 2, 1), helper.spawnWithNoFreeWill(EntityType.DOLPHIN, 5, 3, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 300)
    public static void hostWardenAttacksUninfectedMob(GameTestHelper helper) {
        // A warden fights only while angry at its target, so the brain goal angers it; it roars before it attacks.
        assertAttacks(helper, helper.spawn(EntityType.WARDEN, 1, 2, 1), helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostBreezeAttacksIronGolem(GameTestHelper helper) {
        // A breeze only ever attacks players and iron golems (Breeze#canAttackType), so the prey is a golem; its wind
        // charges are owned by the breeze, so the golem is hurt by it. The golem is 1.4 wide, so at the usual prey corner
        // (5, 5) its box already overlaps the glass; a sliding breeze can shove it further in until glass blocks the
        // breeze's line of sight, and the breeze drops the target and never sees it again. At (4, 4) it clears the walls.
        assertAttacks(helper, helper.spawn(EntityType.BREEZE, 1, 2, 1),
                helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, 4, 2, 4));
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void unsupportedBrainMobsAreMarkedInDefaultProfile(GameTestHelper helper) {
        for (EntityType<? extends Mob> type : List.of(EntityType.VILLAGER, EntityType.ALLAY, EntityType.FROG)) {
            Mob mob = helper.spawnWithNoFreeWill(type, 1, 2, 1);
            Optional<MobProfile> profile = MobProfiles.find(mob);
            helper.assertTrue(profile.isPresent(), type + " has no default profile");
            helper.assertValueEqual(profile.get().priority(), RemissionMobProfileProvider.DEFAULT_PRIORITY,
                    "default profile priority of " + type);
            helper.assertValueEqual(HostBehaviours.behaviourFor(mob), MobBehaviour.NONE, "behaviour of " + type);
            Corruption.set(mob, HOST_LEVEL);
            helper.assertTrue(Corruption.isHost(mob), type + " did not become a host");
            helper.assertFalse(HostBehaviours.hasHostGoals(mob), type + " host got host goals");
            mob.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void packProfileReplacesDefaultProfile(GameTestHelper helper) {
        // Profiles never merge: a pack's priority 0 frog profile without a behaviour wins over remission:unsupported,
        // and the frog falls back to its category default (melee), as documented in remission-data-formats.
        MobProfile unsupported = helper.getLevel().registryAccess().registryOrThrow(MobProfiles.KEY)
                .get(Remission.id("unsupported"));
        helper.assertTrue(unsupported != null, "no remission:unsupported profile");
        MobProfile pack = new MobProfile(HolderSet.direct(EntityType.FROG.builtInRegistryHolder()), 0, Optional.empty(),
                Map.of(), Optional.empty(), MobProfile.Conversion.DEFAULT);
        Optional<MobProfile> chosen = MobProfiles.select(List.of(pack, unsupported), EntityType.FROG);
        helper.assertTrue(chosen.isPresent() && chosen.get() == pack, "the pack profile did not replace the default");
        Mob frog = helper.spawnWithNoFreeWill(EntityType.FROG, 1, 2, 1);
        helper.assertValueEqual(HostBehaviours.behaviourFor(frog, chosen), MobBehaviour.MELEE, "frog behaviour");
        helper.assertValueEqual(HostBehaviours.behaviourFor(frog), MobBehaviour.NONE, "frog behaviour from the default");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void cleanseClearsBrainTarget(GameTestHelper helper) {
        Piglin piglin = helper.spawnWithNoFreeWill(EntityType.PIGLIN, 1, 2, 1);
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 3, 2, 1);
        Corruption.set(piglin, HOST_LEVEL);
        helper.assertTrue(has(piglin.targetSelector, BrainHostTargetGoal.class), "host piglin has no brain target goal");
        piglin.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, pig);
        Corruption.set(piglin, 0);
        helper.assertFalse(HostBehaviours.hasHostGoals(piglin), "cleansed piglin kept host goals");
        helper.assertFalse(has(piglin.targetSelector, BrainHostTargetGoal.class), "cleansed piglin kept its brain target goal");
        helper.assertFalse(piglin.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET),
                "cleansed piglin kept its attack target");
        helper.succeed();
    }

    private static void assertAttacks(GameTestHelper helper, Mob host, LivingEntity prey) {
        Corruption.set(host, HOST_LEVEL);
        helper.assertTrue(HostBehaviours.hasHostGoals(host), "host " + host.getType() + " has no host goals");
        helper.succeedWhen(() -> helper.assertTrue(prey.getLastHurtByMob() == host, "host " + host.getType()
                + " has not hurt the " + prey.getType() + ": target=" + host.getTarget() + " brainTarget="
                + (host.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                        ? host.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET) : "n/a")
                + " pos=" + helper.relativeVec(host.position()) + " activity=" + host.getBrain().getActiveNonCoreActivity()
                + (host instanceof Warden warden ? " anger=" + warden.getAngerLevel() : "")));
    }

    private static void flood(GameTestHelper helper) {
        for (BlockPos pos : BlockPos.betweenClosed(1, 2, 1, 5, 4, 5)) {
            helper.setBlock(pos, Blocks.WATER);
        }
    }

    private static boolean has(GoalSelector selector, Class<? extends Goal> type) {
        return selector.getAvailableGoals().stream().anyMatch(wrapped -> type.isInstance(wrapped.getGoal()));
    }

    private static boolean running(GoalSelector selector, Class<? extends Goal> type) {
        return selector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.isRunning() && type.isInstance(wrapped.getGoal()));
    }
}
