package com.cubiecode.remission.api;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.countermeasure.Countermeasures;
import com.cubiecode.remission.notice.HazardStatus;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Strain hazards and countermeasures (P5.7): the one answer to "does this hazard apply?" that every Strain hazard asks,
 * and the countermeasures players hold. A hazard is a plain id (no registry); integrations use their own namespace.
 * Remission's are {@link #CONTACT} now and {@code remission:shedding}, {@code remission:fouling}, {@code remission:rot},
 * {@code remission:containment} and {@code remission:contamination} as their items land. Danger (surges, nidi, hosts,
 * variants, budding) is never a hazard and never asks.
 *
 * <p>A team shares its countermeasures (the team query, {@link TeamQueries}): a hazard does not apply when anyone on the
 * team holds its countermeasure. Otherwise {@link StrainHazardEvent} decides, so a pack can hold a hazard back.
 */
public final class StrainHazards {
    /** Contact viral load (P5.2): viral load gained while touching a block in {@code #remission:infectious}. */
    public static final ResourceLocation CONTACT = Remission.id("contact");

    private StrainHazards() {
    }

    /**
     * Whether a player hazard (one that acts on a player, such as contact viral load) applies to {@code player}: false
     * when their team holds the countermeasure, otherwise false when a {@link StrainHazardEvent} listener cancels it.
     * Server thread.
     */
    public static boolean applies(ServerPlayer player, ResourceLocation hazard) {
        return Countermeasures.applies(player, hazard);
    }

    /**
     * Whether a block hazard (one that acts on a placed block, such as fouling or rot) applies to the block at
     * {@code pos}, placed by {@code placer} ({@code null} when unknown). False unless the presence rule of
     * {@code [countermeasures] blockHazards} is met: {@code TEAM_ONLINE} (default) a player from the placer's team is
     * online; {@code PLAYER_NEARBY} one is in the level within {@link Countermeasures#NEARBY_RADIUS} blocks;
     * {@code ALWAYS} no rule. A block with no known placer needs any player within that radius, and that player's team
     * stands in for the placer's. Then false when the placer's team holds the countermeasure, otherwise the event
     * decides. Server thread.
     */
    public static boolean appliesAtBlock(ServerLevel level, BlockPos pos, @Nullable UUID placer, ResourceLocation hazard) {
        return Countermeasures.appliesAtBlock(level, pos, placer, hazard);
    }

    /** Whether the player holds the countermeasure themselves. Either side (a client knows only its own player's). */
    public static boolean holds(Player player, ResourceLocation hazard) {
        return Countermeasures.holds(player, hazard);
    }

    /** Every countermeasure the player holds, sorted. Either side (a client knows only its own player's). */
    public static Set<ResourceLocation> countermeasures(Player player) {
        return Countermeasures.get(player).hazards();
    }

    /** Whether the player with UUID {@code player} (online or not) or a teammate holds the countermeasure. Server thread. */
    public static boolean teamHolds(MinecraftServer server, UUID player, ResourceLocation hazard) {
        return Countermeasures.teamHolds(server, player, hazard);
    }

    /**
     * Grants the countermeasure to the player and their online teammates (event cause {@code OTHER} for the player,
     * {@code TEAM} for teammates). Returns how many players gained it. Server thread.
     */
    public static int grant(ServerPlayer player, ResourceLocation hazard) {
        return Countermeasures.grant(player, hazard, CountermeasureGrantedEvent.Cause.OTHER);
    }

    /**
     * The hazard's player-facing name: lang key {@code hazard.<namespace>.<path>}, falling back to the id, so an
     * integration's hazard reads sensibly before it ships a name.
     */
    public static Component displayName(ResourceLocation hazard) {
        return Component.translatableWithFallback("hazard." + hazard.getNamespace() + "." + hazard.getPath(),
                hazard.toString());
    }

    /**
     * Takes the countermeasure from the player's whole team: online members at once, offline members' records at once
     * and their attachments at their next login. Returns how many lost it, offline ones included. Server thread.
     */
    public static int revoke(ServerPlayer player, ResourceLocation hazard) {
        return Countermeasures.revoke(player, hazard);
    }

    /**
     * Declares a hazard so the bestiary's hazards tab (P5.8) lists it while it applies to the player. Remission declares
     * its own built hazards; an integration declares its own once, at startup, and the declaration lasts for the game
     * session. Hazards reported through
     * {@link HazardNotices} and hazards a team holds a countermeasure for are listed without it. Any thread.
     */
    public static void declare(ResourceLocation hazard) {
        HazardStatus.declare(hazard);
    }

    /**
     * Every declared hazard and every hazard reported through {@link HazardNotices} on the running server (forgotten when
     * it stops), sorted (P5.8). Any thread.
     */
    public static Set<ResourceLocation> declared() {
        return HazardStatus.declared();
    }

    /**
     * Every countermeasure the player with UUID {@code player} (online or not) or anyone on their team holds, sorted
     * (P5.8). Server thread.
     */
    public static Set<ResourceLocation> teamCountermeasures(MinecraftServer server, UUID player) {
        return Countermeasures.teamCountermeasures(server, player);
    }
}
