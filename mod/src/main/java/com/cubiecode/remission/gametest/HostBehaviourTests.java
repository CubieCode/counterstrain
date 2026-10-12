package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.HostBehaviours;
import com.cubiecode.remission.behaviour.MobBehaviour;
import com.cubiecode.remission.corruption.Corruption;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Host goals (P3.1): hosts attack players and uninfected mobs, never other hosts, and cleansing restores their goals.
 * Fights run in {@code remission:host_arena} (5x3x5 interior, glass walls and roof, so hosts cannot see neighbouring
 * tests) and in their own batch, so no other test's mobs are nearby. The arena floor is at relative y 1 (GameTest
 * positions are relative to the structure block, one below the template), so mobs spawn at y 2.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HostBehaviourTests {
    private static final String ARENA = "host_arena";
    private static final String BATCH = "host_behaviour";
    private static final int HOST_LEVEL = 50;

    private HostBehaviourTests() {
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostCowAttacksUninfectedMob(GameTestHelper helper) {
        Mob cow = helper.spawn(EntityType.COW, 1, 2, 1);
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5);
        Corruption.set(cow, HOST_LEVEL);
        helper.succeedWhen(() -> helper.assertTrue(pig.getLastHurtByMob() == cow, "host cow has not hurt the pig: "
                + describe(helper, cow)));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostZombieAttacksUninfectedAnimal(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, 1, 2, 1);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5);
        Corruption.set(zombie, HOST_LEVEL);
        helper.succeedWhen(() -> helper.assertTrue(pig.getLastHurtByMob() == zombie, "host zombie has not hurt the pig: "
                + describe(helper, zombie)));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostSheepAttacksPlayer(GameTestHelper helper) {
        Mob sheep = helper.spawn(EntityType.SHEEP, 1, 2, 1);
        ServerPlayer player = survivalPlayer(helper, helper.absoluteVec(new Vec3(5.5, 2, 5.5)));
        Corruption.set(sheep, HOST_LEVEL);
        succeedOnceThenCleanUp(helper, () -> player.getLastHurtByMob() == sheep,
                () -> helper.getLevel().players().remove(player));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostModdedMobAttacks(GameTestHelper helper) {
        // Mekanism's robit is a goal-based PathfinderMob in the dev runtime (smoke/mob_lineup); CI runs without it. A robit
        // discards itself unless its home is a chargepad, so the test places one and sets it through the robit's NBT.
        if (!ModList.get().isLoaded("mekanism")) {
            helper.succeed();
            return;
        }
        BlockPos chargepad = new BlockPos(1, 2, 5);
        helper.setBlock(chargepad, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mekanism", "chargepad")));
        EntityType<?> robitType = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("mekanism", "robit"));
        Entity created = robitType.create(helper.getLevel());
        helper.assertTrue(created instanceof Mob, "robit is not a mob");
        Mob robit = (Mob) created;
        CompoundTag save = new CompoundTag();
        save.put("home_location", GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE,
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(chargepad))).getOrThrow());
        robit.load(save);
        Vec3 spawn = helper.absoluteVec(new Vec3(1.5, 2, 1.5));
        robit.moveTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(robit);
        // An uncharged robit teleports to its chargepad and cannot walk, so the pig stands next to the pad.
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 2, 5);
        Corruption.set(robit, HOST_LEVEL);
        helper.assertValueEqual(HostBehaviours.behaviourFor(robit), MobBehaviour.MELEE, "robit behaviour");
        helper.assertTrue(HostBehaviours.hasHostGoals(robit), "host robit has no host goals");
        helper.succeedWhen(() -> helper.assertTrue(pig.getLastHurtByMob() == robit, "host robit has not hurt the pig: "
                + describe(helper, robit)));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void hostsDoNotTargetHosts(GameTestHelper helper) {
        Mob cow = helper.spawn(EntityType.COW, 1, 2, 1);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, 5, 2, 5);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        LivingEntity villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 3);
        Corruption.set(cow, HOST_LEVEL);
        Corruption.set(zombie, HOST_LEVEL);
        Corruption.set(villager, HOST_LEVEL);
        // A host hurt by a host does not retaliate.
        cow.hurt(cow.damageSources().mobAttack(zombie), 1.0F);
        // Setting a host target directly is refused too.
        zombie.setTarget(villager);
        helper.assertTrue(zombie.getTarget() == null, "host zombie was allowed to target a host villager");

        helper.runAtTickTime(150, () -> {
            helper.assertTrue(cow.getTarget() == null, "host cow targets " + cow.getTarget());
            helper.assertTrue(zombie.getTarget() == null, "host zombie targets " + zombie.getTarget());
            helper.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "host villager health");
            helper.assertValueEqual(zombie.getHealth(), zombie.getMaxHealth(), "host zombie health");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 200)
    public static void targetThatBecomesHostIsDropped(GameTestHelper helper) {
        Mob cow = helper.spawn(EntityType.COW, 1, 2, 1);
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 5, 2, 5);
        Corruption.set(cow, HOST_LEVEL);
        boolean[] infected = {false};
        helper.onEachTick(() -> {
            if (!infected[0] && cow.getTarget() == pig) {
                Corruption.set(pig, HOST_LEVEL);
                infected[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(infected[0], "host cow never targeted the pig");
            helper.assertTrue(cow.getTarget() == null, "host cow still targets the pig after it became a host");
        });
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void cleanseRestoresGoalsInSession(GameTestHelper helper) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, 1, 1, 1);
        Set<WrappedSlot> goals = slots(cow.goalSelector);
        Set<WrappedSlot> targets = slots(cow.targetSelector);

        Corruption.set(cow, HOST_LEVEL);
        helper.assertTrue(HostBehaviours.hasHostGoals(cow), "host cow has no host goals");
        assertNoPassiveGoals(helper, cow);
        helper.assertTrue(has(cow.goalSelector, MeleeAttackGoal.class), "host cow has no melee goal");
        helper.assertTrue(has(cow.targetSelector, NearestAttackableTargetGoal.class), "host cow has no target goal");

        Corruption.set(cow, HOST_LEVEL + 10);
        helper.assertValueEqual(count(cow.goalSelector, MeleeAttackGoal.class), 1L, "melee goals after a level change");

        Corruption.set(cow, 0);
        helper.assertFalse(HostBehaviours.hasHostGoals(cow), "cleansed cow kept host goals");
        helper.assertValueEqual(slots(cow.goalSelector), goals, "goals after cleanse");
        helper.assertValueEqual(slots(cow.targetSelector), targets, "target goals after cleanse");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void reloadReappliesAndCleansedReloadIsNormal(GameTestHelper helper) {
        Mob cow = helper.spawnWithNoFreeWill(EntityType.COW, 1, 1, 1);
        Corruption.set(cow, HOST_LEVEL);
        CompoundTag hostSave = cow.saveWithoutId(new CompoundTag());
        Corruption.set(cow, 0);
        CompoundTag cleanSave = cow.saveWithoutId(new CompoundTag());
        cow.discard();

        // A host loaded from disk comes back with vanilla goals, then gets host goals when it joins the level.
        Mob host = reload(helper, hostSave);
        helper.assertTrue(Corruption.isHost(host), "reloaded host is not a host");
        helper.assertFalse(HostBehaviours.hasHostGoals(host), "host goals were saved");
        helper.assertTrue(has(host.goalSelector, TemptGoal.class), "reloaded host has no vanilla goals before join");
        helper.getLevel().addFreshEntity(host);
        helper.assertTrue(HostBehaviours.hasHostGoals(host), "reloaded host did not get host goals on join");
        assertNoPassiveGoals(helper, host);

        // A cow cleansed before it was saved reloads as a normal cow.
        Mob clean = reload(helper, cleanSave);
        helper.getLevel().addFreshEntity(clean);
        helper.assertFalse(Corruption.isHost(clean), "cleansed cow reloaded as a host");
        helper.assertFalse(HostBehaviours.hasHostGoals(clean), "cleansed cow reloaded with host goals");
        helper.assertTrue(has(clean.goalSelector, TemptGoal.class) && has(clean.goalSelector, BreedGoal.class),
                "cleansed cow reloaded without its vanilla goals");
        helper.assertFalse(has(clean.goalSelector, MeleeAttackGoal.class), "cleansed cow reloaded with a melee goal");
        host.discard();
        clean.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void categoryDefaults(GameTestHelper helper) {
        Map<EntityType<? extends Mob>, MobBehaviour> expected = Map.ofEntries(
                Map.entry(EntityType.COW, MobBehaviour.MELEE),
                Map.entry(EntityType.SHEEP, MobBehaviour.MELEE),
                Map.entry(EntityType.ZOMBIE, MobBehaviour.MELEE),
                Map.entry(EntityType.SLIME, MobBehaviour.MELEE),
                Map.entry(EntityType.SKELETON, MobBehaviour.RANGED),
                Map.entry(EntityType.SNOW_GOLEM, MobBehaviour.RANGED),
                Map.entry(EntityType.VILLAGER, MobBehaviour.NONE),
                Map.entry(EntityType.ALLAY, MobBehaviour.NONE),
                Map.entry(EntityType.GOAT, MobBehaviour.NONE),
                Map.entry(EntityType.PIGLIN, MobBehaviour.MELEE),
                Map.entry(EntityType.HOGLIN, MobBehaviour.MELEE),
                Map.entry(EntityType.AXOLOTL, MobBehaviour.MELEE),
                Map.entry(EntityType.FROG, MobBehaviour.MELEE),
                Map.entry(EntityType.PIGLIN_BRUTE, MobBehaviour.MELEE),
                Map.entry(EntityType.ZOGLIN, MobBehaviour.MELEE),
                Map.entry(EntityType.BREEZE, MobBehaviour.MELEE),
                Map.entry(EntityType.WARDEN, MobBehaviour.MELEE),
                Map.entry(EntityType.CAMEL, MobBehaviour.NONE),
                Map.entry(EntityType.SNIFFER, MobBehaviour.NONE),
                Map.entry(EntityType.BEE, MobBehaviour.FLYING),
                Map.entry(EntityType.PHANTOM, MobBehaviour.FLYING),
                Map.entry(EntityType.DOLPHIN, MobBehaviour.SWIMMING),
                Map.entry(EntityType.TROPICAL_FISH, MobBehaviour.SWIMMING),
                Map.entry(EntityType.BAT, MobBehaviour.FLYING),
                Map.entry(EntityType.PARROT, MobBehaviour.FLYING),
                Map.entry(EntityType.GHAST, MobBehaviour.FLYING),
                Map.entry(EntityType.SQUID, MobBehaviour.SWIMMING),
                Map.entry(EntityType.COD, MobBehaviour.SWIMMING),
                Map.entry(EntityType.GUARDIAN, MobBehaviour.SWIMMING));
        expected.forEach((type, behaviour) -> {
            Mob mob = type.create(helper.getLevel());
            helper.assertTrue(mob != null, "could not create " + type);
            // Category defaults alone, without Remission's default profiles (which mark frogs unsupported).
            helper.assertValueEqual(HostBehaviours.categoryDefault(mob), behaviour, "category default of " + type);
        });
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void everyLivingTypeHasAttackAttributes(GameTestHelper helper) {
        int checked = 0;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (!DefaultAttributes.hasSupplier(type)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            AttributeSupplier supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
            helper.assertTrue(supplier.hasAttribute(Attributes.ATTACK_DAMAGE), type + " has no attack damage");
            helper.assertTrue(supplier.hasAttribute(Attributes.FOLLOW_RANGE), type + " has no follow range");
            checked++;
        }
        helper.assertTrue(checked > 50, "only " + checked + " entity types have attributes");
        helper.succeed();
    }

    private static String describe(GameTestHelper helper, Mob mob) {
        return "target=" + mob.getTarget() + " pos=" + helper.relativeVec(mob.position()) + " goals="
                + mob.goalSelector.getAvailableGoals().stream()
                        .map(wrapped -> wrapped.getGoal().getClass().getSimpleName() + (wrapped.isRunning() ? "*" : ""))
                        .toList();
    }

    private static Mob reload(GameTestHelper helper, CompoundTag save) {
        Mob mob = EntityType.COW.create(helper.getLevel());
        helper.assertTrue(mob != null, "cow not created");
        mob.load(save);
        mob.setUUID(UUID.randomUUID());
        return mob;
    }

    private static void assertNoPassiveGoals(GameTestHelper helper, Mob mob) {
        helper.assertFalse(has(mob.goalSelector, TemptGoal.class), "host kept its tempt goal");
        helper.assertFalse(has(mob.goalSelector, BreedGoal.class), "host kept its breed goal");
        helper.assertFalse(has(mob.goalSelector, PanicGoal.class), "host kept its panic goal");
    }

    private static boolean has(GoalSelector selector, Class<? extends Goal> type) {
        return count(selector, type) > 0;
    }

    private static long count(GoalSelector selector, Class<? extends Goal> type) {
        return selector.getAvailableGoals().stream().filter(wrapped -> type.isInstance(wrapped.getGoal())).count();
    }

    private static Set<WrappedSlot> slots(GoalSelector selector) {
        Set<WrappedSlot> slots = new HashSet<>();
        for (WrappedGoal wrapped : selector.getAvailableGoals()) {
            slots.add(new WrappedSlot(wrapped.getPriority(), wrapped.getGoal()));
        }
        return slots;
    }

    /** Polls {@code done} each tick; when it holds, runs {@code cleanUp} once and succeeds. */
    private static void succeedOnceThenCleanUp(GameTestHelper helper, BooleanSupplier done, Runnable cleanUp) {
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (!finished[0] && done.getAsBoolean()) {
                finished[0] = true;
                cleanUp.run();
                helper.succeed();
            }
        });
    }

    /**
     * A survival server player that mobs can find (the vanilla mock player is creative, so mobs ignore it). It is put in
     * the level's player list without joining the level, because login and join listeners in the dev runtime (KubeJS,
     * JourneyMap) fail on a fake connection; the test ticks it itself so its spawn invulnerability runs out.
     */
    private static ServerPlayer survivalPlayer(GameTestHelper helper, Vec3 pos) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "host-test-player"), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        helper.getLevel().players().add(player);
        helper.onEachTick(player::tick);
        return player;
    }

    private record WrappedSlot(int priority, Goal goal) {
    }
}
