package com.cubiecode.remission.integration.journeymap;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.client.notice.HazardNoticeDisplay;
import com.cubiecode.remission.config.RemissionClientConfig;
import com.cubiecode.remission.network.HazardClientCache;
import com.cubiecode.remission.network.HazardNoticePayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.Util;

/**
 * Temporary JourneyMap waypoints for hazard notices (P5.8): each notice that is not muted adds a session-only waypoint
 * named after the notice at its position, removed after {@code [notices] waypointSeconds} (client config; 0 adds none)
 * and on logout. Waypoints are added and removed on client ticks, through the map layer update.
 */
final class NoticeWaypoints implements JourneyMapIntegration.MapLayer, HazardClientCache.Listener {
    /** The Strain's violet, as the stage 4 overlay. */
    private static final int COLOUR = 0x8E3FD0;
    /** Notices waiting for the next update; the oldest are dropped past this (JourneyMap never attached its API). */
    static final int MAX_QUEUED = 16;

    private record Live(Waypoint waypoint, long expiresAt) {
    }

    private final List<HazardNoticePayload> queued = new ArrayList<>();
    private final List<Live> live = new ArrayList<>();
    private boolean clear;

    @Override
    public void notice(HazardNoticePayload notice) {
        if (!HazardNoticeDisplay.muted(notice.hazard()) && RemissionClientConfig.NOTICES_WAYPOINT_SECONDS.get() > 0) {
            queued.add(notice);
            if (queued.size() > MAX_QUEUED) {
                queued.removeFirst();
            }
        }
    }

    @Override
    public void cleared() {
        queued.clear();
        clear = true;
    }

    @Override
    public void update(IClientAPI api) {
        if (clear) {
            live.forEach(entry -> api.removeWaypoint(Remission.MOD_ID, entry.waypoint()));
            live.clear();
            clear = false;
        }
        long now = Util.getMillis();
        for (HazardNoticePayload notice : queued) {
            Waypoint waypoint = WaypointFactory.createClientWaypoint(Remission.MOD_ID, notice.pos(),
                    HazardNoticeDisplay.message(notice, null).getString(), notice.dimension(), false);
            waypoint.setColor(COLOUR);
            api.addWaypoint(Remission.MOD_ID, waypoint);
            live.add(new Live(waypoint, now + RemissionClientConfig.NOTICES_WAYPOINT_SECONDS.get() * 1000L));
            Remission.LOGGER.debug("Added hazard notice waypoint {} at {}", notice.hazard(), notice.pos());
        }
        queued.clear();
        for (Iterator<Live> it = live.iterator(); it.hasNext(); ) {
            Live entry = it.next();
            if (now >= entry.expiresAt()) {
                api.removeWaypoint(Remission.MOD_ID, entry.waypoint());
                it.remove();
                Remission.LOGGER.debug("Removed expired hazard notice waypoint at {}", entry.waypoint().getBlockPos());
            }
        }
    }
}
