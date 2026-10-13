package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.countermeasure.CountermeasureProtocolItem;
import com.cubiecode.remission.counterstrain.PrototypeItem;
import com.cubiecode.remission.purification.HandheldPurifierItem;
import com.cubiecode.remission.research.ScanItem;
import com.cubiecode.remission.research.ScannerItem;
import com.cubiecode.remission.serum.SerumItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item and block-item registrations. */
public final class RemissionItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Remission.MOD_ID);

    public static final DeferredItem<BlockItem> NIDUS = ITEMS.registerSimpleBlockItem(RemissionBlocks.NIDUS);

    // Research (P6.1)
    public static final DeferredItem<ScannerItem> SCANNER = ITEMS.registerItem("scanner", ScannerItem::new,
            new Item.Properties().stacksTo(1));
    public static final DeferredItem<ScanItem> HOST_SCAN = ITEMS.registerItem("host_scan", ScanItem::new);
    public static final DeferredItem<ScanItem> BLOCK_SCAN = ITEMS.registerItem("block_scan", ScanItem::new);

    /** Sample dropped by hosts ({@code remission:host_samples} loot modifier). */
    public static final DeferredItem<Item> HOST_TISSUE = ITEMS.registerSimpleItem("host_tissue");

    // Miasma and samples (P7.1)
    public static final DeferredItem<BucketItem> MIASMA_BUCKET = ITEMS.registerItem("miasma_bucket",
            properties -> new BucketItem(RemissionFluids.MIASMA.get(), properties),
            new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));
    /** Sample dropped by a broken nidus (its block loot table). */
    public static final DeferredItem<Item> NIDUS_CORE = ITEMS.registerSimpleItem("nidus_core");
    /** Sample dropped by festering ground ({@code remission:festered_soil_samples} loot modifier). */
    public static final DeferredItem<Item> FESTERED_SOIL = ITEMS.registerSimpleItem("festered_soil");

    // Festering stages (P4.2)
    public static final DeferredItem<BlockItem> FESTERING_GRASS = ITEMS.registerSimpleBlockItem(RemissionBlocks.FESTERING_GRASS);
    public static final DeferredItem<BlockItem> FESTERED_STONE = ITEMS.registerSimpleBlockItem(RemissionBlocks.FESTERED_STONE);
    // Festering plants (P4.16)
    public static final DeferredItem<BlockItem> FESTERING_SHORT_GRASS = ITEMS.registerSimpleBlockItem(RemissionBlocks.FESTERING_SHORT_GRASS);
    public static final DeferredItem<DoubleHighBlockItem> FESTERING_TALL_GRASS = ITEMS.registerItem("festering_tall_grass",
            properties -> new DoubleHighBlockItem(RemissionBlocks.FESTERING_TALL_GRASS.get(), properties));
    public static final DeferredItem<BlockItem> FESTERING_FERN = ITEMS.registerSimpleBlockItem(RemissionBlocks.FESTERING_FERN);
    public static final DeferredItem<DoubleHighBlockItem> FESTERING_LARGE_FERN = ITEMS.registerItem("festering_large_fern",
            properties -> new DoubleHighBlockItem(RemissionBlocks.FESTERING_LARGE_FERN.get(), properties));
    public static final DeferredItem<BlockItem> FESTERED_DEEPSLATE = ITEMS.registerSimpleBlockItem(RemissionBlocks.FESTERED_DEEPSLATE);

    // Festering veins (P4.8)
    public static final DeferredItem<BlockItem> FESTERING_VEIN = ITEMS.registerSimpleBlockItem(RemissionBlocks.FESTERING_VEIN);
    // Machines (P7.2)
    public static final DeferredItem<BlockItem> ANALYZER = ITEMS.registerSimpleBlockItem(RemissionBlocks.ANALYZER);
    public static final DeferredItem<BlockItem> SEQUENCER = ITEMS.registerSimpleBlockItem(RemissionBlocks.SEQUENCER);
    public static final DeferredItem<BlockItem> SYNTHESIZER = ITEMS.registerSimpleBlockItem(RemissionBlocks.SYNTHESIZER);
    /** Sequencer product (P7.2): the synthesizer's input, and what serums are made from (P7.3). */
    public static final DeferredItem<Item> STRAIN_SEQUENCE = ITEMS.registerSimpleItem("strain_sequence");

    // Purification (P5.2)
    public static final DeferredItem<HandheldPurifierItem> HANDHELD_PURIFIER = ITEMS.registerItem("handheld_purifier",
            HandheldPurifierItem::new, new Item.Properties().durability(HandheldPurifierItem.DURABILITY));
    public static final DeferredItem<BlockItem> QUARANTINE_PYLON = ITEMS.registerSimpleBlockItem(RemissionBlocks.QUARANTINE_PYLON);
    /** Planted like seeds (a block item), so players, dispensers with a pack's behaviour, and automation can place it. */
    public static final DeferredItem<BlockItem> MENDROOT = ITEMS.registerSimpleBlockItem(RemissionBlocks.MENDROOT);

    // Countermeasures (P5.7)
    public static final DeferredItem<CountermeasureProtocolItem> COUNTERMEASURE_PROTOCOL = ITEMS.registerItem(
            "countermeasure_protocol", CountermeasureProtocolItem::new);

    // Synthesizer products (P7.3)
    public static final DeferredItem<SerumItem> SERUM = ITEMS.registerItem("serum", SerumItem::new,
            new Item.Properties().stacksTo(16));
    /** In {@code #remission:purifier_fuel}; repairs the handheld purifier in an anvil. */
    public static final DeferredItem<Item> PURIFIER_FUEL = ITEMS.registerSimpleItem("purifier_fuel");
    public static final DeferredItem<PrototypeItem> COUNTERSTRAIN_PROTOTYPE = ITEMS.registerItem("counterstrain_prototype",
            PrototypeItem::new, new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON));

    private RemissionItems() {
    }
}
