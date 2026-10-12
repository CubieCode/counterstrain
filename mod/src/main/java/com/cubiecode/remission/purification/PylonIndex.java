package com.cubiecode.remission.purification;

import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Level-wide index of every quarantine pylon, saved as {@code data/remission_pylons.dat} in the dimension folder, and the
 * set of chunks their wards cover. A chunk is warded when its Chebyshev distance in chunks to some pylon's chunk is at
 * most the ward radius. The covered set is rebuilt lazily after a change or a radius change and iterates in
 * {@link ChunkPos#toLong()} order, so pylon passes are deterministic.
 */
public final class PylonIndex extends SavedData {
    static final String FILE_NAME = "remission_pylons";
    private static final Factory<PylonIndex> FACTORY = new Factory<>(PylonIndex::new, PylonIndex::load);

    private final TreeSet<Long> pylons = new TreeSet<>();
    private LongSortedSet warded;
    private LongOpenHashSet wardedLookup;
    private int wardedRadius = -1;

    public static PylonIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** Indexes a pylon at {@code pos} unless it is already indexed. Returns whether it was added. */
    public boolean add(BlockPos pos) {
        if (!pylons.add(pos.asLong())) {
            return false;
        }
        changed();
        return true;
    }

    public boolean remove(BlockPos pos) {
        if (!pylons.remove(pos.asLong())) {
            return false;
        }
        changed();
        return true;
    }

    public boolean contains(BlockPos pos) {
        return pylons.contains(pos.asLong());
    }

    public boolean isEmpty() {
        return pylons.isEmpty();
    }

    /** Every indexed pylon in {@link BlockPos#asLong()} order. */
    public List<BlockPos> pylons() {
        List<BlockPos> list = new ArrayList<>(pylons.size());
        pylons.forEach(pos -> list.add(BlockPos.of(pos)));
        return list;
    }

    /** Whether {@code chunk} is inside some pylon's ward of {@code radius} chunks. */
    public boolean isWarded(long chunk, int radius) {
        return !pylons.isEmpty() && lookup(radius).contains(chunk);
    }

    /** Every chunk inside some pylon's ward of {@code radius} chunks, in {@link ChunkPos#toLong()} order; shared, do not change. */
    public LongSortedSet wardedChunks(int radius) {
        lookup(radius);
        return warded;
    }

    /** The chunks one pylon at {@code pos} wards with {@code radius}. */
    public static List<ChunkPos> ward(BlockPos pos, int radius) {
        ChunkPos centre = new ChunkPos(pos);
        List<ChunkPos> chunks = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new ChunkPos(centre.x + dx, centre.z + dz));
            }
        }
        return chunks;
    }

    private LongOpenHashSet lookup(int radius) {
        if (wardedLookup == null || wardedRadius != radius) {
            LongSortedSet sorted = new LongAVLTreeSet();
            for (long pylon : pylons) {
                for (ChunkPos chunk : ward(BlockPos.of(pylon), radius)) {
                    sorted.add(chunk.toLong());
                }
            }
            warded = sorted;
            wardedLookup = new LongOpenHashSet(sorted);
            wardedRadius = radius;
        }
        return wardedLookup;
    }

    private void changed() {
        wardedLookup = null;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("pylons", new LongArrayTag(pylons.stream().mapToLong(Long::longValue).toArray()));
        return tag;
    }

    public static PylonIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        PylonIndex index = new PylonIndex();
        if (tag.contains("pylons", Tag.TAG_LONG_ARRAY)) {
            for (long pos : tag.getLongArray("pylons")) {
                index.pylons.add(pos);
            }
        }
        return index;
    }
}
