package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.BlockFesteredEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.registry.RemissionBlocks;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Festering veins (P4.8): {@code remission:festering_vein} creeps over the solid faces of blocks in chunks at or above
 * {@code [festering] veinMinStage}, on the surface, in caves and over player builds, without replacing anything. A vein
 * goes into an air block of the chunk, on each side whose neighbour can hold a multiface block
 * ({@link MultifaceBlock#canAttachTo}) and is not in {@link #VEIN_RESISTANT}, where {@link #selected} picks that face
 * for the chunk's {@link #coverage}. The pick is a hash of the position and side, so the veins depend only on the blocks
 * and the stage, never on scan order, timing or reloads, and a higher stage covers a superset of a lower one.
 *
 * <p>Work is queued per level when a chunk loads or its stage rises, and every chunk that grew is visited again
 * {@link #REVISIT_TICKS} later while it stays loaded ({@link VeinGrowth}), so faces that appear later (a wall a player built, a block broken
 * out of a cave wall) are overgrown too. Each tick walks up to {@link #LAYER_BUDGET} block layers of the current chunk,
 * bottom to top, and skips sections that hold no air, or only air with only air around them, for one unit each. A chunk
 * unloaded mid-way is queued again by its load event and done from the start, so nothing is saved; a chunk that loads
 * at a higher stage gets that stage's veins at once (catch-up).
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class FesteringVeins {
    /** Blocks veins never grow on. Empty by default; packs fill it. */
    public static final TagKey<Block> VEIN_RESISTANT = TagKey.create(Registries.BLOCK, Remission.id("vein_resistant"));
    /** Block layers (16 by 16 blocks at one height) grown, or sections or queued chunks skipped, per tick and level. */
    public static final int LAYER_BUDGET = 8;
    /** Game ticks before a chunk that grew veins is visited again, if it is still loaded. */
    public static final int REVISIT_TICKS = 1200;
    /** Share of eligible faces (in percent) covered per stage from the minimum stage up: 15, 30, 45... */
    public static final int COVERAGE_PER_STAGE = 15;
    /** Most faces (in percent) any stage covers. */
    public static final int MAX_COVERAGE = 60;
    /** Side of the cubic cells whose own hash scales coverage, so veins grow in patches rather than evenly. */
    private static final int PATCH_SHIFT = 2;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final Map<ServerLevel, VeinGrowth> GROWTH = new IdentityHashMap<>();

    private FesteringVeins() {
    }

    /** The vein pass that runs on {@code level}'s ticks with the configured settings. */
    public static VeinGrowth growth(ServerLevel level) {
        synchronized (GROWTH) {
            return GROWTH.computeIfAbsent(level, l -> new VeinGrowth(l, VeinSettings::fromConfig));
        }
    }

    /**
     * Percent of eligible faces covered at festering stage {@code stage} when veins grow from {@code minStage}: 0 below
     * it, then {@link #COVERAGE_PER_STAGE} more per stage up to {@link #MAX_COVERAGE}.
     */
    public static int coverage(int stage, int minStage) {
        return stage < minStage ? 0 : Math.min(MAX_COVERAGE, COVERAGE_PER_STAGE * (stage - minStage + 1));
    }

    /** {@link #coverage(int, int)} for a chunk's current stage and the configured minimum stage. */
    public static int coverage(ServerLevel level, ChunkPos chunk) {
        return coverage(CorruptionQueries.chunkStage(level, chunk), VeinSettings.fromConfig().minStage());
    }

    /**
     * Whether the vein face on side {@code side} of the air block at {@code pos} is grown at {@code coverage}. The face's
     * own hash is compared against the coverage scaled by its patch (a 4-block cell, weight 0 to 2 by its hash), so the
     * mean share is {@code coverage} percent, some patches stay bare and others are dense.
     */
    public static boolean selected(BlockPos pos, Direction side, int coverage) {
        if (coverage <= 0) {
            return false;
        }
        long patch = BlockPos.asLong(pos.getX() >> PATCH_SHIFT, pos.getY() >> PATCH_SHIFT, pos.getZ() >> PATCH_SHIFT);
        int weight = (int) Long.remainderUnsigned(HashCommon.mix(patch ^ 0x5DEECE66DL), 5);
        int face = (int) Long.remainderUnsigned(HashCommon.mix(pos.asLong() * 6 + side.ordinal()), 200);
        return face < coverage * weight;
    }

    /** Grows every layer of a loaded chunk at once at its current stage, ignoring the budget (harness and tests). */
    public static int growNow(ServerLevel level, LevelChunk chunk) {
        return growNow(level, chunk, coverage(level, chunk.getPos()), FesteringVeins::resistant);
    }

    /** {@link #growNow(ServerLevel, LevelChunk)} at {@code coverage}, with {@code resistant} in place of the tag (tests). */
    public static int growNow(ServerLevel level, LevelChunk chunk, int coverage, Predicate<BlockState> resistant) {
        int changed = 0;
        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
            changed += growLayer(level, chunk, y, coverage, resistant);
        }
        return changed;
    }

    /** Grows the veins of one block layer of a chunk at {@code coverage}. Returns the number of blocks changed. */
    public static int growLayer(ServerLevel level, LevelChunk chunk, int y, int coverage) {
        return growLayer(level, chunk, y, coverage, FesteringVeins::resistant);
    }

    /**
     * Grows the veins of one block layer: every air block (or vein) at height {@code y} gets each face that
     * {@link #selected} picks and whose neighbour holds a vein and is not {@code resistant}; a neighbour in a chunk that
     * is not loaded is left for a later visit. One {@link BlockFesteredEvent} per block changed. Returns that number.
     */
    public static int growLayer(ServerLevel level, LevelChunk chunk, int y, int coverage, Predicate<BlockState> resistant) {
        if (coverage <= 0 || y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) {
            return 0;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        Block vein = RemissionBlocks.FESTERING_VEIN.get();
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                BlockState state = section.getBlockState(localX, y & 15, localZ);
                if (!state.isAir() && !state.is(vein)) {
                    continue;
                }
                pos.set(chunkPos.getBlockX(localX), y, chunkPos.getBlockZ(localZ));
                BlockState grown = state;
                for (Direction direction : DIRECTIONS) {
                    if (MultifaceBlock.hasFace(grown, direction) || !selected(pos, direction, coverage)) {
                        continue;
                    }
                    side.setWithOffset(pos, direction);
                    BlockState neighbour = neighbour(level, chunk, side);
                    if (neighbour == null || neighbour.isAir() || neighbour.is(vein) || resistant.test(neighbour)
                            || !MultifaceBlock.canAttachTo(level, direction, side, neighbour)) {
                        continue;
                    }
                    if (!grown.is(vein)) {
                        grown = vein.defaultBlockState();
                    }
                    grown = grown.setValue(MultifaceBlock.getFaceProperty(direction), true);
                }
                if (grown != state && level.setBlock(pos, grown, FLAGS)) {
                    NeoForge.EVENT_BUS.post(new BlockFesteredEvent(level, pos.immutable(), state, grown));
                    changed++;
                }
            }
        }
        return changed;
    }

    private static boolean resistant(BlockState state) {
        return state.is(VEIN_RESISTANT);
    }

    /** The block at {@code pos}, or null outside the build height or in a chunk that is not loaded. */
    private static BlockState neighbour(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        LevelChunk holder = chunkX == chunk.getPos().x && chunkZ == chunk.getPos().z ? chunk
                : level.getChunkSource().getChunkNow(chunkX, chunkZ);
        return holder == null ? null : holder.getBlockState(pos);
    }

    /**
     * Whether section {@code index} of {@code chunk} can hold no new vein: its palette has no air, or it is all air and
     * so are the sections above, below and beside it (a side in a chunk that is not loaded counts as air).
     */
    static boolean skipSection(ServerLevel level, LevelChunk chunk, int index) {
        LevelChunkSection section = chunk.getSection(index);
        if (!section.hasOnlyAir()) {
            return !section.getStates().maybeHas(BlockState::isAir);
        }
        if (!onlyAir(chunk, index - 1) || !onlyAir(chunk, index + 1)) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            LevelChunk beside = level.getChunkSource().getChunkNow(chunk.getPos().x + direction.getStepX(),
                    chunk.getPos().z + direction.getStepZ());
            if (beside != null && !onlyAir(beside, index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean onlyAir(LevelChunk chunk, int index) {
        return index < 0 || index >= chunk.getSectionsCount() || chunk.getSection(index).hasOnlyAir();
    }

    @SubscribeEvent
    static void onStageChanged(ChunkStageChangedEvent event) {
        if (event.getNewStage() > event.getOldStage()) {
            growth(event.getLevel()).queue(event.getChunk());
        }
    }

    // Load fires before the chunk is in the level; the stage check happens when the queue reaches it.
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            growth(level).queue(chunk.getPos());
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            growth(level).unloaded(event.getChunk().getPos());
        }
    }

    // Frozen ticks (/tick freeze, harness scenes) grow nothing; stepped ticks do.
    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.tickRateManager().runsNormally()) {
            growth(level).tick(LAYER_BUDGET);
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (GROWTH) {
                GROWTH.remove(level);
            }
        }
    }
}
