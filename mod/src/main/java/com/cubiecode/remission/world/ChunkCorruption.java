package com.cubiecode.remission.world;

import com.cubiecode.remission.corruption.CorruptionState;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Strain level of one chunk of land and the game time the spread engine last changed it.
 *
 * @param level              0 (clean) to {@link #MAX_LEVEL}; levels never fall in phase 4 (purification is phase 5)
 * @param lastUpdateGameTime level game time of the last change, used to fast-forward the chunk when it loads again
 */
public record ChunkCorruption(int level, long lastUpdateGameTime) {
    public static final int MAX_LEVEL = CorruptionState.MAX_LEVEL;
    public static final ChunkCorruption NONE = new ChunkCorruption(0, 0L);

    public static final Codec<ChunkCorruption> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, MAX_LEVEL).fieldOf("level").forGetter(ChunkCorruption::level),
            Codec.LONG.fieldOf("last_update_game_time").forGetter(ChunkCorruption::lastUpdateGameTime)
    ).apply(instance, ChunkCorruption::new));
}
