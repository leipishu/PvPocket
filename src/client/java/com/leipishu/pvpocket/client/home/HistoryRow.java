package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * One finished series in the history list: score, outcome chip, map line and round strip.
 *
 * <p>The row is 42px tall and draws a 38px card with two text lines and a leading accent bar. The
 * outcome is carried by the accent colour and a chip, never by red or green.
 *
 * <p>{@code onClick} runs on a left click inside the row. A click outside it is ignored.
 */
public class HistoryRow extends PvWidget {

    private static final int H = 42;
    /** Room a round dot needs, including its gap. */
    private static final int DOT_PITCH = 8;
    private static final int MAX_DOTS = 12;
    /** Least the map line may claim before the round strip is dropped: enough for the map name. */
    private static final int MIN_META = 56;

    private final HomeState.History record;
    private final java.util.List<HomeState.Round> rounds;
    private final Runnable onClick;
    private float hotT;

    public HistoryRow(HomeState.History record, Runnable onClick) {
        this.record = record;
        // Rounds are parsed once here and reused by every render pass.
        this.rounds = record.roundList();
        this.onClick = onClick;
        this.height = H;
        this.tooltip = PvLang.str("pvpocket.gui.home.history.tooltip");
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        hotT = PvAnim.damp(hotT, hovered ? 1F : 0F, d, 0.09F);
        Font tr = Minecraft.getInstance().font;

        int h = H - 4;
        PvDraw.roundPanel(ctx, x, y, width, h, PvTheme.RADIUS,
                PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, hotT),
                PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, hotT));

        // Two-pixel leading bar: accent when the series was won, plain border when lost.
        PvDraw.roundRect(ctx, x + 1, y + 7, 2, h - 14, 1,
                record.won() ? PvTheme.ACCENT : PvTheme.BORDER);

        int textX = x + 10;
        int line1 = y + 6;
        int line2 = y + 22;

        String score = record.myScore() + " : " + record.theirScore();
        // Each run reports the width it covered; the next run starts after it.
        int scoreW = PvDraw.textFitLeft(ctx, tr, score, textX, line1, width / 2, PvTheme.TEXT);

        String chip = PvLang.str(record.won()
                ? "pvpocket.gui.home.result.win" : "pvpocket.gui.home.result.loss");
        int chipW = tr.width(chip) + 10;
        int chipX = textX + scoreW + 8;
        PvDraw.roundRect(ctx, chipX, line1 - 2, chipW, 12, 3,
                record.won() ? PvTheme.ACCENT_SOFT : PvTheme.CARD_ACTIVE);
        PvDraw.text(ctx, tr, chip, chipX + 5, line1,
                record.won() ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);

        // The map line claims its floor width first and the strip takes whole dots from the
        // remainder. The strip is not drawn when no remainder is left.
        String full = metaLine();
        int metaX = chipX + chipW + 8;
        int metaFull = x + width - 10 - metaX;
        int metaRoom = Math.min(metaFull, Math.max(MIN_META, floorWidth(tr, full)));
        int dots = Math.max(0, Math.min(Math.min(MAX_DOTS, rounds.size()),
                (metaFull - metaRoom - 8) / DOT_PITCH));
        int dotsX = x + width - 10 - dots * DOT_PITCH;
        if (dots > 0) {
            drawDots(ctx, dotsX, line1 + 2);
        }
        int mapRoom = Math.max(8, (dots > 0 ? dotsX - 8 : x + width - 10) - metaX);
        // Rebuilt for the room measured above, so qualifiers drop one at a time.
        String shown = metaLine(full, mapRoom);
        boolean metaShort = !shown.equals(full) || PvDraw.willCut(tr, shown, mapRoom, 1);
        PvDraw.textFitLeft(ctx, tr, shown, metaX, line1, mapRoom, PvTheme.TEXT_DIM);

        String who = record.opponents().isEmpty()
                ? PvLang.str("pvpocket.gui.home.history.solo")
                : PvLang.str("pvpocket.gui.home.history.vs", record.opponents());
        String second = HomeText.stamp(record.t()) + " · " + who;
        // Player names are unbounded; this line shrinks to the floor before it is cut.
        PvDraw.textFitLeft(ctx, tr, second, textX, line2, width - 20, PvTheme.TEXT_MUTED);
        boolean secondCut = PvDraw.willCut(tr, second, width - 20, 1);
        // The hover tooltip carries the text the row could not show.
        overflowTip(metaShort ? full : second, metaShort || secondCut);
    }

    /**
     * Draws one six-pixel square per round, left to right, at most {@code MAX_DOTS} of them.
     *
     * <p>{@code startX} is the left edge of the first square, {@code top} its top edge. The strip
     * stops at the cap without any marker.
     */
    private void drawDots(GuiGraphics ctx, int startX, int top) {
        int i = 0;
        for (HomeState.Round round : rounds) {
            if (i >= MAX_DOTS) {
                break;
            }
            PvDraw.roundRect(ctx, startX + i * DOT_PITCH, top, 6, 6, 1,
                    round.won() ? PvTheme.ACCENT : PvTheme.CARD_ACTIVE);
            i++;
        }
    }

    private String teamsLabel() {
        int perSide = Math.max(1, record.teamSize());
        if (record.teams() <= 2) {
            return perSide + "v" + perSide;
        }
        return record.teams() + "v" + record.teams();
    }

    private String metaLine() {
        return record.mapLabel()
                + " · " + PvLang.str(record.mode().equals("legacy")
                ? "pvpocket.gui.home.mode.legacy" : "pvpocket.gui.home.mode.modern")
                + " · " + teamsLabel();
    }

    /**
     * Returns the meta line rebuilt from the parts that fit in {@code room}.
     *
     * <p>Falls back from the full line to map plus teams, then to the map label alone. The map
     * label is always returned, whatever the room.
     */
    private String metaLine(String full, int room) {
        String map = record.mapLabel();
        String teams = teamsLabel();
        if (fits(full, room)) {
            return full;
        }
        String brief = map + " · " + teams;
        return fits(brief, room) ? brief : map;
    }

    private boolean fits(String s, int room) {
        return floorWidth(net.minecraft.client.Minecraft.getInstance().font, s) <= room;
    }

    /** Width a string needs at the legibility floor, where every word is still visible. */
    private static int floorWidth(Font tr, String s) {
        return Math.round(tr.width(s) * PvDraw.MIN_TEXT_SCALE);
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }
        // The full map line takes priority over the row's own tooltip.
        return overflow() != null ? overflow() : tooltip;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0 || !contains(mx, my)) {
            return false;
        }
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1F));
        if (onClick != null) {
            onClick.run();
        }
        return true;
    }
}
