package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.ClientPrefs;
import com.leipishu.pvpocket.client.audio.PvCues;
import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvMatrix;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.client.compat.PvScreens;
import com.leipishu.pvpocket.client.compat.PvSkins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The scoreboard drawn across the top of the screen during a match.
 *
 * <p>One chip per side: wool strip, heads, round wins. Two sides draw the same board as three or
 * four, so the HUD keeps one shape and one reading order whatever the roster looks like.
 *
 * <p>Heads are 12px on a 16px chip, scaled by {@code ClientPrefs.hudScale()} between 0.75x and
 * 1.2x. A player who prefers the corners can move the whole column to the bottom left instead; see
 * {@link ClientPrefs#hudAnchor()}. Both layouts share one opacity and one radius with the clock
 * plate, so the HUD reads as one surface rather than three.
 */
public final class MatchHud implements PvClientPlatform.HudLayer {

    private static final int HEAD = 12;
    /** Gap between two heads of the same side. */
    private static final int HEAD_GAP = 2;
    private static final int TOP = 3;
    /** Wool strip on the outer edge of a side - the same colour the team cards and blocks use. */
    private static final int STRIP_W = 2;

    /**
     * Opacity shared by the bar, the team cards, the round plate and the clock.
     *
     * <p>One number for all four: the HUD is read while fighting, and a bar that is more opaque
     * than the plates sitting next to it looks like a different layer of the interface.
     */
    private static final float PLATE_ALPHA = 0.72F;
    /** Gap the bottom-left column keeps from the screen edges. */
    private static final int STACK_MARGIN = 4;
    /** Vanilla's hotbar width, and the height of the strip it occupies at the bottom. */
    private static final int HOTBAR_W = 182;
    private static final int HOTBAR_LIFT = 26;

    /**
     * Gap between a corner plate and the screen edge, in pixels.
     *
     * <p>A plate is the outermost thing the HUD draws, so the gap is measured from its own edge.
     * The same allowance the bar keeps at each side, so the three read as one row.
     */
    private static final int PLATE_MARGIN = HudPlate.MARGIN;
    /** Air between a corner plate and the bar, so a wide bar never touches one. */
    private static final int PLATE_GAP = 6;

    // ------------------------------------------------------------------ team chips

    /**
     * Chip height inside the bar.
     *
     * <p>Two pixels of air above and below give the bar 20px whatever the roster size, so the HUD
     * does not change height when a match gains or loses a side.
     */
    private static final int CHIP_H = 16;
    /** A chip's inset to its first head; the wool strip owns the first two of these pixels. */
    private static final int CHIP_PAD_L = 4;
    private static final int CHIP_PAD_R = 4;
    private static final int CHIP_GAP = 3;
    /** Gap between a side's heads and its round wins. */
    private static final int SCORE_GAP = 3;
    /** The bar's own padding. Smaller than a chip's: every chip already carries a margin. */
    private static final int ROW_PAD_X = 3;
    private static final int ROW_PAD_Y = 1;
    private static final int CHIP_ROW_GAP = 2;
    /**
     * One pip per round won, three pixels square.
     *
     * <p>A pip row is as wide as a two-digit number at three wins and much quicker to read: one
     * more round and the side is out is a count of filled squares, not a subtraction.
     */
    private static final int PIP = 3;
    private static final int PIP_GAP = 1;
    /** Past four wins a pip row outgrows a number, so the number takes over. */
    private static final int MAX_PIPS = 4;

    /** Null means "no match on screen". Replaced wholesale by every payload. */
    private static MatchHudPayload state;
    private static float appearT;

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
        if (next != null) {
            announceOwnElimination(state, next);
        }
        state = next;
    }

    /**
     * Plays the "you are out" cue the first time this player's uuid shows up in the list.
     *
     * <p>The list is per round, so the uuid leaves it at the next start and can sound again. Only
     * this player's own name is worth a sound; a team-mate going out is on screen already.
     */
    private static void announceOwnElimination(MatchHudPayload previous, MatchHudPayload next) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        String me = client.player.getStringUUID();
        boolean now = contains(next.eliminated(), me);
        boolean before = previous != null && contains(previous.eliminated(), me);
        if (now && !before) {
            PvCues.eliminated();
        }
    }

    private static boolean contains(String csv, String uuid) {
        if (csv == null || csv.isEmpty()) {
            return false;
        }
        for (String part : csv.split(",")) {
            if (part.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    /** Dropped on disconnect so a stale scoreboard cannot survive into the next server. */
    public static void clear() {
        state = null;
        appearT = 0F;
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

        float d = PvAnim.dt(tickCounter.getGameTimeDeltaPartialTick(false));
        appearT = PvAnim.damp(appearT, 1F, d, 0.06F);

        Font tr = client.font;
        int screenW = client.getWindow().getGuiScaledWidth();

        // The row is scaled as a whole (frame, heads and text) about the centre of its top edge, so
        // every step stays centred and keeps the 3px gap below the screen edge.
        float scale = ClientPrefs.hudScaleFactor();
        String round = auxLabel(payload);
        int centreX = screenW / 2;

        if (ClientPrefs.hudAnchor() == ClientPrefs.HUD_ANCHOR_BOTTOM_LEFT) {
            // The stacked layout places itself from the corner and scales about it, so it needs no
            // shared transform with the row.
            drawStack(ctx, client, tr, payload, teams, scores, eliminated);
        } else {
            // The row is centred between two corner plates, so it gives the wider of them up on
            // both sides - not just on the side it happens to be nearer.
            int side = Math.max(
                    round.isEmpty() ? 0 : plateWidth(tr, round) + PLATE_MARGIN + PLATE_GAP,
                    payload.clock() >= 0
                            ? plateWidth(tr, "00:00") + PLATE_MARGIN + PLATE_GAP
                            : 0);
            int room = (int) Math.floor((screenW - side * 2) / scale);
            PvMatrix.push(ctx);
            if (scale != 1F) {
                PvMatrix.translate(ctx, centreX, TOP);
                PvMatrix.scale(ctx, scale, scale);
                PvMatrix.translate(ctx, -centreX, -TOP);
            }
            drawTeamRow(ctx, client, tr, payload, teams, scores, centreX, room, eliminated);
            PvMatrix.pop(ctx);
        }
        // Both corners are drawn last and unscaled: they are the two fixed ends of the top edge.
        // They stay in the corners in both layouts, so the clock always has its counterpart.
        if (!round.isEmpty()) {
            drawPlate(ctx, tr, round, PLATE_MARGIN, TOP,
                    payload.secondsLeft() > 0 ? PvTheme.ACCENT : PvTheme.TEXT_DIM);
        }
        if (payload.clock() >= 0) {
            drawRoundClock(ctx, tr, payload.clock(), payload.clockDown(), screenW);
        }
    }

    /** Width of a corner plate holding {@code text}: the string plus its own padding. */
    private static int plateWidth(Font tr, String text) {
        return HudPlate.width(tr, text);
    }

    /**
     * Draws one corner plate, left-anchored, sized to its own text.
     *
     * <p>Measured from the string rather than from a fixed width: the round label changes with the
     * language and with the series length, and a plate narrower than its text draws the overflow
     * past its own edge.
     */
    private static void drawPlate(GuiGraphics ctx, Font tr, String text, int x, int y, int colour) {
        HudPlate.draw(ctx, tr, text, x, y, colour, PLATE_ALPHA * appearT);
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
        // Laid out from the plate's own right edge, not from the text's: the gap belongs to the
        // frame, and a text-anchored box grows half of its padding back into the margin.
        int x = screenW - PLATE_MARGIN - plateWidth(tr, text);
        int colour = down && seconds <= 10 ? PvTheme.LIVE : PvTheme.TEXT;
        drawPlate(ctx, tr, text, x, TOP, colour);
    }

    // ------------------------------------------------------------------ team chips

    /** The chip layout picked for the current payload: one row when it fits, two when it cannot. */
    private record RowPlan(int width, int height, int rows, int perRow, int gap, int padR,
                           boolean pips) {
    }

    /**
     * Draws the bar as a row of team chips, for two sides as well as three or four.
     *
     * <p>Each side is a chip: wool strip, heads, round wins. One side per line made a 1v1v1v1 bar
     * 83px tall and 64px wide - a narrow column that pushed the play area down, and four scores
     * read along a diagonal. Side by side the bar costs width instead of height and turns the
     * scores into one horizontal sweep.
     *
     * <p>A two-side match draws through the same code: the same board with two chips, so the shape
     * of the HUD is independent of the roster size.
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
        PvDraw.roundPanel(ctx, barX, barY, plan.width(), plan.height(), PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.CARD, PLATE_ALPHA * appearT));

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
                drawChip(ctx, tr, payload, teams.get(team), team, scoreOf(scores, team),
                        plan.pips(), x, y, w, eliminated);
                x += w + plan.gap();
            }
            y += CHIP_H + CHIP_ROW_GAP;
        }
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
    private static void drawChip(GuiGraphics ctx, Font tr,
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
        int wash = PvTheme.teamWash(team, PvTheme.CARD, alive ? 0.14F : 0.05F);
        PvDraw.roundRect(ctx, x, y, w, CHIP_H, PvTheme.RADIUS,
                PvTheme.alpha(wash, PLATE_ALPHA * appearT));
        // Inside the scaled matrix: a chip carries its own wash, so the strip only has to name the
        // side and 1.5px at 0.75x still reads.
        PvDraw.roundRect(ctx, x, y, STRIP_W, CHIP_H, 2, 0, 0, 2, PvTheme.team(team));

        int headX = x + CHIP_PAD_L;
        int headY = y + (CHIP_H - HEAD) / 2;
        for (Row row : members) {
            drawHead(ctx, row.uuid(), headX, headY, eliminated.contains(row.uuid()));
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
     * "Round 2/5, next 12s" between rounds, "Round 2/5" during one.
     *
     * <p>A single-round match has no round number worth a plate, so it only appears while the next
     * round is pending.
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

    // ------------------------------------------------------------------ bottom-left column

    /**
     * Draws the column anchored to the bottom-left corner.
     *
     * <p>One card per side, stacked top to bottom in roster order. The block is scaled about the
     * anchor, so a larger HUD grows away from the corner instead of off the screen.
     *
     * <p>Every card is as wide as the widest, so the column reads as one block rather than as four
     * cards of four widths. That width is the content's own: a 1v1 chip is about 30px and gets a
     * 30px card, not a padded-out minimum.
     */
    private static void drawStack(GuiGraphics ctx, Minecraft client, Font tr,
                                  MatchHudPayload payload, List<List<Row>> teams, int[] scores,
                                  Set<UUID> eliminated) {
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            if (!teams.get(i).isEmpty()) {
                shown.add(i);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        boolean pips = payload.winsNeeded() >= 1 && payload.winsNeeded() <= MAX_PIPS;
        int contentW = 0;
        for (int team : shown) {
            contentW = Math.max(contentW, chipWidth(tr, teams.get(team), payload, pips, CHIP_PAD_R));
        }
        int stackH = shown.size() * CHIP_H + (shown.size() - 1) * CHIP_ROW_GAP;
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();

        float scale = ClientPrefs.hudScaleFactor();
        // The width is the content's own - a 1v1 chip gets a 30px card, not a padded-out minimum.
        // Content wider than the room left of the hotbar is lifted above it, never drawn over it.
        int hotbarLeft = (screenW - HOTBAR_W) / 2;
        int besideW = hotbarLeft - STACK_MARGIN * 2;
        boolean lift = Math.round(contentW * scale) > besideW;
        int cap = lift ? screenW / 2 - STACK_MARGIN * 2 : besideW;
        int cardW = Math.min(contentW, Math.max(1, cap));
        int bottom = lift ? HOTBAR_LIFT : STACK_MARGIN;

        PvMatrix.push(ctx);
        if (scale != 1F) {
            int anchorX = STACK_MARGIN;
            int anchorY = screenH - bottom;
            PvMatrix.translate(ctx, anchorX, anchorY);
            PvMatrix.scale(ctx, scale, scale);
            PvMatrix.translate(ctx, -anchorX, -anchorY);
        }
        int x = STACK_MARGIN;
        int y = screenH - bottom - stackH;
        for (int i = 0; i < shown.size(); i++) {
            int team = shown.get(i);
            drawChip(ctx, tr, payload, teams.get(team), team, scoreOf(scores, team), pips,
                    x, y + i * (CHIP_H + CHIP_ROW_GAP), cardW, eliminated);
        }
        PvMatrix.pop(ctx);
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws one head, with a grey wash over it when the player is out of this round.
     *
     * <p>The wash sits on top of the face, so it reads the same for every skin and needs no
     * shader or item-specific texture path.
     */
    private static void drawHead(GuiGraphics ctx, UUID uuid, int x, int y, boolean eliminated) {
        PvDraw.roundRect(ctx, x - 1, y - 1, HEAD + 2, HEAD + 2, 2,
                PvTheme.alpha(0xFF000000, 0.55F));
        PvSkins.draw(ctx, uuid.toString(), x, y, HEAD);
        if (eliminated) {
            PvDraw.rect(ctx, x, y, HEAD, HEAD, PvTheme.alpha(0xFF8A8A8A, 0.72F));
        }
    }

    private static String roundLabel(MatchHudPayload payload) {
        if (payload.maxRounds() <= 1) {
            return PvLang.str("pvpocket.gui.hud.single_round");
        }
        return PvLang.str("pvpocket.gui.hud.round", payload.round(), payload.maxRounds());
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
