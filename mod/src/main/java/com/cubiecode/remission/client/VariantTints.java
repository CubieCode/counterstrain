package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.corruption.CorruptionState;
import com.cubiecode.remission.network.VariantLandCache;
import com.cubiecode.remission.registry.RemissionBlocks;
import com.cubiecode.remission.registry.RemissionDataComponents;
import com.cubiecode.remission.variant.Variants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Variant tints on the client (P7.5). Festering blocks (tint index 0 in their models) take the tint of their chunk's
 * variant from {@link VariantLandCache}, white elsewhere; a chunk whose variant changes is re-meshed. Hosts take their
 * own variant's tint through {@link #hostTint}. Items with a {@code remission:variant} component (scans, strain
 * sequences, serums, purifier fuel) name it in their tooltip.
 */
@EventBusSubscriber(modid = Remission.MOD_ID, value = Dist.CLIENT)
public final class VariantTints {
    private static boolean listening;

    private VariantTints() {
    }

    @SubscribeEvent
    static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> pos == null ? VariantLandCache.NO_TINT
                        : VariantLandCache.tint(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)),
                RemissionBlocks.FESTERING_GRASS.get(), RemissionBlocks.FESTERING_SHORT_GRASS.get(),
                RemissionBlocks.FESTERING_TALL_GRASS.get(), RemissionBlocks.FESTERING_FERN.get(),
                RemissionBlocks.FESTERING_LARGE_FERN.get(), RemissionBlocks.FESTERED_STONE.get(),
                RemissionBlocks.FESTERED_DEEPSLATE.get(), RemissionBlocks.FESTERING_VEIN.get());
        if (!listening) {
            listening = true;
            VariantLandCache.addListener(new VariantLandCache.Listener() {
                @Override
                public void changed(long chunk) {
                    Minecraft minecraft = Minecraft.getInstance();
                    Level level = minecraft.level;
                    if (level != null) {
                        int x = ChunkPos.getX(chunk) << 4;
                        int z = ChunkPos.getZ(chunk) << 4;
                        minecraft.levelRenderer.setBlocksDirty(x, level.getMinBuildHeight(), z, x + 15, level.getMaxBuildHeight() - 1, z + 15);
                    }
                }

                @Override
                public void cleared() {
                }
            });
        }
    }

    /** The RGB a host of {@code state}'s variant is drawn with, or white without one (or one this client lacks). */
    static int hostTint(CorruptionState state) {
        Level level = Minecraft.getInstance().level;
        if (state.variant().isEmpty() || level == null) {
            return 0xFFFFFF;
        }
        return Variants.get(level.registryAccess(), state.variant().get()).map(variant -> variant.tint()).orElse(0xFFFFFF);
    }

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        ResourceLocation variant = event.getItemStack().get(RemissionDataComponents.VARIANT);
        if (variant != null) {
            event.getToolTip().add(1, Component.translatable("item.remission.variant", Variants.name(variant))
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            VariantLandCache.forget(event.getChunk().getPos().toLong());
        }
    }

    // A respawn or dimension change gives the client a new level; logging out ends the session.
    @SubscribeEvent
    static void onClone(ClientPlayerNetworkEvent.Clone event) {
        VariantLandCache.clear();
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        VariantLandCache.clear();
    }
}
