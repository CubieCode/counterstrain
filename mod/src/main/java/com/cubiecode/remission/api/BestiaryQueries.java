package com.cubiecode.remission.api;

import com.cubiecode.remission.research.Bestiary;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * Read-only queries over the bestiary (P6.4). Research belongs to the team: the team progress is, for each host type,
 * the highest host stage the player or any teammate (the team query, {@link TeamQueries}) has scanned, offline teammates
 * included. With nothing answering the team query it is the player's own progress. Player methods work on both sides
 * (on the client, for the local player only, from the synced copy); UUID methods are server only and take players who
 * may be offline.
 */
public final class BestiaryQueries {
    private BestiaryQueries() {
    }

    /** Every host type the bestiary lists ({@code Bestiary.isHostType}): the "N" in "X of N host types scanned". */
    public static int hostTypeCount() {
        return Bestiary.hostTypes().size();
    }

    /** The team's highest scanned host stage per entity type id, as the player's bestiary shows it. */
    public static Map<ResourceLocation, Integer> teamProgress(Player player) {
        return Bestiary.team(player).highestStages();
    }

    /** {@link #teamProgress(Player)} for the player with UUID {@code player}, online or not. Server only. */
    public static Map<ResourceLocation, Integer> teamProgress(MinecraftServer server, UUID player) {
        return Bestiary.team(server, player).highestStages();
    }

    /** The highest host stage the player's team has scanned for the type; 0 if no member has scanned it. */
    public static int teamHighestStage(Player player, ResourceLocation type) {
        return Bestiary.team(player).highestStage(type);
    }

    /** The team total: how many listed host types the player's team has scanned (the "X" of the bestiary header). */
    public static int teamHostTypesScanned(Player player) {
        return Bestiary.hostTypesScanned(Bestiary.team(player));
    }

    /** {@link #teamHostTypesScanned(Player)} for the player with UUID {@code player}, online or not. Server only. */
    public static int teamHostTypesScanned(MinecraftServer server, UUID player) {
        return Bestiary.hostTypesScanned(Bestiary.team(server, player));
    }

    /** How many listed host types the player has scanned themselves (their own record, without teammates). */
    public static int ownHostTypesScanned(Player player) {
        return Bestiary.hostTypesScanned(Bestiary.get(player));
    }
}
