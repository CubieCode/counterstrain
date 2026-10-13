package com.cubiecode.remission.client.research;

import com.cubiecode.remission.api.StrainHazards;
import com.cubiecode.remission.client.notice.HazardNoticeDisplay;
import com.cubiecode.remission.network.HazardClientCache;
import com.cubiecode.remission.network.HazardNoticePayload;
import com.cubiecode.remission.network.HazardStatusPayload;
import com.cubiecode.remission.network.HazardStatusRequests;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The bestiary's hazards tab (P5.8): the hazards active for the local player and the countermeasures their team holds
 * (from the server's {@code remission:hazard_status}, asked for each time the player switches to the tab) and the recent
 * hazard notices
 * of this session ({@link HazardClientCache}), muted ones marked. Read-only.
 */
final class HazardsTab {
    private static final int ROW_HEIGHT = 22;
    private static final FormattedText ELLIPSIS = FormattedText.of("...");
    private static boolean listening;

    private HazardsTab() {
    }

    /** The player switched to the hazards tab: asks the server for the status, once per switch. */
    static void opened(Minecraft minecraft) {
        if (minecraft.getConnection() != null) {
            HazardStatusRequests.tabOpened();
        }
    }

    /**
     * Fills the bestiary layout's header and contents for the hazards tab from the cached data. Never asks the server:
     * it also runs on every rebuild, including the one a status answer causes.
     */
    static HazardList init(BestiaryScreen screen, Minecraft minecraft, Font font, HeaderAndFooterLayout layout) {
        if (!listening) {
            listening = true;
            HazardClientCache.addListener(new HazardClientCache.Listener() {
                @Override
                public void notice(HazardNoticePayload notice) {
                    refresh();
                }

                @Override
                public void status(HazardStatusPayload status) {
                    refresh();
                }
            });
        }
        HazardStatusPayload status = HazardClientCache.status();
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(6));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(screen.getTitle(), font));
        Component summary = status == null
                ? Component.translatable("gui.remission.bestiary.hazards.loading")
                : Component.translatable("gui.remission.bestiary.hazards.summary", status.active().size(), status.held().size());
        header.addChild(new StringWidget(summary.copy().withStyle(ChatFormatting.GRAY), font));
        return layout.addToContents(new HazardList(minecraft, screen, layout, font, status, HazardClientCache.notices()));
    }

    /** Rebuilds an open hazards tab when new hazard data arrives. */
    private static void refresh() {
        if (Minecraft.getInstance().screen instanceof BestiaryScreen screen && screen.showsHazards()) {
            screen.refresh();
        }
    }

    static final class HazardList extends ObjectSelectionList<Row> {
        HazardList(Minecraft minecraft, BestiaryScreen screen, HeaderAndFooterLayout layout, Font font,
                @Nullable HazardStatusPayload status, List<HazardNoticePayload> notices) {
            super(minecraft, screen.width, layout.getContentHeight(), layout.getHeaderHeight(), ROW_HEIGHT);
            if (status != null) {
                addEntry(Row.heading(font, "gui.remission.bestiary.hazards.active"));
                addHazards(font, status.active(), Component.translatable("gui.remission.bestiary.hazards.state.active"),
                        0xFF5555);
                addEntry(Row.heading(font, "gui.remission.bestiary.hazards.held"));
                addHazards(font, status.held(), Component.translatable("gui.remission.bestiary.hazards.state.held"),
                        0x55FF55);
            }
            addEntry(Row.heading(font, "gui.remission.bestiary.hazards.notices"));
            if (notices.isEmpty()) {
                addEntry(Row.none(font));
            }
            ResourceKey<Level> current = minecraft.player == null ? null : minecraft.player.level().dimension();
            for (HazardNoticePayload notice : notices) {
                boolean muted = HazardNoticeDisplay.muted(notice.hazard());
                addEntry(new Row(font, false, HazardNoticeDisplay.message(notice, current),
                        muted ? Component.translatable("gui.remission.bestiary.hazards.muted") : null, null,
                        muted ? 0x808080 : 0xFFAA00, 0));
            }
        }

        private void addHazards(Font font, List<ResourceLocation> hazards, Component state, int stateColour) {
            if (hazards.isEmpty()) {
                addEntry(Row.none(font));
            }
            for (ResourceLocation hazard : hazards) {
                // The id below the name, for the mute list; a hazard with no name already shows its id.
                boolean named = I18n.exists("hazard." + hazard.getNamespace() + "." + hazard.getPath());
                addEntry(new Row(font, false, StrainHazards.displayName(hazard),
                        named ? Component.literal(hazard.toString()) : null, state, 0xFFFFFF, stateColour));
            }
        }

        @Override
        public int getRowWidth() {
            return 300;
        }
    }

    /**
     * One line: a section heading, a hazard (name, its id below it, its state on the right), a notice (its text, "Muted"
     * below it when muted) or "None". Text too long for the row ends in an ellipsis.
     */
    static final class Row extends ObjectSelectionList.Entry<Row> {
        private final Font font;
        private final boolean heading;
        private final Component text;
        @Nullable
        private final Component detail;
        @Nullable
        private final Component state;
        private final int colour;
        private final int stateColour;

        Row(Font font, boolean heading, Component text, @Nullable Component detail, @Nullable Component state, int colour,
                int stateColour) {
            this.font = font;
            this.heading = heading;
            this.text = text;
            this.detail = detail;
            this.state = state;
            this.colour = colour;
            this.stateColour = stateColour;
        }

        static Row heading(Font font, String key) {
            return new Row(font, true, Component.translatable(key), null, null, 0xFFFFFF, 0);
        }

        static Row none(Font font) {
            return new Row(font, false, Component.translatable("gui.remission.bestiary.hazards.none"), null, null,
                    0x808080, 0);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            int textLeft = heading ? left + 2 : left + 10;
            int stateWidth = state == null ? 0 : font.width(state) + 8;
            int room = width - (textLeft - left) - stateWidth - 4;
            FormattedCharSequence line = font.width(text) <= room ? text.getVisualOrderText()
                    : Language.getInstance().getVisualOrder(FormattedText.composite(
                            font.substrByWidth(text, room - font.width(ELLIPSIS)), ELLIPSIS));
            graphics.drawString(font, line, textLeft, top + (detail == null ? 6 : 2), colour);
            if (detail != null) {
                graphics.drawString(font, detail, textLeft, top + 12, 0x606060);
            }
            if (state != null) {
                graphics.drawString(font, state, left + width - 4 - font.width(state), top + 6, stateColour);
            }
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select", text);
        }
    }
}
