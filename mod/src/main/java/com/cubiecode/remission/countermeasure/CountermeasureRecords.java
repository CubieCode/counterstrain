package com.cubiecode.remission.countermeasure;

import com.cubiecode.remission.Remission;
import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every player's countermeasures as last stored, saved as {@code data/remission_countermeasures.dat} in the overworld
 * folder, so the block hazard query can answer for a placer's team while its players are offline. A copy of the
 * player attachments, written by {@link Countermeasures} on every change and on login; the attachment stays the
 * player's own record.
 */
final class CountermeasureRecords extends SavedData {
    static final String FILE_NAME = "remission_countermeasures";
    private static final Factory<CountermeasureRecords> FACTORY = new Factory<>(CountermeasureRecords::new,
            CountermeasureRecords::load);
    private static final Codec<Map<UUID, CountermeasureSet>> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC,
            CountermeasureSet.CODEC);

    private final Map<UUID, CountermeasureSet> players = new HashMap<>();

    static CountermeasureRecords get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** Every recorded player with at least one countermeasure. */
    Map<UUID, CountermeasureSet> players() {
        return players;
    }

    void put(UUID player, CountermeasureSet set) {
        CountermeasureSet old = set.isEmpty() ? players.remove(player) : players.put(player, set);
        if (!set.equals(old == null ? CountermeasureSet.EMPTY : old)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, players).ifSuccess(map -> tag.put("players", map));
        return tag;
    }

    static CountermeasureRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        CountermeasureRecords records = new CountermeasureRecords();
        if (tag.contains("players")) {
            CODEC.parse(NbtOps.INSTANCE, tag.get("players"))
                    .resultOrPartial(error -> Remission.LOGGER.warn("Dropping unreadable countermeasure records: {}", error))
                    .ifPresent(records.players::putAll);
        }
        return records;
    }
}
