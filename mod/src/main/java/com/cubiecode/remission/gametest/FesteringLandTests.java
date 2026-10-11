package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.Nidus;
import com.cubiecode.remission.world.NidusIndex;
import com.cubiecode.remission.world.SpreadEngine;
import com.cubiecode.remission.world.SpreadSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Chunk corruption, the nidus index and the spread engine (P4.1). Engine tests run on an in-memory land. */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FesteringLandTests {
    // Fast rates so a few hundred ticks cover several passes: radius (100 - 40) / 15 + 1 = 5.
    static final SpreadSettings SETTINGS = new SpreadSettings(20, 10, 5, 15, 40, 50, 8);

    private FesteringLandTests() {
    }

    /** In-memory land: a square of loaded chunks, every spread attempt passes unless {@code blocked} matches its target. */
    static final class MapLand implements SpreadEngine.Land {
        final Map<Long, ChunkCorruption> chunks = new HashMap<>();
        final List<Nidus> nidi = new ArrayList<>();
        final Set<Long> destroyed = new HashSet<>();
        final int half;
        LongPredicate blocked = chunk -> false;

        MapLand(int half) {
            this.half = half;
        }

        @Override
        public boolean isLoaded(long chunk) {
            return Math.abs(ChunkPos.getX(chunk)) <= half && Math.abs(ChunkPos.getZ(chunk)) <= half;
        }

        @Override
        public ChunkCorruption get(long chunk) {
            return chunks.getOrDefault(chunk, ChunkCorruption.NONE);
        }

        @Override
        public void set(long chunk, ChunkCorruption state) {
            chunks.put(chunk, state);
        }

        @Override
        public int spreadAttempt(long from, long to, int amount) {
            return blocked.test(to) ? 0 : amount;
        }

        @Override
        public List<Nidus> nidi() {
            return List.copyOf(nidi);
        }

        @Override
        public boolean validate(Nidus nidus) {
            if (destroyed.contains(nidus.pos().asLong())) {
                nidi.remove(nidus);
                return false;
            }
            return true;
        }

        Map<Long, Integer> levels() {
            Map<Long, Integer> levels = new HashMap<>();
            chunks.forEach((chunk, state) -> levels.put(chunk, state.level()));
            return levels;
        }
    }

    static Nidus nidusInChunk(int chunkX, int chunkZ) {
        return new Nidus(new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8), 0L);
    }

    static int level(MapLand land, int chunkX, int chunkZ) {
        return land.get(ChunkPos.asLong(chunkX, chunkZ)).level();
    }

    static void run(SpreadEngine engine, long from, long to) {
        for (long t = from; t < to; t++) {
            engine.tick(t);
        }
    }

    static MapLand threeNidi() {
        MapLand land = new MapLand(12);
        land.nidi.add(nidusInChunk(0, 0));
        land.nidi.add(nidusInChunk(3, 1));
        land.nidi.add(nidusInChunk(-6, -4));
        return land;
    }

    @GameTest(template = "empty")
    public static void spreadIsDeterministic(GameTestHelper helper) {
        MapLand first = threeNidi();
        MapLand second = threeNidi();
        run(new SpreadEngine(first, () -> SETTINGS), 0, 600);
        run(new SpreadEngine(second, () -> SETTINGS), 0, 600);
        helper.assertValueEqual(first.levels(), second.levels(), "chunk levels of two identical runs");

        helper.assertValueEqual(level(first, 0, 0), ChunkCorruption.MAX_LEVEL, "nidus chunk level");
        helper.assertTrue(level(first, 1, 0) > 0, "neighbour of a nidus did not fester");
        int radius = SETTINGS.radius();
        helper.assertValueEqual(radius, 5, "radius");
        for (Map.Entry<Long, Integer> entry : first.levels().entrySet()) {
            int x = ChunkPos.getX(entry.getKey());
            int z = ChunkPos.getZ(entry.getKey());
            int nearest = Integer.MAX_VALUE;
            for (Nidus nidus : first.nidi) {
                nearest = Math.min(nearest, Math.abs(x - ChunkPos.getX(nidus.chunk())) + Math.abs(z - ChunkPos.getZ(nidus.chunk())));
            }
            helper.assertTrue(nearest <= radius, "chunk " + x + "," + z + " festered beyond the radius");
            helper.assertTrue(entry.getValue() <= SETTINGS.target(nearest), "chunk " + x + "," + z + " above its target");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void passStaysWithinBudget(GameTestHelper helper) {
        MapLand land = threeNidi();
        SpreadEngine engine = new SpreadEngine(land, () -> SETTINGS);
        int perPass = 3 * (2 * 5 * 5 + 2 * 5 + 1);
        int passTicks = 0;
        int visits = 0;
        for (long t = 0; t < SETTINGS.intervalTicks(); t++) {
            SpreadEngine.TickWork work = engine.tick(t);
            helper.assertTrue(work.visits() <= SETTINGS.chunkBudget(), "visits over budget at tick " + t + ": " + work.visits());
            if (work.visits() > 0) {
                passTicks++;
            }
            visits += work.visits();
        }
        helper.assertValueEqual(visits, perPass, "visits in one pass");
        helper.assertValueEqual(passTicks, (perPass + SETTINGS.chunkBudget() - 1) / SETTINGS.chunkBudget(), "ticks one pass took");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancelledSpreadHoldsTheLine(GameTestHelper helper) {
        MapLand land = new MapLand(12);
        land.nidi.add(nidusInChunk(0, 0));
        land.blocked = chunk -> ChunkPos.getX(chunk) == 2;
        run(new SpreadEngine(land, () -> SETTINGS), 0, 2000);
        land.levels().forEach((chunk, level) ->
                helper.assertTrue(ChunkPos.getX(chunk) < 2 || level == 0, "festered past the firebreak at " + new ChunkPos(chunk)));
        helper.assertTrue(level(land, -2, 0) > 0, "spread away from the firebreak");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void destroyedNidusStopsSpreading(GameTestHelper helper) {
        MapLand land = new MapLand(12);
        Nidus nidus = nidusInChunk(0, 0);
        land.nidi.add(nidus);
        SpreadEngine engine = new SpreadEngine(land, () -> SETTINGS);
        run(engine, 0, 200);
        land.destroyed.add(nidus.pos().asLong());
        run(engine, 200, 220);
        Map<Long, Integer> afterBreak = land.levels();
        helper.assertTrue(level(land, 0, 0) > 0, "land around the nidus was cleansed");
        run(engine, 220, 1000);
        helper.assertValueEqual(land.levels(), afterBreak, "levels after the nidus was destroyed");
        helper.assertTrue(land.nidi.isEmpty(), "destroyed nidus still listed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void catchUpFastForwards(GameTestHelper helper) {
        MapLand land = new MapLand(12);
        land.nidi.add(new Nidus(new BlockPos(8, 64, 8), 100L));
        SpreadEngine engine = new SpreadEngine(land, () -> SETTINGS);
        long near = ChunkPos.asLong(1, 0);
        long far = ChunkPos.asLong(3, 0);
        long outside = ChunkPos.asLong(9, 0);
        land.set(near, new ChunkCorruption(4, 160L));

        long now = 160L + 3 * SETTINGS.intervalTicks() + 7;
        engine.catchUp(near, now, SETTINGS);
        helper.assertValueEqual(land.get(near), new ChunkCorruption(4 + 3 * SETTINGS.spreadStep(), now), "near chunk after 3 missed passes");
        // Never credited from before the nidus existed, and capped at its target.
        engine.catchUp(far, 100L + 1000 * SETTINGS.intervalTicks(), SETTINGS);
        helper.assertValueEqual(land.get(far).level(), SETTINGS.target(3), "far chunk capped at its target");
        engine.catchUp(outside, now, SETTINGS);
        helper.assertFalse(land.chunks.containsKey(outside), "chunk out of reach changed");
        long nidusChunk = ChunkPos.asLong(0, 0);
        engine.catchUp(nidusChunk, 100L + 2 * SETTINGS.intervalTicks(), SETTINGS);
        helper.assertValueEqual(land.get(nidusChunk).level(), 2 * SETTINGS.nidusGrowth(), "nidus chunk after 2 passes");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void catchUpIsBoundedPerTick(GameTestHelper helper) {
        MapLand land = new MapLand(12);
        land.nidi.add(nidusInChunk(0, 0));
        SpreadEngine engine = new SpreadEngine(land, () -> SETTINGS);
        for (int x = -10; x < 10; x++) {
            for (int z = -10; z < 10; z++) {
                engine.chunkLoaded(ChunkPos.asLong(x, z));
            }
        }
        long t = 10_000;
        int ticks = 0;
        while (engine.pendingCatchUps() > 0) {
            int before = engine.pendingCatchUps();
            SpreadEngine.TickWork work = engine.tick(t++);
            ticks++;
            helper.assertTrue(work.catchUps() <= SETTINGS.catchUpBudget(), "catch-up over budget: " + work.catchUps());
            helper.assertValueEqual(before - engine.pendingCatchUps(), work.catchUps(), "queue drained by the work done");
        }
        helper.assertValueEqual(ticks, 400 / SETTINGS.catchUpBudget(), "ticks to drain 400 loaded chunks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nidusIndexFollowsTheBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        NidusIndex index = NidusIndex.get(level);
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCorruption before = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);

        helper.setBlock(1, 1, 1, RemissionBlocks.NIDUS.get());
        helper.assertTrue(index.contains(pos), "placed nidus not indexed");
        helper.assertBlockEntityData(new BlockPos(1, 1, 1), be -> be.getType() != null, () -> "nidus block entity");
        chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(37, level.getGameTime()));

        helper.destroyBlock(new BlockPos(1, 1, 1));
        helper.assertFalse(index.contains(pos), "destroyed nidus still indexed");
        helper.assertValueEqual(chunk.getData(RemissionAttachments.CHUNK_CORRUPTION).level(), 37, "land level after the nidus broke");

        if (before == null) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, before);
        }
        helper.setBlock(1, 1, 1, Blocks.AIR);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stateSurvivesSaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NidusIndex index = new NidusIndex();
        index.add(new BlockPos(5, -20, 900), 42L);
        index.add(new BlockPos(-30000, 300, 7), 7L);
        helper.assertFalse(index.add(new BlockPos(5, -20, 900), 99L), "duplicate nidus added");
        CompoundTag saved = index.save(new CompoundTag(), level.registryAccess());
        helper.assertValueEqual(NidusIndex.load(saved, level.registryAccess()).nidi(), index.nidi(), "nidi after reload");

        ChunkCorruption state = new ChunkCorruption(63, 123_456_789_000L);
        Tag encoded = ChunkCorruption.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        helper.assertValueEqual(ChunkCorruption.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow(), state, "chunk state after reload");
        helper.succeed();
    }
}
