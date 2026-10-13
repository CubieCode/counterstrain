package com.cubiecode.remission.purification;

import com.cubiecode.remission.registry.RemissionAttachments;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * One level's remission zone passes (P5.5): regrowth and wildlife return in its loaded chunks in remission.
 *
 * <p>Chunks are tracked when they enter remission or load in remission and forgotten when they unload or are found out
 * of remission. Each tick visits tracked chunks round-robin, each at most once, until the budget is spent: one unit per
 * chunk visited, column regrown and wildlife attempt, with at most {@link #WILDLIFE_ATTEMPTS_PER_TICK} wildlife attempts.
 *
 * <p>Regrowth: column {@code i} of a chunk's fixed column order is due {@code (i + 1) / 256} of {@code regrowthTicks}
 * after the game time it entered remission, so a chunk regrows evenly over that time; a visit regrows the columns that
 * are due ({@link Regrowth#grow}). Nothing is saved: after a reload the columns that are due are walked again, which
 * places the same plants where a column is still bare.
 *
 * <p>Wildlife: a chunk's first attempt is due {@code wildlifeIntervalTicks} after it entered remission (at once after a
 * reload), then every {@code wildlifeIntervalTicks} ({@link Wildlife#attempt}).
 */
public final class RemissionZoneEngine {
    /**
     * Wildlife attempts per tick and level. An attempt spawns a whole group (entity creation, finalisation, joining the
     * level), far more than a budget unit's other work, so chunks that entered remission together and come due together
     * are spread over ticks; the rest stay due for their next visit.
     */
    public static final int WILDLIFE_ATTEMPTS_PER_TICK = 2;
    private static final int[] ORDER = order();

    private final ServerLevel level;
    private final LongLinkedOpenHashSet zones = new LongLinkedOpenHashSet();
    private final Long2IntOpenHashMap regrown = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap nextWildlife = new Long2LongOpenHashMap();

    public RemissionZoneEngine(ServerLevel level) {
        this.level = level;
    }

    private static int[] order() {
        int[] order = new int[Regrowth.COLUMNS];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        RandomSource random = RandomSource.create(0x5A0E5L);
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        return order;
    }

    /** The column (z * 16 + x) that is {@code index}th in {@code chunk}'s regrowth order. */
    public static int column(ChunkPos chunk, int index) {
        return ORDER[index] ^ (int) (Mth.getSeed(chunk.x, 0, chunk.z) & 0xFF);
    }

    /** Columns of a chunk that are due {@code elapsed} game ticks after it entered remission. */
    public static int dueColumns(long elapsed, int regrowthTicks) {
        if (elapsed <= 0) {
            return 0;
        }
        return (int) Math.min(Regrowth.COLUMNS, elapsed * Regrowth.COLUMNS / Math.max(1, regrowthTicks));
    }

    /** Tracks {@code chunk}; a chunk already tracked keeps its progress. */
    public void track(ChunkPos chunk) {
        zones.add(chunk.toLong());
    }

    /** Forgets {@code chunk} and its progress. */
    public void forget(ChunkPos chunk) {
        forget(chunk.toLong());
    }

    private void forget(long key) {
        zones.remove(key);
        regrown.remove(key);
        nextWildlife.remove(key);
    }

    /** Whether {@code chunk} is tracked. */
    public boolean tracks(ChunkPos chunk) {
        return zones.contains(chunk.toLong());
    }

    /** Number of tracked chunks. */
    public int size() {
        return zones.size();
    }

    /** Columns of {@code chunk} walked so far. */
    public int regrown(ChunkPos chunk) {
        return regrown.get(chunk.toLong());
    }

    /**
     * Does up to {@code budget} units of zone work at game time {@code now}. Returns the units spent. A pass that is
     * switched off in {@code settings} does nothing.
     */
    public int tick(long now, int budget, RemissionZoneSettings settings, RandomSource random) {
        if (!settings.regrowth() && !settings.wildlife()) {
            return 0;
        }
        int spent = 0;
        int attempts = 0;
        int count = zones.size();
        for (int visited = 0; visited < count && spent < budget; visited++) {
            long key = zones.removeFirstLong();
            zones.add(key);
            spent++;
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                continue;
            }
            Long entered = chunk.getExistingDataOrNull(RemissionAttachments.CHUNK_REMISSION);
            if (entered == null) {
                forget(key);
                continue;
            }
            if (settings.regrowth()) {
                spent += regrow(chunk, key, dueColumns(now - entered, settings.regrowthTicks()), budget - spent, settings);
            }
            if (settings.wildlife() && spent < budget && attempts < WILDLIFE_ATTEMPTS_PER_TICK) {
                long due = nextWildlife.containsKey(key) ? nextWildlife.get(key) : entered + settings.wildlifeIntervalTicks();
                if (now >= due) {
                    spent++;
                    attempts++;
                    Wildlife.attempt(level, chunk.getPos(), settings.wildlifeCap(), random);
                    nextWildlife.put(key, now + settings.wildlifeIntervalTicks());
                }
            }
        }
        return spent;
    }

    /** Walks {@code chunk}'s columns up to {@code due}, at most {@code budget} of them. Returns the columns walked. */
    private int regrow(LevelChunk chunk, long key, int due, int budget, RemissionZoneSettings settings) {
        int done = regrown.get(key);
        int walked = 0;
        ChunkPos pos = chunk.getPos();
        while (done < due && walked < budget) {
            int column = column(pos, done);
            Regrowth.grow(level, pos.getBlockX(column & 15), pos.getBlockZ(column >> 4), settings.regrowthDensity(),
                    settings.saplingChance());
            done++;
            walked++;
        }
        regrown.put(key, done);
        return walked;
    }

    /**
     * Regrows every column of every loaded tracked chunk in remission at once, ignoring the time and the budget (harness
     * and tests). Returns the plants placed.
     */
    public int regrowNow(RemissionZoneSettings settings) {
        int placed = 0;
        for (long key : zones.toLongArray()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null || !chunk.hasData(RemissionAttachments.CHUNK_REMISSION)) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            for (int i = 0; i < Regrowth.COLUMNS; i++) {
                int column = column(pos, i);
                if (Regrowth.grow(level, pos.getBlockX(column & 15), pos.getBlockZ(column >> 4), settings.regrowthDensity(),
                        settings.saplingChance())) {
                    placed++;
                }
            }
            regrown.put(key, Regrowth.COLUMNS);
        }
        return placed;
    }
}
