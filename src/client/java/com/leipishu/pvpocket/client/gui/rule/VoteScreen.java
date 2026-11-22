package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvBadge;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvScrollPane;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.client.compat.PvScreens;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.VoteOpenPayload;
import com.leipishu.pvpocket.network.VoteProgressPayload;
import com.leipishu.pvpocket.network.VoteSubmitPayload;
import com.leipishu.pvpocket.session.VoteItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ballot: one row per changed rule or loadout row, and a footer that says who is still out.
 *
 * <p>Everything to vote on sits in a scrolling well; a single 18px footer carries the two numbers
 * that answer "can the match start": how many players have finished, and how many items there are.
 *
 * <p>Votes are cast by clicking a chip and can be changed until the panel closes. Nothing is
 * confirmed here, because nothing is final until the ballot closes.
 *
 * <p>No running tallies are drawn: the server sends participation only, so the panel cannot leak a
 * bandwagon.
 */
public class VoteScreen extends PvScreen {

    private static final int MAX_W = 580;
    private static final int MAX_H = 348;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 18;
    private static final int SECTION_H = 18;

    private final VoteOpenPayload payload;
    private final List<VoteItem> items;
    /** This player's votes, {@code itemId -> option index}; updated optimistically on click. */
    private final Map<String, Integer> votes = new LinkedHashMap<>();
    private final long openedAt = System.currentTimeMillis();

    private int votedPlayers;
    private int totalPlayers;
    private PvScrollPane pane;
    /** One per item, in item order: a {@link VoteRow} or a {@link RosterVoteRow}. */
    private final List<PvWidget> rows = new ArrayList<>();
    private final Map<String, String> names;

    public VoteScreen(VoteOpenPayload payload) {
        super(PvLang.t("pvpocket.gui.vote.title"));
        this.payload = payload;
        this.items = VoteItem.decodeAll(payload.items());
        this.votedPlayers = payload.votedPlayers();
        this.totalPlayers = payload.totalPlayers();
        this.names = VoteText.parseNames(payload.names());
        parseVotes(payload.myVotes());
    }

    public String sessionId() {
        return payload.sessionId();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        rows.clear();
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
        boolean rulesSeen = false;
        boolean loadoutSeen = false;
        for (VoteItem item : items) {
            boolean rule = item.kind == VoteItem.KIND_RULE;
            if (rule && !rulesSeen) {
                pane.add(new SectionRow(PvLang.str("pvpocket.gui.vote.section.rules")));
                rulesSeen = true;
            }
            if (!rule && !loadoutSeen) {
                pane.add(new SectionRow(PvLang.str("pvpocket.gui.vote.section.loadout")));
                loadoutSeen = true;
            }
            int selected = votes.getOrDefault(item.id, -1);
            PvWidget row = item.kind == VoteItem.KIND_ROSTER
                    ? new RosterVoteRow(item, names, selected, index -> cast(item.id, index))
                    : new VoteRow(item, selected, index -> cast(item.id, index));
            rows.add(row);
            pane.add(row);
        }
        pane.layout();
    }

    /** Sends one vote and shows it immediately; the server's progress packet confirms the count. */
    private void cast(String itemId, int option) {
        votes.put(itemId, option);
        if (!PvClientPlatform.get().canSend(VoteSubmitPayload.ID)) {
            return;
        }
        PvClientPlatform.get().send(new VoteSubmitPayload(payload.sessionId(), itemId, option));
    }

    /** Applies a progress packet: participation counts and, if it changed, this player's votes. */
    public void update(VoteProgressPayload progress) {
        if (!progress.sessionId().equals(payload.sessionId())) {
            return;
        }
        votedPlayers = progress.votedPlayers();
        totalPlayers = progress.totalPlayers();
        Map<String, Integer> before = new LinkedHashMap<>(votes);
        parseVotes(progress.myVotes());
        if (!before.equals(votes)) {
            // Rows are built in item order, so position in one list indexes the other.
            for (int i = 0; i < rows.size() && i < items.size(); i++) {
                select(rows.get(i), votes.getOrDefault(items.get(i).id, -1));
            }
        }
    }

    private static void select(PvWidget row, int index) {
        if (row instanceof VoteRow) {
            ((VoteRow) row).setSelected(index);
        } else if (row instanceof RosterVoteRow) {
            ((RosterVoteRow) row).setSelected(index);
        }
    }

    private void parseVotes(String encoded) {
        votes.clear();
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String part : encoded.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                votes.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // A malformed pair is dropped; the row simply shows nothing selected.
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        Font tr = font();
        String title = PvLang.str("pvpocket.gui.vote.title");
        PvDraw.text(ctx, tr, title, panelX + 14, panelY + 11, PvTheme.TEXT);

        String subtitle = PvLang.str("pvpocket.gui.vote.subtitle", totalPlayers);
        int titleEnd = panelX + 14 + tr.width(title) + 10;

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
        int waiting = Math.max(0, totalPlayers - votedPlayers);
        String left = waiting == 0
                ? PvLang.str("pvpocket.gui.vote.footer.all_voted")
                : PvLang.str("pvpocket.gui.vote.footer.waiting", waiting);
        PvDraw.text(ctx, tr, left, panelX + 12, textY, waiting == 0 ? PvTheme.ACCENT : PvTheme.TEXT_DIM);
        PvDraw.textRight(ctx, tr, PvLang.str("pvpocket.gui.vote.footer.total", items.size()),
                panelX + panelW - 12, textY, PvTheme.TEXT_DIM);
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

    /** Closes without reporting anything: the server already ended the ballot. */
    public void closeSilently() {
        PvScreens.open(Minecraft.getInstance(), null);
    }

    // ------------------------------------------------------------------ audit

    /**
     * Two arrangements: a fresh ballot and one where everything is answered.
     *
     * <p>The second matters because a selected chip is a different fill and a different text
     * colour, and because the footer swaps to "everyone voted", its widest left-hand string.
     */
    @Override
    public int auditVariantCount() {
        return 2;
    }

    @Override
    public void auditVariant(int index) {
        if (index == 0) {
            return;
        }
        for (PvWidget row : rows) {
            select(row, 1);
        }
        votedPlayers = totalPlayers;
    }

    /** A muted one-line heading between the rule items and the loadout items. */
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
}
