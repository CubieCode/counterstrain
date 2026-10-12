package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.behaviour.AppliedHostGoals;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.research.Bestiary;
import com.cubiecode.remission.research.BestiaryProgress;
import com.cubiecode.remission.world.ChunkCorruption;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Data attachment types (planned: player viral load). */
public final class RemissionAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Remission.MOD_ID);

    /**
     * Per-entity Strain state. Saved with the entity (never at level 0), not copied on death or conversion, and synced
     * through NeoForge's attachment sync to tracking players and the holder itself.
     */
    public static final Supplier<AttachmentType<CorruptionState>> CORRUPTION = ATTACHMENT_TYPES.register("corruption",
            () -> AttachmentType.builder(() -> CorruptionState.NONE)
                    .serialize(CorruptionState.CODEC, state -> state.level() > 0)
                    .sync(CorruptionState.STREAM_CODEC)
                    .build());

    /**
     * The goals a host mob lost and gained (P3.1), so cleansing can restore them in-session. Never saved, synced or
     * copied: goals are rebuilt when a mob loads and host goals are re-applied on join.
     */
    public static final Supplier<AttachmentType<AppliedHostGoals>> HOST_GOALS = ATTACHMENT_TYPES.register("host_goals",
            () -> AttachmentType.builder(() -> AppliedHostGoals.EMPTY).build());

    /**
     * Per-chunk Strain level of the land and the game time it last changed (P4.1). Saved with the chunk (never at level
     * 0) and server-only: nothing on the client reads it yet.
     */
    public static final Supplier<AttachmentType<ChunkCorruption>> CHUNK_CORRUPTION = ATTACHMENT_TYPES.register("chunk_corruption",
            () -> AttachmentType.builder(() -> ChunkCorruption.NONE)
                    .serialize(ChunkCorruption.CODEC, state -> state.level() > 0)
                    .build());

    /**
     * Per-player bestiary progress (P6.2): highest host stage scanned per host type. Saved with the player (never empty)
     * and copied on death. Through NeoForge's attachment sync, updates go to the owning player only; the initial sync when
     * a client starts tracking the player ignores the predicate, so tracking clients also get the current map (harmless:
     * the bestiary screen reads only the local player's progress).
     */
    public static final Supplier<AttachmentType<BestiaryProgress>> BESTIARY = ATTACHMENT_TYPES.register("bestiary",
            () -> AttachmentType.builder(() -> BestiaryProgress.EMPTY)
                    .serialize(BestiaryProgress.CODEC, progress -> !progress.isEmpty())
                    .copyOnDeath()
                    .sync(Bestiary::syncsTo, BestiaryProgress.STREAM_CODEC)
                    .build());

    /**
     * The highest festering stage whose block transformations (and, at the last stage, biome flip) have been applied to a
     * chunk (P4.2). Saved with the chunk (never at 0) and server-only, so a stage interrupted by an unload or a restart
     * is finished when the chunk loads again.
     */
    public static final Supplier<AttachmentType<Integer>> FESTERED_STAGE = ATTACHMENT_TYPES.register("festered_stage",
            () -> AttachmentType.builder(() -> 0)
                    .serialize(Codec.intRange(0, Integer.MAX_VALUE), stage -> stage > 0)
                    .build());

    /**
     * A mob's festering land exposure in ticks (P4.7): rises while it stands in festering land at or above the infection
     * minimum stage and falls by the same amount outside it. Saved with the mob (never at 0) and server-only.
     */
    public static final Supplier<AttachmentType<Integer>> FESTERING_EXPOSURE = ATTACHMENT_TYPES.register("festering_exposure",
            () -> AttachmentType.builder(() -> 0)
                    .serialize(Codec.intRange(0, Integer.MAX_VALUE), ticks -> ticks > 0)
                    .build());

    private RemissionAttachments() {
    }
}
