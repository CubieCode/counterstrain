package com.cubiecode.remission.loot;

import com.cubiecode.remission.registry.RemissionLootModifiers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/** Global loot modifier {@code remission:add_item}: adds a copy of {@code item} to the loot when its conditions pass. */
public final class AddItemModifier extends LootModifier {
    public static final MapCodec<AddItemModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> codecStart(instance)
            .and(ItemStack.CODEC.fieldOf("item").forGetter(modifier -> modifier.item))
            .apply(instance, AddItemModifier::new));

    private final ItemStack item;

    public AddItemModifier(LootItemCondition[] conditions, ItemStack item) {
        super(conditions);
        this.item = item;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        generatedLoot.add(item.copy());
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return RemissionLootModifiers.ADD_ITEM.get();
    }
}
