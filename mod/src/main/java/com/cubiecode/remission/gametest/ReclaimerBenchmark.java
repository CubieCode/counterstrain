package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.purification.ReclaimerBlockEntity;
import com.cubiecode.remission.purification.ReclaimerSettings;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.world.ChunkCorruption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Server cost of a reclaimer (P7.4) at the default radius (4, 81 chunks), for the P7.4 measurement. Registered only when
 * the {@code REMISSION_RECLAIMER_BENCHMARK} environment variable is set, so normal gametest runs skip it. It force-loads
 * the reclaimer's reach away from the test area, sets every chunk festering with a live nidus at the edge, and times
 * {@link ReclaimerBlockEntity#operate}: operations that drain a chunk (the survey of all 81 chunks, the drain and a
 * second survey; chunks entering remission included), and operations with nothing to drain. The chunk cleanse those
 * remission entries queue runs in P5.2's shared, already budgeted purification engine and is not counted here. Writes
 * {@code reclaimer-benchmark.txt}.
 */
@GameTestHolder(Remission.MOD_ID)
public final class ReclaimerBenchmark {
    private static final int OFFSET = 260;
    private static final int RADIUS = 4;
    private static final int ROUNDS = 5;

    private ReclaimerBenchmark() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> benchmarks() {
        if (System.getenv("REMISSION_RECLAIMER_BENCHMARK") == null) {
            return List.of();
        }
        return List.of(new TestFunction("reclaimerBenchmark", "remission.reclaimerbenchmark", "remission:empty", 2000, 0L, true,
                ReclaimerBenchmark::run));
    }

    private static void run(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(origin.x + OFFSET, origin.z + OFFSET);
        List<LevelChunk> chunks = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                chunks.add(level.getChunk(centre.x + dx, centre.z + dz));
            }
        }
        BlockPos at = new BlockPos(centre.getBlockX(8), 100, centre.getBlockZ(8));
        BlockPos nidus = new BlockPos(centre.getBlockX(8) + RADIUS * 16, 100, centre.getBlockZ(8));
        level.setBlock(at, RemissionBlocks.RECLAIMER.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(nidus, RemissionBlocks.NIDUS.get().defaultBlockState(), Block.UPDATE_ALL);
        ReclaimerBlockEntity reclaimer = (ReclaimerBlockEntity) level.getBlockEntity(at);
        ReclaimerSettings settings = ReclaimerSettings.fromConfig();
        ReclaimerSettings free = new ReclaimerSettings(RADIUS, settings.intervalTicks(), settings.drainStep(), 0,
                settings.energyCapacity(), settings.maxEnergyInput(), 1_000_000, 0);
        reclaimer.items().setStackInSlot(0, new ItemStack(RemissionItems.PURIFIER_FUEL.get()));
        List<String> out = new ArrayList<>();
        try {
            out.add("radius " + RADIUS + " (" + chunks.size() + " chunks), drainStep " + settings.drainStep()
                    + ", intervalTicks " + settings.intervalTicks());
            for (int round = 0; round < ROUNDS; round++) {
                chunks.forEach(chunk -> chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(100, 0L)));
                chunks.forEach(chunk -> chunk.removeData(RemissionAttachments.CHUNK_REMISSION));
                int operations = 0;
                long start = System.nanoTime();
                while (reclaimer.operate(level, free) == ReclaimerBlockEntity.Status.RUNNING) {
                    operations++;
                }
                long draining = System.nanoTime() - start;
                // The last call (held by the nidus) is in the time too; it is one survey.
                long idleStart = System.nanoTime();
                for (int i = 0; i < 1000; i++) {
                    reclaimer.operate(level, free);
                }
                long idle = System.nanoTime() - idleStart;
                double perOperation = draining / 1000.0 / (operations + 1);
                out.add(String.format("round %d: %d draining operations, %.1f us each (%.2f us per tick at intervalTicks %d);"
                                + " held/clear operation %.1f us; status %s, %d in remission",
                        round, operations, perOperation, perOperation / settings.intervalTicks(), settings.intervalTicks(),
                        idle / 1000.0 / 1000, reclaimer.status(), reclaimer.reclaimed()));
                Purification.engine(level).tick(Integer.MAX_VALUE);
            }
        } finally {
            level.setBlock(nidus, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            for (LevelChunk chunk : chunks) {
                Cleansing.cleanseNow(level, chunk, PurificationCause.OTHER);
                Purification.engine(level).unloaded(chunk.getPos());
                chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
                chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
                chunk.removeData(RemissionAttachments.FESTERED_STAGE);
                level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
            }
        }
        out.forEach(line -> Remission.LOGGER.info("[reclaimer benchmark] {}", line));
        try {
            Files.write(Path.of("reclaimer-benchmark.txt"), out);
        } catch (IOException e) {
            Remission.LOGGER.warn("Could not write reclaimer-benchmark.txt", e);
        }
        helper.succeed();
    }
}
