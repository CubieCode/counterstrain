package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Data attachment types (planned: per-entity corruption state, per-chunk corruption level, player exposure). */
public final class RemissionAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Remission.MOD_ID);

    private RemissionAttachments() {
    }
}
