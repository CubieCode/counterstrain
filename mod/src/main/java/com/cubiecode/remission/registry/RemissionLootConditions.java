package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.loot.IsCorruptedCondition;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Loot item condition types. */
public final class RemissionLootConditions {
    public static final DeferredRegister<LootItemConditionType> LOOT_CONDITION_TYPES =
            DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, Remission.MOD_ID);

    public static final Supplier<LootItemConditionType> IS_CORRUPTED = LOOT_CONDITION_TYPES.register("is_corrupted",
            () -> new LootItemConditionType(IsCorruptedCondition.CODEC));

    private RemissionLootConditions() {
    }
}
