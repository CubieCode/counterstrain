package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.behaviour.MobProfiles;
import com.cubiecode.remission.infection.ContactSettings;
import com.cubiecode.remission.infection.ContactTracker;
import com.cubiecode.remission.infection.ExposureSettings;
import com.cubiecode.remission.infection.ExposureTracker;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesteringStages;
import com.cubiecode.remission.world.FesteringVeins;
import com.cubiecode.remission.world.VeinGrowth;
import com.cubiecode.remission.world.VeinSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Server tick cost of festering veins and contact infection next to P4.6's cave pass and P4.7's exposure tracker, for
 * the P4.8 measurement. Registered only when the {@code REMISSION_VEIN_BENCHMARK} environment variable is set, so normal
 * gametest runs skip it. It force-loads a square of chunks away from the test area and writes synthetic land straight
 * into their sections (stone above deepslate up to {@link #TOP}, about 1.7% coal ore, {@link #CAVES} cave spheres of
 * radius 2–4 per chunk, a stone top and a small plank hut in every fourth chunk), sets them to the last festering stage,
 * then times per tick: the cave pass at its 8-layer budget, the vein pass at its budget (first growth, then a revisit
 * with nothing to add), and with 400 mobs (no AI) on the land, the contact tracker (misses: plain stone underfoot, the
 * most expensive check) and the exposure tracker side by side, then a contact wave in which every mob is infected.
 * Writes {@code vein-benchmark.txt}.
 */
@GameTestHolder(Remission.MOD_ID)
public final class VeinBenchmark {
    private static final int SIDE = 4;
    private static final int OFFSET = 200;
    private static final int TOP = 56;
    private static final int CAVES = 40;
    private static final int MOBS = 400;

    private VeinBenchmark() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> benchmarks() {
        if (System.getenv("REMISSION_VEIN_BENCHMARK") == null) {
            return List.of();
        }
        return List.of(new TestFunction("veinBenchmark", "remission.veinbenchmark", "remission:empty", 12000, 0L, true,
                VeinBenchmark::start));
    }

    private static void start(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                level.setChunkForced(origin.x + OFFSET + x, origin.z + OFFSET + z, true);
                chunks.add(level.getChunk(origin.x + OFFSET + x, origin.z + OFFSET + z));
            }
        }
        List<Mob> mobs = new ArrayList<>();
        List<String> out = new ArrayList<>();
        helper.startSequence()
                .thenExecute(() -> {
                    RandomSource random = RandomSource.create(4808L);
                    for (int i = 0; i < chunks.size(); i++) {
                        buildLand(chunks.get(i), random, i % 4 == 0);
                        chunks.get(i).setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(ChunkCorruption.MAX_LEVEL - 10, 0L));
                    }
                    measureLand(level, chunks, out);
                })
                .thenWaitUntil(() -> chunks.forEach(chunk -> helper.assertTrue(
                        level.isPositionEntityTicking(chunk.getPos().getWorldPosition()), "chunk is not entity-ticking")))
                .thenExecute(() -> spawn(level, chunks, mobs))
                .thenIdle(1)
                .thenExecute(() -> {
                    measureMobs(level, chunks, mobs, out);
                    mobs.forEach(Mob::discard);
                    chunks.forEach(chunk -> {
                        chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                        level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
                    });
                    out.forEach(line -> Remission.LOGGER.info("[vein benchmark] {}", line));
                    try {
                        Files.write(Path.of("vein-benchmark.txt"), out);
                    } catch (IOException e) {
                        Remission.LOGGER.warn("Could not write vein-benchmark.txt", e);
                    }
                })
                .thenSucceed();
    }

    /** Writes the synthetic land of one chunk straight into its sections (no light, heightmap or neighbour updates). */
    private static void buildLand(LevelChunk chunk, RandomSource random, boolean hut) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState caveAir = Blocks.CAVE_AIR.defaultBlockState();
        int bottom = chunk.getMinBuildHeight();
        for (int y = bottom; y < chunk.getMaxBuildHeight(); y++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState state;
                    if (y == bottom) {
                        state = Blocks.BEDROCK.defaultBlockState();
                    } else if (y > TOP) {
                        state = air;
                    } else if (random.nextInt(60) == 0) {
                        state = (y < 0 ? Blocks.DEEPSLATE_COAL_ORE : Blocks.COAL_ORE).defaultBlockState();
                    } else {
                        state = (y < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
                    }
                    section.setBlockState(x, y & 15, z, state, false);
                }
            }
        }
        for (int i = 0; i < CAVES; i++) {
            int cx = random.nextInt(16);
            int cy = bottom + 4 + random.nextInt(TOP - bottom - 8);
            int cz = random.nextInt(16);
            int r = 2 + random.nextInt(3);
            for (int x = Math.max(0, cx - r); x <= Math.min(15, cx + r); x++) {
                for (int y = cy - r; y <= cy + r; y++) {
                    for (int z = Math.max(0, cz - r); z <= Math.min(15, cz + r); z++) {
                        if ((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz) <= r * r) {
                            chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, caveAir, false);
                        }
                    }
                }
            }
        }
        if (hut) {
            for (int x = 4; x <= 10; x++) {
                for (int y = TOP + 1; y <= TOP + 4; y++) {
                    for (int z = 4; z <= 10; z++) {
                        boolean wall = x == 4 || x == 10 || z == 4 || z == 10 || y == TOP + 4;
                        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z,
                                wall ? Blocks.OAK_PLANKS.defaultBlockState() : air, false);
                    }
                }
            }
        }
    }

    private static void measureLand(ServerLevel level, List<LevelChunk> chunks, List<String> out) {
        int stage = CorruptionQueries.chunkStage(level, chunks.get(0).getPos());
        out.add(String.format("== %d chunks, stage %d, land to Y=%d with %d caves each, hut in every fourth", chunks.size(), stage, TOP, CAVES));
        // Warm up the JIT on the first chunk; it is then measured as a rescan inside the first pass.
        caveTicks(level, chunks.subList(0, 1), stage);
        long[] cave = caveTicks(level, chunks, stage);
        out.add(summary("cave pass, " + FesteringStages.LAYER_BUDGET + " layers per tick (first pass)", cave));
        out.add(summary("cave pass rescan, nothing to change", caveTicks(level, chunks, stage)));

        VeinSettings settings = VeinSettings.fromConfig();
        veinTicks(level, chunks.subList(0, 1), settings);
        out.add(summary("vein pass, " + FesteringVeins.LAYER_BUDGET + " layers per tick (first growth)", veinTicks(level, chunks, settings)));
        out.add(summary("vein pass revisit, nothing to add", veinTicks(level, chunks, settings)));
        int veins = 0;
        for (LevelChunk chunk : chunks) {
            for (LevelChunkSection section : chunk.getSections()) {
                if (section.maybeHas(state -> state.is(RemissionBlocks.FESTERING_VEIN.get()))) {
                    for (int i = 0; i < 4096; i++) {
                        if (section.getBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15).is(RemissionBlocks.FESTERING_VEIN.get())) {
                            veins++;
                        }
                    }
                }
            }
        }
        out.add("vein blocks grown: " + veins);
    }

    /** Runs the cave pass over every chunk at its per-tick layer budget and returns the time of each tick. */
    private static long[] caveTicks(ServerLevel level, List<LevelChunk> chunks, int stage) {
        List<Long> ticks = new ArrayList<>();
        for (LevelChunk chunk : chunks) {
            for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y += FesteringStages.LAYER_BUDGET) {
                long start = System.nanoTime();
                for (int layer = y; layer < Math.min(y + FesteringStages.LAYER_BUDGET, chunk.getMaxBuildHeight()); layer++) {
                    FesteringStages.festerCaves(level, chunk, layer, stage);
                }
                ticks.add(System.nanoTime() - start);
            }
        }
        return ticks.stream().mapToLong(Long::longValue).toArray();
    }

    /** Runs a vein pass over every chunk at its per-tick budget until it is done, returning the time of each tick. */
    private static long[] veinTicks(ServerLevel level, List<LevelChunk> chunks, VeinSettings settings) {
        VeinGrowth growth = new VeinGrowth(level, () -> settings);
        chunks.forEach(chunk -> growth.queue(chunk.getPos()));
        List<Long> ticks = new ArrayList<>();
        while (growth.busy()) {
            long start = System.nanoTime();
            growth.tick(FesteringVeins.LAYER_BUDGET);
            ticks.add(System.nanoTime() - start);
        }
        return ticks.stream().mapToLong(Long::longValue).toArray();
    }

    private static void spawn(ServerLevel level, List<LevelChunk> chunks, List<Mob> mobs) {
        for (int i = 0; i < MOBS; i++) {
            LevelChunk chunk = chunks.get(i % chunks.size());
            // Outside the huts, on the stone top.
            int x = chunk.getPos().getMinBlockX() + 11 + (i / chunks.size()) % 5;
            int z = chunk.getPos().getMinBlockZ() + (i / chunks.size() / 5) % 16;
            Mob mob = (i % 2 == 0 ? EntityType.PIG : EntityType.SHEEP).create(level);
            mob.setNoAi(true);
            mob.moveTo(x + 0.5, TOP + 1, z + 0.5, 0, 0);
            if (level.addFreshEntity(mob)) {
                mobs.add(mob);
            }
        }
    }

    private static void measureMobs(ServerLevel level, List<LevelChunk> chunks, List<Mob> mobs, List<String> out) {
        out.add(String.format("== %d mobs on the stone top of the festering chunks", mobs.size()));
        long base = level.getGameTime() + 1_000_000L;
        // Contact misses: nothing infectious underfoot, so each check reads every block around the mob.
        ContactSettings never = new ContactSettings(true, Integer.MAX_VALUE, ContactSettings.fromConfig().waterMinStage(),
                ContactSettings.INTERVAL_TICKS, ContactSettings.MOB_BUDGET, ContactSettings.INFECTION_BUDGET);
        ContactTracker warmup = new ContactTracker(level, () -> never, level::getAllEntities, MobProfiles::find);
        run(warmup::tick, base, 20 * ContactSettings.INTERVAL_TICKS);
        ContactTracker contact = new ContactTracker(level, () -> never, level::getAllEntities, MobProfiles::find);
        base += 100_000L;
        out.add(summary("contact tracker, steady (no contact), 60 passes", run(contact::tick, base, 60 * ContactSettings.INTERVAL_TICKS)));

        ExposureSettings exposureSettings = ExposureSettings.fromConfig();
        ExposureTracker exposure = new ExposureTracker(level, () -> exposureSettings, level::getAllEntities, MobProfiles::find);
        base += 100_000L;
        out.add(summary("exposure tracker (P4.7), steady, 30 passes", run(exposure::tick, base, 30 * exposureSettings.intervalTicks())));
        mobs.forEach(mob -> ExposureTracker.setExposure(mob, 0));

        // Wave: festering grass under every mob and contact on first touch.
        for (Mob mob : mobs) {
            BlockPos below = mob.blockPosition().below();
            LevelChunk chunk = level.getChunkAt(below);
            chunk.getSection(chunk.getSectionIndex(below.getY())).setBlockState(below.getX() & 15, below.getY() & 15, below.getZ() & 15,
                    RemissionBlocks.FESTERING_GRASS.get().defaultBlockState(), false);
        }
        ContactSettings instant = new ContactSettings(true, 0, never.waterMinStage(), ContactSettings.INTERVAL_TICKS,
                ContactSettings.MOB_BUDGET, ContactSettings.INFECTION_BUDGET);
        ContactTracker wave = new ContactTracker(level, () -> instant, level::getAllEntities, MobProfiles::find);
        base += 100_000L;
        List<Long> waveTicks = new ArrayList<>();
        int infected = 0;
        for (long t = base; infected < mobs.size() && t < base + 400; t++) {
            long start = System.nanoTime();
            ExposureTracker.TickWork work = wave.tick(t);
            long elapsed = System.nanoTime() - start;
            if (work.checked() > 0) {
                waveTicks.add(elapsed);
                infected += work.infected();
            }
        }
        out.add(summary("contact wave, " + infected + " new hosts (ticks with work)", waveTicks.stream().mapToLong(Long::longValue).toArray()));
    }

    private interface Tick {
        Object tick(long now);
    }

    private static long[] run(Tick tracker, long base, int ticks) {
        long[] nanos = new long[ticks];
        for (int t = 0; t < ticks; t++) {
            long start = System.nanoTime();
            tracker.tick(base + t);
            nanos[t] = System.nanoTime() - start;
        }
        return nanos;
    }

    private static String summary(String name, long[] nanos) {
        if (nanos.length == 0) {
            return name + ": no ticks";
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double total = Arrays.stream(nanos).sum();
        return String.format("%s: %d ticks, mean %.1f us, median %.1f us, p95 %.1f us, p99 %.1f us, max %.1f us, total %.2f ms",
                name, nanos.length, total / nanos.length / 1e3, sorted[sorted.length / 2] / 1e3,
                sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))] / 1e3,
                sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.99))] / 1e3,
                sorted[sorted.length - 1] / 1e3, total / 1e6);
    }
}
