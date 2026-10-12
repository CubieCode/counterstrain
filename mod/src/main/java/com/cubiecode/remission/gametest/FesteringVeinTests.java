package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.infection.ExposureSettings;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.world.ChunkCorruption;
import com.cubiecode.remission.world.FesteringVeins;
import com.cubiecode.remission.world.VeinGrowth;
import com.cubiecode.remission.world.VeinSettings;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Festering veins (P4.8): veins grow only from the minimum stage, on every solid face (placed blocks included) and
 * never on resistant blocks, follow the face rule exactly, cover more at higher stages, are the same whatever order or
 * budget grows them, finish after an unload, drop only with silk touch, and grow nothing while turned off. Each test
 * builds in its own chunk far east of the test grid (beyond {@link CaveFesteringTests}' chunks), in open air from
 * {@link #BASE_Y} up.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FesteringVeinTests {
    private static final String BATCH = "festering_veins";
    /** Chunks east of the test's own chunk, one per test. */
    private static final int FAR = 128;
    /** Bottom of each test's build; well above the gametest floor. */
    private static final int BASE_Y = 100;

    private FesteringVeinTests() {
    }

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
        fill(level, at(chunk, 0, BASE_Y - 1, 0), at(chunk, 15, BASE_Y + 12, 15), Blocks.AIR.defaultBlockState());
        clearVeins(level, chunk);
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
     * A small build in open air: a stone floor (local 1–14, {@link #BASE_Y}), an oak plank hut on it (local 4–9, walls
     * and roof, a doorway), a floating 3-block glass and cobblestone column and a floating obsidian block. Everything
     * above the floor up to {@code BASE_Y + 11} is cleared first.
     */
    private static void build(ServerLevel level, LevelChunk chunk) {
        fill(level, at(chunk, 0, BASE_Y - 1, 0), at(chunk, 15, BASE_Y + 12, 15), Blocks.AIR.defaultBlockState());
        fill(level, at(chunk, 1, BASE_Y, 1), at(chunk, 14, BASE_Y, 14), Blocks.STONE.defaultBlockState());
        fill(level, at(chunk, 4, BASE_Y + 1, 4), at(chunk, 9, BASE_Y + 4, 9), Blocks.OAK_PLANKS.defaultBlockState());
        fill(level, at(chunk, 5, BASE_Y + 1, 5), at(chunk, 8, BASE_Y + 3, 8), Blocks.AIR.defaultBlockState());
        fill(level, at(chunk, 6, BASE_Y + 1, 4), at(chunk, 7, BASE_Y + 2, 4), Blocks.AIR.defaultBlockState());
        level.setBlock(at(chunk, 12, BASE_Y + 6, 3), Blocks.GLASS.defaultBlockState(), 2);
        level.setBlock(at(chunk, 12, BASE_Y + 7, 3), Blocks.COBBLESTONE.defaultBlockState(), 2);
        level.setBlock(at(chunk, 12, BASE_Y + 8, 3), Blocks.GLASS.defaultBlockState(), 2);
        level.setBlock(at(chunk, 12, BASE_Y + 7, 11), Blocks.OBSIDIAN.defaultBlockState(), 2);
    }

    private static void setStage(LevelChunk chunk, int stage) {
        if (stage <= 0) {
            chunk.removeData(RemissionAttachments.CHUNK_CORRUPTION);
        } else {
            chunk.setData(RemissionAttachments.CHUNK_CORRUPTION, new ChunkCorruption(ExposureSettings.levelForStage(stage), 0L));
        }
    }

    /** Every block state in the test's build space. */
    private static Map<BlockPos, BlockState> snapshot(ServerLevel level, LevelChunk chunk) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(at(chunk, 0, BASE_Y - 1, 0), at(chunk, 15, BASE_Y + 12, 15))) {
            states.put(pos.immutable(), level.getBlockState(pos));
        }
        return states;
    }

    /** Every vein in the chunk (its ground included, which the passes grow too), by position. */
    private static Map<BlockPos, BlockState> veins(ServerLevel level, LevelChunk chunk) {
        Map<BlockPos, BlockState> veins = new HashMap<>();
        Block vein = RemissionBlocks.FESTERING_VEIN.get();
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            LevelChunkSection section = chunk.getSection(index);
            if (!section.maybeHas(state -> state.is(vein))) {
                continue;
            }
            int bottom = chunk.getMinBuildHeight() + index * 16;
            for (BlockPos pos : BlockPos.betweenClosed(at(chunk, 0, bottom, 0), at(chunk, 15, bottom + 15, 15))) {
                BlockState state = level.getBlockState(pos);
                if (state.is(vein)) {
                    veins.put(pos.immutable(), state);
                }
            }
        }
        return veins;
    }

    private static void clearVeins(ServerLevel level, LevelChunk chunk) {
        veins(level, chunk).keySet().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2));
    }

    /**
     * Asserts the face rule for every block of the build space that was air: it holds a face exactly where
     * {@link FesteringVeins#selected} picks it, the neighbour holds a multiface block and is not {@code resistant}.
     * Returns the faces grown, by the block each covers.
     */
    private static Map<Block, Set<Direction>> assertFaceRule(GameTestHelper helper, ServerLevel level, LevelChunk chunk,
                                                             Map<BlockPos, BlockState> before, int coverage,
                                                             Predicate<BlockState> resistant) {
        Map<Block, Set<Direction>> grown = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!entry.getValue().isAir()) {
                helper.assertValueEqual(level.getBlockState(pos), entry.getValue(), "block at " + pos.toShortString() + " replaced");
                continue;
            }
            BlockState now = level.getBlockState(pos);
            for (Direction direction : Direction.values()) {
                BlockPos side = pos.relative(direction);
                BlockState neighbour = level.getBlockState(side);
                boolean expected = FesteringVeins.selected(pos, direction, coverage) && !neighbour.isAir()
                        && !neighbour.is(RemissionBlocks.FESTERING_VEIN.get()) && !resistant.test(neighbour)
                        && MultifaceBlock.canAttachTo(level, direction, side, neighbour);
                boolean face = now.is(RemissionBlocks.FESTERING_VEIN.get()) && MultifaceBlock.hasFace(now, direction);
                helper.assertValueEqual(face, expected, "vein face " + direction + " at " + pos.toShortString() + " on " + neighbour);
                if (face) {
                    grown.computeIfAbsent(neighbour.getBlock(), block -> EnumSet.noneOf(Direction.class)).add(direction);
                }
            }
        }
        return grown;
    }

    private static int capture(List<BlockFesteredEvent> events, ChunkPos chunk, Runnable body) {
        Consumer<BlockFesteredEvent> listener = event -> {
            if (new ChunkPos(event.getPos()).equals(chunk)) {
                events.add(event);
            }
        };
        NeoForge.EVENT_BUS.addListener(BlockFesteredEvent.class, listener);
        try {
            body.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return events.size();
    }

    /**
     * Below the minimum stage nothing grows; at it the face rule holds with one event per vein block, and each stage up
     * covers a superset of the stage below it.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void veinsGrowFromMinimumStage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 0);
        try {
            build(level, chunk);
            Map<BlockPos, BlockState> before = snapshot(level, chunk);
            int minStage = RemissionCommonConfig.FESTERING_VEIN_MIN_STAGE.get();
            helper.assertValueEqual(FesteringVeins.coverage(minStage - 1, minStage), 0, "coverage below the minimum stage");
            setStage(chunk, minStage - 1);
            helper.assertValueEqual(FesteringVeins.growNow(level, chunk), 0, "blocks changed below the minimum stage");
            helper.assertTrue(veins(level, chunk).isEmpty(), "veins below the minimum stage");

            setStage(chunk, minStage);
            List<BlockFesteredEvent> events = new ArrayList<>();
            int[] changed = {0};
            capture(events, chunk.getPos(), () -> changed[0] = FesteringVeins.growNow(level, chunk));
            int coverage = FesteringVeins.coverage(minStage, minStage);
            assertFaceRule(helper, level, chunk, before, coverage, state -> state.is(FesteringVeins.VEIN_RESISTANT));
            Map<BlockPos, BlockState> grown = veins(level, chunk);
            helper.assertTrue(!grown.isEmpty(), "no veins at the minimum stage");
            helper.assertValueEqual(changed[0], grown.size(), "blocks changed at the minimum stage");
            helper.assertValueEqual(events.size(), grown.size(), "block festered events");
            for (BlockFesteredEvent event : events) {
                helper.assertTrue(event.getOldState().isAir() && event.getNewState().is(RemissionBlocks.FESTERING_VEIN.get()),
                        "event states " + event.getOldState() + " -> " + event.getNewState());
            }

            Map<BlockPos, BlockState> atMin = snapshot(level, chunk);
            setStage(chunk, minStage + 1);
            helper.assertTrue(FesteringVeins.growNow(level, chunk) > 0, "nothing more one stage up");
            assertFaceRule(helper, level, chunk, before, FesteringVeins.coverage(minStage + 1, minStage),
                    state -> state.is(FesteringVeins.VEIN_RESISTANT));
            for (Map.Entry<BlockPos, BlockState> entry : atMin.entrySet()) {
                if (entry.getValue().is(RemissionBlocks.FESTERING_VEIN.get())) {
                    for (Direction direction : MultifaceBlock.availableFaces(entry.getValue())) {
                        helper.assertTrue(MultifaceBlock.hasFace(level.getBlockState(entry.getKey()), direction),
                                "face " + direction + " at " + entry.getKey().toShortString() + " lost one stage up");
                    }
                }
            }
            helper.assertValueEqual(FesteringVeins.growNow(level, chunk), 0, "blocks changed by a repeated pass");
        } finally {
            release(level, chunk);
        }
        helper.succeed();
    }

    /**
     * At full coverage veins cover every kind of solid face of the build (floor, plank walls on all six sides, glass and
     * cobblestone placed in the air), never a resistant block, and the shipped resistant tag is empty.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void veinsCoverSolidFacesButNotResistant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 1);
        try {
            build(level, chunk);
            Map<BlockPos, BlockState> before = snapshot(level, chunk);
            Predicate<BlockState> resistant = state -> state.is(Blocks.OBSIDIAN);
            // 200 percent: every face outside the bare patches.
            FesteringVeins.growNow(level, chunk, 200, resistant);
            Map<Block, Set<Direction>> grown = assertFaceRule(helper, level, chunk, before, 200, resistant);
            helper.assertTrue(grown.getOrDefault(Blocks.OAK_PLANKS, Set.of()).containsAll(EnumSet.allOf(Direction.class)),
                    "plank faces covered: " + grown.get(Blocks.OAK_PLANKS));
            helper.assertTrue(grown.containsKey(Blocks.STONE), "stone floor not covered");
            helper.assertTrue(grown.containsKey(Blocks.GLASS), "placed glass not covered");
            helper.assertTrue(grown.containsKey(Blocks.COBBLESTONE), "placed cobblestone not covered");
            helper.assertFalse(grown.containsKey(Blocks.OBSIDIAN), "resistant obsidian covered");
            helper.assertTrue(BuiltInRegistries.BLOCK.getTag(FesteringVeins.VEIN_RESISTANT).map(tag -> tag.size() == 0).orElse(true),
                    "shipped #remission:vein_resistant is not empty");
        } finally {
            release(level, chunk);
        }
        helper.succeed();
    }

    /**
     * The same build at the same stage gets the same veins however it is grown: at once, layer by layer from the top
     * down, or by a budgeted pass queued by a stage change.
     */
    @GameTest(template = "empty", batch = BATCH + "_order", timeoutTicks = 400)
    public static void veinGrowthIsDeterministic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 2);
        int stage = RemissionCommonConfig.FESTERING_VEIN_MIN_STAGE.get() + 1;
        int coverage = FesteringVeins.coverage(stage, stage - 1);
        build(level, chunk);
        setStage(chunk, stage);
        FesteringVeins.growNow(level, chunk);
        Map<BlockPos, BlockState> once = veins(level, chunk);
        helper.assertTrue(!once.isEmpty(), "no veins");

        clearVeins(level, chunk);
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            FesteringVeins.growLayer(level, chunk, y, coverage);
        }
        helper.assertTrue(veins(level, chunk).equals(once), "top-down growth differs");

        clearVeins(level, chunk);
        VeinGrowth growth = new VeinGrowth(level, () -> new VeinSettings(true, stage - 1));
        growth.queue(chunk.getPos());
        int ticks = 0;
        while (growth.busy() && ticks < 10_000) {
            growth.tick(3);
            ticks++;
        }
        helper.assertTrue(ticks > 1, "budgeted pass finished in one tick");
        helper.assertTrue(veins(level, chunk).equals(once), "budgeted growth differs");

        // The level's own pass, queued by the stage event the spread engine posts. The stage pass (P4.2, P4.6) festers the
        // stone floor meanwhile; veins hold on festered stone just the same.
        clearVeins(level, chunk);
        helper.startSequence()
                .thenExecute(() -> NeoForge.EVENT_BUS.post(new ChunkStageChangedEvent(level, chunk.getPos(),
                        ExposureSettings.levelForStage(stage - 1), ExposureSettings.levelForStage(stage), stage - 1, stage)))
                .thenWaitUntil(() -> helper.assertFalse(FesteringVeins.growth(level).busy(), "level pass still busy"))
                .thenExecute(() -> {
                    boolean same = veins(level, chunk).equals(once);
                    release(level, chunk);
                    helper.assertTrue(same, "level pass growth differs");
                })
                .thenSucceed();
    }

    /** A chunk unloaded while its vein pass runs finishes every section after it loads again. */
    @GameTest(template = "empty", batch = BATCH + "_resume", timeoutTicks = 800)
    public static void veinGrowthResumesAfterUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk first = farChunk(helper, 3);
        ChunkPos pos = first.getPos();
        // One stone block in open air in every section above the ground: about two ticks of vein work per section.
        List<BlockPos> stones = new ArrayList<>();
        for (int y = 8; y < level.getMaxBuildHeight() - 8; y += 16) {
            stones.add(at(first, 8, y, 8));
        }
        int stage = maxStage();
        int coverage = FesteringVeins.coverage(stage, RemissionCommonConfig.FESTERING_VEIN_MIN_STAGE.get());
        Map<BlockPos, BlockState> expected = new HashMap<>();
        List<BlockPos> grown = new ArrayList<>();
        Consumer<BlockFesteredEvent> listener = event -> {
            if (expected.containsKey(event.getPos())) {
                grown.add(event.getPos());
            }
        };
        LevelChunk[] chunk = {first};
        helper.startSequence()
                .thenExecute(() -> {
                    stones.forEach(stone -> level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2));
                    // The expected veins: grown at once, recorded, then cleared.
                    for (BlockPos stone : stones) {
                        for (int dy = -1; dy <= 1; dy++) {
                            FesteringVeins.growLayer(level, first, stone.getY() + dy, coverage);
                        }
                    }
                    for (BlockPos stone : stones) {
                        for (Direction direction : Direction.values()) {
                            BlockState state = level.getBlockState(stone.relative(direction));
                            if (state.is(RemissionBlocks.FESTERING_VEIN.get())) {
                                expected.put(stone.relative(direction).immutable(), state);
                                level.setBlock(stone.relative(direction), Blocks.AIR.defaultBlockState(), 2);
                            }
                        }
                    }
                    helper.assertTrue(expected.size() > 4, "too few expected veins: " + expected.size());
                    NeoForge.EVENT_BUS.addListener(BlockFesteredEvent.class, listener);
                    setStage(first, stage);
                    NeoForge.EVENT_BUS.post(new ChunkStageChangedEvent(level, pos, 0, ExposureSettings.levelForStage(stage), 0, stage));
                })
                .thenWaitUntil(() -> helper.assertTrue(!grown.isEmpty(), "vein pass started"))
                .thenExecute(() -> {
                    helper.assertTrue(grown.size() < expected.size(), "vein pass still running: " + grown.size());
                    level.setChunkForced(pos.x, pos.z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(pos.x, pos.z) == null, "chunk unloaded"))
                .thenExecute(() -> {
                    helper.assertTrue(new ArrayList<>(grown).size() < expected.size(), "unloaded before the vein pass finished");
                    level.setChunkForced(pos.x, pos.z, true);
                    chunk[0] = level.getChunk(pos.x, pos.z);
                })
                .thenWaitUntil(() -> {
                    for (Map.Entry<BlockPos, BlockState> entry : expected.entrySet()) {
                        helper.assertValueEqual(level.getBlockState(entry.getKey()), entry.getValue(),
                                "vein at " + entry.getKey().toShortString() + " after reload");
                    }
                })
                .thenExecute(() -> {
                    NeoForge.EVENT_BUS.unregister(listener);
                    // Every vein in the chunk, the ones on its ground included.
                    for (BlockPos vein : BlockPos.betweenClosed(at(chunk[0], 0, level.getMinBuildHeight(), 0),
                            at(chunk[0], 15, level.getMaxBuildHeight() - 1, 15))) {
                        if (level.getBlockState(vein).is(RemissionBlocks.FESTERING_VEIN.get())) {
                            level.setBlock(vein, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                    stones.forEach(stone -> level.setBlock(stone, Blocks.AIR.defaultBlockState(), 2));
                    chunk[0].removeData(RemissionAttachments.CHUNK_CORRUPTION);
                    level.setChunkForced(pos.x, pos.z, false);
                })
                .thenSucceed();
    }

    /** With veins turned off, a queued festering chunk grows nothing; the same pass turned on grows it. */
    @GameTest(template = "empty", batch = BATCH)
    public static void nothingGrowsWhenOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelChunk chunk = farChunk(helper, 4);
        try {
            build(level, chunk);
            setStage(chunk, maxStage());
            boolean[] enabled = {false};
            VeinGrowth growth = new VeinGrowth(level, () -> new VeinSettings(enabled[0], 1));
            growth.queue(chunk.getPos());
            for (int t = 0; t < 200; t++) {
                helper.assertValueEqual(growth.tick(64), 0, "work done while off");
            }
            helper.assertTrue(veins(level, chunk).isEmpty(), "veins grew while off");
            enabled[0] = true;
            for (int t = 0; t < 200 && growth.busy(); t++) {
                growth.tick(64);
            }
            helper.assertTrue(!veins(level, chunk).isEmpty(), "no veins once turned on");
        } finally {
            release(level, chunk);
        }
        helper.succeed();
    }

    /** A vein drops one of itself per face with silk touch and nothing otherwise. */
    @GameTest(template = "empty", batch = BATCH)
    public static void veinsDropOnlyWithSilkTouch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState vein = RemissionBlocks.FESTERING_VEIN.get().defaultBlockState()
                .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
                .setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true);
        helper.assertTrue(Block.getDrops(vein, level, pos, null, null, ItemStack.EMPTY).isEmpty(), "drops by hand");
        helper.assertTrue(Block.getDrops(vein, level, pos, null, null, new ItemStack(Items.DIAMOND_HOE)).isEmpty(), "drops with a hoe");
        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SILK_TOUCH), 1);
        List<ItemStack> drops = Block.getDrops(vein, level, pos, null, null, silk);
        int count = drops.stream().filter(stack -> stack.is(RemissionItems.FESTERING_VEIN.get())).mapToInt(ItemStack::getCount).sum();
        helper.assertValueEqual(count, 2, "veins dropped with silk touch from two faces");
        helper.succeed();
    }

    /** The highest festering stage under the current thresholds. */
    private static int maxStage() {
        return RemissionCommonConfig.FESTERING_STAGE_THRESHOLDS.get().size();
    }
}
