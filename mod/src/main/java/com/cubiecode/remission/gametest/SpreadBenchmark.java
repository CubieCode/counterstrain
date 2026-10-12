package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.StrainSpreadAttemptEvent;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.surge.MiasmaCloud;
import com.cubiecode.remission.surge.SurgeController;
import com.cubiecode.remission.surge.SurgeData;
import com.cubiecode.remission.surge.SurgeSettings;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesteringLand;
import com.cubiecode.remission.world.SpreadEngine;
import com.cubiecode.remission.world.SpreadSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Server tick cost of the spread engine on a real level, for the P4.1 measurement. Registered only when the
 * {@code REMISSION_SPREAD_BENCHMARK} environment variable is set, so normal gametest runs skip it. It loads a square of
 * chunks away from the test area, places real nidi, drives a separate engine over them tick by tick with the default
 * config, and logs the time each engine tick took (the mspt it adds), also writing {@code spread-benchmark-<case>.txt}.
 * The {@code surge} case (P4.3) runs the default case with a surge from one nidus, then a surge controller with four
 * players in the surge's area for a whole surge, waves and clouds included.
 */
@GameTestHolder(Remission.MOD_ID)
public final class SpreadBenchmark {
    private static final int SIDE = 32;

    private SpreadBenchmark() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> benchmarks() {
        if (System.getenv("REMISSION_SPREAD_BENCHMARK") == null) {
            return List.of();
        }
        // Default config with 16 nidi; then a stress case: 64 nidi and a pass every second, so the budget is always spent;
        // then the stress case again with a listener on each public land event (P4.4), as an integration would add.
        return List.of(
                new TestFunction("spreadBenchmark", "remission.spreadbenchmark_default", "remission:empty", 200, 0L, true,
                        helper -> measure(helper, "default", 4, 0, 64, false)),
                new TestFunction("spreadBenchmark", "remission.spreadbenchmark_stress", "remission:empty", 200, 0L, true,
                        helper -> measure(helper, "stress", 8, 20, 128, false)),
                new TestFunction("spreadBenchmark", "remission.spreadbenchmark_listeners", "remission:empty", 200, 0L, true,
                        helper -> measure(helper, "listeners", 8, 20, 192, true)),
                new TestFunction("spreadBenchmark", "remission.spreadbenchmark_surge", "remission:empty", 200, 0L, true,
                        helper -> measureSurge(helper, 256)));
    }

    private static void measure(GameTestHelper helper, String name, int nidiPerSide, int intervalTicks, int offset,
                                boolean listeners) {
        Consumer<StrainSpreadAttemptEvent> spread = event -> event.setRateMultiplier(event.getRateMultiplier() * 1.0);
        Consumer<ChunkStageChangedEvent> stage = event -> {
        };
        if (listeners) {
            NeoForge.EVENT_BUS.addListener(StrainSpreadAttemptEvent.class, spread);
            NeoForge.EVENT_BUS.addListener(ChunkStageChangedEvent.class, stage);
        }
        try {
            measure(helper, name, nidiPerSide, intervalTicks, offset);
        } finally {
            NeoForge.EVENT_BUS.unregister(spread);
            NeoForge.EVENT_BUS.unregister(stage);
        }
    }

    private static void measure(GameTestHelper helper, String name, int nidiPerSide, int intervalTicks, int offset) {
        ServerLevel level = helper.getLevel();
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int baseX = origin.x + offset;
        int baseZ = origin.z + offset;
        List<String> out = new ArrayList<>();

        long loadStart = System.nanoTime();
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                level.setChunkForced(baseX + x, baseZ + z, true);
                chunks.add(level.getChunk(baseX + x, baseZ + z));
            }
        }
        out.add(String.format("loaded %d chunks in %.0f ms", chunks.size(), (System.nanoTime() - loadStart) / 1e6));

        List<BlockPos> nidi = new ArrayList<>();
        int spacing = SIDE / nidiPerSide;
        for (int i = 0; i < nidiPerSide; i++) {
            for (int j = 0; j < nidiPerSide; j++) {
                int chunkX = baseX + spacing / 2 + i * spacing;
                int chunkZ = baseZ + spacing / 2 + j * spacing;
                BlockPos pos = new BlockPos(chunkX * 16 + 8, level.getMinBuildHeight() + 1, chunkZ * 16 + 8);
                level.setBlockAndUpdate(pos, RemissionBlocks.NIDUS.get().defaultBlockState());
                nidi.add(pos);
            }
        }
        SpreadSettings config = SpreadSettings.fromConfig();
        SpreadSettings settings = intervalTicks == 0 ? config : new SpreadSettings(intervalTicks, config.nidusGrowth(),
                config.spreadStep(), config.falloff(), config.spreadThreshold(), config.chunkBudget(), config.catchUpBudget());
        out.add(0, "== " + name);
        out.add(String.format("%d nidi, %s, radius %d", nidi.size(), settings, settings.radius()));

        // Warm up the JIT on a throwaway run, then measure from clean land.
        SpreadEngine warmup = FesteringLand.newEngine(level, () -> settings);
        long base = level.getGameTime() + 1_000_000L;
        for (long t = 0; t < 20L * settings.intervalTicks(); t++) {
            warmup.tick(base + t);
        }
        chunks.forEach(chunk -> chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION));

        SpreadEngine engine = FesteringLand.newEngine(level, () -> settings);
        base += 100L * settings.intervalTicks();
        long ticks = 60L * settings.intervalTicks();
        long[] nanos = new long[(int) ticks];
        int working = 0;
        long workingNanos = 0;
        for (int t = 0; t < ticks; t++) {
            long start = System.nanoTime();
            SpreadEngine.TickWork work = engine.tick(base + t);
            nanos[t] = System.nanoTime() - start;
            if (work.visits() > 0) {
                working++;
                workingNanos += nanos[t];
            }
        }
        out.add(summary("live spread, 60 intervals from clean land", nanos, working, workingNanos, settings.intervalTicks()));
        int festered = 0;
        for (LevelChunk chunk : chunks) {
            ChunkCorruption state = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
            festered += state != null && state.level() > 0 ? 1 : 0;
        }
        out.add("festered chunks after 60 intervals: " + festered);

        // Catch-up: every loaded chunk loads again after a long absence with clean land.
        chunks.forEach(chunk -> chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION));
        SpreadEngine catchUp = FesteringLand.newEngine(level, () -> settings);
        chunks.forEach(chunk -> catchUp.chunkLoaded(chunk.getPos().toLong()));
        long later = base + 1000L * settings.intervalTicks();
        List<Long> catchUpNanos = new ArrayList<>();
        while (catchUp.pendingCatchUps() > 0) {
            long start = System.nanoTime();
            catchUp.tick(later++);
            catchUpNanos.add(System.nanoTime() - start);
        }
        long[] catchUpArray = catchUpNanos.stream().mapToLong(Long::longValue).toArray();
        out.add(summary("catch-up of " + chunks.size() + " chunks", catchUpArray, catchUpArray.length,
                Arrays.stream(catchUpArray).sum(), 0));

        for (BlockPos pos : nidi) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        chunks.forEach(chunk -> {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
        });

        out.forEach(line -> Remission.LOGGER.info("[spread benchmark] {}", line));
        try {
            Files.write(Path.of("spread-benchmark-" + name + ".txt"), out);
        } catch (IOException e) {
            Remission.LOGGER.warn("Could not write spread-benchmark.txt", e);
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

    /**
     * Default spread with a surge running from the first nidus (through the level's own controller, so the engine reads
     * its factor as in play), then one surge of a separate controller over the same land with four players in its area.
     */
    private static void measureSurge(GameTestHelper helper, int offset) {
        ServerLevel level = helper.getLevel();
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int baseX = origin.x + offset;
        int baseZ = origin.z + offset;
        List<String> out = new ArrayList<>();
        out.add("== surge");
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                level.setChunkForced(baseX + x, baseZ + z, true);
                chunks.add(level.getChunk(baseX + x, baseZ + z));
            }
        }
        List<BlockPos> nidi = new ArrayList<>();
        int spacing = SIDE / 4;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int chunkX = baseX + spacing / 2 + i * spacing;
                int chunkZ = baseZ + spacing / 2 + j * spacing;
                BlockPos pos = new BlockPos(chunkX * 16 + 8, level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                        chunkX * 16 + 8, chunkZ * 16 + 8), chunkZ * 16 + 8);
                level.setBlockAndUpdate(pos, RemissionBlocks.NIDUS.get().defaultBlockState());
                nidi.add(pos);
            }
        }
        BlockPos surging = nidi.get(5);
        SpreadSettings settings = SpreadSettings.fromConfig();
        SurgeController surges = Surges.controller(level);
        long base = level.getGameTime() + 2_000_000L;
        try {
            // Live spread from clean land with the surge running, against the default case's figures.
            surges.stop(base);
            surges.start(surging, 1, base);
            surges.tick(surges.current().startTime());
            SpreadEngine engine = FesteringLand.newEngine(level, () -> settings);
            long ticks = 60L * settings.intervalTicks();
            long[] nanos = new long[(int) ticks];
            int working = 0;
            long workingNanos = 0;
            for (int t = 0; t < ticks; t++) {
                long start = System.nanoTime();
                SpreadEngine.TickWork work = engine.tick(base + t);
                nanos[t] = System.nanoTime() - start;
                if (work.visits() > 0) {
                    working++;
                    workingNanos += nanos[t];
                }
            }
            out.add(summary("live spread with a surge from one of 16 nidi, 60 intervals", nanos, working, workingNanos,
                    settings.intervalTicks()));
            surges.stop(base + ticks);

            // A whole surge of a separate controller: festering land, four players around the surging nidus.
            chunks.forEach(chunk -> chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(30, 0L)));
            List<ServerPlayer> players = new ArrayList<>();
            for (int[] at : new int[][] {{-40, 0}, {40, 0}, {0, -40}, {0, 40}}) {
                int x = surging.getX() + at[0];
                int z = surging.getZ() + at[1];
                players.add(SurgeTests.player(helper, new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z)));
            }
            SurgeSettings config = SurgeSettings.fromConfig();
            SurgeController measured = new SurgeController(level, new SurgeData(), () -> config, () -> settings,
                    new SurgeController.Players() {
                        @Override
                        public List<ServerPlayer> inLevel() {
                            return players;
                        }

                        @Override
                        public ServerPlayer byId(UUID id) {
                            return players.stream().filter(p -> p.getUUID().equals(id)).findFirst().orElse(null);
                        }
                    });
            long now = base + 10_000_000L;
            measured.start(surging, 1, now);
            long end = measured.current().endTime();
            List<Long> surgeNanos = new ArrayList<>();
            while (measured.current() != null && now <= end + 1) {
                long start = System.nanoTime();
                measured.tick(now++);
                surgeNanos.add(System.nanoTime() - start);
            }
            long[] surgeArray = surgeNanos.stream().mapToLong(Long::longValue).toArray();
            AABB area = new AABB(chunks.getFirst().getPos().getMinBlockX(), level.getMinBuildHeight(),
                    chunks.getFirst().getPos().getMinBlockZ(), chunks.getLast().getPos().getMaxBlockX() + 1,
                    level.getMaxBuildHeight(), chunks.getLast().getPos().getMaxBlockZ() + 1);
            List<Entity> made = level.getEntities((Entity) null, area, e -> e instanceof Mob || e instanceof MiasmaCloud);
            out.add(String.format("%s; %d hosts and clouds in the land afterwards", summary(
                    "surge controller, warning and whole surge with 4 players in the area (" + config + ")",
                    surgeArray, surgeArray.length, Arrays.stream(surgeArray).sum(), 0), made.size()));
            made.forEach(Entity::discard);
        } finally {
            surges.stop(level.getGameTime());
            for (BlockPos pos : nidi) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            chunks.forEach(chunk -> {
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
            });
        }
        out.forEach(line -> Remission.LOGGER.info("[spread benchmark] {}", line));
        try {
            Files.write(Path.of("spread-benchmark-surge.txt"), out);
        } catch (IOException e) {
            Remission.LOGGER.warn("Could not write spread-benchmark-surge.txt", e);
        }
        helper.succeed();
    }
}
