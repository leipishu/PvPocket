package com.leipishu.pvpocket.client.gui.result;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.home.HomeText;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The card that appears when a series ends: who won, by how much, round by round.
 *
 * <p>The server pushes it, so it has no parent and Esc returns to the game.
 *
 * <p>The title is the verdict in the reader's language and the line under it is the same verdict in
 * English; the second line is empty in {@code en_us}, where the title already is English.
 *
 * <p>Exporting redraws the same card full-bleed, without scrim, world or buttons, and captures the
 * framebuffer to {@code screenshots/pvpocket/}. The poster fits every round of a fifteen-round
 * series, where the panel on a 320x180 canvas fits six and says so.
 */
public class SeriesResultScreen extends PvScreen {

    /** Design size. The panel shrinks on a small canvas; the poster uses the whole viewport. */
    private static final int CARD_MAX_W = 400;
    private static final int CARD_MAX_H = 216;

    private static final int SIDE_ROW_H = 12;
    private static final int SIDE_ROW_H_COMPACT = 11;
    private static final int ROUND_ROW_H = 11;
    /** Cap per column even when the card has room for more; eight reads as two lists, not one. */
    private static final int PER_COLUMN_MAX = 8;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final MatchResult result;
    private final PvButton export;
    private final PvButton close;

    /** Drawing the poster instead of the panel this frame. */
    private boolean posterMode;
    /** The poster has been drawn; capture it at the end of this frame. */
    private boolean posterCapture;
    /** Set while {@link #drawContent} is drawing the poster, which has no buttons. */
    private boolean posterFrame;

    public SeriesResultScreen(MatchResult result) {
        super(PvLang.t("pvpocket.gui.result.title"));
        this.result = result;
        this.export = new PvButton(PvLang.str("pvpocket.gui.result.export"),
                PvButton.Style.NORMAL, this::requestPoster).size(64, 20);
        this.export.tooltip = PvLang.str("pvpocket.gui.result.export_hint");
        this.close = new PvButton(PvLang.str("pvpocket.gui.result.close"),
                PvButton.Style.GHOST, this::onClose).size(48, 20);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        layoutPanel(CARD_MAX_W, CARD_MAX_H);
        chromeW = 0;
        chromeH = bandH();
        export.height = compact() ? 18 : 20;
        close.height = export.height;
        roots.add(export);
        roots.add(close);
        setFocus(null);
    }

    /** Title band height: two lines of text plus the same breathing room the other panels use. */
    private int bandH() {
        return compact() ? 28 : 34;
    }

    /**
     * Returns whether the panel is on the small canvas, 160px tall.
     *
     * <p>Three things are dropped there rather than shrunk: the section label above the round list,
     * the second meta line, and two pixels off each row.
     */
    private boolean compact() {
        return panelH <= 176;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (posterMode) {
            drawPoster(ctx);
            return;
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        Font tr = font();
        int wx = panelX + PvTheme.PAD_L;
        int ww = panelW - PvTheme.PAD_L * 2;
        drawBand(ctx, tr);

        int y = panelY + chromeH + (compact() ? 4 : 6);
        int sideRow = compact() ? SIDE_ROW_H_COMPACT : SIDE_ROW_H;
        for (MatchResult.Side side : result.sides()) {
            drawSide(ctx, tr, side, wx, y, ww, sideRow);
            y += sideRow;
        }
        y += 3;
        PvDraw.hLine(ctx, wx, y, ww, PvTheme.DIVIDER);
        y += compact() ? 6 : 8;

        // The label travels with the list: the block that gets centred has to be the whole section,
        // or the gap between a heading and the thing it heads grows with the empty space.
        int headerH = compact() ? 0 : 11;
        int labelY = y;

        // Bottom-anchored: the meta block and the buttons are pinned and the round list takes what
        // is left, since the list is the only part whose height varies.
        int bottom = panelY + panelH;
        int btnH = compact() ? 18 : 20;
        int btnY = bottom - 6 - btnH;
        int metaLines = compact() ? 1 : 2;
        int metaTop = (posterFrame ? bottom - 6 - metaLines * 10 : btnY - 5 - metaLines * 10);
        int dividerY = metaTop - 6;

        PvDraw.hLine(ctx, wx, dividerY, ww, PvTheme.DIVIDER);
        drawRounds(ctx, tr, wx, ww, labelY, headerH, dividerY - 4);
        drawMeta(ctx, tr, wx, ww, metaTop);

        if (!posterFrame) {
            export.x = wx;
            export.y = btnY;
            close.x = wx + ww - close.width;
            close.y = btnY;
        }
    }

    private void drawBand(GuiGraphics ctx, Font tr) {
        int band = chromeH;
        // A win gets one 2px rule along the top edge of the card. A rule is how the accent is used
        // everywhere else, and it keeps the title's contrast against the surface.
        if (result.won()) {
            PvDraw.rect(ctx, panelX + PvTheme.RADIUS_PANEL, panelY,
                    panelW - PvTheme.RADIUS_PANEL * 2, 2, PvTheme.ACCENT);
        }
        int cx = panelX + panelW / 2;
        int room = panelW - PvTheme.PAD_L * 2;
        int titleY = panelY + (compact() ? 6 : 9);
        PvDraw.textFitCenter(ctx, tr, PvLang.str(verdictKey("")), cx, titleY, room,
                result.won() ? PvTheme.ACCENT : PvTheme.TEXT);
        // Empty in en_us, where the title is already the English line. See the class comment.
        String sub = PvLang.str(verdictKey("_en"));
        if (!sub.isEmpty()) {
            PvDraw.textFitCenter(ctx, tr, sub, cx, titleY + 12, room, PvTheme.TEXT_DIM);
        }
    }

    /**
     * Returns {@code pvpocket.gui.result.win|loss|draw}, with {@code suffix} appended.
     *
     * <p>One switch rather than six keys at the call sites; the verdict is asked for four times.
     */
    private String verdictKey(String suffix) {
        String state = result.won() ? "win" : result.drawn() ? "draw" : "loss";
        return "pvpocket.gui.result." + state + suffix;
    }

    /**
     * Draws one side: a wool-coloured bar, its roster and its round wins.
     *
     * <p>The bar is the wool colour and the accent marks the winner, so the two questions on the
     * same row do not both read amber. The reader's own side gets a wash behind the row.
     */
    private void drawSide(GuiGraphics ctx, Font tr, MatchResult.Side side,
                          int x, int y, int w, int rowH) {
        boolean mine = side.index() == result.mySide();
        boolean took = side.index() == result.winnerSide();
        if (mine) {
            PvDraw.roundRect(ctx, x - 4, y - 1, w + 8, rowH, 3, PvTheme.ACCENT_SOFT);
        }
        PvDraw.roundRect(ctx, x, y + 1, 2, rowH - 4, 1,
                took ? PvTheme.team(side.index()) : PvTheme.BORDER_STRONG);

        String score = Integer.toString(side.score());
        int scoreW = tr.width(score);
        String roster = mine
                ? side.roster() + " · " + PvLang.str("pvpocket.gui.result.you")
                : side.roster();
        PvDraw.textFitLeft(ctx, tr, roster, x + 8, y + 2, w - 10 - scoreW - 6,
                mine ? PvTheme.TEXT : PvTheme.TEXT_DIM);
        PvDraw.textRight(ctx, tr, score, x + w - 2, y + 2,
                took ? PvTheme.ACCENT : PvTheme.TEXT);
    }

    /**
     * Draws the round list in two columns, down then across.
     *
     * <p>A series can run to fifteen rounds and one column does not fit a 232px card. When even two
     * columns run out, the last slot says how many are missing.
     */
    private void drawRounds(GuiGraphics ctx, Font tr, int x, int w,
                            int labelY, int headerH, int bottom) {
        List<MatchResult.Round> rounds = result.rounds();
        if (rounds.isEmpty()) {
            if (headerH > 0) {
                PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.result.rounds"), x, labelY,
                        PvTheme.TEXT_MUTED);
            }
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.result.no_rounds"), x, labelY + headerH,
                    PvTheme.TEXT_MUTED);
            return;
        }
        int perColumn = Math.max(1,
                Math.min(PER_COLUMN_MAX, (bottom - labelY - headerH) / ROUND_ROW_H));
        int capacity = perColumn * 2;
        boolean truncated = rounds.size() > capacity;
        int shown = truncated ? capacity - 1 : rounds.size();
        int colW = Math.max(40, (w - 10) / 2);

        // Centred in the space it was given, label and all: top-anchoring left a third of the card
        // empty under the last round on a poster.
        int slots = shown + (truncated ? 1 : 0);
        int usedRows = Math.min(perColumn, (slots + 1) / 2);
        int blockH = headerH + usedRows * ROUND_ROW_H;
        int offset = Math.max(0, (bottom - labelY - blockH) / 2);
        if (headerH > 0) {
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.result.rounds"), x, labelY + offset,
                    PvTheme.TEXT_MUTED);
        }
        int rowsTop = labelY + headerH + offset;

        // The label column is measured, not assumed: the round label is wider at round 10 than at
        // round 1, and a fixed offset would put the mark through the last digit.
        int labelW = 0;
        for (int i = 0; i < shown; i++) {
            labelW = Math.max(labelW, tr.width(
                    PvLang.str("pvpocket.gui.home.detail.round", i + 1)));
        }
        for (int i = 0; i < shown; i++) {
            MatchResult.Round round = rounds.get(i);
            int rx = x + (i / perColumn) * (colW + 10);
            int ry = rowsTop + (i % perColumn) * ROUND_ROW_H;
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.detail.round", i + 1), rx, ry,
                    PvTheme.TEXT_MUTED);

            boolean none = round.winner() < 0;
            boolean mine = round.winner() == result.mySide();
            String mark = PvLang.str(none ? "pvpocket.gui.result.mark.draw"
                    : mine ? "pvpocket.gui.result.mark.win" : "pvpocket.gui.result.mark.loss");
            int markX = rx + labelW + 5;
            PvDraw.text(ctx, tr, mark, markX, ry,
                    none ? PvTheme.TEXT_MUTED : mine ? PvTheme.ACCENT : PvTheme.TEXT_DIM);

            // The tail is clipped to what is left rather than right-aligned over the mark: two
            // strings drawn towards each other overlap in the middle.
            String tail = reasonLabel(round.reason()) + "  " + HomeText.duration(round.ms());
            int tailRoom = rx + colW - (markX + tr.width(mark)) - 5;
            if (tailRoom > 8) {
                PvDraw.textRight(ctx, tr, PvDraw.fit(tr, tail, tailRoom), rx + colW, ry,
                        PvTheme.TEXT_MUTED);
            }
        }
        if (truncated) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr,
                            PvLang.str("pvpocket.gui.home.detail.more", rounds.size() - shown),
                            colW),
                    x + (shown / perColumn) * (colW + 10),
                    rowsTop + (shown % perColumn) * ROUND_ROW_H,
                    PvTheme.TEXT_MUTED);
        }
    }

    /**
     * Draws map, mode, shape and length, plus time and reason when there is room.
     *
     * <p>No separate opponents line: the score block above already lists every side's roster.
     */
    private void drawMeta(GuiGraphics ctx, Font tr, int x, int w, int top) {
        String line1 = result.mapLabel()
                + " · " + PvLang.str("legacy".equals(result.mode())
                ? "pvpocket.gui.home.mode.legacy" : "pvpocket.gui.home.mode.modern")
                + " · " + result.teamsLabel()
                + " · " + PvLang.str("pvpocket.gui.result.duration",
                HomeText.duration(result.duration()));
        PvDraw.textFitLeft(ctx, tr, line1, x, top, w, PvTheme.TEXT_DIM);
        if (compact()) {
            return;
        }
        String line2 = HomeText.stamp(result.endedAt())
                + (result.reason().isEmpty() ? "" : " · " + reasonLabel(result.reason()));
        PvDraw.textFitLeft(ctx, tr, line2, x, top + 10, w, PvTheme.TEXT_MUTED);
    }

    /**
     * Returns a reason code as a word, falling back to the code itself.
     */
    private static String reasonLabel(String reason) {
        if (reason == null || reason.isEmpty()) {
            return PvLang.str("pvpocket.gui.home.reason.none");
        }
        String key = "pvpocket.gui.home.reason." + reason.toLowerCase(java.util.Locale.ROOT);
        String text = PvLang.str(key);
        return text.equals(key) ? reason : text;
    }

    // ------------------------------------------------------------------ poster

    private void requestPoster() {
        posterMode = true;
        posterCapture = true;
    }

    /**
     * Draws the card over the whole viewport and, on the frame that asked for it, captures it.
     *
     * <p>The panel rect is swapped for the viewport for the duration of the draw, so the poster is
     * the same card at a different size. The fields are restored afterwards.
     */
    private void drawPoster(GuiGraphics ctx) {
        // Two pixels wider than the viewport on every side: the logical width is the framebuffer
        // divided by the GUI scale, so the last columns belong to no logical pixel.
        PvDraw.rect(ctx, -2, -2, width + 4, height + 4, PvTheme.SUNKEN);
        int px = panelX;
        int py = panelY;
        int pw = panelW;
        int ph = panelH;
        panelX = 0;
        panelY = 0;
        panelW = width;
        panelH = height;
        chromeH = bandH();
        drawPanelFrame(ctx);
        posterFrame = true;
        drawContent(ctx, -1, -1, 0F);
        posterFrame = false;
        panelX = px;
        panelY = py;
        panelW = pw;
        panelH = ph;
        chromeH = bandH();

        if (!posterCapture) {
            return;
        }
        posterCapture = false;
        posterMode = false;
//? if >=1.21.11 {
        // Drawing is submitted after this returns, so the framebuffer cannot be read yet.
        // one tick later runs before the next frame, target still holding the poster.
        Minecraft.getInstance().execute(this::savePoster);
//?} else {
        // Flush first: fill batches its quads and the capture reads the framebuffer, so a read
        // before the batch is submitted returns the frame from before the poster existed.
        ctx.flush();
        savePoster();
//?}
    }

    /**
     * Writes the current framebuffer to {@code screenshots/pvpocket/result-<timestamp>.png}.
     *
     * <p>Its own folder rather than the vanilla screenshots root, so posters are not mixed into a
     * directory of F2 shots.
     */
    private void savePoster() {
        Minecraft client = Minecraft.getInstance();
//? if >=1.21.11 {
        Screenshot.takeScreenshot(client.getMainRenderTarget(), this::writePoster);
//?} else {
        writePoster(Screenshot.takeScreenshot(client.getMainRenderTarget()));
//?}
    }

    private void writePoster(NativeImage image) {
        Minecraft client = Minecraft.getInstance();
        Path dir = client.gameDirectory.toPath().resolve("screenshots").resolve("pvpocket");
        Path file = dir.resolve("result-"
                + LocalDateTime.now().format(FILE_STAMP) + ".png");
        try {
            Files.createDirectories(dir);
            image.writeToFile(file);
            PvPocket.LOGGER.info("Result poster written to {}", file);
            client.gui.getChat().addMessage(PvLang.t("pvpocket.msg.result.saved",
                    file.getFileName().toString()).withStyle(ChatFormatting.GRAY));
        } catch (IOException | RuntimeException e) {
            // Runtime as well as IOException: the capture itself can throw if the framebuffer is
            // not readable, and a failed export must not take the screen with it.
            PvPocket.LOGGER.error("Cannot write result poster to {}", file, e);
            client.gui.getChat().addMessage(
                    PvLang.t("pvpocket.msg.result.save_failed").withStyle(ChatFormatting.RED));
        } finally {
            image.close();
        }
    }

    // ------------------------------------------------------------------ audit

    /**
     * Builds one of three sample cards for the layout audit: a win, a loss that ended early, and a
     * four-side draw.
     *
     * <p>A fifteen-round series makes the round list wrap or truncate, four sides make the score
     * block taller, and a loss is the only variant whose headline is not the accent colour.
     */
    public static SeriesResultScreen sample(int variant) {
        MatchResult.Side a = new MatchResult.Side(0, variant == 1 ? 0 : 2, List.of("Dev1", "Dev2"));
        MatchResult.Side b = new MatchResult.Side(1, variant == 1 ? 2 : 1, List.of("Dev3", "Dev4"));
        List<MatchResult.Side> sides = variant == 2
                ? List.of(a, b, new MatchResult.Side(2, 1, List.of("Dev5")),
                new MatchResult.Side(3, 1, List.of("Dev6", "Dev7")))
                : List.of(a, b);
        List<MatchResult.Round> rounds = new java.util.ArrayList<>();
        int count = variant == 1 ? 3 : 15;
        for (int i = 0; i < count; i++) {
            rounds.add(new MatchResult.Round(variant == 1 ? 1 : i % 2,
                    variant == 1 && i == 2 ? "SURRENDER" : i % 3 == 0 ? "TIMEOUT" : "DEATH",
                    60_000L + i * 7_000L));
        }
        return new SeriesResultScreen(new MatchResult("audit", "arena_colosseum", "legacy",
                0, variant == 1 ? 1 : variant == 2 ? -1 : 0,
                System.currentTimeMillis(), 1_320_000L,
                variant == 1 ? "SURRENDER" : variant == 2 ? "DRAW" : "", sides, rounds));
    }
}
