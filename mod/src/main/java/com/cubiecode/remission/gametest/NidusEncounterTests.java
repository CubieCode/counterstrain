package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.SurgeEndedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.world.NidusBlock;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.cubiecode.remission.world.NidusEncounters;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.NidusSettings;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Nidus encounters (P4.10): mining never breaks a nidus, strikes, projectiles and explosions deal their damage, each
 * threshold starts one surge from the nidus and lashes players, vitality regenerates and is saved, the final blow drops
 * the core and credits every contributor within the window, vitality and surge intensity scale with players, the
 * damaged event cancels and scales damage, and vitality 0 restores mining. Batch {@code nidi} runs on {@link #SETTINGS}
 * (installed for the batch); batch {@code nidi_plain} on the same with vitality 0.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NidusEncounterTests {
    private static final String BATCH = "nidi";
    private static final String PLAIN_BATCH = "nidi_plain";
    static final NidusSettings SETTINGS = new NidusSettings(100.0, 1000, 1.0, 16, 0.5, 3, 0.0, 2.0, 200, 40, 0.1,
            List.of(75, 50, 25), 0.5, 0.25, 6.0, 15.0, 1.0);
    private static final NidusSettings PLAIN = new NidusSettings(0.0, 1000, 1.0, 16, 0.5, 3, 0.0, 2.0, 200, 40, 0.1,
            List.of(75, 50, 25), 0.5, 0.25, 6.0, 15.0, 1.0);
    private static final float EPSILON = 1.0E-3F;

    private NidusEncounterTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void installSettings(ServerLevel level) {
        NidusSettings.override(SETTINGS);
    }

    @AfterBatch(batch = BATCH)
    public static void restoreSettings(ServerLevel level) {
        NidusSettings.override(null);
    }

    @BeforeBatch(batch = PLAIN_BATCH)
    public static void installPlainSettings(ServerLevel level) {
        NidusSettings.override(PLAIN);
    }

    @AfterBatch(batch = PLAIN_BATCH)
    public static void restorePlainSettings(ServerLevel level) {
        NidusSettings.override(null);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    /** A nidus in the test area, survival players next to it (registered as online), and listeners; cleaned up after. */
    private static final class Fight implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos rel = new BlockPos(2, 2, 2);
        final BlockPos pos;
        final List<ServerPlayer> players = new ArrayList<>();
        private final List<Consumer<?>> listeners = new ArrayList<>();

        Fight(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(rel);
            helper.setBlock(rel, RemissionBlocks.NIDUS.get());
        }

        NidusBlockEntity nidus() {
            return (NidusBlockEntity) level.getBlockEntity(pos);
        }

        /** A survival player {@code dx} blocks east of the nidus, on its level, counted as online. */
        ServerPlayer player(int dx) {
            ServerPlayer player = SurgeTests.player(helper, pos.east(dx));
            players.add(player);
            NidusEncounters.addTestPlayer(pos, player);
            return player;
        }

        <E extends Event> List<E> listen(Class<E> type) {
            List<E> events = new ArrayList<>();
            Consumer<E> listener = events::add;
            NeoForge.EVENT_BUS.addListener(type, listener);
            listeners.add(listener);
            return events;
        }

        <E extends Event> void listen(Class<E> type, Consumer<E> listener) {
            NeoForge.EVENT_BUS.addListener(type, listener);
            listeners.add(listener);
        }

        float strike(ServerPlayer player, float amount, long now) {
            return nidus().damage(level, NidusDamagedEvent.Kind.STRIKE, player, null, amount, SETTINGS, now);
        }

        @Override
        public void close() {
            listeners.forEach(NeoForge.EVENT_BUS::unregister);
            NidusEncounters.removeTestPlayers(pos);
            if (level.getBlockState(pos).is(RemissionBlocks.NIDUS.get())) {
                level.removeBlock(pos, false); // a nidus left in place would spread into neighbouring tests
            }
        }
    }

    private static List<SurgeStartedEvent> from(List<SurgeStartedEvent> events, BlockPos origin) {
        return events.stream().filter(event -> event.getOrigin().equals(origin)).toList();
    }

    // ---- mining ------------------------------------------------------------------------------------------------------

    /**
     * Survival mining makes no progress and a survival break (any number of ticks, or the break itself) leaves the nidus
     * with no drop; a creative player and an edit still remove it at once.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void miningNeverBreaksNidus(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_HOE));
            BlockState state = fight.level.getBlockState(fight.pos);
            helper.assertValueEqual(state.getDestroyProgress(player, fight.level, fight.pos), 0.0F, "survival mining progress");

            player.gameMode.handleBlockBreakAction(fight.pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP, fight.level.getMaxBuildHeight(), 0);
            for (int i = 0; i < 400; i++) {
                player.gameMode.tick();
            }
            player.gameMode.handleBlockBreakAction(fight.pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP, fight.level.getMaxBuildHeight(), 0);
            player.gameMode.destroyBlock(fight.pos);
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            helper.assertItemEntityNotPresent(RemissionItems.NIDUS_CORE.get(), fight.rel, 3);
            helper.assertTrue(NidusIndex.get(fight.level).contains(fight.pos), "a mined nidus left the index");
            helper.assertTrue(fight.nidus().fighting(), "the strike that started the mining did not start a fight");

            List<NidusDestroyedEvent> destroyed = fight.listen(NidusDestroyedEvent.class);
            player.setGameMode(GameType.CREATIVE);
            player.gameMode.destroyBlock(fight.pos);
            helper.assertBlockPresent(Blocks.AIR, fight.rel);
            helper.assertValueEqual(destroyed.size(), 1, "destroyed events for a creative break");
            helper.assertValueEqual(destroyed.getFirst().getPlayer().orElse(null), player, "creative breaker");

            helper.setBlock(fight.rel, RemissionBlocks.NIDUS.get());
            helper.setBlock(fight.rel, Blocks.STONE);
            helper.assertValueEqual(destroyed.size(), 2, "destroyed events after an edit");
            helper.assertTrue(destroyed.get(1).getPlayer().isEmpty(), "an edit has a player");
            helper.assertTrue(destroyed.get(1).getContributors().isEmpty(), "an edit has contributors");
        }
        helper.succeed();
    }

    // ---- damage ------------------------------------------------------------------------------------------------------

    /**
     * A strike through the left-click path deals the held item's attack damage scaled by attack strength (and resets
     * it), wears the item by 1, and a bare hand deals less than a sword.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void strikesDealAttackDamage(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            player.setItemInHand(InteractionHand.MAIN_HAND, sword);
            // A mock player never ticks, so apply the held item's attack modifiers as a tick's equipment check would.
            sword.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) ->
                    player.getAttribute(attribute).addOrReplacePermanentModifier(modifier));
            float expected = NidusEncounters.strikeDamage(player);
            float strength = player.getAttackStrengthScale(0.5F);
            helper.assertTrue(Math.abs(expected - 6.0F * (0.2F + strength * strength * 0.8F)) < EPSILON,
                    "iron sword strike damage " + expected);
            player.gameMode.handleBlockBreakAction(fight.pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP, fight.level.getMaxBuildHeight(), 0);
            helper.assertTrue(Math.abs(fight.nidus().damageTaken() - expected) < EPSILON,
                    "damage after a strike: " + fight.nidus().damageTaken() + ", expected " + expected);
            helper.assertValueEqual(sword.getDamageValue(), 1, "sword wear");

            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.getAttribute(Attributes.ATTACK_DAMAGE).removeModifiers();
            float hand = NidusEncounters.strikeDamage(player);
            helper.assertTrue(hand < expected, "a bare hand (" + hand + ") deals as much as a sword (" + expected + ")");
        }
        helper.succeed();
    }

    /**
     * An arrow deals its vanilla damage times the projectile fraction, credited to its owner; an explosion its mob
     * damage times the explosion fraction without removing the nidus; neither takes the last point of vitality.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void projectilesAndExplosionsDealFractions(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer archer = fight.player(3);
            Arrow arrow = new Arrow(EntityType.ARROW, fight.level);
            arrow.setOwner(archer);
            arrow.setPos(Vec3.atCenterOf(fight.pos).add(2.0, 0.0, 0.0));
            arrow.setDeltaMovement(-2.5, 0.0, 0.0);
            float arrowDamage = (float) Math.ceil(2.5 * arrow.getBaseDamage()) * 0.5F;
            BlockState state = fight.level.getBlockState(fight.pos);
            state.onProjectileHit(fight.level, state, new BlockHitResult(Vec3.atCenterOf(fight.pos), Direction.EAST, fight.pos, false), arrow);
            helper.assertTrue(Math.abs(fight.nidus().damageTaken() - arrowDamage) < EPSILON,
                    "arrow damage " + fight.nidus().damageTaken() + ", expected " + arrowDamage);

            // A small blast, so it reaches no nidus of a test running beside this one.
            Vec3 centre = Vec3.atCenterOf(fight.pos).add(0.0, 1.5, 0.0);
            float explosionDamage = NidusEncounters.explosionDamage(centre, 1.5F, fight.pos) * 0.25F;
            fight.level.explode(archer, centre.x, centre.y, centre.z, 1.5F, Level.ExplosionInteraction.NONE);
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            helper.assertTrue(Math.abs(fight.nidus().damageTaken() - arrowDamage - explosionDamage) < EPSILON,
                    "damage after the explosion " + fight.nidus().damageTaken() + ", expected " + (arrowDamage + explosionDamage));

            // An explosion cannot finish a nidus: it stops at 1 vitality.
            long now = fight.level.getGameTime();
            fight.nidus().damage(fight.level, NidusDamagedEvent.Kind.EXPLOSION, archer, null, 1000.0F, SETTINGS, now);
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            helper.assertTrue(Math.abs(fight.nidus().vitality(SETTINGS) - 1.0F) < EPSILON,
                    "vitality after a huge explosion: " + fight.nidus().vitality(SETTINGS));
            helper.assertValueEqual(fight.nidus().damage(fight.level, NidusDamagedEvent.Kind.PROJECTILE, archer, arrow, 5.0F,
                    SETTINGS, now), 0.0F, "projectile damage at 1 vitality");
        }
        helper.succeed();
    }

    // ---- thresholds --------------------------------------------------------------------------------------------------

    /**
     * Each threshold starts one surge from that nidus at once (intensity 1, 2, 3 for one player), replacing the last, and
     * lashes the player in range with viral load and knockback; a player out of lash range is spared.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void thresholdsSurgeAndLash(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer near = fight.player(2);
            ServerPlayer far = fight.player(12);
            List<SurgeStartedEvent> started = fight.listen(SurgeStartedEvent.class);
            List<SurgeEndedEvent> ended = fight.listen(SurgeEndedEvent.class);
            long now = fight.level.getGameTime();
            int players = 2; // both within the 16-block radius: vitality 150
            float max = (float) (SETTINGS.vitality() * SETTINGS.playerMultiplier(players));

            fight.strike(near, max * 0.2F, now);
            helper.assertTrue(from(started, fight.pos).isEmpty(), "a surge before the first threshold");
            helper.assertValueEqual(ViralLoad.get(near), 0.0F, "viral load before the first threshold");

            fight.strike(near, max * 0.06F, now);
            helper.assertValueEqual(from(started, fight.pos).size(), 1, "surges after the first threshold");
            helper.assertValueEqual(from(started, fight.pos).getFirst().getIntensity(),
                    (int) Math.round(1 * SETTINGS.playerMultiplier(players)), "first surge intensity");
            helper.assertValueEqual(Surges.controller(fight.level).current().origin(), fight.pos, "running surge origin");
            helper.assertTrue(Surges.controller(fight.level).current().started(), "the threshold surge waited for a warning");
            helper.assertValueEqual(ViralLoad.get(near), (float) SETTINGS.lashViralLoad(), "lashed viral load");
            helper.assertTrue(near.getDeltaMovement().x > 0, "lash did not push the player away: " + near.getDeltaMovement());
            helper.assertValueEqual(ViralLoad.get(far), 0.0F, "viral load out of lash range");

            fight.strike(near, max * 0.25F, now);
            fight.strike(near, max * 0.25F, now);
            List<SurgeStartedEvent> ours = from(started, fight.pos);
            helper.assertValueEqual(ours.size(), 3, "surges after three thresholds");
            helper.assertValueEqual(ours.get(1).getIntensity(), (int) Math.round(2 * SETTINGS.playerMultiplier(players)), "second intensity");
            helper.assertValueEqual(ours.get(2).getIntensity(), (int) Math.round(3 * SETTINGS.playerMultiplier(players)), "third intensity");
            helper.assertTrue(ended.stream().filter(e -> e.getOrigin().equals(fight.pos))
                    .allMatch(e -> e.getReason() == SurgeEndedEvent.Reason.SUPERSEDED), "a replaced surge ended other than superseded");
            helper.assertValueEqual(fight.nidus().thresholdsPassed(), 3, "thresholds passed");
            helper.assertValueEqual(ViralLoad.get(near), (float) SETTINGS.lashViralLoad() * 3, "viral load after three lashes");

            // No threshold left: more damage short of the last point starts nothing.
            fight.strike(near, max * 0.1F, now);
            helper.assertValueEqual(from(started, fight.pos).size(), 3, "surges after the last threshold");
            helper.assertTrue(fight.nidus().barPlayers().containsAll(List.of(near, far)), "boss bar players");
            helper.assertTrue(Math.abs(fight.nidus().barProgress() - fight.nidus().vitality(SETTINGS) / max) < EPSILON,
                    "boss bar progress");
            helper.assertValueEqual(fight.nidus().damageStage(), 3, "damage shown at 14% vitality");
            helper.assertValueEqual(fight.level.getBlockState(fight.pos).getValue(NidusBlock.DAMAGE), 3, "damage state");
        }
        helper.succeed();
    }

    // ---- regeneration and saving -------------------------------------------------------------------------------------

    /**
     * Vitality regenerates only after the delay, at the configured rate; the fight is saved with the block entity; once
     * whole the fight is over and the thresholds re-arm.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void vitalityRegeneratesAndSaves(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            NidusBlockEntity nidus = fight.nidus();
            long now = fight.level.getGameTime();
            fight.strike(player, 30.0F, now);
            helper.assertValueEqual(nidus.thresholdsPassed(), 1, "thresholds after 30 damage");
            helper.assertValueEqual(nidus.damageStage(), 1, "damage shown after 30 damage");

            nidus.tick(fight.level, SETTINGS, now + SETTINGS.regenDelayTicks() - 1);
            helper.assertTrue(Math.abs(nidus.damageTaken() - 30.0F) < EPSILON, "regenerated before the delay");
            nidus.tick(fight.level, SETTINGS, now + SETTINGS.regenDelayTicks());
            float perTick = (float) (SETTINGS.regenRate() * SETTINGS.vitality() / 20.0);
            helper.assertTrue(Math.abs(nidus.damageTaken() - (30.0F - perTick)) < EPSILON,
                    "damage after one regeneration tick: " + nidus.damageTaken());

            CompoundTag saved = nidus.saveWithoutMetadata(fight.level.registryAccess());
            NidusBlockEntity loaded = new NidusBlockEntity(fight.pos, fight.level.getBlockState(fight.pos));
            loaded.loadWithComponents(saved, fight.level.registryAccess());
            helper.assertTrue(loaded.fighting(), "loaded nidus is not fighting");
            helper.assertTrue(Math.abs(loaded.damageTaken() - nidus.damageTaken()) < EPSILON, "loaded damage");
            helper.assertValueEqual(loaded.thresholdsPassed(), 1, "loaded thresholds");
            helper.assertValueEqual(loaded.fightPlayers(), 1, "loaded players");

            long t = now + SETTINGS.regenDelayTicks();
            while (nidus.fighting() && t < now + 2000) {
                nidus.tick(fight.level, SETTINGS, ++t);
            }
            helper.assertFalse(nidus.fighting(), "the fight did not end after regenerating");
            helper.assertValueEqual(nidus.thresholdsPassed(), 0, "thresholds after the fight ended");
            helper.assertValueEqual(nidus.damageStage(), 0, "damage shown after the fight ended");
            helper.assertTrue(nidus.barPlayers().isEmpty(), "boss bar after the fight ended");
        }
        helper.succeed();
    }

    // ---- the final blow ----------------------------------------------------------------------------------------------

    /**
     * The final strike destroys the nidus and drops its core; the destroyed event names the striker and every player
     * who damaged it within the window (a projectile's owner too), and each of them earns the trigger and stat, but not
     * a player whose only strike was before the window.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void finalBlowDropsCoreAndCreditsContributors(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer striker = fight.player(1);
            ServerPlayer archer = fight.player(3);
            ServerPlayer early = fight.player(-2);
            List<NidusDestroyedEvent> destroyed = fight.listen(NidusDestroyedEvent.class);
            AdvancementHolder strikerGate = gate(helper, striker);
            AdvancementHolder archerGate = gate(helper, archer);
            AdvancementHolder earlyGate = gate(helper, early);
            long now = fight.level.getGameTime();

            fight.strike(early, 5.0F, now - SETTINGS.contributorWindowTicks() - 1);
            fight.strike(striker, 10.0F, now);
            fight.nidus().damage(fight.level, NidusDamagedEvent.Kind.PROJECTILE, archer, null, 10.0F, SETTINGS, now);
            float left = fight.nidus().vitality(SETTINGS);
            fight.strike(striker, left - 1.0F, now);
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            fight.strike(striker, 50.0F, now);

            helper.assertBlockPresent(Blocks.AIR, fight.rel);
            helper.assertItemEntityCountIs(RemissionItems.NIDUS_CORE.get(), fight.rel, 3, 1);
            helper.assertFalse(NidusIndex.get(fight.level).contains(fight.pos), "destroyed nidus still indexed");
            helper.assertValueEqual(destroyed.size(), 1, "destroyed events");
            NidusDestroyedEvent event = destroyed.getFirst();
            helper.assertValueEqual(event.getPlayer().orElse(null), striker, "destroying player");
            helper.assertValueEqual(event.getContributors(), List.of(striker.getUUID(), archer.getUUID()), "contributors");
            helper.assertTrue(done(striker, strikerGate), "nidus_destroyed for the striker");
            helper.assertTrue(done(archer, archerGate), "nidus_destroyed for the archer");
            helper.assertFalse(done(early, earlyGate), "nidus_destroyed for a strike outside the window");
            helper.assertValueEqual(stat(striker), 1, "striker's nidi destroyed");
            helper.assertValueEqual(stat(archer), 1, "archer's nidi destroyed");
            helper.assertValueEqual(stat(early), 0, "early player's nidi destroyed");
        }
        helper.succeed();
    }

    // ---- scaling -----------------------------------------------------------------------------------------------------

    /**
     * Vitality scales with the players in range when the fight starts and rises (keeping its share) as more join, up to
     * the cap; never falls when they leave; surge intensity uses the same factor. Growth and distance scale it too.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void vitalityScalesWithPlayers(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer first = fight.player(1);
            fight.player(2);
            ServerPlayer outside = fight.player(20);
            long now = fight.level.getGameTime();
            List<SurgeStartedEvent> started = fight.listen(SurgeStartedEvent.class);
            NidusBlockEntity nidus = fight.nidus();
            fight.strike(first, 15.0F, now);
            helper.assertValueEqual(nidus.fightPlayers(), 2, "players at the start (one is out of range)");
            helper.assertTrue(Math.abs(nidus.maxVitality(SETTINGS) - 150.0F) < EPSILON, "vitality for two players");

            fight.player(-1);
            outside.moveTo(fight.pos.getX() + 3.5, fight.pos.getY(), fight.pos.getZ() + 0.5);
            long check = (now / NidusBlockEntity.CHECK_TICKS + 1) * NidusBlockEntity.CHECK_TICKS;
            nidus.tick(fight.level, SETTINGS, check);
            helper.assertValueEqual(nidus.fightPlayers(), 4, "players after two joined");
            helper.assertTrue(Math.abs(nidus.maxVitality(SETTINGS) - 200.0F) < EPSILON,
                    "vitality is capped at three players: " + nidus.maxVitality(SETTINGS));
            helper.assertTrue(Math.abs(nidus.damageTaken() - 20.0F) < EPSILON, "damage keeps its share: " + nidus.damageTaken());

            fight.strike(first, 35.0F, now);
            helper.assertValueEqual(from(started, fight.pos).size(), 1, "surges after the first threshold");
            helper.assertValueEqual(from(started, fight.pos).getFirst().getIntensity(), 2, "surge intensity for the capped three");

            outside.moveTo(fight.pos.getX() + 40.5, fight.pos.getY(), fight.pos.getZ() + 0.5);
            nidus.tick(fight.level, SETTINGS, check + NidusBlockEntity.CHECK_TICKS);
            helper.assertValueEqual(nidus.fightPlayers(), 4, "players after one left");
            helper.assertTrue(Math.abs(nidus.maxVitality(SETTINGS) - 200.0F) < EPSILON, "vitality after one left");

            // Distance scaling (off in SETTINGS): the factor per 1000 blocks from spawn, capped.
            NidusSettings far = new NidusSettings(100.0, 1000, 1.0, 16, 0.5, 3, 1.0, 0.5, 200, 40, 0.1, List.of(75, 50, 25),
                    0.5, 0.25, 6.0, 15.0, 1.0);
            helper.assertTrue(Math.abs(far.distanceMultiplier(200.0) - 1.2) < 1.0E-6, "distance multiplier at 200 blocks");
            helper.assertTrue(Math.abs(far.distanceMultiplier(1.0E6) - 1.5) < 1.0E-6, "distance multiplier cap");
            helper.assertTrue(Math.abs(SETTINGS.distanceMultiplier(1.0E6) - 1.0) < 1.0E-6, "distance scaling off by default");
            helper.assertTrue(Math.abs(SETTINGS.growthMultiplier(SETTINGS.growthTicks() * 10L) - 2.0) < 1.0E-6, "growth cap");
        }
        helper.succeed();
    }

    /** A fresh fight on a nidus indexed half its growth time ago starts at 1.5 times the vitality. */
    @GameTest(template = "empty", batch = BATCH)
    public static void vitalityScalesWithGrowth(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            NidusIndex index = NidusIndex.get(fight.level);
            index.remove(fight.pos);
            index.add(fight.pos, fight.level.getGameTime() - SETTINGS.growthTicks() / 2);
            fight.strike(player, 1.0F, fight.level.getGameTime());
            helper.assertTrue(Math.abs(fight.nidus().maxVitality(SETTINGS) - 150.0F) < 0.5F,
                    "vitality of a half-grown nidus: " + fight.nidus().maxVitality(SETTINGS));
        }
        helper.succeed();
    }

    // ---- the damaged event -------------------------------------------------------------------------------------------

    /** A listener scales damage and another cancels it: a cancelled strike deals nothing and earns no credit. */
    @GameTest(template = "empty", batch = BATCH)
    public static void damagedEventCancelsAndScales(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            ServerPlayer blocked = fight.player(2);
            List<NidusDamagedEvent> seen = new ArrayList<>();
            fight.listen(NidusDamagedEvent.class, event -> {
                if (!event.getPos().equals(fight.pos)) {
                    return;
                }
                seen.add(event);
                if (event.getPlayer().orElse(null) == blocked) {
                    event.setCanceled(true);
                } else {
                    event.setAmount(event.getAmount() * 3.0F);
                }
            });
            long now = fight.level.getGameTime();
            helper.assertTrue(Math.abs(fight.strike(player, 2.0F, now) - 6.0F) < EPSILON, "scaled damage dealt");
            helper.assertTrue(Math.abs(fight.nidus().damageTaken() - 6.0F) < EPSILON, "scaled damage taken");
            helper.assertValueEqual(fight.strike(blocked, 50.0F, now), 0.0F, "cancelled damage dealt");
            helper.assertTrue(Math.abs(fight.nidus().damageTaken() - 6.0F) < EPSILON, "damage after a cancelled strike");
            helper.assertValueEqual(seen.size(), 2, "damaged events");
            helper.assertValueEqual(seen.getFirst().getKind(), NidusDamagedEvent.Kind.STRIKE, "event kind");
            helper.assertValueEqual(seen.getFirst().getAmount(), 6.0F, "event amount after scaling");
            List<UUID> credited = new ArrayList<>();
            fight.listen(NidusDestroyedEvent.class, event -> credited.addAll(event.getContributors()));
            fight.strike(player, 1000.0F, now);
            helper.assertValueEqual(credited, List.of(player.getUUID()), "contributors (the cancelled player excluded)");
        }
        helper.succeed();
    }

    // ---- vitality 0 --------------------------------------------------------------------------------------------------

    /** With vitality 0 a survival player mines the nidus as before (core, trigger), and strikes deal nothing. */
    @GameTest(template = "empty", batch = PLAIN_BATCH)
    public static void vitalityZeroRestoresMining(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            AdvancementHolder destroyedGate = gate(helper, player);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
            BlockState state = fight.level.getBlockState(fight.pos);
            helper.assertTrue(state.getDestroyProgress(player, fight.level, fight.pos) > 0.0F, "no mining progress at vitality 0");
            helper.assertValueEqual(fight.nidus().damage(fight.level, NidusDamagedEvent.Kind.STRIKE, player, null, 10.0F,
                    NidusSettings.current(), fight.level.getGameTime()), 0.0F, "strike damage at vitality 0");
            helper.assertTrue(player.gameMode.destroyBlock(fight.pos), "player could not mine the nidus");
            helper.assertBlockPresent(Blocks.AIR, fight.rel);
            helper.assertItemEntityCountIs(RemissionItems.NIDUS_CORE.get(), fight.rel, 3, 1);
            helper.assertTrue(done(player, destroyedGate), "nidus_destroyed for the miner");
        }
        helper.succeed();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player) {
        Criterion<?> criterion = Criterion.CODEC.parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString("{\"trigger\":\"remission:nidus_destroyed\"}")).getOrThrow();
        AdvancementHolder holder = Advancement.Builder.advancement().addCriterion("gate", criterion)
                .build(Remission.id("test/" + UUID.randomUUID()));
        listen(player, criterion, holder);
        return holder;
    }

    private static <T extends CriterionTriggerInstance> void listen(ServerPlayer player, Criterion<T> criterion, AdvancementHolder holder) {
        criterion.trigger().addPlayerListener(player.getAdvancements(), new CriterionTrigger.Listener<>(criterion.triggerInstance(), holder, "gate"));
    }

    private static boolean done(ServerPlayer player, AdvancementHolder holder) {
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static int stat(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(RemissionStats.NIDI_DESTROYED.get()));
    }
}
