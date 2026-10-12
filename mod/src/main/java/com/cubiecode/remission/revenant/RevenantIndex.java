package com.cubiecode.remission.revenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Level-wide index of every revenant in a dimension, saved as {@code data/remission_revenants.dat} in the dimension
 * folder: owner, owner name and last known position per revenant UUID. A revenant adds itself when it rises, updates its
 * position whenever its block position changes while loaded, and removes itself when it dies or is discarded, so the
 * index also covers revenants in unloaded chunks (P5.4's tracker reads it). Iteration is in insertion order (rise order).
 */
public final class RevenantIndex extends SavedData {
    static final String FILE_NAME = "remission_revenants";
    private static final Factory<RevenantIndex> FACTORY = new Factory<>(RevenantIndex::new, RevenantIndex::load);

    private final LinkedHashMap<UUID, Entry> revenants = new LinkedHashMap<>();

    /** One revenant: its UUID, its owner's UUID and name, and its last known block position. */
    public record Entry(UUID revenant, UUID owner, String ownerName, BlockPos pos) {
    }

    /** An entry together with the dimension whose index holds it. */
    public record Located(ResourceKey<Level> dimension, Entry entry) {
    }

    public static RevenantIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** Every indexed revenant in every loaded dimension, dimension by dimension in the server's level order. */
    public static List<Located> all(MinecraftServer server) {
        List<Located> all = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entry entry : get(level).entries()) {
                all.add(new Located(level.dimension(), entry));
            }
        }
        return all;
    }

    /** Every revenant of {@code owner} in every loaded dimension. */
    public static List<Located> ofOwner(MinecraftServer server, UUID owner) {
        return all(server).stream().filter(located -> located.entry().owner().equals(owner)).toList();
    }

    /** Adds or updates a revenant. Returns whether anything changed. */
    public boolean put(UUID revenant, UUID owner, String ownerName, BlockPos pos) {
        Entry entry = new Entry(revenant, owner, ownerName, pos.immutable());
        if (entry.equals(revenants.put(revenant, entry))) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean remove(UUID revenant) {
        if (revenants.remove(revenant) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    public Entry find(UUID revenant) {
        return revenants.get(revenant);
    }

    /** A snapshot of every entry in this dimension. */
    public List<Entry> entries() {
        return List.copyOf(revenants.values());
    }

    public int size() {
        return revenants.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : revenants.values()) {
            CompoundTag revenant = new CompoundTag();
            revenant.putUUID("revenant", entry.revenant());
            revenant.putUUID("owner", entry.owner());
            revenant.putString("owner_name", entry.ownerName());
            revenant.putLong("pos", entry.pos().asLong());
            list.add(revenant);
        }
        tag.put("revenants", list);
        return tag;
    }

    public static RevenantIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        RevenantIndex index = new RevenantIndex();
        for (Tag element : tag.getList("revenants", Tag.TAG_COMPOUND)) {
            CompoundTag revenant = (CompoundTag) element;
            UUID id = revenant.getUUID("revenant");
            index.revenants.put(id, new Entry(id, revenant.getUUID("owner"), revenant.getString("owner_name"),
                    BlockPos.of(revenant.getLong("pos"))));
        }
        return index;
    }
}
