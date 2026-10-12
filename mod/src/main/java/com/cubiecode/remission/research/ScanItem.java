package com.cubiecode.remission.research;

import com.cubiecode.remission.registry.RemissionDataComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A host scan or block scan: a data item whose {@code remission:scan_data} component says what was scanned. */
public class ScanItem extends Item {
    public ScanItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ScanData data = stack.get(RemissionDataComponents.SCAN_DATA);
        if (data == null) {
            return;
        }
        Component line = switch (data.kind()) {
            case HOST -> Component.translatable("item.remission.host_scan.subject", data.subjectName(), data.stage());
            case BLOCK -> Component.translatable("item.remission.block_scan.subject", data.subjectName());
        };
        tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
    }
}
