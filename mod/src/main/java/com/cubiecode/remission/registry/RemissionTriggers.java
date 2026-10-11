package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Advancement criterion triggers that quest mods gate on (planned list: docs/architecture.md). */
public final class RemissionTriggers {
    public static final DeferredRegister<CriterionTrigger<?>> TRIGGER_TYPES =
            DeferredRegister.create(Registries.TRIGGER_TYPE, Remission.MOD_ID);

    private RemissionTriggers() {
    }
}
