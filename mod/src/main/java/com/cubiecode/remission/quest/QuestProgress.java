package com.cubiecode.remission.quest;

import com.cubiecode.remission.registry.RemissionStats;
import com.cubiecode.remission.registry.RemissionTriggers;
import com.cubiecode.remission.research.Bestiary;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
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

    /** The player broke the nidus at {@code pos}; it is already removed. */
    public static void nidusDestroyed(ServerPlayer player, BlockPos pos) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.NIDI_DESTROYED.get());
        RemissionTriggers.NIDUS_DESTROYED.get().trigger(player, pos);
    }

    /** The player took a host scan of {@code host} at {@code stage}; the bestiary already holds it. */
    public static void hostScanned(ServerPlayer player, LivingEntity host, int stage) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.HOSTS_SCANNED.get());
        RemissionTriggers.HOST_SCANNED.get().trigger(player, host, stage, Bestiary.get(player).highestStages().size());
    }

    /** The player took a block scan of the block at {@code pos}. */
    public static void blockScanned(ServerPlayer player, BlockPos pos) {
        if (player instanceof FakePlayer) {
            return;
        }
        player.awardStat(RemissionStats.BLOCKS_SCANNED.get());
        RemissionTriggers.BLOCK_SCANNED.get().trigger(player, pos);
    }
}
