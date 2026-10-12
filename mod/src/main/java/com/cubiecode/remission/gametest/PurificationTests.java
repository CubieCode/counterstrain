package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockPurifiedEvent;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.HostCleansedEvent;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.infection.ContactSettings;
import com.cubiecode.remission.infection.ContactTracker;
import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.purification.PurificationSettings;
import com.cubiecode.remission.purification.PylonIndex;
import com.cubiecode.remission.purification.RemissionZones;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesterMaps;
import com.cubiecode.remission.world.FesteringLand;
import com.cubiecode.remission.world.FesteringStages;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.SpreadEngine;
import com.cubiecode.remission.world.SpreadSettings;
import com.cubiecode.remission.world.VeinGrowth;
import com.cubiecode.remission.world.VeinSettings;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Purification (P5.2): a quarantine pylon's ward (radius, no spread, festering, veins or contact infection inside it,
 * festered blocks and veins cleansed, its land drained into remission), remission entry with its event, stat, trigger
 * and query, the handheld purifier, mendroot and the remission zone bonuses. Each test runs alone in its own batch in
 * its own row of chunks far east of the test grid (beyond {@link FesteringVeinTests}' chunks), force-loading only the
 * chunks it reads, and does its work within one tick; it removes its pylons, blocks and land afterwards.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PurificationTests {
    private static final String BATCH = "purification";
    /** Chunks east of the test's own chunk to the first test area. */
    private static final int FAR = 176;
    /** Chunks between test areas. */
    private static final int SPACING = 16;
    /** Bottom of each test's build; well above the gametest floor. */
    private static final int BASE_Y = 100;
    /** The shipped defaults, spelled out so a config change does not move the expected numbers (pylon radius is config). */
    private static final PurificationSettings SETTINGS = new PurificationSettings(200, 2, 5, 2, 1, 34, 3, 1, 600);

    private PurificationTests() {
    }

    // ---- pylon ward --------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_radius")
    public static void pylonWardsItsRadius(GameTestHelper helper) {
        inArea(helper, 0, area -> {
            helper.assertValueEqual(RemissionCommonConfig.PURIFICATION_PYLON_RADIUS.get(), 2, "pylon radius in the test config");
            ServerLevel level = helper.getLevel();
            LevelChunk centre = area.chunk(0, 0);
            LevelChunk edge = area.chunk(2, 0);
            LevelChunk corner = area.chunk(-2, 2);
            LevelChunk outside = area.chunk(3, 0);
            LevelChunk outsideCorner = area.chunk(3, -3);
            for (LevelChunk chunk : List.of(centre, edge, corner, outside, outsideCorner)) {
                setLevel(chunk, 60);
            }
            BlockPos pylon = area.pylon(0, 0);
            helper.assertTrue(PylonIndex.get(level).contains(pylon), "placed pylon is not indexed");
            for (LevelChunk chunk : List.of(centre, edge, corner)) {
                helper.assertTrue(Purification.isWarded(level, chunk.getPos()), "not warded: " + chunk.getPos());
            }
            helper.assertTrue(Purification.isWarded(level, area.pos(1, -1)), "an unloaded chunk inside the ward");
            helper.assertFalse(Purification.isWarded(level, outside.getPos()), "warded one chunk past the radius");
            helper.assertFalse(Purification.isWarded(level, outsideCorner.getPos()), "warded past the corner");

            int visited = Purification.engine(level).passNow(SETTINGS);
            // Chunks next to a force-loaded chunk are loaded too, so the pass visits more than the three read here.
            helper.assertTrue(visited >= 3 && visited <= 25, "loaded warded chunks a pass visits: " + visited);
            for (LevelChunk chunk : List.of(centre, edge, corner)) {
                helper.assertValueEqual(levelOf(chunk), 55, "warded chunk after one pass " + chunk.getPos());
            }
            helper.assertValueEqual(levelOf(outside), 60, "chunk outside the ward after a pass");

            // A second pylon with an overlapping ward does not drain its chunks twice.
            area.pylon(1, 0);
            helper.assertTrue(Purification.isWarded(level, outside.getPos()), "second pylon's ward does not reach one chunk further");
            Purification.engine(level).passNow(SETTINGS);
            helper.assertValueEqual(levelOf(centre), 50, "chunk in two wards after a pass");
            helper.assertValueEqual(levelOf(outside), 55, "chunk in the second ward only after a pass");

            area.removePylons();
            helper.assertFalse(PylonIndex.get(level).contains(pylon), "removed pylon still indexed");
            helper.assertFalse(Purification.isWarded(level, centre.getPos()), "warded after the pylon was removed");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_holds")
    public static void wardStopsSpreadFesteringAndVeins(GameTestHelper helper) {
        inArea(helper, 1, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk warded = area.chunk(2, 0);
            LevelChunk nidusChunk = area.chunk(3, 0);
            LevelChunk open = area.chunk(4, 0);
            LevelChunk wardedNidus = area.chunk(0, 0);
            setLevel(nidusChunk, 100);
            area.nidus(3, 0);
            area.nidus(0, 0);
            area.pylon(0, 0, 2, 2);
            helper.assertTrue(Purification.isWarded(level, warded.getPos()) && Purification.isWarded(level, wardedNidus.getPos())
                    && !Purification.isWarded(level, nidusChunk.getPos()) && !Purification.isWarded(level, open.getPos()), "ward layout");

            SpreadSettings spread = new SpreadSettings(400, 5, 2, 10, 40, 4096, 32);
            SpreadEngine engine = FesteringLand.newEngine(level, () -> spread);
            long pass = (level.getGameTime() / 400 + 1) * 400;
            engine.tick(pass);
            helper.assertTrue(levelOf(open) > 0, "unwarded neighbour of the nidus after a pass");
            helper.assertValueEqual(levelOf(warded), 0, "warded neighbour of the nidus after a pass");
            helper.assertValueEqual(levelOf(wardedNidus), 0, "warded nidus chunk grew");
            engine.catchUp(warded.getPos().toLong(), pass + 4000, spread);
            helper.assertValueEqual(levelOf(warded), 0, "warded chunk caught up");

            // Festering and veins: a stone floor in the warded chunk at stage 4 neither festers nor grows veins, while
            // the same floor in the unwarded chunk grows veins.
            setLevel(warded, 90);
            setLevel(open, 90);
            area.fill(2, 0, 2, BASE_Y, 2, 13, BASE_Y, 13, Blocks.STONE.defaultBlockState());
            area.fill(4, 0, 2, BASE_Y, 2, 13, BASE_Y, 13, Blocks.STONE.defaultBlockState());
            FesteringStages.queue(level, warded.getPos());
            FesteringStages.tick(level, 100_000);
            FesteringStages.tickCaves(level, 100_000);
            helper.assertValueEqual(FesteringStages.festeredStage(warded), 0, "warded chunk festered");
            helper.assertTrue(level.getBlockState(area.at(2, 0, 8, BASE_Y, 8)).is(Blocks.STONE), "warded stone festered");

            VeinGrowth veins = new VeinGrowth(level, () -> new VeinSettings(true, 2));
            veins.queue(warded.getPos());
            veins.tick(100_000);
            helper.assertValueEqual(veinsAbove(level, area, 2), 0, "veins grew in the warded chunk");
            veins.queue(open.getPos());
            veins.tick(100_000);
            helper.assertTrue(veinsAbove(level, area, 4) > 0, "no veins grew in the unwarded chunk");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_contact")
    public static void wardStopsContactInfection(GameTestHelper helper) {
        // In the test's own chunk, which is always entity-ticking; nothing else runs in this batch.
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(1, 0, 1));
        BlockPos pylon = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockState oldGround = level.getBlockState(ground);
        level.setBlock(ground, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(pylon, RemissionBlocks.QUARANTINE_PYLON.get().defaultBlockState(), Block.UPDATE_ALL);
        try {
            helper.assertTrue(Purification.isWarded(level, new ChunkPos(ground)), "test chunk not warded");
            Pig pig = pig(level, ground.above());
            ContactSettings settings = new ContactSettings(true, 0, 2, 10, 64, 32);
            ContactTracker tracker = new ContactTracker(level, () -> settings, () -> List.of(pig), mob -> Optional.empty());
            long base = 30_000_000L;
            for (long t = base; t <= base + 20; t++) {
                tracker.tick(t);
            }
            helper.assertFalse(Corruption.isHost(pig), "pig on festering grass inside a ward became a host");
            helper.assertValueEqual(ContactTracker.contact(pig), 0, "contact built up inside a ward");

            level.setBlock(pylon, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            for (long t = base + 21; t <= base + 40; t++) {
                tracker.tick(t);
            }
            helper.assertTrue(Corruption.isHost(pig), "pig on festering grass did not become a host once the ward was gone");
        } finally {
            level.setBlock(pylon, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(ground, oldGround, Block.UPDATE_CLIENTS);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_cleanse")
    public static void pylonCleansesItsWard(GameTestHelper helper) {
        inArea(helper, 3, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk warded = area.chunk(0, 0);
            LevelChunk outside = area.chunk(3, 0);
            setLevel(warded, 90);
            setLevel(outside, 90);
            BlockState log = RemissionBlocks.FESTERING_LOG.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
            BlockState leaves = RemissionBlocks.FESTERING_LEAVES.get().defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true).setValue(LeavesBlock.DISTANCE, 3);
            area.set(0, 0, 4, BASE_Y, 4, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            area.set(0, 0, 5, BASE_Y, 4, RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            area.set(0, 0, 6, BASE_Y, 4, log);
            area.set(0, 0, 7, BASE_Y, 4, leaves);
            area.set(0, 0, 8, BASE_Y, 4, RemissionBlocks.FESTERED_DEEPSLATE.get().defaultBlockState());
            area.set(0, 0, 5, BASE_Y, 5, RemissionBlocks.FESTERING_VEIN.get().defaultBlockState()
                    .setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true));
            area.set(3, 0, 4, BASE_Y, 4, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            warded.setData(RemissionAttachments.FESTERED_STAGE, 4);
            FesteringStages.flipBiomes(level, warded);
            FesteringStages.flipBiomes(level, outside);
            helper.assertTrue(isFester(level, area.at(0, 0, 4, BASE_Y, 4)), "warded biome did not flip");

            List<BlockPurifiedEvent> events = capture(BlockPurifiedEvent.class, () -> {
                area.pylon(0, 0, 12, 12);
                Purification.engine(level).tick(Integer.MAX_VALUE);
            });
            helper.assertValueEqual(stateAt(area, 0, 4, 4).getBlock(), Blocks.GRASS_BLOCK, "festering grass cleansed");
            helper.assertValueEqual(stateAt(area, 0, 5, 4).getBlock(), Blocks.STONE, "festered stone cleansed");
            helper.assertValueEqual(stateAt(area, 0, 6, 4), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,
                    Direction.Axis.X), "festering log cleansed with its axis");
            helper.assertValueEqual(stateAt(area, 0, 7, 4), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
                    .setValue(LeavesBlock.DISTANCE, 3), "festering leaves cleansed with persistence and distance");
            helper.assertValueEqual(stateAt(area, 0, 8, 4).getBlock(), Blocks.DEEPSLATE, "festered deepslate cleansed");
            helper.assertTrue(stateAt(area, 0, 5, 5).isAir(), "festering vein not cleared");
            helper.assertValueEqual(events.size(), 6, "block purified events");
            helper.assertTrue(events.stream().allMatch(e -> e.getCause() == PurificationCause.PYLON), "event causes");
            BlockPurifiedEvent grass = events.stream().filter(e -> e.getPos().equals(area.at(0, 0, 4, BASE_Y, 4))).findFirst().orElseThrow();
            helper.assertTrue(grass.getOldState().is(RemissionBlocks.FESTERING_GRASS.get()) && grass.getNewState().is(Blocks.GRASS_BLOCK),
                    "event states");
            helper.assertFalse(isFester(level, area.at(0, 0, 4, BASE_Y, 4)), "warded biome not restored");
            helper.assertValueEqual(FesteringStages.festeredStage(warded), 0, "festered stage kept after a cleanse");
            helper.assertValueEqual(levelOf(warded), 90, "a cleanse alone changed the Strain level");

            helper.assertTrue(stateAt(area, 3, 4, 4).is(RemissionBlocks.FESTERING_GRASS.get()), "outside the ward cleansed");
            helper.assertTrue(isFester(level, area.at(3, 0, 4, BASE_Y, 4)), "outside biome restored");
            helper.succeed();
        });
    }

    // ---- remission ---------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_remission")
    public static void wardedLandEntersRemission(GameTestHelper helper) {
        inArea(helper, 4, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk centre = area.chunk(0, 0);
            LevelChunk edge = area.chunk(2, 0);
            LevelChunk outside = area.chunk(3, 0);
            for (LevelChunk chunk : List.of(centre, edge, outside)) {
                setLevel(chunk, 10);
            }
            area.pylon(0, 0);
            ServerPlayer near = area.player(helper, area.at(0, 0, 8, BASE_Y + 1, 8));
            ServerPlayer far = area.player(helper, area.at(12, 0, 8, BASE_Y + 1, 8));
            AdvancementHolder own = gate(helper, near, "{\"trigger\": \"remission:region_cleansed\", \"conditions\": {\"radius\": 0, \"share\": {\"min\": 1.0}}}");
            AdvancementHolder most = gate(helper, near, "{\"trigger\": \"remission:region_cleansed\", \"conditions\": {\"radius\": 3, \"share\": {\"min\": 0.9}}}");
            AdvancementHolder two = gate(helper, near, "{\"trigger\": \"remission:region_cleansed\", \"conditions\": {\"radius\": 3, \"chunks\": {\"min\": 2}}}");
            AdvancementHolder farGate = gate(helper, far, "{\"trigger\": \"remission:region_cleansed\"}");

            List<ChunkStageChangedEvent> stages = new ArrayList<>();
            List<ChunkEnteredRemissionEvent> entered = capture(ChunkEnteredRemissionEvent.class, () -> stages.addAll(
                    capture(ChunkStageChangedEvent.class, () -> Purification.engine(level).passNow(SETTINGS))));
            helper.assertValueEqual(entered.size(), 0, "remission after the first pass");
            helper.assertValueEqual(levelOf(centre), 5, "level after one pass");
            helper.assertFalse(done(near, own), "region trigger before any remission");

            entered = capture(ChunkEnteredRemissionEvent.class, () -> stages.addAll(
                    capture(ChunkStageChangedEvent.class, () -> Purification.engine(level).passNow(SETTINGS))));
            helper.assertValueEqual(entered.size(), 2, "chunks entering remission");
            helper.assertTrue(entered.stream().allMatch(e -> e.getCause() == PurificationCause.PYLON && e.getPlayer().isEmpty()
                    && e.getLevel() == level), "remission event payload");
            helper.assertTrue(entered.stream().anyMatch(e -> e.getChunk().equals(centre.getPos())), "centre remission event");
            helper.assertTrue(stages.stream().anyMatch(e -> e.getChunk().equals(centre.getPos()) && e.getNewStage() == 0
                    && e.getNewLevel() == 0), "stage event to 0");
            helper.assertTrue(CorruptionQueries.isInRemission(level, centre.getPos()), "centre not in remission");
            helper.assertTrue(CorruptionQueries.isInRemission(level, edge.getPos()), "edge not in remission");
            helper.assertFalse(CorruptionQueries.isInRemission(level, outside.getPos()), "festering chunk in remission");
            helper.assertFalse(CorruptionQueries.isInRemission(level, area.pos(5, 5)), "unloaded chunk in remission");
            helper.assertFalse(centre.hasData(RemissionAttachments.CHUNK_CORRUPTION), "level attachment left at 0");
            helper.assertTrue(Purification.engine(level).busy(), "remission cleanse not queued");

            helper.assertValueEqual(stat(near), 2, "chunks cleansed for the nearby player");
            helper.assertValueEqual(stat(far), 0, "chunks cleansed for a player 12 chunks away");
            helper.assertTrue(done(near, own), "region_cleansed over the player's own chunk");
            helper.assertFalse(done(near, most), "region_cleansed share 0.9 with one festering chunk of three");
            helper.assertTrue(done(near, two), "region_cleansed with two chunks in remission");
            helper.assertFalse(done(far, farGate), "region_cleansed for a player out of range");

            // A drained chunk stays at 0; the Strain raising it again ends remission.
            Purification.engine(level).passNow(SETTINGS);
            helper.assertValueEqual(stat(near), 2, "a chunk already in remission counted again");
            FesteringLand.setChunkLevel(level, centre, 3);
            helper.assertFalse(CorruptionQueries.isInRemission(level, centre.getPos()), "festering chunk still in remission");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_catch_up")
    public static void remissionLimitsCatchUp(GameTestHelper helper) {
        inArea(helper, 5, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk nidusChunk = area.chunk(0, 0);
            LevelChunk purified = area.chunk(1, 0);
            setLevel(nidusChunk, 100);
            setLevel(purified, 1);
            long now = level.getGameTime();
            BlockPos nidus = area.at(0, 0, 8, BASE_Y, 8);
            NidusIndex.get(level).add(nidus, now - 40_000);
            try {
                Purification.lowerLevel(level, purified, 1, PurificationCause.OTHER, null);
                helper.assertTrue(CorruptionQueries.isInRemission(level, purified.getPos()), "purified chunk not in remission");
                SpreadSettings spread = new SpreadSettings(400, 5, 2, 10, 40, 4096, 32);
                SpreadEngine engine = FesteringLand.newEngine(level, () -> spread);
                engine.catchUp(purified.getPos().toLong(), now + 3 * 400, spread);
                helper.assertValueEqual(levelOf(purified), 6, "catch-up credits only the passes since remission");
                helper.assertFalse(CorruptionQueries.isInRemission(level, purified.getPos()), "spread did not end remission");
            } finally {
                NidusIndex.get(level).remove(nidus);
            }
            helper.succeed();
        });
    }

    // ---- tools -------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_purifier")
    public static void handheldPurifierCleanses(GameTestHelper helper) {
        inArea(helper, 6, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = area.chunk(0, 0);
            setLevel(chunk, 20);
            area.set(0, 0, 8, BASE_Y, 8, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            area.set(0, 0, 10, BASE_Y, 8, RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            area.set(0, 0, 11, BASE_Y, 8, RemissionBlocks.FESTERED_STONE.get().defaultBlockState());
            area.set(0, 0, 8, BASE_Y + 1, 8, RemissionBlocks.FESTERING_VEIN.get().defaultBlockState()
                    .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
            ServerPlayer player = SurgeTests.player(helper, area.at(0, 0, 8, BASE_Y + 1, 6));
            ItemStack purifier = new ItemStack(RemissionItems.HANDHELD_PURIFIER.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, purifier);

            BlockPos target = area.at(0, 0, 8, BASE_Y, 8);
            List<BlockPurifiedEvent> events = capture(BlockPurifiedEvent.class, () -> helper.assertValueEqual(use(player, target),
                    InteractionResult.CONSUME, "use on festering ground"));
            helper.assertTrue(stateAt(area, 0, 8, 8).is(Blocks.GRASS_BLOCK), "used block not cleansed");
            helper.assertTrue(level.getBlockState(target.above()).isAir(), "vein above not cleared");
            helper.assertTrue(stateAt(area, 0, 10, 8).is(Blocks.STONE), "block 2 away not cleansed");
            helper.assertTrue(stateAt(area, 0, 11, 8).is(RemissionBlocks.FESTERED_STONE.get()), "block 3 away cleansed");
            helper.assertValueEqual(events.size(), 3, "block purified events");
            helper.assertTrue(events.stream().allMatch(e -> e.getCause() == PurificationCause.PURIFIER), "event causes");
            helper.assertValueEqual(levelOf(chunk), 19, "chunk level after one use");
            helper.assertValueEqual(player.getMainHandItem().getDamageValue(), 1, "durability used");
            helper.assertTrue(player.getCooldowns().isOnCooldown(RemissionItems.HANDHELD_PURIFIER.get()), "no cooldown");

            // Purifying a chunk down to 0 credits the player on the remission event.
            setLevel(chunk, 1);
            player.getCooldowns().removeCooldown(RemissionItems.HANDHELD_PURIFIER.get());
            List<ChunkEnteredRemissionEvent> entered = capture(ChunkEnteredRemissionEvent.class, () -> use(player, target));
            helper.assertValueEqual(entered.size(), 1, "remission events from the purifier");
            helper.assertTrue(entered.getFirst().getCause() == PurificationCause.PURIFIER
                    && entered.getFirst().getPlayer().orElse(null) == player, "purifier remission event payload");

            // Nothing to do: no durability used.
            player.getCooldowns().removeCooldown(RemissionItems.HANDHELD_PURIFIER.get());
            helper.assertValueEqual(use(player, target), InteractionResult.PASS, "use on clean ground");
            helper.assertValueEqual(player.getMainHandItem().getDamageValue(), 2, "durability used on clean ground");

            // On a host: one host stage of Strain lifted, with the purification cause.
            Cow cow = EntityType.COW.create(level);
            cow.moveTo(target.getX() + 0.5, target.getY() + 1, target.getZ() + 2.5);
            Corruption.set(cow, 50);
            List<HostCleansedEvent> cleansed = capture(HostCleansedEvent.class, () -> helper.assertValueEqual(
                    player.getMainHandItem().interactLivingEntity(player, cow, InteractionHand.MAIN_HAND), InteractionResult.CONSUME,
                    "use on a host"));
            helper.assertValueEqual(Corruption.get(cow).level(), 16, "host level after the purifier");
            helper.assertValueEqual(cleansed.size(), 1, "host cleansed events");
            helper.assertValueEqual(cleansed.getFirst().getCause(), CorruptionCause.PURIFICATION, "host cleansed cause");
            helper.assertTrue(List.of(CorruptionCause.values()).indexOf(CorruptionCause.PURIFICATION)
                    < List.of(CorruptionCause.values()).indexOf(CorruptionCause.OTHER), "PURIFICATION comes before OTHER");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_mendroot")
    public static void mendrootCleansesAroundIt(GameTestHelper helper) {
        inArea(helper, 7, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = area.chunk(0, 0);
            setLevel(chunk, 20);
            area.fill(0, 0, 3, BASE_Y, 3, 12, BASE_Y, 12, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
            area.set(0, 0, 3, BASE_Y + 1, 8, Blocks.STONE.defaultBlockState());
            BlockPos root = area.at(0, 0, 8, BASE_Y + 1, 8);
            BlockState mendroot = RemissionBlocks.MENDROOT.get().defaultBlockState();
            helper.assertTrue(mendroot.canSurvive(level, root), "mendroot cannot grow on festering grass");
            helper.assertFalse(mendroot.canSurvive(level, area.at(0, 0, 3, BASE_Y + 2, 8)), "mendroot grows on stone");
            area.set(0, 0, 8, BASE_Y + 1, 8, mendroot);
            helper.assertTrue(level.getBlockTicks().hasScheduledTick(root, RemissionBlocks.MENDROOT.get()), "no cleanse scheduled");

            List<BlockPurifiedEvent> events = capture(BlockPurifiedEvent.class, () -> mendroot.tick(level, root, level.random));
            helper.assertTrue(stateAt(area, 0, 8, 8).is(Blocks.GRASS_BLOCK), "ground under the mendroot");
            helper.assertTrue(stateAt(area, 0, 5, 8).is(Blocks.GRASS_BLOCK), "ground 3 away");
            helper.assertTrue(stateAt(area, 0, 11, 11).is(Blocks.GRASS_BLOCK), "ground 3 away diagonally");
            helper.assertTrue(stateAt(area, 0, 4, 8).is(RemissionBlocks.FESTERING_GRASS.get()), "ground 4 away cleansed");
            helper.assertValueEqual(events.size(), 49, "block purified events (a 7 by 7 patch)");
            helper.assertTrue(events.stream().allMatch(e -> e.getCause() == PurificationCause.MENDROOT), "event causes");
            helper.assertValueEqual(levelOf(chunk), 19, "chunk level after one cleanse");
            helper.assertTrue(level.getBlockState(root).is(RemissionBlocks.MENDROOT.get()), "mendroot did not survive its cleanse");
            helper.assertTrue(level.getBlockTicks().hasScheduledTick(root, RemissionBlocks.MENDROOT.get()), "next cleanse not scheduled");
            helper.succeed();
        });
    }

    // ---- remission zones and data ------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_zones")
    public static void remissionZoneBonusesAndDefaults(GameTestHelper helper) {
        inArea(helper, 8, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk cleansed = area.chunk(0, 0);
            LevelChunk festering = area.chunk(1, 0);
            setLevel(cleansed, 1);
            setLevel(festering, 1);
            Purification.lowerLevel(level, cleansed, 5, PurificationCause.OTHER, null);
            BlockPos in = area.at(0, 0, 8, BASE_Y, 8);
            BlockPos out = area.at(1, 0, 8, BASE_Y, 8);
            helper.assertTrue(RemissionCommonConfig.REMISSION_SPAWN_SAFETY.get(), "spawn safety off in the test config");
            helper.assertTrue(RemissionZones.refusesSpawn(level, in, MobCategory.MONSTER, MobSpawnType.NATURAL), "monster spawn allowed");
            helper.assertFalse(RemissionZones.refusesSpawn(level, in, MobCategory.CREATURE, MobSpawnType.NATURAL), "animal refused");
            helper.assertFalse(RemissionZones.refusesSpawn(level, in, MobCategory.MONSTER, MobSpawnType.SPAWNER), "spawner refused");
            helper.assertFalse(RemissionZones.refusesSpawn(level, out, MobCategory.MONSTER, MobSpawnType.NATURAL), "refused outside remission");

            List<ItemEntity> drops = List.of(new ItemEntity(level, 0, 0, 0, new ItemStack(Items.WHEAT)),
                    new ItemEntity(level, 0, 0, 0, new ItemStack(Items.WHEAT_SEEDS, 64)));
            RemissionZones.addYield(drops);
            helper.assertValueEqual(drops.get(0).getItem().getCount(), 2, "yield bonus on one wheat");
            helper.assertValueEqual(drops.get(1).getItem().getCount(), 64, "yield bonus past a full stack");
            helper.assertTrue(Blocks.WHEAT.defaultBlockState().is(RemissionZones.YIELDS), "wheat not in #remission:remission_yields");

            assertCleansesInto(helper, RemissionBlocks.FESTERING_GRASS.get(), Blocks.GRASS_BLOCK);
            assertCleansesInto(helper, RemissionBlocks.FESTERING_LOG.get(), Blocks.OAK_LOG);
            assertCleansesInto(helper, RemissionBlocks.FESTERING_LEAVES.get(), Blocks.OAK_LEAVES);
            assertCleansesInto(helper, RemissionBlocks.FESTERED_STONE.get(), Blocks.STONE);
            assertCleansesInto(helper, RemissionBlocks.FESTERED_DEEPSLATE.get(), Blocks.DEEPSLATE);
            helper.assertTrue(Cleansing.cleansed(Blocks.STONE.defaultBlockState()) == null, "clean stone cleanses into something");
            helper.assertTrue(Cleansing.cleansed(RemissionBlocks.FESTERING_VEIN.get().defaultBlockState()).isAir(), "vein not cleared");
            helper.succeed();
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static void assertCleansesInto(GameTestHelper helper, Block festered, Block clean) {
        BlockState state = Cleansing.cleansed(festered.defaultBlockState());
        helper.assertTrue(state != null && state.is(clean), festered + " cleanses into " + state + ", not " + clean);
    }

    /** One test's chunks, force-loaded on demand, and everything to put back. */
    private record Area(GameTestHelper helper, ChunkPos origin, List<LevelChunk> chunks, List<BlockPos> pylons,
                        List<BlockPos> nidi, List<ServerPlayer> players, List<Runnable> undo) {
        ChunkPos pos(int dx, int dz) {
            return new ChunkPos(origin.x + dx, origin.z + dz);
        }

        LevelChunk chunk(int dx, int dz) {
            ServerLevel level = helper.getLevel();
            ChunkPos pos = pos(dx, dz);
            level.setChunkForced(pos.x, pos.z, true);
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
            chunks.add(chunk);
            return chunk;
        }

        BlockPos at(int dx, int dz, int localX, int y, int localZ) {
            ChunkPos pos = pos(dx, dz);
            return new BlockPos(pos.getBlockX(localX), y, pos.getBlockZ(localZ));
        }

        void set(int dx, int dz, int localX, int y, int localZ, BlockState state) {
            ServerLevel level = helper.getLevel();
            BlockPos at = at(dx, dz, localX, y, localZ);
            undo.add(() -> level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            level.setBlock(at, state, Block.UPDATE_ALL);
        }

        void fill(int dx, int dz, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(dx, dz, x, y, z, state);
                    }
                }
            }
        }

        BlockPos pylon(int dx, int dz) {
            return pylon(dx, dz, 8, 8);
        }

        BlockPos pylon(int dx, int dz, int localX, int localZ) {
            BlockPos at = at(dx, dz, localX, BASE_Y + 6, localZ);
            helper.getLevel().setBlock(at, RemissionBlocks.QUARANTINE_PYLON.get().defaultBlockState(), Block.UPDATE_ALL);
            pylons.add(at);
            return at;
        }

        void nidus(int dx, int dz) {
            BlockPos at = at(dx, dz, 8, BASE_Y + 8, 8);
            helper.getLevel().setBlock(at, RemissionBlocks.NIDUS.get().defaultBlockState(), Block.UPDATE_ALL);
            nidi.add(at);
        }

        ServerPlayer player(GameTestHelper helper, BlockPos feet) {
            ServerPlayer player = SurgeTests.player(helper, feet);
            helper.getLevel().players().add(player);
            players.add(player);
            return player;
        }

        void removePylons() {
            pylons.forEach(pos -> helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
            pylons.clear();
        }

        void release() {
            ServerLevel level = helper.getLevel();
            players.forEach(player -> level.players().remove(player));
            removePylons();
            nidi.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
            undo.reversed().forEach(Runnable::run);
            for (LevelChunk chunk : chunks) {
                // Clears veins and festered blocks the test's passes left anywhere in the chunk, and its biome.
                Cleansing.cleanseNow(level, chunk, PurificationCause.OTHER);
                Purification.engine(level).unloaded(chunk.getPos());
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
                chunk.removeData(RemissionAttachments.FESTERED_STAGE);
                level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
            }
        }
    }

    private static void inArea(GameTestHelper helper, int index, Consumer<Area> body) {
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        Area area = new Area(helper, new ChunkPos(own.x + FAR + index * SPACING, own.z), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        try {
            body.accept(area);
        } finally {
            area.release();
        }
    }

    private static void setLevel(LevelChunk chunk, int value) {
        if (value <= 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(value, 0L));
        }
    }

    private static int levelOf(LevelChunk chunk) {
        ChunkCorruption state = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        return state == null ? 0 : state.level();
    }

    private static BlockState stateAt(Area area, int dx, int localX, int localZ) {
        return area.helper().getLevel().getBlockState(area.at(dx, 0, localX, BASE_Y, localZ));
    }

    private static boolean isFester(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        return biome.is(FesterMaps.FESTER);
    }

    /** Vein blocks in the layer above the test floor of chunk {@code dx}. */
    private static int veinsAbove(ServerLevel level, Area area, int dx) {
        int veins = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (level.getBlockState(area.at(dx, 0, x, BASE_Y + 1, z)).is(RemissionBlocks.FESTERING_VEIN.get())) {
                    veins++;
                }
            }
        }
        return veins;
    }

    private static Pig pig(ServerLevel level, BlockPos feet) {
        Pig pig = EntityType.PIG.create(level);
        pig.setNoAi(true);
        pig.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        return pig;
    }

    private static InteractionResult use(ServerPlayer player, BlockPos target) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target).add(0, 0.5, 0), Direction.UP, target, false);
        return player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static <E extends Event> List<E> capture(Class<E> type, Runnable body) {
        List<E> events = new ArrayList<>();
        Consumer<E> listener = events::add;
        NeoForge.EVENT_BUS.addListener(type, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events;
    }

    private static int stat(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(RemissionStats.CHUNKS_CLEANSED.get()));
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
}
