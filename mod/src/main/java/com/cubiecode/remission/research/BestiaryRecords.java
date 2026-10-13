package com.cubiecode.remission.research;

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
 * Every player's own bestiary progress as last stored, saved as {@code data/remission_bestiary.dat} in the overworld
 * folder, so a team's progress includes members who are offline (P6.4). A copy of the {@code remission:bestiary} player
 * attachments, written by {@link Bestiary} on every recorded scan and on login; the attachment stays the player's own
 * record.
 */
final class BestiaryRecords extends SavedData {
    static final String FILE_NAME = "remission_bestiary";
    private static final Factory<BestiaryRecords> FACTORY = new Factory<>(BestiaryRecords::new, BestiaryRecords::load);
    private static final Codec<Map<UUID, BestiaryProgress>> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC,
            BestiaryProgress.CODEC);

    private final Map<UUID, BestiaryProgress> players = new HashMap<>();

    static BestiaryRecords get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** Every recorded player with any progress. */
    Map<UUID, BestiaryProgress> players() {
        return players;
    }

    /** Stores the player's own progress; returns whether the record changed. */
    boolean put(UUID player, BestiaryProgress progress) {
        BestiaryProgress old = progress.isEmpty() ? players.remove(player) : players.put(player, progress);
        if (progress.equals(old == null ? BestiaryProgress.EMPTY : old)) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, players).ifSuccess(map -> tag.put("players", map));
        return tag;
    }

    static BestiaryRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        BestiaryRecords records = new BestiaryRecords();
        if (tag.contains("players")) {
            CODEC.parse(NbtOps.INSTANCE, tag.get("players"))
                    .resultOrPartial(error -> Remission.LOGGER.warn("Dropping unreadable bestiary records: {}", error))
                    .ifPresent(records.players::putAll);
        }
        return records;
    }
}
