package com.cubiecode.remission.registry;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.research.ScanItem;
import com.cubiecode.remission.research.ScannerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

    private RemissionItems() {
    }
}
