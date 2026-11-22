package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.ClientPrefs;
import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvMatrix;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.client.compat.PvScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The scoreboard drawn across the top of the screen during a match.
 *
 * <p>Both sides share one bar: heads on the left, the round counter and score in the middle,
 * heads on the right. A 1v1 also names each player; a larger match drops the names and lays the
 * heads out side by side, wrapping into two rows once a side has four or more players.
 *
 * <p>The bar is centred on the screen, clear of the top-right corner where vanilla draws status
 * effects. Heads are 14px on a 16px row, scaled by {@code ClientPrefs.hudScale()} between 0.75x and
 * 1.2x.
 */
public final class MatchHud implements PvClientPlatform.HudLayer {

    private static final int ROW_H = 16;
    private static final int HEAD = 14;
    private static final int PAD_X = 8;
    private static final int PAD_Y = 4;
    private static final int NAME_GAP = 4;
    /** Gap between two heads of the same side. */
    private static final int HEAD_GAP = 2;
    private static final int BLOCK_GAP = 9;
    private static final int MIN_H = 24;
    private static final int TOP = 3;
    /** Wool strip on the outer edge of a side - the same colour the team cards and blocks use. */
    private static final int STRIP_W = 2;
    private static final float FLASH_TIME = 0.32F;

    /**
     * Gap between the clock plate and the screen edge, in pixels.
     *
     * <p>The plate is the outermost thing the HUD draws, so the gap is measured from its own right
     * edge. 4 is the same allowance the scoreboard bar keeps at each side ({@code screenW - 8} in
     * the width test), so the two read as one row.
     */
    private static final int CLOCK_MARGIN = 4;
    /** Clock plate height and inner padding; the text is centred in the plate. */
    private static final int CLOCK_H = 14;
    private static final int CLOCK_PAD_X = 6;

    // ------------------------------------------------------------------ three- or four-side row

    /**
     * Chip height inside the bar.
     *
     * <p>Two pixels of air above and below give the bar 24px, the same height the two-side bar has,
     * so the HUD does not change size when a match adds a third side.
     */
    private static final int CHIP_H = 20;
    /** A chip's inset to its first head; the wool strip owns the first two of these pixels. */
    private static final int CHIP_PAD_L = 5;
    private static final int CHIP_PAD_R = 6;
    private static final int CHIP_GAP = 4;
    /** Gap between a side's heads and its round wins. */
    private static final int SCORE_GAP = 4;
    /** The bar's own padding. Smaller than {@link #PAD_X}: every chip already carries a margin. */
    private static final int ROW_PAD_X = 4;
    private static final int ROW_PAD_Y = 2;
    private static final int CHIP_ROW_GAP = 2;
    /**
     * One pip per round won, four pixels square.
     *
     * <p>A pip row is as wide as a two-digit number at three wins and much quicker to read: one
     * more round and the side is out is a count of filled squares, not a subtraction.
     */
    private static final int PIP = 4;
    private static final int PIP_GAP = 1;
    /** Past four wins a pip row outgrows a number, so the number takes over. */
    private static final int MAX_PIPS = 4;
    /** Gap between the bar and the top of the round line's plate, and the plate's height. */
    private static final int AUX_H = 15;
    private static final int AUX_GAP = 4;

    /** Null means "no match on screen". Replaced wholesale by every payload. */
    private static MatchHudPayload state;
    private static float appearT;
    private static float flashT;
    private static String lastScore = "";

    private MatchHud() {
    }

    public static void register() {
        PvClientPlatform.get().onHudRender(new MatchHud());
    }

    /** Called on the client thread when a payload arrives. */
    public static void update(MatchHudPayload payload) {
        MatchHudPayload next = payload != null && payload.active() ? payload : null;
        // Fade in when a match appears, not on every payload: the intermission countdown re-pushes
        // this packet once a second.
        if (state == null && next != null) {
            appearT = 0F;
        }
        state = next;
    }

    /** Dropped on disconnect so a stale scoreboard cannot survive into the next server. */
    public static void clear() {
        state = null;
        appearT = 0F;
        flashT = 0F;
        lastScore = "";
    }

    /**
     * Returns whether a match HUD is currently on screen.
     *
     * <p>Used by the death-screen gate: a player who dies inside an arena round must not see the
     * vanilla "You Died" screen, since the next round starts on its own.
     */
    public static boolean isActive() {
        return state != null;
    }

    @Override
    public void draw(GuiGraphics ctx, DeltaTracker tickCounter) {
        MatchHudPayload payload = state;
        if (payload == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        // F1 hides the HUD; the scoreboard respects it so a clean screenshot is possible.
        if (client.options.hideGui || PvScreens.current(client) != null) {
            return;
        }

        List<List<Row>> teams = parseTeams(payload.teams());
        int[] scores = parseScores(payload.scores());
        Set<UUID> eliminated = parseEliminated(payload.eliminated());
        if (teams.isEmpty()) {
            return;
        }
        int players = 0;
        for (List<Row> side : teams) {
            players += side.size();
        }
        boolean oneOnOne = teams.size() == 2 && players == 2;

        float d = PvAnim.dt(tickCounter.getGameTimeDeltaPartialTick(false));
        appearT = PvAnim.damp(appearT, 1F, d, 0.06F);

        // A score change flashes the number: it is 9px tall between two heads, so a round win is
        // easy to miss during a fight.
        String scoreKey = payload.scores();
        if (!scoreKey.equals(lastScore)) {
            if (!lastScore.isEmpty()) {
                flashT = 1F;
            }
            lastScore = scoreKey;
        }
        flashT = Math.max(0F, flashT - d / FLASH_TIME);

        Font tr = client.font;
        int screenW = client.getWindow().getGuiScaledWidth();

        // The bar is scaled as a whole (frame, heads and text) about the centre of its top edge, so
        // every step stays centred and keeps the 3px gap below the screen edge.
        float scale = ClientPrefs.hudScaleFactor();
        // Width the bar may use in its own pixels. At 1.2x a fifth less of the screen is available,
        // so the name-dropping fallback measures against this rather than the raw width.
        int room = (int) Math.floor((screenW - 8) / scale);
        // A three- or four-side row is wide enough to reach the round clock in the top-right
        // corner, so that branch also subtracts the clock plate from its budget.
        int clockW = payload.clock() >= 0
                ? tr.width("00:00") + CLOCK_PAD_X * 2 + CLOCK_MARGIN + 8
                : 0;
        int rowRoom = (int) Math.floor((screenW - 8 - clockW) / scale);
        int centreX = screenW / 2;

        PvMatrix.push(ctx);
        if (scale != 1F) {
            PvMatrix.translate(ctx, centreX, TOP);
            PvMatrix.scale(ctx, scale, scale);
            PvMatrix.translate(ctx, -centreX, -TOP);
        }
        Bar bar = null;
        int barH = 0;
        if (teams.size() == 2) {
            bar = drawHeadToHead(ctx, client, tr, payload, teams, scores, centreX, room,
                    eliminated, oneOnOne);
            barH = bar == null ? 0 : bar.h();
        } else {
            drawTeamRow(ctx, client, tr, payload, teams, scores, centreX, rowRoom, eliminated);
        }
        PvMatrix.pop(ctx);
        // The team strips are drawn after the matrix is restored: their geometry is a fixed pixel
        // count, so it is computed from the panel's on-screen rectangle.
        if (bar != null) {
            drawTeamStrips(ctx, bar, scale, centreX);
        }
        // The intermission countdown sits under the two-side bar, which has no line of its own.
        // The chip row draws a line that carries both the round and the countdown.
        if (teams.size() == 2 && payload.secondsLeft() > 0) {
            int under = TOP + Math.round((barH > 0 ? barH + 10 : 34F) * scale);
            drawCountdown(ctx, tr, payload.secondsLeft(), centreX, under);
        }
        if (payload.clock() >= 0) {
            drawRoundClock(ctx, tr, payload.clock(), payload.clockDown(), screenW);
        }
    }

    /**
     * Draws the in-round clock in the top-right corner.
     *
     * <p>A countdown turns warm inside its last ten seconds so the finish reads without counting;
     * a stopwatch stays muted. Both use {@code m:ss}, which needs no translation and stays compact
     * at every HUD scale.
     */
    private static void drawRoundClock(GuiGraphics ctx, Font tr, int seconds, boolean down,
                                       int screenW) {
        String text = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        int w = tr.width(text);
        int plateW = w + CLOCK_PAD_X * 2;
        // Laid out from the plate's own right edge, not from the text's: the gap belongs to the
        // frame, and a text-anchored box grows half of its padding back into the margin.
        int x = screenW - CLOCK_MARGIN - plateW;
        int y = TOP;
        int colour = down && seconds <= 10 ? PvTheme.LIVE : PvTheme.TEXT;
        PvDraw.roundRect(ctx, x, y, plateW, CLOCK_H, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.CARD, 0.72F));
        PvDraw.text(ctx, tr, text, x + CLOCK_PAD_X, y + (CLOCK_H - 8) / 2 + 1, colour);
    }

    /**
     * Draws "Next round in N" under the scoreboard.
     *
     * <p>The plate is drawn because the arena behind it is being reverted, so the background is
     * whatever map comes next.
     */
    private static void drawCountdown(GuiGraphics ctx, Font tr, int seconds, int centreX, int y) {
        String text = PvLang.str("pvpocket.hud.next_round", seconds);
        int w = tr.width(text);
        PvDraw.roundRect(ctx, centreX - w / 2 - 6, y - 3, w + 12, 15, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.CARD, 0.78F));
        PvDraw.text(ctx, tr, text, centreX - w / 2, y, PvTheme.ACCENT);
    }

    // ------------------------------------------------------------------ two sides

    /** A panel rectangle in layout coordinates, so the strips can be placed after scaling. */
    private record Bar(int x, int y, int w, int h) {
    }

    private Bar drawHeadToHead(GuiGraphics ctx, Minecraft client, Font tr,
                               MatchHudPayload payload, List<List<Row>> teams, int[] scores,
                               int centreX, int room, Set<UUID> eliminated, boolean oneOnOne) {
        List<Row> left = teams.get(0);
        List<Row> right = teams.get(1);
        if (left.isEmpty() && right.isEmpty()) {
            return null;
        }
        int leftScore = scoreOf(scores, 0);
        int rightScore = scoreOf(scores, 1);

        // Names belong to a 1v1. Every larger match keeps the same bar and drops them, which is
        // also the fallback when a 1v1 name does not fit at the current HUD scale.
        boolean names = oneOnOne;
        Layout layout = measure(tr, left, right, payload, leftScore, rightScore, names);
        if (layout.width() > room && names) {
            names = false;
            layout = measure(tr, left, right, payload, leftScore, rightScore, names);
        }

        int barX = centreX - layout.width() / 2;
        int barY = TOP;
        PvDraw.shadow(ctx, barX, barY, layout.width(), layout.height(), PvTheme.RADIUS);
        PvDraw.roundPanel(ctx, barX, barY, layout.width(), layout.height(), PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.PANEL, 0.94F * appearT));

        // The wool strips are drawn in screen space: their width and inset are pixels, so scaling
        // them with the bar would leave a 0.75px gap at the smallest step and 1.2px at the largest.

        drawSide(ctx, tr, client, left, true, barX + PAD_X, barY, layout.height(), names,
                leftScore, payload.winsNeeded(), eliminated);
        drawSide(ctx, tr, client, right, false, barX + layout.width() - PAD_X, barY, layout.height(),
                names, rightScore, payload.winsNeeded(), eliminated);
        String score = leftScore + " : " + rightScore;
        drawCentre(ctx, tr, payload, score, barX + PAD_X + layout.leftW() + BLOCK_GAP,
                barY, layout.height(), layout.centreW());

        // Divider ticks between the side blocks and the centre, so the three parts read as three
        // parts even when a name has the same colour as the score.
        int divTop = barY + 6;
        int divH = layout.height() - 12;
        PvDraw.vLine(ctx, barX + PAD_X + layout.leftW() + BLOCK_GAP / 2, divTop, divH, PvTheme.DIVIDER);
        PvDraw.vLine(ctx, barX + PAD_X + layout.leftW() + BLOCK_GAP + layout.centreW()
                + BLOCK_GAP / 2, divTop, divH, PvTheme.DIVIDER);
        return new Bar(barX, barY, layout.width(), layout.height());
    }

    /**
     * Draws the two wool strips on the outer edges of a two-side bar.
     *
     * <p>The strips are the only part of the bar given in absolute pixels: one pixel inside the
     * edge, two wide. They are drawn in screen space from the panel rectangle mapped through the
     * same pivot, so the width stays exact at every scale step.
     *
     * <p>The corner radius follows the panel radius, so the strip ends do not sit as square nubs.
     */
    private static void drawTeamStrips(GuiGraphics ctx, Bar bar, float scale, int centreX) {
        int left = Math.round(centreX + (bar.x() - centreX) * scale);
        int right = Math.round(centreX + (bar.x() + bar.w() - centreX) * scale);
        int top = Math.round(TOP + (bar.y() - TOP) * scale);
        int bottom = Math.round(TOP + (bar.y() + bar.h() - TOP) * scale);
        int stripH = Math.max(1, bottom - top - 2);
        // One less than the panel radius: the strip sits a pixel inside the edge.
        int r = Math.max(1, Math.round(PvTheme.RADIUS * scale) - 1);
        PvDraw.roundRect(ctx, left + 1, top + 1, STRIP_W, stripH, r, 0, 0, r, PvTheme.team(0));
        PvDraw.roundRect(ctx, right - 1 - STRIP_W, top + 1, STRIP_W, stripH, 0, r, r, 0,
                PvTheme.team(1));
    }

    // ------------------------------------------------------------------ three or four sides

    /** The chip layout picked for the current payload: one row when it fits, two when it cannot. */
    private record RowPlan(int width, int height, int rows, int perRow, int gap, int padR,
                           boolean pips) {
    }

    /**
     * Draws one row of team chips for a three- or four-side match.
     *
     * <p>Each side is a chip: wool strip, heads, round wins. One side per line made a 1v1v1v1 bar
     * 83px tall and 64px wide - a narrow column that pushed the play area down, and four scores
     * read along a diagonal. Side by side the bar costs width instead of height, keeps the 24px
     * the two-side bar already uses, and turns four scores into one horizontal sweep.
     */
    private static void drawTeamRow(GuiGraphics ctx, Minecraft client, Font tr,
                                   MatchHudPayload payload, List<List<Row>> teams, int[] scores,
                                   int centreX, int room, Set<UUID> eliminated) {
        // An empty side would be a chip with nothing but a score, so it is skipped rather than
        // drawn as a hole in the row.
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            if (!teams.get(i).isEmpty()) {
                shown.add(i);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        RowPlan plan = planRow(tr, teams, payload, shown, room);
        int barX = centreX - plan.width() / 2;
        int barY = TOP;
        PvDraw.shadow(ctx, barX, barY, plan.width(), plan.height(), PvTheme.RADIUS);
        PvDraw.roundPanel(ctx, barX, barY, plan.width(), plan.height(), PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.PANEL, 0.94F * appearT));

        int y = barY + ROW_PAD_Y;
        for (int row = 0; row * plan.perRow() < shown.size(); row++) {
            int start = row * plan.perRow();
            List<Integer> line = shown.subList(start, Math.min(shown.size(), start + plan.perRow()));
            int rowW = rowWidth(tr, teams, payload, line, plan.pips(), plan.gap(), plan.padR());
            // Every line is centred on its own, so a wrapped row reads as a second tier of the
            // same bar rather than as a left-aligned overflow.
            int x = centreX - rowW / 2;
            for (int team : line) {
                int w = chipWidth(tr, teams.get(team), payload, plan.pips(), plan.padR());
                drawChip(ctx, client, tr, payload, teams.get(team), team, scoreOf(scores, team),
                        plan.pips(), x, y, w, eliminated);
                x += w + plan.gap();
            }
            y += CHIP_H + CHIP_ROW_GAP;
        }
        drawAux(ctx, tr, payload, centreX, barY + plan.height() + AUX_GAP);
    }

    /**
     * Picks the widest chip layout that fits {@code room}, stepping down at most three times.
     *
     * <p>The steps exist because the row has to survive a narrow GUI at a large scale with four
     * full sides: pips fall back to a number, then the padding tightens, and only then does the
     * row wrap. A two-line chip bar is still less than half the height the stacked one needed.
     */
    private static RowPlan planRow(Font tr, List<List<Row>> teams, MatchHudPayload payload,
                                   List<Integer> shown, int room) {
        boolean pips = payload.winsNeeded() >= 1 && payload.winsNeeded() <= MAX_PIPS;
        RowPlan plan = measureRow(tr, teams, payload, shown, pips, CHIP_GAP, CHIP_PAD_R);
        if (plan.width() > room && pips) {
            pips = false;
            plan = measureRow(tr, teams, payload, shown, false, CHIP_GAP, CHIP_PAD_R);
        }
        if (plan.width() > room) {
            plan = measureRow(tr, teams, payload, shown, pips, 2, 4);
        }
        if (plan.width() > room) {
            plan = measureWrapped(tr, teams, payload, shown, pips);
        }
        return plan;
    }

    private static RowPlan measureRow(Font tr, List<List<Row>> teams, MatchHudPayload payload,
                                      List<Integer> shown, boolean pips, int gap, int padR) {
        int width = rowWidth(tr, teams, payload, shown, pips, gap, padR) + ROW_PAD_X * 2;
        return new RowPlan(width, ROW_PAD_Y * 2 + CHIP_H, 1, shown.size(), gap, padR, pips);
    }

    /** Two tiers, each centred; the widest tier sets the bar. */
    private static RowPlan measureWrapped(Font tr, List<List<Row>> teams, MatchHudPayload payload,
                                          List<Integer> shown, boolean pips) {
        int perRow = (shown.size() + 1) / 2;
        int width = 0;
        for (int start = 0; start < shown.size(); start += perRow) {
            List<Integer> line = shown.subList(start, Math.min(shown.size(), start + perRow));
            width = Math.max(width,
                    rowWidth(tr, teams, payload, line, pips, CHIP_GAP, CHIP_PAD_R));
        }
        int rows = (shown.size() + perRow - 1) / perRow;
        int height = ROW_PAD_Y * 2 + rows * CHIP_H + (rows - 1) * CHIP_ROW_GAP;
        return new RowPlan(width + ROW_PAD_X * 2, height, rows, perRow, CHIP_GAP, CHIP_PAD_R, pips);
    }

    private static int rowWidth(Font tr, List<List<Row>> teams, MatchHudPayload payload,
                                List<Integer> line, boolean pips, int gap, int padR) {
        int w = 0;
        for (int i = 0; i < line.size(); i++) {
            w += chipWidth(tr, teams.get(line.get(i)), payload, pips, padR);
            if (i > 0) {
                w += gap;
            }
        }
        return w;
    }

    private static int chipWidth(Font tr, List<Row> members, MatchHudPayload payload,
                                 boolean pips, int padR) {
        int heads = members.size() * HEAD + Math.max(0, members.size() - 1) * HEAD_GAP;
        return CHIP_PAD_L + heads + SCORE_GAP + scoreWidth(tr, payload, pips) + padR;
    }

    private static int scoreWidth(Font tr, MatchHudPayload payload, boolean pips) {
        if (!pips) {
            return tr.width("0");
        }
        int n = payload.winsNeeded();
        return n * PIP + Math.max(0, n - 1) * PIP_GAP;
    }

    /**
     * Draws one side's chip: washed in its wool colour, wool strip on the left, heads, round wins.
     *
     * <p>A side that is out of the round keeps its chip so the row does not reflow mid-round; the
     * wash and the pips fade towards the panel instead.
     */
    private static void drawChip(GuiGraphics ctx, Minecraft client, Font tr,
                                 MatchHudPayload payload, List<Row> members, int team, int score,
                                 boolean pips, int x, int y, int w, Set<UUID> eliminated) {
        boolean alive = false;
        for (Row row : members) {
            if (!eliminated.contains(row.uuid())) {
                alive = true;
                break;
            }
        }
        boolean matchPoint = score + 1 >= payload.winsNeeded();
        int wash = PvTheme.teamWash(team, PvTheme.PANEL, alive ? 0.14F : 0.05F);
        PvDraw.roundRect(ctx, x, y, w, CHIP_H, PvTheme.RADIUS,
                PvTheme.alpha(wash, (alive ? 0.94F : 0.72F) * appearT));
        // The strip is drawn inside the scaled matrix, unlike the two-side bar's: a chip carries
        // its own wash, so the strip only has to name the side and 1.5px at 0.75x still reads.
        PvDraw.roundRect(ctx, x, y, STRIP_W, CHIP_H, 2, 0, 0, 2, PvTheme.team(team));

        int headX = x + CHIP_PAD_L;
        int headY = y + (CHIP_H - HEAD) / 2;
        for (Row row : members) {
            drawHead(ctx, client, row.uuid(), headX, headY, eliminated.contains(row.uuid()));
            headX += HEAD + HEAD_GAP;
        }
        int scoreX = headX - HEAD_GAP + SCORE_GAP;
        if (pips) {
            drawPips(ctx, payload.winsNeeded(), score, team, alive, matchPoint, scoreX,
                    y + (CHIP_H - PIP) / 2);
            return;
        }
        int colour = !alive ? PvTheme.TEXT_MUTED
                : matchPoint ? PvTheme.LIVE : PvTheme.teamInk(team);
        PvDraw.text(ctx, tr, Integer.toString(score), scoreX, y + (CHIP_H - 8) / 2 + 1, colour);
    }

    /**
     * Draws one square per round won, the rest at 30% so the count is the gap between them.
     *
     * <p>At match point the whole row turns the HUD's green: one more round ends it, and that is
     * the one thing worth spotting at a glance in a three-way fight.
     */
    private static void drawPips(GuiGraphics ctx, int total, int won, int team, boolean alive,
                                 boolean matchPoint, int x, int y) {
        int lit = !alive ? PvTheme.TEXT_MUTED : matchPoint ? PvTheme.LIVE : PvTheme.teamInk(team);
        int off = PvTheme.alpha(lit, 0.30F);
        for (int i = 0; i < total; i++) {
            PvDraw.roundRect(ctx, x + i * (PIP + PIP_GAP), y, PIP, PIP, 1,
                    i < won ? lit : off);
        }
    }

    /**
     * Draws the round and countdown line under a three- or four-side bar.
     *
     * <p>The chip row has no centre block to hold it, and putting the round inside the bar would
     * leave the chips off-centre for an odd number of sides. Below the bar it costs one 15px line
     * and steals no width from the sides. The plate is drawn because the arena behind it is being
     * reverted between rounds, so the background is whatever map comes next.
     *
     * <p>{@code y} is the top of the plate, not the baseline of the text.
     */
    private static void drawAux(GuiGraphics ctx, Font tr, MatchHudPayload payload, int centreX,
                                int y) {
        String text = auxLabel(payload);
        if (text.isEmpty()) {
            return;
        }
        int w = tr.width(text);
        PvDraw.roundRect(ctx, centreX - w / 2 - 6, y, w + 12, AUX_H, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.CARD, 0.78F * appearT));
        PvDraw.textCenter(ctx, tr, text, centreX, y + 4,
                payload.secondsLeft() > 0 ? PvTheme.ACCENT : PvTheme.TEXT_DIM);
    }

    /**
     * "Round 2/5, next 12s" between rounds, "Round 2/5" during one.
     *
     * <p>A single-round match has no round number worth the line, so it only appears while the
     * next round is pending.
     */
    private static String auxLabel(MatchHudPayload payload) {
        int seconds = payload.secondsLeft();
        if (payload.maxRounds() > 1) {
            return seconds > 0
                    ? PvLang.str("pvpocket.hud.round_next", payload.round(), payload.maxRounds(),
                    seconds)
                    : roundLabel(payload);
        }
        return seconds > 0 ? PvLang.str("pvpocket.hud.next_round", seconds) : "";
    }

    private static int scoreOf(int[] scores, int team) {
        return team < scores.length ? scores[team] : 0;
    }

    // ------------------------------------------------------------------ measuring

    private record Layout(int width, int height, int leftW, int centreW, int rightW) {
    }

    private static Layout measure(Font tr, List<Row> left, List<Row> right,
                                  MatchHudPayload payload, int leftScore, int rightScore,
                                  boolean names) {
        int leftW = blockWidth(tr, left, names);
        int rightW = blockWidth(tr, right, names);
        String round = roundLabel(payload);
        String score = leftScore + " : " + rightScore;
        int centreW = Math.max(tr.width(round), tr.width(score));

        int blocks = 0;
        if (leftW > 0) {
            blocks++;
        }
        if (rightW > 0) {
            blocks++;
        }
        int width = PAD_X * 2 + leftW + centreW + rightW + BLOCK_GAP * blocks;
        int rows = Math.max(lineCount(left.size(), names), lineCount(right.size(), names));
        int height = Math.max(MIN_H, rows * ROW_H + PAD_Y * 2);
        return new Layout(width, height, leftW, centreW, rightW);
    }

    /** Head rows a side takes: one up to three players, two above that. */
    private static int lineCount(int players, boolean names) {
        if (names) {
            return players;
        }
        return players <= 3 ? 1 : 2;
    }

    /** Heads on the widest row of a side's grid. */
    private static int columnCount(int players, boolean names) {
        if (names) {
            return 1;
        }
        return players <= 3 ? players : (players + 1) / 2;
    }

    private static int blockWidth(Font tr, List<Row> rows, boolean names) {
        if (rows.isEmpty()) {
            return 0;
        }
        if (!names) {
            return columnCount(rows.size(), false) * (HEAD + HEAD_GAP) - HEAD_GAP;
        }
        int max = 0;
        for (Row row : rows) {
            max = Math.max(max, HEAD + NAME_GAP + tr.width(row.name()));
        }
        return max;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the round counter in dim grey above the score in accent.
     *
     * <p>Two lines answer two questions and keep the centre block narrow enough for a 2v2 bar at 2x
     * GUI scale. The pair is centred vertically, so a bar that grew to two head rows keeps the
     * score on the same line as the heads.
     */
    private static void drawCentre(GuiGraphics ctx, Font tr, MatchHudPayload payload,
                                   String score, int x, int barY, int barH, int width) {
        int centreX = x + width / 2;
        int top = barY + Math.max(4, (barH - 17) / 2);
        PvDraw.textCenter(ctx, tr, roundLabel(payload), centreX, top, PvTheme.TEXT_DIM);
        int color = PvTheme.mix(PvTheme.ACCENT, 0xFFFFFFFF, flashT * 0.85F);
        PvDraw.textCenter(ctx, tr, score, centreX, top + 9, color);
    }

    /**
     * Draws one head, with a grey wash over it when the player is out of this round.
     *
     * <p>The wash sits on top of the face, so it reads the same for every skin and needs no
     * shader or item-specific texture path.
     */
    private static void drawHead(GuiGraphics ctx, Minecraft client, UUID uuid,
                                 int x, int y, boolean eliminated) {
        PvDraw.roundRect(ctx, x - 1, y - 1, HEAD + 2, HEAD + 2, 2,
                PvTheme.alpha(0xFF000000, 0.55F));
        PlayerFaceRenderer.draw(ctx, skinOf(client, uuid), x, y, HEAD);
        if (eliminated) {
            PvDraw.rect(ctx, x, y, HEAD, HEAD, PvTheme.alpha(0xFF8A8A8A, 0.72F));
        }
    }

    private static void drawSide(GuiGraphics ctx, Font tr, Minecraft client,
                                 List<Row> rows, boolean left, int edgeX, int barY, int barH,
                                 boolean names, int score, int winsNeeded, Set<UUID> eliminated) {
        if (rows.isEmpty()) {
            return;
        }
        // A side at match point is tinted, so one more round reads without the numbers.
        int tint = score + 1 >= winsNeeded ? PvTheme.LIVE : PvTheme.TEXT;
        if (names) {
            drawNamedColumn(ctx, tr, client, rows, left, edgeX, barY, barH, tint, eliminated);
            return;
        }
        int cellW = HEAD + HEAD_GAP;
        List<List<Row>> lines = gridOf(rows);
        int y = barY + (barH - lines.size() * ROW_H) / 2;
        for (List<Row> line : lines) {
            int x = left ? edgeX : edgeX - line.size() * cellW + HEAD_GAP;
            for (Row row : line) {
                drawHead(ctx, client, row.uuid(), x, y, eliminated.contains(row.uuid()));
                x += cellW;
            }
            y += ROW_H;
        }
    }

    /** One row per player: head and name. The 1v1 layout. */
    private static void drawNamedColumn(GuiGraphics ctx, Font tr, Minecraft client,
                                        List<Row> rows, boolean left, int edgeX, int barY,
                                        int barH, int tint, Set<UUID> eliminated) {
        int y = barY + (barH - rows.size() * ROW_H) / 2;
        for (Row row : rows) {
            int headX = left ? edgeX : edgeX - HEAD;
            drawHead(ctx, client, row.uuid(), headX, y, eliminated.contains(row.uuid()));
            int textY = y + (HEAD - 8) / 2 + 1;
            if (left) {
                PvDraw.text(ctx, tr, row.name(), headX + HEAD + NAME_GAP, textY, tint);
            } else {
                PvDraw.textRight(ctx, tr, row.name(), headX - NAME_GAP, textY, tint);
            }
            y += ROW_H;
        }
    }

    /**
     * Splits a side's heads into one row up to three players, two rows above that.
     *
     * <p>Four players give two and two; five give two then three.
     */
    private static List<List<Row>> gridOf(List<Row> rows) {
        List<List<Row>> lines = new ArrayList<>();
        int n = rows.size();
        if (n <= 3) {
            lines.add(rows);
            return lines;
        }
        int first = n / 2;
        lines.add(rows.subList(0, first));
        lines.add(rows.subList(first, n));
        return lines;
    }

    private static String roundLabel(MatchHudPayload payload) {
        if (payload.maxRounds() <= 1) {
            return PvLang.str("pvpocket.gui.hud.single_round");
        }
        return PvLang.str("pvpocket.gui.hud.round", payload.round(), payload.maxRounds());
    }

    /**
     * Returns the player's skin, from the live entity when it is loaded and the default table
     * otherwise.
     *
     * <p>The other side may not be in this client's world, spectating or between rounds, and a
     * default head is better than a missing row.
     */
    private static PlayerSkin skinOf(Minecraft client, UUID uuid) {
        if (client.level != null) {
            for (AbstractClientPlayer player : client.level.players()) {
                if (player.getUUID().equals(uuid)) {
                    return player.getSkin();
                }
            }
        }
        return DefaultPlayerSkin.get(uuid);
    }

    // ------------------------------------------------------------------ parsing

    private record Row(String name, UUID uuid) {
    }

    /**
     * Parses {@code "Dev1|uuid,Dev2|uuid;Dev3|uuid"} into one list per side.
     *
     * <p>A malformed row or side is dropped rather than thrown: the HUD is decoration and must not
     * take the client down mid-duel.
     */
    private static List<List<Row>> parseTeams(String encoded) {
        List<List<Row>> teams = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return teams;
        }
        for (String side : encoded.split(";", -1)) {
            teams.add(parseSide(side));
        }
        return teams;
    }

    private static List<Row> parseSide(String encoded) {
        List<Row> rows = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return rows;
        }
        for (String part : encoded.split(",")) {
            int bar = part.indexOf('|');
            if (bar <= 0 || bar == part.length() - 1) {
                continue;
            }
            try {
                rows.add(new Row(part.substring(0, bar), UUID.fromString(part.substring(bar + 1))));
            } catch (IllegalArgumentException ignored) {
                // Bad uuid: skip this row only.
            }
        }
        return rows;
    }

    /** Parses the comma-separated uuid list of players who are out of this round. */
    private static Set<UUID> parseEliminated(String encoded) {
        Set<UUID> out = new HashSet<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String part : encoded.split(",")) {
            try {
                out.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // Bad uuid: skip this entry only.
            }
        }
        return out;
    }

    private static int[] parseScores(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new int[0];
        }
        String[] parts = encoded.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }
}
