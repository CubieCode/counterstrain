package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.MobProfiles;
import com.cubiecode.remission.infection.ExposureSettings;
import com.cubiecode.remission.infection.ExposureTracker;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.world.ChunkCorruption;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Server tick cost of festering land infection with a few hundred mobs, for the P4.7 measurement. Registered only when
 * the {@code REMISSION_EXPOSURE_BENCHMARK} environment variable is set, so normal gametest runs skip it. It force-loads a
 * square of festering chunks away from the test area, adds real mobs (no AI) to the level once they are entity-ticking, and drives a separate tracker
 * over the whole level's entities with the default config, as the level's own tracker does. It logs the time each tracker
 * tick took (the mspt it adds) for steady exposure and for a wave in which every mob becomes a host, and writes
 * {@code exposure-benchmark.txt}.
 */
@GameTestHolder(Remission.MOD_ID)
public final class ExposureBenchmark {
    private static final int SIDE = 4;
    private static final int MOBS = 400;

    private ExposureBenchmark() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> benchmarks() {
        if (System.getenv("REMISSION_EXPOSURE_BENCHMARK") == null) {
            return List.of();
        }
        return List.of(new TestFunction("exposureBenchmark", "remission.exposurebenchmark", "remission:empty", 2400, 0L, true,
                ExposureBenchmark::start));
    }

    private static void start(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                level.setChunkForced(origin.x + 64 + x, origin.z + 64 + z, true);
                LevelChunk chunk = level.getChunk(origin.x + 64 + x, origin.z + 64 + z);
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(ChunkCorruption.MAX_LEVEL / 2, 0L));
                chunks.add(chunk);
            }
        }
        List<Mob> mobs = new ArrayList<>();
        // Mobs added before their chunk is entity-ticking stay out of the level's entity lookup, so wait first.
        helper.startSequence()
                .thenWaitUntil(() -> chunks.forEach(chunk -> helper.assertTrue(
                        level.isPositionEntityTicking(chunk.getPos().getWorldPosition()), "chunk is not entity-ticking")))
                .thenExecute(() -> spawn(helper, chunks, mobs))
                .thenIdle(1)
                .thenExecute(() -> measure(helper, chunks, mobs));
    }

    private static void spawn(GameTestHelper helper, List<LevelChunk> chunks, List<Mob> mobs) {
        ServerLevel level = helper.getLevel();
        for (int i = 0; i < MOBS; i++) {
            LevelChunk chunk = chunks.get(i % chunks.size());
            int x = chunk.getPos().getMinBlockX() + (i / chunks.size()) % 16;
            int z = chunk.getPos().getMinBlockZ() + (i / chunks.size() / 16) % 16;
            Mob mob = (i % 2 == 0 ? EntityType.PIG : EntityType.SHEEP).create(level);
            mob.setNoAi(true);
            mob.moveTo(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5, 0, 0);
            if (level.addFreshEntity(mob)) {
                mobs.add(mob);
            }
        }
    }

    private static void measure(GameTestHelper helper, List<LevelChunk> chunks, List<Mob> mobs) {
        ServerLevel level = helper.getLevel();
        ExposureSettings settings = ExposureSettings.fromConfig();
        List<String> out = new ArrayList<>();
        int entities = 0;
        for (var ignored : level.getAllEntities()) {
            entities++;
        }
        out.add(String.format("== %d mobs in %d festering chunks (level %d), %d entities in the level, %s", mobs.size(),
                chunks.size(), ChunkCorruption.MAX_LEVEL / 2, entities, settings));

        // Warm up the JIT on a throwaway tracker, then measure from no exposure.
        ExposureTracker warmup = new ExposureTracker(level, () -> settings, level::getAllEntities, MobProfiles::find);
        long base = level.getGameTime() + 1_000_000L;
        for (long t = 0; t < 20L * settings.intervalTicks(); t++) {
            warmup.tick(base + t);
        }
        mobs.forEach(mob -> ExposureTracker.setExposure(mob, 0));

        ExposureTracker tracker = new ExposureTracker(level, () -> settings, level::getAllEntities, MobProfiles::find);
        base += 100L * settings.intervalTicks();
        int ticks = 60 * settings.intervalTicks();
        long[] nanos = new long[ticks];
        int working = 0;
        long workingNanos = 0;
        for (int t = 0; t < ticks; t++) {
            long start = System.nanoTime();
            ExposureTracker.TickWork work = tracker.tick(base + t);
            nanos[t] = System.nanoTime() - start;
            if (work.checked() > 0) {
                working++;
                workingNanos += nanos[t];
            }
        }
        out.add(summary("steady exposure, 60 passes", nanos, working, workingNanos, settings.intervalTicks()));
        out.add("exposure of the first mob after 60 passes: " + ExposureTracker.exposure(mobs.get(0)));

        // Wave: every mob one pass short of the threshold, so the next pass turns them all into hosts.
        mobs.forEach(mob -> ExposureTracker.setExposure(mob, settings.exposureTicks() - 1));
        List<Long> wave = new ArrayList<>();
        int infected = 0;
        for (long t = base + ticks; infected < mobs.size() && t < base + ticks + 10L * settings.intervalTicks(); t++) {
            long start = System.nanoTime();
            ExposureTracker.TickWork work = tracker.tick(t);
            long elapsed = System.nanoTime() - start;
            if (work.checked() > 0) {
                wave.add(elapsed);
                infected += work.infected();
            }
        }
        long[] waveNanos = wave.stream().mapToLong(Long::longValue).toArray();
        out.add(summary("infection wave, " + infected + " new hosts", waveNanos, waveNanos.length,
                Arrays.stream(waveNanos).sum(), 0));

        mobs.forEach(Mob::discard);
        chunks.forEach(chunk -> {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
        });
        out.forEach(line -> Remission.LOGGER.info("[exposure benchmark] {}", line));
        try {
            Files.write(Path.of("exposure-benchmark.txt"), out);
        } catch (IOException e) {
            Remission.LOGGER.warn("Could not write exposure-benchmark.txt", e);
        }
        helper.succeed();
    }

    private static String summary(String name, long[] nanos, int working, long workingNanos, int interval) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double total = Arrays.stream(nanos).sum();
        return String.format("%s: %d ticks, %d with work; mean %.1f us/tick overall, %.1f us per working tick, "
                        + "p99 %.1f us, max %.1f us%s",
                name, nanos.length, working, total / nanos.length / 1e3, working == 0 ? 0 : workingNanos / (double) working / 1e3,
                sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.99))] / 1e3, sorted[sorted.length - 1] / 1e3,
                interval > 0 ? String.format(", %.1f us per interval", total / (nanos.length / (double) interval) / 1e3) : "");
    }
}
