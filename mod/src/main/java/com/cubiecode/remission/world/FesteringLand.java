package com.cubiecode.remission.world;

import com.cubiecode.remission.Remission;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Runs one {@link SpreadEngine} per server level and owns the nidus index entry points. */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class FesteringLand {
    private static final Map<ServerLevel, SpreadEngine> ENGINES = new IdentityHashMap<>();

    private FesteringLand() {
    }

    /** The engine that runs on {@code level}'s ticks. */
    public static SpreadEngine engine(ServerLevel level) {
        synchronized (ENGINES) {
            return ENGINES.computeIfAbsent(level, l -> newEngine(l, SpreadSettings::fromConfig));
        }
    }

    /** A separate engine over {@code level}'s chunks and index, driven by the caller (tests and measurements). */
    public static SpreadEngine newEngine(ServerLevel level, Supplier<SpreadSettings> settings) {
        return new SpreadEngine(new ServerLand(level), settings);
    }

    /** Indexes a nidus placed in or loaded into {@code level}; no-op if already indexed. */
    public static void nidusPresent(ServerLevel level, BlockPos pos) {
        NidusIndex.get(level).add(pos, level.getGameTime());
    }

    /** Drops a removed nidus from the index. Its land keeps its level; only further spread from it stops. */
    public static void nidusRemoved(ServerLevel level, BlockPos pos) {
        if (NidusIndex.get(level).remove(pos)) {
            SpreadHooks.nidusDestroyed(level, pos);
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            engine(level).tick(level.getGameTime());
        }
    }

    // Load fires before the chunk is fully in the level, so the chunk is only queued here and caught up on a later tick.
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            engine(level).chunkLoaded(chunk.getPos().toLong());
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            synchronized (ENGINES) {
                ENGINES.remove(level);
            }
        }
    }
}
