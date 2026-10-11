package com.cubiecode.remission.world;

import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/** {@link SpreadEngine.Land} over a server level: loaded chunks only, the {@code chunk_corruption} attachment, the saved index. */
final class ServerLand implements SpreadEngine.Land {
    private final ServerLevel level;

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
        return state == null ? ChunkCorruption.NONE : state;
    }

    @Override
    public void set(long chunk, ChunkCorruption state) {
        LevelChunk loaded = chunk(chunk);
        if (loaded == null) {
            return;
        }
        int oldLevel = get(chunk).level();
        loaded.setData(RemissionAttachments.CHUNK_CORRUPTION, state);
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
}
