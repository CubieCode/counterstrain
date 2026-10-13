package com.cubiecode.remission.network;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.variant.VariantRecords;
import com.cubiecode.remission.variant.Variants;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
 * Server side of {@link VariantLandPayload} (P7.5): queues a chunk's variant for a player when the chunk is sent to them
 * and it has one (a level without variants costs one check per chunk), and the current variant of every chunk of a
 * nidus's land, for each player watching it, when that nidus's variant changes or the nidus goes. Each player's queue is
 * sent once per server tick; a later value for the same chunk replaces an earlier one.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class VariantLandSync {
    private static final Map<ServerPlayer, Map<ResourceKey<Level>, Map<Long, Optional<ResourceLocation>>>> PENDING =
            new IdentityHashMap<>();

    private VariantLandSync() {
    }

    private static void queue(ServerPlayer player, ServerLevel level, ChunkPos chunk, Optional<ResourceLocation> variant) {
        PENDING.computeIfAbsent(player, p -> new LinkedHashMap<>())
                .computeIfAbsent(level.dimension(), d -> new LinkedHashMap<>())
                .put(chunk.toLong(), variant);
    }

    /**
     * The land of the nidus at {@code nidus} (every chunk within {@code reach}) may have changed variant: queues each
     * chunk's current variant for the players watching it.
     */
    public static void landChanged(ServerLevel level, BlockPos nidus, int reach) {
        ChunkPos centre = new ChunkPos(nidus);
        for (int dx = -reach; dx <= reach; dx++) {
            int span = reach - Math.abs(dx);
            for (int dz = -span; dz <= span; dz++) {
                ChunkPos chunk = new ChunkPos(centre.x + dx, centre.z + dz);
                var players = level.getChunkSource().chunkMap.getPlayers(chunk, false);
                if (players.isEmpty()) {
                    continue;
                }
                Optional<ResourceLocation> variant = Variants.variantAt(level, chunk);
                for (ServerPlayer player : players) {
                    queue(player, level, chunk, variant);
                }
            }
        }
    }

    @SubscribeEvent
    static void onChunkSent(ChunkWatchEvent.Sent event) {
        if (!VariantRecords.get(event.getLevel()).anyVariant()) {
            return;
        }
        Variants.variantAt(event.getLevel(), event.getPos())
                .ifPresent(variant -> queue(event.getPlayer(), event.getLevel(), event.getPos(), Optional.of(variant)));
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
            dimensions.forEach((dimension, chunks) -> {
                Map<Long, Optional<ResourceLocation>> batch = new LinkedHashMap<>();
                for (Map.Entry<Long, Optional<ResourceLocation>> entry : chunks.entrySet()) {
                    batch.put(entry.getKey(), entry.getValue());
                    if (batch.size() == VariantLandPayload.MAX_ENTRIES) {
                        PacketDistributor.sendToPlayer(player, new VariantLandPayload(dimension, batch));
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    PacketDistributor.sendToPlayer(player, new VariantLandPayload(dimension, batch));
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
