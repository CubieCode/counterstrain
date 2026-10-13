package com.cubiecode.remission.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Level-wide index of every nidus, saved as {@code data/remission_nidi.dat} in the dimension folder. The spread engine
 * reads it instead of scanning chunks, so a nidus keeps spreading while its own chunk is unloaded. Iteration is in
 * {@link BlockPos#asLong()} order, which keeps spread deterministic.
 */
public final class NidusIndex extends SavedData {
    static final String FILE_NAME = "remission_nidi";
    private static final Factory<NidusIndex> FACTORY = new Factory<>(NidusIndex::new, NidusIndex::load);

    private final TreeMap<Long, Long> nidi = new TreeMap<>();
    private List<Nidus> sorted;

    public static NidusIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** Indexes a nidus at {@code pos} unless it is already indexed. Returns whether it was added. */
    public boolean add(BlockPos pos, long gameTime) {
        if (nidi.putIfAbsent(pos.asLong(), gameTime) != null) {
            return false;
        }
        changed();
        return true;
    }

    public boolean remove(BlockPos pos) {
        if (nidi.remove(pos.asLong()) == null) {
            return false;
        }
        changed();
        return true;
    }

    public boolean contains(BlockPos pos) {
        return nidi.containsKey(pos.asLong());
    }

    /** Game time the nidus at {@code pos} was indexed, or {@code fallback} if it is not indexed. */
    public long since(BlockPos pos, long fallback) {
        Long since = nidi.get(pos.asLong());
        return since == null ? fallback : since;
    }

    public int size() {
        return nidi.size();
    }

    /** Every indexed nidus in a fixed order; the list is shared and immutable. */
    public List<Nidus> nidi() {
        if (sorted == null) {
            List<Nidus> list = new ArrayList<>(nidi.size());
            for (Map.Entry<Long, Long> entry : nidi.entrySet()) {
                list.add(new Nidus(BlockPos.of(entry.getKey()), entry.getValue()));
            }
            sorted = List.copyOf(list);
        }
        return sorted;
    }

    private void changed() {
        sorted = null;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Long> entry : nidi.entrySet()) {
            CompoundTag nidus = new CompoundTag();
            nidus.putLong("pos", entry.getKey());
            nidus.putLong("since", entry.getValue());
            list.add(nidus);
        }
        tag.put("nidi", list);
        return tag;
    }

    public static NidusIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        NidusIndex index = new NidusIndex();
        for (Tag element : tag.getList("nidi", Tag.TAG_COMPOUND)) {
            CompoundTag nidus = (CompoundTag) element;
            index.nidi.put(nidus.getLong("pos"), nidus.getLong("since"));
        }
        return index;
    }
}
