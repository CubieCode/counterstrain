package com.cubiecode.remission.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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
 * give the same levels. A chunk still queued for catch-up is skipped (it counts as a visit) until it is caught up.
 * While a surge from a nidus runs, its step (and its own chunk's growth) is multiplied by {@link Land#surgeFactor}
 * before the spread attempt, so cancelled and scaled attempts still apply on top.
 *
 * <p>Catch-up: a chunk that loads again is queued; up to {@code catchUpBudget} queued chunks per tick are moved toward
 * each nidus target by the passes they missed since {@code max(last change, nidus indexed)}, in closed form. The land
 * keeps moving while nobody is near, and a reload does the same work however long it was away. Catch-up follows the
 * live parent rule: a step whose parent is loaded and below the threshold is held (so a firebreak holds after a
 * reload too), and an unloaded parent counts as at its own target. When a chunk's loaded, below-threshold parent is
 * itself still queued, the parent is caught up first (recursively, at most {@code radius} deep, stopping at cycles
 * between nidi) and its own queue entry is then skipped, so a region that loads at once catches up from the nidus
 * outward whatever order its chunks loaded in, each chunk once.
 */
public final class SpreadEngine {
    /** The level as the engine sees it. Chunks are {@link ChunkPos#toLong()} keys; calls come from the server thread. */
    public interface Land {
        boolean isLoaded(long chunk);

        /** Current state of a loaded chunk, {@link ChunkCorruption#NONE} when it has none. */
        ChunkCorruption get(long chunk);

        void set(long chunk, ChunkCorruption state);

        /**
         * Lets the level scale or cancel (return 0) one chunk-to-chunk step at {@code gameTime}; {@code catchUp} when it
         * is part of a reloaded chunk's fast-forward.
         */
        int spreadAttempt(long from, long to, int amount, long gameTime, boolean catchUp);

        /** Every indexed nidus in a fixed order. */
        List<Nidus> nidi();

        /** Checks a nidus whose chunk is loaded still exists, dropping it from the index if not. */
        boolean validate(Nidus nidus);

        /**
         * Factor on the live-pass steps from {@code nidus} now, applied before the spread attempt (1 normally; a surge
         * from that nidus raises it). Catch-up never applies it.
         */
        default int surgeFactor(Nidus nidus) {
            return 1;
        }
    }

    /**
     * Work done in one tick, for budget checks and measurement: chunks caught up (or found unloaded), at most
     * {@code catchUpBudget}; how many of them were queued parents caught up ahead of a child; live pass visits.
     */
    public record TickWork(int catchUps, int parentsFirst, int visits) {
    }

    private final Land land;
    private final Supplier<SpreadSettings> settings;
    private final Deque<Long> pendingCatchUp = new ConcurrentLinkedDeque<>();
    /** How many times each chunk is in {@link #pendingCatchUp}, so a child can find its queued parent. */
    private final Map<Long, Integer> queued = new ConcurrentHashMap<>();
    /**
     * Queue entries left for chunks already caught up this round (one catch-up covers every entry queued before it);
     * they are skipped without counting against the budget.
     */
    private final Map<Long, Integer> covered = new HashMap<>();
    /** Chunks whose catch-up is waiting on a parent's, to stop at cycles. */
    private final List<Long> path = new ArrayList<>();
    private int remaining;

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
        queued.merge(chunk, 1, Integer::sum);
        pendingCatchUp.addLast(chunk);
    }

    private void queueFirst(long chunk) {
        queued.merge(chunk, 1, Integer::sum);
        pendingCatchUp.addFirst(chunk);
    }

    public int pendingCatchUps() {
        return pendingCatchUp.size();
    }

    public boolean passRunning() {
        return pass != null;
    }

    public TickWork tick(long gameTime) {
        SpreadSettings current = settings.get();
        int[] parents = new int[1];
        int catchUps = runCatchUp(gameTime, current, parents);
        int visits = runPass(gameTime, current);
        return new TickWork(catchUps, parents[0], visits);
    }

    private int runCatchUp(long now, SpreadSettings current, int[] parents) {
        remaining = current.catchUpBudget();
        while (remaining > 0) {
            Long chunk = pendingCatchUp.pollFirst();
            if (chunk == null) {
                break;
            }
            queued.computeIfPresent(chunk, (key, count) -> count > 1 ? count - 1 : null);
            Integer left = covered.get(chunk);
            if (left != null) {
                if (left > 1) {
                    covered.put(chunk, left - 1);
                } else {
                    covered.remove(chunk);
                }
                continue;
            }
            if (!land.isLoaded(chunk)) {
                remaining--;
                continue;
            }
            if (!catchUpAfterParents(chunk, now, current, parents)) {
                // Out of budget partway: the parents done so far are covered; the chunk goes first next tick.
                queueFirst(chunk);
                break;
            }
            cover(chunk);
        }
        return current.catchUpBudget() - remaining;
    }

    /**
     * Catches {@code chunk} up after any loaded, still-queued parent below the threshold (recursively, at most
     * {@code radius} deep and never through a chunk already on the path, which breaks cycles between nidi). Returns
     * false, leaving {@code chunk} untouched, when the tick's budget runs out first.
     */
    private boolean catchUpAfterParents(long chunk, long now, SpreadSettings current, int[] parents) {
        if (path.size() < current.radius()) {
            path.add(chunk);
            try {
                for (long parent : queuedParents(chunk, current)) {
                    if (path.contains(parent) || covered.containsKey(parent)) {
                        continue;
                    }
                    if (!catchUpAfterParents(parent, now, current, parents)) {
                        return false;
                    }
                    cover(parent);
                    parents[0]++;
                }
            } finally {
                path.remove(chunk);
            }
        }
        if (remaining == 0) {
            return false;
        }
        remaining--;
        catchUp(chunk, now, current);
        return true;
    }

    /**
     * Whether a chunk has a queue entry not yet covered by a catch-up. A live pass skips it, so the pass cannot move its
     * last change to now and the catch-up then credits every pass it missed (including the skipped one).
     */
    private boolean awaitingCatchUp(long chunk) {
        Integer entries = queued.get(chunk);
        return entries != null && entries > covered.getOrDefault(chunk, 0);
    }

    /** Marks every queue entry of a chunk just caught up as done, so they are skipped. */
    private void cover(long chunk) {
        Integer rest = queued.get(chunk);
        if (rest != null) {
            covered.put(chunk, rest);
        }
    }

    /** Loaded parents below the threshold, still queued, that steps this chunk could catch up on come from. */
    private List<Long> queuedParents(long chunk, SpreadSettings current) {
        List<Long> parents = List.of();
        int level = land.get(chunk).level();
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        for (Nidus nidus : land.nidi()) {
            long origin = nidus.chunk();
            int dx = x - ChunkPos.getX(origin);
            int dz = z - ChunkPos.getZ(origin);
            int distance = Math.abs(dx) + Math.abs(dz);
            if (distance == 0 || distance > current.radius() || level >= current.target(distance)) {
                continue;
            }
            long parent = parent(origin, dx, dz);
            if (queued.containsKey(parent) && !covered.containsKey(parent) && land.isLoaded(parent)
                    && land.get(parent).level() < current.spreadThreshold()) {
                if (parents.isEmpty()) {
                    parents = new ArrayList<>(2);
                }
                parents.add(parent);
            }
        }
        return parents;
    }

    /**
     * Fast-forwards one loaded chunk to {@code now}; normally run from the catch-up queue. A step from a loaded parent
     * below the spread threshold is held without a spread attempt, as in a live pass; an unloaded parent is assumed to
     * be at its target. The chunk's last change moves to {@code now} only when its level changed: passes shortened by a
     * scaled step are then not credited again, but a catch-up whose steps were all held or cancelled keeps the old time,
     * so a later catch-up credits that history again (still capped at the target). A parent caught up first contributes
     * its level after that catch-up, which converges on the same targets as live spread rather than replaying history.
     */
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
                long parent = parent(origin, dx, dz);
                if (land.isLoaded(parent) && land.get(parent).level() < current.spreadThreshold()) {
                    continue;
                }
                amount = clamp(land.spreadAttempt(parent, chunk, amount, now, true), target - level);
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
        if (!land.isLoaded(chunk) || awaitingCatchUp(chunk)) {
            return;
        }
        int distance = Math.abs(dx) + Math.abs(dz);
        int target = pass.target(distance);
        ChunkCorruption state = land.get(chunk);
        if (state.level() >= target) {
            return;
        }
        int factor = land.surgeFactor(nidus);
        int amount;
        if (distance == 0) {
            amount = Math.min(surged(pass.nidusGrowth(), factor), target - state.level());
        } else {
            long parent = parent(origin, dx, dz);
            int parentLevel = land.isLoaded(parent) ? land.get(parent).level() : pass.target(distance - 1);
            if (parentLevel < pass.spreadThreshold()) {
                return;
            }
            amount = clamp(land.spreadAttempt(parent, chunk, Math.min(surged(pass.spreadStep(), factor),
                    target - state.level()), now, false), target - state.level());
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

    private static int surged(int rate, int factor) {
        return (int) Math.min(Integer.MAX_VALUE, (long) rate * factor);
    }

    private static int clamp(int amount, int max) {
        return Math.max(0, Math.min(amount, max));
    }
}
