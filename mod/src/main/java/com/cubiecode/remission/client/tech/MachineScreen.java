package com.cubiecode.remission.client.tech;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.tech.MachineKind;
import com.cubiecode.remission.tech.MachineMenu;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Machine screen: energy bar, input tank, input slots, progress arrow, output slots and output tank. Everything it
 * shows comes from the menu's synced slots and data. The background is placeholder art (CONTENT-PROVENANCE).
 */
public final class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    public static final ResourceLocation TEXTURE = Remission.id("textures/gui/machine.png");
    // Regions of the background, in GUI pixels (MachineMenu holds the slot layout).
    public static final int ENERGY_X = 8;
    public static final int INPUT_TANK_X = 24;
    public static final int OUTPUT_TANK_X = 152;
    public static final int GAUGE_Y = 17;
    public static final int GAUGE_HEIGHT = 52;
    public static final int ENERGY_WIDTH = 12;
    public static final int TANK_WIDTH = 16;
    public static final int ARROW_X = 101;
    public static final int ARROW_Y = 34;
    public static final int ARROW_WIDTH = 24;
    public static final int ARROW_HEIGHT = 17;
    // Sprites to the right of the background.
    private static final int SLOT_U = 176;
    private static final int SLOT_V = 0;
    private static final int ARROW_U = 176;
    private static final int ARROW_V = 18;
    private static final int ENERGY_U = 176;
    private static final int ENERGY_V = 35;
    private static final int GAUGE_U = 188;
    private static final int GAUGE_V = 35;

    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderGaugeTooltips(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
        MachineKind kind = menu.kind();
        for (int i = 0; i < kind.inputSlots(); i++) {
            graphics.blit(TEXTURE, x + MachineMenu.inputX(kind, i) - 1, y + MachineMenu.INPUT_Y - 1, SLOT_U, SLOT_V, 18, 18);
        }

        int energy = scaled(menu.energy(), MachineKind.ENERGY_CAPACITY, GAUGE_HEIGHT);
        if (energy > 0) {
            graphics.blit(TEXTURE, x + ENERGY_X, y + GAUGE_Y + GAUGE_HEIGHT - energy, ENERGY_U,
                    ENERGY_V + GAUGE_HEIGHT - energy, ENERGY_WIDTH, energy);
        }
        renderTank(graphics, menu.inputFluid(), x + INPUT_TANK_X, y + GAUGE_Y);
        renderTank(graphics, menu.outputFluid(), x + OUTPUT_TANK_X, y + GAUGE_Y);

        int arrow = scaled(menu.progress(), menu.time(), ARROW_WIDTH);
        if (arrow > 0) {
            graphics.blit(TEXTURE, x + ARROW_X, y + ARROW_Y, ARROW_U, ARROW_V, arrow, ARROW_HEIGHT);
        }
    }

    /** The fluid's still texture, tinted, filling the tank from the bottom; then the gauge marks over it. */
    private void renderTank(GuiGraphics graphics, FluidStack fluid, int x, int y) {
        int height = scaled(fluid.getAmount(), MachineKind.TANK_CAPACITY, GAUGE_HEIGHT);
        if (height > 0) {
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid.getFluid());
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(extensions.getStillTexture(fluid));
            int tint = extensions.getTintColor(fluid);
            float red = (tint >> 16 & 0xFF) / 255.0F;
            float green = (tint >> 8 & 0xFF) / 255.0F;
            float blue = (tint & 0xFF) / 255.0F;
            float alpha = (tint >>> 24) / 255.0F;
            for (int drawn = 0; drawn < height; drawn += 16) {
                int piece = Math.min(16, height - drawn);
                graphics.blit(x, y + GAUGE_HEIGHT - drawn - piece, 0, TANK_WIDTH, piece, sprite, red, green, blue, alpha);
            }
        }
        graphics.blit(TEXTURE, x, y, GAUGE_U, GAUGE_V, TANK_WIDTH, GAUGE_HEIGHT);
    }

    private void renderGaugeTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        if (inGauge(mouseX, mouseY, ENERGY_X, ENERGY_WIDTH)) {
            graphics.renderComponentTooltip(font, List.of(Component.translatable("gui.remission.machine.energy",
                    menu.energy(), MachineKind.ENERGY_CAPACITY)), mouseX, mouseY);
        } else if (inGauge(mouseX, mouseY, INPUT_TANK_X, TANK_WIDTH)) {
            graphics.renderComponentTooltip(font, tankTooltip("gui.remission.machine.input_tank", menu.inputFluid()), mouseX, mouseY);
        } else if (inGauge(mouseX, mouseY, OUTPUT_TANK_X, TANK_WIDTH)) {
            graphics.renderComponentTooltip(font, tankTooltip("gui.remission.machine.output_tank", menu.outputFluid()), mouseX, mouseY);
        }
    }

    private static List<Component> tankTooltip(String key, FluidStack fluid) {
        Component contents = fluid.isEmpty()
                ? Component.translatable("gui.remission.machine.tank_empty")
                : Component.translatable("gui.remission.machine.tank", fluid.getHoverName(), fluid.getAmount(),
                        MachineKind.TANK_CAPACITY);
        return List.of(Component.translatable(key), contents);
    }

    private boolean inGauge(int mouseX, int mouseY, int gaugeX, int width) {
        int x = mouseX - leftPos - gaugeX;
        int y = mouseY - topPos - GAUGE_Y;
        return x >= 0 && x < width && y >= 0 && y < GAUGE_HEIGHT;
    }

    private static int scaled(int value, int max, int size) {
        return max <= 0 || value <= 0 ? 0 : Math.min(size, Math.max(1, (int) ((long) value * size / max)));
    }
}
