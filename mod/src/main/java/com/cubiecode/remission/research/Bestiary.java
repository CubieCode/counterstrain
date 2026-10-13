package com.cubiecode.remission.research;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.TeamQueries;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.registry.RemissionAttachments;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The bestiary: which entity types are host types, and each player's scan progress ({@link BestiaryProgress}). Host types
 * are derived from the entity type registry, so every installed mod's living mobs widen it. Progress is written on the
 * server when the scanner records a host scan of a host type; updates sync to the owner only (see
 * {@link #syncsTo}). Reads work on either side.
 *
 * <p>Research belongs to the team (P6.4): the bestiary shows {@link #team} progress, the union of the records of the
 * player and every teammate (the team query, {@link TeamQueries}), offline ones included through {@link BestiaryRecords}.
 * Each online player's team progress is kept in the {@link RemissionAttachments#TEAM_BESTIARY} attachment, present only
 * while it differs from their own record, and refreshed when a teammate scans, when they log in or respawn, and on the
 * server tick after a team change (noticed through the team query's invalidation calls). Each player's own record is
 * never changed by a teammate's scan, so a player who leaves a team keeps exactly what they scanned.
 *
 * <p>Methods taking a collection of online players use it instead of the server's player list, so gametests can
 * supply players that never joined.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Bestiary {
    /**
     * Types the bestiary leaves out although they could carry the Strain: ones survival players cannot normally meet as
     * hosts. Remission ships giant, illusioner and armour stand; packs extend or replace it.
     */
    public static final TagKey<EntityType<?>> HIDDEN = TagKey.create(Registries.ENTITY_TYPE, Remission.id("bestiary_hidden"));

    private static volatile boolean pendingRefresh;

    private Bestiary() {
    }

    /**
     * Whether the bestiary lists the type: a living type (one with default attributes, which every living entity type
     * must register), other than the player, and in neither {@code #remission:immune} nor {@link #HIDDEN}. Tags are read
     * on every call, so a reload's tags apply at once. Needs tags bound (any loaded world).
     */
    public static boolean isHostType(EntityType<?> type) {
        return type != EntityType.PLAYER && DefaultAttributes.hasSupplier(type) && !type.is(Corruption.IMMUNE)
                && !type.is(HIDDEN);
    }

    /** Every host type, in registry order. */
    public static List<EntityType<?>> hostTypes() {
        return BuiltInRegistries.ENTITY_TYPE.stream().filter(Bestiary::isHostType).toList();
    }

    /** The player's progress, or {@link BestiaryProgress#EMPTY}. Never creates an attachment. */
    public static BestiaryProgress get(Player player) {
        BestiaryProgress progress = player.getExistingDataOrNull(RemissionAttachments.BESTIARY);
        return progress == null ? BestiaryProgress.EMPTY : progress;
    }

    /**
     * Records a host scan of the type at the stage; keeps the highest stage per type. Types the bestiary does not list
     * ({@link #isHostType}: players, immune, hidden or unregistered types) record nothing. Refreshes the team progress
     * of the player's online teammates. Server only.
     */
    public static void recordScan(Player player, ResourceLocation type, int stage) {
        MinecraftServer server = player.getServer();
        recordScan(player, type, stage, server == null ? List.of(player) : server.getPlayerList().getPlayers());
    }

    /** {@link #recordScan(Player, ResourceLocation, int)} with {@code online} as the online players. */
    public static void recordScan(Player player, ResourceLocation type, int stage, Collection<? extends Player> online) {
        if (player.level().isClientSide()) {
            throw new IllegalStateException("Bestiary progress is server-authoritative; record it on the server");
        }
        if (BuiltInRegistries.ENTITY_TYPE.getOptional(type).filter(Bestiary::isHostType).isEmpty()) {
            return;
        }
        BestiaryProgress old = get(player);
        BestiaryProgress progress = old.withScan(type, stage);
        if (progress == old) {
            return;
        }
        player.setData(RemissionAttachments.BESTIARY, progress);
        if (store(player, progress)) {
            refreshTeam(player, online);
        } else {
            refresh(player);
        }
    }

    /**
     * The progress the player's bestiary shows: the highest stage any member of their team has scanned for each type
     * (their own record when they have no teammates). On the server it is computed from the saved records; on the
     * client it is the synced copy of the local player's.
     */
    public static BestiaryProgress team(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.level().isClientSide()) {
            BestiaryProgress shown = player.getExistingDataOrNull(RemissionAttachments.TEAM_BESTIARY);
            return shown == null ? get(player) : shown;
        }
        return get(player).with(team(server, player.getUUID()));
    }

    /**
     * The team progress of the player with UUID {@code player}, who may be offline: the union of the saved records of
     * that player and every recorded teammate. Server only.
     */
    public static BestiaryProgress team(MinecraftServer server, UUID player) {
        BestiaryProgress progress = BestiaryProgress.EMPTY;
        for (Map.Entry<UUID, BestiaryProgress> entry : BestiaryRecords.get(server).players().entrySet()) {
            if (TeamQueries.areTeammates(server, player, entry.getKey())) {
                progress = progress.with(entry.getValue());
            }
        }
        return progress;
    }

    /** How many listed host types ({@link #isHostType}) the progress holds; types since hidden or removed do not count. */
    public static int hostTypesScanned(BestiaryProgress progress) {
        int count = 0;
        for (ResourceLocation type : progress.highestStages().keySet()) {
            if (BuiltInRegistries.ENTITY_TYPE.getOptional(type).filter(Bestiary::isHostType).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Brings the player's synced team progress up to date: sets {@link RemissionAttachments#TEAM_BESTIARY} when the
     * team's progress differs from their own record, and removes it otherwise. Server only.
     */
    public static void refresh(Player player) {
        if (player.level().isClientSide() || player.getServer() == null) {
            return;
        }
        BestiaryProgress own = get(player);
        BestiaryProgress shown = team(player);
        if (shown.equals(own)) {
            if (player.hasData(RemissionAttachments.TEAM_BESTIARY)) {
                player.removeData(RemissionAttachments.TEAM_BESTIARY);
            }
        } else if (!shown.equals(player.getExistingDataOrNull(RemissionAttachments.TEAM_BESTIARY))) {
            player.setData(RemissionAttachments.TEAM_BESTIARY, shown);
        }
    }

    /** {@link #refresh}es the player and every one of {@code online} who is their teammate. */
    public static void refreshTeam(Player player, Collection<? extends Player> online) {
        refresh(player);
        for (Player other : online) {
            if (other != player && !(other instanceof FakePlayer) && TeamQueries.areTeammates(player, other)) {
                refresh(other);
            }
        }
    }

    /** {@link #refresh}es every one of {@code online}, as after a team change. */
    public static void refreshAll(Collection<? extends Player> online) {
        for (Player player : online) {
            if (!(player instanceof FakePlayer)) {
                refresh(player);
            }
        }
    }

    /**
     * A player joined: records their own progress (players from before P6.4 have no record yet) and refreshes their team
     * progress, and their online teammates' if the record changed.
     */
    public static void logIn(Player player, Collection<? extends Player> online) {
        if (store(player, get(player))) {
            refreshTeam(player, online);
        } else {
            refresh(player);
        }
    }

    /**
     * A player's team changed (called from {@link TeamQueries#invalidate} and {@link TeamQueries#invalidateAll}). Every
     * online player is refreshed on the next server tick: a player who left a team is no longer anyone's teammate, so the
     * former teammates are not known by then.
     */
    public static void teamChanged() {
        pendingRefresh = true;
    }

    /** Mirrors the player's own record into {@link BestiaryRecords}; returns whether it changed. Fake players are not kept. */
    private static boolean store(Player player, BestiaryProgress progress) {
        MinecraftServer server = player.getServer();
        if (server == null || player instanceof FakePlayer) {
            return false;
        }
        return BestiaryRecords.get(server).put(player.getUUID(), progress);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (pendingRefresh) {
            pendingRefresh = false;
            refreshAll(List.copyOf(event.getServer().getPlayerList().getPlayers()));
        }
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            logIn(player, player.server.getPlayerList().getPlayers());
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            refresh(player);
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        pendingRefresh = false;
    }

    /**
     * Attachment sync filter for updates: a change goes to the player it belongs to and nobody else. NeoForge's initial
     * sync when a client starts tracking an entity does not consult it, so clients tracking the player also receive the
     * current map; nothing on the client reads another player's progress. Also the filter for
     * {@link RemissionAttachments#TEAM_BESTIARY}.
     */
    public static boolean syncsTo(IAttachmentHolder holder, Player to) {
        return holder == to;
    }
}
