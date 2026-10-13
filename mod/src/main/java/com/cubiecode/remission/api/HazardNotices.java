package com.cubiecode.remission.api;

import com.cubiecode.remission.notice.HazardNoticeTracker;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Hazard notices (P5.8): every hazard is visible. A hazard system that strikes a block (fouling, rot, a containment
 * breach) reports it here, and Remission tells the placer's online teammates (the team query, {@link TeamQueries}),
 * the placer included, where it struck: in chat, in the bestiary's hazards tab and, with JourneyMap, as a temporary
 * waypoint. Reports are grouped per player, hazard, dimension and area and rate-limited, so many strikes in a short
 * time read as one notice ("Rot, 3 times near 120, 64, -340"). Each player can mute a hazard's notices in the client
 * config.
 *
 * <p>Report a strike after it happened, never to ask whether it may happen ({@link StrainHazards} answers that). A
 * reported hazard is also listed in the hazards tab until the server stops, as if {@link StrainHazards#declare declared}.
 */
public final class HazardNotices {
    private HazardNotices() {
    }

    /**
     * Reports that {@code hazard} struck the block at {@code pos} in {@code level}, placed by {@code placer}
     * ({@code null} when unknown: the nearest player within {@code Countermeasures.NEARBY_RADIUS} blocks stands in, as
     * for the block hazard query, and nobody is told when there is none). Server thread.
     */
    public static void report(ServerLevel level, ResourceLocation hazard, BlockPos pos, @Nullable UUID placer) {
        HazardNoticeTracker.report(level, hazard, pos, placer);
    }
}
