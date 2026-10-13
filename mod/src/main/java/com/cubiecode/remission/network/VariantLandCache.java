package com.cubiecode.remission.network;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/**
 * The client's variants of loaded chunks in its current dimension ({@code remission:variant_land}, P7.5), with each
 * variant's tint, for drawing festering land. Written on the main thread; read from chunk meshing threads, hence the
 * concurrent maps. The client forgets a chunk when it unloads it and everything on a dimension change or logout. Lives in
 * common code with the payload that fills it and uses no client-only classes; only the client fills it.
 */
public final class VariantLandCache {
    /** Told about each chunk whose variant or tint changed, and when everything is forgotten. */
    public interface Listener {
        void changed(long chunk);

        void cleared();
    }

    /** {@link #tint} of a chunk without a variant: white, which leaves a tinted face as it is. */
    public static final int NO_TINT = -1;

    private static final Map<Long, ResourceLocation> VARIANTS = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> TINTS = new ConcurrentHashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private VariantLandCache() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** The chunk's variant, if the server sent one. */
    public static Optional<ResourceLocation> variant(long chunk) {
        return Optional.ofNullable(VARIANTS.get(chunk));
    }

    /** The chunk's tint as {@code 0xFFRRGGBB}, or {@link #NO_TINT}. */
    public static int tint(long chunk) {
        return TINTS.getOrDefault(chunk, NO_TINT);
    }

    /** Stores the variants of one payload; {@code tints} gives a variant's tint (empty for one the client lacks). */
    public static void apply(Map<Long, Optional<ResourceLocation>> chunks, Function<ResourceLocation, OptionalInt> tints) {
        chunks.forEach((chunk, variant) -> {
            ResourceLocation old = variant.isPresent() ? VARIANTS.put(chunk, variant.get()) : VARIANTS.remove(chunk);
            if (variant.isPresent()) {
                OptionalInt tint = tints.apply(variant.get());
                if (tint.isPresent()) {
                    TINTS.put(chunk, 0xFF000000 | tint.getAsInt());
                } else {
                    TINTS.remove(chunk);
                }
            } else {
                TINTS.remove(chunk);
            }
            if (!variant.equals(Optional.ofNullable(old))) {
                LISTENERS.forEach(listener -> listener.changed(chunk));
            }
        });
    }

    /** Forgets a chunk the client unloaded. */
    public static void forget(long chunk) {
        if (VARIANTS.remove(chunk) != null) {
            TINTS.remove(chunk);
        }
    }

    /** Forgets every chunk (dimension change, logout). */
    public static void clear() {
        VARIANTS.clear();
        TINTS.clear();
        LISTENERS.forEach(Listener::cleared);
    }
}
