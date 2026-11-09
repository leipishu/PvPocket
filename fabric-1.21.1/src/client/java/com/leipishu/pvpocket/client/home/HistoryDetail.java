package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * The detail dialog behind one history row: per-round outcomes, who was on which side, and a way to
 * take the result somewhere else.
 *
 * <p>A modal on {@code PvScreen.overlay} rather than a second screen, for the reason the base class
 * documents: the overlay is drawn last <em>and</em> hit-tested first, so a click on the scrim closes
 * the dialog instead of pressing the row underneath it.</p>
 *
 * <p>Rounds are laid out in two columns. A series can run to fifteen rounds and a single column of
 * them does not fit a 232px card; two columns of eight do, and the reading order (down, then across)
 * matches the order they were played.</p>
 */
public class HistoryDetail extends PvWidget {

    private static final int CARD_MAX_W = 360;
    private static final int CARD_MAX_H = 232;
    private static final int ROW_H = 12;
    private static final int PER_COLUMN = 8;

    private final HomeState.History record;
    private final List<HomeState.Round> rounds;
    private final PvButton copy;
    private final PvButton close;

    public HistoryDetail(HomeState.History record, Runnable onClose) {
        this.record = record;
        this.rounds = record.roundList();
        this.copy = new PvButton(PvLang.str("pvpocket.gui.home.detail.copy"),
                PvButton.Style.NORMAL, this::copyToClipboard).size(60, PvTheme.BTN_H);
        this.close = new PvButton(PvLang.str("pvpocket.gui.home.detail.close"),
                PvButton.Style.GHOST, onClose).size(48, PvTheme.BTN_H);
    }

    private int cardX() {
        return x + (width - cardW()) / 2;
    }

    private int cardY() {
        return y + (height - cardH()) / 2;
    }

    private int cardW() {
        return Math.min(CARD_MAX_W, width - 20);
    }

    private int cardH() {
        return Math.min(CARD_MAX_H, height - 20);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        // The scrim is the dialog's own: the overlay fills the viewport, and a dialog that draws only
        // its card leaves the panel behind it looking pressable.
        PvDraw.rect(ctx, x, y, width, height, PvTheme.alpha(PvTheme.SCRIM, 0.85F));

        int cx = cardX();
        int cy = cardY();
        int cw = cardW();
        int ch = cardH();
        PvDraw.roundPanel(ctx, cx, cy, cw, ch, PvTheme.RADIUS_PANEL,
                PvTheme.BORDER, PvTheme.PANEL);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.detail.title"), cx + 16, cy + 12,
                PvTheme.TEXT);

        String meta = record.mapLabel() + " · "
                + PvLang.str(record.mode().equals("legacy")
                ? "pvpocket.gui.home.mode.legacy" : "pvpocket.gui.home.mode.modern")
                + " · " + HomeText.stamp(record.t()) + " · " + HomeText.duration(record.duration());
        PvDraw.textFitLeft(ctx, tr, meta, cx + 16, cy + 28, cw - 32, PvTheme.TEXT_DIM);

        String score = record.myScore() + " : " + record.theirScore();
        PvDraw.text(ctx, tr, score, cx + 16, cy + 44, PvTheme.TEXT);
        String verdict = PvLang.str(record.won()
                ? "pvpocket.gui.home.result.win" : "pvpocket.gui.home.result.loss");
        int verdictW = tr.getWidth(verdict) + 10;
        int verdictX = cx + 16 + tr.getWidth(score) + 8;
        PvDraw.roundRect(ctx, verdictX, cy + 42, verdictW, 12, 3,
                record.won() ? PvTheme.ACCENT_SOFT : PvTheme.CARD_ACTIVE);
        PvDraw.text(ctx, tr, verdict, verdictX + 5, cy + 44,
                record.won() ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);

        PvDraw.hLine(ctx, cx + 16, cy + 60, cw - 32, PvTheme.DIVIDER);

        int colW = (cw - 32 - 12) / 2;
        // The round list is the only part of this card whose height is not fixed, so it is the part
        // that has to be measured against what is left: title, meta line, score, two dividers, the
        // sides line and the button row are all pinned to the card, and on the smallest canvas
        // (320x180, where the card is 160 tall) a fixed eight rows per column ran straight through
        // the sides line and the buttons. Two columns of what fits, and a count of the rest.
        int listTop = cy + 68;
        int listBottom = cy + ch - 52 - 8;
        int rowsPerColumn = Math.max(1, Math.min(PER_COLUMN, (listBottom - listTop) / ROW_H));
        int capacity = rowsPerColumn * 2;
        boolean truncated = rounds.size() > capacity;
        // When it does not all fit, the last slot says how much is missing instead of being spent on
        // a round: a detail view that silently drops the last four rounds of a series is worse than
        // one that admits to it.
        int shown = truncated ? capacity - 1 : rounds.size();
        // The label column is measured, not assumed. "Round 10" is half again as wide as "Round 1",
        // and a fixed 34px offset put the result straight through the last digit on the tenth round —
        // which is exactly the round a long series reaches.
        int labelW = 0;
        for (int i = 0; i < shown; i++) {
            labelW = Math.max(labelW, tr.getWidth(PvLang.str("pvpocket.gui.home.detail.round", i + 1)));
        }
        for (int i = 0; i < shown; i++) {
            int col = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            HomeState.Round round = rounds.get(i);
            int rx = cx + 16 + col * (colW + 12);
            int ry = listTop + row * ROW_H;
            String label = PvLang.str("pvpocket.gui.home.detail.round", i + 1);
            PvDraw.text(ctx, tr, label, rx, ry, PvTheme.TEXT_MUTED);
            String result = PvLang.str(round.won()
                    ? "pvpocket.gui.home.result.win" : "pvpocket.gui.home.result.loss");
            int resultX = rx + labelW + 6;
            PvDraw.text(ctx, tr, result, resultX, ry,
                    round.won() ? PvTheme.ACCENT : PvTheme.TEXT_DIM);
            // The reason is clipped to what is left rather than right-aligned over the result: two
            // strings drawn towards each other from opposite edges overlap in the middle, and which
            // round that happens on depends on the language.
            String tail = reasonLabel(round.reason()) + "  " + HomeText.duration(round.ms());
            int tailRoom = rx + colW - (resultX + tr.getWidth(result)) - 6;
            if (tailRoom > 8) {
                PvDraw.textRight(ctx, tr, PvDraw.fit(tr, tail, tailRoom), rx + colW, ry,
                        PvTheme.TEXT_MUTED);
            }
        }
        if (truncated) {
            int rx = cx + 16 + (shown / rowsPerColumn) * (colW + 12);
            int ry = listTop + (shown % rowsPerColumn) * ROW_H;
            PvDraw.text(ctx, tr, PvDraw.fit(tr, PvLang.str("pvpocket.gui.home.detail.more",
                    rounds.size() - shown), colW), rx, ry, PvTheme.TEXT_MUTED);
        }

        int peopleY = cy + ch - 52;
        PvDraw.hLine(ctx, cx + 16, peopleY - 6, cw - 32, PvTheme.DIVIDER);
        String sides = record.teamMates().isEmpty()
                ? PvLang.str("pvpocket.gui.home.history.solo")
                : PvLang.str("pvpocket.gui.home.detail.sides", record.teamMates(), record.opponents());
        // Both sides' names on one line: unbounded input in a 360px card.
        PvDraw.textFitLeft(ctx, tr, sides, cx + 16, peopleY, cw - 32, PvTheme.TEXT_DIM);

        int by = cy + ch - 28;
        copy.x = cx + 16;
        copy.y = by;
        copy.visible = visible;
        close.x = cx + cw - 16 - close.width;
        close.y = by;
        close.visible = visible;
        copy.render(ctx, mouseX, mouseY, delta);
        close.render(ctx, mouseX, mouseY, delta);
    }

    private static String reasonLabel(String reason) {
        if (reason == null || reason.isEmpty()) {
            return PvLang.str("pvpocket.gui.home.reason.none");
        }
        String key = "pvpocket.gui.home.reason." + reason.toLowerCase(java.util.Locale.ROOT);
        String text = PvLang.str(key);
        return text.equals(key) ? reason : text;
    }

    /**
     * Copies a one-line summary for pasting into chat.
     *
     * <p>Not the full detail: the point of copying a result is to show it to somebody, and a block of
     * per-round lines pasted into a chat window is unreadable. The line carries what a reader needs to
     * know who won, on what, and by how much.</p>
     */
    private void copyToClipboard() {
        String line = "PvPocket · " + record.mapLabel() + " · "
                + record.myScore() + ":" + record.theirScore() + " "
                + PvLang.str(record.won()
                ? "pvpocket.gui.home.result.win" : "pvpocket.gui.home.result.loss")
                + (record.opponents().isEmpty() ? "" : " · vs " + record.opponents());
        MinecraftClient.getInstance().keyboard.setClipboard(line);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (copy.visible && copy.mouseClicked(mx, my, button)) {
            return true;
        }
        if (close.visible && close.mouseClicked(mx, my, button)) {
            return true;
        }
        // Anywhere else on the scrim is a dismiss: a dialog that requires hitting a 48px button to
        // leave is a dialog that traps a player who does not know Esc works.
        return contains(mx, my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = copy.mouseReleased(mx, my, button);
        handled |= close.mouseReleased(mx, my, button);
        return handled;
    }

    @Override
    public List<PvWidget> childrenForAudit() {
        return List.of(copy, close);
    }
}
