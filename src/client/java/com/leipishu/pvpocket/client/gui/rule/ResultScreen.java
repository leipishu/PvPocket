package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.compat.PvScreens;
import com.leipishu.pvpocket.client.gui.PvBadge;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvScrollPane;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.ResultOpenPayload;
import com.leipishu.pvpocket.session.RosterShape;
import com.leipishu.pvpocket.session.VoteItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The result page: what the match will run on, published before the teleport.
 *
 * <p>Three sections in the order the invite showed them: the arrangement as cards, the rules that
 * were voted on, and the loadout rows. Every line is read-only and carries its tally, so the page
 * answers "what was agreed" rather than asking anything.
 *
 * <p>A countdown, not a ready check: everybody has answered, and the page closes itself. Closing it
 * early does not skip the start, and {@code /pvpocket review} brings it back.
 */
public class ResultScreen extends PvScreen {

    private static final int MAX_W = 580;
    private static final int MAX_H = 348;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 18;
    private static final int SECTION_H = 18;

    private final ResultOpenPayload payload;
    private final List<VoteItem> items;
    private final Map<String, Integer> winners = new HashMap<>();
    private final Map<String, int[]> counts = new HashMap<>();
    private final List<List<String>> roster;
    private final Map<String, String> names;
    private final long openedAt = System.currentTimeMillis();

    private PvScrollPane pane;

    public ResultScreen(ResultOpenPayload payload) {
        super(PvLang.t("pvpocket.gui.result.title"));
        this.payload = payload;
        this.items = VoteItem.decodeAll(payload.items());
        this.roster = RosterShape.decode(payload.roster());
        this.names = VoteText.parseNames(payload.names());
        parseTallies(payload.tallies());
    }

    public String sessionId() {
        return payload.sessionId();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        layoutPanel(MAX_W, MAX_H);
        chromeH = HEADER_H;
        chromeW = 0;

        pane = new PvScrollPane();
        pane.x = panelX + 12;
        pane.y = panelY + HEADER_H;
        pane.width = panelW - 24;
        pane.height = panelH - HEADER_H - FOOTER_H;
        pane.gap = 2;
        roots.add(pane);
        buildRows();
        setFocus(null);
    }

    private void buildRows() {
        pane.clear();
        if (roster != null && !roster.isEmpty()) {
            pane.add(new SectionRow(PvLang.str("pvpocket.gui.result.section.roster")));
            pane.add(new RosterSummaryRow(roster, names, rosterCaption()));
        }
        boolean rulesSeen = false;
        boolean loadoutSeen = false;
        for (VoteItem item : items) {
            if (item.kind == VoteItem.KIND_ROSTER) {
                continue;
            }
            boolean rule = item.kind == VoteItem.KIND_RULE;
            if (rule && !rulesSeen) {
                pane.add(new SectionRow(PvLang.str("pvpocket.gui.result.section.rules")));
                rulesSeen = true;
            }
            if (!rule && !loadoutSeen) {
                pane.add(new SectionRow(PvLang.str("pvpocket.gui.result.section.loadout")));
                loadoutSeen = true;
            }
            pane.add(new VoteRow(item, winners.getOrDefault(item.id, 0),
                    counts.get(item.id), true, index -> {
            }));
        }
        pane.layout();
    }

    /** The arrangement's one-line outcome: adopted by whom, or unchanged. */
    private String rosterCaption() {
        for (VoteItem item : items) {
            if (item.kind != VoteItem.KIND_ROSTER) {
                continue;
            }
            int winner = winners.getOrDefault(item.id, 0);
            if (winner > 0 && winner < item.options.size()) {
                String who = String.join("、", item.options.get(winner).proposers);
                return PvLang.str("pvpocket.gui.result.adopted_by", who);
            }
            return PvLang.str("pvpocket.gui.result.kept");
        }
        return PvLang.str("pvpocket.gui.result.kept");
    }

    private void parseTallies(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String line : encoded.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 3) {
                continue;
            }
            try {
                winners.put(parts[0], Integer.parseInt(parts[1]));
                String[] raw = parts[2].split(",");
                int[] perOption = new int[raw.length];
                for (int i = 0; i < raw.length; i++) {
                    perOption[i] = Integer.parseInt(raw[i]);
                }
                counts.put(parts[0], perOption);
            } catch (NumberFormatException ignored) {
                // A malformed line is dropped; the row still draws, without a tally.
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        Font tr = font();
        String title = PvLang.str("pvpocket.gui.result.title");
        PvDraw.text(ctx, tr, title, panelX + 14, panelY + 11, PvTheme.TEXT);

        int titleEnd = panelX + 14 + tr.width(title) + 10;
        String subtitle = items.isEmpty()
                ? PvLang.str("pvpocket.gui.result.no_changes")
                : PvLang.str("pvpocket.gui.result.subtitle", changedCount(), items.size());

        String clock = clock(remainingMs());
        int clockW = PvBadge.widthOf(clock);
        int clockX = panelX + panelW - 12 - clockW;
        int chipY = panelY + (HEADER_H - PvBadge.H) / 2;
        PvDraw.roundRect(ctx, clockX, chipY, clockW, PvBadge.H, 3, PvTheme.ACCENT_SOFT);
        PvDraw.textCenter(ctx, tr, clock, clockX + clockW / 2, chipY + 3, PvTheme.ACCENT);

        PvDraw.text(ctx, tr, PvDraw.fit(tr, subtitle, clockX - 8 - titleEnd), titleEnd,
                panelY + 11, PvTheme.TEXT_DIM);

        drawFooter(ctx, tr);
    }

    private void drawFooter(GuiGraphics ctx, Font tr) {
        int top = panelY + panelH - FOOTER_H;
        PvDraw.hLine(ctx, panelX + 12, top, panelW - 24, PvTheme.DIVIDER);
        int textY = top + 5;
        int changed = changedCount();
        String left = PvLang.str("pvpocket.gui.result.footer.summary", changed,
                Math.max(0, items.size() - changed));
        PvDraw.text(ctx, tr, left, panelX + 12, textY, changed > 0 ? PvTheme.ACCENT : PvTheme.TEXT_DIM);
        PvDraw.textRight(ctx, tr, PvLang.str("pvpocket.gui.result.footer.countdown",
                        Math.max(0, remainingMs() / 1000)),
                panelX + panelW - 12, textY, PvTheme.TEXT_DIM);
    }

    private int changedCount() {
        int n = 0;
        for (VoteItem item : items) {
            if (winners.getOrDefault(item.id, 0) > 0) {
                n++;
            }
        }
        return n;
    }

    private int remainingMs() {
        long left = payload.deadlineMsLeft() - (System.currentTimeMillis() - openedAt);
        return (int) Math.max(0, left);
    }

    private static String clock(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------------ closing

    @Override
    public void onClose() {
        PvScreens.open(Minecraft.getInstance(), null);
    }

    /** Closes without reporting anything: the server already started the match. */
    public void closeSilently() {
        PvScreens.open(Minecraft.getInstance(), null);
    }

    // ------------------------------------------------------------------ audit

    @Override
    public int auditVariantCount() {
        return 2;
    }

    @Override
    public void auditVariant(int index) {
        if (index == 1) {
            // The widest footer: every item changed, so the left string carries two numbers.
            pane.scrollTo(pane.maxScroll());
        }
    }

    /** A muted one-line heading. */
    private static final class SectionRow extends PvWidget {

        private final String label;

        SectionRow(String label) {
            this.label = label;
            this.height = SECTION_H;
        }

        @Override
        public boolean focusable() {
            return false;
        }

        @Override
        public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
            if (!visible) {
                return;
            }
            PvDraw.text(ctx, Minecraft.getInstance().font, label, x + 4, y + 8, PvTheme.TEXT_MUTED);
        }
    }

    /** The winning arrangement as cards, with its outcome on the header line. */
    private static final class RosterSummaryRow extends PvWidget {

        private static final int PAD = 8;
        private static final int HEAD_H = 12;

        private final List<List<String>> sides;
        private final Map<String, String> names;
        private final String caption;

        RosterSummaryRow(List<List<String>> sides, Map<String, String> names, String caption) {
            this.sides = sides;
            this.names = names;
            this.caption = caption;
            this.height = HEAD_H + PAD;
        }

        @Override
        public boolean focusable() {
            return false;
        }

        @Override
        public void onWidth(int width) {
            this.height = HEAD_H + RosterCards.height(sides, width - PAD * 2) + PAD;
        }

        @Override
        public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
            if (!visible) {
                return;
            }
            Font tr = Minecraft.getInstance().font;
            PvDraw.textFitLeft(ctx, tr, caption, x + PAD, y + 1, width - PAD * 2, PvTheme.TEXT_DIM);
            RosterCards.draw(ctx, tr, sides, names, 0, x + PAD, y + HEAD_H, width - PAD * 2);
        }
    }
}
