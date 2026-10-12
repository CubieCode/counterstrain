package com.cubiecode.remission.api;

import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.revenant.Revenant;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadState;
import com.cubiecode.remission.world.ChunkCorruption;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Read-only queries over Remission's state. Entity queries work on both sides (hosts are synced to tracking clients,
 * viral load only to its own player); land queries are server only, because chunk Strain levels are not synced, and read
 * loaded chunks only (an unloaded chunk reports level and stage 0; it never loads one).
 */
public final class CorruptionQueries {
    /** Highest Strain level of an entity or a chunk. */
    public static final int MAX_LEVEL = CorruptionState.MAX_LEVEL;
    /** Highest viral load of a player. */
    public static final int MAX_VIRAL_LOAD = ViralLoadState.MAX;

    private CorruptionQueries() {
    }

    /** Whether {@code entity} is a living host (host stage 1 or higher). False for non-living entities. */
    public static boolean isHost(Entity entity) {
        return entity instanceof LivingEntity living && Corruption.isHost(living);
    }

    /** The entity's stored Strain level, 0 to {@link #MAX_LEVEL}; 0 for clean or non-living entities. */
    public static int hostLevel(Entity entity) {
        return entity instanceof LivingEntity living ? Corruption.get(living).level() : 0;
    }

    /** The entity's stored host stage (0 = not a host, then one per {@code hosts.stageThresholds} entry met). */
    public static int hostStage(Entity entity) {
        return entity instanceof LivingEntity living ? Corruption.get(living).stage() : 0;
    }

    /** Strain level of a loaded chunk, 0 to {@link #MAX_LEVEL}; 0 when the chunk is clean or not loaded. */
    public static int chunkLevel(ServerLevel level, ChunkPos chunk) {
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        if (loaded == null) {
            return 0;
        }
        ChunkCorruption state = loaded.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        return state == null ? 0 : state.level();
    }

    /** Festering stage of a loaded chunk: {@link #chunkStageFor} of its level; 0 when clean or not loaded. */
    public static int chunkStage(ServerLevel level, ChunkPos chunk) {
        return chunkStageFor(chunkLevel(level, chunk));
    }

    /** Strain level of the chunk holding {@code pos}. */
    public static int levelAt(ServerLevel level, BlockPos pos) {
        return chunkLevel(level, new ChunkPos(pos));
    }

    /** Festering stage of the chunk holding {@code pos}. Stages are per chunk; every block in a chunk shares one. */
    public static int stageAt(ServerLevel level, BlockPos pos) {
        return chunkStage(level, new ChunkPos(pos));
    }

    /**
     * The festering stage of a chunk at Strain level {@code level}: the number of {@code festering.stageThresholds}
     * entries it meets (0 = clean; with the defaults {@code [1, 25, 50, 75]}, stages 1 to 4). Computed from the current
     * server config on every call.
     */
    public static int chunkStageFor(int level) {
        return CorruptionState.stageFor(level, RemissionCommonConfig.FESTERING_STAGE_THRESHOLDS.get());
    }

    /** The highest festering stage the current config allows (the number of thresholds). */
    public static int maxChunkStage() {
        return RemissionCommonConfig.FESTERING_STAGE_THRESHOLDS.get().size();
    }

    /** Whether {@code entity} is a revenant ({@code remission:revenant}, P5.3). Both sides. */
    public static boolean isRevenant(Entity entity) {
        return entity instanceof Revenant;
    }

    /**
     * Whether a loaded chunk is in remission (P5.2): it was festering, was purified to Strain level 0 and has not
     * festered again since. False when the chunk never festered, is festering or is not loaded (never loads one).
     */
    public static boolean isInRemission(ServerLevel level, ChunkPos chunk) {
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        return loaded != null && loaded.hasData(RemissionAttachments.CHUNK_REMISSION);
    }

    /**
     * A player's viral load, 0 to {@link #MAX_VIRAL_LOAD} (P5.1). On the server, any player; on a client, only the local
     * player's own value is synced, so other players read 0.
     */
    public static float viralLoad(Player player) {
        return ViralLoad.get(player);
    }
}
