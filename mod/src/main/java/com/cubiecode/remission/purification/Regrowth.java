package com.cubiecode.remission.purification;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Regrowth (P5.5): a cleansed surface in a chunk in remission grows back a plant of its biome. What a column grows
 * depends only on its position and the world seed, so regrowing the same land twice places the same plants.
 *
 * <p>A biome's {@link Palette} comes from its own vegetal decoration features, the ones that generated it: every
 * {@code simple_block} feature (also inside random patches) is a ground plant (its grass, ferns, flowers, bushes), and
 * every tree feature's foliage gives a sapling, found by name ({@code <wood>_leaves} to {@code <wood>_sapling},
 * {@code <wood>_propagule} or {@code <wood>}). Only bush plants that can survive on the ground below are placed.
 */
public final class Regrowth {
    /** Columns in a chunk. */
    public static final int COLUMNS = 256;
    private static final int MAX_DEPTH = 8;
    private static final long SALT = 0x5245_4752_4F57_5448L;
    private static final Map<Biome, Palette> PALETTES = Collections.synchronizedMap(new WeakHashMap<>());

    private Regrowth() {
    }

    /**
     * The plants a biome regrows.
     *
     * @param ground providers of its ground plants, in feature order
     * @param trees  providers of its trees' foliage, in feature order
     */
    public record Palette(List<BlockStateProvider> ground, List<BlockStateProvider> trees) {
        public boolean isEmpty() {
            return ground.isEmpty() && trees.isEmpty();
        }
    }

    /** {@code biome}'s palette, read from its vegetal decoration features once and cached. */
    public static Palette palette(Holder<Biome> biome) {
        return PALETTES.computeIfAbsent(biome.value(), Regrowth::readPalette);
    }

    private static Palette readPalette(Biome biome) {
        Set<BlockStateProvider> ground = new LinkedHashSet<>();
        Set<BlockStateProvider> trees = new LinkedHashSet<>();
        List<HolderSet<PlacedFeature>> steps = biome.getGenerationSettings().features();
        int vegetal = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (steps.size() > vegetal) {
            for (Holder<PlacedFeature> placed : steps.get(vegetal)) {
                collect(placed.value().feature().value(), ground, trees, 0);
            }
        }
        return new Palette(List.copyOf(ground), List.copyOf(trees));
    }

    private static void collect(ConfiguredFeature<?, ?> feature, Set<BlockStateProvider> ground, Set<BlockStateProvider> trees,
                                int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        feature.getFeatures().forEach(nested -> {
            if (nested.config() instanceof SimpleBlockConfiguration simple) {
                ground.add(simple.toPlace());
            } else if (nested.config() instanceof TreeConfiguration tree) {
                trees.add(tree.foliageProvider);
            } else if (nested.config() instanceof RandomPatchConfiguration patch) {
                collect(patch.feature().value().feature().value(), ground, trees, depth + 1);
            }
        });
    }

    /** The sapling that grows {@code leaves}' tree, found by name, or null if there is none. */
    @Nullable
    public static BlockState sapling(BlockState leaves) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(leaves.getBlock());
        String path = id.getPath();
        if (!path.endsWith("_leaves")) {
            return null;
        }
        String wood = path.substring(0, path.length() - "_leaves".length());
        for (String candidate : List.of(wood + "_sapling", wood + "_propagule", wood)) {
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id.withPath(candidate));
            if (block.isPresent() && block.get() instanceof BushBlock) {
                return block.get().defaultBlockState();
            }
        }
        return null;
    }

    /**
     * The biome of the noise cell holding {@code pos}: the chunk's own (restored) biome there, without the fuzzy zoom of
     * {@code level.getBiome}, which can read a neighbouring chunk's biome near the border.
     */
    public static Holder<Biome> biomeAt(ServerLevel level, BlockPos pos) {
        return level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
    }

    /** The random source of column ({@code x}, {@code z}): the same for the same world seed and column. */
    public static RandomSource columnRandom(long worldSeed, int x, int z) {
        return RandomSource.create(Mth.getSeed(x, 0, z) ^ worldSeed ^ SALT);
    }

    /**
     * The plant column ({@code x}, {@code z}) regrows at {@code pos} (the free block above its surface), or null if the
     * column regrows nothing: it is not picked by {@code density}, or its biome has no plant for it.
     */
    @Nullable
    public static BlockState plantFor(ServerLevel level, BlockPos pos, double density, double saplingChance) {
        RandomSource random = columnRandom(level.getSeed(), pos.getX(), pos.getZ());
        if (random.nextDouble() >= density) {
            return null;
        }
        Palette palette = palette(biomeAt(level, pos));
        boolean sapling = random.nextDouble() < saplingChance && !palette.trees().isEmpty();
        List<BlockStateProvider> pool = sapling ? palette.trees() : palette.ground();
        if (pool.isEmpty()) {
            return null;
        }
        BlockState state = pool.get(random.nextInt(pool.size())).getState(random, pos);
        return sapling ? sapling(state) : state;
    }

    /**
     * Regrows column ({@code x}, {@code z}): if its surface is clean, with nothing on it, it gets {@link #plantFor} when
     * that is a bush that can survive there. Returns whether a plant was placed.
     */
    public static boolean grow(ServerLevel level, int x, int z, double density, double saplingChance) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 1) {
            return false;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState ground = level.getBlockState(pos.below());
        if (!level.getBlockState(pos).isAir() || !ground.getFluidState().isEmpty() || Cleansing.cleansable(ground)) {
            return false;
        }
        BlockState plant = plantFor(level, pos, density, saplingChance);
        if (plant == null || !(plant.getBlock() instanceof BushBlock) || !plant.canSurvive(level, pos)) {
            return false;
        }
        if (plant.getBlock() instanceof DoublePlantBlock) {
            if (!level.isEmptyBlock(pos.above())) {
                return false;
            }
            DoublePlantBlock.placeAt(level, plant, pos, Block.UPDATE_ALL);
        } else {
            level.setBlock(pos, plant, Block.UPDATE_ALL);
        }
        return true;
    }
}
