package com.cubiecode.remission.variant;

import com.mojang.serialization.Codec;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * The variants a player is countered against (P7.5): variant id to the game time it lasts until, from drinking that
 * variant's serum. The {@code remission:variant_counters} player attachment: saved (never empty), server only, not
 * copied on death.
 */
public record VariantCounters(Map<ResourceLocation, Long> until) {
    public static final VariantCounters EMPTY = new VariantCounters(Map.of());
    public static final Codec<VariantCounters> CODEC = Codec.unboundedMap(ResourceLocation.CODEC, Codec.LONG)
            .xmap(VariantCounters::new, VariantCounters::until);

    public VariantCounters {
        until = Map.copyOf(until);
    }

    public boolean isEmpty() {
        return until.isEmpty();
    }

    public boolean countered(ResourceLocation variant, long gameTime) {
        return gameTime < until.getOrDefault(variant, 0L);
    }

    /** These counters with {@code variant} lasting until {@code time}, and the ones expired by {@code now} dropped. */
    public VariantCounters with(ResourceLocation variant, long time, long now) {
        Map<ResourceLocation, Long> next = new TreeMap<>();
        until.forEach((id, end) -> {
            if (end > now) {
                next.put(id, end);
            }
        });
        next.merge(variant, time, Math::max);
        return new VariantCounters(next);
    }
}
