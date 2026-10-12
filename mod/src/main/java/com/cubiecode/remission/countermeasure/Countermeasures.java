package com.cubiecode.remission.countermeasure;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.StrainHazardEvent;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.quest.QuestProgress;
import com.cubiecode.remission.registry.RemissionAttachments;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Countermeasures (P5.7): which Strain hazards each player is protected from, in the {@link RemissionAttachments#COUNTERMEASURES}
 * attachment (mirrored in {@link CountermeasureRecords} for offline lookups), and the hazard queries every hazard asks
 * (public face: {@code api.StrainHazards}). A team shares its countermeasures: a grant reaches the player's online
 * teammates, and a player who logs in or changes team pools theirs with their online teammates'. Teams come from the
 * team query ({@link TeamQueries}); a team change is noticed through its invalidation calls and shared on the next
 * server tick. Server authoritative; the attachment syncs to its owner only.
 *
 * <p>Methods taking a collection of online players use it instead of the server's player list, so gametests can
 * supply players that never joined.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Countermeasures {
    /** How far a team member may be from a block, in blocks, for {@link BlockHazardMode#PLAYER_NEARBY}. */
    public static final int NEARBY_RADIUS = 128;

    private static final Set<UUID> PENDING_SHARES = new HashSet<>();
    private static boolean pendingShareAll;

    private Countermeasures() {
    }

    // ---- held countermeasures ---------------------------------------------------------------------------------------

    /** The player's countermeasures, or {@link CountermeasureSet#EMPTY}. Never creates the attachment. Either side. */
    public static CountermeasureSet get(Player player) {
        CountermeasureSet set = player.getExistingDataOrNull(RemissionAttachments.COUNTERMEASURES);
        return set == null ? CountermeasureSet.EMPTY : set;
    }

    /** Whether the player holds the countermeasure (their own record). Either side (a client knows its own). */
    public static boolean holds(Player player, ResourceLocation hazard) {
        return get(player).holds(hazard);
    }

    /**
     * Whether the player with UUID {@code player} (online or not) or anyone on their team holds the countermeasure,
     * from the records. Server thread.
     */
    public static boolean teamHolds(MinecraftServer server, UUID player, ResourceLocation hazard) {
        Map<UUID, CountermeasureSet> records = CountermeasureRecords.get(server).players();
        CountermeasureSet own = records.get(player);
        if (own != null && own.holds(hazard)) {
            return true;
        }
        for (Map.Entry<UUID, CountermeasureSet> entry : records.entrySet()) {
            if (entry.getValue().holds(hazard) && TeamQueries.areTeammates(server, player, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives the player the countermeasure with {@code cause}, then every online teammate who lacks it (cause
     * {@code TEAM}). Returns how many players gained it (0 when the whole online team already held it). Fake players
     * gain nothing. Server only.
     */
    public static int grant(ServerPlayer player, ResourceLocation hazard, CountermeasureGrantedEvent.Cause cause) {
        return grant(player, hazard, cause, online(player.server));
    }

    public static int grant(ServerPlayer player, ResourceLocation hazard, CountermeasureGrantedEvent.Cause cause,
            Collection<ServerPlayer> online) {
        if (player instanceof FakePlayer) {
            return 0;
        }
        int gained = add(player, hazard, cause) ? 1 : 0;
        for (ServerPlayer teammate : onlineTeammates(player, online)) {
            if (add(teammate, hazard, CountermeasureGrantedEvent.Cause.TEAM)) {
                gained++;
            }
        }
        return gained;
    }

    /**
     * Takes the countermeasure from the player and every online teammate, so the team share does not hand it back.
     * Offline teammates keep theirs and share it again when they log in. Returns how many players lost it.
     */
    public static int revoke(ServerPlayer player, ResourceLocation hazard) {
        return revoke(player, hazard, online(player.server));
    }

    public static int revoke(ServerPlayer player, ResourceLocation hazard, Collection<ServerPlayer> online) {
        int lost = 0;
        List<ServerPlayer> team = new ArrayList<>();
        team.add(player);
        team.addAll(onlineTeammates(player, online));
        for (ServerPlayer member : team) {
            CountermeasureSet old = get(member);
            if (old.holds(hazard)) {
                store(member, old.without(hazard));
                lost++;
            }
        }
        return lost;
    }

    /**
     * Pools the countermeasures of the player and their online teammates: each of them gains every one any of them
     * holds (cause {@code TEAM}). Run when the player logs in or their team changes. Returns how many countermeasures
     * were gained in all.
     */
    public static int shareWithTeam(ServerPlayer player, Collection<ServerPlayer> online) {
        if (player instanceof FakePlayer) {
            return 0;
        }
        List<ServerPlayer> team = new ArrayList<>();
        team.add(player);
        team.addAll(onlineTeammates(player, online));
        Set<ResourceLocation> pooled = new HashSet<>();
        team.forEach(member -> pooled.addAll(get(member).hazards()));
        int gained = 0;
        for (ServerPlayer member : team) {
            for (ResourceLocation hazard : pooled) {
                if (add(member, hazard, CountermeasureGrantedEvent.Cause.TEAM)) {
                    gained++;
                }
            }
        }
        return gained;
    }

    /** Adds one countermeasure to one player; posts the event and awards the trigger and stat if it was new. */
    private static boolean add(ServerPlayer player, ResourceLocation hazard, CountermeasureGrantedEvent.Cause cause) {
        CountermeasureSet old = get(player);
        if (old.holds(hazard) || player instanceof FakePlayer) {
            return false;
        }
        store(player, old.with(hazard));
        NeoForge.EVENT_BUS.post(new CountermeasureGrantedEvent(player, hazard, cause));
        QuestProgress.countermeasureGained(player, hazard);
        return true;
    }

    /** Stores the player's set (removing an empty attachment) and mirrors it in the records. */
    private static void store(ServerPlayer player, CountermeasureSet set) {
        if (set.isEmpty()) {
            player.removeData(RemissionAttachments.COUNTERMEASURES);
        } else {
            player.setData(RemissionAttachments.COUNTERMEASURES, set);
        }
        CountermeasureRecords.get(player.server).put(player.getUUID(), set);
    }

    /** Online players other than {@code player} on their team (the team query). */
    private static List<ServerPlayer> onlineTeammates(ServerPlayer player, Collection<ServerPlayer> online) {
        return online.stream()
                .filter(other -> other != player && !(other instanceof FakePlayer)
                        && !other.getUUID().equals(player.getUUID())
                        && TeamQueries.areTeammates(player.server, player.getUUID(), other.getUUID()))
                .toList();
    }

    private static Collection<ServerPlayer> online(MinecraftServer server) {
        return server.getPlayerList().getPlayers();
    }

    // ---- hazard queries ---------------------------------------------------------------------------------------------

    /**
     * Whether a player hazard applies to the player: false if their team holds the countermeasure, otherwise false
     * when a {@link StrainHazardEvent} listener cancels it. Server thread.
     */
    public static boolean applies(ServerPlayer player, ResourceLocation hazard) {
        if (holds(player, hazard) || teamHolds(player.server, player.getUUID(), hazard)) {
            return false;
        }
        return !NeoForge.EVENT_BUS.post(new StrainHazardEvent(player.server, hazard, player, null, null, null)).isCanceled();
    }

    /**
     * Whether a block hazard applies to the block at {@code pos}, placed by {@code placer} (null when unknown), under
     * the configured {@link BlockHazardMode}. Server thread.
     */
    public static boolean appliesAtBlock(ServerLevel level, BlockPos pos, @Nullable UUID placer, ResourceLocation hazard) {
        return appliesAtBlock(level, pos, placer, hazard, RemissionCommonConfig.COUNTERMEASURES_BLOCK_HAZARDS.get(),
                online(level.getServer()));
    }

    /**
     * The block hazard query with an explicit mode and online players: the presence rule first (a block with no known
     * placer needs a player within {@link #NEARBY_RADIUS}), then the countermeasure of the placer's team (of the nearest
     * player's team when there is no placer), then the event.
     */
    public static boolean appliesAtBlock(ServerLevel level, BlockPos pos, @Nullable UUID placer, ResourceLocation hazard,
            BlockHazardMode mode, Collection<ServerPlayer> online) {
        MinecraftServer server = level.getServer();
        ServerPlayer present;
        if (placer == null) {
            present = nearest(level, pos, online, null);
            if (present == null || teamHolds(server, present.getUUID(), hazard)) {
                return false;
            }
        } else {
            present = switch (mode) {
                case PLAYER_NEARBY -> nearest(level, pos, online, placer);
                case TEAM_ONLINE, ALWAYS -> teamMemberOnline(server, placer, online);
            };
            if ((present == null && mode != BlockHazardMode.ALWAYS) || teamHolds(server, placer, hazard)) {
                return false;
            }
        }
        return !NeoForge.EVENT_BUS.post(new StrainHazardEvent(server, hazard, present, level, pos, placer)).isCanceled();
    }

    /** The placer if online, else the first online teammate of theirs, else null. */
    @Nullable
    private static ServerPlayer teamMemberOnline(MinecraftServer server, UUID placer, Collection<ServerPlayer> online) {
        ServerPlayer teammate = null;
        for (ServerPlayer player : online) {
            if (player instanceof FakePlayer) {
                continue;
            }
            if (player.getUUID().equals(placer)) {
                return player;
            }
            if (teammate == null && TeamQueries.areTeammates(server, placer, player.getUUID())) {
                teammate = player;
            }
        }
        return teammate;
    }

    /**
     * The nearest non-spectator player in {@code level} within {@link #NEARBY_RADIUS} of {@code pos}; when
     * {@code teamOf} is set, only that player and their teammates count.
     */
    @Nullable
    private static ServerPlayer nearest(ServerLevel level, BlockPos pos, Collection<ServerPlayer> online, @Nullable UUID teamOf) {
        double best = (double) NEARBY_RADIUS * NEARBY_RADIUS;
        ServerPlayer nearest = null;
        for (ServerPlayer player : online) {
            if (player instanceof FakePlayer || player.isSpectator() || player.level() != level) {
                continue;
            }
            double distance = player.distanceToSqr(pos.getCenter());
            if (distance <= best && (teamOf == null || TeamQueries.areTeammates(level.getServer(), teamOf, player.getUUID()))) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    // ---- sync, login and team changes -------------------------------------------------------------------------------

    /** Attachment sync filter for updates: a change goes to its owner only (as the bestiary). */
    public static boolean syncsTo(IAttachmentHolder holder, Player to) {
        return holder == to;
    }

    /** The player's team changed (called from {@link TeamQueries#invalidate}); shared on the next server tick. */
    public static void teamChanged(UUID player) {
        synchronized (PENDING_SHARES) {
            PENDING_SHARES.add(player);
        }
    }

    /** Teams changed in bulk (called from {@link TeamQueries#invalidateAll}); every online team shares next tick. */
    public static void teamsChanged() {
        synchronized (PENDING_SHARES) {
            pendingShareAll = true;
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        Set<UUID> pending;
        boolean all;
        synchronized (PENDING_SHARES) {
            if (PENDING_SHARES.isEmpty() && !pendingShareAll) {
                return;
            }
            pending = Set.copyOf(PENDING_SHARES);
            all = pendingShareAll;
            PENDING_SHARES.clear();
            pendingShareAll = false;
        }
        MinecraftServer server = event.getServer();
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        for (ServerPlayer player : List.copyOf(online)) {
            if (all || pending.contains(player.getUUID())) {
                shareWithTeam(player, online);
            }
        }
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            CountermeasureRecords.get(player.server).put(player.getUUID(), get(player));
            shareWithTeam(player, online(player.server));
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        synchronized (PENDING_SHARES) {
            PENDING_SHARES.clear();
            pendingShareAll = false;
        }
    }
}
