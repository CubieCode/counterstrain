package com.cubiecode.remission.client.purification;

import com.cubiecode.remission.Remission;
import com.cubiecode.remission.purification.ReclaimerBlockEntity;
import com.cubiecode.remission.purification.ReclaimerMenu;
import com.cubiecode.remission.purification.ReclaimerSettings;
import com.cubiecode.remission.registry.RemissionMenus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Reclaimer screen (P7.4): energy bar, fuel tank and fuel slot, what the last operation did, how much of its reach is
 * in remission, its reach, and a map of the chunks in reach. Everything comes from the menu's synced slot and data. The
 * background is placeholder art (CONTENT-PROVENANCE).
 */
@EventBusSubscriber(modid = Remission.MOD_ID, value = Dist.CLIENT)
public final class ReclaimerScreen extends AbstractContainerScreen<ReclaimerMenu> {
    public static final ResourceLocation TEXTURE = Remission.id("textures/gui/reclaimer.png");
    // Regions of the background, in GUI pixels (ReclaimerMenu holds the slot layout).
    public static final int ENERGY_X = 8;
    public static final int TANK_X = 24;
    public static final int GAUGE_Y = 17;
    public static final int GAUGE_HEIGHT = 52;
    public static final int ENERGY_WIDTH = 12;
    public static final int TANK_WIDTH = 16;
    public static final int TEXT_X = 44;
    public static final int TEXT_WIDTH = 70;
    public static final int STATUS_Y = 18;
    public static final int BAR_Y = 28;
    public static final int BAR_HEIGHT = 4;
    public static final int PROGRESS_Y = 34;
    public static final int REACH_Y = 43;
    public static final int MAP_X = 117;
    public static final int MAP_Y = 17;
    public static final int MAP_SIZE = 51;
    // Sprites to the right of the background.
    private static final int ENERGY_U = 176;
    private static final int ENERGY_V = 35;
    private static final int GAUGE_U = 188;
    private static final int GAUGE_V = 35;
    /** Map colours by {@link ReclaimerMenu.Cell} code (ARGB). */
    private static final int[] CELL_COLOURS = {
            0x00000000, // outside
            0xFF2E2E33, // unloaded
            0xFF7A8A6E, // clean
            0xFF73E3B8, // remission
            0xFFA07AB4, // stage 1
            0xFF8452A0, // stage 2
            0xFF673286, // stage 3
            0xFF4A1A66, // stage 4
            0xFFE0344E, // nidus
    };
    private static final int TEXT_COLOUR = 0x404040;
    private static final int REMISSION_BAR = 0xFF52C79A;

    public ReclaimerScreen(ReclaimerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
        inventoryLabelY = imageHeight - 94;
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(RemissionMenus.RECLAIMER.get(), ReclaimerScreen::new);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderPanelTooltips(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);

        int energy = scaled(menu.energy(), menu.capacity(), GAUGE_HEIGHT);
        if (energy > 0) {
            graphics.blit(TEXTURE, x + ENERGY_X, y + GAUGE_Y + GAUGE_HEIGHT - energy, ENERGY_U,
                    ENERGY_V + GAUGE_HEIGHT - energy, ENERGY_WIDTH, energy);
        }
        renderTank(graphics, menu.fluid(), x + TANK_X, y + GAUGE_Y);

        int bar = scaled(menu.reclaimed(), menu.area(), TEXT_WIDTH);
        if (bar > 0) {
            graphics.fill(x + TEXT_X, y + BAR_Y, x + TEXT_X + bar, y + BAR_Y + BAR_HEIGHT, REMISSION_BAR);
        }
        renderMap(graphics, x + MAP_X, y + MAP_Y);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        ReclaimerBlockEntity.Status status = menu.status();
        drawFit(graphics, statusText(status), TEXT_X, STATUS_Y, statusColour(status));
        drawFit(graphics, Component.translatable("gui.remission.reclaimer.progress", menu.reclaimed(), menu.area()),
                TEXT_X, PROGRESS_Y, TEXT_COLOUR);
        int side = 2 * menu.radius() + 1;
        drawFit(graphics, Component.translatable("gui.remission.reclaimer.reach", side, side), TEXT_X, REACH_Y, TEXT_COLOUR);
        graphics.drawString(font, "×" + menu.fuel(), ReclaimerMenu.FUEL_X + 20, ReclaimerMenu.FUEL_Y + 5, TEXT_COLOUR, false);
    }

    /** Draws {@code text}, scaled down if it is wider than the text column. */
    private void drawFit(GuiGraphics graphics, Component text, int x, int y, int colour) {
        int width = font.width(text);
        if (width <= TEXT_WIDTH) {
            graphics.drawString(font, text, x, y, colour, false);
            return;
        }
        float scale = (float) TEXT_WIDTH / width;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y + (1.0F - scale) * font.lineHeight / 2.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, colour, false);
        graphics.pose().popPose();
    }

    /** One square per chunk in reach, the reclaimer's own outlined. */
    private void renderMap(GuiGraphics graphics, int x, int y) {
        int radius = Math.min(menu.radius(), ReclaimerSettings.MAX_RADIUS);
        int side = 2 * radius + 1;
        int cell = MAP_SIZE / side;
        int offset = (MAP_SIZE - cell * side) / 2;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int code = menu.cell(dx, dz);
                if (code <= 0 || code >= CELL_COLOURS.length) {
                    continue;
                }
                int left = x + offset + (dx + radius) * cell;
                int top = y + offset + (dz + radius) * cell;
                int gap = cell > 3 ? 1 : 0;
                graphics.fill(left, top, left + cell - gap, top + cell - gap, CELL_COLOURS[code]);
            }
        }
        int left = x + offset + radius * cell;
        int top = y + offset + radius * cell;
        graphics.renderOutline(left - 1, top - 1, cell + 1, cell + 1, 0xFFFFFFFF);
    }

    /** The fluid's still texture, tinted, filling the tank from the bottom; then the gauge marks over it. */
    private void renderTank(GuiGraphics graphics, FluidStack fluid, int x, int y) {
        int height = scaled(fluid.getAmount(), ReclaimerBlockEntity.TANK_CAPACITY, GAUGE_HEIGHT);
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

    private void renderPanelTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        List<Component> lines = new ArrayList<>();
        if (inside(localX, localY, ENERGY_X, GAUGE_Y, ENERGY_WIDTH, GAUGE_HEIGHT)) {
            lines.add(Component.translatable("gui.remission.machine.energy", menu.energy(), menu.capacity()));
        } else if (inside(localX, localY, TANK_X, GAUGE_Y, TANK_WIDTH, GAUGE_HEIGHT)) {
            FluidStack fluid = menu.fluid();
            lines.add(Component.translatable("gui.remission.reclaimer.fuel_tank"));
            lines.add(fluid.isEmpty()
                    ? Component.translatable("gui.remission.machine.tank_empty")
                    : Component.translatable("gui.remission.machine.tank", fluid.getHoverName(), fluid.getAmount(),
                            ReclaimerBlockEntity.TANK_CAPACITY));
        } else if (inside(localX, localY, ReclaimerMenu.FUEL_X + 18, ReclaimerMenu.FUEL_Y, 36, 16) && !hoveredSlotHasItem()) {
            lines.add(Component.translatable("gui.remission.reclaimer.fuel", menu.fuel()));
        } else if (inside(localX, localY, TEXT_X, STATUS_Y, TEXT_WIDTH, REACH_Y + 9 - STATUS_Y)) {
            lines.add(Component.translatable("gui.remission.reclaimer.reclaimed", menu.reclaimed(), menu.area()));
            lines.add(Component.translatable("gui.remission.reclaimer.festering", menu.festering()));
            lines.add(Component.translatable("gui.remission.reclaimer.fuel", menu.fuel()));
        } else if (inside(localX, localY, MAP_X, MAP_Y, MAP_SIZE, MAP_SIZE)) {
            int side = 2 * menu.radius() + 1;
            lines.add(Component.translatable("gui.remission.reclaimer.reach", side, side));
            Component cell = hoveredCell(localX - MAP_X, localY - MAP_Y);
            if (cell != null) {
                lines.add(cell);
            }
        }
        if (!lines.isEmpty()) {
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private boolean hoveredSlotHasItem() {
        return hoveredSlot != null && hoveredSlot.hasItem();
    }

    @Nullable
    private Component hoveredCell(int mapX, int mapY) {
        int radius = Math.min(menu.radius(), ReclaimerSettings.MAX_RADIUS);
        int side = 2 * radius + 1;
        int cell = MAP_SIZE / side;
        int offset = (MAP_SIZE - cell * side) / 2;
        if (cell <= 0 || mapX < offset || mapY < offset || mapX >= offset + cell * side || mapY >= offset + cell * side) {
            return null;
        }
        int code = menu.cell((mapX - offset) / cell - radius, (mapY - offset) / cell - radius);
        return switch (code) {
            case ReclaimerMenu.Cell.UNLOADED -> Component.translatable("gui.remission.reclaimer.cell.unloaded");
            case ReclaimerMenu.Cell.CLEAN -> Component.translatable("gui.remission.reclaimer.cell.clean");
            case ReclaimerMenu.Cell.REMISSION -> Component.translatable("gui.remission.reclaimer.cell.remission");
            case ReclaimerMenu.Cell.NIDUS -> Component.translatable("gui.remission.reclaimer.cell.nidus");
            default -> code >= ReclaimerMenu.Cell.STAGE_1 && code <= ReclaimerMenu.Cell.STAGE_4
                    ? Component.translatable("gui.remission.reclaimer.cell.festering", code - ReclaimerMenu.Cell.STAGE_1 + 1)
                    : null;
        };
    }

    private static Component statusText(ReclaimerBlockEntity.Status status) {
        return Component.translatable("gui.remission.reclaimer.status." + status.name().toLowerCase(Locale.ROOT));
    }

    private static int statusColour(ReclaimerBlockEntity.Status status) {
        return switch (status) {
            case RUNNING -> 0x1F7A55;
            case CLEAR -> TEXT_COLOUR;
            case HELD -> 0x8A1C2E;
            case NO_ENERGY, NO_FUEL -> 0xA05A00;
        };
    }

    private static boolean inside(int x, int y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static int scaled(int value, int max, int size) {
        return max <= 0 || value <= 0 ? 0 : Math.min(size, Math.max(1, (int) ((long) value * size / max)));
    }
}
