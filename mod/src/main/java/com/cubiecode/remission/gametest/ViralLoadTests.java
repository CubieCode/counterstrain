package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.ViralLoadChangedEvent;
import com.cubiecode.remission.infection.Contact;
import com.cubiecode.remission.network.RemissionNetwork;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.surge.MiasmaCloud;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadSettings;
import com.cubiecode.remission.viralload.ViralLoadState;
import com.cubiecode.remission.world.ChunkCorruption;
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
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Viral load (P5.1): gain from festering land, water in festering land (swimming and drinking) and miasma clouds, and
 * from contact with infectious blocks (P5.2), never counted again for festering water; the
 * altitude falloff, on and off, and full gain underground; decay; debuffs and damage; the event, trigger, query, save
 * and death reset. Each test runs alone in its own batch with players that are never added to the level (so the
 * level's own updates never touch them), drives {@link ViralLoad#update} itself with explicit settings, and works in
 * its own chunk, whose land and blocks it restores afterwards.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ViralLoadTests {
    private static final String BATCH = "viral_load";
    private static final int TICKS = ViralLoadSettings.INTERVAL_TICKS;
    private static final float EPSILON = 1.0E-4F;
    private static final List<ViralLoadSettings.Debuff> DEBUFFS = List.of(
            ViralLoadSettings.Debuff.parse("25 minecraft:hunger 0"),
            ViralLoadSettings.Debuff.parse("50 minecraft:weakness 0"),
            ViralLoadSettings.Debuff.parse("50 minecraft:mining_fatigue 0"),
            ViralLoadSettings.Debuff.parse("75 minecraft:slowness 0"),
            ViralLoadSettings.Debuff.parse("75 minecraft:hunger 1"));
    /** The shipped defaults, spelled out so a config change does not move the expected numbers. */
    private static final ViralLoadSettings SETTINGS = settings(true, 5.0);

    private ViralLoadTests() {
    }

    // ---- gain -------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_land")
    public static void landGainByStage(GameTestHelper helper) {
        inOwnChunk(helper, 1, land -> {
            ServerPlayer player = onGround(helper);
            List<ViralLoadChangedEvent> events = capture(() -> update(player, 3));
            assertLoad(helper, player, 3 * gain(1.0), "after a minute's twentieth at stage 1");
            helper.assertValueEqual(ViralLoad.state(player).trend(), ViralLoadState.Trend.RISING, "trend at stage 1");
            helper.assertValueEqual(events.size(), 3, "events for three rising updates");
            helper.assertValueEqual(events.getFirst().getCause(), ViralLoadChangedEvent.Cause.EXPOSURE, "event cause");
            helper.assertTrue(events.getFirst().getEntity() == player && events.getFirst().isRising(), "event player and direction");

            float before = ViralLoad.get(player);
            setStage(land.chunk(), 4);
            update(player, 1);
            assertLoad(helper, player, before + gain(12.0), "one update at stage 4");

            helper.assertValueEqual(SETTINGS.landGainAt(6), 12.0, "stages past the list use its last entry");
            helper.assertValueEqual(SETTINGS.landGainAt(0), 0.0, "clean land");

            ViralLoad.set(player, 99.9F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            update(player, 5);
            assertLoad(helper, player, ViralLoadState.MAX, "capped at the maximum");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_swimming")
    public static void swimmingInFesteringWater(GameTestHelper helper) {
        inOwnChunk(helper, 1, land -> {
            ServerLevel level = helper.getLevel();
            BlockPos feet = spot(helper);
            land.place(level, feet, Blocks.WATER.defaultBlockState());
            land.place(level, feet.above(), Blocks.WATER.defaultBlockState());
            ServerPlayer player = SurgeTests.player(helper, feet);
            helper.assertTrue(ViralLoad.inWater(level, player), "player is not in the water");
            helper.assertTrue(ViralLoad.heightAboveGround(level, player) <= 0, "water surface counts as ground");
            update(player, 1);
            assertLoad(helper, player, gain(1.0 + 20.0), "land plus water gain");

            setStage(land.chunk(), 0);
            ViralLoad.set(player, 0.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            update(player, 1);
            assertLoad(helper, player, 0.0F, "water in clean land");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_drinking")
    public static void drinkingWaterInFesteringLand(GameTestHelper helper) {
        inOwnChunk(helper, 1, land -> {
            ServerPlayer player = onGround(helper);
            float dose = (float) ViralLoadSettings.fromConfig().drinkDose();
            helper.assertTrue(dose > 0, "drink dose turned off in the test config");
            List<ViralLoadChangedEvent> events = capture(() -> drink(player, PotionContents.createItemStack(Items.POTION, Potions.WATER)));
            assertLoad(helper, player, dose, "after a water bottle");
            helper.assertValueEqual(events.size(), 1, "events for one drink");
            helper.assertValueEqual(events.getFirst().getCause(), ViralLoadChangedEvent.Cause.DRINK, "drink cause");

            drink(player, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
            drink(player, new ItemStack(Items.MILK_BUCKET));
            assertLoad(helper, player, dose, "other drinks add nothing");

            player.setGameMode(GameType.CREATIVE);
            drink(player, PotionContents.createItemStack(Items.POTION, Potions.WATER));
            assertLoad(helper, player, dose, "creative players add nothing");
            player.setGameMode(GameType.SURVIVAL);

            setStage(land.chunk(), 0);
            drink(player, PotionContents.createItemStack(Items.POTION, Potions.WATER));
            assertLoad(helper, player, dose, "water drunk in clean land");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_clouds")
    public static void miasmaCloudsAddViralLoad(GameTestHelper helper) {
        // Clean land: clouds count wherever they are, and nothing else adds viral load.
        inOwnChunk(helper, 0, land -> {
            ServerLevel level = helper.getLevel();
            BlockPos at = spot(helper).above(30);
            ServerPlayer player = SurgeTests.player(helper, at);
            List<MiasmaCloud> clouds = new ArrayList<>();
            try {
                MiasmaCloud near = cloud(level, at.getX() + 0.5, at.getY() - 1.0, at.getZ() + 0.5, 4.0F, clouds);
                update(player, 1);
                assertLoad(helper, player, gain(30.0), "inside a cloud");
                near.discard();

                // A wide cloud whose centre is more than a section away still covers the player.
                cloud(level, at.getX() + 0.5 + 14.0, at.getY() - 1.0, at.getZ() + 0.5, 15.0F, clouds);
                update(player, 1);
                assertLoad(helper, player, 2 * gain(30.0), "inside a wide cloud centred far off");

                player.moveTo(at.getX() + 0.5, at.getY() + MiasmaCloud.HEIGHT + 1.0, at.getZ() + 0.5);
                update(player, 1);
                helper.assertValueEqual(ViralLoad.state(player).trend(), ViralLoadState.Trend.FALLING, "trend above the cloud");
                assertLoad(helper, player, 2 * gain(30.0) - gain(5.0), "above the cloud: decay");
            } finally {
                clouds.forEach(MiasmaCloud::discard);
            }
            helper.succeed();
        });
    }

    // ---- contact (P5.2) -----------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_contact")
    public static void contactWithInfectiousBlocks(GameTestHelper helper) {
        // Clean land, so only contact adds viral load: on, in and against an infectious block, wherever it is.
        inOwnChunk(helper, 0, land -> {
            ServerLevel level = helper.getLevel();
            BlockPos feet = spot(helper);
            ServerPlayer player = SurgeTests.player(helper, feet);
            land.place(level, feet.below(), RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            helper.assertValueEqual(Contact.of(player), Contact.Kind.BLOCK, "contact standing on festering grass");
            update(player, 1);
            assertLoad(helper, player, gain(10.0), "standing on festering grass");

            land.place(level, feet.below(), Blocks.STONE.defaultBlockState());
            land.place(level, feet, RemissionBlocks.FESTERING_VEIN.get().defaultBlockState()
                    .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
            helper.assertValueEqual(Contact.of(player), Contact.Kind.BLOCK, "contact inside a vein");
            update(player, 1);
            assertLoad(helper, player, 2 * gain(10.0), "inside a festering vein");

            land.place(level, feet, Blocks.AIR.defaultBlockState());
            land.place(level, feet.east(), RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            player.moveTo(feet.getX() + 0.7, feet.getY(), feet.getZ() + 0.5);
            helper.assertValueEqual(Contact.of(player), Contact.Kind.BLOCK, "contact against festered stone");
            update(player, 1);
            assertLoad(helper, player, 3 * gain(10.0), "pressed against festered stone");

            player.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            helper.assertValueEqual(Contact.of(player), Contact.Kind.NONE, "contact a step away from the stone");
            update(player, 1);
            assertLoad(helper, player, 3 * gain(10.0) - gain(5.0), "a step away: decay");

            // Turned off, the same contact adds nothing and viral load decays.
            player.moveTo(feet.getX() + 0.7, feet.getY(), feet.getZ() + 0.5);
            float before = ViralLoad.get(player);
            ViralLoad.update(player, TICKS, withContact(false));
            assertLoad(helper, player, before - gain(5.0), "contact turned off");
            helper.assertValueEqual(ViralLoad.gainPerMinute(player, withContact(false)), 0.0, "gain with contact off");
            helper.assertValueEqual(ViralLoad.gainPerMinute(player, SETTINGS), 10.0, "gain with contact on");

            // In festering land, contact adds to land gain.
            setStage(land.chunk(), 4);
            helper.assertValueEqual(ViralLoad.gainPerMinute(player, SETTINGS), 12.0 + 10.0, "land plus contact at stage 4");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_contact_water")
    public static void festeringWaterIsNotContact(GameTestHelper helper) {
        inOwnChunk(helper, 2, land -> {
            ServerLevel level = helper.getLevel();
            BlockPos feet = spot(helper);
            land.place(level, feet.below(), Blocks.STONE.defaultBlockState());
            land.place(level, feet, Blocks.WATER.defaultBlockState());
            land.place(level, feet.above(), Blocks.WATER.defaultBlockState());
            ServerPlayer player = SurgeTests.player(helper, feet);
            helper.assertValueEqual(Contact.of(player), Contact.Kind.WATER, "contact in festering water");
            helper.assertValueEqual(ViralLoad.gainPerMinute(player, SETTINGS), 3.0 + 20.0, "land and water gain only");
            helper.assertValueEqual(ViralLoad.gainPerMinute(player, SETTINGS), ViralLoad.gainPerMinute(player, withContact(false)),
                    "contact on changes festering water gain");
            update(player, 1);
            assertLoad(helper, player, gain(3.0 + 20.0), "one update swimming in festering water");
            helper.succeed();
        });
    }

    // ---- altitude --------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_altitude")
    public static void altitudeFalloffOnAndOff(GameTestHelper helper) {
        inOwnChunk(helper, 4, land -> {
            ServerLevel level = helper.getLevel();
            ServerPlayer player = onGround(helper);
            double ground = player.getY();
            ViralLoadSettings off = settings(false, 5.0);
            double[][] cases = {{0, 1.0}, {4, 1.0}, {22, 0.55}, {40, 0.1}, {80, 0.1}};
            for (double[] c : cases) {
                player.moveTo(player.getX(), ground + c[0], player.getZ());
                helper.assertTrue(Math.abs(ViralLoad.heightAboveGround(level, player) - c[0]) < 1.0E-6, "height " + c[0]);
                assertClose(helper, ViralLoad.gainPerMinute(player, SETTINGS), 12.0 * c[1], "falloff on at height " + c[0]);
                assertClose(helper, ViralLoad.gainPerMinute(player, off), 12.0, "falloff off at height " + c[0]);
            }

            player.moveTo(player.getX(), ground + 22, player.getZ());
            update(player, 1);
            assertLoad(helper, player, gain(12.0 * 0.55), "an update halfway up the falloff");

            assertClose(helper, ViralLoadSettings.FalloffCurve.LINEAR.remaining(0.25), 0.75, "linear");
            assertClose(helper, ViralLoadSettings.FalloffCurve.QUADRATIC.remaining(0.25), 0.5625, "quadratic");
            assertClose(helper, ViralLoadSettings.FalloffCurve.SMOOTH.remaining(0.25), 0.84375, "smooth");
            assertClose(helper, ViralLoadSettings.FalloffCurve.SMOOTH.remaining(1.5), 0.0, "past the end");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_underground")
    public static void fullGainUnderground(GameTestHelper helper) {
        inOwnChunk(helper, 4, land -> {
            ServerLevel level = helper.getLevel();
            // A falloff that leaves nothing a block above the ground, so any reduction would show.
            ViralLoadSettings steep = new ViralLoadSettings(true, List.of(1.0, 3.0, 6.0, 12.0), 20.0, 10.0, 30.0, 5.0,
                    true, 0.0, 1.0, 0.0, ViralLoadSettings.FalloffCurve.LINEAR, DEBUFFS, 100.0, 1.0, true, 10.0);
            BlockPos cave = cave(helper, land);
            ServerPlayer player = SurgeTests.player(helper, cave);
            helper.assertFalse(level.canSeeSky(cave), "cave pocket sees the sky");
            helper.assertTrue(ViralLoad.heightAboveGround(level, player) < 0, "cave is below the ground");
            assertClose(helper, ViralLoad.gainPerMinute(player, steep), 12.0, "gain in a cave");

            // Under a roof high above the ground counts as under the ground too.
            BlockPos high = spot(helper).above(20);
            land.place(level, high.above(3), Blocks.STONE.defaultBlockState());
            player.moveTo(high.getX() + 0.5, high.getY(), high.getZ() + 0.5);
            assertClose(helper, ViralLoad.gainPerMinute(player, steep), 12.0, "gain under a roof");
            land.place(level, high.above(3), Blocks.AIR.defaultBlockState());
            assertClose(helper, ViralLoad.gainPerMinute(player, steep), 0.0, "gain in the open at the same height");
            helper.succeed();
        });
    }

    // ---- decay, debuffs and the rest -------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_decay")
    public static void decayOutsideFesteringLand(GameTestHelper helper) {
        inOwnChunk(helper, 0, land -> {
            ServerPlayer player = onGround(helper);
            ViralLoad.set(player, 1.0F, ViralLoadState.Trend.RISING, ViralLoadChangedEvent.Cause.OTHER);
            List<ViralLoadChangedEvent> events = capture(() -> update(player, 2));
            assertLoad(helper, player, 1.0F - 2 * gain(5.0), "after two updates of decay");
            helper.assertValueEqual(ViralLoad.state(player).trend(), ViralLoadState.Trend.FALLING, "trend while decaying");
            helper.assertValueEqual(events.size(), 2, "decay events");
            helper.assertValueEqual(events.getFirst().getCause(), ViralLoadChangedEvent.Cause.DECAY, "decay cause");

            update(player, 20);
            assertLoad(helper, player, 0.0F, "decayed to 0");
            helper.assertValueEqual(ViralLoad.state(player), ViralLoadState.NONE, "state at 0");
            helper.assertFalse(player.hasData(RemissionAttachments.VIRAL_LOAD), "attachment kept at 0");

            ViralLoadSettings disabled = new ViralLoadSettings(false, SETTINGS.landGain(), 20.0, 10.0, 30.0, 5.0, true,
                    4.0, 40.0, 0.1, ViralLoadSettings.FalloffCurve.LINEAR, DEBUFFS, 100.0, 1.0, true, 10.0);
            ViralLoad.set(player, 40.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            ViralLoad.update(player, TICKS, disabled);
            assertLoad(helper, player, 40.0F, "frozen while disabled");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_debuffs")
    public static void debuffsEscalateAndDamage(GameTestHelper helper) {
        inOwnChunk(helper, 0, land -> {
            ServerPlayer player = onGround(helper);
            ObfuscationReflectionHelper.setPrivateValue(ServerPlayer.class, player, 0, "spawnInvulnerableTime");
            ViralLoadSettings noDecay = settings(true, 0.0);
            ViralLoad.set(player, 20.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            ViralLoad.update(player, TICKS, noDecay);
            helper.assertTrue(player.getActiveEffects().isEmpty(), "debuffs below the first threshold");

            ViralLoad.set(player, 60.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            ViralLoad.update(player, TICKS, noDecay);
            assertEffect(helper, player, MobEffects.HUNGER, 0);
            assertEffect(helper, player, MobEffects.WEAKNESS, 0);
            assertEffect(helper, player, MobEffects.DIG_SLOWDOWN, 0);
            helper.assertFalse(player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "slowness below 75");
            MobEffectInstance hunger = player.getEffect(MobEffects.HUNGER);
            helper.assertTrue(hunger != null && hunger.isAmbient() && !hunger.isVisible() && hunger.getDuration() > 200,
                    "debuffs are ambient, without particles and long enough not to blink");

            ViralLoad.set(player, 80.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            ViralLoad.update(player, TICKS, noDecay);
            assertEffect(helper, player, MobEffects.HUNGER, 1);
            assertEffect(helper, player, MobEffects.MOVEMENT_SLOWDOWN, 0);
            helper.assertValueEqual(player.getHealth(), player.getMaxHealth(), "health below the damage threshold");

            ViralLoad.set(player, ViralLoadState.MAX, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            ViralLoad.update(player, TICKS, noDecay);
            helper.assertValueEqual(player.getHealth(), player.getMaxHealth() - 1.0F, "health after a second at 100");
            helper.assertTrue(player.getLastDamageSource() != null && player.getLastDamageSource().is(ViralLoad.DAMAGE_TYPE),
                    "damage type " + player.getLastDamageSource());

            player.removeAllEffects();
            player.setGameMode(GameType.CREATIVE);
            ViralLoad.update(player, TICKS, noDecay);
            helper.assertTrue(player.getActiveEffects().isEmpty(), "debuffs in creative");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_contract")
    public static void queryTriggerSaveAndDeath(GameTestHelper helper) {
        inOwnChunk(helper, 0, land -> {
            ServerPlayer player = onGround(helper);
            AdvancementHolder reached = gate(helper, player,
                    "{\"trigger\":\"remission:viral_load_changed\",\"conditions\":{\"viral_load\":{\"min\":50}}}");
            AdvancementHolder recovered = gate(helper, player,
                    "{\"trigger\":\"remission:viral_load_changed\",\"conditions\":{\"viral_load\":{\"max\":0}}}");
            ViralLoad.set(player, 30.0F, ViralLoadState.Trend.RISING, ViralLoadChangedEvent.Cause.OTHER);
            helper.assertFalse(done(player, reached), "gate at 30 of min 50");
            ViralLoad.set(player, 55.5F, ViralLoadState.Trend.RISING, ViralLoadChangedEvent.Cause.OTHER);
            helper.assertTrue(done(player, reached), "gate at 55.5 of min 50");
            assertClose(helper, CorruptionQueries.viralLoad(player), 55.5, "query");
            helper.assertValueEqual(CorruptionQueries.MAX_VIRAL_LOAD, 100, "query maximum");

            CompoundTag saved = player.saveWithoutId(new CompoundTag());
            ServerPlayer reloaded = SurgeTests.player(helper, spot(helper));
            reloaded.load(saved);
            helper.assertValueEqual(ViralLoad.state(reloaded), new ViralLoadState(55.5F, ViralLoadState.Trend.STEADY),
                    "state after save and load");

            ServerPlayer respawned = SurgeTests.player(helper, spot(helper));
            respawned.restoreFrom(player, false);
            assertLoad(helper, respawned, 0.0F, "viral load after death");

            ViralLoad.set(player, 0.0F, ViralLoadState.Trend.STEADY, ViralLoadChangedEvent.Cause.OTHER);
            helper.assertTrue(done(player, recovered), "gate back at 0");
            // 2 added the viral load payload; later payloads bump it further (3: P8.2's festering map).
            helper.assertTrue(Integer.parseInt(RemissionNetwork.PROTOCOL_VERSION) >= 2, "protocol version with the viral load payload");
            helper.succeed();
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static ViralLoadSettings settings(boolean falloff, double decay) {
        return new ViralLoadSettings(true, List.of(1.0, 3.0, 6.0, 12.0), 20.0, 10.0, 30.0, decay, falloff, 4.0, 40.0, 0.1,
                ViralLoadSettings.FalloffCurve.LINEAR, DEBUFFS, 100.0, 1.0, true, 10.0);
    }

    /** The shipped defaults with contact viral load on or off. */
    private static ViralLoadSettings withContact(boolean contact) {
        return new ViralLoadSettings(true, List.of(1.0, 3.0, 6.0, 12.0), 20.0, 10.0, 30.0, 5.0, true, 4.0, 40.0, 0.1,
                ViralLoadSettings.FalloffCurve.LINEAR, DEBUFFS, 100.0, 1.0, contact, 10.0);
    }

    /** Viral load one update adds at {@code perMinute}. */
    private static float gain(double perMinute) {
        return (float) (perMinute * TICKS / 1200.0);
    }

    private static void update(ServerPlayer player, int times) {
        for (int i = 0; i < times; i++) {
            ViralLoad.update(player, TICKS, SETTINGS);
        }
    }

    private static void drink(ServerPlayer player, ItemStack stack) {
        NeoForge.EVENT_BUS.post(new LivingEntityUseItemEvent.Finish(player, stack, 0, new ItemStack(Items.GLASS_BOTTLE)));
    }

    private static MiasmaCloud cloud(ServerLevel level, double x, double y, double z, float radius, List<MiasmaCloud> made) {
        MiasmaCloud cloud = MiasmaCloud.create(level, x, y, z, 0.0F, radius, 200);
        level.addFreshEntity(cloud);
        made.add(cloud);
        return cloud;
    }

    private static void assertLoad(GameTestHelper helper, ServerPlayer player, float expected, String what) {
        float actual = ViralLoad.get(player);
        helper.assertTrue(Math.abs(actual - expected) < EPSILON, what + ": expected viral load " + expected + ", got " + actual);
    }

    private static void assertClose(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < 1.0E-6, what + ": expected " + expected + ", got " + actual);
    }

    private static void assertEffect(GameTestHelper helper, ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
        MobEffectInstance instance = player.getEffect(effect);
        helper.assertTrue(instance != null && instance.getAmplifier() == amplifier,
                "expected " + effect.getRegisteredName() + " " + amplifier + ", got " + instance);
    }

    private static List<ViralLoadChangedEvent> capture(Runnable body) {
        List<ViralLoadChangedEvent> events = new ArrayList<>();
        Consumer<ViralLoadChangedEvent> listener = events::add;
        NeoForge.EVENT_BUS.addListener(ViralLoadChangedEvent.class, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events;
    }

    private static AdvancementHolder gate(GameTestHelper helper, ServerPlayer player, String json) {
        Criterion<?> criterion = Criterion.CODEC.parse(
                helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
        AdvancementHolder holder = Advancement.Builder.advancement().addCriterion("gate", criterion)
                .build(Remission.id("test/" + UUID.randomUUID()));
        listen(player, criterion, holder);
        return holder;
    }

    private static <T extends CriterionTriggerInstance> void listen(ServerPlayer player, Criterion<T> criterion, AdvancementHolder holder) {
        criterion.trigger().addPlayerListener(player.getAdvancements(),
                new CriterionTrigger.Listener<>(criterion.triggerInstance(), holder, "gate"));
    }

    private static boolean done(ServerPlayer player, AdvancementHolder holder) {
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    /** The test's chunk and the blocks to put back. */
    private record Land(LevelChunk chunk, List<Runnable> undo) {
        void place(ServerLevel level, BlockPos pos, BlockState state) {
            BlockPos at = pos.immutable();
            BlockState old = level.getBlockState(at);
            undo.add(() -> level.setBlockAndUpdate(at, old));
            level.setBlockAndUpdate(at, state);
        }
    }

    private static void inOwnChunk(GameTestHelper helper, int stage, Consumer<Land> body) {
        LevelChunk chunk = helper.getLevel().getChunkAt(spot(helper));
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        Land land = new Land(chunk, new ArrayList<>());
        setStage(chunk, stage);
        try {
            body.accept(land);
        } finally {
            land.undo().reversed().forEach(Runnable::run);
            if (before == null) {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            } else {
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, before);
            }
        }
    }

    private static BlockPos spot(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 1, 1));
    }

    /** A survival player standing on the ground (the top of the motion-blocking heightmap) in the spot's column. */
    private static ServerPlayer onGround(GameTestHelper helper) {
        BlockPos spot = spot(helper);
        int ground = helper.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ());
        return SurgeTests.player(helper, new BlockPos(spot.getX(), ground, spot.getZ()));
    }

    /** An air pocket inside a stone shell three blocks under {@link #spot}, in the same chunk. */
    private static BlockPos cave(GameTestHelper helper, Land land) {
        ServerLevel level = helper.getLevel();
        BlockPos pocket = spot(helper).below(4);
        for (BlockPos pos : BlockPos.betweenClosed(pocket.offset(-1, -1, -1), pocket.offset(1, 2, 1))) {
            BlockPos at = pos.immutable();
            boolean inside = at.getX() == pocket.getX() && at.getZ() == pocket.getZ() && at.getY() >= pocket.getY()
                    && at.getY() <= pocket.getY() + 1;
            land.place(level, at, inside ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
        return pocket;
    }

    /** Sets the chunk to the lowest Strain level of festering {@code stage} under the shipped thresholds; 0 clears it. */
    private static void setStage(LevelChunk chunk, int stage) {
        int[] levels = {0, 1, 25, 50, 75};
        if (stage == 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(levels[stage], 0L));
        }
    }
}
