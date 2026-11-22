package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.session.RosterShape;
import com.leipishu.pvpocket.session.VoteItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The arrangement item on the ballot: one card grid per candidate.
 *
 * <p>An arrangement is voted on whole, so the candidates are stacked rather than turned into chips:
 * two rosters cannot be told apart from a label, and the point of the item is that voters compare
 * them. Clicking anywhere in a candidate's block selects it.
 */
public class RosterVoteRow extends PvWidget {

    private static final int PAD = 8;
    private static final int TITLE_H = 12;
    private static final int HEADER_H = 12;
    private static final int BLOCK_PAD = 6;
    private static final int BLOCK_GAP = 6;

    private final VoteItem item;
    private final Map<String, String> names;
    private final IntConsumer onVote;
    private final List<List<List<String>>> sides = new ArrayList<>();
    private final List<String> headers = new ArrayList<>();
    /** Candidate rectangles relative to the row, filled by {@link #onWidth}. */
    private final List<int[]> blocks = new ArrayList<>();

    private int selected;

    public RosterVoteRow(VoteItem item, Map<String, String> names, int selected,
                         IntConsumer onVote) {
        this.item = item;
        this.names = names;
        this.selected = selected;
        this.onVote = onVote;
        for (int i = 0; i < item.options.size(); i++) {
            List<List<String>> decoded = RosterShape.decode(item.options.get(i).value);
            sides.add(decoded == null ? List.of() : decoded);
            headers.add(headerOf(i));
        }
        this.height = TITLE_H + PAD;
    }

    public void setSelected(int index) {
        this.selected = index;
    }

    @Override
    public boolean focusable() {
        return false;
    }

    @Override
    public void onWidth(int width) {
        layout();
    }

    private void layout() {
        blocks.clear();
        int inner = Math.max(60, width - PAD * 2);
        int y = TITLE_H;
        for (List<List<String>> option : sides) {
            int cards = RosterCards.height(option, inner - BLOCK_PAD * 2);
            blocks.add(new int[]{PAD, y, inner, HEADER_H + cards + BLOCK_PAD * 2});
            y += HEADER_H + cards + BLOCK_PAD * 2 + BLOCK_GAP;
        }
        height = y + PAD - BLOCK_GAP;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        tickHover(dt(delta));
        Font tr = Minecraft.getInstance().font;

        String title = PvLang.str("pvpocket.gui.vote.roster_title");
        String summary = PvLang.str("pvpocket.gui.vote.roster_summary", sides.isEmpty()
                ? 0 : sides.get(0).size());
        int summaryW = tr.width(summary);
        PvDraw.text(ctx, tr, PvDraw.fit(tr, title, width - PAD * 2 - summaryW - 10),
                x + PAD, y + 2, PvTheme.TEXT);
        PvDraw.textRight(ctx, tr, summary, x + width - PAD, y + 2, PvTheme.TEXT_MUTED);

        for (int i = 0; i < blocks.size(); i++) {
            int[] block = blocks.get(i);
            int bx = x + block[0];
            int by = y + block[1];
            boolean on = i == selected;
            PvDraw.roundPanel(ctx, bx, by, block[2], block[3], PvTheme.RADIUS,
                    on ? PvTheme.ACCENT_DIM : PvTheme.BORDER,
                    on ? PvTheme.ACCENT_SOFT : PvTheme.CARD);
            PvDraw.textFitLeft(ctx, tr, headers.get(i), bx + BLOCK_PAD, by + 3,
                    block[2] - BLOCK_PAD * 2, on ? PvTheme.ACCENT : PvTheme.TEXT_DIM);
            RosterCards.draw(ctx, tr, sides.get(i), names, 0, bx + BLOCK_PAD,
                    by + HEADER_H, block[2] - BLOCK_PAD * 2);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled) {
            return false;
        }
        for (int i = 0; i < blocks.size(); i++) {
            int[] block = blocks.get(i);
            if (mx >= x + block[0] && mx < x + block[0] + block[2]
                    && my >= y + block[1] && my < y + block[1] + block[3]) {
                selected = i;
                onVote.accept(i);
                return true;
            }
        }
        return false;
    }

    /** The base chip reads "keep as is"; a candidate chip reads the proposers' names. */
    private String headerOf(int index) {
        if (index == 0) {
            return PvLang.str("pvpocket.gui.vote.keep_plain");
        }
        String names = String.join("、", item.options.get(index).proposers);
        return names.isEmpty() ? PvLang.str("pvpocket.gui.vote.keep_plain") : names;
    }
}
