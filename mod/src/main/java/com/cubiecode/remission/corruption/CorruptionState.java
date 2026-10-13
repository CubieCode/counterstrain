package com.cubiecode.remission.corruption;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * How far the Strain has taken an entity: {@code level} in {@code [0, MAX_LEVEL]} and the host {@code stage} derived from
 * it when it was set (0 means not a host), and the {@code variant} the host carries (P7.5): the id of a
 * {@code remission:variant} entry, given when the entity became a host in a variant nidus's land or when a variant
 * emerged around it, kept while it stays a host. Ids stay ids, so a variant a datapack no longer has still loads.
 */
public record CorruptionState(int level, int stage, Optional<ResourceLocation> variant) {
    public static final int MAX_LEVEL = 100;
    public static final CorruptionState NONE = new CorruptionState(0, 0);

    public static final Codec<CorruptionState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, MAX_LEVEL).fieldOf("level").forGetter(CorruptionState::level),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("stage").forGetter(CorruptionState::stage),
            ResourceLocation.CODEC.optionalFieldOf("variant").forGetter(CorruptionState::variant)
    ).apply(instance, CorruptionState::new));

    /** Wire format (attachment sync): level and stage as var-ints, then the optional variant id. */
    public static final StreamCodec<ByteBuf, CorruptionState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CorruptionState::level,
            ByteBufCodecs.VAR_INT, CorruptionState::stage,
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), CorruptionState::variant,
            CorruptionState::new);

    public CorruptionState {
        if (level < 0 || level > MAX_LEVEL || stage < 0) {
            throw new IllegalArgumentException("Invalid corruption state: level " + level + ", stage " + stage);
        }
        if (level == 0 && variant.isPresent()) {
            throw new IllegalArgumentException("Invalid corruption state: variant " + variant.get() + " at level 0");
        }
    }

    /** A state without a variant. */
    public CorruptionState(int level, int stage) {
        this(level, stage, Optional.empty());
    }

    /** This state carrying {@code variant} instead (ignored at level 0, which carries none). */
    public CorruptionState withVariant(Optional<ResourceLocation> variant) {
        return level == 0 || variant.equals(this.variant) ? this : new CorruptionState(level, stage, variant);
    }

    /** A state at {@code level} (clamped), staged by {@code thresholds}. */
    public static CorruptionState of(int level, List<? extends Integer> thresholds) {
        int clamped = Math.clamp(level, 0, MAX_LEVEL);
        return clamped == 0 ? NONE : new CorruptionState(clamped, stageFor(clamped, thresholds));
    }

    /** The number of thresholds {@code level} meets; order does not matter. Level 0 is always stage 0. */
    public static int stageFor(int level, List<? extends Integer> thresholds) {
        if (level <= 0) {
            return 0;
        }
        int stage = 0;
        for (int threshold : thresholds) {
            if (level >= threshold) {
                stage++;
            }
        }
        return stage;
    }

    public boolean isHost() {
        return stage > 0;
    }
}
