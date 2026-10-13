package com.cubiecode.remission.notice;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.countermeasure.Countermeasures;
import com.cubiecode.remission.network.HazardNoticePayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Hazard notices (P5.8; public face {@code api.HazardNotices}): who is told about a reported strike, and the grouping
 * and rate limit. Each recipient has open groups; a report joins the open group of the same hazard and dimension
 * within {@link #GROUP_RADIUS} blocks of its first strike, or opens one. A group is sent as one notice
 * {@link #GROUP_TICKS} after it opened, but never sooner than {@link #COOLDOWN_TICKS} after the recipient's last
 * notice for that hazard, so repeats keep collecting into the waiting group. At most {@link #MAX_NOTICES_PER_HAZARD}
 * notices of one hazard go to a player at once; more due groups are summarised. Server thread; the server's tracker
 * flushes on each server tick and sends {@link HazardNoticePayload}s.
 *
 * <p>Instances other than the server's are for gametests, which pass their own online players and clock.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class HazardNoticeTracker {
    /** How long a group collects reports before it is sent, in server ticks (5 seconds). */
    public static final int GROUP_TICKS = 100;
    /** The least time between two notices of one hazard to one player, in server ticks (30 seconds). */
    public static final int COOLDOWN_TICKS = 600;
    /** How far from a group's first strike a report still joins it, in blocks. */
    public static final int GROUP_RADIUS = 32;
    /**
     * Open groups per player. A report that would open one more joins the nearest open group of its hazard instead
     * (which then covers more than one area); a hazard with no open group still gets one, so nothing is dropped.
     */
    static final int MAX_GROUPS = 32;
    /**
     * Notices of one hazard sent to one player in one flush: when more groups are due, the largest
     * {@code MAX_NOTICES_PER_HAZARD - 1} are sent as they are and the rest merged into one summary notice.
     */
    public static final int MAX_NOTICES_PER_HAZARD = 3;

    private static final HazardNoticeTracker SERVER = new HazardNoticeTracker();

    /**
     * One notice to send: {@code count} strikes of {@code hazard} in {@code areas} areas, one of them near {@code pos}
     * (the area with the most strikes); {@code areas} is 1 for an ordinary group.
     */
    public record Notice(UUID recipient, ResourceLocation hazard, ResourceKey<Level> dimension, BlockPos pos, int count,
            int areas) {
        public HazardNoticePayload payload() {
            return new HazardNoticePayload(hazard, dimension, pos, count, areas);
        }
    }

    private static final class Group {
        final ResourceLocation hazard;
        final ResourceKey<Level> dimension;
        final BlockPos anchor;
        final long opened;
        int count = 1;
        /** Areas (by {@link #GROUP_RADIUS}-block cell) of overflow reports merged into this group. */
        final LongSet extraAreas = new LongOpenHashSet();

        Group(ResourceLocation hazard, ResourceKey<Level> dimension, BlockPos anchor, long opened) {
            this.hazard = hazard;
            this.dimension = dimension;
            this.anchor = anchor;
            this.opened = opened;
        }

        int areas() {
            return 1 + extraAreas.size();
        }

        boolean joins(ResourceLocation hazard, ResourceKey<Level> dimension, BlockPos pos) {
            return this.hazard.equals(hazard) && this.dimension.equals(dimension)
                    && anchor.distSqr(pos) <= (double) GROUP_RADIUS * GROUP_RADIUS;
        }
    }

    private final Map<UUID, List<Group>> pending = new HashMap<>();
    private final Map<UUID, Map<ResourceLocation, Long>> lastSent = new HashMap<>();

    public HazardNoticeTracker() {
    }

    /** Reports a strike to the server's tracker (from {@code api.HazardNotices}). */
    public static void report(ServerLevel level, ResourceLocation hazard, BlockPos pos, @Nullable UUID placer) {
        HazardStatus.reported(hazard);
        SERVER.report(level, hazard, pos, placer, level.getServer().getPlayerList().getPlayers(),
                level.getServer().getTickCount());
    }

    /** Reports a strike, telling the placer's team among {@code online}. Returns the recipients. */
    public List<ServerPlayer> report(ServerLevel level, ResourceLocation hazard, BlockPos pos, @Nullable UUID placer,
            Collection<ServerPlayer> online, long now) {
        List<ServerPlayer> recipients = recipients(level, pos, placer, online);
        BlockPos at = pos.immutable();
        for (ServerPlayer recipient : recipients) {
            List<Group> groups = pending.computeIfAbsent(recipient.getUUID(), uuid -> new ArrayList<>());
            Group group = groups.stream().filter(g -> g.joins(hazard, level.dimension(), at)).findFirst().orElse(null);
            if (group != null) {
                group.count++;
                continue;
            }
            Group nearest = groups.size() < MAX_GROUPS ? null : nearestOfHazard(groups, hazard, level.dimension(), at);
            if (nearest != null) {
                nearest.count++;
                nearest.extraAreas.add(BlockPos.asLong(at.getX() >> 5, at.getY() >> 5, at.getZ() >> 5));
            } else {
                groups.add(new Group(hazard, level.dimension(), at, now));
            }
        }
        return recipients;
    }

    /** The open group of {@code hazard} nearest to {@code pos}, preferring the same dimension; null if none. */
    @Nullable
    private static Group nearestOfHazard(List<Group> groups, ResourceLocation hazard, ResourceKey<Level> dimension,
            BlockPos pos) {
        return groups.stream().filter(g -> g.hazard.equals(hazard))
                .min(Comparator.comparing((Group g) -> !g.dimension.equals(dimension))
                        .thenComparingDouble(g -> g.anchor.distSqr(pos)))
                .orElse(null);
    }

    /**
     * The online players to tell: the placer and their online teammates; with no known placer, the nearest player
     * within {@link Countermeasures#NEARBY_RADIUS} blocks in the level and their online teammates. Fake players never.
     */
    public static List<ServerPlayer> recipients(ServerLevel level, BlockPos pos, @Nullable UUID placer,
            Collection<ServerPlayer> online) {
        MinecraftServer server = level.getServer();
        UUID team = placer != null ? placer : nearest(level, pos, online);
        if (team == null) {
            return List.of();
        }
        return online.stream()
                .filter(player -> !(player instanceof FakePlayer)
                        && TeamQueries.areTeammates(server, team, player.getUUID()))
                .toList();
    }

    @Nullable
    private static UUID nearest(ServerLevel level, BlockPos pos, Collection<ServerPlayer> online) {
        double best = (double) Countermeasures.NEARBY_RADIUS * Countermeasures.NEARBY_RADIUS;
        UUID nearest = null;
        for (ServerPlayer player : online) {
            if (player instanceof FakePlayer || player.isSpectator() || player.level() != level) {
                continue;
            }
            double distance = player.distanceToSqr(pos.getCenter());
            if (distance <= best) {
                best = distance;
                nearest = player.getUUID();
            }
        }
        return nearest;
    }

    /** Takes the groups that are due at {@code now} as notices, oldest first per player. */
    public List<Notice> flush(long now) {
        return flush(now, false);
    }

    /** Takes every open group as a notice now, ignoring the grouping time and the cooldown (the harness). */
    public List<Notice> flushAll(long now) {
        return flush(now, true);
    }

    private List<Notice> flush(long now, boolean all) {
        List<Notice> notices = new ArrayList<>();
        Iterator<Map.Entry<UUID, List<Group>>> players = pending.entrySet().iterator();
        while (players.hasNext()) {
            Map.Entry<UUID, List<Group>> entry = players.next();
            UUID recipient = entry.getKey();
            Map<ResourceLocation, Long> sent = lastSent.computeIfAbsent(recipient, uuid -> new HashMap<>());
            Map<ResourceLocation, List<Group>> due = new LinkedHashMap<>();
            Iterator<Group> groups = entry.getValue().iterator();
            while (groups.hasNext()) {
                Group group = groups.next();
                Long last = sent.get(group.hazard);
                long dueAt = Math.max(group.opened + GROUP_TICKS, last == null ? Long.MIN_VALUE : last + COOLDOWN_TICKS);
                if (all || now >= dueAt) {
                    due.computeIfAbsent(group.hazard, hazard -> new ArrayList<>()).add(group);
                    groups.remove();
                }
            }
            due.forEach((hazard, ready) -> {
                notices.addAll(noticesOf(recipient, ready));
                sent.put(hazard, now);
            });
            if (entry.getValue().isEmpty()) {
                players.remove();
            }
        }
        lastSent.values().forEach(sent -> sent.values().removeIf(last -> now - last >= COOLDOWN_TICKS));
        lastSent.values().removeIf(Map::isEmpty);
        return notices;
    }

    /**
     * The notices for one hazard's due groups (in opening order): each group as it is, or, past
     * {@link #MAX_NOTICES_PER_HAZARD}, the largest groups and one summary of the rest.
     */
    private static List<Notice> noticesOf(UUID recipient, List<Group> ready) {
        if (ready.size() <= MAX_NOTICES_PER_HAZARD) {
            return ready.stream().map(g -> new Notice(recipient, g.hazard, g.dimension, g.anchor, g.count, g.areas()))
                    .toList();
        }
        List<Group> bySize = new ArrayList<>(ready);
        bySize.sort(Comparator.comparingInt((Group g) -> g.count).reversed());
        List<Notice> notices = new ArrayList<>();
        for (Group g : bySize.subList(0, MAX_NOTICES_PER_HAZARD - 1)) {
            notices.add(new Notice(recipient, g.hazard, g.dimension, g.anchor, g.count, g.areas()));
        }
        List<Group> rest = bySize.subList(MAX_NOTICES_PER_HAZARD - 1, bySize.size());
        Group largest = rest.getFirst();
        notices.add(new Notice(recipient, largest.hazard, largest.dimension, largest.anchor,
                rest.stream().mapToInt(g -> g.count).sum(), rest.stream().mapToInt(Group::areas).sum()));
        return notices;
    }

    /** Drops a player's open groups and cooldowns (they logged out). */
    public void forget(UUID player) {
        pending.remove(player);
        lastSent.remove(player);
    }

    public void clear() {
        pending.clear();
        lastSent.clear();
    }

    /** Sends {@code notices} to those recipients still online. */
    public static void send(MinecraftServer server, List<Notice> notices) {
        for (Notice notice : notices) {
            ServerPlayer player = server.getPlayerList().getPlayer(notice.recipient());
            if (player != null) {
                PacketDistributor.sendToPlayer(player, notice.payload());
            }
        }
    }

    /** The harness: sends every open group of the server's tracker at once. */
    public static void sendAllNow(MinecraftServer server) {
        send(server, SERVER.flushAll(server.getTickCount()));
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (!SERVER.pending.isEmpty()) {
            send(event.getServer(), SERVER.flush(event.getServer().getTickCount()));
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SERVER.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        SERVER.clear();
    }
}
