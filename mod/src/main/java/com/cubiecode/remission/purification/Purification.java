package com.cubiecode.remission.purification;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.api.ChunkEnteredRemissionEvent;
import com.cubiecode.remission.api.CorruptionQueries;
import com.cubiecode.remission.api.PurificationCause;
import com.cubiecode.remission.config.RemissionCommonConfig;
import com.cubiecode.remission.quest.QuestProgress;
import com.cubiecode.remission.registry.RemissionAttachments;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.variant.VariantEffects;
import com.cubiecode.remission.world.FesteringLand;
import com.cubiecode.remission.world.FesteringStages;
import com.cubiecode.remission.world.FesteringVeins;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Purification (P5.2): quarantine pylon wards, lowering chunk Strain levels, and remission. A chunk purified from a
 * positive Strain level down to 0 <em>enters remission</em>: it is marked with the {@code chunk_remission} attachment,
 * queued for a full cleanse, and {@link ChunkEnteredRemissionEvent} is posted; it leaves remission when the Strain
 * raises its level again. Runs one {@link PurificationEngine} per server level; server side, loaded chunks only.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Purification {
    /** Block layers cleansed (or sections or queued chunks skipped) per tick and level. */
    public static final int LAYER_BUDGET = 8;

    private static final Map<ServerLevel, PurificationEngine> ENGINES = new IdentityHashMap<>();

    private Purification() {
    }

    /** The engine that runs on {@code level}'s ticks with the configured settings. */
    public static PurificationEngine engine(ServerLevel level) {
        synchronized (ENGINES) {
            return ENGINES.computeIfAbsent(level, l -> new PurificationEngine(l, PurificationSettings::fromConfig));
        }
    }

    /** Whether a quarantine pylon wards {@code chunk} (a {@link ChunkPos#toLong()} key) with the configured radius. */
    public static boolean isWarded(ServerLevel level, long chunk) {
        return PylonIndex.get(level).isWarded(chunk, RemissionCommonConfig.PURIFICATION_PYLON_RADIUS.get());
    }

    public static boolean isWarded(ServerLevel level, ChunkPos chunk) {
        return isWarded(level, chunk.toLong());
    }

    /**
     * Lowers a loaded chunk's Strain level by {@code amount} (not below 0), lowers the festering stage its land is
     * recorded at to the new stage (so the stage work festers it again if the Strain returns), and, when it reaches 0,
     * puts it into remission. Returns whether the level changed; a clean chunk is left alone.
     */
    public static boolean lowerLevel(ServerLevel level, LevelChunk chunk, int amount, PurificationCause cause,
                                     @Nullable ServerPlayer player) {
        int old = CorruptionQueries.chunkLevel(level, chunk.getPos());
        if (old <= 0 || amount <= 0) {
            return false;
        }
        // A variant that resists this cause slows it in its nidus's land; every step builds pressure (P7.5).
        amount = VariantEffects.purification(level, chunk.getPos(), amount, cause, old);
        if (amount <= 0) {
            return false;
        }
        int next = Math.max(0, old - amount);
        FesteringLand.setChunkLevel(level, chunk, next);
        int stage = CorruptionQueries.chunkStageFor(next);
        if (FesteringStages.festeredStage(chunk) > stage) {
            if (stage == 0) {
                chunk.removeData(RemissionAttachments.FESTERED_STAGE);
            } else {
                chunk.setData(RemissionAttachments.FESTERED_STAGE, stage);
            }
        }
        if (next == 0) {
            enterRemission(level, chunk, cause, player);
        }
        return true;
    }

    private static void enterRemission(ServerLevel level, LevelChunk chunk, PurificationCause cause, @Nullable ServerPlayer player) {
        chunk.setData(RemissionAttachments.CHUNK_REMISSION, level.getGameTime());
        engine(level).queue(chunk.getPos(), cause);
        NeoForge.EVENT_BUS.post(new ChunkEnteredRemissionEvent(level, chunk.getPos(), cause, player));
        QuestProgress.chunkEnteredRemission(level, chunk.getPos());
    }

    /**
     * A handheld purifier used on the block at {@code pos}: cleanses the festered blocks and veins within
     * {@code purifierRadius} and lowers the block's chunk by {@code purifierStep}. Returns the blocks changed, plus one if
     * the chunk's level changed (0 means the use did nothing).
     */
    public static int purify(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player, PurificationSettings settings) {
        int changed = Cleansing.cleanseBox(level, pos, settings.purifierRadius(), PurificationCause.PURIFIER);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && lowerLevel(level, chunk, settings.purifierStep(), PurificationCause.PURIFIER, player)) {
            changed++;
        }
        return changed;
    }

    /**
     * One mendroot cleanse at {@code pos}: the festered blocks and veins within {@code mendrootRadius}, and its chunk
     * lowered by {@code mendrootStep}. Returns the blocks changed, plus one if the chunk's level changed.
     */
    public static int mendroot(ServerLevel level, BlockPos pos, PurificationSettings settings) {
        int changed = Cleansing.cleanseBox(level, pos, settings.mendrootRadius(), PurificationCause.MENDROOT);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && lowerLevel(level, chunk, settings.mendrootStep(), PurificationCause.MENDROOT, null)) {
            changed++;
        }
        return changed;
    }

    /** Indexes a pylon placed in or loaded into {@code level}; a new one queues the loaded chunks of its ward for a cleanse. */
    public static void pylonPresent(ServerLevel level, BlockPos pos) {
        if (!PylonIndex.get(level).add(pos)) {
            return;
        }
        PurificationEngine engine = engine(level);
        for (ChunkPos chunk : PylonIndex.ward(pos, RemissionCommonConfig.PURIFICATION_PYLON_RADIUS.get())) {
            if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null) {
                engine.queue(chunk, PurificationCause.PYLON);
            }
        }
    }

    /**
     * Drops a removed pylon from the index. Loaded chunks it no longer wards are queued for the festering stage work and
     * vein growth, which catch them up to their current stage.
     */
    public static void pylonRemoved(ServerLevel level, BlockPos pos) {
        if (!PylonIndex.get(level).remove(pos)) {
            return;
        }
        for (ChunkPos chunk : PylonIndex.ward(pos, RemissionCommonConfig.PURIFICATION_PYLON_RADIUS.get())) {
            if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null && !isWarded(level, chunk)) {
                FesteringStages.queue(level, chunk);
                FesteringVeins.growth(level).queue(chunk);
            }
        }
    }

    /** Drops an indexed pylon whose loaded block is gone (an external world edit); returns whether it is still there. */
    static boolean validate(ServerLevel level, BlockPos pylon) {
        if (level.getChunkSource().getChunkNow(pylon.getX() >> 4, pylon.getZ() >> 4) == null
                || level.getBlockState(pylon).is(RemissionBlocks.QUARANTINE_PYLON.get())) {
            return true;
        }
        pylonRemoved(level, pylon);
        return false;
    }

    // Frozen ticks (/tick freeze, harness scenes) purify nothing; stepped ticks do.
    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.tickRateManager().runsNormally()) {
            PurificationEngine engine = engine(level);
            engine.pass(level.getGameTime());
            engine.tick(LAYER_BUDGET);
        }
    }

    // Load fires before the chunk is in the level; the engine parks it until it is. Chunks in remission are checked
    // again too, since a cleanse cut short by an unload is not saved (a clean chunk costs one unit per section).
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            if (isWarded(level, chunk.getPos())) {
                engine(level).queue(chunk.getPos(), PurificationCause.PYLON);
            } else if (chunk.hasData(RemissionAttachments.CHUNK_REMISSION)) {
                engine(level).queue(chunk.getPos(), PurificationCause.OTHER);
            }
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            engine(level).unloaded(event.getChunk().getPos());
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
