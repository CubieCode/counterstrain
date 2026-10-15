package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.api.NidusDestroyedEvent;
import com.cubiecode.remission.api.SurgeEndedEvent;
import com.cubiecode.remission.api.SurgeStartedEvent;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionEntityTypes;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.world.NidusBlock;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.cubiecode.remission.world.NidusDefenceSettings;
import com.cubiecode.remission.world.NidusEncounters;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.NidusSettings;
import com.cubiecode.remission.world.NidusTentacle;
import com.cubiecode.remission.world.NidusTentacles;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import com.cubiecode.remission.surge.SurgeController;
import com.cubiecode.remission.surge.SurgeData;
import com.cubiecode.remission.surge.SurgeSettings;
import com.cubiecode.remission.world.SpreadSettings;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Nidus encounters (P4.10): mining never breaks a nidus, strikes, projectiles and explosions deal their damage, each
 * threshold starts one surge from the nidus and lashes players, vitality regenerates and is saved, the final blow drops
 * the core and credits every contributor within the window, vitality and surge intensity scale with players, the
 * damaged event cancels and scales damage, and vitality 0 restores mining. What clients draw (health stage, hit
 * reaction, enraged while its surge runs) follows the fight. Batch {@code nidi} runs on {@link #SETTINGS}
 * (installed for the batch); batch {@code nidi_plain} on the same with vitality 0. Both run with rallying and
 * sympathetic surges off (P4.13; {@link NidusDefenceTests}).
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NidusEncounterTests {
    private static final String BATCH = "nidi";
    private static final String PLAIN_BATCH = "nidi_plain";
    static final NidusSettings SETTINGS = new NidusSettings(100.0, 1000, 1.0, 16, 0.5, 3, 0.0, 2.0, 200, 40, 0.1,
            List.of(75, 50, 25), 0.5, 0.25, 3.0, 15.0, 1.0, 10, 0, 0.5);
    private static final NidusSettings PLAIN = new NidusSettings(0.0, 1000, 1.0, 16, 0.5, 3, 0.0, 2.0, 200, 40, 0.1,
            List.of(75, 50, 25), 0.5, 0.25, 3.0, 15.0, 1.0, 10, 0, 0.5);
    private static final float EPSILON = 1.0E-3F;

    private NidusEncounterTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void installSettings(ServerLevel level) {
        NidusSettings.override(SETTINGS);
        NidusDefenceSettings.override(NidusDefenceTests.OFF);
    }

    @AfterBatch(batch = BATCH)
    public static void restoreSettings(ServerLevel level) {
        NidusSettings.override(null);
        NidusDefenceSettings.override(null);
    }

    @BeforeBatch(batch = PLAIN_BATCH)
    public static void installPlainSettings(ServerLevel level) {
        NidusSettings.override(PLAIN);
        NidusDefenceSettings.override(NidusDefenceTests.OFF);
    }

    @AfterBatch(batch = PLAIN_BATCH)
    public static void restorePlainSettings(ServerLevel level) {
        NidusSettings.override(null);
        NidusDefenceSettings.override(null);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

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

    // ---- mining -----------------------------------------------------------------------------------------------------

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

    // ---- damage -----------------------------------------------------------------------------------------------------

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

    // ---- thresholds -------------------------------------------------------------------------------------------------

    /**
     * Each threshold starts one surge from that nidus at once (intensity 1, 2, 3 for one player), replacing the last, and
     * lashes: the tentacle nearest the player in reach (due east: mid right) turns at it, and only at contact time does
     * the player gain viral load and get knocked away; a player out of lash range is spared.
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
            int midRight = NidusTentacles.NAMES.indexOf("mid_right");
            helper.assertTrue(blockEvents(fight.level).contains(new BlockEventData(fight.pos, RemissionBlocks.NIDUS.get(),
                    NidusBlockEntity.EVENT_LASH, NidusBlockEntity.lashParam(midRight, 90.0F))),
                    "no lash event toward the east from the mid right tentacle");
            helper.assertTrue(NidusBlockEntity.lashParam(midRight, 90.0F) < 256, "lash param does not fit the block event's byte");
            helper.assertValueEqual(ViralLoad.get(near), 0.0F, "viral load before the lash lands");
            fight.nidus().tick(fight.level, SETTINGS, now + NidusTentacles.LASH_CONTACT_TICKS - 1);
            helper.assertValueEqual(ViralLoad.get(near), 0.0F, "viral load a tick before the lash lands");
            fight.nidus().tick(fight.level, SETTINGS, now + NidusTentacles.LASH_CONTACT_TICKS);
            helper.assertValueEqual(from(started, fight.pos).getFirst().getIntensity(),
                    (int) Math.round(1 * SETTINGS.playerMultiplier(players)), "first surge intensity");
            helper.assertValueEqual(Surges.controller(fight.level).current().origin(), fight.pos, "running surge origin");
            helper.assertTrue(Surges.controller(fight.level).current().started(), "the threshold surge waited for a warning");
            helper.assertValueEqual(ViralLoad.get(near), (float) SETTINGS.lashViralLoad(), "lashed viral load");
            helper.assertTrue(near.getDeltaMovement().x > 0, "lash did not push the player away: " + near.getDeltaMovement());
            helper.assertValueEqual(ViralLoad.get(far), 0.0F, "viral load out of lash range");

            // The next thresholds once the tentacle has finished lashing: each lands at its contact time.
            for (int i = 1; i <= 2; i++) {
                long later = now + (long) i * NidusTentacles.LASH_TICKS;
                fight.strike(near, max * 0.25F, later);
                fight.nidus().tick(fight.level, SETTINGS, later + NidusTentacles.LASH_CONTACT_TICKS);
            }
            List<SurgeStartedEvent> ours = from(started, fight.pos);
            helper.assertValueEqual(ours.size(), 3, "surges after three thresholds");
            helper.assertValueEqual(ours.get(1).getIntensity(), (int) Math.round(2 * SETTINGS.playerMultiplier(players)), "second intensity");
            helper.assertValueEqual(ours.get(2).getIntensity(), (int) Math.round(3 * SETTINGS.playerMultiplier(players)), "third intensity");
            helper.assertTrue(ended.stream().filter(e -> e.getOrigin().equals(fight.pos))
                    .allMatch(e -> e.getReason() == SurgeEndedEvent.Reason.SUPERSEDED), "a replaced surge ended other than superseded");
            helper.assertValueEqual(fight.nidus().thresholdsPassed(), 3, "thresholds passed");
            helper.assertValueEqual(ViralLoad.get(near), (float) SETTINGS.lashViralLoad() * 3, "viral load after three lashes");

            // No threshold left: more damage short of the last point starts nothing.
            fight.strike(near, max * 0.1F, now + 3L * NidusTentacles.LASH_TICKS);
            helper.assertValueEqual(from(started, fight.pos).size(), 3, "surges after the last threshold");
            helper.assertTrue(fight.nidus().barPlayers().containsAll(List.of(near, far)), "boss bar players");
            helper.assertTrue(Math.abs(fight.nidus().barProgress() - fight.nidus().vitality(SETTINGS) / max) < EPSILON,
                    "boss bar progress");
            helper.assertValueEqual(fight.nidus().damageStage(), 3, "damage shown at 14% vitality");
            helper.assertValueEqual(fight.level.getBlockState(fight.pos).getValue(NidusBlock.DAMAGE), 3, "damage state");
        }
        helper.succeed();
    }

    // ---- regeneration and saving ------------------------------------------------------------------------------------

    /**
     * Vitality never regenerates while the nidus's own surge runs, then only after the delay, at the configured rate; the
     * fight is saved with the block entity; once whole the fight is over and the thresholds re-arm.
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

            // Its threshold surge is running: no regeneration however long, nor until the delay after the surge ends.
            nidus.tick(fight.level, SETTINGS, now + SETTINGS.regenDelayTicks() * 2L);
            helper.assertTrue(Math.abs(nidus.damageTaken() - 30.0F) < EPSILON, "regenerated during its own surge");
            Surges.controller(fight.level).stop(now);
            helper.assertFalse(nidus.enraged(), "still enraged after its surge stopped");

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

    // ---- the final blow ---------------------------------------------------------------------------------------------

    /**
     * The final strike kills the nidus: it plays its death for {@link NidusBlockEntity#DEATH_TICKS}, taking no damage,
     * then it is destroyed and drops its core; the destroyed event names the striker and every player who damaged it
     * within the window (a projectile's owner too), and each of them earns the trigger and stat, but not a player whose
     * only strike was before the window.
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
            NidusBlockEntity nidus = fight.nidus();
            fight.strike(striker, 50.0F, now);
            helper.assertTrue(nidus.dying(), "the final blow did not start the death");
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            helper.assertTrue(destroyed.isEmpty(), "destroyed before the death played");
            helper.assertTrue(nidus.getUpdateTag(fight.level.registryAccess()).getBoolean("dying"), "dying not in the update tag");
            helper.assertValueEqual(fight.strike(archer, 50.0F, now + 1), 0.0F, "damage to a dying nidus");
            helper.assertTrue(nidus.hitboxes().isEmpty(), "tentacle hitboxes while dying");

            // An unload mid-death keeps it: the reloaded block entity finishes it.
            CompoundTag saved = nidus.saveWithoutMetadata(fight.level.registryAccess());
            NidusBlockEntity reloaded = new NidusBlockEntity(fight.pos, fight.level.getBlockState(fight.pos));
            reloaded.loadWithComponents(saved, fight.level.registryAccess());
            helper.assertTrue(reloaded.dying(), "the death was not saved");

            nidus.tick(fight.level, SETTINGS, now + NidusBlockEntity.DEATH_TICKS - 1);
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            nidus.tick(fight.level, SETTINGS, now + NidusBlockEntity.DEATH_TICKS);
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

    // ---- scaling ----------------------------------------------------------------------------------------------------

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
                    0.5, 0.25, 3.0, 15.0, 1.0, 10, 0, 0.5);
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

    // ---- the damaged event ------------------------------------------------------------------------------------------

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
            NidusBlockEntity nidus = fight.nidus();
            fight.strike(player, 1000.0F, now);
            nidus.tick(fight.level, SETTINGS, now + NidusBlockEntity.DEATH_TICKS);
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

    // ---- fix round 1: fake players, strike cadence, attribution, supersession, explosions -----------------------------

    /** A fake player (a machine acting as a player) neither strikes, starts a fight, damages nor breaks a nidus. */
    @GameTest(template = "empty", batch = BATCH)
    public static void fakePlayersNeverStrike(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            FakePlayer machine = FakePlayerFactory.getMinecraft(fight.level);
            machine.moveTo(fight.pos.getX() + 1.5, fight.pos.getY(), fight.pos.getZ() + 0.5);
            machine.setGameMode(GameType.SURVIVAL);
            machine.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD));
            long now = fight.level.getGameTime();
            machine.gameMode.handleBlockBreakAction(fight.pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP, fight.level.getMaxBuildHeight(), 0);
            helper.assertValueEqual(fight.nidus().strike(fight.level, machine, SETTINGS, now), 0.0F, "fake player strike");
            for (NidusDamagedEvent.Kind kind : NidusDamagedEvent.Kind.values()) {
                helper.assertValueEqual(fight.nidus().damage(fight.level, kind, machine, null, 1000.0F, SETTINGS, now), 0.0F,
                        "fake player " + kind);
            }
            machine.gameMode.destroyBlock(fight.pos);
            helper.assertFalse(fight.nidus().fighting(), "a fake player started a fight");
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
        }
        helper.succeed();
    }

    /**
     * Clicking every tick (20 clicks a second) for 10 seconds deals less than fully charged swings over the same time,
     * with a sword, an axe and a bare hand: the strike cooldown ignores clicks within 10 ticks of the last strike that
     * counted.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void strikeSpamNeverBeatsChargedSwings(GameTestHelper helper) {
        NidusSettings tough = new NidusSettings(1.0E6, 1000, 1.0, 16, 0.5, 3, 0.0, 2.0, 200, 40, 0.1, List.of(), 0.5, 0.25,
                3.0, 15.0, 1.0, 10, 0, 0.5);
        try (Fight fight = new Fight(helper)) {
            for (ItemStack held : List.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_AXE), ItemStack.EMPTY)) {
                ServerPlayer spammer = fight.player(1);
                ServerPlayer charger = fight.player(-1);
                for (ServerPlayer player : List.of(spammer, charger)) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
                    held.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) ->
                            player.getAttribute(attribute).addOrReplacePermanentModifier(modifier));
                    setAttackTicker(player, 100);
                }
                long now = fight.level.getGameTime();
                float spam = 0.0F;
                float charged = 0.0F;
                for (int t = 0; t < 200; t++) {
                    spam += fight.nidus().strike(fight.level, spammer, tough, now + t);
                    if (charger.getAttackStrengthScale(0.5F) >= 1.0F) {
                        charged += fight.nidus().strike(fight.level, charger, tough, now + t);
                    }
                    for (ServerPlayer player : List.of(spammer, charger)) {
                        setAttackTicker(player, attackTicker(player) + 1);
                    }
                }
                helper.assertTrue(spam > 0 && spam <= charged, (held.isEmpty() ? "hand" : held.getItem().toString())
                        + ": spam dealt " + spam + ", charged swings " + charged);
            }
        }
        helper.succeed();
    }

    /**
     * Only damage traceable to a player counts: a host creeper's blast, unlit (ownerless) TNT and a skeleton's arrow do
     * nothing and start no fight; TNT a player lit does. Block-breaking blasts and a wither's dangerous skull leave the
     * nidus in place.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void onlyPlayerDamageCounts(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(4);
            Vec3 above = Vec3.atCenterOf(fight.pos).add(0.0, 1.2, 0.0);
            Creeper creeper = new Creeper(EntityType.CREEPER, fight.level);
            creeper.setPos(above);
            fight.level.explode(creeper, above.x, above.y, above.z, 1.5F, Level.ExplosionInteraction.MOB);
            PrimedTnt unlit = new PrimedTnt(fight.level, above.x, above.y, above.z, null);
            fight.level.explode(unlit, above.x, above.y, above.z, 1.5F, Level.ExplosionInteraction.TNT);
            Skeleton skeleton = new Skeleton(EntityType.SKELETON, fight.level);
            Arrow arrow = new Arrow(EntityType.ARROW, fight.level);
            arrow.setOwner(skeleton);
            arrow.setPos(Vec3.atCenterOf(fight.pos).add(2.0, 0.0, 0.0));
            arrow.setDeltaMovement(-2.5, 0.0, 0.0);
            BlockState state = fight.level.getBlockState(fight.pos);
            state.onProjectileHit(fight.level, state, new BlockHitResult(Vec3.atCenterOf(fight.pos), Direction.EAST, fight.pos, false), arrow);
            WitherSkull skull = new WitherSkull(EntityType.WITHER_SKULL, fight.level);
            skull.setDangerous(true);
            skull.setPos(above);
            fight.level.explode(skull, above.x, above.y, above.z, 1.0F, Level.ExplosionInteraction.MOB);
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            helper.assertFalse(fight.nidus().fighting(), "a host, unlit TNT, a skeleton or a wither skull started a fight");
            helper.assertValueEqual(fight.nidus().damageTaken(), 0.0F, "damage without a player");

            PrimedTnt lit = new PrimedTnt(fight.level, above.x, above.y, above.z, player);
            fight.level.explode(lit, above.x, above.y, above.z, 1.5F, Level.ExplosionInteraction.TNT);
            helper.assertBlockPresent(RemissionBlocks.NIDUS.get(), fight.rel);
            helper.assertTrue(fight.nidus().damageTaken() > 0.0F, "TNT a player lit dealt nothing");
        }
        helper.succeed();
    }

    /**
     * A threshold surge replacing a surge from the same nidus awards nothing for the replaced one and keeps its caught
     * players, so the fight awards {@code surge_survived} once; replacing a surge from another nidus awards its survivors.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void sameNidusSupersessionAwardsOnce(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            BlockPos other = fight.pos.east(3);
            fight.level.setBlockAndUpdate(other, RemissionBlocks.NIDUS.get().defaultBlockState());
            List<SurgeEndedEvent> ended = fight.listen(SurgeEndedEvent.class);
            AdvancementHolder survived = gate(helper, player, "{\"trigger\":\"remission:surge_survived\"}");
            SurgeController surges = new SurgeController(fight.level, new SurgeData(),
                    () -> new SurgeSettings(false, 24000, 600, 2400, 4, 0, 50, 0, 0),
                    () -> new SpreadSettings(400, 5, 2, 10, 40, 256, 32), new SurgeController.Players() {
                        @Override
                        public List<ServerPlayer> inLevel() {
                            return List.of(player);
                        }

                        @Override
                        public ServerPlayer byId(UUID id) {
                            return player.getUUID().equals(id) ? player : null;
                        }
                    });
            long now = fight.level.getGameTime();
            try {
                surges.startNow(fight.pos, 1, now);
                surges.startNow(fight.pos, 2, now + 1);
                helper.assertValueEqual(ended.size(), 1, "ended events after the same nidus surged again");
                helper.assertValueEqual(ended.getFirst().getReason(), SurgeEndedEvent.Reason.SUPERSEDED, "reason");
                helper.assertTrue(ended.getFirst().getSurvivors().isEmpty(), "survivors of a surge its own nidus replaced");
                helper.assertFalse(done(player, survived), "surge_survived for a surge its own nidus replaced");
                helper.assertValueEqual(surges.current().participants(), List.of(player.getUUID()), "caught players carried over");

                surges.startNow(other, 1, now + 2);
                helper.assertValueEqual(ended.get(1).getSurvivors(), List.of(player), "survivors of a surge another nidus replaced");
                helper.assertTrue(done(player, survived), "surge_survived for a surge another nidus replaced");
                helper.assertValueEqual(player.getStats().getValue(Stats.CUSTOM.get(RemissionStats.SURGES_SURVIVED.get())), 1,
                        "surges survived");
            } finally {
                surges.stop(now + 3);
                fight.level.removeBlock(other, false);
            }
        }
        helper.succeed();
    }

    // ---- tentacles (P4.19) -------------------------------------------------------------------------------------------

    private static List<NidusTentacle> hitboxesNear(Fight fight) {
        return fight.level.getEntitiesOfClass(NidusTentacle.class, new AABB(fight.pos).inflate(4.0));
    }

    /**
     * A nidus keeps six tentacle hitboxes at their rest places, one per tentacle, never more however often it ticks;
     * their type never saves, a stray one discards itself, and none are left once the block is removed.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void tentacleHitboxesFollowTheirNidus(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            NidusBlockEntity nidus = fight.nidus();
            long now = fight.level.getGameTime();
            nidus.tick(fight.level, SETTINGS, now);
            nidus.tick(fight.level, SETTINGS, now + 1);
            helper.assertValueEqual(nidus.hitboxes().size(), NidusTentacles.COUNT, "hitboxes kept");
            helper.assertValueEqual(hitboxesNear(fight).size(), NidusTentacles.COUNT, "hitboxes in the level");
            for (NidusTentacle hitbox : nidus.hitboxes()) {
                Vec3 centre = NidusTentacles.hitboxCentre(hitbox.tentacle(), 0.0F);
                Vec3 expected = new Vec3(fight.pos.getX() + 0.5 + centre.x, fight.pos.getY() + centre.y, fight.pos.getZ() + 0.5 + centre.z);
                helper.assertTrue(hitbox.getBoundingBox().getCenter().distanceTo(expected) < 0.01,
                        "hitbox " + hitbox.tentacle() + " at " + hitbox.getBoundingBox().getCenter() + ", expected " + expected);
            }
            helper.assertFalse(RemissionEntityTypes.NIDUS_TENTACLE.get().canSerialize(), "tentacle hitboxes save");

            // One lost (killed, or a chunk's entities unloaded) comes back once, at the next tick.
            nidus.hitboxes().getFirst().discard();
            nidus.tick(fight.level, SETTINGS, now + 2);
            helper.assertValueEqual(hitboxesNear(fight).size(), NidusTentacles.COUNT, "hitboxes after one was lost");

            NidusTentacle stray = RemissionEntityTypes.NIDUS_TENTACLE.get().create(fight.level);
            stray.moveTo(Vec3.atCenterOf(fight.pos).add(1.0, 0.0, 0.0));
            fight.level.addFreshEntity(stray);
            stray.tick();
            helper.assertTrue(stray.isRemoved(), "a hitbox no nidus owns stayed");

            fight.level.removeBlock(fight.pos, false);
            helper.assertTrue(hitboxesNear(fight).isEmpty(), "hitboxes left after the nidus was removed");
        }
        helper.succeed();
    }

    /**
     * A player's attack on a tentacle is a strike at the tentacle factor, never the weapon's hit: it shares the strike
     * cooldown, names the tentacle in the hit event and has the hitbox as the damage's direct entity. An arrow is
     * projectile damage at the projectile and tentacle factors; an explosion counts once, through the nidus.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void tentacleHitsDamageTheNidus(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(2);
            ServerPlayer archer = fight.player(-3); // in range from the start, so the fight's vitality never rescales
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            player.setItemInHand(InteractionHand.MAIN_HAND, sword);
            sword.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) ->
                    player.getAttribute(attribute).addOrReplacePermanentModifier(modifier));
            setAttackTicker(player, 100);
            NidusBlockEntity nidus = fight.nidus();
            nidus.tick(fight.level, SETTINGS, fight.level.getGameTime());
            List<NidusDamagedEvent> seen = new ArrayList<>();
            fight.listen(NidusDamagedEvent.class, event -> {
                if (event.getPos().equals(fight.pos)) {
                    seen.add(event);
                }
            });
            NidusTentacle hitbox = nidus.hitboxes().get(2);
            float expected = NidusEncounters.strikeDamage(player) * (float) SETTINGS.tentacleDamageFactor();
            player.attack(hitbox);
            helper.assertTrue(Math.abs(nidus.damageTaken() - expected) < EPSILON,
                    "tentacle strike damage " + nidus.damageTaken() + ", expected " + expected);
            helper.assertValueEqual(seen.getFirst().getKind(), NidusDamagedEvent.Kind.STRIKE, "tentacle strike kind");
            helper.assertValueEqual(seen.getFirst().getDirectEntity(), hitbox, "tentacle strike direct entity");
            helper.assertTrue(blockEvents(fight.level).contains(new BlockEventData(fight.pos, RemissionBlocks.NIDUS.get(),
                    NidusBlockEntity.EVENT_HIT, hitbox.tentacle() + 1)), "hit event naming the tentacle");
            helper.assertValueEqual(sword.getDamageValue(), 1, "sword wear");

            setAttackTicker(player, 100);
            player.attack(nidus.hitboxes().get(3));
            helper.assertTrue(Math.abs(nidus.damageTaken() - expected) < EPSILON, "a second strike within the cooldown counted");

            Arrow arrow = new Arrow(EntityType.ARROW, fight.level);
            arrow.setOwner(archer);
            arrow.setPos(hitbox.position());
            arrow.setDeltaMovement(-2.5, 0.0, 0.0);
            float arrowDamage = (float) Math.ceil(2.5 * arrow.getBaseDamage()) * 0.5F * (float) SETTINGS.tentacleDamageFactor();
            helper.assertTrue(hitbox.hurt(fight.level.damageSources().arrow(arrow, archer), 9.0F), "the arrow did not hit");
            helper.assertTrue(Math.abs(nidus.damageTaken() - expected - arrowDamage) < EPSILON,
                    "damage after a tentacle arrow " + nidus.damageTaken() + ", expected " + (expected + arrowDamage));
            helper.assertValueEqual(seen.getLast().getKind(), NidusDamagedEvent.Kind.PROJECTILE, "tentacle arrow kind");

            float before = nidus.damageTaken();
            Vec3 blast = hitbox.getBoundingBox().getCenter();
            float blastDamage = NidusEncounters.explosionDamage(blast, 1.0F, fight.pos) * 0.25F;
            fight.level.explode(archer, blast.x, blast.y, blast.z, 1.0F, Level.ExplosionInteraction.NONE);
            helper.assertTrue(Math.abs(nidus.damageTaken() - before - blastDamage) < EPSILON,
                    "explosion by a tentacle " + (nidus.damageTaken() - before) + ", expected once " + blastDamage);
            helper.assertFalse(hitbox.isRemoved(), "an explosion removed a hitbox");
        }
        helper.succeed();
    }

    /**
     * Tentacles go to the nearest players first, each to the free tentacle resting closest to its bearing: players east
     * and west get the mid right and mid left tentacles; of seven players the furthest gets none; the same players
     * always get the same tentacles, whatever order they are listed in.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void tentaclesAreAssignedDeterministically(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            Vec3 centre = Vec3.atCenterOf(fight.pos);
            ServerPlayer east = fight.player(2);
            ServerPlayer west = fight.player(-2);
            List<NidusTentacles.Assignment> two = NidusTentacles.assign(centre, List.of(west, east));
            helper.assertValueEqual(tentacleOf(two, east), NidusTentacles.NAMES.indexOf("mid_right"), "east player's tentacle");
            helper.assertValueEqual(tentacleOf(two, west), NidusTentacles.NAMES.indexOf("mid_left"), "west player's tentacle");

            List<ServerPlayer> seven = sevenAround(fight);
            List<NidusTentacles.Assignment> assigned = NidusTentacles.assign(centre, seven);
            helper.assertValueEqual(assigned.size(), NidusTentacles.COUNT, "players assigned of seven");
            helper.assertValueEqual(assigned.stream().map(NidusTentacles.Assignment::tentacle).distinct().count(),
                    (long) NidusTentacles.COUNT, "distinct tentacles");
            helper.assertValueEqual(tentacleOf(assigned, seven.getLast()), -1, "the furthest of seven was assigned");
            List<ServerPlayer> reversed = new ArrayList<>(seven);
            Collections.reverse(reversed);
            List<NidusTentacles.Assignment> again = NidusTentacles.assign(centre, reversed);
            for (ServerPlayer player : seven) {
                helper.assertValueEqual(tentacleOf(again, player), tentacleOf(assigned, player), "tentacle on a second run");
            }
        }
        helper.succeed();
    }

    /** Seven players around the nidus within reach: six within 2 blocks and the last, furthest, 2.8 blocks out. */
    private static List<ServerPlayer> sevenAround(Fight fight) {
        List<ServerPlayer> players = new ArrayList<>();
        int[][] offsets = {{2, 0}, {-2, 0}, {0, -2}, {0, 2}, {1, -1}, {-1, 1}, {2, -2}};
        for (int[] offset : offsets) {
            ServerPlayer player = SurgeTests.player(fight.helper, fight.pos.offset(offset[0], 0, offset[1]));
            fight.players.add(player);
            NidusEncounters.addTestPlayer(fight.pos, player);
            players.add(player);
        }
        return players;
    }

    private static int tentacleOf(List<NidusTentacles.Assignment> assignments, ServerPlayer player) {
        return assignments.stream().filter(a -> a.player() == player).mapToInt(NidusTentacles.Assignment::tentacle)
                .findFirst().orElse(-1);
    }

    /**
     * A lash strikes each assigned player at contact time and no one else: of seven players in reach the furthest is
     * spared, each struck player is knocked away from the nidus, a player who steps out of reach before contact dodges
     * it, and the lashing tentacle's hitbox lies on the ground out along the lash.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void lashesLandOnTheirTargetsAtContact(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            List<ServerPlayer> seven = sevenAround(fight);
            NidusBlockEntity nidus = fight.nidus();
            long now = fight.level.getGameTime();
            fight.strike(seven.getFirst(), 1.0F, now);
            nidus.lash(fight.level, SETTINGS, now);
            ServerPlayer dodger = seven.get(3); // due south
            dodger.moveTo(dodger.getX(), dodger.getY(), dodger.getZ() + 3.0);
            nidus.tick(fight.level, SETTINGS, now + NidusTentacles.LASH_CONTACT_TICKS - 1);
            for (ServerPlayer player : seven) {
                helper.assertValueEqual(ViralLoad.get(player), 0.0F, "viral load before contact");
            }
            nidus.tick(fight.level, SETTINGS, now + NidusTentacles.LASH_CONTACT_TICKS);
            Vec3 centre = Vec3.atCenterOf(fight.pos);
            // While a lash is down its tentacle's hitbox lies on the ground out along the lash, so a dodger can hit back.
            NidusTentacle east = nidus.hitboxes().get(NidusTentacles.NAMES.indexOf("mid_right"));
            helper.assertTrue(Math.abs(east.getX() - centre.x - NidusTentacles.LASH_HITBOX_DISTANCE) < 0.05
                    && Math.abs(east.getY() - fight.pos.getY()) < 0.05, "grounded hitbox at " + east.position());
            for (int i = 0; i < seven.size(); i++) {
                ServerPlayer player = seven.get(i);
                boolean struck = i < NidusTentacles.COUNT && player != dodger;
                helper.assertValueEqual(ViralLoad.get(player), struck ? (float) SETTINGS.lashViralLoad() : 0.0F, "viral load of player " + i);
                if (struck) {
                    Vec3 away = player.position().subtract(centre).multiply(1.0, 0.0, 1.0);
                    helper.assertTrue(player.getDeltaMovement().multiply(1.0, 0.0, 1.0).dot(away) > 0,
                            "player " + i + " knocked toward the nidus: " + player.getDeltaMovement());
                }
            }
        }
        helper.succeed();
    }

    /**
     * With {@code lashIntervalTicks} set, a nidus lashes a player in reach on its own, fought or not: a second after the
     * player comes within reach, then every interval; at 0 (the batch's settings) it lashes only at thresholds.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void periodicLashesFollowTheInterval(GameTestHelper helper) {
        NidusSettings periodic = new NidusSettings(100.0, 1000, 1.0, 16, 0.5, 3, 0.0, 2.0, 200, 400, 0.1, List.of(75, 50, 25),
                0.5, 0.25, 3.0, 15.0, 1.0, 10, 40, 0.5);
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(2);
            NidusBlockEntity nidus = fight.nidus();
            long now = fight.level.getGameTime();
            BlockEventData event = new BlockEventData(fight.pos, RemissionBlocks.NIDUS.get(), NidusBlockEntity.EVENT_LASH,
                    NidusBlockEntity.lashParam(NidusTentacles.NAMES.indexOf("mid_right"), 90.0F));
            for (int t = 0; t <= 60; t++) {
                nidus.tick(fight.level, SETTINGS, now + t);
            }
            helper.assertFalse(blockEvents(fight.level).contains(event), "a lash with the interval off");
            long start = now + 100;
            nidus.tick(fight.level, periodic, start);
            nidus.tick(fight.level, periodic, start + NidusBlockEntity.FIRST_LASH_TICKS - 1);
            helper.assertFalse(blockEvents(fight.level).contains(event), "a lash before the first lash's delay");
            nidus.tick(fight.level, periodic, start + NidusBlockEntity.FIRST_LASH_TICKS);
            helper.assertTrue(blockEvents(fight.level).contains(event), "no lash a second after the player came within reach");
            helper.assertFalse(nidus.fighting(), "a periodic lash started a fight");
            nidus.tick(fight.level, periodic, start + NidusBlockEntity.FIRST_LASH_TICKS + NidusTentacles.LASH_CONTACT_TICKS);
            helper.assertValueEqual(ViralLoad.get(player), (float) periodic.lashViralLoad(), "viral load from the periodic lash");
            blockEvents(fight.level).clear();
            long next = start + NidusBlockEntity.FIRST_LASH_TICKS + periodic.lashIntervalTicks();
            nidus.tick(fight.level, periodic, next - 1);
            helper.assertFalse(blockEvents(fight.level).contains(event), "a lash before the interval");
            nidus.tick(fight.level, periodic, next);
            helper.assertTrue(blockEvents(fight.level).contains(event), "no lash at the interval");
        }
        helper.succeed();
    }

    /**
     * Standing on the nidus is never safe: a player on its crown for {@link NidusBlockEntity#CROWN_TICKS} is lashed at
     * once, whatever the lash interval (here off), and the lash throws them upward off it; a player beside it is spared.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void playersOnTheCrownAreThrownOff(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer beside = fight.player(2);
            ServerPlayer crown = fight.player(0);
            crown.teleportTo(fight.pos.getX() + 0.5, fight.pos.getY() + 1.0, fight.pos.getZ() + 0.5);
            NidusBlockEntity nidus = fight.nidus();
            helper.assertTrue(nidus.onCrown(crown), "player on top of the block not on the crown");
            helper.assertFalse(nidus.onCrown(beside), "player beside the block on the crown");
            long now = fight.level.getGameTime();
            for (int t = 0; t < NidusBlockEntity.CROWN_TICKS; t++) {
                nidus.tick(fight.level, SETTINGS, now + t);
            }
            helper.assertTrue(blockEvents(fight.level).stream().noneMatch(e -> e.pos().equals(fight.pos) && e.paramA() == NidusBlockEntity.EVENT_LASH),
                    "a lash before the crown's grace ran out");
            long lash = now + NidusBlockEntity.CROWN_TICKS;
            nidus.tick(fight.level, SETTINGS, lash);
            helper.assertValueEqual(blockEvents(fight.level).stream().filter(e -> e.pos().equals(fight.pos) && e.paramA() == NidusBlockEntity.EVENT_LASH).count(),
                    1L, "lashes at the player on the crown");
            for (long t = lash + 1; t <= lash + NidusTentacles.LASH_CONTACT_TICKS; t++) {
                nidus.tick(fight.level, SETTINGS, t);
            }
            helper.assertValueEqual(ViralLoad.get(crown), (float) SETTINGS.lashViralLoad(), "viral load on the crown");
            helper.assertValueEqual(ViralLoad.get(beside), 0.0F, "viral load beside the nidus");
            helper.assertTrue(crown.getDeltaMovement().y >= NidusBlockEntity.CROWN_THROW, "lash did not throw the player up");
        }
        helper.succeed();
    }

    // ---- appearance -------------------------------------------------------------------------------------------------

    /**
     * What clients draw: the health stage follows the quarters of vitality lost (healthy above 75%, lightly wounded above
     * 50%, badly wounded above 25%, near death at or below), each damage queues the hit block event, a surge from the
     * nidus enrages it at once and its end calms it, the tick re-checks against the surge controller, and the update tag
     * carries the enraged flag without it being saved.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void appearanceFollowsFightAndSurges(GameTestHelper helper) {
        try (Fight fight = new Fight(helper)) {
            ServerPlayer player = fight.player(1);
            NidusBlockEntity nidus = fight.nidus();
            long now = fight.level.getGameTime();
            float max = (float) SETTINGS.vitality();
            helper.assertValueEqual(nidus.healthStage(), 0, "stage before the fight");
            helper.assertFalse(nidus.enraged(), "enraged before the fight");

            fight.strike(player, max * 0.2F, now);
            helper.assertValueEqual(nidus.healthStage(), 0, "stage at 80% vitality");
            helper.assertTrue(blockEvents(fight.level).contains(
                    new BlockEventData(fight.pos, RemissionBlocks.NIDUS.get(), NidusBlockEntity.EVENT_HIT, 0)), "hit event queued");
            helper.assertTrue(nidus.triggerEvent(NidusBlockEntity.EVENT_HIT, 0), "hit event not sent on to clients");

            fight.strike(player, max * 0.05F, now);
            helper.assertValueEqual(nidus.healthStage(), 1, "stage at 75% vitality");
            helper.assertTrue(nidus.enraged(), "not enraged by its own threshold surge");
            helper.assertTrue(nidus.getUpdateTag(fight.level.registryAccess()).getBoolean("enraged"), "update tag");
            NidusBlockEntity client = new NidusBlockEntity(fight.pos, fight.level.getBlockState(fight.pos));
            client.handleUpdateTag(nidus.getUpdateTag(fight.level.registryAccess()), fight.level.registryAccess());
            helper.assertTrue(client.enraged(), "enraged lost in the update tag");
            helper.assertFalse(nidus.saveWithoutMetadata(fight.level.registryAccess()).contains("enraged"), "enraged saved");

            fight.strike(player, max * 0.25F, now);
            helper.assertValueEqual(nidus.healthStage(), 2, "stage at 50% vitality");
            fight.strike(player, max * 0.25F, now);
            helper.assertValueEqual(nidus.healthStage(), 3, "stage at 25% vitality");

            Surges.controller(fight.level).stop(now);
            helper.assertFalse(nidus.enraged(), "still enraged after its surge stopped");
            nidus.setEnraged(true);
            long check = now + NidusBlockEntity.CHECK_TICKS - Math.floorMod(now, NidusBlockEntity.CHECK_TICKS);
            nidus.tick(fight.level, SETTINGS, check);
            helper.assertFalse(nidus.enraged(), "the tick did not calm a nidus with no surge");
        }
        helper.succeed();
    }

    private static Collection<BlockEventData> blockEvents(ServerLevel level) {
        return ObfuscationReflectionHelper.getPrivateValue(ServerLevel.class, level, "blockEvents");
    }

    private static int attackTicker(ServerPlayer player) {
        return ObfuscationReflectionHelper.getPrivateValue(LivingEntity.class, player, "attackStrengthTicker");
    }

    private static void setAttackTicker(ServerPlayer player, int ticks) {
        ObfuscationReflectionHelper.setPrivateValue(LivingEntity.class, player, ticks, "attackStrengthTicker");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player) {
        return gate(helper, player, "{\"trigger\":\"remission:nidus_destroyed\"}");
    }

    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player, String json) {
        Criterion<?> criterion = Criterion.CODEC.parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString(json)).getOrThrow();
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
