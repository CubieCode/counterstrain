package com.cubiecode.remission.data;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.loot.AddItemModifier;
import com.cubiecode.remission.loot.IsCorruptedCondition;
import com.cubiecode.remission.registry.RemissionItems;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.DamageSourceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

/** Global loot modifiers: host drops gated on {@code remission:is_corrupted}. */
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
    }
}
