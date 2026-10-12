package com.cubiecode.remission.registry;

import net.neoforged.bus.api.IEventBus;

/** Attaches every Remission deferred register to the mod event bus. */
public final class RemissionRegistries {
    private RemissionRegistries() {
    }

    public static void register(IEventBus modBus) {
        RemissionBlocks.BLOCKS.register(modBus);
        RemissionItems.ITEMS.register(modBus);
        RemissionFluids.FLUID_TYPES.register(modBus);
        RemissionFluids.FLUIDS.register(modBus);
        RemissionBlockEntities.BLOCK_ENTITY_TYPES.register(modBus);
        RemissionAttachments.ATTACHMENT_TYPES.register(modBus);
        RemissionLootConditions.LOOT_CONDITION_TYPES.register(modBus);
        RemissionLootModifiers.LOOT_MODIFIER_SERIALIZERS.register(modBus);
        RemissionRecipeTypes.RECIPE_TYPES.register(modBus);
        RemissionRecipeTypes.RECIPE_SERIALIZERS.register(modBus);
        RemissionTriggers.TRIGGER_TYPES.register(modBus);
        RemissionDataComponents.DATA_COMPONENTS.register(modBus);
        RemissionStats.CUSTOM_STATS.register(modBus);
        RemissionEntityTypes.ENTITY_TYPES.register(modBus);
    }
}
