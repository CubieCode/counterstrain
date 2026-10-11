package com.cubiecode.remission.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;

/**
 * Budgeted, deterministic spread of the Strain from nidi to the chunks around them, for one level.
 *
 * <p>Live passes: every {@code intervalTicks} (aligned to multiples of the level game time) a pass visits, for each
 * nidus in index order, every chunk within {@link SpreadSettings#radius()} in order of distance. A loaded chunk below
 * its target gains from its <em>parent</em>, the neighbour one step closer to the nidus (along the larger axis
 * offset first), when the parent has reached the threshold; an unloaded parent is assumed to be at its own target. A
 * pass stops after {@code chunkBudget} visits and resumes on the next tick, so the same state and the same ticks always
 * give the same levels.
 *
 * <p>Catch-up: a chunk that loads again is queued; up to {@code catchUpBudget} queued chunks per tick are moved toward
 * each nidus target by the passes they missed since {@code max(last change, nidus indexed)}, in closed form. The land
 * keeps moving while nobody is near, and a reload does the same work however long it was away.
 */
public final class SpreadEngine {
    /** The level as the engine sees it. Chunks are {@link ChunkPos#toLong()} keys; calls come from the server thread. */
    public interface Land {
        boolean isLoaded(long chunk);

        /** Current state of a loaded chunk, {@link ChunkCorruption#NONE} when it has none. */
        ChunkCorruption get(long chunk);

        void set(long chunk, ChunkCorruption state);

        /** Lets the level scale or cancel (return 0) one chunk-to-chunk step. */
        int spreadAttempt(long from, long to, int amount);

        /** Every indexed nidus in a fixed order. */
        List<Nidus> nidi();

        /** Checks a nidus whose chunk is loaded still exists, dropping it from the index if not. */
        boolean validate(Nidus nidus);
    }

    /** Work done in one tick, for budget checks and measurement. */
    public record TickWork(int catchUps, int visits) {
    }

    private final Land land;
    private final Supplier<SpreadSettings> settings;
    private final Queue<Long> pendingCatchUp = new ConcurrentLinkedQueue<>();

    private SpreadSettings pass;
    private List<Nidus> passNidi = List.of();
    private int nidusCursor;
    private int offsetCursor;
    private long nextPassTime = Long.MIN_VALUE;
    private int[][] offsets = new int[0][];
    private int offsetsRadius = -1;

    public SpreadEngine(Land land, Supplier<SpreadSettings> settings) {
        this.land = land;
        this.settings = settings;
    }

    /** Queues a chunk for catch-up. Safe to call from any thread. */
    public void chunkLoaded(long chunk) {
        pendingCatchUp.add(chunk);
    }

    public int pendingCatchUps() {
        return pendingCatchUp.size();
    }

    public boolean passRunning() {
        return pass != null;
    }

    public TickWork tick(long gameTime) {
        SpreadSettings current = settings.get();
        int catchUps = runCatchUp(gameTime, current);
        int visits = runPass(gameTime, current);
        return new TickWork(catchUps, visits);
    }

    private int runCatchUp(long now, SpreadSettings current) {
        int done = 0;
        while (done < current.catchUpBudget()) {
            Long chunk = pendingCatchUp.poll();
            if (chunk == null) {
                break;
            }
            done++;
            if (land.isLoaded(chunk)) {
                catchUp(chunk, now, current);
            }
        }
        return done;
    }

    /** Fast-forwards one loaded chunk to {@code now}; normally run from the catch-up queue. */
    public void catchUp(long chunk, long now, SpreadSettings current) {
        ChunkCorruption state = land.get(chunk);
        int level = state.level();
        int radius = current.radius();
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        for (Nidus nidus : land.nidi()) {
            long origin = nidus.chunk();
            int dx = x - ChunkPos.getX(origin);
            int dz = z - ChunkPos.getZ(origin);
            int distance = Math.abs(dx) + Math.abs(dz);
            int target = current.target(distance);
            if (distance > radius || level >= target) {
                continue;
            }
            long missed = (now - Math.max(state.lastUpdateGameTime(), nidus.since())) / current.intervalTicks();
            if (missed <= 0) {
                continue;
            }
            int rate = distance == 0 ? current.nidusGrowth() : current.spreadStep();
            int amount = (int) Math.min(target - level, missed * rate);
            if (distance > 0) {
                amount = clamp(land.spreadAttempt(parent(origin, dx, dz), chunk, amount), target - level);
            }
            level += amount;
        }
        if (level != state.level()) {
            land.set(chunk, new ChunkCorruption(level, now));
        }
    }

    private int runPass(long now, SpreadSettings current) {
        if (pass == null) {
            if (now < nextPassTime) {
                return 0;
            }
            nextPassTime = (Math.floorDiv(now, current.intervalTicks()) + 1) * current.intervalTicks();
            passNidi = land.nidi();
            if (passNidi.isEmpty()) {
                return 0;
            }
            pass = current;
            nidusCursor = 0;
            offsetCursor = 0;
            int radius = pass.radius();
            if (radius != offsetsRadius) {
                offsets = offsets(radius);
                offsetsRadius = radius;
            }
        }
        int visits = 0;
        boolean resumed = true;
        while (visits < current.chunkBudget() && pass != null) {
            Nidus nidus = passNidi.get(nidusCursor);
            // A nidus destroyed since the pass started (or since the last tick) stops spreading at once.
            if ((offsetCursor == 0 || resumed) && !land.validate(nidus)) {
                visits++;
                nextNidus();
                continue;
            }
            resumed = false;
            visit(nidus, offsets[offsetCursor], now);
            visits++;
            if (++offsetCursor == offsets.length) {
                nextNidus();
            }
        }
        return visits;
    }

    private void nextNidus() {
        offsetCursor = 0;
        if (++nidusCursor == passNidi.size()) {
            pass = null;
            passNidi = List.of();
        }
    }

    private void visit(Nidus nidus, int[] offset, long now) {
        long origin = nidus.chunk();
        int dx = offset[0];
        int dz = offset[1];
        long chunk = ChunkPos.asLong(ChunkPos.getX(origin) + dx, ChunkPos.getZ(origin) + dz);
        if (!land.isLoaded(chunk)) {
            return;
        }
        int distance = Math.abs(dx) + Math.abs(dz);
        int target = pass.target(distance);
        ChunkCorruption state = land.get(chunk);
        if (state.level() >= target) {
            return;
        }
        int amount;
        if (distance == 0) {
            amount = Math.min(pass.nidusGrowth(), target - state.level());
        } else {
            long parent = parent(origin, dx, dz);
            int parentLevel = land.isLoaded(parent) ? land.get(parent).level() : pass.target(distance - 1);
            if (parentLevel < pass.spreadThreshold()) {
                return;
            }
            amount = clamp(land.spreadAttempt(parent, chunk, Math.min(pass.spreadStep(), target - state.level())),
                    target - state.level());
        }
        if (amount > 0) {
            land.set(chunk, new ChunkCorruption(state.level() + amount, now));
        }
    }

    /** The neighbour of {@code origin + (dx, dz)} one step closer to {@code origin}, along the larger offset. */
    static long parent(long origin, int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            dx -= Integer.signum(dx);
        } else {
            dz -= Integer.signum(dz);
        }
        return ChunkPos.asLong(ChunkPos.getX(origin) + dx, ChunkPos.getZ(origin) + dz);
    }

    /** Chunk offsets within Manhattan {@code radius}, nearest first, so parents are visited before children. */
    static int[][] offsets(int radius) {
        List<int[]> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            int span = radius - Math.abs(dx);
            for (int dz = -span; dz <= span; dz++) {
                list.add(new int[] {dx, dz});
            }
        }
        list.sort(Comparator.<int[]>comparingInt(o -> Math.abs(o[0]) + Math.abs(o[1]))
                .thenComparingInt(o -> o[0]).thenComparingInt(o -> o[1]));
        return list.toArray(new int[0][]);
    }

    private static int clamp(int amount, int max) {
        return Math.max(0, Math.min(amount, max));
    }
}
