package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.api.RevenantPickupEvent;
import com.cubiecode.remission.api.RevenantRisenEvent;
import com.cubiecode.remission.api.RevenantSlainEvent;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.integration.curios.CuriosIntegration;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.revenant.Revenant;
import com.cubiecode.remission.revenant.RevenantDrop;
import com.cubiecode.remission.revenant.RevenantIndex;
import com.cubiecode.remission.revenant.Revenants;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadState;
import com.cubiecode.remission.world.ChunkCorruption;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Revenants (P5.3): Strain deaths (killed by a host or a revenant, viral load, festering land) raise a revenant holding
 * exactly the dropped items (Curios slots included when Curios is loaded) and experience; other deaths and
 * {@code keepInventory} drop as vanilla; damage only from players; void return; save, reload and despawn rules; the
 * leash; owner-only pickup, its widening by {@link RevenantPickupEvent} and its timeout; the reclaim trigger and stat;
 * and the index on rise, move and death. Each test runs alone in its own batch, because several change chunk levels,
 * game rules or difficulty, and players are never added to the level.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevenantTests {
    private static final int EXPERIENCE_LEVEL = 3;
    /** Vanilla's death drop for {@link #EXPERIENCE_LEVEL}: 7 per level. Splits into orbs of 17, 3 and 1, which never merge. */
    private static final int DROPPED_EXPERIENCE = EXPERIENCE_LEVEL * 7;

    private RevenantTests() {
    }

    // ---- which deaths ------------------------------------------------------------------------------------------------

    /** A host's kill raises a revenant with exactly the dropped items and experience; a revenant's kill raises another. */
    @GameTest(template = "empty", batch = "revenant_host_kill")
    public static void hostKillRaisesRevenant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Consumer<?>> listeners = new ArrayList<>();
        try {
            List<RevenantRisenEvent> risen = listen(RevenantRisenEvent.class, listeners);
            List<HostCorruptedEvent> corrupted = listen(HostCorruptedEvent.class, listeners);
            Zombie host = zombie(helper, true);
            ServerPlayer player = player(helper, new BlockPos(1, 1, 1));
            List<ItemStack> expected = loadout(player);
            BlockPos deathPos = player.blockPosition();
            player.die(level.damageSources().mobAttack(host));

            assertNoDrops(helper, deathPos);
            Revenant revenant = onlyRevenant(helper, deathPos);
            assertSameItems(helper, expected, revenant.contents(), "revenant contents");
            helper.assertValueEqual(revenant.experience(), DROPPED_EXPERIENCE, "revenant experience");
            helper.assertValueEqual(revenant.owner(), player.getUUID(), "owner");
            helper.assertValueEqual(revenant.ownerName(), player.getGameProfile().getName(), "owner name");
            helper.assertValueEqual(revenant.home(), deathPos, "home");
            helper.assertTrue(ItemStack.matches(revenant.getItemBySlot(EquipmentSlot.HEAD), new ItemStack(Items.IRON_HELMET)), "helmet worn");
            helper.assertTrue(ItemStack.matches(revenant.getItemBySlot(EquipmentSlot.CHEST), new ItemStack(Items.DIAMOND_CHESTPLATE)), "chestplate worn");
            helper.assertTrue(ItemStack.matches(revenant.getMainHandItem(), new ItemStack(Items.DIAMOND_SWORD)), "sword held");
            helper.assertTrue(revenant.getOffhandItem().isEmpty(), "offhand is held, not worn");
            helper.assertTrue(CorruptionQueries.isHost(revenant) && CorruptionQueries.isRevenant(revenant), "revenant is a host");
            helper.assertValueEqual(CorruptionQueries.hostStage(revenant), 1, "stage at viral load 0");
            helper.assertTrue(corrupted.stream().anyMatch(event -> event.getEntity() == revenant
                    && event.getCause() == CorruptionCause.REVENANT), "host corrupted with cause REVENANT");
            helper.assertValueEqual(risen.size(), 1, "risen events");
            helper.assertTrue(risen.getFirst().getEntity() == revenant && risen.getFirst().getPlayer() == player, "risen event payload");
            helper.assertValueEqual(risen.getFirst().getExperience(), DROPPED_EXPERIENCE, "risen event experience");
            assertSameItems(helper, expected, risen.getFirst().getItems(), "risen event items");
            helper.assertTrue(player.getInventory().isEmpty(), "player kept items");

            // A revenant's own kill is a Strain death too.
            ServerPlayer second = player(helper, new BlockPos(0, 1, 1));
            second.getInventory().add(new ItemStack(Items.APPLE, 5));
            second.die(level.damageSources().mobAttack(revenant));
            helper.assertValueEqual(revenants(helper, second.blockPosition()).stream()
                    .filter(r -> second.getUUID().equals(r.owner())).count(), 1L, "revenants of the revenant's victim");
            host.discard();
        } finally {
            unlisten(listeners);
            clear(helper);
        }
        helper.succeed();
    }

    /** Viral load damage raises a revenant at the viral load's Strain level; the death anywhere in festering land does too. */
    @GameTest(template = "empty", batch = "revenant_viral_land")
    public static void viralLoadAndFesteringLandDeaths(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = level.getChunkAt(helper.absolutePos(BlockPos.ZERO));
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        try {
            ServerPlayer poisoned = player(helper, new BlockPos(1, 1, 1));
            List<ItemStack> expected = loadout(poisoned);
            ViralLoad.set(poisoned, 100.0F, ViralLoadState.Trend.RISING, ViralLoadChangedEvent.Cause.OTHER);
            DamageSource viral = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(ViralLoad.DAMAGE_TYPE));
            poisoned.die(viral);
            Revenant revenant = onlyRevenant(helper, poisoned.blockPosition());
            assertSameItems(helper, expected, revenant.contents(), "viral load revenant contents");
            helper.assertValueEqual(CorruptionQueries.hostLevel(revenant), 100, "level from viral load 100");
            helper.assertValueEqual(CorruptionQueries.hostStage(revenant), 3, "stage from viral load 100");
            revenant.discard();

            // A fall in a clean chunk is not a Strain death; the same fall in a stage 1 chunk is, at any height.
            ServerPlayer clean = player(helper, new BlockPos(1, 1, 1));
            loadout(clean);
            clean.die(level.damageSources().fall());
            helper.assertTrue(revenants(helper, clean.blockPosition()).isEmpty(), "fall in clean land raised a revenant");
            clearDrops(helper);

            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(1, level.getGameTime()));
            helper.assertValueEqual(CorruptionQueries.stageAt(level, helper.absolutePos(BlockPos.ZERO)), 1, "chunk stage");
            ServerPlayer fallen = player(helper, new BlockPos(1, 1, 1));
            fallen.moveTo(fallen.getX(), level.getMinBuildHeight() + 2, fallen.getZ());
            List<ItemStack> fallenItems = loadout(fallen);
            fallen.die(level.damageSources().fall());
            Revenant underground = onlyRevenant(helper, fallen.blockPosition());
            assertSameItems(helper, fallenItems, underground.contents(), "festering land revenant contents");
            helper.assertValueEqual(underground.home(), fallen.blockPosition(), "home deep underground");
        } finally {
            if (before == null) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            } else {
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, before);
            }
            clear(helper);
        }
        helper.succeed();
    }

    /** Killed by a mob that is not a host in clean land: vanilla drops, no revenant. */
    @GameTest(template = "empty", batch = "revenant_vanilla")
    public static void otherDeathsDropAsVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            Zombie zombie = zombie(helper, false);
            ServerPlayer player = player(helper, new BlockPos(1, 1, 1));
            List<ItemStack> expected = loadout(player);
            player.die(level.damageSources().mobAttack(zombie));
            helper.assertTrue(revenants(helper, player.blockPosition()).isEmpty(), "non-Strain death raised a revenant");
            List<ItemEntity> drops = drops(helper);
            assertSameItems(helper, expected, drops.stream().map(ItemEntity::getItem).toList(), "vanilla drops");
            helper.assertTrue(drops.stream().allMatch(drop -> Revenants.restriction(drop) == null), "vanilla drops restricted");
            helper.assertValueEqual(experience(helper), DROPPED_EXPERIENCE, "vanilla experience");
            zombie.discard();
        } finally {
            clear(helper);
        }
        helper.succeed();
    }

    /** With keepInventory, a host's kill keeps the inventory and raises nothing. */
    @GameTest(template = "empty", batch = "revenant_keep_inventory")
    public static void keepInventoryTurnsRevenantsOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameRules.BooleanValue keep = level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean old = keep.get();
        try {
            keep.set(true, level.getServer());
            Zombie host = zombie(helper, true);
            ServerPlayer player = player(helper, new BlockPos(1, 1, 1));
            List<ItemStack> expected = loadout(player);
            player.die(level.damageSources().mobAttack(host));
            helper.assertTrue(revenants(helper, player.blockPosition()).isEmpty(), "keepInventory death raised a revenant");
            helper.assertTrue(drops(helper).isEmpty(), "keepInventory death dropped items");
            List<ItemStack> kept = new ArrayList<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                if (!player.getInventory().getItem(i).isEmpty()) {
                    kept.add(player.getInventory().getItem(i));
                }
            }
            assertSameItems(helper, expected, kept, "kept inventory");
            host.discard();
        } finally {
            keep.set(old, level.getServer());
            clear(helper);
        }
        helper.succeed();
    }

    /** With Curios loaded, a Curios slot item goes into the revenant like the rest; without it the test has nothing to do. */
    @GameTest(template = "empty", batch = "revenant_curios")
    public static void curiosItemsGoIntoRevenant(GameTestHelper helper) {
        if (!CuriosIntegration.isLoaded()) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        try (AutoCloseable ringSlot = CuriosIntegration.assignPlayerSlot("ring")) {
            Zombie host = zombie(helper, true);
            ServerPlayer player = player(helper, new BlockPos(1, 1, 1));
            List<ItemStack> expected = new ArrayList<>(loadout(player));
            ItemStack ring = new ItemStack(Items.GOLD_NUGGET);
            helper.assertTrue(CuriosIntegration.equip(player, "ring", ring), "ring slot not equipped");
            helper.assertValueEqual(CuriosIntegration.equipped(player).size(), 1, "Curios items before death");
            expected.add(ring);
            player.die(level.damageSources().mobAttack(host));
            assertNoDrops(helper, player.blockPosition());
            assertSameItems(helper, expected, onlyRevenant(helper, player.blockPosition()).contents(), "contents with Curios");
            helper.assertTrue(CuriosIntegration.equipped(player).isEmpty(), "Curios slot kept its item");
            host.discard();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("Curios slot: " + e, e);
        } finally {
            clear(helper);
        }
        helper.succeed();
    }

    /**
     * A revenant that cannot join the level (here a join listener refuses it) leaves the death as vanilla: the items drop,
     * the experience is awarded, nothing is indexed and no risen event is posted.
     */
    @GameTest(template = "empty", batch = "revenant_rise_fails")
    public static void failedRiseDropsAsVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Consumer<?>> listeners = new ArrayList<>();
        Consumer<EntityJoinLevelEvent> refuse = event -> {
            if (event.getEntity() instanceof Revenant) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(refuse);
        try {
            List<RevenantRisenEvent> risen = listen(RevenantRisenEvent.class, listeners);
            Zombie host = zombie(helper, true);
            ServerPlayer player = player(helper, new BlockPos(1, 1, 1));
            List<ItemStack> expected = loadout(player);
            player.die(level.damageSources().mobAttack(host));
            helper.assertTrue(revenants(helper, player.blockPosition()).isEmpty(), "a refused revenant is in the level");
            assertSameItems(helper, expected, drops(helper).stream().map(ItemEntity::getItem).toList(), "drops after a failed rise");
            helper.assertValueEqual(experience(helper), DROPPED_EXPERIENCE, "experience after a failed rise");
            helper.assertTrue(RevenantIndex.ofOwner(level.getServer(), player.getUUID()).isEmpty(), "a refused revenant was indexed");
            helper.assertTrue(risen.isEmpty(), "a refused revenant posted a risen event");
            host.discard();
        } finally {
            NeoForge.EVENT_BUS.unregister(refuse);
            unlisten(listeners);
            clear(helper);
        }
        helper.succeed();
    }

    /**
     * A Strain death that a later listener cancels (the player lives) leaves nothing for the next death: that one, not a
     * Strain death, drops as vanilla.
     */
    @GameTest(template = "empty", batch = "revenant_cancelled_death")
    public static void cancelledDeathIsForgotten(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, new BlockPos(1, 1, 1));
        Consumer<LivingDeathEvent> spare = event -> {
            if (event.getEntity() == player) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, spare);
        Zombie host = zombie(helper, true);
        loadout(player);
        player.die(level.damageSources().mobAttack(host));
        NeoForge.EVENT_BUS.unregister(spare);
        helper.assertTrue(revenants(helper, player.blockPosition()).isEmpty(), "a cancelled death raised a revenant");
        helper.assertTrue(drops(helper).isEmpty(), "a cancelled death dropped items");
        host.discard();
        helper.runAfterDelay(1, () -> {
            try {
                Zombie zombie = zombie(helper, false);
                List<ItemStack> expected = new ArrayList<>();
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (!player.getInventory().getItem(i).isEmpty()) {
                        expected.add(player.getInventory().getItem(i).copy());
                    }
                }
                player.die(level.damageSources().mobAttack(zombie));
                helper.assertTrue(revenants(helper, player.blockPosition()).isEmpty(), "the later death used the cancelled one's entry");
                assertSameItems(helper, expected, drops(helper).stream().map(ItemEntity::getItem).toList(), "later death's drops");
                helper.assertValueEqual(experience(helper), DROPPED_EXPERIENCE, "later death's experience");
                zombie.discard();
            } finally {
                clear(helper);
            }
            helper.succeed();
        });
    }

    /** Below the world a revenant rises on the column's surface; over an empty End column, on the obsidian platform. */
    @GameTest(template = "empty", batch = "revenant_rise_spot")
    public static void riseSpotStaysInTheLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inWorld = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.assertValueEqual(Revenants.riseSpot(level, inWorld), inWorld, "a death in the world");
        BlockPos below = new BlockPos(inWorld.getX(), level.getMinBuildHeight() - 20, inWorld.getZ());
        BlockPos surface = Revenants.riseSpot(level, below);
        helper.assertValueEqual(surface, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, below), "below the world");
        helper.assertTrue(surface.getY() > level.getMinBuildHeight(), "surface above the minimum height");
        ServerLevel end = level.getServer().getLevel(Level.END);
        if (end != null) {
            // Between the main island and the outer islands: an empty column.
            BlockPos voidColumn = new BlockPos(600, end.getMinBuildHeight() - 10, 600);
            helper.assertValueEqual(Revenants.riseSpot(end, voidColumn), ServerLevel.END_SPAWN_POINT, "an empty End column");
        }
        helper.succeed();
    }

    // ---- the revenant ------------------------------------------------------------------------------------------------

    /** Only players (melee, projectile) and /kill hurt it; fire, lava, falls, drowning, mobs and magic do not. */
    @GameTest(template = "empty", batch = "revenant_damage")
    public static void onlyPlayersHurtRevenants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
            Revenant revenant = rise(helper, owner, List.of(new ItemStack(Items.STONE)), 5);
            Zombie mob = zombie(helper, false);
            float health = revenant.getHealth();
            for (DamageSource source : List.of(level.damageSources().inFire(), level.damageSources().onFire(),
                    level.damageSources().lava(), level.damageSources().fall(), level.damageSources().drown(),
                    level.damageSources().mobAttack(mob), level.damageSources().magic(), level.damageSources().wither(),
                    level.damageSources().cactus(), level.damageSources().inWall(), level.damageSources().fellOutOfWorld(),
                    level.damageSources().explosion(null, null))) {
                revenant.invulnerableTime = 0;
                helper.assertFalse(revenant.hurt(source, 5.0F), "hurt by " + source.getMsgId());
            }
            helper.assertValueEqual(revenant.getHealth(), health, "health after non-player damage");
            helper.assertTrue(revenant.fireImmune(), "revenant burns");

            ServerPlayer attacker = player(helper, new BlockPos(0, 1, 0));
            revenant.invulnerableTime = 0;
            helper.assertTrue(revenant.hurt(level.damageSources().playerAttack(attacker), 2.0F), "player melee");
            Arrow arrow = new Arrow(level, attacker, new ItemStack(Items.ARROW), null);
            revenant.invulnerableTime = 0;
            helper.assertTrue(revenant.hurt(level.damageSources().arrow(arrow, attacker), 2.0F), "player arrow");
            helper.assertTrue(revenant.getHealth() < health, "health after player damage");
            revenant.kill();
            helper.assertTrue(revenant.isDeadOrDying(), "/kill");
            mob.discard();
        } finally {
            clear(helper);
        }
        helper.succeed();
    }

    /** Below the world it is put back where its owner fell. */
    @GameTest(template = "empty", batch = "revenant_void")
    public static void revenantReturnsFromTheVoid(GameTestHelper helper) {
        ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
        Revenant revenant = rise(helper, owner, List.of(new ItemStack(Items.STONE)), 0);
        BlockPos home = revenant.home();
        revenant.teleportTo(revenant.getX(), helper.getLevel().getMinBuildHeight() - 80, revenant.getZ());
        helper.succeedWhen(() -> {
            helper.assertFalse(revenant.isRemoved(), "revenant removed in the void");
            helper.assertValueEqual(revenant.blockPosition(), home, "position after the void");
            helper.assertValueEqual(revenant.getHealth(), revenant.getMaxHealth(), "health after the void");
            revenant.discard();
        });
    }

    /** Saved and loaded (an unload and reload) it keeps owner, home, contents, gear and host state; it never despawns. */
    @GameTest(template = "empty", batch = "revenant_persistence")
    public static void revenantPersistsAndNeverDespawns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Difficulty difficulty = server.getWorldData().getDifficulty();
        try {
            ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
            loadout(owner);
            List<ItemStack> items = List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_HELMET),
                    new ItemStack(Items.BREAD, 12));
            Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
            worn.put(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            worn.put(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            Revenant revenant = Revenants.rise(level, owner, level.damageSources().generic(), owner.blockPosition(), items,
                    DROPPED_EXPERIENCE, 40.0F, worn);
            helper.assertTrue(revenant != null, "no revenant");
            helper.assertFalse(revenant.removeWhenFarAway(10_000.0), "removed when far away");
            helper.assertTrue(revenant.requiresCustomPersistence() && revenant.isPersistenceRequired(), "not persistent");

            server.setDifficulty(Difficulty.PEACEFUL, true);
            revenant.checkDespawn();
            helper.assertFalse(revenant.isRemoved(), "despawned in peaceful");

            CompoundTag saved = new CompoundTag();
            helper.assertTrue(revenant.save(saved), "not saved");
            revenant.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.assertTrue(RevenantIndex.get(level).find(revenant.getUUID()) != null, "unload dropped the index entry");
            Entity loaded = EntityType.loadEntityRecursive(saved, level, entity -> entity);
            helper.assertTrue(loaded instanceof Revenant, "loaded entity is not a revenant");
            Revenant back = (Revenant) loaded;
            level.addFreshEntity(back);
            helper.assertValueEqual(back.getUUID(), revenant.getUUID(), "uuid");
            helper.assertValueEqual(back.owner(), owner.getUUID(), "owner after reload");
            helper.assertValueEqual(back.ownerName(), owner.getGameProfile().getName(), "owner name after reload");
            helper.assertValueEqual(back.home(), revenant.home(), "home after reload");
            helper.assertValueEqual(back.getRestrictCenter(), revenant.home(), "leash centre after reload");
            assertSameItems(helper, items, back.contents(), "contents after reload");
            helper.assertValueEqual(back.experience(), DROPPED_EXPERIENCE, "experience after reload");
            helper.assertTrue(ItemStack.matches(back.getMainHandItem(), new ItemStack(Items.DIAMOND_SWORD)), "sword after reload");
            helper.assertValueEqual(Corruption.get(back).level(), 40, "Strain level after reload");
            helper.assertTrue(back.isPersistenceRequired(), "persistence after reload");
            back.discard();
            helper.assertTrue(RevenantIndex.get(level).find(revenant.getUUID()) == null, "discarded revenant still indexed");
        } finally {
            server.setDifficulty(difficulty, true);
            clear(helper);
        }
        helper.succeed();
    }

    /**
     * It is leashed to its home: put back when far off, never chasing prey past the leash. The revenant is ticked by hand,
     * because the spots outside the leash lie in chunks next to the test that are loaded but not entity-ticking.
     */
    @GameTest(template = "empty", batch = "revenant_leash")
    public static void revenantStaysLeashed(GameTestHelper helper) {
        ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
        Revenant revenant = rise(helper, owner, List.of(new ItemStack(Items.STONE)), 0);
        BlockPos home = revenant.home();
        helper.assertValueEqual(revenant.getRestrictCenter(), home, "leash centre");
        helper.assertValueEqual((int) revenant.getRestrictRadius(), Revenant.LEASH_RADIUS, "leash radius");
        Zombie prey = zombie(helper, false);
        prey.teleportTo(home.getX() + Revenant.LEASH_RADIUS + 8.5, home.getY(), home.getZ() + 0.5);
        try {
            revenant.setTarget(prey);
            revenant.tick();
            helper.assertTrue(revenant.getTarget() == null, "revenant kept prey past the leash");

            revenant.teleportTo(home.getX() + Revenant.RETURN_RADIUS + 6.5, home.getY() + 2, home.getZ() + 0.5);
            revenant.tick();
            helper.assertValueEqual(revenant.blockPosition(), home, "position after straying past the return radius");

            // Within the return radius it is not moved at once; its restriction goal walks it back.
            revenant.teleportTo(home.getX() + Revenant.LEASH_RADIUS + 2.5, home.getY(), home.getZ() + 0.5);
            revenant.tick();
            helper.assertFalse(revenant.isWithinRestriction(), "outside the leash but within the return radius");
            helper.assertTrue(revenant.blockPosition().distManhattan(home) > Revenant.LEASH_RADIUS, "put back too early");
        } finally {
            revenant.discard();
            prey.discard();
        }
        helper.succeed();
    }

    // ---- pickup and reclaim ------------------------------------------------------------------------------------------

    /**
     * Slain, it drops its items (restricted to the owner, never to mobs, never despawning) and experience. A stranger
     * cannot pick them up, the owner can; a stranger's kill reclaims nothing, the owner's fires the trigger and stat.
     */
    @GameTest(template = "empty", batch = "revenant_pickup")
    public static void ownerOnlyPickupAndReclaim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Consumer<?>> listeners = new ArrayList<>();
        ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
        ServerPlayer stranger = player(helper, new BlockPos(0, 1, 1));
        AdvancementHolder ownGate = gate(helper, owner, "{\"trigger\":\"remission:revenant_reclaimed\",\"conditions\":{\"own\":true}}");
        AdvancementHolder strangerGate = gate(helper, stranger, "{\"trigger\":\"remission:revenant_reclaimed\"}");
        List<RevenantSlainEvent> slain = listen(RevenantSlainEvent.class, listeners);
        List<ItemStack> items = List.of(new ItemStack(Items.DIAMOND, 3), new ItemStack(Items.IRON_INGOT, 7));
        Revenant first = rise(helper, owner, items, DROPPED_EXPERIENCE);
        first.hurt(level.damageSources().playerAttack(stranger), 1000.0F);
        helper.assertTrue(first.isDeadOrDying(), "stranger did not kill the revenant");
        helper.assertValueEqual(slain.size(), 1, "slain events");
        helper.assertTrue(slain.getFirst().getKiller() == stranger && !slain.getFirst().isReclaimed(), "stranger's kill reclaimed");
        helper.assertFalse(done(stranger, strangerGate), "stranger's kill fired revenant_reclaimed");
        helper.assertValueEqual(stat(stranger), 0, "stranger's stat");
        List<ItemEntity> drops = drops(helper);
        assertSameItems(helper, items, drops.stream().map(ItemEntity::getItem).toList(), "revenant drops");
        helper.assertValueEqual(experience(helper), DROPPED_EXPERIENCE, "revenant experience drop");
        for (ItemEntity drop : drops) {
            RevenantDrop restriction = Revenants.restriction(drop);
            helper.assertTrue(restriction != null && restriction.owner().equals(owner.getUUID()), "drop not restricted to the owner");
            helper.assertValueEqual(restriction.freeAt() - restriction.droppedAt(),
                    (long) RemissionCommonConfig.REVENANT_PICKUP_TIMEOUT_TICKS.get(), "timeout");
            helper.assertTrue(drop.hasPickUpDelay(), "mobs could pick up a restricted drop");
        }
        // Before the pickup delay nobody takes it.
        drops.getFirst().playerTouch(owner);
        helper.assertFalse(drops.getFirst().isRemoved(), "owner took a drop before the pickup delay");
        helper.runAfterDelay(Revenants.PICKUP_DELAY_TICKS + 1, () -> {
            try {
                for (ItemEntity drop : drops) {
                    drop.playerTouch(stranger);
                    helper.assertFalse(drop.isRemoved(), "stranger took a restricted drop");
                }
                helper.assertTrue(stranger.getInventory().isEmpty(), "stranger's inventory");
                for (ItemEntity drop : drops) {
                    drop.playerTouch(owner);
                    helper.assertTrue(drop.isRemoved(), "owner could not take a drop");
                }
                helper.assertValueEqual(owner.getInventory().countItem(Items.DIAMOND), 3, "owner's diamonds");

                Revenant second = rise(helper, owner, List.of(new ItemStack(Items.APPLE)), 0);
                second.hurt(level.damageSources().playerAttack(owner), 1000.0F);
                helper.assertTrue(slain.getLast().getKiller() == owner && slain.getLast().isReclaimed(), "owner's kill not reclaimed");
                helper.assertTrue(done(owner, ownGate), "owner's kill did not fire revenant_reclaimed own=true");
                helper.assertValueEqual(stat(owner), 1, "owner's stat");
            } finally {
                unlisten(listeners);
                clear(helper);
            }
            helper.succeed();
        });
    }

    /** A {@link RevenantPickupEvent} listener lets a teammate collect and reclaim (own = false), as the pack's FTB Teams script does. */
    @GameTest(template = "empty", batch = "revenant_pickup_event")
    public static void pickupEventWidensPickup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
        ServerPlayer friend = player(helper, new BlockPos(0, 1, 1));
        AdvancementHolder notOwn = gate(helper, friend, "{\"trigger\":\"remission:revenant_reclaimed\",\"conditions\":{\"own\":false}}");
        AdvancementHolder own = gate(helper, friend, "{\"trigger\":\"remission:revenant_reclaimed\",\"conditions\":{\"own\":true}}");
        List<RevenantPickupEvent> asked = new ArrayList<>();
        Consumer<RevenantPickupEvent> team = event -> {
            asked.add(event);
            if (event.getOwner().equals(owner.getUUID()) && event.getEntity() == friend) {
                event.setAllowed(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(team);
        Revenant revenant = rise(helper, owner, List.of(new ItemStack(Items.EMERALD, 4)), 0);
        revenant.hurt(level.damageSources().playerAttack(friend), 1000.0F);
        helper.assertTrue(done(friend, notOwn), "friend's kill did not fire revenant_reclaimed own=false");
        helper.assertFalse(done(friend, own), "friend's kill fired own=true");
        helper.assertValueEqual(stat(friend), 1, "friend's stat");
        helper.assertTrue(asked.stream().anyMatch(event -> event.getItem() == null && !event.isOwner()), "kill asked the event");
        List<ItemEntity> drops = drops(helper);
        helper.runAfterDelay(Revenants.PICKUP_DELAY_TICKS + 1, () -> {
            try {
                drops.forEach(drop -> drop.playerTouch(friend));
                helper.assertValueEqual(friend.getInventory().countItem(Items.EMERALD), 4, "friend's emeralds");
                helper.assertTrue(asked.stream().anyMatch(event -> event.getItem() != null), "pickup asked the event");
            } finally {
                NeoForge.EVENT_BUS.unregister(team);
                clear(helper);
            }
            helper.succeed();
        });
    }

    /** After the timeout anyone may pick the items up, on touch or freed by the item's own tick, and they despawn again. */
    @GameTest(template = "empty", batch = "revenant_timeout")
    public static void pickupTimeoutFreesDrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
        ServerPlayer stranger = player(helper, new BlockPos(0, 1, 1));
        Revenant revenant = rise(helper, owner, List.of(new ItemStack(Items.GOLD_INGOT, 2), new ItemStack(Items.COAL, 9)), 0);
        revenant.hurt(level.damageSources().playerAttack(stranger), 1000.0F);
        List<ItemEntity> drops = drops(helper);
        helper.assertValueEqual(drops.size(), 2, "drops");
        ItemEntity touched = drops.stream().filter(drop -> drop.getItem().is(Items.GOLD_INGOT)).findFirst().orElseThrow();
        ItemEntity ticked = drops.stream().filter(drop -> drop.getItem().is(Items.COAL)).findFirst().orElseThrow();
        long now = level.getGameTime();
        // Expire both restrictions as if the timeout had passed.
        touched.setData(RemissionAttachments.REVENANT_DROP, new RevenantDrop(owner.getUUID(), now - 100, now));
        ticked.setData(RemissionAttachments.REVENANT_DROP, new RevenantDrop(owner.getUUID(), now - 100, now));
        touched.playerTouch(stranger);
        helper.assertValueEqual(stranger.getInventory().countItem(Items.GOLD_INGOT), 2, "stranger's gold after the timeout");
        helper.succeedWhen(() -> {
            helper.assertTrue(Revenants.restriction(ticked) == null, "ticked drop still restricted");
            helper.assertFalse(ticked.hasPickUpDelay(), "ticked drop still has its pickup delay");
            helper.assertTrue(ticked.getAge() > -32768, "ticked drop still never despawns");
            ticked.discard();
            clear(helper);
        });
    }

    /** The index has the revenant from its rise, follows it as it moves and drops it when it dies. */
    @GameTest(template = "empty", batch = "revenant_index")
    public static void indexFollowsRevenants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, new BlockPos(1, 1, 1));
        Revenant revenant = rise(helper, owner, List.of(new ItemStack(Items.STONE)), 0);
        revenant.setNoAi(true);
        RevenantIndex index = RevenantIndex.get(level);
        RevenantIndex.Entry entry = index.find(revenant.getUUID());
        helper.assertTrue(entry != null, "not indexed on rise");
        helper.assertValueEqual(entry.owner(), owner.getUUID(), "indexed owner");
        helper.assertValueEqual(entry.ownerName(), owner.getGameProfile().getName(), "indexed owner name");
        helper.assertValueEqual(entry.pos(), revenant.blockPosition(), "indexed position on rise");
        helper.assertTrue(RevenantIndex.ofOwner(level.getServer(), owner.getUUID()).stream()
                .anyMatch(located -> located.dimension() == level.dimension()
                        && located.entry().revenant().equals(revenant.getUUID())), "ofOwner");
        BlockPos moved = revenant.blockPosition().east(3);
        revenant.teleportTo(moved.getX() + 0.5, moved.getY(), moved.getZ() + 0.5);
        // The revenant's own tick moves its entry. Ticked by hand: the new spot may lie in a chunk next to the test that is
        // loaded but not entity-ticking.
        revenant.tick();
        helper.assertValueEqual(revenant.blockPosition(), moved, "position after the move");
        helper.assertValueEqual(index.find(revenant.getUUID()).pos(), moved, "indexed position after a move");
        revenant.hurt(level.damageSources().playerAttack(owner), 1000.0F);
        helper.assertTrue(index.find(revenant.getUUID()) == null, "still indexed after death");
        clear(helper);
        helper.succeed();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** A survival server player at {@code rel} with a connection that drops what it is sent (see QuestTriggerTests). */
    private static ServerPlayer player(GameTestHelper helper, BlockPos rel) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "revenant-test"), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            }
        };
        player.setGameMode(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(rel);
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        return player;
    }

    /** Fills main inventory, armour, offhand and experience; returns what a death should drop. */
    private static List<ItemStack> loadout(ServerPlayer player) {
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().selected = 0;
        player.getInventory().setItem(5, new ItemStack(Items.BREAD, 12));
        player.getInventory().setItem(20, new ItemStack(Items.COBBLESTONE, 64));
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        player.experienceLevel = EXPERIENCE_LEVEL;
        return List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.BREAD, 12), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.IRON_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.SHIELD));
    }

    private static Revenant rise(GameTestHelper helper, ServerPlayer owner, List<ItemStack> items, int experience) {
        ServerLevel level = helper.getLevel();
        Revenant revenant = Revenants.rise(level, owner, level.damageSources().generic(), owner.blockPosition(), items,
                experience, 0.0F, Map.of());
        helper.assertTrue(revenant != null, "no revenant");
        return revenant;
    }

    private static Zombie zombie(GameTestHelper helper, boolean host) {
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        if (host) {
            Corruption.set(zombie, 50, CorruptionCause.COMMAND);
        }
        return zombie;
    }

    private static AABB area(GameTestHelper helper) {
        return new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(6.0, 400.0, 6.0);
    }

    private static List<Revenant> revenants(GameTestHelper helper, BlockPos near) {
        return helper.getLevel().getEntitiesOfClass(Revenant.class, new AABB(near).inflate(2.0), Entity::isAlive);
    }

    private static Revenant onlyRevenant(GameTestHelper helper, BlockPos deathPos) {
        List<Revenant> found = revenants(helper, deathPos);
        helper.assertValueEqual(found.size(), 1, "revenants at " + deathPos);
        return found.getFirst();
    }

    private static List<ItemEntity> drops(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper), Entity::isAlive);
    }

    private static int experience(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, area(helper), Entity::isAlive).stream()
                .mapToInt(ExperienceOrb::getValue).sum();
    }

    private static void assertNoDrops(GameTestHelper helper, BlockPos deathPos) {
        helper.assertTrue(drops(helper).isEmpty(), "items dropped at " + deathPos);
        helper.assertValueEqual(experience(helper), 0, "experience dropped at " + deathPos);
    }

    private static void clearDrops(GameTestHelper helper) {
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper)).forEach(Entity::discard);
        helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, area(helper)).forEach(Entity::discard);
    }

    private static void clear(GameTestHelper helper) {
        clearDrops(helper);
        helper.getLevel().getEntitiesOfClass(Revenant.class, area(helper)).forEach(Entity::discard);
    }

    /** Same stacks, same counts, in any order. */
    private static void assertSameItems(GameTestHelper helper, List<ItemStack> expected, List<ItemStack> actual, String what) {
        List<ItemStack> left = new ArrayList<>(actual);
        for (ItemStack stack : expected) {
            int match = -1;
            for (int i = 0; i < left.size(); i++) {
                if (ItemStack.matches(left.get(i), stack)) {
                    match = i;
                    break;
                }
            }
            helper.assertTrue(match >= 0, what + ": missing " + stack + " in " + actual);
            left.remove(match);
        }
        helper.assertTrue(left.isEmpty(), what + ": unexpected " + left);
    }

    private static <E extends Event> List<E> listen(Class<E> type, List<Consumer<?>> listeners) {
        List<E> events = new ArrayList<>();
        Consumer<E> listener = events::add;
        NeoForge.EVENT_BUS.addListener(type, listener);
        listeners.add(listener);
        return events;
    }

    private static void unlisten(List<Consumer<?>> listeners) {
        listeners.forEach(NeoForge.EVENT_BUS::unregister);
    }

    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player, String json) {
        Criterion<?> criterion = Criterion.CODEC.parse(
                helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
        AdvancementHolder holder = Advancement.Builder.advancement().addCriterion("gate", criterion)
                .build(Remission.id("test/" + UUID.randomUUID()));
        listenTrigger(player, criterion, holder);
        return holder;
    }

    private static <T extends CriterionTriggerInstance> void listenTrigger(ServerPlayer player, Criterion<T> criterion, AdvancementHolder holder) {
        criterion.trigger().addPlayerListener(player.getAdvancements(),
                new CriterionTrigger.Listener<>(criterion.triggerInstance(), holder, "gate"));
    }

    private static boolean done(ServerPlayer player, AdvancementHolder holder) {
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static int stat(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(RemissionStats.REVENANTS_RECLAIMED.get()));
    }
}
