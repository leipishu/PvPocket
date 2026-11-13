package com.leipishu.pvpocket.client.gui.match;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.MatchListPayload;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /pvpocket list} panel: every duel currently being fought, with a way in.
 *
 * <p>Rows are drawn by hand rather than built from a scroll pane of row widgets. The panel shows at
 * most {@link #MAX_ROWS} matches; past the cap it says how many it is not showing and points at
 * {@code /pvpocket spectate <id>}.
 *
 * <p>The buttons are real widgets in the screen's root list, positioned on their row, so focus
 * traversal, hover, tooltips and keyboard activation work as they do in the rule panel.
 */
public class MatchListScreen extends PvScreen {

    private static final int MAX_W = 500;
    /** One row of text and a right-aligned count: the same header band as the other two panels. */
    private static final int HEADER_H = 30;
    private static final int ROW_H = 38;
    private static final int FOOTER_H = 40;
    private static final int MAX_ROWS = 6;
    private static final int BTN_W = 58;
    private static final int BTN_H = 22;

    /**
     * Least width the map name keeps before the row drops what qualifies it.
     *
     * <p>48px is "Colosseum" at the legibility floor. The stage chip and the round number are
     * measured against it.
     */
    private static final int MIN_MAP_ROOM = 48;

    /**
     * One row of the list, parsed from the payload's flat encoding.
     *
     * <p>{@code map} is a map id and {@code stage} a session stage name, both identifiers rather
     * than text, since the server has no language. {@link #mapLabel()} and {@link #stageLabel()}
     * resolve them on the client.
     */
    public record Entry(String id, String map, String stage, int round, int maxRounds,
                        String score, int flags, int spectators, List<String> players) {

        public boolean spectatable() {
            return (flags & MatchListPayload.FLAG_SPECTATABLE) != 0;
        }

        public boolean participant() {
            return (flags & MatchListPayload.FLAG_PARTICIPANT) != 0;
        }

        public boolean watching() {
            return (flags & MatchListPayload.FLAG_WATCHING) != 0;
        }

        public String playersLine() {
            return players.isEmpty() ? "—" : String.join(" vs ", players);
        }

        /** The map's name in the reader's language. An unknown id falls back to the id itself. */
        public String mapLabel() {
            String key = "pvpocket.map." + map;
            String text = PvLang.str(key);
            return text.equals(key) ? map : text;
        }

        /**
         * Returns the stage's name in the reader's language, falling back to the raw stage
         * constant, which is what the server sent.
         */
        public String stageLabel() {
            String key = "pvpocket.stage." + stage.toLowerCase(java.util.Locale.ROOT);
            String text = PvLang.str(key);
            return text.equals(key) ? stage : text;
        }

        /**
         * Parses the payload, dropping malformed rows.
         *
         * <p>A row that does not split into nine fields is a version skew. Dropping it keeps the
         * rest of the panel usable; throwing would turn a cosmetic mismatch into a crash.
         */
        public static List<Entry> parse(String encoded) {
            List<Entry> out = new ArrayList<>();
            if (encoded == null || encoded.isEmpty()) {
                return out;
            }
            for (String line : encoded.split("\n")) {
                String[] f = line.split("\\|", 9);
                if (f.length < 9) {
                    continue;
                }
                List<String> names = new ArrayList<>();
                for (String pair : f[8].split(",")) {
                    int colon = pair.indexOf(':');
                    if (colon > 0) {
                        names.add(pair.substring(0, colon));
                    }
                }
                try {
                    out.add(new Entry(f[0], f[1], f[2],
                            Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                            f[5], Integer.parseInt(f[6]), Integer.parseInt(f[7]), names));
                } catch (NumberFormatException ignored) {
                    // Same reasoning as the field-count check: one bad row, not one bad panel.
                }
            }
            return out;
        }
    }

    private final List<Entry> entries;
    private final float[] rowHot;
    private int rowTop;
    /** Rows actually drawn: the design cap and the height of the canvas, whichever is smaller. */
    private int shownRows;
    /** The footer's close button, so the hint beside it knows where it has to stop. */
    private PvButton closeButton;

    public MatchListScreen(MatchListPayload payload) {
        super(PvLang.t("pvpocket.gui.match.title"));
        this.entries = Entry.parse(payload.entries());
        this.rowHot = new float[entries.size()];
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        // Rows start at the top and the footer is pinned to the bottom, so how many fit is a
        // subtraction: on a 320x180 canvas the panel is 160px tall.
        int fit = Math.max(1, (height - 20 - HEADER_H - FOOTER_H) / ROW_H);
        shownRows = Math.min(entries.size(), Math.min(MAX_ROWS, fit));
        int extra = entries.size() > shownRows ? 14 : 0;
        int rowsHeight = shownRows * ROW_H;
        layoutPanel(MAX_W, HEADER_H + rowsHeight + extra + FOOTER_H);
        chromeH = HEADER_H;
        chromeW = 0;
        rowTop = panelY + HEADER_H;

        for (int i = 0; i < shownRows; i++) {
            addRowButton(entries.get(i), i);
        }
        buildFooter();
        setFocus(null);
    }

    private void addRowButton(Entry entry, int index) {
        PvButton button;
        if (entry.participant()) {
            button = new PvButton(PvLang.str("pvpocket.gui.match.button.playing"),
                    PvButton.Style.GHOST, null);
            button.enabled = false;
            button.tooltip = PvLang.str("pvpocket.gui.match.tooltip.playing");
        } else if (entry.watching()) {
            button = new PvButton(PvLang.str("pvpocket.gui.match.button.leave"),
                    PvButton.Style.DANGER, this::leave);
            button.tooltip = PvLang.str("pvpocket.gui.match.tooltip.leave");
        } else {
            button = new PvButton(PvLang.str("pvpocket.gui.match.button.watch"),
                    PvButton.Style.NORMAL, () -> join(entry.id));
            if (!entry.spectatable()) {
                button.enabled = false;
                button.tooltip = PvLang.str("pvpocket.gui.match.tooltip.disabled");
            } else {
                button.tooltip = PvLang.str("pvpocket.gui.match.tooltip.watch");
            }
        }
        button.size(BTN_W, BTN_H);
        // Inset from the card's edge: a button ending at the border reads as if it is falling out
        // of the row.
        button.x = panelX + panelW - 20 - BTN_W;
        button.y = rowTop + index * ROW_H + (ROW_H - BTN_H) / 2 - 2;
        roots.add(button);
    }

    private void buildFooter() {
        int y = panelY + panelH - FOOTER_H + 8;
        int right = panelX + panelW - 12;
        PvButton close = new PvButton(PvLang.str("pvpocket.gui.match.button.close"),
                PvButton.Style.NORMAL, this::onClose);
        close.size(56, PvTheme.BTN_H);
        close.x = right - close.width;
        close.y = y;
        roots.add(close);
        closeButton = close;

        PvButton refresh = new PvButton(PvLang.str("pvpocket.gui.match.button.refresh"),
                PvButton.Style.GHOST, this::refresh);
        refresh.size(52, PvTheme.BTN_H);
        refresh.x = panelX + 12;
        refresh.y = y;
        roots.add(refresh);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        Font tr = font();
        float d = PvAnim.dt(delta);

        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.match.title"), panelX + 16, panelY + 11,
                PvTheme.TEXT);
        // The count shares the title row: it modifies the title rather than being a second subject.
        PvDraw.textRight(ctx, tr, entries.isEmpty()
                        ? PvLang.str("pvpocket.gui.match.count.none")
                        : PvLang.str("pvpocket.gui.match.count.some", entries.size()),
                panelX + panelW - 16, panelY + 11, PvTheme.TEXT_DIM);

        if (entries.isEmpty()) {
            PvDraw.textCenter(ctx, tr, PvLang.str("pvpocket.gui.match.empty"),
                    panelX + panelW / 2, rowTop + 14, PvTheme.TEXT_MUTED);
        }

        for (int i = 0; i < shownRows; i++) {
            drawRow(ctx, tr, entries.get(i), i, mouseX, mouseY, d);
        }
        if (entries.size() > shownRows) {
            PvDraw.textCenter(ctx, tr,
                    PvLang.str("pvpocket.gui.match.overflow", entries.size() - shownRows),
                    panelX + panelW / 2, rowTop + shownRows * ROW_H + 2, PvTheme.TEXT_MUTED);
        }
        PvDraw.hLine(ctx, panelX + 12, panelY + panelH - FOOTER_H, panelW - 24, PvTheme.DIVIDER);
        // Fitted against the close button rather than left to run: the hint is a sentence in both
        // languages and would otherwise end under the button on a 320x180 canvas.
        PvDraw.text(ctx, tr,
                PvDraw.fit(tr, PvLang.str("pvpocket.gui.match.keys"),
                        (closeButton == null ? panelX + panelW - 68 : closeButton.x) - 8
                                - (panelX + 72)),
                panelX + 72, panelY + panelH - FOOTER_H + 16, PvTheme.TEXT_MUTED);
    }

    private void drawRow(GuiGraphics ctx, Font tr, Entry entry, int index,
                         int mouseX, int mouseY, float d) {
        int x = panelX + 12;
        int y = rowTop + index * ROW_H;
        int w = panelW - 24;
        int h = ROW_H - 4;
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

        rowHot[index] = PvAnim.damp(rowHot[index], hot ? 1F : 0F, d, 0.09F);
        float t = rowHot[index];

        PvDraw.roundPanel(ctx, x, y, w, h, PvTheme.RADIUS,
                PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, t),
                PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, t));

        // A watching row is marked with an accent bar rather than a different fill: the fill is
        // already carrying hover, and one visual channel cannot say two things at once.
        if (entry.watching()) {
            PvDraw.roundRect(ctx, x + 1, y + 6, 2, h - 12, 1, PvTheme.ACCENT);
        }

        // Two lines of 8px with 5px leading occupy 21px of the 34px card, so the block starts at
        // y+7 and ends at y+28. Every element on line one shares that baseline.
        int textX = x + 10;
        int line1 = y + 7;
        int line2 = y + 20;

        // The right-hand block is measured first, because the left block has to stop before it. On
        // a 320x180 canvas the card is 178px wide and that block is 180px on its own.
        int anchor = panelX + panelW - 20 - BTN_W - 12;
        String score = entry.score();
        int scoreW = tr.width(score);
        String watchers = entry.spectators() > 0
                ? PvLang.str("pvpocket.gui.match.watchers", entry.spectators())
                : null;
        int rightW = scoreW + (watchers == null ? 0 : tr.width(watchers) + 10);
        int leftLimit = anchor - rightW - 10;

        String stage = entry.stageLabel();
        int chipW = tr.width(stage) + 10;
        String round = entry.maxRounds() > 1
                ? PvLang.str("pvpocket.gui.match.round", Math.max(1, entry.round()), entry.maxRounds())
                : null;
        int room = leftLimit - textX;
        int roundW = round == null ? 0 : tr.width(round) + 6;
        // Right to left: the round number goes first, then the stage chip, and the map name keeps a
        // floor-sized minimum throughout.
        if (round != null && room - chipW - 8 - roundW < MIN_MAP_ROOM) {
            round = null;
            roundW = 0;
        }
        boolean stageChip = room - chipW - 8 >= MIN_MAP_ROOM;
        if (!stageChip) {
            chipW = 0;
        }
        // Shrunk rather than cut: the map name is the row's subject, and the width the run actually
        // covers is what the stage chip is anchored to.
        int mapW = PvDraw.textFitLeft(ctx, tr, entry.mapLabel(), textX, line1,
                Math.max(MIN_MAP_ROOM, room - chipW - 8 - roundW), PvTheme.TEXT);

        // Stage chip, right after the map name. The live colour is keyed on the stage constant, not
        // on the rendered word, which differs in every language.
        int stageColor = "ROUND_ACTIVE".equals(entry.stage()) ? PvTheme.LIVE : PvTheme.ACCENT;
        int chipX = textX + mapW + 8;
        if (stageChip) {
            PvDraw.roundRect(ctx, chipX, line1 - 2, chipW, 12, 3, PvTheme.alpha(stageColor, 0.14F));
            PvDraw.text(ctx, tr, stage, chipX + 5, line1, stageColor);
        }

        PvDraw.textFitLeft(ctx, tr, entry.playersLine(), textX, line2, leftLimit - textX,
                PvTheme.TEXT_DIM);

        // Score and spectator count right-aligned against the button, on one line centred in the
        // card, so both anchors agree.
        int scoreY = y + (h - 8) / 2 + 1;
        PvDraw.textRight(ctx, tr, score, anchor, scoreY, PvTheme.TEXT);
        if (watchers != null) {
            PvDraw.textRight(ctx, tr, watchers, anchor - scoreW - 10, scoreY, PvTheme.TEXT_MUTED);
        }
        // Round number after the stage chip, where it reads as "which round of that state".
        if (round != null) {
            PvDraw.text(ctx, tr, round, chipX + chipW + 6, line1, PvTheme.TEXT_MUTED);
        }
    }

    // ------------------------------------------------------------------ actions

    private void join(String sessionId) {
        send(SpectateRequestPayload.join(sessionId));
        // Closed immediately: the server switches this player into spectator mode and teleports
        // them, and a modal panel over that transition would be the last thing they see.
        Minecraft.getInstance().setScreen(null);
    }

    private void leave() {
        send(SpectateRequestPayload.leave());
        refresh();
    }

    private void refresh() {
        send(SpectateRequestPayload.list());
    }

    private static void send(SpectateRequestPayload payload) {
        if (PvClientPlatform.get().canSend(SpectateRequestPayload.ID)) {
            PvClientPlatform.get().send(payload);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
