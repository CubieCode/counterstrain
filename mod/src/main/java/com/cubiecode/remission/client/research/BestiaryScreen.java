package com.cubiecode.remission.client.research;

import com.cubiecode.remission.research.Bestiary;
import com.cubiecode.remission.research.BestiaryProgress;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.fml.ModList;

/**
 * Read-only list of every host type ({@link Bestiary#hostTypes()}) with the local player's synced scan progress. Scanned
 * types come first, then the rest; each group sorted by mod, then name.
 */
public class BestiaryScreen extends Screen {
    private static final int HEADER_HEIGHT = 48;
    private static final int ROW_HEIGHT = 24;

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, 33);
    private HostList list;

    public BestiaryScreen() {
        super(Component.translatable("gui.remission.bestiary.title"));
    }

    @Override
    protected void init() {
        BestiaryProgress progress = minecraft.player == null ? BestiaryProgress.EMPTY : Bestiary.get(minecraft.player);
        List<HostEntry> entries = Bestiary.hostTypes().stream()
                .map(type -> new HostEntry(type, BuiltInRegistries.ENTITY_TYPE.getKey(type), progress))
                .sorted(Comparator.comparing((HostEntry e) -> e.stage == 0)
                        .thenComparing(e -> e.id.getNamespace().equals("minecraft") ? "" : e.id.getNamespace())
                        .thenComparing(e -> e.name.getString()))
                .toList();
        long scanned = entries.stream().filter(e -> e.stage > 0).count();

        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(6));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font));
        header.addChild(new StringWidget(Component.translatable("gui.remission.bestiary.progress", scanned, entries.size())
                .withStyle(ChatFormatting.GRAY), font));
        list = layout.addToContents(new HostList(minecraft, entries));
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) {
            list.updateSize(width, layout);
        }
    }

    private class HostList extends ObjectSelectionList<HostEntry> {
        HostList(Minecraft minecraft, List<HostEntry> entries) {
            super(minecraft, BestiaryScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), ROW_HEIGHT);
            entries.forEach(this::addEntry);
        }

        @Override
        public int getRowWidth() {
            return 260;
        }
    }

    private class HostEntry extends ObjectSelectionList.Entry<HostEntry> {
        private final ResourceLocation id;
        private final Component name;
        private final Component source;
        private final int stage;
        private final ItemStack icon;

        HostEntry(EntityType<?> type, ResourceLocation id, BestiaryProgress progress) {
            this.id = id;
            this.name = type.getDescription();
            this.source = Component.literal(ModList.get().getModContainerById(id.getNamespace())
                    .map(container -> container.getModInfo().getDisplayName()).orElse(id.getNamespace()));
            this.stage = progress.highestStage(id);
            SpawnEggItem egg = SpawnEggItem.byId(type);
            this.icon = egg == null ? ItemStack.EMPTY : new ItemStack(egg);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            boolean scanned = stage > 0;
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, left + 2, top + 2);
            }
            int textLeft = left + 24;
            graphics.drawString(font, name, textLeft, top + 2, scanned ? 0xFFFFFF : 0x808080);
            graphics.drawString(font, source, textLeft, top + 12, 0x606060);
            Component status = scanned
                    ? Component.translatable("gui.remission.bestiary.scanned", stage)
                    : Component.translatable("gui.remission.bestiary.unscanned");
            graphics.drawString(font, status, left + width - 4 - font.width(status), top + 2,
                    scanned ? 0x55FF55 : 0x808080);
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select", name);
        }
    }
}
