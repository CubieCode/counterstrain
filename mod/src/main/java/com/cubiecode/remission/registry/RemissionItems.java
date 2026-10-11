package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item and block-item registrations. Empty until a roadmap item adds items. */
public final class RemissionItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Remission.MOD_ID);

    private RemissionItems() {
    }
}
