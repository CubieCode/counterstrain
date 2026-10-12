package com.cubiecode.remission.research;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

/** Records host scans from living entities ({@link Scanning}) and block scans from blocks. */
public class ScannerItem extends Item {
    public ScannerItem(Properties properties) {
        super(properties);
    }

    // Before the block's own use, so scanning a chest or door does not open it.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        return Scanning.scanBlock(context.getPlayer(), context.getLevel(), context.getClickedPos(), context.getHand());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.remission.scanner.desc").withStyle(ChatFormatting.GRAY));
    }
}
