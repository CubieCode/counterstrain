package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Loot item condition types (planned: {@code remission:is_corrupted}). */
public final class RemissionLootConditions {
    public static final DeferredRegister<LootItemConditionType> LOOT_CONDITION_TYPES =
            DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, Remission.MOD_ID);

    private RemissionLootConditions() {
    }
}
