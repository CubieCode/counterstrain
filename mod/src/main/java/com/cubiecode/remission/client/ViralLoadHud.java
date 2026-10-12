package com.cubiecode.remission.client;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.config.RemissionClientConfig;
import com.cubiecode.remission.viralload.ViralLoad;
import com.cubiecode.remission.viralload.ViralLoadState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The viral load meter (P5.1): one HUD row on the right, stacked above the hunger bar and air bubbles like vanilla's
 * rows ({@code Gui.rightHeight}). An icon, a bar filled to the local player's synced viral load whose colour steps up
 * every quarter, and an arrow while it rises or falls. Shown only while viral load is above 0, in survival and adventure,
 * with the GUI visible and {@code hud.showViralLoad} on. The icon is placeholder art; the bar is drawn in code.
 */
public final class ViralLoadHud {
    public static final ResourceLocation LAYER = Remission.id("viral_load");
    /** GUI sprite {@code textures/gui/sprites/hud/viral_load.png}. */
    private static final ResourceLocation ICON = Remission.id("hud/viral_load");
    private static final int ROW_WIDTH = 81;
    private static final int BAR_WIDTH = 70;
    private static final int FRAME = 0xFF1C1424;
    private static final int EMPTY = 0xFF3A3042;
    /** Fill colour per quarter of the meter: pale green, sickly yellow, bruise orange, Strain violet. */
    private static final int[] FILL = {0xFF9CC46A, 0xFFD2C056, 0xFFC47A48, 0xFFA8367E};
    private static final int ARROW = 0xFFE8DDF0;

    private ViralLoadHud() {
    }

    static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.AIR_LEVEL, LAYER, ViralLoadHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer()
                || !RemissionClientConfig.SHOW_VIRAL_LOAD.get()) {
            return;
        }
        ViralLoadState state = ViralLoad.state(mc.player);
        if (state.load() <= 0) {
            return;
        }
        int right = graphics.guiWidth() / 2 + 91;
        int y = graphics.guiHeight() - mc.gui.rightHeight;
        int left = right - ROW_WIDTH;

        RenderSystem.enableBlend();
        graphics.blitSprite(ICON, right - 9, y, 9, 9);
        RenderSystem.disableBlend();

        graphics.fill(left, y + 2, left + BAR_WIDTH, y + 7, FRAME);
        graphics.fill(left + 1, y + 3, left + BAR_WIDTH - 1, y + 6, EMPTY);
        float share = state.load() / ViralLoadState.MAX;
        int filled = Mth.ceil((BAR_WIDTH - 2) * share);
        int colour = FILL[Math.min(FILL.length - 1, (int) (share * FILL.length))];
        graphics.fill(left + 1, y + 3, left + 1 + filled, y + 6, colour);

        switch (state.trend()) {
            case RISING -> arrow(graphics, left - 7, y + 2, true);
            case FALLING -> arrow(graphics, left - 7, y + 2, false);
            case STEADY -> {
            }
        }
        mc.gui.rightHeight += 10;
    }

    /** A 5x3 chevron with its top-left corner at {@code x, y}, pointing up or down. */
    private static void arrow(GuiGraphics graphics, int x, int y, boolean up) {
        for (int row = 0; row < 3; row++) {
            int width = up ? row : 2 - row;
            graphics.fill(x + 2 - width, y + 1 + row, x + 3 + width, y + 2 + row, ARROW);
        }
    }
}
