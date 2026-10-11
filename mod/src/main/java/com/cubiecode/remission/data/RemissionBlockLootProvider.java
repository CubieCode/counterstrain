package com.cubiecode.remission.data;

import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.BonusLevelTableCondition;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class RemissionBlockLootProvider extends BlockLootSubProvider {
    // Vanilla's stick chances for leaves, by fortune level.
    private static final float[] LEAVES_STICK_CHANCES = {0.02F, 0.022222223F, 0.025F, 0.033333335F, 0.1F};

    public RemissionBlockLootProvider(HolderLookup.Provider lookup) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), lookup);
    }

    @Override
    protected void generate() {
        // A broken nidus drops its core (the sample item), never itself, so a nidus cannot be carried and replanted.
        add(RemissionBlocks.NIDUS.get(), createSingleItemTable(RemissionItems.NIDUS_CORE.get()));
        // Festering stages (P4.2): grass drops dirt and stone drops itself, as their sources would without silk touch;
        // leaves drop sticks, or themselves with shears or silk touch. Festering grass is in #minecraft:dirt, so the
        // festered soil sample modifier (P7.1) also applies to it.
        add(RemissionBlocks.FESTERING_GRASS.get(), block -> createSingleItemTableWithSilkTouch(block, Blocks.DIRT));
        dropSelf(RemissionBlocks.FESTERING_LOG.get());
        add(RemissionBlocks.FESTERING_LEAVES.get(), block -> createSilkTouchOrShearsDispatchTable(block,
                ((LootPoolSingletonContainer.Builder<?>) applyExplosionDecay(block, LootItem.lootTableItem(Items.STICK)
                        .apply(SetItemCountFunction.setCount(UniformGenerator.between(1.0F, 2.0F)))))
                        .when(BonusLevelTableCondition.bonusLevelFlatChance(
                                registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE),
                                LEAVES_STICK_CHANCES))));
        dropSelf(RemissionBlocks.FESTERED_STONE.get());
    }

    // Every Remission block must have a loot table; datagen fails if one is missing.
    @Override
    protected Iterable<Block> getKnownBlocks() {
        return RemissionBlocks.BLOCKS.getEntries().stream().<Block>map(DeferredHolder::get).toList();
    }
}
