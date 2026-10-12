package com.cubiecode.remission.surge;

import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;

/**
 * How players see a surge, through vanilla packets only (no Remission payload): a title, subtitle and raid horn when
 * the warning begins, the horn again and an action bar line when it starts, and a boss bar for everyone in the area
 * (yellow while warning, purple while running) whose progress counts down. A line on the action bar marks the end.
 */
final class SurgeWarnings {
    private final ServerBossEvent bar = new ServerBossEvent(Component.translatable("event.remission.surge.bar.warning"),
            BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);

    /** The warning began: tells everyone in the area. */
    void warn(Collection<ServerPlayer> inArea) {
        for (ServerPlayer player : inArea) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("event.remission.surge.warning.subtitle")));
            player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("event.remission.surge.warning.title")));
            horn(player);
        }
    }

    /** The surge started. */
    void started(Collection<ServerPlayer> inArea) {
        bar.setName(Component.translatable("event.remission.surge.bar.active"));
        bar.setColor(BossEvent.BossBarColor.PURPLE);
        for (ServerPlayer player : inArea) {
            player.displayClientMessage(Component.translatable("event.remission.surge.started"), true);
            horn(player);
        }
    }

    /** Keeps the bar on the players in the area and its progress at {@code progress} (1 full, 0 empty). */
    void update(Collection<ServerPlayer> inArea, float progress) {
        bar.setProgress(Math.max(0.0F, Math.min(1.0F, progress)));
        for (ServerPlayer player : new ArrayList<>(bar.getPlayers())) {
            if (!inArea.contains(player)) {
                bar.removePlayer(player);
            }
        }
        inArea.forEach(bar::addPlayer);
    }

    /** The surge ended (or was stopped): tells the players on the bar if it had started, and removes the bar. */
    void ended(boolean started) {
        if (started) {
            bar.getPlayers().forEach(player ->
                    player.displayClientMessage(Component.translatable("event.remission.surge.ended"), true));
        }
        clear();
    }

    void clear() {
        bar.removeAllPlayers();
        bar.setName(Component.translatable("event.remission.surge.bar.warning"));
        bar.setColor(BossEvent.BossBarColor.YELLOW);
        bar.setProgress(1.0F);
    }

    private static void horn(ServerPlayer player) {
        player.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64.0F, 1.0F);
    }
}
