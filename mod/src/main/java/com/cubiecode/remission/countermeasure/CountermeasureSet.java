package com.cubiecode.remission.countermeasure;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * The Strain hazards one player holds a countermeasure for (P5.7), stored as their {@code remission:countermeasures}
 * attachment. Hazard IDs are plain ids with no registry, so a since-removed integration's countermeasures survive in
 * the save. Kept sorted, so saves and syncs are stable.
 */
public record CountermeasureSet(Set<ResourceLocation> hazards) {
    public static final CountermeasureSet EMPTY = new CountermeasureSet(Set.of());

    /** {@code ["<hazard id>", ...]}. */
    public static final Codec<CountermeasureSet> CODEC = ResourceLocation.CODEC.listOf()
            .xmap(list -> new CountermeasureSet(Set.copyOf(list)), set -> set.hazards().stream().toList());

    public static final StreamCodec<ByteBuf, CountermeasureSet> STREAM_CODEC = ResourceLocation.STREAM_CODEC
            .apply(ByteBufCodecs.list())
            .map(list -> new CountermeasureSet(Set.copyOf(list)), set -> set.hazards().stream().toList());

    public CountermeasureSet {
        hazards = Collections.unmodifiableSortedSet(new TreeSet<>(hazards));
    }

    public boolean isEmpty() {
        return hazards.isEmpty();
    }

    public boolean holds(ResourceLocation hazard) {
        return hazards.contains(hazard);
    }

    /** This set with {@code hazard} added; the same instance if it is already held. */
    public CountermeasureSet with(ResourceLocation hazard) {
        if (holds(hazard)) {
            return this;
        }
        Set<ResourceLocation> next = new TreeSet<>(hazards);
        next.add(hazard);
        return new CountermeasureSet(next);
    }

    /** This set with {@code hazard} removed; the same instance if it is not held. */
    public CountermeasureSet without(ResourceLocation hazard) {
        if (!holds(hazard)) {
            return this;
        }
        Set<ResourceLocation> next = new TreeSet<>(hazards);
        next.remove(hazard);
        return new CountermeasureSet(next);
    }
}
