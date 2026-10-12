package com.cubiecode.remission.world;

import com.cubiecode.remission.purification.Purification;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.surge.SurgeController;
import com.cubiecode.remission.surge.Surges;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/** {@link SpreadEngine.Land} over a server level: loaded chunks only, the {@code chunk_corruption} attachment, the saved index. */
final class ServerLand implements SpreadEngine.Land {
    private final ServerLevel level;
    private SurgeController surges;

    ServerLand(ServerLevel level) {
        this.level = level;
    }

    private LevelChunk chunk(long chunk) {
        return level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
    }

    @Override
    public boolean isLoaded(long chunk) {
        return chunk(chunk) != null;
    }

    @Override
    public ChunkCorruption get(long chunk) {
        LevelChunk loaded = chunk(chunk);
        if (loaded == null) {
            return ChunkCorruption.NONE;
        }
        ChunkCorruption state = loaded.getExistingDataOrNull(RemissionAttachments.CHUNK_CORRUPTION);
        if (state != null) {
            return state;
        }
        // A chunk in remission was purified at a known time: catch-up credits only the passes since then (P5.2).
        Long remission = loaded.getExistingDataOrNull(RemissionAttachments.CHUNK_REMISSION);
        return remission == null ? ChunkCorruption.NONE : new ChunkCorruption(0, remission);
    }

    @Override
    public void set(long chunk, ChunkCorruption state) {
        LevelChunk loaded = chunk(chunk);
        if (loaded == null) {
            return;
        }
        int oldLevel = get(chunk).level();
        loaded.setData(RemissionAttachments.CHUNK_CORRUPTION, state);
        if (state.level() > 0) {
            // Festering again: the chunk leaves remission (P5.2).
            loaded.removeData(RemissionAttachments.CHUNK_REMISSION);
        }
        if (oldLevel != state.level()) {
            SpreadHooks.chunkLevelChanged(level, loaded.getPos(), oldLevel, state.level());
        }
    }

    @Override
    public int spreadAttempt(long from, long to, int amount, long gameTime, boolean catchUp) {
        return SpreadHooks.spreadAttempt(level, from, to, amount, gameTime, catchUp);
    }

    @Override
    public List<Nidus> nidi() {
        return NidusIndex.get(level).nidi();
    }

    @Override
    public boolean validate(Nidus nidus) {
        if (!isLoaded(nidus.chunk()) || level.getBlockState(nidus.pos()).is(RemissionBlocks.NIDUS.get())) {
            return true;
        }
        // The block went away without NidusBlock#onRemove (an external world edit): treat it as destroyed.
        if (NidusIndex.get(level).remove(nidus.pos())) {
            SpreadHooks.nidusDestroyed(level, nidus.pos());
        }
        return false;
    }

    @Override
    public boolean warded(long chunk) {
        return Purification.isWarded(level, chunk);
    }

    @Override
    public int surgeFactor(Nidus nidus) {
        if (surges == null) {
            surges = Surges.controller(level);
        }
        return surges.spreadFactor(nidus);
    }
}
