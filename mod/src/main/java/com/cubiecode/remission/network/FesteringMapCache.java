package com.cubiecode.remission.network;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The client's copy of the chunk states the server sent ({@code remission:festering_map}, P8.2), per dimension, for map
 * overlays. A state packs a chunk's festering stage and whether it is in remission ({@link FesteringMapPayload#state});
 * 0 is clean land never purified. It holds every chunk seen festering or in remission this session, loaded or not, so a
 * map keeps showing land the player has left; nothing is saved, and logging out clears it. It records nothing until a map
 * integration {@linkplain #enable() enables} it. Main thread only. It lives in common code with the payload that fills
 * it and uses no client-only classes; only the client fills it.
 */
public final class FesteringMapCache {
    /** Told about each stored change; a state of 0 means the chunk is clean and left the cache. */
    public interface Listener {
        void changed(ResourceKey<Level> dimension, long chunk, int oldState, int newState);

        /** Every dimension was emptied (logout). */
        void cleared();
    }

    private static final Map<ResourceKey<Level>, Long2ByteOpenHashMap> STATES = new HashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean enabled;

    private FesteringMapCache() {
    }

    /** The festering stage of a chunk state. */
    public static int stageOf(int state) {
        return state & FesteringMapPayload.STAGE_MASK;
    }

    /** Whether a chunk state is in remission. */
    public static boolean inRemission(int state) {
        return (state & FesteringMapPayload.REMISSION) != 0;
    }

    /** Starts recording; called by a map integration once its mod is known to be loaded. */
    public static void enable() {
        enabled = true;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Stops recording and forgets every dimension without telling listeners (tests restore the off state with it). */
    public static void disable() {
        enabled = false;
        STATES.clear();
    }

    /** The state last sent for {@code chunk} in {@code dimension}; 0 when clean or never sent. */
    public static int state(ResourceKey<Level> dimension, long chunk) {
        Long2ByteOpenHashMap states = STATES.get(dimension);
        return states == null ? 0 : states.get(chunk) & 0xFF;
    }

    /** A read-only view of the chunks known festering or in remission in {@code dimension}, by state. */
    public static Long2ByteMap known(ResourceKey<Level> dimension) {
        Long2ByteOpenHashMap states = STATES.get(dimension);
        return states == null ? Long2ByteMaps.EMPTY_MAP : Long2ByteMaps.unmodifiable(states);
    }

    /** Stores the states of one payload and tells listeners about each chunk whose state changed. */
    public static void apply(ResourceKey<Level> dimension, Long2ByteMap update) {
        if (!enabled) {
            return;
        }
        Long2ByteOpenHashMap states = STATES.computeIfAbsent(dimension, d -> new Long2ByteOpenHashMap());
        for (Long2ByteMap.Entry entry : update.long2ByteEntrySet()) {
            long chunk = entry.getLongKey();
            byte state = entry.getByteValue();
            byte old = state != 0 ? states.put(chunk, state) : states.remove(chunk);
            if (old != state) {
                LISTENERS.forEach(listener -> listener.changed(dimension, chunk, old & 0xFF, state & 0xFF));
            }
        }
        if (states.isEmpty()) {
            STATES.remove(dimension);
        }
    }

    /** Forgets every dimension (on logout). */
    public static void clear() {
        STATES.clear();
        LISTENERS.forEach(Listener::cleared);
    }
}
