package com.cubiecode.remission.data;

import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionItems;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class RemissionBlockLootProvider extends BlockLootSubProvider {
    public RemissionBlockLootProvider(HolderLookup.Provider lookup) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), lookup);
    }

    @Override
    protected void generate() {
        // A broken nidus drops its core (the sample item), never itself, so a nidus cannot be carried and replanted.
        add(RemissionBlocks.NIDUS.get(), createSingleItemTable(RemissionItems.NIDUS_CORE.get()));
        // Festering stages (P4.2): grass drops dirt and stone drops itself, as their sources would without silk touch.
        // Festering grass is in #minecraft:dirt, so the festered soil sample modifier (P7.1) also applies to it.
        add(RemissionBlocks.FESTERING_GRASS.get(), block -> createSingleItemTableWithSilkTouch(block, Blocks.DIRT));
        // Festering plants (P4.16): only with shears, as themselves; a tall one drops two of its short plant, as vanilla.
        add(RemissionBlocks.FESTERING_SHORT_GRASS.get(), block -> createShearsOnlyDrop(block));
        add(RemissionBlocks.FESTERING_FERN.get(), block -> createShearsOnlyDrop(block));
        add(RemissionBlocks.FESTERING_TALL_GRASS.get(), block -> createDoublePlantShearsDrop(RemissionBlocks.FESTERING_SHORT_GRASS.get()));
        add(RemissionBlocks.FESTERING_LARGE_FERN.get(), block -> createDoublePlantShearsDrop(RemissionBlocks.FESTERING_FERN.get()));
        dropSelf(RemissionBlocks.FESTERED_STONE.get());
        dropSelf(RemissionBlocks.FESTERED_DEEPSLATE.get());
        // Festering veins (P4.8): one vein per face, with silk touch only, as sculk veins.
        add(RemissionBlocks.FESTERING_VEIN.get(), block -> createMultifaceBlockDrops(block, hasSilkTouch()));
        // Machines (P7.2) drop themselves; their contents drop when they are removed (MachineBlock).
        dropSelf(RemissionBlocks.ANALYZER.get());
        dropSelf(RemissionBlocks.SEQUENCER.get());
        dropSelf(RemissionBlocks.SYNTHESIZER.get());
        // Purification (P5.2): both drop themselves, so a pylon can be moved and mendroot replanted.
        dropSelf(RemissionBlocks.QUARANTINE_PYLON.get());
        dropSelf(RemissionBlocks.MENDROOT.get());
        // Reclaimers (P7.4) drop themselves; the fuel slot drops when it is removed (ReclaimerBlock).
        dropSelf(RemissionBlocks.RECLAIMER.get());
    }

    // Every Remission block must have a loot table; datagen fails if one is missing.
    @Override
    protected Iterable<Block> getKnownBlocks() {
        return RemissionBlocks.BLOCKS.getEntries().stream().<Block>map(DeferredHolder::get).toList();
    }
}
