package com.cubiecode.remission.world;

import com.cubiecode.remission.api.CorruptionQueries;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * One level's budgeted vein pass (P4.8; rules in {@link FesteringVeins}). Chunks are queued when they load or their
 * stage rises; each tick walks up to a budget of block layers of the current chunk, bottom to top, skipping sections
 * that cannot take a vein for one unit each. A chunk that finishes is due again {@link FesteringVeins#REVISIT_TICKS}
 * later while it stays loaded, so faces that appear later are overgrown too; one that is too clean to grow veins is
 * dropped until its stage rises or it loads again. A chunk that leaves full status without unloading is parked and
 * queued again once it is back; one that unloads is forgotten and queued by its load event. Takes its settings as an argument, so tests drive their own passes.
 */
public final class VeinGrowth {
    private final ServerLevel level;
    private final Supplier<VeinSettings> settings;
    private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    /** Chunks in the order they finished, each with the game time it is due again; a chunk is in it at most once. */
    private final LongArrayFIFOQueue revisits = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue revisitTimes = new LongArrayFIFOQueue();
    private final LongOpenHashSet scheduled = new LongOpenHashSet();
    /** Chunks that left full status without unloading before their pass finished; queued again once they are back. */
    private final LongOpenHashSet parked = new LongOpenHashSet();
    private long chunk;
    private int coverage;
    /** Block layer of the current chunk from its bottom, or -1 if idle. */
    private int step = -1;

    public VeinGrowth(ServerLevel level, Supplier<VeinSettings> settings) {
        this.level = level;
        this.settings = settings;
    }

    /** Queues {@code pos} to grow its veins. */
    public void queue(ChunkPos pos) {
        queue.add(pos.toLong());
    }

    /** Whether chunks are queued or one is in progress (due revisits not counted). */
    public boolean busy() {
        return step >= 0 || !queue.isEmpty();
    }

    /** Forgets {@code pos}: it unloaded, and its load event queues it again. */
    public void unloaded(ChunkPos pos) {
        long unloaded = pos.toLong();
        scheduled.remove(unloaded);
        parked.remove(unloaded);
        queue.remove(unloaded);
        if (step >= 0 && chunk == unloaded) {
            step = -1;
        }
    }

    /**
     * Does up to {@code budget} units of vein work: one per block layer grown or section or queued chunk skipped. Does
     * nothing while veins are off. Returns the units spent.
     */
    public int tick(int budget) {
        VeinSettings current = settings.get();
        if (!current.enabled()) {
            return 0;
        }
        revisit();
        unpark();
        int spent = 0;
        while (spent < budget) {
            if (step < 0) {
                spent++;
                if (!start(current)) {
                    if (queue.isEmpty()) {
                        return spent;
                    }
                    continue;
                }
            }
            LevelChunk target = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            if (target == null) {
                // Left full status mid-way: queued again when it is back, or by its load event if it unloads.
                parked.add(chunk);
                step = -1;
                continue;
            }
            int layers = target.getHeight();
            while (step < layers && spent < budget) {
                if ((step & 15) == 0 && FesteringVeins.skipSection(level, target, step >> 4)) {
                    step += 16;
                } else {
                    FesteringVeins.growLayer(level, target, target.getMinBuildHeight() + step, coverage);
                    step++;
                }
                spent++;
            }
            if (step >= layers) {
                schedule(chunk);
                step = -1;
            }
        }
        return spent;
    }

    /** Takes the next queued chunk that is loaded and festering enough to grow veins; false if none was taken. */
    private boolean start(VeinSettings current) {
        if (queue.isEmpty()) {
            return false;
        }
        long next = queue.removeFirstLong();
        LevelChunk loaded = level.getChunkSource().getChunkNow(ChunkPos.getX(next), ChunkPos.getZ(next));
        if (loaded == null) {
            parked.add(next);
            return false;
        }
        int cover = FesteringVeins.coverage(CorruptionQueries.chunkStage(level, loaded.getPos()), current.minStage());
        if (cover <= 0) {
            return false;
        }
        chunk = next;
        coverage = cover;
        step = 0;
        return true;
    }

    private void schedule(long pos) {
        if (scheduled.add(pos)) {
            revisits.enqueue(pos);
            revisitTimes.enqueue(level.getGameTime() + FesteringVeins.REVISIT_TICKS);
        }
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

    /** Moves revisits that are due to the queue; chunks that unloaded meanwhile were unscheduled and are dropped. */
    private void revisit() {
        long now = level.getGameTime();
        while (!revisits.isEmpty() && revisitTimes.firstLong() <= now) {
            long pos = revisits.dequeueLong();
            revisitTimes.dequeueLong();
            if (scheduled.remove(pos)) {
                queue.add(pos);
            }
        }
    }
}
