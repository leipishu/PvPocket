package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.rules.RuleCatalog;
import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.session.VoteItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * One ballot item: what main has on the first line, and every candidate as a chip below it.
 *
 * <p>The chips wrap, so the row's height is decided from its width rather than fixed. A rule shows
 * formatted values; a loadout row shows what each candidate changes, because two whole rows side
 * by side say nothing at a glance.
 *
 * <p>Clicking a chip casts that vote immediately. There is no confirm button: the footer counts the
 * players who have finished, so a vote that is still changeable should not look like a decision.
 */
public class VoteRow extends PvWidget {

    private static final int PAD = 8;
    private static final int TOP = 5;
    private static final int TITLE_H = 9;
    private static final int CHIP_H = 14;
    private static final int CHIP_GAP = 4;
    private static final int LINE_GAP = 3;
    private static final int BOTTOM = 5;

    private final VoteItem item;
    private final String title;
    private final String base;
    private final List<String> labels = new ArrayList<>();
    /** Chip rectangles relative to the row, filled by {@link #onWidth}. */
    private final List<int[]> chips = new ArrayList<>();
    private final IntConsumer onVote;
    /** Votes per option on the result page; null on the ballot, where counts are not shown. */
    private final int[] counts;
    private final boolean readOnly;

    private int selected;

    public VoteRow(VoteItem item, int selected, IntConsumer onVote) {
        this(item, selected, null, false, onVote);
    }

    public VoteRow(VoteItem item, int selected, int[] counts, boolean readOnly,
                   IntConsumer onVote) {
        this.item = item;
        this.selected = selected;
        this.counts = counts;
        this.readOnly = readOnly;
        this.onVote = onVote;
        this.title = titleOf(item);
        this.base = baseOf(item);
        for (int i = 0; i < item.options.size(); i++) {
            String label = labelOf(item, i);
            if (counts != null && i < counts.length) {
                label = PvLang.str("pvpocket.gui.vote.with_votes", label, counts[i]);
            }
            labels.add(label);
        }
        this.height = measure(1);
    }

    public void setSelected(int index) {
        this.selected = index;
    }

    /** Chips are pointer-only; making the row a tab stop would give keyboard users a dead end. */
    @Override
    public boolean focusable() {
        return false;
    }

    @Override
    public void onWidth(int width) {
        layout();
    }

    private void layout() {
        chips.clear();
        Font tr = Minecraft.getInstance().font;
        int x0 = PAD;
        int limit = Math.max(40, width - PAD);
        int cx = x0;
        int cy = TOP + TITLE_H + LINE_GAP;
        int lines = 1;
        for (String label : labels) {
            int w = tr.width(label) + 12;
            if (cx > x0 && cx + w > limit) {
                cx = x0;
                cy += CHIP_H + LINE_GAP;
                lines++;
            }
            chips.add(new int[]{cx, cy, Math.min(w, limit - x0), CHIP_H});
            cx += w + CHIP_GAP;
        }
        height = measure(lines);
    }

    private int measure(int lines) {
        return TOP + TITLE_H + LINE_GAP + lines * CHIP_H + Math.max(0, lines - 1) * LINE_GAP + BOTTOM;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        Font tr = Minecraft.getInstance().font;
        if (hoverT > 0.01F) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS,
                    PvTheme.alpha(PvTheme.CARD_HOVER, hoverT * 0.6F));
        }

        int titleY = y + TOP;
        int baseW = base.isEmpty() ? 0 : tr.width(base);
        PvDraw.text(ctx, tr, PvDraw.fit(tr, title, width - PAD * 2 - baseW - 12), x + PAD,
                titleY, PvTheme.TEXT);
        if (!base.isEmpty()) {
            PvDraw.textRight(ctx, tr, base, x + width - PAD, titleY, PvTheme.TEXT_MUTED);
        }

        for (int i = 0; i < chips.size() && i < labels.size(); i++) {
            int[] chip = chips.get(i);
            int cx = x + chip[0];
            int cy = y + chip[1];
            boolean on = i == selected;
            boolean hot = hovered && mouseX >= cx && mouseX < cx + chip[2]
                    && mouseY >= cy && mouseY < cy + chip[3];
            int fill = on ? PvTheme.ACCENT_SOFT : hot ? PvTheme.CARD_HOVER : PvTheme.CARD;
            int border = on ? PvTheme.ACCENT_DIM : PvTheme.BORDER;
            int ink = on ? PvTheme.ACCENT : hot ? PvTheme.TEXT : PvTheme.TEXT_DIM;
            PvDraw.roundPanel(ctx, cx, cy, chip[2], chip[3], 3, border, fill);
            PvDraw.textCenter(ctx, tr, PvDraw.fit(tr, labels.get(i), chip[2] - 8),
                    cx + chip[2] / 2, cy + 3, ink);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || readOnly) {
            return false;
        }
        for (int i = 0; i < chips.size(); i++) {
            int[] chip = chips.get(i);
            if (mx >= x + chip[0] && mx < x + chip[0] + chip[2]
                    && my >= y + chip[1] && my < y + chip[1] + chip[3]) {
                selected = i;
                onVote.accept(i);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ text

    private static String titleOf(VoteItem item) {
        if (item.kind == VoteItem.KIND_RULE) {
            RuleDefinition def = RuleCatalog.byId(item.ruleId);
            return def == null ? item.ruleId : def.label();
        }
        LoadoutEntry entry = entryOf(item, item.baseValue());
        if (entry == null) {
            // An addition: main has no row, so the candidate names it.
            for (int i = 1; i < item.options.size(); i++) {
                entry = entryOf(item, item.options.get(i).value);
                if (entry != null) {
                    break;
                }
            }
        }
        return entry == null ? item.id : VoteText.title(entry);
    }

    private static String baseOf(VoteItem item) {
        if (item.kind == VoteItem.KIND_RULE) {
            RuleDefinition def = RuleCatalog.byId(item.ruleId);
            return def == null ? "" : PvLang.str("pvpocket.gui.vote.original",
                    def.format(parse(item.baseValue())));
        }
        if (item.kind == VoteItem.KIND_ADD) {
            return PvLang.str("pvpocket.gui.vote.original_none");
        }
        LoadoutEntry entry = entryOf(item, item.baseValue());
        return entry == null ? "" : PvLang.str("pvpocket.gui.vote.original", VoteText.value(entry));
    }

    private static String labelOf(VoteItem item, int index) {
        VoteItem.Option option = item.options.get(index);
        String names = String.join("、", option.proposers);
        if (item.kind == VoteItem.KIND_RULE) {
            RuleDefinition def = RuleCatalog.byId(item.ruleId);
            String value = def == null ? option.value : def.format(parse(option.value));
            if (index == 0) {
                return PvLang.str("pvpocket.gui.vote.keep", value);
            }
            return names.isEmpty() ? value : value + " · " + names;
        }
        if (index == 0) {
            return PvLang.str(item.kind == VoteItem.KIND_ADD
                    ? "pvpocket.gui.vote.skip"
                    : "pvpocket.gui.vote.keep_plain");
        }
        if (option.value.isEmpty()) {
            return PvLang.str("pvpocket.gui.vote.remove") + (names.isEmpty() ? "" : " · " + names);
        }
        LoadoutEntry candidate = entryOf(item, option.value);
        if (candidate == null) {
            return names;
        }
        if (item.kind == VoteItem.KIND_ADD) {
            return PvLang.str("pvpocket.gui.vote.add", VoteText.title(candidate))
                    + (names.isEmpty() ? "" : " · " + names);
        }
        LoadoutEntry before = entryOf(item, item.baseValue());
        String diff = before == null ? VoteText.title(candidate) : VoteText.diff(before, candidate);
        return names.isEmpty() ? diff : diff + " · " + names;
    }

    private static LoadoutEntry entryOf(VoteItem item, String encoded) {
        return encoded == null || encoded.isEmpty() ? null : LoadoutEntry.decode(encoded);
    }

    private static int parse(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
