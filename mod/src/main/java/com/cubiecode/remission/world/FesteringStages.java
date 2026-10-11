package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.registry.RemissionAttachments;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.chunk.LevelChunkSection;
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
 * chunk's stage rises ({@link ChunkStageChangedEvent}) or a chunk loads, and done in bounded batches per tick: first
 * the surface, column by column, then the caves (P4.6), layer by layer on their own budget. The {@code festered_stage}
 * chunk attachment records the stage a chunk was last brought up to and is written only after both passes, so an
 * interrupted chunk is redone (both passes are idempotent) after it loads again.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class FesteringStages {
    /** Columns festered (or queued chunks skipped) per tick and level. */
    public static final int COLUMN_BUDGET = 64;
    /** Blocks below and including the first ground block of a column that can fester. */
    public static final int GROUND_DEPTH = 5;
    /** Block layers (16 by 16 blocks at one height) of the cave pass, or sections skipped, per tick and level. */
    public static final int LAYER_BUDGET = 8;
    /** Lowest height at which every exposed block festers in the cave pass; below it only one in {@link #SPARSE}. */
    public static final int FULL_DEPTH_Y = 0;
    /** Below {@link #FULL_DEPTH_Y}, one in this many exposed blocks festers, picked by a hash of its position. */
    public static final int SPARSE = 4;
    private static final int COLUMNS = 16 * 16;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final Map<ServerLevel, Work> WORK = new IdentityHashMap<>();
    private static final Map<ServerLevel, Work> CAVES = new IdentityHashMap<>();
    private static final Set<ResourceKey<Biome>> WARNED = new HashSet<>();

    private FesteringStages() {
    }

    /**
     * Queued chunks of one level and the chunk being worked on; {@code step} is a column or a cave layer, -1 if idle.
     * The cave pass queues each chunk with the stage its surface pass reached ({@code stages}). The surface work also
     * keeps chunks that left full status before their work was done ({@code parked}) until they are back or unloaded.
     */
    private static final class Work {
        final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
        final Long2IntOpenHashMap stages = new Long2IntOpenHashMap();
        final LongOpenHashSet parked = new LongOpenHashSet();
        long chunk;
        int stage;
        int step = -1;
    }

    private static Work work(ServerLevel level) {
        synchronized (WORK) {
            return WORK.computeIfAbsent(level, l -> new Work());
        }
    }

    private static Work caves(ServerLevel level) {
        synchronized (CAVES) {
            return CAVES.computeIfAbsent(level, l -> new Work());
        }
    }

    /** Queues {@code chunk} to be brought up to its current festering stage. */
    public static void queue(ServerLevel level, ChunkPos chunk) {
        work(level).queue.add(chunk.toLong());
    }

    /** Whether {@code level} has chunks queued or a chunk in progress, in either pass. */
    public static boolean busy(ServerLevel level) {
        Work work = work(level);
        Work caves = caves(level);
        return work.step >= 0 || !work.queue.isEmpty() || caves.step >= 0 || !caves.queue.isEmpty();
    }

    /** The stage {@code chunk} was last brought up to (0 if never). */
    public static int festeredStage(LevelChunk chunk) {
        Integer stage = chunk.getExistingDataOrNull(RemissionAttachments.FESTERED_STAGE);
        return stage == null ? 0 : stage;
    }

    /** Does up to {@code budget} units of queued surface work: one per column festered or queued chunk skipped. */
    public static void tick(ServerLevel level, int budget) {
        Work work = work(level);
        unpark(level, work);
        while (budget > 0) {
            if (work.step < 0) {
                budget--;
                if (!start(level, work)) {
                    if (work.queue.isEmpty()) {
                        return;
                    }
                    continue;
                }
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(work.chunk), ChunkPos.getZ(work.chunk));
            if (chunk == null) {
                // Left full status mid-way: its festered stage is still behind, so it is queued again when it is back.
                park(level, work.chunk);
                work.step = -1;
                continue;
            }
            while (work.step < COLUMNS && budget > 0) {
                festerColumn(level, chunk, work.step & 15, work.step >> 4, work.stage);
                work.step++;
                budget--;
            }
            if (work.step == COLUMNS) {
                if (work.stage >= CorruptionQueries.maxChunkStage()) {
                    flipBiomes(level, chunk);
                }
                Work caves = caves(level);
                caves.queue.add(work.chunk);
                caves.stages.put(work.chunk, work.stage);
                work.step = -1;
            }
        }
    }

    /**
     * Does up to {@code budget} units of queued cave work: one per block layer festered, section skipped or queued chunk
     * skipped. A chunk whose cave pass finishes is recorded at its stage.
     */
    public static void tickCaves(ServerLevel level, int budget) {
        Work work = caves(level);
        while (budget > 0) {
            if (work.step < 0) {
                budget--;
                if (!start(level, work)) {
                    if (work.queue.isEmpty()) {
                        return;
                    }
                    continue;
                }
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(work.chunk), ChunkPos.getZ(work.chunk));
            if (chunk == null) {
                park(level, work.chunk);
                work.step = -1;
                continue;
            }
            int layers = chunk.getHeight();
            while (work.step < layers && budget > 0) {
                int y = chunk.getMinBuildHeight() + work.step;
                if ((work.step & 15) == 0 && !mayFesterUnderground(chunk.getSection(work.step >> 4), work.stage, FesteringStages::entry)) {
                    work.step += 16;
                } else {
                    festerCaves(level, chunk, y, work.stage);
                    work.step++;
                }
                budget--;
            }
            if (work.step >= layers) {
                record(chunk, work.stage);
                work.step = -1;
            }
        }
    }

    /**
     * Takes the next queued chunk that is loaded and behind its stage (the current one, or for the cave pass the one its
     * surface pass reached); false if the one taken was skipped or none was queued.
     */
    private static boolean start(ServerLevel level, Work work) {
        if (work.queue.isEmpty()) {
            return false;
        }
        long next = work.queue.removeFirstLong();
        int queued = work.stages.remove(next);
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(next), ChunkPos.getZ(next));
        if (chunk == null) {
            park(level, next);
            return false;
        }
        int stage = queued > 0 ? queued : CorruptionQueries.chunkStage(level, chunk.getPos());
        if (stage <= festeredStage(chunk)) {
            return false;
        }
        work.chunk = next;
        work.stage = stage;
        work.step = 0;
        return true;
    }

    /**
     * Keeps a chunk that is not at full status for later. A chunk that unloads is queued again by its load event, but one
     * that only drops below full status and comes back gets none, so it is queued when {@link #unpark} sees it again.
     */
    private static void park(ServerLevel level, long chunk) {
        work(level).parked.add(chunk);
    }

    private static void unpark(ServerLevel level, Work work) {
        if (work.parked.isEmpty()) {
            return;
        }
        LongIterator parked = work.parked.iterator();
        while (parked.hasNext()) {
            long chunk = parked.nextLong();
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) {
                work.queue.add(chunk);
                parked.remove();
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
        if (stage >= CorruptionQueries.maxChunkStage()) {
            flipBiomes(level, chunk);
        }
        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
            festerCaves(level, chunk, y, stage);
        }
        record(chunk, stage);
    }

    private static void record(LevelChunk chunk, int stage) {
        if (stage > festeredStage(chunk)) {
            chunk.setData(RemissionAttachments.FESTERED_STAGE, stage);
        }
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

    /**
     * The cave pass over one block layer of a chunk at {@code stage} (P4.6): every block at height {@code y} whose
     * {@code festers_into} entry {@linkplain FesterMaps.FesterBlock#festersUnderground festers underground} at this stage
     * and that faces air on any side is replaced, at or above {@link #FULL_DEPTH_Y}, or below it where
     * {@link #festersBelowFullDepth} picks it. Rock without air beside it and blocks without an entry (ores) stay.
     * Returns the number of blocks changed.
     */
    public static int festerCaves(ServerLevel level, LevelChunk chunk, int y, int stage) {
        return festerCaves(level, chunk, y, stage, FesteringStages::entry);
    }

    /** {@link #festerCaves(ServerLevel, LevelChunk, int, int)} with the entries from {@code entries} (tests). */
    public static int festerCaves(ServerLevel level, LevelChunk chunk, int y, int stage,
                                  Function<BlockState, FesterMaps.FesterBlock> entries) {
        if (stage <= 0 || y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
            return 0;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        if (!mayFesterUnderground(section, stage, entries)) {
            return 0;
        }
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                BlockState state = section.getBlockState(localX, y & 15, localZ);
                if (state.isAir()) {
                    continue;
                }
                FesterMaps.FesterBlock entry = entries.apply(state);
                if (entry == null || !entry.festersUnderground(stage)) {
                    continue;
                }
                pos.set(chunkPos.getBlockX(localX), y, chunkPos.getBlockZ(localZ));
                if ((y >= FULL_DEPTH_Y || festersBelowFullDepth(pos)) && facesAir(level, chunk, pos, side)
                        && festerBlock(level, pos, state, entry)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** Whether the cave pass festers an exposed block at {@code pos} below {@link #FULL_DEPTH_Y}: one in {@link #SPARSE}. */
    public static boolean festersBelowFullDepth(BlockPos pos) {
        return Long.remainderUnsigned(HashCommon.mix(pos.asLong()), SPARSE) == 0;
    }

    /** Whether {@code section}'s palette holds a block the cave pass could fester at {@code stage}. */
    private static boolean mayFesterUnderground(LevelChunkSection section, int stage,
                                                Function<BlockState, FesterMaps.FesterBlock> entries) {
        return !section.hasOnlyAir() && section.getStates().maybeHas(state -> {
            FesterMaps.FesterBlock entry = entries.apply(state);
            return entry != null && entry.festersUnderground(stage) && !state.is(entry.block());
        });
    }

    /** Whether any side of {@code pos} is air; a side in a chunk that is not loaded counts as solid. */
    private static boolean facesAir(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockPos.MutableBlockPos side) {
        ChunkPos chunkPos = chunk.getPos();
        for (Direction direction : DIRECTIONS) {
            side.setWithOffset(pos, direction);
            if (level.isOutsideBuildHeight(side)) {
                continue;
            }
            LevelChunk holder = chunk;
            int chunkX = SectionPos.blockToSectionCoord(side.getX());
            int chunkZ = SectionPos.blockToSectionCoord(side.getZ());
            if (chunkX != chunkPos.x || chunkZ != chunkPos.z) {
                holder = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (holder == null) {
                    continue;
                }
            }
            if (holder.getBlockState(side).isAir()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static FesterMaps.FesterBlock entry(BlockState state) {
        return state.getBlockHolder().getData(FesterMaps.FESTERS_INTO);
    }

    private static boolean festerBlock(ServerLevel level, BlockPos pos, BlockState state, int stage) {
        FesterMaps.FesterBlock entry = entry(state);
        return entry != null && entry.stage() <= stage && festerBlock(level, pos, state, entry);
    }

    private static boolean festerBlock(ServerLevel level, BlockPos pos, BlockState state, FesterMaps.FesterBlock entry) {
        // Blocks with block entities are never replaced: their contents would be lost.
        if (state.is(entry.block()) || state.hasBlockEntity()) {
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
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            work(level).parked.remove(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            tick(level, COLUMN_BUDGET);
            tickCaves(level, LAYER_BUDGET);
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (WORK) {
                WORK.remove(level);
            }
            synchronized (CAVES) {
                CAVES.remove(level);
            }
        }
    }
}
