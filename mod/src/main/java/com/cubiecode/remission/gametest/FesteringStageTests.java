package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.purification.Cleansing;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesterMaps;
import com.cubiecode.remission.world.FesteringStages;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Festering stages (P4.2): blocks turn through {@code remission:festers_into} as the chunk stage rises, a stage change
 * queues the chunk and the budgeted ticks finish it, the last stage flips the biome, and {@code fester_variant} picks
 * the biome. Whole-chunk tests use a chunk far from the test grid; tests run in their own batch.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FesteringStageTests {
    private static final String BATCH = "festering_stages";
    /** Chunks east of the test's own chunk used for whole-chunk work, one per test, away from the grid. */
    private static final int FAR = 96;

    private FesteringStageTests() {
    }

    /** The lowest chunk level at festering stage {@code stage} under the current config. */
    private static int levelForStage(int stage) {
        List<Integer> thresholds = new ArrayList<>(RemissionCommonConfig.FESTERING_STAGE_THRESHOLDS.get());
        thresholds.sort(null);
        return thresholds.get(stage - 1);
    }

    private static void setLevel(LevelChunk chunk, int value) {
        if (value == 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(value, 0L));
        }
    }

    private static List<BlockFesteredEvent> capture(Set<BlockPos> at, Runnable body) {
        List<BlockFesteredEvent> events = new ArrayList<>();
        Consumer<BlockFesteredEvent> listener = event -> {
            if (at.contains(event.getPos())) {
                events.add(event);
            }
        };
        NeoForge.EVENT_BUS.addListener(BlockFesteredEvent.class, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events;
    }

    /** A loaded, force-loaded chunk {@code offset} chunks east of the test, its land and festered stage cleared. */
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
        Holder<Biome> plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        FesteringStages.flipBiomes(level, chunk, source -> plains);
        level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
    }

    /** Asserts the block state at an absolute position. */
    private static void assertBlock(GameTestHelper helper, BlockPos pos, Predicate<BlockState> test, Supplier<String> message) {
        BlockState state = helper.getLevel().getBlockState(pos);
        helper.assertTrue(test.test(state), message.get() + ": " + state);
    }

    /** The stored biome cell holding {@code pos} (not the fuzzed {@code getBiome}, which can read a neighbour chunk). */
    private static ResourceKey<Biome> biomeAt(ServerLevel level, BlockPos pos) {
        return level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()))
                .unwrapKey().orElseThrow();
    }

    /**
     * Each stage festers what the default data map gives it, cumulatively: nothing before stage 3, then grass and stone
     * (P4.16). Logs and leaves never turn and keep their state.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void columnFestersByStage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stone = helper.absolutePos(new BlockPos(1, 0, 1));
        BlockPos grass = stone.above();
        BlockPos log = grass.above();
        BlockPos leaves = log.above();
        LevelChunk chunk = level.getChunkAt(stone);
        // The scan also reaches the floor under the test area; restore it afterwards.
        Map<BlockPos, BlockState> saved = new HashMap<>();
        for (int y = leaves.getY(); y >= stone.getY() - FesteringStages.GROUND_DEPTH; y--) {
            BlockPos pos = stone.atY(y);
            saved.put(pos, level.getBlockState(pos));
        }
        try {
            for (int stage = 0; stage <= CorruptionQueries.maxChunkStage(); stage++) {
                level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(grass, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                level.setBlock(log, Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X), 2);
                level.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 2);
                int s = stage;
                List<BlockFesteredEvent> events = capture(Set.of(stone, grass, log, leaves),
                        () -> FesteringStages.festerColumn(level, chunk, stone.getX() & 15, stone.getZ() & 15, s));
                assertBlock(helper, grass, state -> state.is(s >= 3 ? RemissionBlocks.FESTERING_GRASS.get() : Blocks.GRASS_BLOCK),
                        () -> "grass at stage " + s);
                assertBlock(helper, log, state -> state.is(Blocks.OAK_LOG)
                        && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X, () -> "log and its axis at stage " + s);
                assertBlock(helper, leaves, state -> state.is(Blocks.OAK_LEAVES)
                        && state.getValue(LeavesBlock.PERSISTENT), () -> "leaves and persistence at stage " + s);
                assertBlock(helper, stone, state -> state.is(s >= 3 ? RemissionBlocks.FESTERED_STONE.get() : Blocks.STONE),
                        () -> "stone at stage " + s);
                int expected = stage >= 3 ? 2 : 0;
                helper.assertValueEqual(events.size(), expected, "block festered events at stage " + s);
                for (BlockFesteredEvent event : events) {
                    helper.assertTrue(event.getLevel() == level, "event level");
                    helper.assertTrue(event.getNewState() == level.getBlockState(event.getPos()), "event new state");
                    helper.assertTrue(event.getOldState().getBlock() != event.getNewState().getBlock(), "event old state");
                }
            }
            // A second pass at the same stage changes nothing.
            int again = FesteringStages.festerColumn(level, chunk, stone.getX() & 15, stone.getZ() & 15, 3);
            helper.assertValueEqual(again, 0, "blocks changed by a repeated pass");
        } finally {
            saved.forEach((pos, state) -> level.setBlock(pos, state, 2));
        }
        helper.succeed();
    }

    /**
     * A queued chunk (as on load) and a stage change event are both festered by the budgeted ticks; the last stage flips
     * the biome, and the festered stage is recorded so nothing is redone.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 400)
    public static void stageChangeFestersChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 0);
        ChunkPos pos = chunk.getPos();
        BlockPos ground = new BlockPos(pos.getBlockX(8), 0, pos.getBlockZ(8));
        ground = ground.atY(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8) + 1);
        BlockPos grass = ground;
        BlockPos log = grass.above();
        BlockPos leaves = log.above();
        BlockPos[] corner = {new BlockPos(pos.getMinBlockX(), grass.getY(), pos.getMinBlockZ())};
        int max = CorruptionQueries.maxChunkStage();
        helper.startSequence()
                .thenExecute(() -> {
                    level.setBlock(grass, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    level.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 2);
                    level.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 2);
                    level.setBlock(corner[0], Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    // Stage 1 stored without an event, then queued the way a chunk load queues it.
                    setLevel(chunk, levelForStage(1));
                    FesteringStages.queue(level, pos);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(FesteringStages.festeredStage(chunk), 1, "festered stage after load"))
                .thenExecute(() -> {
                    assertBlock(helper, grass, state -> state.is(Blocks.GRASS_BLOCK), () -> "grass at stage 1");
                    // Stage 3 through the event the spread engine posts.
                    setLevel(chunk, levelForStage(3));
                    NeoForge.EVENT_BUS.post(new ChunkStageChangedEvent(level, pos, levelForStage(1), levelForStage(3), 1, 3));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(FesteringStages.festeredStage(chunk), 3, "festered stage after the event"))
                .thenExecute(() -> {
                    assertBlock(helper, grass, state -> state.is(RemissionBlocks.FESTERING_GRASS.get()), () -> "grass at stage 3");
                    assertBlock(helper, corner[0], state -> state.is(RemissionBlocks.FESTERING_GRASS.get()), () -> "corner grass at stage 3");
                    assertBlock(helper, log, state -> state.is(Blocks.OAK_LOG), () -> "log at stage 3");
                    assertBlock(helper, leaves, state -> state.is(Blocks.OAK_LEAVES), () -> "leaves at stage 3");
                    helper.assertTrue(biomeAt(level, grass) == Biomes.PLAINS, "biome before the last stage");
                    setLevel(chunk, levelForStage(max));
                    NeoForge.EVENT_BUS.post(new ChunkStageChangedEvent(level, pos, levelForStage(3), levelForStage(max), 3, max));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(FesteringStages.festeredStage(chunk), max, "festered stage at the last stage"))
                .thenExecute(() -> {
                    for (BlockPos at : List.of(grass, corner[0], new BlockPos(pos.getMaxBlockX(), level.getMinBuildHeight(), pos.getMaxBlockZ()),
                            new BlockPos(pos.getMinBlockX(), level.getMaxBuildHeight() - 1, pos.getMaxBlockZ()))) {
                        helper.assertTrue(biomeAt(level, at) == FesterMaps.FESTER, "biome at " + at.toShortString());
                    }
                    helper.assertTrue(chunk.isUnsaved(), "flipped chunk marked for saving");
                    // A queued chunk already at its stage is skipped without festering anything.
                    level.setBlock(grass, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    FesteringStages.queue(level, pos);
                })
                .thenWaitUntil(() -> helper.assertFalse(FesteringStages.busy(level), "queue drained"))
                .thenExecute(() -> {
                    assertBlock(helper, grass, state -> state.is(Blocks.GRASS_BLOCK), () -> "grass placed after the stage");
                    releaseFarChunk(level, chunk);
                })
                .thenSucceed();
    }

    /**
     * Short and tall grass and ferns turn at stage 2 into one festering block each, both halves of a tall plant together,
     * and cleanse back into the same plant, also when only one half is cleansed, as at the edge of a purifier's box
     * (P4.16).
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void plantsFesterWholeAndCleanseBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(1, 0, 1));
        BlockPos lower = ground.above();
        BlockPos upper = lower.above();
        LevelChunk chunk = level.getChunkAt(ground);
        int localX = ground.getX() & 15;
        int localZ = ground.getZ() & 15;
        Map<BlockPos, BlockState> saved = new HashMap<>();
        for (int y = upper.getY(); y >= ground.getY() - FesteringStages.GROUND_DEPTH; y--) {
            BlockPos pos = ground.atY(y);
            saved.put(pos, level.getBlockState(pos));
        }
        try {
            Map<Block, Block> tall = Map.of(Blocks.TALL_GRASS, RemissionBlocks.FESTERING_TALL_GRASS.get(),
                    Blocks.LARGE_FERN, RemissionBlocks.FESTERING_LARGE_FERN.get());
            for (Map.Entry<Block, Block> plant : tall.entrySet()) {
                level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                // Cleared without shape updates first, so the last plant's halves do not break the new one's.
                level.setBlock(upper, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                level.setBlock(lower, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                DoublePlantBlock.placeAt(level, plant.getKey().defaultBlockState(), lower, 2);
                helper.assertValueEqual(FesteringStages.festerColumn(level, chunk, localX, localZ, 1), 0, plant.getKey() + " at stage 1");
                FesteringStages.festerColumn(level, chunk, localX, localZ, 2);
                assertPlant(helper, lower, upper, plant.getValue(), "festered " + plant.getKey());
                assertBlock(helper, ground, state -> state.is(Blocks.GRASS_BLOCK), () -> "grass block at stage 2");
                Cleansing.cleanseBlock(level, upper, level.getBlockState(upper), PurificationCause.PURIFIER);
                assertPlant(helper, lower, upper, plant.getKey(), "cleansed " + plant.getKey());
            }
            level.setBlock(upper, Blocks.AIR.defaultBlockState(), 2);
            Map<Block, Block> short_ = Map.of(Blocks.SHORT_GRASS, RemissionBlocks.FESTERING_SHORT_GRASS.get(),
                    Blocks.FERN, RemissionBlocks.FESTERING_FERN.get());
            for (Map.Entry<Block, Block> plant : short_.entrySet()) {
                level.setBlock(lower, plant.getKey().defaultBlockState(), 2);
                FesteringStages.festerColumn(level, chunk, localX, localZ, 2);
                assertBlock(helper, lower, state -> state.is(plant.getValue()), () -> "festered " + plant.getKey());
                helper.assertTrue(Cleansing.cleansed(level.getBlockState(lower)).is(plant.getKey()), "cleanses into " + plant.getKey());
            }
        } finally {
            saved.forEach((pos, state) -> level.setBlock(pos, state, 2));
        }
        helper.succeed();
    }

    private static void assertPlant(GameTestHelper helper, BlockPos lower, BlockPos upper, Block block, String message) {
        assertBlock(helper, lower, state -> state.is(block) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER,
                () -> message + ", lower half");
        assertBlock(helper, upper, state -> state.is(block) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER,
                () -> message + ", upper half");
    }

    /** {@code fester_variant} resolution, the shipped defaults, and a flip into a variant. */
    @GameTest(template = "empty", batch = BATCH)
    public static void festerVariantPicksBiome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);

        // Contract: the value shape decodes as documented.
        FesterMaps.FesterVariant desert = FesterMaps.FesterVariant.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"biome\": \"minecraft:desert\"}")).getOrThrow();
        helper.assertTrue(desert.biome() == Biomes.DESERT, "decoded variant");
        FesterMaps.FesterBlock block = FesterMaps.FesterBlock.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"block\": \"remission:festered_stone\"}")).getOrThrow();
        helper.assertTrue(block.block() == RemissionBlocks.FESTERED_STONE.get() && block.stage() == 1, "decoded block entry");

        // Resolution: a variant wins, no entry falls back to the fester biome, fester biomes never flip again.
        Set<ResourceKey<Biome>> festered = Set.of(FesterMaps.FESTER, Biomes.DESERT);
        helper.assertTrue(FesteringStages.festerTarget(Biomes.PLAINS, desert, festered) == Biomes.DESERT, "plains with a variant");
        helper.assertTrue(FesteringStages.festerTarget(Biomes.FOREST, null, festered) == FesterMaps.FESTER, "forest without one");
        helper.assertTrue(FesteringStages.festerTarget(Biomes.DESERT, new FesterMaps.FesterVariant(Biomes.BADLANDS), festered)
                == Biomes.DESERT, "a variant target does not flip again");
        helper.assertTrue(FesteringStages.festerTarget(FesterMaps.FESTER, desert, festered) == FesterMaps.FESTER, "fester stays");

        // Shipped data: the fester biome exists, no variants by default, the default block map.
        helper.assertTrue(biomes.getHolder(FesterMaps.FESTER).isPresent(), "remission:fester registered");
        helper.assertValueEqual(FesteringStages.festerBiomes(biomes), Set.of(FesterMaps.FESTER), "default fester biomes");
        helper.assertTrue(FesteringStages.festerTarget(biomes.getHolderOrThrow(Biomes.PLAINS), biomes, Set.of(FesterMaps.FESTER))
                .is(FesterMaps.FESTER), "plains festers to remission:fester by default");
        // Logs and leaves keep their wood type (P4.16): no default entry for any of them.
        for (Block tree : List.of(Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_WOOD, Blocks.OAK_LEAVES, Blocks.CHERRY_LEAVES)) {
            helper.assertTrue(tree.builtInRegistryHolder().getData(FesterMaps.FESTERS_INTO) == null, "no entry for " + tree);
        }
        FesterMaps.FesterBlock grass = Blocks.GRASS_BLOCK.builtInRegistryHolder().getData(FesterMaps.FESTERS_INTO);
        helper.assertTrue(grass != null && grass.block() == RemissionBlocks.FESTERING_GRASS.get() && grass.stage() == 3, "grass entry");
        FesterMaps.FesterBlock deepslate = Blocks.DEEPSLATE.builtInRegistryHolder().getData(FesterMaps.FESTERS_INTO);
        helper.assertTrue(deepslate != null && deepslate.stage() == 3, "deepslate entry from deepslate_ore_replaceables");
        helper.assertTrue(Blocks.DIRT.builtInRegistryHolder().getData(FesterMaps.FESTERS_INTO) == null, "dirt has no entry");

        // A flip through a variant lookup, then a second flip that changes nothing.
        LevelChunk chunk = farChunk(helper, 1);
        try {
            Holder<Biome> desertHolder = biomes.getHolderOrThrow(Biomes.DESERT);
            boolean changed = FesteringStages.flipBiomes(level, chunk, source -> source.is(Biomes.PLAINS) ? desertHolder
                    : biomes.getHolderOrThrow(FesteringStages.festerTarget(source.unwrapKey().orElseThrow(), null, festered)));
            helper.assertTrue(changed, "first flip changed the chunk");
            BlockPos centre = chunk.getPos().getMiddleBlockPosition(64);
            helper.assertTrue(biomeAt(level, centre) == Biomes.DESERT, "plains flipped into its variant");
            boolean again = FesteringStages.flipBiomes(level, chunk,
                    source -> FesteringStages.festerTarget(source, biomes, festered));
            helper.assertFalse(again, "a flipped chunk does not flip again");
        } finally {
            releaseFarChunk(level, chunk);
        }
        helper.succeed();
    }
}
