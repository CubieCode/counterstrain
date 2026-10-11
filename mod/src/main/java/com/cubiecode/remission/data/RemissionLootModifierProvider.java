package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.loot.AddItemModifier;
import com.cubiecode.remission.loot.IsCorruptedCondition;
import com.cubiecode.remission.loot.IsFesteringCondition;
import com.cubiecode.remission.registry.RemissionItems;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.DamageSourceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

/** Global loot modifiers: host drops gated on {@code remission:is_corrupted}, ground drops on {@code remission:is_festering}. */
public final class RemissionLootModifierProvider extends GlobalLootModifierProvider {
    public RemissionLootModifierProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup, Remission.MOD_ID);
    }

    @Override
    protected void start() {
        // One host tissue from any host's death loot; the damage source check keeps it to death drops.
        add("host_samples", new AddItemModifier(new LootItemCondition[]{
                new IsCorruptedCondition(LootContext.EntityTarget.THIS, 1),
                new DamageSourceCondition(Optional.empty())
        }, new ItemStack(RemissionItems.HOST_TISSUE.get())));

        // A chance of festered soil from soil broken in a chunk at festering stage 2 or higher.
        add("festered_soil_samples", new AddItemModifier(new LootItemCondition[]{
                new IsFesteringCondition(2, Optional.of(registries.lookupOrThrow(Registries.BLOCK)
                        .getOrThrow(IsFesteringCondition.FESTERED_SOIL_SOURCES))),
                LootItemRandomChanceCondition.randomChance(0.25F).build()
        }, new ItemStack(RemissionItems.FESTERED_SOIL.get())));
    }
}
