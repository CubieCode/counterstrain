package com.cubiecode.remission.purification;

import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.PurificationCause;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * One level's purification work (P5.2): quarantine pylon passes and the budgeted chunk cleanse.
 *
 * <p>Pylon pass: at each multiple of {@code intervalTicks} of level game time, every loaded chunk in some pylon's ward
 * (each once, in {@link ChunkPos#toLong()} order) loses {@code pylonStep} Strain level through
 * {@link Purification#lowerLevel}; a chunk that reaches 0 enters remission. Pylons whose loaded block is gone are dropped
 * from the index first. A pass is cheap (one attachment read per warded chunk), so it is not budgeted.
 *
 * <p>Cleanse: chunks are queued when a pylon starts warding them, when a warded chunk loads and when a chunk enters
 * remission. Each tick walks up to a budget of block layers of the current chunk, bottom to top, cleansing every
 * festered block and vein ({@link Cleansing#cleanseLayer}); a section whose palette holds nothing to cleanse costs one
 * unit. A finished chunk gets its biome back and forgets its festered stage ({@link Cleansing#finishChunk}). A chunk
 * that leaves full status mid-way is parked and redone when it is back; one that unloads is dropped (its load event
 * queues it again if it is warded). Nothing is saved.
 */
public final class PurificationEngine {
    private final ServerLevel level;
    private final Supplier<PurificationSettings> settings;
    private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    private final Long2ObjectOpenHashMap<PurificationCause> causes = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet parked = new LongOpenHashSet();
    private long nextPass = Long.MIN_VALUE;
    private long chunk;
    private PurificationCause cause = PurificationCause.OTHER;
    /** Block layer of the current chunk from its bottom, or -1 if idle. */
    private int step = -1;

    public PurificationEngine(ServerLevel level, Supplier<PurificationSettings> settings) {
        this.level = level;
        this.settings = settings;
    }

    /** Queues {@code pos} to be cleansed; a chunk already queued keeps its first cause. */
    public void queue(ChunkPos pos, PurificationCause why) {
        long key = pos.toLong();
        if (queue.add(key)) {
            causes.put(key, why);
        }
    }

    /** Whether chunks are queued for a cleanse or one is in progress. */
    public boolean busy() {
        return step >= 0 || !queue.isEmpty();
    }

    /** Forgets {@code pos}: it unloaded. */
    public void unloaded(ChunkPos pos) {
        long key = pos.toLong();
        parked.remove(key);
        queue.remove(key);
        causes.remove(key);
        if (step >= 0 && chunk == key) {
            step = -1;
        }
    }

    /** Runs the pylon pass when one is due at {@code now}. Returns the number of warded chunks it visited. */
    public int pass(long now) {
        PurificationSettings current = settings.get();
        if (now < nextPass) {
            return 0;
        }
        nextPass = (Math.floorDiv(now, current.intervalTicks()) + 1) * current.intervalTicks();
        return passNow(current);
    }

    /** One pylon pass now, whatever the time: drops vanished pylons, then lowers every loaded warded chunk. */
    public int passNow(PurificationSettings current) {
        PylonIndex index = PylonIndex.get(level);
        if (index.isEmpty()) {
            return 0;
        }
        for (BlockPos pylon : index.pylons()) {
            Purification.validate(level, pylon);
        }
        // Copied first: entering remission posts events whose listeners may place or break pylons.
        LongArrayList warded = new LongArrayList(index.wardedChunks(current.pylonRadius()));
        int visited = 0;
        for (int i = 0; i < warded.size(); i++) {
            long key = warded.getLong(i);
            LevelChunk loaded = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (loaded != null) {
                visited++;
                Purification.lowerLevel(level, loaded, current.pylonStep(), PurificationCause.PYLON, null);
            }
        }
        return visited;
    }

    /**
     * Runs pylon passes until no loaded warded chunk is festering, then every queued cleanse, at once, ignoring the time
     * and the budget (harness and tests). Returns the number of passes run.
     */
    public int settleNow(PurificationSettings current) {
        int passes = 0;
        PylonIndex index = PylonIndex.get(level);
        while (passes <= CorruptionQueries.MAX_LEVEL && festeringWarded(index, current.pylonRadius())) {
            passNow(current);
            passes++;
        }
        tick(Integer.MAX_VALUE);
        return passes;
    }

    private boolean festeringWarded(PylonIndex index, int radius) {
        for (long key : index.wardedChunks(radius)) {
            if (CorruptionQueries.chunkLevel(level, new ChunkPos(key)) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does up to {@code budget} units of cleanse work: one per block layer cleansed or section or queued chunk skipped.
     * Returns the units spent.
     */
    public int tick(int budget) {
        unpark();
        int spent = 0;
        while (spent < budget) {
            if (step < 0) {
                spent++;
                if (!start()) {
                    if (queue.isEmpty()) {
                        return spent;
                    }
                    continue;
                }
            }
            LevelChunk target = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            if (target == null) {
                parked.add(chunk);
                causes.put(chunk, cause);
                step = -1;
                continue;
            }
            int layers = target.getHeight();
            while (step < layers && spent < budget) {
                if ((step & 15) == 0 && !Cleansing.mayCleanse(target.getSection(step >> 4))) {
                    step += 16;
                } else {
                    Cleansing.cleanseLayer(level, target, target.getMinBuildHeight() + step, cause);
                    step++;
                }
                spent++;
            }
            if (step >= layers) {
                Cleansing.finishChunk(level, target);
                step = -1;
            }
        }
        return spent;
    }

    private boolean start() {
        if (queue.isEmpty()) {
            return false;
        }
        long next = queue.removeFirstLong();
        PurificationCause why = causes.remove(next);
        if (level.getChunkSource().getChunkNow(ChunkPos.getX(next), ChunkPos.getZ(next)) == null) {
            parked.add(next);
            causes.put(next, why);
            return false;
        }
        chunk = next;
        cause = why == null ? PurificationCause.OTHER : why;
        step = 0;
        return true;
    }

    private void unpark() {
        if (parked.isEmpty()) {
            return;
        }
        LongIterator iterator = parked.iterator();
        while (iterator.hasNext()) {
            long pos = iterator.nextLong();
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos)) != null) {
                queue.add(pos);
                iterator.remove();
            }
        }
    }
}
