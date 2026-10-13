package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.NidusDamagedEvent;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.surge.SurgeHosts;
import com.cubiecode.remission.surge.Surges;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.NidusBlockEntity;
import com.cubiecode.remission.world.NidusDefenceSettings;
import com.cubiecode.remission.world.NidusEncounters;
import com.cubiecode.remission.world.NidusRally;
import com.cubiecode.remission.world.NidusSettings;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Server tick cost of rallying (P4.13): a few hundred hosts in a nidus's festering land, first idle, then rallying to
 * it while it is under attack. Registered only when the {@code REMISSION_NIDUS_RALLY} environment variable is set: a
 * comma-separated list of {@code rallyBatch} values (default {@code 4,16}), one trial each, in its own batch.
 *
 * <p>Each trial: the {@code [nidi]} config as loaded but with that {@code rallyBatch} and sympathetic surges off, a
 * nidus on a 5x5-chunk square of stage 4 festering land far from the test grid, and {@link #HOSTS} husk hosts at random
 * spots {@link #MIN_DISTANCE} to {@code rallyRadius - 2} blocks from it. After {@link #SETTLE_TICKS} it samples the
 * server's mean tick time (over the last 100 ticks) every 100 ticks for {@link #PHASE_TICKS} with the hosts idle, then
 * strikes the nidus (1 damage, every 40 ticks so it stays under attack, never near a threshold) for another
 * {@link #PHASE_TICKS}, sampling the same and timing each rally batch. It writes {@code nidus-rally-<batch>.txt} (mean
 * and worst tick time per phase, rally batch time, hosts rallying, arrived and left alone) and logs it.
 */
@GameTestHolder(Remission.MOD_ID)
public final class NidusRallyBenchmark {
    public static final int HOSTS = 300;
    private static final int MIN_DISTANCE = 6;
    private static final int SETTLE_TICKS = 200;
    private static final int PHASE_TICKS = 1200;
    private static final int RADIUS_CHUNKS = 2;

    private NidusRallyBenchmark() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> trials() {
        String env = System.getenv("REMISSION_NIDUS_RALLY");
        if (env == null) {
            return List.of();
        }
        List<TestFunction> trials = new ArrayList<>();
        int i = 0;
        for (String value : (env.isBlank() ? "4,16" : env).split(",")) {
            int batch = Integer.parseInt(value.trim());
            int trial = i++;
            trials.add(new TestFunction("nidusRally" + batch, "remission.nidusrally_" + batch, "remission:empty",
                    SETTLE_TICKS + 2 * PHASE_TICKS + 400, 0L, true, helper -> rally(helper, trial, batch)));
        }
        return trials;
    }

    private static final class Run {
        final List<String> out = new ArrayList<>();
        final List<Double> idle = new ArrayList<>();
        final List<Double> rallying = new ArrayList<>();
        final List<Double> batches = new ArrayList<>();
        int searches;
        int maxRallying;
        /** Game time the hosts were spawned; none yet until then. */
        long start = Long.MAX_VALUE;
        boolean done;
    }

    private static void rally(GameTestHelper helper, int trial, int batch) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        NidusSettings settings = NidusSettings.current();
        NidusDefenceSettings config = NidusDefenceSettings.current();
        NidusDefenceSettings defence = new NidusDefenceSettings(true, config.rallyRadius(), batch, false,
                config.sympatheticRadius(), config.sympatheticChance(), config.sympatheticIntensity());
        NidusDefenceSettings.override(defence);

        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(own.x + 600 + trial * 40, own.z + 200);
        List<LevelChunk> chunks = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                level.setChunkForced(centre.x + dx, centre.z + dz, true);
                LevelChunk chunk = level.getChunk(centre.x + dx, centre.z + dz);
                chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(90, level.getGameTime()));
                chunks.add(chunk);
            }
        }
        BlockPos nidus = ground(level, centre.getMiddleBlockX(), centre.getMiddleBlockZ());
        level.setBlockAndUpdate(nidus, RemissionBlocks.NIDUS.get().defaultBlockState());
        ServerPlayer player = SurgeTests.player(helper, helper.absolutePos(BlockPos.ZERO));
        NidusEncounters.addTestPlayer(nidus, player);
        NidusBlockEntity entity = (NidusBlockEntity) level.getBlockEntity(nidus);
        Run run = new Run();
        run.out.add(String.format("trial %d: %d husk hosts %d-%d blocks from a nidus; [nidi] rally %s, rallyRadius %d, rallyBatch %d",
                trial, HOSTS, MIN_DISTANCE, defence.rallyRadius() - 2, defence.rally(), defence.rallyRadius(), batch));

        helper.startSequence()
                .thenWaitUntil(() -> chunks.forEach(chunk -> helper.assertTrue(
                        level.isPositionEntityTicking(chunk.getPos().getWorldPosition()), "chunk is not entity-ticking")))
                .thenExecute(() -> {
                    spawnHosts(level, nidus, defence.rallyRadius() - 2);
                    run.start = level.getGameTime();
                });
        helper.onEachTick(() -> {
            if (run.done || level.getGameTime() < run.start) {
                return;
            }
            long now = level.getGameTime();
            long t = now - run.start;
            boolean attacking = t >= SETTLE_TICKS + PHASE_TICKS;
            if (attacking && (t - SETTLE_TICKS - PHASE_TICKS) % 40 == 0) {
                entity.damage(level, NidusDamagedEvent.Kind.STRIKE, player, null, 1.0F, settings, now);
            }
            if (t > SETTLE_TICKS && t % 100 == 0) {
                double mspt = server.getAverageTickTimeNanos() / 1.0E6;
                // Each sample covers the 100 ticks before it, so the first sample of a phase is skipped.
                if (t > SETTLE_TICKS + 100 && t <= SETTLE_TICKS + PHASE_TICKS) {
                    run.idle.add(mspt);
                } else if (t > SETTLE_TICKS + PHASE_TICKS + 100) {
                    run.rallying.add(mspt);
                }
            }
            NidusRally rally = entity.rally();
            if (attacking && now % NidusRally.RALLY_TICKS == NidusRally.RALLY_TICKS / 2 && rally.lastSearches() > 0) {
                run.batches.add(rally.lastBatchNanos() / 1.0E6);
                run.searches += rally.lastSearches();
            }
            run.maxRallying = Math.max(run.maxRallying, rally.rallying().size());
            if (t >= SETTLE_TICKS + 2L * PHASE_TICKS) {
                run.done = true;
                Vec3 at = Vec3.atCenterOf(nidus);
                List<Mob> hosts = level.getEntitiesOfClass(Mob.class, area(nidus), Mob::isAlive);
                long arrived = hosts.stream().filter(host -> host.distanceToSqr(at) <= Math.pow(NidusRally.ARRIVED + 1.5, 2)).count();
                run.out.add(String.format("idle hosts: mean tick %.2f ms (worst 100-tick mean %.2f ms) over %d samples",
                        mean(run.idle), max(run.idle), run.idle.size()));
                run.out.add(String.format("rallying: mean tick %.2f ms (worst 100-tick mean %.2f ms) over %d samples",
                        mean(run.rallying), max(run.rallying), run.rallying.size()));
                run.out.add(String.format("rally batches: %d with searches, %d path searches, mean %.3f ms, worst %.3f ms per batch",
                        run.batches.size(), run.searches, mean(run.batches), max(run.batches)));
                run.out.add(String.format("hosts alive %d, rallying at the end %d (most %d), within %.1f blocks %d, left alone %d",
                        hosts.size(), rally.rallying().size(), run.maxRallying, NidusRally.ARRIVED + 1.5, arrived,
                        rally.leftAlone().size()));
                int radius = defence.rallyRadius();
                long beyond = hosts.stream().filter(host -> !rally.rallying().contains(host.getUUID())
                        && host.distanceToSqr(at) > radius * radius).count();
                long near = hosts.stream().filter(host -> !rally.rallying().contains(host.getUUID())
                        && host.distanceToSqr(at) <= NidusRally.ARRIVED * NidusRally.ARRIVED).count();
                long airborne = hosts.stream().filter(host -> !rally.rallying().contains(host.getUUID()) && !host.onGround()).count();
                run.out.add(String.format("not rallying: beyond the radius %d, already within %.0f blocks %d, not on the ground %d",
                        beyond, NidusRally.ARRIVED, near, airborne));
                finish(helper, level, nidus, chunks, batch, run);
            }
        });
    }

    private static void spawnHosts(ServerLevel level, BlockPos nidus, int maxDistance) {
        RandomSource random = RandomSource.create(nidus.asLong());
        int spawned = 0;
        while (spawned < HOSTS) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = MIN_DISTANCE + random.nextDouble() * (maxDistance - MIN_DISTANCE);
            BlockPos pos = ground(level, nidus.getX() + (int) Math.round(Math.cos(angle) * distance),
                    nidus.getZ() + (int) Math.round(Math.sin(angle) * distance));
            Mob host = SurgeHosts.spawn(level, EntityType.HUSK, pos, 50, random, false);
            if (host != null) {
                host.setPersistenceRequired();
                spawned++;
            }
        }
    }

    private static void finish(GameTestHelper helper, ServerLevel level, BlockPos nidus, List<LevelChunk> chunks, int batch, Run run) {
        run.out.forEach(line -> Remission.LOGGER.info("[nidus rally] {}", line));
        try {
            Files.write(Path.of("nidus-rally-" + batch + ".txt"), run.out);
        } catch (IOException e) {
            Remission.LOGGER.warn("[nidus rally] could not write the result", e);
        }
        Surges.controller(level).stop(level.getGameTime());
        NidusEncounters.removeTestPlayers(nidus);
        level.getEntities((Entity) null, area(nidus), entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
        if (level.getBlockState(nidus).is(RemissionBlocks.NIDUS.get())) {
            level.removeBlock(nidus, false);
        }
        for (LevelChunk chunk : chunks) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
            level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
        }
        NidusDefenceSettings.override(null);
        helper.succeed();
    }

    private static BlockPos ground(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    private static AABB area(BlockPos nidus) {
        int reach = RADIUS_CHUNKS * 16 + 16;
        return new AABB(nidus).inflate(reach, 64, reach);
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }
}
