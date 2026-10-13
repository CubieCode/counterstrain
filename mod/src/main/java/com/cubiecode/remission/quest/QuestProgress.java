package com.cubiecode.remission.quest;

import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.registry.RemissionTriggers;
import com.cubiecode.remission.research.Bestiary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Where gameplay awards Remission's stats and fires its advancement triggers (contracts: remission-data-formats). Server
 * only; called by the code that owns each action, after it succeeded. Fake players (machines acting as players) earn
 * nothing.
 */
public final class QuestProgress {
    private QuestProgress() {
    }

    /**
     * The player shares the credit for destroying the nidus at {@code pos} in {@code level} (P4.10: the player who
     * destroyed it and every contributor); it is already removed. The player may be in another level.
     */
    public static void nidusDestroyed(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.NIDI_DESTROYED.get());
        RemissionTriggers.NIDUS_DESTROYED.get().trigger(player, level, pos);
    }

    /** The player took a host scan of {@code host} at {@code stage}; the bestiary already holds it. */
    public static void hostScanned(ServerPlayer player, LivingEntity host, int stage) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.HOSTS_SCANNED.get());
        RemissionTriggers.HOST_SCANNED.get().trigger(player, host, stage, Bestiary.hostTypesScanned(Bestiary.team(player)));
    }

    /** The player took a block scan of the block at {@code pos}. */
    public static void blockScanned(ServerPlayer player, BlockPos pos) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.BLOCKS_SCANNED.get());
        RemissionTriggers.BLOCK_SCANNED.get().trigger(player, pos);
    }

    /** The player was caught in a surge of {@code intensity} that just ended, and did not die during it. */
    public static void surgeSurvived(ServerPlayer player, int intensity) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.SURGES_SURVIVED.get());
        RemissionTriggers.SURGE_SURVIVED.get().trigger(player, intensity);
    }

    /** Chunks (Chebyshev distance) from a chunk entering remission within which players are credited with it. */
    public static final int REMISSION_CREDIT_RADIUS = 8;

    /**
     * {@code chunk} entered remission (it is already marked). Every living, non-spectator player in the level whose chunk
     * is within {@link #REMISSION_CREDIT_RADIUS} of it counts one more chunk cleansed and fires
     * {@code remission:region_cleansed} for the region around their own chunk.
     */
    public static void chunkEnteredRemission(ServerLevel level, ChunkPos chunk) {
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            if (player instanceof FakePlayer || !player.isAlive() || player.isSpectator()
                    || Math.max(Math.abs(at.x - chunk.x), Math.abs(at.z - chunk.z)) > REMISSION_CREDIT_RADIUS) {
                continue;
            }
            player.awardStat(RemissionStats.CHUNKS_CLEANSED.get());
            RemissionTriggers.REGION_CLEANSED.get().trigger(player);
        }
    }

    /**
     * The player slew a revenant whose items they may pick up: their own ({@code own}) or a teammate's (the team query,
     * {@code TeamQueries}).
     */
    public static void revenantReclaimed(ServerPlayer player, boolean own) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.REVENANTS_RECLAIMED.get());
        RemissionTriggers.REVENANT_RECLAIMED.get().trigger(player, own);
    }

    /** The player's viral load changed to {@code viralLoad}; it is already stored. */
    public static void viralLoadChanged(ServerPlayer player, float viralLoad) {
        if (player instanceof FakePlayer) {
            return;
        }
        RemissionTriggers.VIRAL_LOAD_CHANGED.get().trigger(player, viralLoad);
    }

    /** The player gained the countermeasure for {@code hazard}; it is already stored (P5.7). */
    public static void countermeasureGained(ServerPlayer player, ResourceLocation hazard) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.COUNTERMEASURES_GAINED.get());
        RemissionTriggers.COUNTERMEASURE_GAINED.get().trigger(player, hazard);
    }
}
