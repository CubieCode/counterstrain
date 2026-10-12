package com.cubiecode.remission.research;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.Corruption;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.quest.QuestProgress;
import com.cubiecode.remission.registry.RemissionDataComponents;
import com.cubiecode.remission.registry.RemissionItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Scanner behaviour. Scanning a host gives a host scan and records it in the player's {@link Bestiary}, scanning a block
 * gives a block scan; a living entity that is not a host gives nothing. Every scan attempt starts a
 * {@link #COOLDOWN_TICKS} cooldown. The server decides and records; the client only claims the interaction so the arm
 * swings and the mob's own interaction (trading, mounting) is skipped.
 */
@EventBusSubscriber(modid = Remission.MOD_ID)
public final class Scanning {
    public static final int COOLDOWN_TICKS = 20;

    private Scanning() {
    }

    // Entity interactions are taken before the entity's own interact (villagers trade and horses mount on any item).
    // Both events are handled because the client tries interactAt first and sends interact only if that passes.
    @SubscribeEvent
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        InteractionResult result = claim(event, event.getTarget());
        if (result != null) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        InteractionResult result = claim(event, event.getTarget());
        if (result != null) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    /** The scan's result when the player is scanning a living entity, else null (the interaction goes ahead). */
    private static @Nullable InteractionResult claim(PlayerInteractEvent event, Entity target) {
        if (target instanceof LivingEntity living && event.getItemStack().is(RemissionItems.SCANNER.get())) {
            return scanEntity(event.getEntity(), living, event.getHand());
        }
        return null;
    }

    /** Scans a living entity with the scanner in {@code hand}. */
    public static InteractionResult scanEntity(Player player, LivingEntity target, InteractionHand hand) {
        ItemStack scanner = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(scanner.getItem())) {
            return InteractionResult.CONSUME;
        }
        Level level = player.level();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        player.getCooldowns().addCooldown(scanner.getItem(), COOLDOWN_TICKS);
        CorruptionState state = Corruption.get(target);
        if (!state.isHost()) {
            player.displayClientMessage(Component.translatable("message.remission.scanner.no_strain", target.getDisplayName()), true);
            return InteractionResult.CONSUME;
        }
        ScanData data = ScanData.host(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()), state.stage());
        give(player, RemissionItems.HOST_SCAN.get().getDefaultInstance(), data);
        Bestiary.recordScan(player, data.id(), data.stage());
        if (player instanceof ServerPlayer serverPlayer) {
            QuestProgress.hostScanned(serverPlayer, target, data.stage());
        }
        player.displayClientMessage(Component.translatable("message.remission.scanner.host", data.subjectName(), data.stage()), true);
        return InteractionResult.CONSUME;
    }

    /** Scans the block at {@code pos} with the scanner in {@code hand}. */
    public static InteractionResult scanBlock(Player player, Level level, BlockPos pos, InteractionHand hand) {
        ItemStack scanner = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(scanner.getItem())) {
            return InteractionResult.CONSUME;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        player.getCooldowns().addCooldown(scanner.getItem(), COOLDOWN_TICKS);
        ScanData data = ScanData.block(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
        give(player, RemissionItems.BLOCK_SCAN.get().getDefaultInstance(), data);
        if (player instanceof ServerPlayer serverPlayer) {
            QuestProgress.blockScanned(serverPlayer, pos);
        }
        player.displayClientMessage(Component.translatable("message.remission.scanner.block", data.subjectName()), true);
        return InteractionResult.CONSUME;
    }

    private static void give(Player player, ItemStack scan, ScanData data) {
        scan.set(RemissionDataComponents.SCAN_DATA, data);
        if (!player.getInventory().add(scan)) {
            player.drop(scan, false);
        }
    }
}
