package com.cubiecode.remission.integration.journeymap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.util.PolygonHelper;
import net.minecraft.world.level.ChunkPos;

/** Outlines of a set of chunks as JourneyMap polygons with holes, one per connected area. */
final class ChunkOutlines {
    private ChunkOutlines() {
    }

    /**
     * The outlines of {@code chunks} (packed {@link ChunkPos} longs) at height {@code y}. Rows of chunks are first merged
     * into runs and runs repeated on following rows into rectangles, so the area union handles a few rectangles rather
     * than one square per chunk.
     */
    static List<MapPolygonWithHoles> of(LongCollection chunks, int y) {
        TreeMap<Integer, IntArrayList> rows = new TreeMap<>();
        for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            rows.computeIfAbsent(ChunkPos.getZ(chunk), z -> new IntArrayList()).add(ChunkPos.getX(chunk));
        }
        Area area = new Area();
        // Open rectangles by their run (first x, last x), with the row they started on.
        Map<Long, Integer> open = new TreeMap<>();
        int previous = Integer.MIN_VALUE;
        for (Map.Entry<Integer, IntArrayList> row : rows.entrySet()) {
            int z = row.getKey();
            List<Long> runs = runs(row.getValue());
            Map<Long, Integer> next = new TreeMap<>();
            for (long run : runs) {
                Integer start = z == previous + 1 ? open.remove(run) : null;
                next.put(run, start == null ? z : start);
            }
            closeAll(area, open, previous);
            open = next;
            previous = z;
        }
        closeAll(area, open, previous);
        return PolygonHelper.createPolygonFromArea(area, y);
    }

    /** Contiguous runs of the row's chunk x values, each packed as {@code first << 32 | last}. */
    private static List<Long> runs(IntArrayList xs) {
        xs.sort(null);
        List<Long> runs = new ArrayList<>();
        int first = xs.getInt(0);
        int last = first;
        for (int i = 1; i < xs.size(); i++) {
            int x = xs.getInt(i);
            if (x != last + 1) {
                runs.add(pack(first, last));
                first = x;
            }
            last = x;
        }
        runs.add(pack(first, last));
        return runs;
    }

    private static long pack(int first, int last) {
        return (long) first << 32 | (last & 0xFFFFFFFFL);
    }

    private static void closeAll(Area area, Map<Long, Integer> open, int lastZ) {
        for (Map.Entry<Long, Integer> rectangle : open.entrySet()) {
            add(area, rectangle.getKey(), rectangle.getValue(), lastZ);
        }
    }

    private static void add(Area area, long run, int firstZ, int lastZ) {
        int firstX = (int) (run >> 32);
        int lastX = (int) run;
        area.add(new Area(new Rectangle(firstX << 4, firstZ << 4, (lastX - firstX + 1) << 4, (lastZ - firstZ + 1) << 4)));
    }
}
