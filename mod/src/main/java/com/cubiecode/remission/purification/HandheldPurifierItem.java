package com.cubiecode.remission.purification;

import com.cubiecode.remission.api.CorruptionCause;
import com.cubiecode.remission.corruption.Corruption;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

/**
 * {@code remission:handheld_purifier}: used on a block, cleanses the festered blocks and veins around it and lowers its
 * chunk's Strain level ({@link Purification#purify}); used on a host, lowers the host's Strain level. Each use that
 * changes something costs one durability; every use starts a short cooldown.
 */
public class HandheldPurifierItem extends Item {
    /** Uses before it breaks. */
    public static final int DURABILITY = 256;
    /** Ticks between uses. */
    public static final int COOLDOWN_TICKS = 10;

    public HandheldPurifierItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        int changed = Purification.purify(level, context.getClickedPos(), player instanceof ServerPlayer serverPlayer ? serverPlayer : null,
                PurificationSettings.fromConfig());
        if (player != null) {
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        }
        if (changed == 0) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        if (player != null) {
            stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(context.getHand()));
        } else {
            stack.hurtAndBreak(1, level, null, item -> { });
        }
        level.playSound(null, context.getClickedPos(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.6F, 1.4F);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!Corruption.isHost(target)) {
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        int step = PurificationSettings.fromConfig().purifierHostStep();
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        if (step <= 0) {
            return InteractionResult.PASS;
        }
        Corruption.set(target, Math.max(0, Corruption.get(target).level() - step), CorruptionCause.PURIFICATION);
        stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        level.playSound(null, target.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.6F, 1.4F);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.remission.handheld_purifier.desc").withStyle(ChatFormatting.GRAY));
    }
}
