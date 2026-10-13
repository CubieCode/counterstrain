package com.cubiecode.remission.gametest;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.purification.Regrowth;
import com.cubiecode.remission.purification.RemissionZoneEngine;
import com.cubiecode.remission.purification.RemissionZoneSettings;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Remission zones come back to life (P5.5): wildlife returns to chunks in remission from the biome's creature list up
 * to the cap, and nowhere else; regrowth places the biome's own plants on cleansed surfaces, deterministically. Each test
 * runs alone in its own batch in its own row of chunks north-east of the test grid, on a grass platform covering the
 * chunk with its biome set, with its own {@link RemissionZoneEngine} and spelled-out settings, and does its work within
 * one tick by passing game times to the engine (the wildlife tests first wait until their chunks are entity ticking); it removes its
 * platform, plants, animals and biome afterwards.
 */
@GameTestHolder(Remission.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RemissionZoneTests {
    private static final String BATCH = "remission_zones";
    /** Chunks east of the test's own chunk to the first test area. */
    private static final int FAR = 176;
    /** Chunks north of the test's own chunk. */
    private static final int NORTH = 64;
    /** Chunks between test areas. */
    private static final int SPACING = 16;
    /** Height of each test's grass platform; well above the gametest floor. */
    private static final int BASE_Y = 100;
    /** Ticks a wildlife test may wait for its force-loaded chunks' entities to load. */
    private static final int LOAD_TIMEOUT = 600;
    private static final int TICKS = 2400;
    private static final int INTERVAL = 100;
    private static final int CAP = 4;
    private static final RemissionZoneSettings WILDLIFE = new RemissionZoneSettings(false, TICKS, 0.3, 0.04, true, INTERVAL, CAP,
            4096);
    private static final RemissionZoneSettings REGROWTH = new RemissionZoneSettings(true, TICKS, 0.5, 0.2, false, INTERVAL, CAP,
            4096);
    private static final Set<Block> PLAINS_PLANTS = Set.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.DANDELION, Blocks.POPPY,
            Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP,
            Blocks.PINK_TULIP, Blocks.OAK_SAPLING, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM);
    private static final Set<Block> TAIGA_PLANTS = Set.of(Blocks.SHORT_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DANDELION,
            Blocks.POPPY, Blocks.SPRUCE_SAPLING, Blocks.SWEET_BERRY_BUSH, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM);

    private RemissionZoneTests() {
    }

    // ---- wildlife ----------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_wildlife", timeoutTicks = LOAD_TIMEOUT)
    public static void wildlifeReturnsFromTheBiomeUpToTheCap(GameTestHelper helper) {
        Area area = area(helper, 0);
        LevelChunk chunk = area.zone(0, Biomes.PLAINS, true);
        afterLoad(helper, area, () -> {
            ServerLevel level = helper.getLevel();
            long entered = chunk.getData(RemissionAttachments.CHUNK_REMISSION);
            RemissionZoneEngine engine = new RemissionZoneEngine(level);
            engine.track(chunk.getPos());
            RandomSource random = RandomSource.create(42L);
            engine.tick(entered + INTERVAL - 1, WILDLIFE.budget(), WILDLIFE, random);
            helper.assertValueEqual(creatures(level, chunk).size(), 0, "animals before the first attempt is due");
            int attempts = 0;
            long now = entered + INTERVAL;
            while (creatures(level, chunk).size() < CAP && attempts < 4) {
                engine.tick(now, WILDLIFE.budget(), WILDLIFE, random);
                now += INTERVAL;
                attempts++;
            }
            helper.assertValueEqual(creatures(level, chunk).size(), CAP, "animals after " + attempts + " attempts");
            for (int i = 0; i < 6; i++) {
                engine.tick(now, WILDLIFE.budget(), WILDLIFE, random);
                now += INTERVAL;
            }
            List<Mob> animals = creatures(level, chunk);
            helper.assertValueEqual(animals.size(), CAP, "animals once at the cap");
            Set<EntityType<?>> listed = Regrowth.biomeAt(level, area.at(0, 8, BASE_Y + 1, 8)).value().getMobSettings()
                    .getMobs(MobCategory.CREATURE).unwrap().stream().map(data -> data.type).collect(Collectors.toSet());
            for (Mob animal : animals) {
                helper.assertTrue(listed.contains(animal.getType()), animal.getType() + " is not on the plains creature list");
                helper.assertFalse(CorruptionQueries.isHost(animal), "a returned animal is a host");
                helper.assertValueEqual(CorruptionQueries.hostLevel(animal), 0, "returned animal Strain level");
            }
        });
    }

    @GameTest(template = "empty", batch = BATCH + "_wildlife_off", timeoutTicks = LOAD_TIMEOUT)
    public static void noWildlifeOutsideRemissionOrWhenOff(GameTestHelper helper) {
        Area area = area(helper, 1);
        LevelChunk outside = area.zone(0, Biomes.PLAINS, false);
        LevelChunk off = area.zone(2, Biomes.PLAINS, true);
        afterLoad(helper, area, () -> {
            ServerLevel level = helper.getLevel();
            long entered = off.getData(RemissionAttachments.CHUNK_REMISSION);
            RemissionZoneSettings disabled = new RemissionZoneSettings(false, TICKS, 0.3, 0.04, false, INTERVAL, CAP, 4096);
            RemissionZoneEngine engine = new RemissionZoneEngine(level);
            engine.track(off.getPos());
            RandomSource random = RandomSource.create(42L);
            for (int i = 1; i <= 10; i++) {
                engine.tick(entered + (long) i * INTERVAL, disabled.budget(), disabled, random);
            }
            helper.assertValueEqual(creatures(level, off).size(), 0, "animals with wildlife off");
            engine.track(outside.getPos());
            for (int i = 11; i <= 20; i++) {
                engine.tick(entered + (long) i * INTERVAL, WILDLIFE.budget(), WILDLIFE, random);
            }
            helper.assertValueEqual(creatures(level, outside).size(), 0, "animals in a chunk not in remission");
            helper.assertFalse(engine.tracks(outside.getPos()), "a chunk not in remission is still tracked");
            helper.assertTrue(creatures(level, off).size() > 0, "no animals once wildlife is on again");
        });
    }

    // ---- regrowth ----------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", batch = BATCH + "_regrowth")
    public static void regrowthPlacesTheBiomesPlantsOnCleansedSurfaces(GameTestHelper helper) {
        inArea(helper, 2, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk plains = area.zone(0, Biomes.PLAINS, true);
            LevelChunk taiga = area.zone(2, Biomes.TAIGA, true);
            // Still festered ground and stone grow nothing.
            for (int x = 0; x < 16; x++) {
                area.set(0, x, BASE_Y, 0, RemissionBlocks.FESTERING_GRASS.get().defaultBlockState());
                area.set(0, x, BASE_Y, 1, Blocks.STONE.defaultBlockState());
            }
            long entered = level.getGameTime();
            RemissionZoneEngine engine = new RemissionZoneEngine(level);
            engine.track(plains.getPos());
            engine.track(taiga.getPos());
            RandomSource random = RandomSource.create(1L);
            engine.tick(entered + TICKS / 2, REGROWTH.budget(), REGROWTH, random);
            helper.assertValueEqual(engine.regrown(plains.getPos()), Regrowth.COLUMNS / 2, "plains columns walked at half time");
            engine.tick(entered + TICKS, REGROWTH.budget(), REGROWTH, random);
            helper.assertValueEqual(engine.regrown(plains.getPos()), Regrowth.COLUMNS, "plains columns walked at full time");
            helper.assertValueEqual(engine.regrown(taiga.getPos()), Regrowth.COLUMNS, "taiga columns walked at full time");

            Map<BlockPos, BlockState> plainsPlants = plants(area, 0);
            Map<BlockPos, BlockState> taigaPlants = plants(area, 2);
            helper.assertTrue(plainsPlants.size() > 32, "plains regrew only " + plainsPlants.size() + " plants");
            helper.assertTrue(taigaPlants.size() > 32, "taiga regrew only " + taigaPlants.size() + " plants");
            plainsPlants.forEach((pos, state) -> helper.assertTrue(PLAINS_PLANTS.contains(state.getBlock()),
                    state + " is not a plains plant at " + pos));
            taigaPlants.forEach((pos, state) -> helper.assertTrue(TAIGA_PLANTS.contains(state.getBlock()),
                    state + " is not a taiga plant at " + pos));
            helper.assertTrue(taigaPlants.values().stream().anyMatch(state -> state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                    || state.is(Blocks.SPRUCE_SAPLING) || state.is(Blocks.SWEET_BERRY_BUSH)), "taiga regrew none of its own plants");
            for (int x = 0; x < 16; x++) {
                helper.assertTrue(level.getBlockState(area.at(0, x, BASE_Y + 1, 0)).isAir(), "a plant on festered ground");
                helper.assertTrue(level.getBlockState(area.at(0, x, BASE_Y + 1, 1)).isAir(), "a plant on stone");
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_deterministic")
    public static void regrowthIsDeterministic(GameTestHelper helper) {
        inArea(helper, 3, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = area.zone(0, Biomes.PLAINS, true);
            long entered = level.getGameTime();
            RemissionZoneEngine first = new RemissionZoneEngine(level);
            first.track(chunk.getPos());
            first.tick(entered + TICKS, REGROWTH.budget(), REGROWTH, RandomSource.create(1L));
            Map<BlockPos, BlockState> once = plants(area, 0);
            helper.assertTrue(!once.isEmpty(), "nothing regrew");
            area.clearPlants(0);
            // Another engine, another random source and the budgeted walk in small steps: the same plants.
            RemissionZoneEngine second = new RemissionZoneEngine(level);
            second.track(chunk.getPos());
            RandomSource other = RandomSource.create(99L);
            for (long now = entered; now <= entered + TICKS; now += 7) {
                second.tick(now, 3, REGROWTH, other);
            }
            second.tick(entered + TICKS, REGROWTH.budget(), REGROWTH, other);
            helper.assertValueEqual(plants(area, 0), once, "plants after regrowing again");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH + "_regrowth_off")
    public static void noRegrowthOutsideRemissionOrWhenOff(GameTestHelper helper) {
        inArea(helper, 4, area -> {
            ServerLevel level = helper.getLevel();
            LevelChunk off = area.zone(0, Biomes.PLAINS, true);
            LevelChunk outside = area.zone(2, Biomes.PLAINS, false);
            long entered = level.getGameTime();
            RemissionZoneSettings disabled = new RemissionZoneSettings(false, TICKS, 0.5, 0.2, false, INTERVAL, CAP, 4096);
            RemissionZoneEngine engine = new RemissionZoneEngine(level);
            engine.track(off.getPos());
            engine.track(outside.getPos());
            engine.tick(entered + TICKS * 2L, disabled.budget(), disabled, RandomSource.create(1L));
            helper.assertValueEqual(plants(area, 0).size(), 0, "plants with regrowth off");
            helper.assertValueEqual(engine.regrown(off.getPos()), 0, "columns walked with regrowth off");
            engine.tick(entered + TICKS * 2L, REGROWTH.budget(), REGROWTH, RandomSource.create(1L));
            helper.assertValueEqual(plants(area, 2).size(), 0, "plants in a chunk not in remission");
            helper.assertFalse(engine.tracks(outside.getPos()), "a chunk not in remission is still tracked");
            helper.assertTrue(!plants(area, 0).isEmpty(), "no plants once regrowth is on again");
        });
        helper.succeed();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static List<Mob> creatures(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        AABB box = new AABB(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(), pos.getMaxBlockX() + 1,
                level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1);
        return level.getEntitiesOfClass(Mob.class, box, mob -> mob.isAlive() && mob.getType().getCategory() == MobCategory.CREATURE);
    }

    /** Plants on the platform of area chunk {@code dx}, by position (lower halves only). */
    private static Map<BlockPos, BlockState> plants(Area area, int dx) {
        Map<BlockPos, BlockState> plants = new HashMap<>();
        ServerLevel level = area.helper().getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos pos = area.at(dx, x, BASE_Y + 1, z);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    plants.put(pos, state);
                }
            }
        }
        return plants;
    }

    private record Area(GameTestHelper helper, ChunkPos origin, List<LevelChunk> chunks) {
        BlockPos at(int dx, int localX, int y, int localZ) {
            ChunkPos pos = new ChunkPos(origin.x + dx, origin.z);
            return new BlockPos(pos.getBlockX(localX), y, pos.getBlockZ(localZ));
        }

        void set(int dx, int localX, int y, int localZ, BlockState state) {
            helper.getLevel().setBlock(at(dx, localX, y, localZ), state, Block.UPDATE_ALL);
        }

        /** Force-loads area chunk {@code dx}, lays its grass platform, sets its biome and puts it in remission or not. */
        LevelChunk zone(int dx, ResourceKey<Biome> biome, boolean inRemission) {
            ServerLevel level = helper.getLevel();
            ChunkPos pos = new ChunkPos(origin.x + dx, origin.z);
            level.setChunkForced(pos.x, pos.z, true);
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            chunks.add(chunk);
            clear(chunk);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    set(dx, x, BASE_Y, z, Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            Holder<Biome> holder = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(biome);
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> holder, level.getChunkSource().randomState().sampler());
            if (inRemission) {
                chunk.setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
            }
            return chunk;
        }

        void clearPlants(int dx) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = BASE_Y + 2; y >= BASE_Y + 1; y--) {
                        helper.getLevel().setBlock(at(dx, x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }

        private void clear(LevelChunk chunk) {
            ServerLevel level = helper.getLevel();
            ChunkPos pos = chunk.getPos();
            creatures(level, chunk).forEach(Mob::discard);
            for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
                for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                    for (int y = BASE_Y + 2; y >= BASE_Y; y--) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            chunk.removeData(RemissionAttachments.CHUNK_REMISSION);
        }

        void release() {
            ServerLevel level = helper.getLevel();
            BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
            for (LevelChunk chunk : chunks) {
                clear(chunk);
                chunk.fillBiomesFromNoise(source::getNoiseBiome, level.getChunkSource().randomState().sampler());
                level.setChunkForced(chunk.getPos().x, chunk.getPos().z, false);
            }
        }
    }

    private static Area area(GameTestHelper helper, int index) {
        ChunkPos own = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        return new Area(helper, new ChunkPos(own.x + FAR + index * SPACING, own.z - NORTH), new ArrayList<>());
    }

    /**
     * Runs {@code body} once every chunk of the area is entity ticking (entities added to a chunk whose entities are not
     * loaded yet stay hidden, and wildlife skips such chunks), then releases the area and succeeds.
     */
    private static void afterLoad(GameTestHelper helper, Area area, Runnable body) {
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || !area.chunks().stream().allMatch(chunk -> helper.getLevel()
                    .isPositionEntityTicking(chunk.getPos().getMiddleBlockPosition(BASE_Y)))) {
                return;
            }
            done[0] = true;
            try {
                body.run();
            } finally {
                area.release();
            }
            helper.succeed();
        });
    }

    private static void inArea(GameTestHelper helper, int index, Consumer<Area> body) {
        Area area = area(helper, index);
        try {
            body.accept(area);
        } finally {
            area.release();
        }
    }
}
