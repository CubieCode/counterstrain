package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.registry.RemissionAttachments;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Applies a chunk's festering stage to its land (P4.2): blocks turn through the {@code remission:festers_into} data map
 * as the stage rises, and at the last stage the chunk's biome flips to its fester biome. Work is queued per level when a
 * chunk's stage rises ({@link ChunkStageChangedEvent}) or a chunk loads, and done in bounded batches of columns per
 * tick. The {@code festered_stage} chunk attachment records the stage a chunk was last brought up to, so an interrupted
 * chunk finishes after it loads again.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class FesteringStages {
    /** Columns festered (or queued chunks skipped) per tick and level. */
    public static final int COLUMN_BUDGET = 64;
    /** Blocks below and including the first ground block of a column that can fester. */
    public static final int GROUND_DEPTH = 5;
    private static final int COLUMNS = 16 * 16;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final Map<ServerLevel, Work> WORK = new IdentityHashMap<>();
    private static final Set<ResourceKey<Biome>> WARNED = new HashSet<>();

    private FesteringStages() {
    }

    /** Queued chunks of one level and the chunk being worked on. */
    private static final class Work {
        final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
        long chunk;
        int stage;
        int column = -1;
    }

    private static Work work(ServerLevel level) {
        synchronized (WORK) {
            return WORK.computeIfAbsent(level, l -> new Work());
        }
    }

    /** Queues {@code chunk} to be brought up to its current festering stage. */
    public static void queue(ServerLevel level, ChunkPos chunk) {
        work(level).queue.add(chunk.toLong());
    }

    /** Whether {@code level} has chunks queued or a chunk in progress. */
    public static boolean busy(ServerLevel level) {
        Work work = work(level);
        return work.column >= 0 || !work.queue.isEmpty();
    }

    /** The stage {@code chunk} was last brought up to (0 if never). */
    public static int festeredStage(LevelChunk chunk) {
        Integer stage = chunk.getExistingDataOrNull(RemissionAttachments.FESTERED_STAGE);
        return stage == null ? 0 : stage;
    }

    /** Does up to {@code budget} units of queued work: one per column festered or queued chunk skipped. */
    public static void tick(ServerLevel level, int budget) {
        Work work = work(level);
        while (budget > 0) {
            if (work.column < 0) {
                if (work.queue.isEmpty()) {
                    return;
                }
                long next = work.queue.removeFirstLong();
                budget--;
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(next), ChunkPos.getZ(next));
                if (chunk == null) {
                    continue;
                }
                int stage = CorruptionQueries.chunkStage(level, chunk.getPos());
                if (stage <= festeredStage(chunk)) {
                    continue;
                }
                work.chunk = next;
                work.stage = stage;
                work.column = 0;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(work.chunk), ChunkPos.getZ(work.chunk));
            if (chunk == null) {
                // Unloaded mid-way: its festered stage is still behind, so it is queued again when it loads.
                work.column = -1;
                continue;
            }
            while (work.column < COLUMNS && budget > 0) {
                festerColumn(level, chunk, work.column & 15, work.column >> 4, work.stage);
                work.column++;
                budget--;
            }
            if (work.column == COLUMNS) {
                finish(level, chunk, work.stage);
                work.column = -1;
            }
        }
    }

    /** Brings a loaded chunk up to its current stage at once, ignoring the budget (harness and tests). */
    public static void festerNow(ServerLevel level, LevelChunk chunk) {
        int stage = CorruptionQueries.chunkStage(level, chunk.getPos());
        if (stage <= festeredStage(chunk)) {
            return;
        }
        for (int column = 0; column < COLUMNS; column++) {
            festerColumn(level, chunk, column & 15, column >> 4, stage);
        }
        finish(level, chunk, stage);
    }

    private static void finish(ServerLevel level, LevelChunk chunk, int stage) {
        if (stage >= CorruptionQueries.maxChunkStage()) {
            flipBiomes(level, chunk);
        }
        chunk.setData(RemissionAttachments.FESTERED_STAGE, stage);
    }

    /**
     * Festers one column of a chunk at {@code stage}: from its highest block down through trees and plants to
     * {@link #GROUND_DEPTH} blocks into the ground (the first block that blocks motion and is not a log or leaves), every
     * block with a {@code festers_into} entry at or below {@code stage} is replaced. Returns the number of blocks changed.
     */
    public static int festerColumn(ServerLevel level, LevelChunk chunk, int localX, int localZ, int stage) {
        if (stage <= 0) {
            return 0;
        }
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getBlockX(localX), top, chunk.getPos().getBlockZ(localZ));
        int changed = 0;
        int depth = -1;
        for (int y = top; y >= level.getMinBuildHeight(); y--) {
            pos.setY(y);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && festerBlock(level, pos, state, stage)) {
                changed++;
            }
            if (depth < 0) {
                if (state.blocksMotion() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
                    depth = 1;
                }
            } else if (++depth > GROUND_DEPTH) {
                break;
            }
        }
        return changed;
    }

    private static boolean festerBlock(ServerLevel level, BlockPos pos, BlockState state, int stage) {
        FesterMaps.FesterBlock entry = state.getBlockHolder().getData(FesterMaps.FESTERS_INTO);
        // Blocks with block entities are never replaced: their contents would be lost.
        if (entry == null || entry.stage() > stage || state.is(entry.block()) || state.hasBlockEntity()) {
            return false;
        }
        BlockState festered = copyProperties(state, entry.block().defaultBlockState());
        if (!level.setBlock(pos, festered, FLAGS)) {
            return false;
        }
        NeoForge.EVENT_BUS.post(new BlockFesteredEvent(level, pos, state, festered));
        return true;
    }

    private static BlockState copyProperties(BlockState from, BlockState to) {
        for (Property<?> property : from.getProperties()) {
            if (to.hasProperty(property)) {
                to = copy(from, to, property);
            }
        }
        return to;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    /**
     * Flips every biome cell of {@code chunk} to its fester biome ({@link #festerTarget}) and resends the chunk's
     * biomes to players who have it (vanilla's {@code /fillbiome} path). Returns whether any cell changed.
     */
    public static boolean flipBiomes(ServerLevel level, LevelChunk chunk) {
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        if (biomes.getHolder(FesterMaps.FESTER).isEmpty()) {
            warnMissing(FesterMaps.FESTER);
            return false;
        }
        Set<ResourceKey<Biome>> festered = festerBiomes(biomes);
        return flipBiomes(level, chunk, source -> festerTarget(source, biomes, festered));
    }

    /** Flips every biome cell of {@code chunk} through {@code target} and resends the chunk's biomes if any changed. */
    public static boolean flipBiomes(ServerLevel level, LevelChunk chunk, UnaryOperator<Holder<Biome>> target) {
        Map<Holder<Biome>, Holder<Biome>> targets = new HashMap<>();
        boolean[] changed = {false};
        chunk.fillBiomesFromNoise((x, y, z, sampler) -> {
            Holder<Biome> source = chunk.getNoiseBiome(x, y, z);
            Holder<Biome> flipped = targets.computeIfAbsent(source, target);
            if (flipped != source) {
                changed[0] = true;
            }
            return flipped;
        }, level.getChunkSource().randomState().sampler());
        if (changed[0]) {
            chunk.setUnsaved(true);
            level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
        }
        return changed[0];
    }

    /** {@link FesterMaps#FESTER} and every biome a {@code fester_variant} entry names: biomes that never flip again. */
    public static Set<ResourceKey<Biome>> festerBiomes(Registry<Biome> biomes) {
        Set<ResourceKey<Biome>> festered = new HashSet<>();
        festered.add(FesterMaps.FESTER);
        biomes.getDataMap(FesterMaps.FESTER_VARIANT).values().forEach(variant -> festered.add(variant.biome()));
        return festered;
    }

    /** The biome {@code source} flips to in {@code biomes}; a variant naming a missing biome falls back to the fester biome. */
    public static Holder<Biome> festerTarget(Holder<Biome> source, Registry<Biome> biomes, Set<ResourceKey<Biome>> festered) {
        ResourceKey<Biome> key = festerTarget(source.unwrapKey().orElse(null), source.getData(FesterMaps.FESTER_VARIANT), festered);
        if (source.is(key)) {
            return source;
        }
        return biomes.getHolder(key).<Holder<Biome>>map(holder -> holder).orElseGet(() -> {
            warnMissing(key);
            return biomes.getHolderOrThrow(FesterMaps.FESTER);
        });
    }

    /**
     * The key {@code source} flips to: itself if it is already a fester biome ({@code festered}), else the biome its
     * {@code fester_variant} entry names, else {@link FesterMaps#FESTER}.
     */
    public static ResourceKey<Biome> festerTarget(@Nullable ResourceKey<Biome> source,
                                                  @Nullable FesterMaps.FesterVariant variant,
                                                  Set<ResourceKey<Biome>> festered) {
        if (source != null && festered.contains(source)) {
            return source;
        }
        return variant == null ? FesterMaps.FESTER : variant.biome();
    }

    private static void warnMissing(ResourceKey<Biome> key) {
        synchronized (WARNED) {
            if (WARNED.add(key)) {
                Remission.LOGGER.warn("Fester biome {} is not registered; festering land falls back to {}", key.location(),
                        FesterMaps.FESTER.location());
            }
        }
    }

    @SubscribeEvent
    static void onStageChanged(ChunkStageChangedEvent event) {
        if (event.getNewStage() > event.getOldStage()) {
            queue(event.getLevel(), event.getChunk());
        }
    }

    // Load fires before the chunk is in the level; the stage check happens when the queue reaches it.
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            queue(level, chunk.getPos());
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            tick(level, COLUMN_BUDGET);
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (WORK) {
                WORK.remove(level);
            }
        }
    }
}
