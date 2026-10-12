package com.cubiecode.remission.api;

import com.cubiecode.remission.countermeasure.Countermeasures;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The one answer to "are these two players teammates?" (P4.12): every Remission rule that involves teammates asks it
 * here. Answers come from {@link TeamLookupEvent}; a player is always their own teammate, and with nothing answering,
 * the only one. Players are identified by UUID, so either may be offline (as far as the answering source allows).
 *
 * <p>Answers are cached per unordered pair for {@link #CACHE_TICKS} server ticks, so callers can ask every tick. Team
 * sources call {@link #invalidate} or {@link #invalidateAll} when their teams change; the FTB Teams integration does.
 * Server only: call it on the server thread. The cache is cleared when the server stops.
 */
public final class TeamQueries {
    /** How long an answer is reused before the lookup event is posted again, in server ticks (5 seconds). */
    public static final int CACHE_TICKS = 100;
    private static final int MAX_CACHED = 4096;
    private static final Map<Pair, Answer> CACHE = new HashMap<>();

    private TeamQueries() {
    }

    /** Whether players {@code a} and {@code b} are teammates; true when they are the same player. */
    public static boolean areTeammates(MinecraftServer server, UUID a, UUID b) {
        if (a.equals(b)) {
            return true;
        }
        if (!server.isSameThread()) {
            return lookup(server, a, b);
        }
        Pair pair = Pair.of(a, b);
        int now = server.getTickCount();
        Answer cached = CACHE.get(pair);
        if (cached != null && now < cached.expiresAt()) {
            return cached.teammates();
        }
        boolean teammates = lookup(server, a, b);
        if (CACHE.size() >= MAX_CACHED) {
            CACHE.values().removeIf(answer -> now >= answer.expiresAt());
            if (CACHE.size() >= MAX_CACHED) {
                CACHE.clear();
            }
        }
        CACHE.put(pair, new Answer(teammates, now + CACHE_TICKS));
        return teammates;
    }

    /**
     * Whether {@code player} and the player with UUID {@code other} (who may be offline) are teammates. On the client
     * side only a player and themselves are teammates.
     */
    public static boolean areTeammates(Player player, UUID other) {
        MinecraftServer server = player.getServer();
        if (server == null || player.level().isClientSide()) {
            return player.getUUID().equals(other);
        }
        return areTeammates(server, player.getUUID(), other);
    }

    /** Whether two players are teammates. On the client side only a player and themselves are teammates. */
    public static boolean areTeammates(Player a, Player b) {
        return areTeammates(a, b.getUUID());
    }

    /**
     * Drops every cached answer involving {@code player}; call it when that player's team changes. Also pools the
     * player's countermeasures with their new online teammates on the next server tick (P5.7).
     */
    public static void invalidate(UUID player) {
        CACHE.keySet().removeIf(pair -> pair.low().equals(player) || pair.high().equals(player));
        Countermeasures.teamChanged(player);
    }

    /**
     * Drops every cached answer; call it when teams change in bulk (a team deleted, teams reloaded). Also pools every
     * online team's countermeasures on the next server tick (P5.7).
     */
    public static void invalidateAll() {
        CACHE.clear();
        Countermeasures.teamsChanged();
    }

    private static boolean lookup(MinecraftServer server, UUID a, UUID b) {
        return NeoForge.EVENT_BUS.post(new TeamLookupEvent(server, a, b)).areTeammates();
    }

    private record Pair(UUID low, UUID high) {
        static Pair of(UUID a, UUID b) {
            return a.compareTo(b) <= 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }

    private record Answer(boolean teammates, int expiresAt) {
    }
}
