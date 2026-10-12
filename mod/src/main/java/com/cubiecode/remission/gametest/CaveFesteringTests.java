package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesterMaps;
import com.cubiecode.remission.world.FesteringStages;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Cave festering (P4.6): blocks with a {@code remission:festers_into} entry that face air turn from the entry's
 * underground stage, rock inside the ground and ores stay, festering is complete down to
 * {@link FesteringStages#FULL_DEPTH_Y} and sparse below it, the result depends only on the blocks, an entry can turn it
 * off, and a chunk unloaded mid-way finishes after it loads again. Each test builds in its own chunk far east of the
 * test grid (beyond {@link FesteringStageTests}' chunks).
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CaveFesteringTests {
    private static final String BATCH = "cave_festering";
    /** Chunks east of the test's own chunk, one per test. */
    private static final int FAR = 112;
    /** Height of the bottom of the hollow cube; above {@link FesteringStages#FULL_DEPTH_Y}. */
    private static final int CUBE_Y = 10;

    private CaveFesteringTests() {
    }

    private static LevelChunk farChunk(GameTestHelper helper, int offset) {
        ServerLevel level = helper.getLevel();
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkPos pos = new ChunkPos(own.x + FAR + offset, own.z);
        level.setChunkForced(pos.x, pos.z, true);
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        chunk.removeData(RemissionAttachments.FESTERED_STAGE);
        return chunk;
    }

    private static void releaseFarChunk(ServerLevel level, LevelChunk chunk) {
        chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        chunk.removeData(RemissionAttachments.FESTERED_STAGE);
        level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
    }

    private static BlockPos at(LevelChunk chunk, int localX, int y, int localZ) {
        return new BlockPos(chunk.getPos().getBlockX(localX), y, chunk.getPos().getBlockZ(localZ));
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, state, 2);
        }
    }

    /**
     * A 9-block stone cube (local 3–11, {@link #CUBE_Y} up) with a 3-block hollow in its middle, a coal ore and a
     * deepslate block in the hollow's walls, and tuff on its outside; the rest of the chunk's space around it is air.
     * Returns the ore's position.
     */
    private static BlockPos buildHollowCube(ServerLevel level, LevelChunk chunk) {
        fill(level, at(chunk, 2, CUBE_Y - 1, 2), at(chunk, 12, CUBE_Y + 9, 12), Blocks.AIR.defaultBlockState());
        fill(level, at(chunk, 3, CUBE_Y, 3), at(chunk, 11, CUBE_Y + 8, 11), Blocks.STONE.defaultBlockState());
        fill(level, at(chunk, 6, CUBE_Y + 3, 6), at(chunk, 8, CUBE_Y + 5, 8), Blocks.AIR.defaultBlockState());
        BlockPos ore = at(chunk, 5, CUBE_Y + 4, 7);
        level.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), 2);
        level.setBlock(at(chunk, 9, CUBE_Y + 4, 7), Blocks.DEEPSLATE.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X), 2);
        level.setBlock(at(chunk, 3, CUBE_Y + 1, 7), Blocks.TUFF.defaultBlockState(), 2);
        return ore;
    }

    private static int festerCube(ServerLevel level, LevelChunk chunk, int stage, Function<BlockState, FesterMaps.FesterBlock> entries) {
        int changed = 0;
        for (int y = CUBE_Y - 1; y <= CUBE_Y + 9; y++) {
            changed += FesteringStages.festerCaves(level, chunk, y, stage, entries);
        }
        return changed;
    }

    private static FesterMaps.FesterBlock shipped(BlockState state) {
        return state.getBlockHolder().getData(FesterMaps.FESTERS_INTO);
    }

    private static boolean facesAir(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Positions in the cube (outside the hollow) that are not air. */
    private static List<BlockPos> cubeBlocks(ServerLevel level, LevelChunk chunk) {
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(at(chunk, 3, CUBE_Y, 3), at(chunk, 11, CUBE_Y + 8, 11))) {
            if (!level.getBlockState(pos).isAir()) {
                blocks.add(pos.immutable());
            }
        }
        return blocks;
    }

    private static boolean isFestered(BlockState state) {
        return state.is(RemissionBlocks.FESTERED_STONE.get()) || state.is(RemissionBlocks.FESTERED_DEEPSLATE.get());
    }

    /**
     * At stage 3 every cube block facing air (its outside and the hollow) festers through the shipped data map, each with
     * one event; rock inside the walls and the ore stay; deepslate keeps its axis; a second pass changes nothing.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void exposedBlocksFesterOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 0);
        try {
            BlockPos ore = buildHollowCube(level, chunk);
            List<BlockPos> blocks = cubeBlocks(level, chunk);
            Set<BlockPos> exposed = new HashSet<>();
            for (BlockPos pos : blocks) {
                if (facesAir(level, pos)) {
                    exposed.add(pos);
                }
            }
            List<BlockFesteredEvent> events = new ArrayList<>();
            Consumer<BlockFesteredEvent> listener = event -> {
                if (event.getLevel() == level && new ChunkPos(event.getPos()).equals(chunk.getPos())) {
                    events.add(event);
                }
            };
            NeoForge.EVENT_BUS.addListener(BlockFesteredEvent.class, listener);
            int changed;
            try {
                changed = festerCube(level, chunk, 3, CaveFesteringTests::shipped);
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
            int interior = 0;
            for (BlockPos pos : blocks) {
                BlockState state = level.getBlockState(pos);
                if (pos.equals(ore)) {
                    helper.assertTrue(state.is(Blocks.COAL_ORE), "ore in a cave wall stays: " + state);
                } else if (exposed.contains(pos)) {
                    helper.assertTrue(isFestered(state), "exposed block at " + pos.toShortString() + " festered: " + state);
                } else {
                    interior++;
                    helper.assertTrue(state.is(Blocks.STONE), "rock inside at " + pos.toShortString() + " stays: " + state);
                }
            }
            helper.assertTrue(interior > 0, "the cube has rock inside");
            BlockState deepslate = level.getBlockState(at(chunk, 9, CUBE_Y + 4, 7));
            helper.assertTrue(deepslate.is(RemissionBlocks.FESTERED_DEEPSLATE.get())
                    && deepslate.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X, "deepslate festers with its axis: " + deepslate);
            helper.assertTrue(level.getBlockState(at(chunk, 3, CUBE_Y + 1, 7)).is(RemissionBlocks.FESTERED_DEEPSLATE.get()), "tuff festers to deepslate");
            helper.assertValueEqual(changed, exposed.size() - 1, "blocks changed (every exposed block but the ore)");
            helper.assertValueEqual(events.size(), changed, "one event per replaced block");
            helper.assertValueEqual(festerCube(level, chunk, 3, CaveFesteringTests::shipped), 0, "blocks changed by a repeated pass");
        } finally {
            releaseFarChunk(level, chunk);
        }
        helper.succeed();
    }

    /** Shipped entries fester underground from stage 3; an entry's {@code underground_stage} moves the gate or turns it off. */
    @GameTest(template = "empty", batch = BATCH)
    public static void undergroundStageGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Contract: the field decodes as documented, with the default and the off value.
        FesterMaps.FesterBlock plain = decode("{\"block\": \"remission:festered_stone\", \"stage\": 2}");
        helper.assertValueEqual(plain.undergroundStage(), FesterMaps.UNDERGROUND_STAGE, "default underground stage");
        FesterMaps.FesterBlock early = decode("{\"block\": \"remission:festered_stone\", \"underground_stage\": 1}");
        FesterMaps.FesterBlock off = decode("{\"block\": \"remission:festered_stone\", \"stage\": 1, \"underground_stage\": 0}");
        helper.assertFalse(plain.festersUnderground(2), "stage 2 is below the default underground stage");
        helper.assertTrue(plain.festersUnderground(3), "stage 3 reaches it");
        helper.assertFalse(new FesterMaps.FesterBlock(Blocks.STONE, 4, 1).festersUnderground(3), "never below the entry's stage");
        helper.assertTrue(early.festersUnderground(1), "underground stage 1");
        helper.assertFalse(off.festersUnderground(Integer.MAX_VALUE), "underground stage 0 is off");
        // Shipped data: stone, tuff and deepslate entries; ores have none.
        FesterMaps.FesterBlock deepslate = shipped(Blocks.DEEPSLATE.defaultBlockState());
        helper.assertTrue(deepslate != null && deepslate.block() == RemissionBlocks.FESTERED_DEEPSLATE.get()
                && deepslate.stage() == 3 && deepslate.undergroundStage() == 3, "deepslate entry");
        FesterMaps.FesterBlock tuff = shipped(Blocks.TUFF.defaultBlockState());
        helper.assertTrue(tuff != null && tuff.block() == RemissionBlocks.FESTERED_DEEPSLATE.get() && tuff.stage() == 3, "tuff entry");
        helper.assertTrue(shipped(Blocks.COAL_ORE.defaultBlockState()) == null, "coal ore has no entry");
        helper.assertTrue(shipped(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()) == null, "deepslate ore has no entry");

        LevelChunk chunk = farChunk(helper, 1);
        try {
            for (int stage = 0; stage <= 4; stage++) {
                buildHollowCube(level, chunk);
                int changed = festerCube(level, chunk, stage, CaveFesteringTests::shipped);
                helper.assertTrue(stage >= 3 ? changed > 0 : changed == 0, "shipped map at stage " + stage + " changed " + changed);
            }
            buildHollowCube(level, chunk);
            helper.assertTrue(festerCube(level, chunk, 1, state -> early) > 0, "an entry at underground stage 1 festers at stage 1");
            buildHollowCube(level, chunk);
            helper.assertValueEqual(festerCube(level, chunk, 4, state -> state.is(Blocks.AIR) ? null : off), 0,
                    "entries at underground stage 0 never fester caves");
        } finally {
            releaseFarChunk(level, chunk);
        }
        helper.succeed();
    }

    /**
     * Pins the block each vanilla overworld stone festers into, through the surface pass (a column) and the cave pass
     * (a block with air below it), at stage 3.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void vanillaStonesFesterInto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 4);
        Map<Block, Block> expected = new LinkedHashMap<>();
        for (Block stone : List.of(Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE)) {
            expected.put(stone, RemissionBlocks.FESTERED_STONE.get());
        }
        expected.put(Blocks.DEEPSLATE, RemissionBlocks.FESTERED_DEEPSLATE.get());
        expected.put(Blocks.TUFF, RemissionBlocks.FESTERED_DEEPSLATE.get());
        try {
            int x = 1;
            for (Map.Entry<Block, Block> entry : expected.entrySet()) {
                // Surface: the top block of a column standing on the chunk's ground.
                BlockPos ground = at(chunk, x, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, 1) + 1, 1);
                level.setBlock(ground, entry.getKey().defaultBlockState(), 2);
                FesteringStages.festerColumn(level, chunk, x, 1, 3);
                helper.assertTrue(level.getBlockState(ground).is(entry.getValue()),
                        entry.getKey() + " on the surface festers into " + entry.getValue() + ": " + level.getBlockState(ground));
                level.setBlock(ground, Blocks.AIR.defaultBlockState(), 2);
                // Cave: a block hanging in the air, below a cover that keeps it off the surface.
                BlockPos cave = at(chunk, x, CUBE_Y, 5);
                level.setBlock(cave.above(), Blocks.BEDROCK.defaultBlockState(), 2);
                level.setBlock(cave, entry.getKey().defaultBlockState(), 2);
                FesteringStages.festerCaves(level, chunk, CUBE_Y, 3);
                helper.assertTrue(level.getBlockState(cave).is(entry.getValue()),
                        entry.getKey() + " in a cave festers into " + entry.getValue() + ": " + level.getBlockState(cave));
                level.setBlock(cave, Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(cave.above(), Blocks.AIR.defaultBlockState(), 2);
                x += 2;
            }
        } finally {
            releaseFarChunk(level, chunk);
        }
        helper.succeed();
    }

    /**
     * A stone layer at {@link FesteringStages#FULL_DEPTH_Y} festers completely; one just below it festers sparsely, exactly
     * where the position hash says, and the same blocks again on a rebuilt layer.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void depthLimitAndSparseFestering(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 2);
        int full = FesteringStages.FULL_DEPTH_Y;
        int sparse = FesteringStages.FULL_DEPTH_Y - 20;
        try {
            for (int y : new int[]{full, sparse}) {
                fill(level, at(chunk, 0, y - 1, 0), at(chunk, 15, y + 1, 15), Blocks.AIR.defaultBlockState());
                fill(level, at(chunk, 0, y, 0), at(chunk, 15, y, 15), Blocks.STONE.defaultBlockState());
            }
            int changedFull = FesteringStages.festerCaves(level, chunk, full, 3);
            helper.assertValueEqual(changedFull, 256, "every exposed block at the depth limit festers");
            Set<BlockPos> first = festeredLayer(level, chunk, sparse, helper);
            int count = first.size();
            helper.assertTrue(count >= 256 / FesteringStages.SPARSE / 2 && count <= 256 * 2 / FesteringStages.SPARSE,
                    "sparse festering below the depth limit festered " + count + " of 256");
            // Determinism: a rebuilt layer festers the same blocks.
            fill(level, at(chunk, 0, sparse, 0), at(chunk, 15, sparse, 15), Blocks.STONE.defaultBlockState());
            Set<BlockPos> second = festeredLayer(level, chunk, sparse, helper);
            helper.assertValueEqual(second, first, "the same blocks fester on a second run");
        } finally {
            releaseFarChunk(level, chunk);
        }
        helper.succeed();
    }

    private static Set<BlockPos> festeredLayer(ServerLevel level, LevelChunk chunk, int y, GameTestHelper helper) {
        int changed = FesteringStages.festerCaves(level, chunk, y, 3);
        Set<BlockPos> festered = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(at(chunk, 0, y, 0), at(chunk, 15, y, 15))) {
            boolean hit = level.getBlockState(pos).is(RemissionBlocks.FESTERED_STONE.get());
            helper.assertValueEqual(hit, FesteringStages.festersBelowFullDepth(pos), "festered at " + pos.toShortString());
            if (hit) {
                festered.add(pos.immutable());
            }
        }
        helper.assertValueEqual(changed, festered.size(), "blocks changed below the depth limit");
        return festered;
    }

    /**
     * A chunk whose stage rises queues the surface and then the cave pass on their own budgets; unloaded while the cave
     * pass is still running, it is not recorded at its stage, and after it loads again it finishes every section.
     */
    @GameTest(template = "empty", batch = BATCH + "_resume", timeoutTicks = 600)
    public static void caveFesteringResumesAfterUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk first = farChunk(helper, 3);
        ChunkPos pos = first.getPos();
        // One exposed stone block in every section above the ground: about one section of cave work per tick.
        List<BlockPos> stones = new ArrayList<>();
        for (int y = 8; y < level.getMaxBuildHeight() - 8; y += 16) {
            stones.add(at(first, 8, y, 8));
        }
        int stage3 = levelForStage(3);
        Set<BlockPos> festered = new HashSet<>();
        Consumer<BlockFesteredEvent> listener = event -> {
            if (stones.contains(event.getPos())) {
                festered.add(event.getPos());
            }
        };
        LevelChunk[] chunk = {first};
        helper.startSequence()
                .thenExecute(() -> {
                    stones.forEach(stone -> level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2));
                    NeoForge.EVENT_BUS.addListener(BlockFesteredEvent.class, listener);
                    first.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(stage3, 0L));
                    NeoForge.EVENT_BUS.post(new ChunkStageChangedEvent(level, pos, 0, stage3, 0, 3));
                })
                .thenWaitUntil(() -> helper.assertTrue(!festered.isEmpty(), "cave pass started"))
                .thenExecute(() -> {
                    helper.assertTrue(festered.size() < stones.size(), "cave pass still running: " + festered.size());
                    helper.assertValueEqual(FesteringStages.festeredStage(first), 0, "not recorded mid-way");
                    level.setChunkForced(pos.x, pos.z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(pos.x, pos.z) == null, "chunk unloaded"))
                .thenExecute(() -> {
                    helper.assertTrue(festered.size() < stones.size(), "unloaded before the cave pass finished");
                    level.setChunkForced(pos.x, pos.z, true);
                    chunk[0] = level.getChunk(pos.x, pos.z);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(FesteringStages.festeredStage(chunk[0]), 3, "festered stage after reload"))
                .thenExecute(() -> {
                    NeoForge.EVENT_BUS.unregister(listener);
                    for (BlockPos stone : stones) {
                        helper.assertTrue(level.getBlockState(stone).is(RemissionBlocks.FESTERED_STONE.get()),
                                "stone at " + stone.toShortString() + " festered after reload");
                    }
                    stones.forEach(stone -> level.setBlock(stone, Blocks.AIR.defaultBlockState(), 2));
                    releaseFarChunk(level, chunk[0]);
                })
                .thenSucceed();
    }

    private static int levelForStage(int stage) {
        List<Integer> thresholds = new ArrayList<>(RemissionCommonConfig.FESTERING_STAGE_THRESHOLDS.get());
        thresholds.sort(null);
        return thresholds.get(stage - 1);
    }

    private static FesterMaps.FesterBlock decode(String json) {
        return FesterMaps.FesterBlock.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}
