package com.cubiecode.remission.notice;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.countermeasure.Countermeasures;
import com.cubiecode.remission.network.HazardStatusPayload;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The bestiary's hazards tab (P5.8), server side: which hazards are active for a player and which countermeasures their
 * team holds, sent as {@link HazardStatusPayload} when the client asks (it opened the tab). Listed hazards are the
 * declared ones ({@code StrainHazards.declare}, once at startup; Remission's built hazards are declared here), every
 * hazard reported through the notice API on the running server (forgotten when it stops) and every hazard the team
 * holds a countermeasure for. A hazard is active when the player
 * hazard query ({@code StrainHazards.applies}) says it applies to the player, so a hazard a pack holds back with
 * {@code StrainHazardEvent} is not listed.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class HazardStatus {
    /**
     * The least time between two answers to one player, in milliseconds (wall clock, so a frozen tick rate does not stop
     * answers); requests in between are ignored.
     */
    static final long REQUEST_MILLIS = 250;

    /** Declared at startup ({@code StrainHazards.declare}): kept for the whole game session. */
    private static final Set<ResourceLocation> DECLARED = Collections.synchronizedSet(new TreeSet<>(Set.of(
            StrainHazards.CONTACT)));
    /** Reported through the notice API on the running server; cleared when it stops, so worlds do not share them. */
    private static final Set<ResourceLocation> REPORTED = Collections.synchronizedSet(new TreeSet<>());
    private static final Map<UUID, Long> ANSWERED = new HashMap<>();

    private HazardStatus() {
    }

    public static void declare(ResourceLocation hazard) {
        DECLARED.add(hazard);
    }

    /** A hazard was reported on the running server (from the notice tracker). */
    public static void reported(ResourceLocation hazard) {
        REPORTED.add(hazard);
    }

    /** Declared hazards and those reported on the running server, sorted. */
    public static Set<ResourceLocation> declared() {
        Set<ResourceLocation> all = new TreeSet<>();
        synchronized (DECLARED) {
            all.addAll(DECLARED);
        }
        synchronized (REPORTED) {
            all.addAll(REPORTED);
        }
        return Collections.unmodifiableSet(all);
    }

    /** The player's status: active hazards (not held, not held back) and the team's countermeasures, each sorted. */
    public static HazardStatusPayload of(ServerPlayer player) {
        Set<ResourceLocation> held = Countermeasures.teamCountermeasures(player.server, player.getUUID());
        held.addAll(Countermeasures.get(player).hazards());
        Set<ResourceLocation> known = new TreeSet<>(declared());
        known.addAll(held);
        List<ResourceLocation> active = known.stream()
                .filter(hazard -> !held.contains(hazard) && StrainHazards.applies(player, hazard))
                .toList();
        return new HazardStatusPayload(active, List.copyOf(held));
    }

    /** The client asked for its status (from {@code HazardStatusRequestPayload}). Server thread. */
    public static void request(ServerPlayer player) {
        long now = Util.getMillis();
        Long last = ANSWERED.get(player.getUUID());
        if (last != null && now - last < REQUEST_MILLIS) {
            return;
        }
        ANSWERED.put(player.getUUID(), now);
        PacketDistributor.sendToPlayer(player, of(player));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ANSWERED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        ANSWERED.clear();
        REPORTED.clear();
    }
}
