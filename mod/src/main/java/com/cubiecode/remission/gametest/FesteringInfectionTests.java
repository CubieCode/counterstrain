package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.api.HostCorruptedEvent;
import com.cubiecode.remission.behaviour.MobProfile;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.infection.ExposureSettings;
import com.cubiecode.remission.infection.ExposureTracker;
import com.cubiecode.remission.infection.Infection;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.world.ChunkCorruption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Festering land infection (P4.7): mobs in festering land at or above the minimum stage build exposure, at any height,
 * lose it elsewhere, and become hosts through the corruption API with {@link CorruptionCause#FESTERING_LAND}. Each test
 * drives its own {@link ExposureTracker} over its own mobs with synthetic game times, on chunks far east of the test grid
 * (force-loaded, and used once they are entity-ticking). Most mobs are created but never added to the level,
 * so the level's own tracker never sees them.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FesteringInfectionTests {
    private static final String BATCH = "festering_infection";
    /** Chunks east of the test's own chunk, one pair per test, away from the grid and from the festering stage tests. */
    private static final int FAR = 160;
    private static final int EXPOSURE = 100;
    private static final ExposureSettings SETTINGS = new ExposureSettings(true, 2, EXPOSURE, 20, 256, 32);
    private static final long BASE = 10_000_000L;

    private FesteringInfectionTests() {
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void surfaceAndUndergroundMobsBecomeHosts(GameTestHelper helper) {
        LevelChunk land = farChunk(helper, 0);
        setStage(land, SETTINGS.minStage());
        whenTicking(helper, List.of(land), () -> {
            ServerLevel level = helper.getLevel();
            Mob surface = create(level, EntityType.PIG, surface(level, land));
            BlockPos cave = cave(level, land);
            Mob underground = create(level, EntityType.PIG, cave);
            helper.assertFalse(level.canSeeSky(cave), "cave pocket sees the sky");
            ExposureTracker tracker = tracker(level, List.of(surface, underground), Map.of());
            List<HostCorruptedEvent> events = capture(() -> {
                run(tracker, BASE, BASE + EXPOSURE - 1);
                helper.assertFalse(Corruption.isHost(surface) || Corruption.isHost(underground), "host before the exposure time");
                helper.assertValueEqual(ExposureTracker.exposure(underground), EXPOSURE - SETTINGS.intervalTicks(), "underground exposure");
                run(tracker, BASE + EXPOSURE, BASE + EXPOSURE);
            });
            helper.assertTrue(Corruption.isHost(surface), "surface mob is not a host after the exposure time");
            helper.assertTrue(Corruption.isHost(underground), "underground mob is not a host after the exposure time");
            helper.assertValueEqual(Corruption.get(surface).level(), Infection.initialLevel(), "new host level");
            helper.assertValueEqual(ExposureTracker.exposure(surface), 0, "exposure kept by a new host");
            helper.assertValueEqual(events.size(), 2, "host corrupted events");
            for (HostCorruptedEvent event : events) {
                helper.assertValueEqual(event.getCause(), CorruptionCause.FESTERING_LAND, "event cause");
                helper.assertTrue(event.becameHost(), "event did not turn the mob into a host");
            }
            release(level, land);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void ineligibleMobsNeverBecomeHosts(GameTestHelper helper) {
        LevelChunk land = farChunk(helper, 2);
        setStage(land, SETTINGS.minStage() + 1);
        whenTicking(helper, List.of(land), () -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = surface(level, land);
            Mob wither = create(level, EntityType.WITHER, pos);
            Mob host = create(level, EntityType.PIG, pos);
            Corruption.set(host, 50);
            Mob optedOut = create(level, EntityType.COW, pos);
            MobProfile noFesteringLand = new MobProfile(HolderSet.direct(EntityType.COW.builtInRegistryHolder()), 0,
                    Optional.empty(), Map.of(), Optional.empty(), new MobProfile.Conversion(true, false));
            ExposureTracker tracker = tracker(level, List.of(wither, host, optedOut), Map.of(optedOut, noFesteringLand));
            List<HostCorruptedEvent> events = capture(() -> run(tracker, BASE, BASE + 5L * EXPOSURE));
            helper.assertFalse(Corruption.isHost(wither), "immune mob became a host");
            helper.assertFalse(Corruption.isHost(optedOut), "conversion.festering_land false became a host");
            helper.assertValueEqual(Corruption.get(host).level(), 50, "existing host level");
            helper.assertTrue(events.isEmpty(), "ineligible mobs posted host corrupted events");
            for (Mob mob : List.of(wither, host, optedOut)) {
                helper.assertValueEqual(ExposureTracker.exposure(mob), 0, "exposure of ineligible " + mob.getType());
            }
            release(level, land);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void nothingBelowMinimumStageOrWhenOff(GameTestHelper helper) {
        LevelChunk land = farChunk(helper, 4);
        setStage(land, SETTINGS.minStage() - 1);
        whenTicking(helper, List.of(land), () -> {
            ServerLevel level = helper.getLevel();
            Mob pig = create(level, EntityType.PIG, surface(level, land));
            run(tracker(level, List.of(pig), Map.of()), BASE, BASE + 5L * EXPOSURE);
            helper.assertFalse(Corruption.isHost(pig), "host below the minimum stage");
            helper.assertValueEqual(ExposureTracker.exposure(pig), 0, "exposure below the minimum stage");

            setStage(land, SETTINGS.minStage());
            ExposureSettings off = new ExposureSettings(false, SETTINGS.minStage(), EXPOSURE, 20, 256, 32);
            ExposureTracker disabled = new ExposureTracker(level, () -> off, () -> List.of(pig), mob -> Optional.empty());
            run(disabled, BASE, BASE + 5L * EXPOSURE);
            helper.assertFalse(Corruption.isHost(pig), "host with the source turned off");
            helper.assertValueEqual(ExposureTracker.exposure(pig), 0, "exposure with the source turned off");

            ExposureSettings unreachable = new ExposureSettings(true, 8, EXPOSURE, 20, 256, 32);
            helper.assertValueEqual(unreachable.minLevel(), Integer.MAX_VALUE, "minimum level of a stage past the thresholds");
            release(level, land);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void exposureDecaysAndIsSaved(GameTestHelper helper) {
        LevelChunk land = farChunk(helper, 6);
        LevelChunk clean = farChunk(helper, 7);
        setStage(land, SETTINGS.minStage());
        whenTicking(helper, List.of(land, clean), () -> {
            ServerLevel level = helper.getLevel();
            Mob pig = create(level, EntityType.PIG, surface(level, land));
            ExposureTracker tracker = tracker(level, List.of(pig), Map.of());
            run(tracker, BASE, BASE + 60);
            helper.assertValueEqual(ExposureTracker.exposure(pig), 60, "exposure after 60 ticks");

            CompoundTag saved = pig.saveWithoutId(new CompoundTag());
            Mob reloaded = EntityType.PIG.create(level);
            helper.assertTrue(reloaded != null, "no pig");
            reloaded.load(saved);
            helper.assertValueEqual(ExposureTracker.exposure(reloaded), 60, "exposure after save and load");

            BlockPos away = surface(level, clean);
            pig.moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
            run(tracker, BASE + 61, BASE + 100);
            helper.assertValueEqual(ExposureTracker.exposure(pig), 20, "exposure after 40 ticks outside");
            run(tracker, BASE + 101, BASE + 200);
            helper.assertValueEqual(ExposureTracker.exposure(pig), 0, "exposure long after leaving");
            helper.assertFalse(pig.hasData(RemissionAttachments.FESTERING_EXPOSURE), "exposure attachment kept at 0");
            helper.assertFalse(Corruption.isHost(pig), "pig that left became a host");
            release(level, land);
            release(level, clean);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void convertToApplies(GameTestHelper helper) {
        LevelChunk land = farChunk(helper, 8);
        setStage(land, SETTINGS.minStage());
        whenTicking(helper, List.of(land), () -> {
            ServerLevel level = helper.getLevel();
            Mob pig = create(level, EntityType.PIG, surface(level, land));
            level.addFreshEntity(pig);
            MobProfile toZombie = new MobProfile(HolderSet.direct(EntityType.PIG.builtInRegistryHolder()), 0, Optional.empty(),
                    Map.of(), Optional.of(EntityType.ZOMBIE.builtInRegistryHolder()), MobProfile.Conversion.DEFAULT);
            ExposureTracker tracker = tracker(level, List.of(pig), Map.of(pig, toZombie));
            List<HostCorruptedEvent> events = capture(() -> run(tracker, BASE, BASE + EXPOSURE));
            helper.assertTrue(pig.isRemoved(), "converted pig was not removed");
            helper.assertValueEqual(events.size(), 1, "host corrupted events");
            HostCorruptedEvent event = events.get(0);
            Entity host = event.getEntity();
            helper.assertValueEqual(host.getType(), EntityType.ZOMBIE, "converted type");
            helper.assertValueEqual(event.getCause(), CorruptionCause.FESTERING_LAND, "event cause");
            helper.assertTrue(event.becameHost() && Corruption.isHost((Mob) host), "converted zombie is not a host");
            helper.assertValueEqual(host.position(), pig.position(), "converted position");
            host.discard();
            release(level, land);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void passesSpendTheBudgets(GameTestHelper helper) {
        LevelChunk land = farChunk(helper, 10);
        setStage(land, SETTINGS.minStage());
        whenTicking(helper, List.of(land), () -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = surface(level, land);
            List<Mob> pigs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                pigs.add(create(level, EntityType.PIG, pos));
            }
            ExposureSettings budget = new ExposureSettings(true, SETTINGS.minStage(), EXPOSURE, 20, 3, 2);
            ExposureTracker tracker = new ExposureTracker(level, () -> budget, () -> pigs, mob -> Optional.empty());
            int[] expected = {3, 3, 3, 1, 0};
            for (int t = 0; t < expected.length; t++) {
                helper.assertValueEqual(tracker.tick(BASE + t).checked(), expected[t], "mobs checked on tick " + t);
            }
            helper.assertValueEqual(tracker.tick(BASE + 19).checked(), 0, "mobs checked before the next interval");
            helper.assertValueEqual(tracker.tick(BASE + 20).checked(), 3, "mobs checked when the next pass starts");
            helper.assertValueEqual(tracker.pending(), 7, "mobs waiting in the second pass");
            helper.assertValueEqual(ExposureTracker.exposure(pigs.get(0)), 20, "exposure credited by the second pass");
            run(tracker, BASE + 21, BASE + 23);
            helper.assertValueEqual(tracker.pending(), 0, "mobs waiting after the second pass");

            // Every mob at the threshold: the infection budget, not the check budget, ends each tick of the third pass.
            pigs.forEach(pig -> ExposureTracker.setExposure(pig, EXPOSURE - 1));
            for (int t = 0; t < 5; t++) {
                helper.assertValueEqual(tracker.tick(BASE + 40 + t), new ExposureTracker.TickWork(2, 2), "work on tick " + t + " of the wave");
            }
            helper.assertTrue(pigs.stream().allMatch(Corruption::isHost), "a pig at the threshold is not a host");
            release(level, land);
            helper.succeed();
        });
    }

    /** Runs {@code body} once every chunk in {@code chunks} is entity-ticking (force-loading takes a few ticks). */
    private static void whenTicking(GameTestHelper helper, List<LevelChunk> chunks, Runnable body) {
        helper.startSequence()
                .thenWaitUntil(() -> chunks.forEach(chunk -> helper.assertTrue(
                        helper.getLevel().isPositionEntityTicking(chunk.getPos().getWorldPosition()), "far chunk is not entity-ticking")))
                .thenExecute(body);
    }

    private static ExposureTracker tracker(ServerLevel level, List<Mob> mobs, Map<Mob, MobProfile> profiles) {
        return new ExposureTracker(level, () -> SETTINGS, () -> mobs, mob -> Optional.ofNullable(profiles.get(mob)));
    }

    /** Ticks {@code tracker} at every game time from {@code from} to {@code to}, inclusive. */
    private static void run(ExposureTracker tracker, long from, long to) {
        for (long t = from; t <= to; t++) {
            tracker.tick(t);
        }
    }

    private static List<HostCorruptedEvent> capture(Runnable body) {
        List<HostCorruptedEvent> events = new ArrayList<>();
        Consumer<HostCorruptedEvent> listener = events::add;
        NeoForge.EVENT_BUS.addListener(HostCorruptedEvent.class, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events;
    }

    /** A mob of {@code type} at {@code pos}, not added to the level. */
    private static Mob create(ServerLevel level, EntityType<? extends Mob> type, BlockPos pos) {
        Mob mob = type.create(level);
        if (mob == null) {
            throw new IllegalStateException("Could not create " + type);
        }
        mob.setNoAi(true);
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        return mob;
    }

    /** The surface position in the middle of {@code chunk}. */
    private static BlockPos surface(ServerLevel level, LevelChunk chunk) {
        int x = chunk.getPos().getMiddleBlockX();
        int z = chunk.getPos().getMiddleBlockZ();
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    /** An air pocket inside a stone shell, below the surface in a corner of {@code chunk}. */
    private static BlockPos cave(ServerLevel level, LevelChunk chunk) {
        int x = chunk.getPos().getMinBlockX() + 3;
        int z = chunk.getPos().getMinBlockZ() + 3;
        int y = Math.max(level.getMinBuildHeight() + 2, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 4);
        BlockPos pocket = new BlockPos(x, y, z);
        for (BlockPos pos : BlockPos.betweenClosed(pocket.offset(-1, -1, -1), pocket.offset(1, 1, 1))) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(pocket, Blocks.AIR.defaultBlockState());
        return pocket;
    }

    private static void setStage(LevelChunk chunk, int stage) {
        ExposureSettings atStage = new ExposureSettings(true, stage, EXPOSURE, 20, 256, 32);
        int level = stage == 0 ? 0 : atStage.minLevel();
        if (level == 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(level, 0L));
        }
    }

    /** A force-loaded chunk {@code offset} chunks east of the test, its land cleared. */
    private static LevelChunk farChunk(GameTestHelper helper, int offset) {
        ServerLevel level = helper.getLevel();
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos pos = new ChunkPos(own.x + FAR + offset, own.z);
        level.setChunkForced(pos.x, pos.z, true);
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        return chunk;
    }

    private static void release(ServerLevel level, LevelChunk chunk) {
        chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
    }
}
