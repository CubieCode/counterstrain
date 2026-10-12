package com.cubiecode.remission.countermeasure;

import com.cubiecode.remission.api.CountermeasureGrantedEvent;
import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.registry.RemissionDataComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * {@code remission:countermeasure_protocol} (P5.7): a data item whose {@code remission:hazard} component names a Strain
 * hazard. Using it grants that countermeasure to the user and their online teammates and uses up one protocol; a
 * protocol for a countermeasure the user already holds, or one that names no hazard, does nothing.
 */
public class CountermeasureProtocolItem extends Item {
    public CountermeasureProtocolItem(Properties properties) {
        super(properties);
    }

    /** A protocol for {@code hazard}. */
    public static ItemStack of(Item item, ResourceLocation hazard) {
        ItemStack stack = new ItemStack(item);
        stack.set(RemissionDataComponents.HAZARD, hazard);
        return stack;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ResourceLocation hazard = stack.get(RemissionDataComponents.HAZARD);
        if (hazard == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (Countermeasures.holds(player, hazard)) {
            if (!level.isClientSide()) {
                player.displayClientMessage(Component.translatable("message.remission.countermeasure.held",
                        StrainHazards.displayName(hazard)), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            Countermeasures.grant(serverPlayer, hazard, CountermeasureGrantedEvent.Cause.PROTOCOL);
            serverPlayer.displayClientMessage(Component.translatable("message.remission.countermeasure.gained",
                    StrainHazards.displayName(hazard)), true);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP,
                    SoundSource.PLAYERS, 0.5F, 1.4F);
            stack.consume(1, player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ResourceLocation hazard = stack.get(RemissionDataComponents.HAZARD);
        if (hazard == null) {
            tooltip.add(Component.translatable("item.remission.countermeasure_protocol.blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.translatable("item.remission.countermeasure_protocol.hazard", StrainHazards.displayName(hazard))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.remission.countermeasure_protocol.desc").withStyle(ChatFormatting.DARK_GRAY));
    }
}
