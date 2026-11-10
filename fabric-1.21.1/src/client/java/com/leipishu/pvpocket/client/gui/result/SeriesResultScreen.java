package com.leipishu.pvpocket.client.gui.result;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.home.HomeText;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The card that appears when a series ends: who won, by how much, round by round.
 *
 * <p>Pushed by the server rather than opened from a menu, so it has no parent and Esc returns to the
 * game — the player did not come from a panel and does not want to land in one.</p>
 *
 * <p><b>Two headlines, not one.</b> The top line is the verdict in the reader's language and the line
 * under it is the same verdict in English, which is what the card is for: a result is the one screen
 * in this mod a player is likely to screenshot and show to somebody who does not read their
 * language. The English line is empty in {@code en_us}, where the title already is the English one;
 * an empty translation draws nothing and takes no space.</p>
 *
 * <p><b>The loss is worded as an encouragement.</b> 「再接再厉」 / "Well fought!" — a card that says
 * 「失败」 is a card nobody wants to export, and the score block below already states the fact without
 * needing to be told twice.</p>
 *
 * <h2>The poster</h2>
 *
 * <p>{@code 导出海报} redraws this same card full-bleed — no scrim, no world behind it, no buttons —
 * and captures the framebuffer to {@code screenshots/pvpocket/}. The capture has to happen in the same
 * frame as the draw, after a flush: {@code DrawContext.fill} batches its quads, and a
 * {@code glReadPixels} taken before the batch reaches the framebuffer reads the frame from before the
 * poster was drawn. Hence {@code ctx.draw()} immediately before {@link ScreenshotRecorder}.</p>
 *
 * <p>The poster is laid out by the same code as the panel, at the full viewport instead of the panel
 * rect, and that is the point of it: at a 1280x720 window the panel is 400x216 logical, so the poster
 * is 426x240 logical and roughly 1.3x the area, and — the part that matters — it fits every round of
 * a fifteen-round series, where the panel on a 320x180 canvas fits six and says so.</p>
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
                PvButton.Style.GHOST, this::close).size(48, 20);
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
     * True on the 320x180 canvas, where the panel is 160px tall.
     *
     * <p>Three things are dropped there rather than shrunk: the section label above the round list,
     * the second meta line, and two pixels off each row. Without them the round list is left with two
     * slots, and a "round by round" section that shows two of five rounds is not that section.</p>
     */
    private boolean compact() {
        return panelH <= 176;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (posterMode) {
            drawPoster(ctx);
            return;
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = font();
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

        // The label travels with the list. Centring the rows alone left the label stranded above
        // them: the block that gets centred has to be the whole section, or the gap between a heading
        // and the thing it heads grows with the empty space.
        int headerH = compact() ? 0 : 11;
        int labelY = y;

        // Bottom-anchored: the meta block and the buttons are pinned, and the round list takes what
        // is left. Measured in that order because the list is the only part whose height is a
        // variable — everything below it is a fixed number of lines.
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

    private void drawBand(DrawContext ctx, TextRenderer tr) {
        int band = chromeH;
        // A win gets one 2px rule along the top edge of the card, and nothing else changes.
        //
        // It used to be an amber wash over the whole band, which was the wrong tool: at 34px tall
        // and full width that is the largest accent area in the interface, and it read as a muddy
        // brown lid on a near-black card. A rule is how the accent is used everywhere else, it keeps
        // the title's contrast against the surface it sits on, and it still says "this one went your
        // way" from across the room.
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
     * {@code pvpocket.gui.result.win|loss|draw}, with {@code suffix} appended.
     *
     * <p>One switch rather than six keys at the call sites: the verdict is asked for four times (the
     * title, its English line, and twice in the button row's absence) and three of those four would
     * otherwise have to repeat the same three-way branch.</p>
     */
    private String verdictKey(String suffix) {
        String state = result.won() ? "win" : result.drawn() ? "draw" : "loss";
        return "pvpocket.gui.result." + state + suffix;
    }

    /**
     * One side: a wool-coloured bar, its roster, its round wins.
     *
     * <p>The bar is the wool colour rather than the accent, because the bar answers "which side is
     * this" and the accent answers "who won" — the two questions are on the same row and would
     * otherwise both be amber. The reader's own side gets a wash behind the whole row, which is the
     * one piece of state the card cannot express in words without adding a word to every row.</p>
     */
    private void drawSide(DrawContext ctx, TextRenderer tr, MatchResult.Side side,
                          int x, int y, int w, int rowH) {
        boolean mine = side.index() == result.mySide();
        boolean took = side.index() == result.winnerSide();
        if (mine) {
            PvDraw.roundRect(ctx, x - 4, y - 1, w + 8, rowH, 3, PvTheme.ACCENT_SOFT);
        }
        PvDraw.roundRect(ctx, x, y + 1, 2, rowH - 4, 1,
                took ? PvTheme.team(side.index()) : PvTheme.BORDER_STRONG);

        String score = Integer.toString(side.score());
        int scoreW = tr.getWidth(score);
        String roster = mine
                ? side.roster() + " · " + PvLang.str("pvpocket.gui.result.you")
                : side.roster();
        PvDraw.textFitLeft(ctx, tr, roster, x + 8, y + 2, w - 10 - scoreW - 6,
                mine ? PvTheme.TEXT : PvTheme.TEXT_DIM);
        PvDraw.textRight(ctx, tr, score, x + w - 2, y + 2,
                took ? PvTheme.ACCENT : PvTheme.TEXT);
    }

    /**
     * The round list, two columns, down then across.
     *
     * <p>Two columns for the same reason the history detail uses two: a series can run to fifteen
     * rounds and one column of them does not fit a 232px card. When even two columns run out, the
     * last slot says how many are missing instead of being spent on a round — a card that silently
     * drops the last four rounds of a series is worse than one that admits to it.</p>
     */
    private void drawRounds(DrawContext ctx, TextRenderer tr, int x, int w,
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

        // Centred in the space it was given, label and all. The list sits in a box whose height comes
        // from what is below it, and on a poster — where the card is the whole viewport and a
        // fifteen-round series still leaves the cap two slots short of full — top-anchoring left a
        // third of the card empty under the last round. Centring moves that emptiness to where it
        // reads as a margin.
        int slots = shown + (truncated ? 1 : 0);
        int usedRows = Math.min(perColumn, (slots + 1) / 2);
        int blockH = headerH + usedRows * ROUND_ROW_H;
        int offset = Math.max(0, (bottom - labelY - blockH) / 2);
        if (headerH > 0) {
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.result.rounds"), x, labelY + offset,
                    PvTheme.TEXT_MUTED);
        }
        int rowsTop = labelY + headerH + offset;

        // The label column is measured, not assumed. 「第 10 局」 is half again as wide as 「第 1 局」,
        // and a fixed offset puts the mark through the last digit on exactly the round a long series
        // reaches.
        int labelW = 0;
        for (int i = 0; i < shown; i++) {
            labelW = Math.max(labelW, tr.getWidth(
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
            // strings drawn towards each other from opposite edges overlap in the middle, and which
            // round that happens on depends on the language.
            String tail = reasonLabel(round.reason()) + "  " + HomeText.duration(round.ms());
            int tailRoom = rx + colW - (markX + tr.getWidth(mark)) - 5;
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
     * Map, mode, shape, length — and, when there is room, when it was and why it ended.
     *
     * <p>No separate "opponents" line: the score block above already lists every side's roster, so
     * naming the opponents again would be the third time the card says who was in the match.</p>
     */
    private void drawMeta(DrawContext ctx, TextRenderer tr, int x, int w, int top) {
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
     * A reason code as a word, falling back to the code itself.
     *
     * <p>The twin of the same method on {@code HistoryDetail}: both cards print round reasons and
     * both have to survive a server that sends a code this build has no key for.</p>
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
     * <p>The panel rect is swapped for the viewport for the duration of the draw rather than the
     * layout being duplicated: the poster <em>is</em> this card, at a different size, and two copies
     * of a twelve-row layout is how the two drift apart. The fields are restored afterwards so the
     * panel does not inherit the poster's geometry on the next frame.</p>
     */
    private void drawPoster(DrawContext ctx) {
        // Two pixels wider than the viewport on every side, and that is not padding. The GUI space is
        // the framebuffer divided by the GUI scale and rounded down, so at 1280x720 with scale 3 the
        // logical width is 426 and 426*3 is 1278: the last two physical columns belong to no logical
        // pixel and nothing drawn in GUI space can ever reach them. Without the overfill they keep
        // whatever the world left there, which is a two-pixel stripe of the world down the edge of
        // the poster.
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
        // Flush first. `fill` batches its quads into a builder and the capture reads the
        // framebuffer, so a read taken before the batch is submitted returns the frame from before
        // the poster existed — which is the world, not the card.
        ctx.draw();
        savePoster();
    }

    /**
     * Writes the current framebuffer to {@code screenshots/pvpocket/result-<timestamp>.png}.
     *
     * <p>Its own folder rather than the vanilla screenshots root: a poster is a document the player
     * will look for later, and mixing it into a directory of F2 shots makes it one of two hundred
     * files named after a moment.</p>
     */
    private void savePoster() {
        MinecraftClient client = MinecraftClient.getInstance();
        NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
        Path dir = client.runDirectory.toPath().resolve("screenshots").resolve("pvpocket");
        Path file = dir.resolve("result-"
                + LocalDateTime.now().format(FILE_STAMP) + ".png");
        try {
            Files.createDirectories(dir);
            image.writeTo(file);
            PvPocket.LOGGER.info("Result poster written to {}", file);
            client.inGameHud.getChatHud().addMessage(PvLang.t("pvpocket.msg.result.saved",
                    file.getFileName().toString()).formatted(Formatting.GRAY));
        } catch (IOException | RuntimeException e) {
            // Runtime as well as IOException: the capture itself can throw if the framebuffer is
            // not readable (a lost GL context), and a failed export must not take the screen with it.
            PvPocket.LOGGER.error("Cannot write result poster to {}", file, e);
            client.inGameHud.getChatHud().addMessage(
                    PvLang.t("pvpocket.msg.result.save_failed").formatted(Formatting.RED));
        } finally {
            image.close();
        }
    }

    // ------------------------------------------------------------------ audit

    /**
     * Three cards for the layout audit: a win, a loss that ended early, and a four-side draw.
     *
     * <p>The shapes are the ones that decide widths — a fifteen-round series is what makes the round
     * list wrap or truncate, four sides is what makes the score block taller than the layout was
     * drawn for, and a loss is the only variant where the headline is not the accent colour.</p>
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
