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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every player's countermeasures as last stored, saved as {@code data/remission_countermeasures.dat} in the overworld
 * folder, so the block hazard query can answer for a placer's team while its players are offline. A copy of the
 * player attachments, written by {@link Countermeasures} on every change and on login; the attachment stays the
 * player's own record. Also holds pending revocations: countermeasures revoked from a team while a member was offline,
 * taken from that member's attachment when they next log in (unless the team holds it again by then).
 */
final class CountermeasureRecords extends SavedData {
    static final String FILE_NAME = "remission_countermeasures";
    private static final Factory<CountermeasureRecords> FACTORY = new Factory<>(CountermeasureRecords::new,
            CountermeasureRecords::load);
    private static final Codec<Map<UUID, CountermeasureSet>> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC,
            CountermeasureSet.CODEC);

    private final Map<UUID, CountermeasureSet> players = new HashMap<>();
    private final Map<UUID, CountermeasureSet> revoked = new HashMap<>();

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

    /** Records that {@code hazard} was revoked from the offline player {@code player}. */
    void addRevoked(UUID player, ResourceLocation hazard) {
        revoked.merge(player, CountermeasureSet.EMPTY.with(hazard), (old, added) -> old.with(hazard));
        setDirty();
    }

    /** Removes and returns the player's pending revocations ({@link CountermeasureSet#EMPTY} when none). */
    CountermeasureSet takeRevoked(UUID player) {
        CountermeasureSet pending = revoked.remove(player);
        if (pending == null) {
            return CountermeasureSet.EMPTY;
        }
        setDirty();
        return pending;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, players).ifSuccess(map -> tag.put("players", map));
        if (!revoked.isEmpty()) {
            CODEC.encodeStart(NbtOps.INSTANCE, revoked).ifSuccess(map -> tag.put("revoked", map));
        }
        return tag;
    }

    static CountermeasureRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        CountermeasureRecords records = new CountermeasureRecords();
        if (tag.contains("players")) {
            CODEC.parse(NbtOps.INSTANCE, tag.get("players"))
                    .resultOrPartial(error -> Remission.LOGGER.warn("Dropping unreadable countermeasure records: {}", error))
                    .ifPresent(records.players::putAll);
        }
        if (tag.contains("revoked")) {
            CODEC.parse(NbtOps.INSTANCE, tag.get("revoked"))
                    .resultOrPartial(error -> Remission.LOGGER.warn("Dropping unreadable countermeasure revocations: {}", error))
                    .ifPresent(records.revoked::putAll);
        }
        return records;
    }
}
