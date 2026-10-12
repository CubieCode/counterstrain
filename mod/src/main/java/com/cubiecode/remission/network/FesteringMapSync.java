package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.ChunkStageChangedEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.world.ChunkCorruption;
import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of {@link FesteringMapPayload} (P8.2): queues a chunk's state (festering stage and remission) for a player
 * when the chunk is sent to them ({@link ChunkWatchEvent.Sent}, clean land included, so a client's map learns that land
 * it knew as festering is clean again) and for every player watching it when its stage changes
 * ({@link ChunkStageChangedEvent}, which also covers leaving remission, since only a level rise does that) or it enters
 * remission ({@link ChunkEnteredRemissionEvent}), and sends each player's queue once per server tick. A later state for the
 * same chunk replaces an earlier one, so purification's stage event and remission event in one tick send one entry.
 * Stages are computed from the current config when queued; a config change reaches clients as chunks are sent again.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class FesteringMapSync {
    private static final Map<ServerPlayer, Map<ResourceKey<Level>, Long2ByteLinkedOpenHashMap>> PENDING = new IdentityHashMap<>();

    private FesteringMapSync() {
    }

    /** Queues {@code state} of {@code chunk} in {@code level} for {@code player}; a later value for the same chunk wins. */
    static void queue(ServerPlayer player, ServerLevel level, ChunkPos chunk, int state) {
        PENDING.computeIfAbsent(player, p -> new LinkedHashMap<>())
                .computeIfAbsent(level.dimension(), d -> new Long2ByteLinkedOpenHashMap())
                .put(chunk.toLong(), (byte) state);
    }

    /** Queues the chunk's current state for every player watching it. */
    private static void queueWatching(ServerLevel level, ChunkPos chunk, int stage) {
        int state = FesteringMapPayload.state(stage, CorruptionQueries.isInRemission(level, chunk));
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk, false)) {
            queue(player, level, chunk, state);
        }
    }

    @SubscribeEvent
    static void onChunkSent(ChunkWatchEvent.Sent event) {
        ChunkCorruption corruption = event.getChunk().getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        int stage = corruption == null ? 0 : CorruptionQueries.chunkStageFor(corruption.level());
        boolean inRemission = event.getChunk().hasData(RemissionAttachments.CHUNK_REMISSION);
        queue(event.getPlayer(), event.getLevel(), event.getPos(), FesteringMapPayload.state(stage, inRemission));
    }

    @SubscribeEvent
    static void onStageChanged(ChunkStageChangedEvent event) {
        queueWatching(event.getLevel(), event.getChunk(), event.getNewStage());
    }

    @SubscribeEvent
    static void onEnteredRemission(ChunkEnteredRemissionEvent event) {
        queueWatching(event.getLevel(), event.getChunk(), 0);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        PENDING.forEach((player, dimensions) -> {
            if (player.hasDisconnected()) {
                return;
            }
            dimensions.forEach((dimension, states) -> {
                Long2ByteLinkedOpenHashMap batch = new Long2ByteLinkedOpenHashMap();
                for (Long2ByteMap.Entry entry : states.long2ByteEntrySet()) {
                    batch.put(entry.getLongKey(), entry.getByteValue());
                    if (batch.size() == FesteringMapPayload.MAX_ENTRIES) {
                        PacketDistributor.sendToPlayer(player, new FesteringMapPayload(dimension, batch));
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    PacketDistributor.sendToPlayer(player, new FesteringMapPayload(dimension, batch));
                }
            });
        });
        PENDING.clear();
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity());
    }
}
