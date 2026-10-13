package com.cubiecode.remission.variant;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Per-nidus variant state of one level (P7.5), saved as {@code data/remission_variants.dat} in the dimension folder:
 * the variant the nidus's Strain carries, the pressure each variant has built there, the total pressure of every method
 * and the game time a variant fuel counters it until. Kept beside the nidus index rather than in the nidus block entity,
 * so pressure counts while the nidus's own chunk is unloaded. An entry goes when its nidus does.
 */
public final class VariantRecords extends SavedData {
    static final String FILE_NAME = "remission_variants";
    private static final Factory<VariantRecords> FACTORY = new Factory<>(VariantRecords::new, VariantRecords::load);

    private final TreeMap<Long, Entry> entries = new TreeMap<>();
    private int withVariant;

    /** One nidus's record. Mutate only through {@link VariantRecords} methods, which mark the data dirty. */
    public static final class Entry {
        @Nullable
        private ResourceLocation variant;
        private final TreeMap<ResourceLocation, Float> pressure = new TreeMap<>();
        private float total;
        private long counteredUntil;

        public Optional<ResourceLocation> variant() {
            return Optional.ofNullable(variant);
        }

        /** Pressure built towards each variant, by variant id (sorted). */
        public Map<ResourceLocation, Float> pressure() {
            return java.util.Collections.unmodifiableMap(pressure);
        }

        public float pressure(ResourceLocation variant) {
            return pressure.getOrDefault(variant, 0.0F);
        }

        /** Pressure of every method used against the nidus, counted once per use whatever it matches. */
        public float total() {
            return total;
        }

        public long counteredUntil() {
            return counteredUntil;
        }

        public boolean countered(long gameTime) {
            return gameTime < counteredUntil;
        }

        private boolean isEmpty() {
            return variant == null && pressure.isEmpty() && total == 0.0F && counteredUntil == 0L;
        }
    }

    public static VariantRecords get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** The nidus's record, or null when it has none. */
    @Nullable
    public Entry get(BlockPos nidus) {
        return entries.get(nidus.asLong());
    }

    public Optional<ResourceLocation> variant(BlockPos nidus) {
        Entry entry = get(nidus);
        return entry == null ? Optional.empty() : entry.variant();
    }

    /** Whether any nidus in the level carries a variant (a fast path for lookups). */
    public boolean anyVariant() {
        return withVariant > 0;
    }

    private Entry create(BlockPos nidus) {
        return entries.computeIfAbsent(nidus.asLong(), pos -> new Entry());
    }

    /** Adds {@code amount} to the total and to each of {@code variants}' pressure. */
    void addPressure(BlockPos nidus, Iterable<ResourceLocation> variants, float amount) {
        Entry entry = create(nidus);
        entry.total += amount;
        for (ResourceLocation variant : variants) {
            entry.pressure.merge(variant, amount, Float::sum);
        }
        setDirty();
    }

    /** Clears one variant's pressure (its emergence was cancelled). */
    void resetPressure(BlockPos nidus, ResourceLocation variant) {
        Entry entry = get(nidus);
        if (entry != null && entry.pressure.remove(variant) != null) {
            setDirty();
        }
    }

    /** Sets the nidus's variant (or none); every pressure and any counter start again from 0. */
    void setVariant(BlockPos nidus, @Nullable ResourceLocation variant) {
        Entry entry = create(nidus);
        if (entry.variant != null) {
            withVariant--;
        }
        entry.variant = variant;
        if (variant != null) {
            withVariant++;
        }
        entry.pressure.clear();
        entry.total = 0.0F;
        entry.counteredUntil = 0L;
        if (entry.isEmpty()) {
            entries.remove(nidus.asLong());
        }
        setDirty();
    }

    void counter(BlockPos nidus, long until) {
        Entry entry = create(nidus);
        entry.counteredUntil = Math.max(entry.counteredUntil, until);
        setDirty();
    }

    /** Drops a removed nidus's record; returns whether it had one. */
    boolean remove(BlockPos nidus) {
        Entry entry = entries.remove(nidus.asLong());
        if (entry == null) {
            return false;
        }
        if (entry.variant != null) {
            withVariant--;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        entries.forEach((pos, entry) -> {
            CompoundTag nidus = new CompoundTag();
            nidus.putLong("pos", pos);
            if (entry.variant != null) {
                nidus.putString("variant", entry.variant.toString());
            }
            CompoundTag pressure = new CompoundTag();
            entry.pressure.forEach((variant, amount) -> pressure.putFloat(variant.toString(), amount));
            nidus.put("pressure", pressure);
            nidus.putFloat("total", entry.total);
            if (entry.counteredUntil != 0L) {
                nidus.putLong("countered_until", entry.counteredUntil);
            }
            list.add(nidus);
        });
        tag.put("nidi", list);
        return tag;
    }

    public static VariantRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        VariantRecords records = new VariantRecords();
        for (Tag element : tag.getList("nidi", Tag.TAG_COMPOUND)) {
            CompoundTag nidus = (CompoundTag) element;
            Entry entry = new Entry();
            if (nidus.contains("variant", Tag.TAG_STRING)) {
                entry.variant = ResourceLocation.tryParse(nidus.getString("variant"));
            }
            CompoundTag pressure = nidus.getCompound("pressure");
            for (String key : pressure.getAllKeys()) {
                ResourceLocation variant = ResourceLocation.tryParse(key);
                if (variant != null) {
                    entry.pressure.put(variant, pressure.getFloat(key));
                }
            }
            entry.total = nidus.getFloat("total");
            entry.counteredUntil = nidus.getLong("countered_until");
            if (entry.variant != null) {
                records.withVariant++;
            }
            records.entries.put(nidus.getLong("pos"), entry);
        }
        return records;
    }
}
