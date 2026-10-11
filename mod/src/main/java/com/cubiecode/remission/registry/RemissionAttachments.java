package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.CorruptionState;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Data attachment types (planned: per-chunk corruption level, player viral load). */
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

    private RemissionAttachments() {
    }
}
