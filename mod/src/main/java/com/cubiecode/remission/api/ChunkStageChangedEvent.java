package com.cubiecode.remission.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * A chunk's festering stage ({@link CorruptionQueries#chunkStageFor}) changed because its Strain level changed. Posted
 * after the new level is stored, once per chunk and change: a catch-up fast-forward posts one event however many passes
 * it applies, so stages may jump by more than one. Level changes within a stage post nothing. Not cancellable.
 */
public class ChunkStageChangedEvent extends FesteringLandEvent {
    private final ChunkPos chunk;
    private final int oldLevel;
    private final int newLevel;
    private final int oldStage;
    private final int newStage;

    public ChunkStageChangedEvent(ServerLevel level, ChunkPos chunk, int oldLevel, int newLevel, int oldStage, int newStage) {
        super(level);
        this.chunk = chunk;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
        this.oldStage = oldStage;
        this.newStage = newStage;
    }

    public ChunkPos getChunk() {
        return chunk;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }

    public int getOldStage() {
        return oldStage;
    }

    public int getNewStage() {
        return newStage;
    }
}
