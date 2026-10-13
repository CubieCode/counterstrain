package com.cubiecode.remission.client.notice;

import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.config.RemissionClientConfig;
import com.cubiecode.remission.network.HazardClientCache;
import com.cubiecode.remission.network.HazardNoticePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Hazard notices on the client (P5.8): each notice the server sends goes to chat unless its hazard is muted in the
 * client config ({@code [notices] muted}), and logging out clears {@link HazardClientCache}. The text uses the hazard's
 * own lang key {@code notice.<namespace>.<path>} when the language has one (so a hazard can say "3 blocks rotted near
 * ..."), otherwise {@code notice.remission.hazard} with the hazard's name; each gets the arguments name, count, x, y, z,
 * areas. A summary of several areas always uses {@code notice.remission.hazard.areas}.
 */
public final class HazardNoticeDisplay {
    private HazardNoticeDisplay() {
    }

    /** From the client entrypoint. */
    public static void init() {
        HazardClientCache.addListener(new HazardClientCache.Listener() {
            @Override
            public void notice(HazardNoticePayload notice) {
                Minecraft mc = Minecraft.getInstance();
                if (!muted(notice.hazard()) && mc.player != null) {
                    mc.gui.getChat().addMessage(message(notice, mc.player.level().dimension())
                            .withStyle(ChatFormatting.GOLD));
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> HazardClientCache.clear());
    }

    /** Whether the player muted this hazard's notices. */
    public static boolean muted(ResourceLocation hazard) {
        return RemissionClientConfig.NOTICES_MUTED.get().contains(hazard.toString());
    }

    /**
     * The notice's text; when {@code current} is set and the notice is in another dimension, the dimension is named
     * after it.
     */
    public static MutableComponent message(HazardNoticePayload notice, @Nullable ResourceKey<Level> current) {
        String own = "notice." + notice.hazard().getNamespace() + "." + notice.hazard().getPath();
        String key = notice.areas() > 1 ? "notice.remission.hazard.areas"
                : I18n.exists(own) ? own
                : notice.count() == 1 ? "notice.remission.hazard" : "notice.remission.hazard.repeated";
        MutableComponent text = Component.translatable(key, StrainHazards.displayName(notice.hazard()), notice.count(),
                notice.pos().getX(), notice.pos().getY(), notice.pos().getZ(), notice.areas());
        if (current != null && !current.equals(notice.dimension())) {
            text.append(Component.translatable("notice.remission.hazard.dimension",
                    notice.dimension().location().toString()));
        }
        return text;
    }
}
