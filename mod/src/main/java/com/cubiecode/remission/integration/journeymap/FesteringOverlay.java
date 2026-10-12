package com.cubiecode.remission.integration.journeymap;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.network.FesteringMapCache;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.model.ShapeProperties;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Festering chunks and remission zones on JourneyMap's full-screen map, minimap and web map (P8.2), from
 * {@link FesteringMapCache}: one translucent polygon layer per festering stage, coloured from pale ochre (stage 1) to the
 * Strain's violet (stage 4), and one in pale cyan for chunks in remission. Chunks are grouped by kind (stage or remission)
 * and by JourneyMap region (32 by 32 chunks); a change re-outlines only its groups, at most {@link #GROUPS_PER_TICK} per
 * client tick.
 */
final class FesteringOverlay implements JourneyMapIntegration.MapLayer, FesteringMapCache.Listener {
    static final int REGION_SHIFT = 5;
    static final int GROUPS_PER_TICK = 4;
    /** Polygon height: JourneyMap's polygons need one, the outlines are flat. */
    static final int Y = 64;
    static final String GROUP_NAME = "Remission";
    static final int DISPLAY_ORDER = 1000;
    /** RGB per stage (index 0 unused): pale ochre, amber, crimson, Strain violet. */
    static final int[] COLOURS = {0, 0xD8C25A, 0xE0892E, 0xC0392B, 0x7B2D9B};
    static final float[] FILL_OPACITY = {0F, 0.25F, 0.32F, 0.40F, 0.48F};
    /** The kind of chunks in remission (festering kinds are their stage, 1 and up). */
    static final int REMISSION = -1;
    static final int REMISSION_COLOUR = 0x59D8E6;
    static final float REMISSION_FILL_OPACITY = 0.35F;

    private record Group(ResourceKey<Level> dimension, int kind, int regionX, int regionZ) {
        static Group of(ResourceKey<Level> dimension, int kind, long chunk) {
            return new Group(dimension, kind, ChunkPos.getX(chunk) >> REGION_SHIFT, ChunkPos.getZ(chunk) >> REGION_SHIFT);
        }
    }

    private final Map<Group, LongSet> members = new HashMap<>();
    private final Set<Group> dirty = new LinkedHashSet<>();
    private final Map<Group, List<PolygonOverlay>> shown = new HashMap<>();
    private boolean warned;

    FesteringOverlay() {
        FesteringMapCache.addListener(this);
    }

    /** The overlay kind of a chunk state: {@link #REMISSION}, its festering stage, or 0 for none. */
    static int kind(int state) {
        return FesteringMapCache.inRemission(state) ? REMISSION : FesteringMapCache.stageOf(state);
    }

    @Override
    public void changed(ResourceKey<Level> dimension, long chunk, int oldState, int newState) {
        int oldKind = kind(oldState);
        int newKind = kind(newState);
        if (oldKind != 0) {
            Group group = Group.of(dimension, oldKind, chunk);
            LongSet chunks = members.get(group);
            if (chunks != null) {
                chunks.remove(chunk);
            }
            dirty.add(group);
        }
        if (newKind != 0) {
            Group group = Group.of(dimension, newKind, chunk);
            members.computeIfAbsent(group, g -> new LongOpenHashSet()).add(chunk);
            dirty.add(group);
        }
    }

    @Override
    public void cleared() {
        members.clear();
        dirty.addAll(shown.keySet());
    }

    @Override
    public void update(IClientAPI api) {
        Iterator<Group> it = dirty.iterator();
        for (int done = 0; done < GROUPS_PER_TICK && it.hasNext(); done++) {
            Group group = it.next();
            it.remove();
            rebuild(api, group);
        }
    }

    private void rebuild(IClientAPI api, Group group) {
        List<PolygonOverlay> old = shown.remove(group);
        if (old != null) {
            old.forEach(api::remove);
        }
        LongSet chunks = members.get(group);
        if (chunks == null || chunks.isEmpty()) {
            members.remove(group);
            return;
        }
        boolean remission = group.kind() == REMISSION;
        String title = remission ? I18n.get("map.remission.remission_zone") : I18n.get("map.remission.festering", group.kind());
        ShapeProperties shape = remission ? shape(REMISSION_COLOUR, REMISSION_FILL_OPACITY) : stageShape(group.kind());
        int order = DISPLAY_ORDER + (remission ? 0 : group.kind());
        List<PolygonOverlay> overlays = new ArrayList<>();
        for (MapPolygonWithHoles polygon : ChunkOutlines.of(chunks, Y)) {
            PolygonOverlay overlay = new PolygonOverlay(Remission.MOD_ID, group.dimension(), shape, polygon);
            overlay.setOverlayGroupName(GROUP_NAME).setTitle(title).setDisplayOrder(order);
            try {
                api.show(overlay);
                overlays.add(overlay);
            } catch (Exception e) {
                if (!warned) {
                    warned = true;
                    Remission.LOGGER.warn("JourneyMap refused a festering overlay; further failures are not logged", e);
                }
            }
        }
        shown.put(group, overlays);
    }

    /** Stages past the last colour (a config with more thresholds) share the last colour. */
    private static ShapeProperties stageShape(int stage) {
        int index = Math.min(stage, COLOURS.length - 1);
        return shape(COLOURS[index], FILL_OPACITY[index]);
    }

    private static ShapeProperties shape(int colour, float fillOpacity) {
        return new ShapeProperties()
                .setFillColor(colour).setFillOpacity(fillOpacity)
                .setStrokeColor(colour).setStrokeOpacity(0.85F).setStrokeWidth(1.5F);
    }
}
