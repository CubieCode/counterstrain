package com.cubiecode.remission.data;

import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.countermeasure.CountermeasureProtocolItem;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionDataComponents;
import com.cubiecode.remission.registry.RemissionFluids;
import com.cubiecode.remission.registry.RemissionItems;
import com.cubiecode.remission.research.ScanData;
import com.cubiecode.remission.research.ScanItem;
import com.cubiecode.remission.tech.MachineKind;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

/** Generic default recipes only; pack balance lives in pack/kubejs. */
public final class RemissionRecipeProvider extends RecipeProvider {
    public RemissionRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        machines(output);
        purification(output);
        analyzing(output);
        sequencing(output);
        synthesizing(output);
        countermeasureProtocols(output);
        products(output);
        variants(output);
    }

    /** The machines themselves, from vanilla parts, so they work in any pack (P7.2). */
    private static void machines(RecipeOutput output) {
        machine(output, RemissionBlocks.ANALYZER.get(), Items.SPYGLASS);
        machine(output, RemissionBlocks.SEQUENCER.get(), Items.COMPARATOR);
        machine(output, RemissionBlocks.SYNTHESIZER.get(), Items.BREWING_STAND);
    }

    private static void machine(RecipeOutput output, Block machine, ItemLike core) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, machine)
                .pattern("IGI")
                .pattern("RCR")
                .pattern("III")
                .define('I', Items.IRON_INGOT)
                .define('G', Items.GLASS)
                .define('R', Items.REDSTONE)
                .define('C', core)
                .unlockedBy("has_iron_ingot", has(Items.IRON_INGOT))
                .save(output);
    }

    /** Purification tools from vanilla parts (P5.2): the purifier, a pylon built around one, the reclaimer (P7.4) and mendroot. */
    private static void purification(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, RemissionItems.HANDHELD_PURIFIER.get())
                .pattern(" G ")
                .pattern("ICI")
                .pattern(" I ")
                .define('G', Items.GLASS)
                .define('I', Items.IRON_INGOT)
                .define('C', Items.COPPER_INGOT)
                .unlockedBy("has_iron_ingot", has(Items.IRON_INGOT))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, RemissionBlocks.QUARANTINE_PYLON.get())
                .pattern("ILI")
                .pattern("IPI")
                .pattern("III")
                .define('I', Items.IRON_INGOT)
                .define('L', Items.LIGHTNING_ROD)
                .define('P', RemissionItems.HANDHELD_PURIFIER.get())
                .unlockedBy("has_handheld_purifier", has(RemissionItems.HANDHELD_PURIFIER.get()))
                .save(output);
        // Reclaimers (P7.4): a pylon's core in a powered frame. Packs gate it behind their tech tiers.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, RemissionBlocks.RECLAIMER.get())
                .pattern("ILI")
                .pattern("PQP")
                .pattern("IRI")
                .define('I', Items.IRON_BLOCK)
                .define('L', Items.LIGHTNING_ROD)
                .define('P', RemissionItems.HANDHELD_PURIFIER.get())
                .define('Q', RemissionBlocks.QUARANTINE_PYLON.get())
                .define('R', Items.REDSTONE_BLOCK)
                .unlockedBy("has_quarantine_pylon", has(RemissionBlocks.QUARANTINE_PYLON.get()))
                .save(output);
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, RemissionBlocks.MENDROOT.get(), 2)
                .requires(Items.WHEAT_SEEDS)
                .requires(Items.BONE_MEAL)
                .requires(Items.GLOW_BERRIES)
                .unlockedBy("has_glow_berries", has(Items.GLOW_BERRIES))
                .save(output);
    }

    /** Samples into miasma, and into block scans where the sample comes from a block. */
    private static void analyzing(RecipeOutput output) {
        MachineRecipeBuilder.of(MachineKind.ANALYZER, 2000, 100)
                .ingredient(RemissionItems.HOST_TISSUE.get())
                .fluidResult(RemissionFluids.MIASMA.get(), 100)
                .save(output, "host_tissue");
        MachineRecipeBuilder.of(MachineKind.ANALYZER, 2000, 100)
                .ingredient(RemissionItems.FESTERED_SOIL.get())
                .result(blockScan(RemissionBlocks.FESTERING_GRASS.get()))
                .fluidResult(RemissionFluids.MIASMA.get(), 50)
                .save(output, "festered_soil");
        MachineRecipeBuilder.of(MachineKind.ANALYZER, 8000, 200)
                .ingredient(RemissionItems.NIDUS_CORE.get())
                .result(blockScan(RemissionBlocks.NIDUS.get()))
                .fluidResult(RemissionFluids.MIASMA.get(), 1000)
                .save(output, "nidus_core");
    }

    /** A scan and host tissue in miasma make a strain sequence. */
    private static void sequencing(RecipeOutput output) {
        MachineRecipeBuilder.of(MachineKind.SEQUENCER, 6000, 200)
                .ingredient(ScanItem.SCANS)
                .ingredient(RemissionItems.HOST_TISSUE.get())
                .fluid(RemissionFluids.MIASMA.get(), 250)
                .result(new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()))
                .save(output, "strain_sequence");
    }

    /** A strain sequence cultures host tissue in miasma; kept beside the synthesizer's products (P7.3). */
    private static void synthesizing(RecipeOutput output) {
        MachineRecipeBuilder.of(MachineKind.SYNTHESIZER, 4000, 200)
                .ingredient(RemissionItems.STRAIN_SEQUENCE.get())
                .ingredient(RemissionItems.HOST_TISSUE.get())
                .fluid(RemissionFluids.MIASMA.get(), 500)
                .result(new ItemStack(RemissionItems.HOST_TISSUE.get(), 2))
                .save(output, "host_tissue");
    }

    /**
     * One countermeasure protocol per built Remission hazard (P5.7): a strain sequence, a sample of what carries the
     * hazard and paper to write the protocol on, in miasma. Later hazards add theirs here; packs replace them.
     */
    private static void countermeasureProtocols(RecipeOutput output) {
        countermeasureProtocol(output, StrainHazards.CONTACT, RemissionItems.FESTERED_SOIL.get());
    }

    private static void countermeasureProtocol(RecipeOutput output, ResourceLocation hazard, ItemLike sample) {
        MachineRecipeBuilder.of(MachineKind.SYNTHESIZER, 8000, 400)
                .ingredient(RemissionItems.STRAIN_SEQUENCE.get())
                .ingredient(sample)
                .ingredient(Items.PAPER)
                .fluid(RemissionFluids.MIASMA.get(), 1000)
                .result(CountermeasureProtocolItem.of(RemissionItems.COUNTERMEASURE_PROTOCOL.get(), hazard))
                .save(output, "countermeasure_protocol_" + hazard.getPath());
    }

    /** The synthesizer's products (P7.3): serums, purifier fuel and unstable Counterstrain prototypes, each from a strain sequence. */
    private static void products(RecipeOutput output) {
        MachineRecipeBuilder.of(MachineKind.SYNTHESIZER, 4000, 200)
                .ingredient(RemissionItems.STRAIN_SEQUENCE.get())
                .ingredient(Items.GLASS_BOTTLE, 2)
                .fluid(RemissionFluids.MIASMA.get(), 250)
                .result(new ItemStack(RemissionItems.SERUM.get(), 2))
                .save(output, "serum");
        MachineRecipeBuilder.of(MachineKind.SYNTHESIZER, 4000, 200)
                .ingredient(RemissionItems.STRAIN_SEQUENCE.get())
                .ingredient(ItemTags.COALS, 4)
                .fluid(RemissionFluids.MIASMA.get(), 250)
                .result(new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 4))
                .save(output, "purifier_fuel");
        MachineRecipeBuilder.of(MachineKind.SYNTHESIZER, 12000, 400)
                .ingredient(RemissionItems.STRAIN_SEQUENCE.get())
                .ingredient(RemissionItems.NIDUS_CORE.get())
                .ingredient(Items.GLASS_BOTTLE)
                .fluid(RemissionFluids.MIASMA.get(), 1000)
                .result(new ItemStack(RemissionItems.COUNTERSTRAIN_PROTOTYPE.get()))
                .save(output, "counterstrain_prototype");
    }

    /**
     * Each default variant (P7.5): a scan of it sequenced with a strain sequence makes a strain sequence of the variant,
     * from which the synthesizer makes the serum (damage variants) or purifier fuel (purification variants) that counters
     * it. Each takes one more ingredient than the plain product (host tissue, festered soil), so a variant's sequence never
     * matches both. Packs that add variants add theirs.
     */
    private static void variants(RecipeOutput output) {
        for (RemissionVariantProvider.Default variant : RemissionVariantProvider.DEFAULTS) {
            ResourceLocation id = variant.id();
            MachineRecipeBuilder.of(MachineKind.SEQUENCER, 6000, 200)
                    .ingredient(DataComponentIngredient.of(false, RemissionDataComponents.VARIANT, id,
                            RemissionItems.HOST_SCAN.get(), RemissionItems.BLOCK_SCAN.get()), 1)
                    .ingredient(RemissionItems.STRAIN_SEQUENCE.get())
                    .fluid(RemissionFluids.MIASMA.get(), 250)
                    .result(withVariant(new ItemStack(RemissionItems.STRAIN_SEQUENCE.get()), id))
                    .save(output, "variant/" + id.getPath());
            MachineRecipeBuilder synthesis = MachineRecipeBuilder.of(MachineKind.SYNTHESIZER, 6000, 300)
                    .ingredient(DataComponentIngredient.of(false, RemissionDataComponents.VARIANT, id,
                            RemissionItems.STRAIN_SEQUENCE.get()), 1)
                    .fluid(RemissionFluids.MIASMA.get(), 250);
            if (variant.damage()) {
                synthesis.ingredient(Items.GLASS_BOTTLE, 2)
                        .ingredient(RemissionItems.HOST_TISSUE.get())
                        .result(withVariant(new ItemStack(RemissionItems.SERUM.get(), 2), id));
            } else {
                synthesis.ingredient(ItemTags.COALS, 4)
                        .ingredient(RemissionItems.FESTERED_SOIL.get())
                        .result(withVariant(new ItemStack(RemissionItems.PURIFIER_FUEL.get(), 4), id));
            }
            synthesis.save(output, "variant/" + id.getPath());
        }
    }

    private static ItemStack withVariant(ItemStack stack, ResourceLocation variant) {
        stack.set(RemissionDataComponents.VARIANT.get(), variant);
        return stack;
    }

    private static ItemStack blockScan(Block block) {
        ItemStack scan = new ItemStack(RemissionItems.BLOCK_SCAN.get());
        scan.set(RemissionDataComponents.SCAN_DATA.get(), ScanData.block(block.builtInRegistryHolder().key().location()));
        return scan;
    }
}
