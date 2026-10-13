package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.purification.RemissionZoneEngine;
import com.cubiecode.remission.purification.RemissionZoneSettings;
import com.cubiecode.remission.purification.Wildlife;
import com.cubiecode.remission.registry.RemissionAttachments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Server cost of the remission zone passes (P5.5) for the P5.5 measurement. Registered only when the
 * {@code REMISSION_ZONE_BENCHMARK} environment variable is set, so normal gametest runs skip it. It force-loads 9 by 9
 * plains chunks in remission with a grass platform each, away from the test area, waits until their entities tick, and
 * times {@link RemissionZoneEngine#tick} with the configured settings and budget for every game tick of
 * {@code regrowthTicks} (regrowth from bare to full, wildlife returning to the cap), then for {@code wildlifeIntervalTicks} more with every
 * chunk regrown and at the cap (the steady cost while the zones stay loaded). Writes {@code remission-zone-benchmark.txt}.
 */
@GameTestHolder(Remission.MOD_ID)
public final class RemissionZoneBenchmark {
    private static final int OFFSET = 320;
    private static final int RADIUS = 4;
    private static final int BASE_Y = 100;

    private RemissionZoneBenchmark() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> benchmarks() {
        if (System.getenv("REMISSION_ZONE_BENCHMARK") == null) {
            return List.of();
        }
        return List.of(new TestFunction("remissionZoneBenchmark", "remission.remissionzonebenchmark", "remission:empty", 2000, 0L,
                true, RemissionZoneBenchmark::run));
    }

    private static void run(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        Holder<Biome> plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        List<LevelChunk> chunks = new ArrayList<>();
        RemissionZoneEngine engine = new RemissionZoneEngine(level);
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(origin.x + OFFSET + dx, origin.z - OFFSET + dz);
                level.setChunkForced(pos.x, pos.z, true);
                LevelChunk chunk = level.getChunk(pos.x, pos.z);
                chunks.add(chunk);
                platform(level, pos, Blocks.GRASS_BLOCK);
                chunk.fillBiomesFromNoise((x, y, z, sampler) -> plains, level.getChunkSource().randomState().sampler());
                chunk.setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
                engine.track(pos);
            }
        }
        // Wildlife skips chunks whose entities are not ticking yet, so measure once they all are.
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (!done[0] && chunks.stream().allMatch(chunk -> level.isPositionEntityTicking(chunk.getPos().getMiddleBlockPosition(BASE_Y)))) {
                done[0] = true;
                measureAll(helper, level, engine, chunks);
            }
        });
    }

    private static void measureAll(GameTestHelper helper, ServerLevel level, RemissionZoneEngine engine, List<LevelChunk> chunks) {
        RemissionZoneSettings settings = RemissionZoneSettings.fromConfig();
        long entered = level.getGameTime();
        chunks.forEach(chunk -> chunk.setData(RemissionAttachments.CHUNK_REMISSION, entered));
        List<String> out = new ArrayList<>();
        try {
            RandomSource random = RandomSource.create(7L);
            out.add(String.format("%d chunks in remission; regrowthTicks %d, density %.2f, saplingChance %.2f, wildlifeIntervalTicks %d,"
                            + " wildlifeCap %d, budget %d", chunks.size(), settings.regrowthTicks(), settings.regrowthDensity(),
                    settings.saplingChance(), settings.wildlifeIntervalTicks(), settings.wildlifeCap(), settings.budget()));
            out.add("regrowing: " + measure(engine, settings, random, entered + 1, settings.regrowthTicks()));
            out.add("steady:    " + measure(engine, settings, random, entered + settings.regrowthTicks() + 1,
                    settings.wildlifeIntervalTicks()));
            int plants = 0;
            int animals = 0;
            for (LevelChunk chunk : chunks) {
                ChunkPos pos = chunk.getPos();
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!level.getBlockState(new BlockPos(pos.getBlockX(x), BASE_Y + 1, pos.getBlockZ(z))).isAir()) {
                            plants++;
                        }
                    }
                }
                animals += Wildlife.creatures(level, pos);
            }
            out.add(String.format("result: %d plants (%.1f per chunk), %d animals (%.1f per chunk)", plants,
                    plants / (double) chunks.size(), animals, animals / (double) chunks.size()));
        } finally {
            BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
            for (LevelChunk chunk : chunks) {
                ChunkPos pos = chunk.getPos();
                level.getEntitiesOfClass(Mob.class, Wildlife.box(level, pos)).forEach(Mob::discard);
                for (int y = BASE_Y + 2; y >= BASE_Y; y--) {
                    clearLayer(level, pos, y);
                }
                chunk.fillBiomesFromNoise(source::getNoiseBiome, level.getChunkSource().randomState().sampler());
                chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
                level.setChunkForced(pos.x, pos.z, false);
            }
        }
        out.forEach(line -> Remission.LOGGER.info("[remission zone benchmark] {}", line));
        try {
            Files.write(Path.of("remission-zone-benchmark.txt"), out);
        } catch (IOException e) {
            Remission.LOGGER.warn("Could not write remission-zone-benchmark.txt", e);
        }
        helper.succeed();
    }

    /** Times {@code ticks} engine ticks from game time {@code from}; returns mean, p95, p99 and max per tick. */
    private static String measure(RemissionZoneEngine engine, RemissionZoneSettings settings, RandomSource random, long from,
                                  int ticks) {
        long[] nanos = new long[ticks];
        long units = 0;
        int worst = 0;
        for (int i = 0; i < ticks; i++) {
            long start = System.nanoTime();
            units += engine.tick(from + i, settings.budget(), settings, random);
            nanos[i] = System.nanoTime() - start;
            if (nanos[i] > nanos[worst]) {
                worst = i;
            }
        }
        double mean = Arrays.stream(nanos).average().orElse(0) / 1000.0;
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format("%d ticks, %.1f units/tick; %.2f us mean, %.2f us p95, %.2f us p99, %.1f us max per tick (tick %d);"
                        + " next worst %.1f us", ticks, units / (double) ticks, mean, sorted[(int) (ticks * 0.95)] / 1000.0,
                sorted[(int) (ticks * 0.99)] / 1000.0, sorted[ticks - 1] / 1000.0, worst, sorted[ticks - 2] / 1000.0);
    }

    private static void platform(ServerLevel level, ChunkPos pos, Block block) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(new BlockPos(pos.getBlockX(x), BASE_Y, pos.getBlockZ(z)), block.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void clearLayer(ServerLevel level, ChunkPos pos, int y) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(new BlockPos(pos.getBlockX(x), y, pos.getBlockZ(z)), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }
}
