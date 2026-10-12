package com.cubiecode.remission.api;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/**
 * Asks whether two different players are teammates, on behalf of {@link TeamQueries#areTeammates}. Team sources (a
 * pack's scripts, an integration such as FTB Teams) answer with {@link #setTeammates}; with no answer the players are
 * not teammates, so without a team source every player is their own team. Remission has no teams of its own.
 *
 * <p>Posted on the server thread, only when {@link TeamQueries} has no fresh cached answer for the pair, and never for a
 * player and themselves (always teammates). Either player may be offline: answer from the UUIDs, never by requiring a
 * {@code ServerPlayer}. Answers must be symmetric, because the cached answer for {@code (a, b)} is also returned for
 * {@code (b, a)}. The last listener to call {@link #setTeammates} wins; a listener that only fills gaps checks
 * {@link #hasAnswer()} first, and one that overrides another source listens at a lower priority. A source whose teams
 * change should call {@link TeamQueries#invalidate} so the change is seen before the cache expires. Not cancellable.
 */
public class TeamLookupEvent extends Event {
    private final MinecraftServer server;
    private final UUID first;
    private final UUID second;
    private Boolean answer;

    public TeamLookupEvent(MinecraftServer server, UUID first, UUID second) {
        this.server = server;
        this.first = first;
        this.second = second;
    }

    public MinecraftServer getServer() {
        return server;
    }

    /** One player of the pair (the first argument of the query). */
    public UUID getFirst() {
        return first;
    }

    /** The other player of the pair; never equal to {@link #getFirst()}. */
    public UUID getSecond() {
        return second;
    }

    /** Whether a listener has answered. */
    public boolean hasAnswer() {
        return answer != null;
    }

    /** The answer so far: false until a listener answers. */
    public boolean areTeammates() {
        return answer != null && answer;
    }

    /** Answers the lookup, replacing any earlier answer. */
    public void setTeammates(boolean teammates) {
        this.answer = teammates;
    }
}
