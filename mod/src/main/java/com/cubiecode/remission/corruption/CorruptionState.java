package com.cubiecode.remission.corruption;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * How far the Strain has taken an entity: {@code level} in {@code [0, MAX_LEVEL]} and the host {@code stage} derived from
 * it when it was set (0 means not a host). The {@code variant} key is reserved for resistant mutations (P9.1).
 */
public record CorruptionState(int level, int stage) {
    public static final int MAX_LEVEL = 100;
    public static final CorruptionState NONE = new CorruptionState(0, 0);

    public static final Codec<CorruptionState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, MAX_LEVEL).fieldOf("level").forGetter(CorruptionState::level),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("stage").forGetter(CorruptionState::stage)
    ).apply(instance, CorruptionState::new));

    public static final StreamCodec<ByteBuf, CorruptionState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CorruptionState::level,
            ByteBufCodecs.VAR_INT, CorruptionState::stage,
            CorruptionState::new);

    public CorruptionState {
        if (level < 0 || level > MAX_LEVEL || stage < 0) {
            throw new IllegalArgumentException("Invalid corruption state: level " + level + ", stage " + stage);
        }
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
