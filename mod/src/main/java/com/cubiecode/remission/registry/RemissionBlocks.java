package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block registrations. Empty until a roadmap item adds blocks. */
public final class RemissionBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Remission.MOD_ID);

    private RemissionBlocks() {
    }
}
