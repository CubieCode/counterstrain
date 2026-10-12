package com.cubiecode.remission.surge;

import com.cubiecode.remission.Remission;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Surge state of one level, saved as {@code data/remission_surges.dat} in the dimension folder: when the next scheduled
 * surge is due and the surge running now, if any.
 */
public final class SurgeData extends SavedData {
    static final String FILE_NAME = "remission_surges";
    /** {@link #nextDue()} before the first tick has scheduled one. */
    public static final long UNSCHEDULED = Long.MIN_VALUE;
    private static final Factory<SurgeData> FACTORY = new Factory<>(SurgeData::new, SurgeData::load);

    private long nextDue = UNSCHEDULED;
    @Nullable
    private Surge current;

    public static SurgeData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public long nextDue() {
        return nextDue;
    }

    void setNextDue(long time) {
        nextDue = time;
        setDirty();
    }

    @Nullable
    public Surge current() {
        return current;
    }

    void setCurrent(@Nullable Surge surge) {
        current = surge;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("next_due", nextDue);
        if (current != null) {
            Surge.CODEC.encodeStart(NbtOps.INSTANCE, current).ifSuccess(surge -> tag.put("current", surge));
        }
        return tag;
    }

    public static SurgeData load(CompoundTag tag, HolderLookup.Provider registries) {
        SurgeData data = new SurgeData();
        data.nextDue = tag.contains("next_due") ? tag.getLong("next_due") : UNSCHEDULED;
        if (tag.contains("current")) {
            data.current = Surge.CODEC.parse(NbtOps.INSTANCE, tag.get("current"))
                    .resultOrPartial(error -> Remission.LOGGER.warn("Dropping unreadable surge: {}", error))
                    .orElse(null);
        }
        return data;
    }
}
