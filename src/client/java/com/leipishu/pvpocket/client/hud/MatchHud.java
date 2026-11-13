package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.ClientPrefs;
import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The scoreboard drawn across the top of the screen during a match.
 *
 * <p>Two sides draw one bar of three blocks: the left heads, the round counter and score, the right
 * heads. Three or four sides draw a stacked list instead, one row per side, with heads on the left
 * and round wins on the right.
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
    /** Gap between a name and its health readout; see {@code g.showOpponentHealth}. */
    private static final int HEALTH_GAP = 4;
    private static final int BLOCK_GAP = 9;
    private static final int MIN_H = 24;
    private static final int TOP = 3;
    /** Wool strip on the outer edge of a side - the same colour the team cards and blocks use. */
    private static final int STRIP_W = 2;
    private static final float FLASH_TIME = 0.32F;

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
    public void render(GuiGraphics ctx, DeltaTracker tickCounter) {
        MatchHudPayload payload = state;
        if (payload == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        // F1 hides the HUD; the scoreboard respects it so a clean screenshot is possible.
        if (client.options.hideGui || client.screen != null) {
            return;
        }

        List<List<Row>> teams = parseTeams(payload.teams());
        int[] scores = parseScores(payload.scores());
        if (teams.isEmpty()) {
            return;
        }

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
        int centreX = screenW / 2;

        ctx.pose().pushPose();
        if (scale != 1F) {
            ctx.pose().translate(centreX, TOP, 0);
            ctx.pose().scale(scale, scale, 1F);
            ctx.pose().translate(-centreX, -TOP, 0);
        }
        Bar bar = null;
        if (teams.size() == 2) {
            bar = drawHeadToHead(ctx, client, tr, payload, teams, scores, centreX, room);
        } else {
            drawStacked(ctx, client, tr, payload, teams, scores, centreX);
        }
        ctx.pose().popPose();
        // The team strips are drawn after the matrix is restored: their geometry is a fixed pixel
        // count, so it is computed from the panel's on-screen rectangle.
        if (bar != null) {
            drawTeamStrips(ctx, bar, scale, centreX);
        }
        // The intermission countdown sits under the bar: it answers how long the wait is, which
        // nobody reads while aiming.
        if (payload.secondsLeft() > 0) {
            drawCountdown(ctx, tr, payload.secondsLeft(), centreX, scale);
        }
    }

    /**
     * Draws "Next round in N" under the scoreboard.
     *
     * <p>The plate is drawn because the arena behind it is being reverted, so the background is
     * whatever map comes next.
     */
    private static void drawCountdown(GuiGraphics ctx, Font tr, int seconds, int centreX, float scale) {
        String text = PvLang.str("pvpocket.hud.next_round", seconds);
        int w = tr.width(text);
        int y = TOP + Math.round(34F * scale);
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
                               int centreX, int room) {
        List<Row> left = teams.get(0);
        List<Row> right = teams.get(1);
        if (left.isEmpty() && right.isEmpty()) {
            return null;
        }
        int leftScore = scoreOf(scores, 0);
        int rightScore = scoreOf(scores, 1);

        // Two passes over the same measurement: with names first, then heads alone when that does
        // not fit. Shrinking the type would make the score unreadable at 2x GUI scale.
        boolean names = true;
        Layout layout = measure(tr, left, right, payload, leftScore, rightScore, names);
        if (layout.width() > room) {
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
                leftScore, payload.winsNeeded(), payload.showHealth());
        drawSide(ctx, tr, client, right, false, barX + layout.width() - PAD_X, barY, layout.height(),
                names, rightScore, payload.winsNeeded(), payload.showHealth());
        String score = leftScore + " : " + rightScore;
        drawCentre(ctx, tr, payload, score, barX + PAD_X + layout.leftW() + BLOCK_GAP,
                barY, layout.centreW());

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

    /**
     * Draws one row per side: heads on the left, the round wins right-aligned.
     *
     * <p>Heads are capped at four per row. A 4x4 match is sixteen players, and sixteen heads would
     * be as wide as the screen.
     */
    private void drawStacked(GuiGraphics ctx, Minecraft client, Font tr,
                             MatchHudPayload payload, List<List<Row>> teams, int[] scores,
                             int centreX) {
        int rows = teams.size();
        int headerH = 11;
        int headsW = 4 * (HEAD + 2);
        int scoreW = tr.width("00") + 6;
        int width = PAD_X * 2 + STRIP_W + 4 + headsW + BLOCK_GAP + scoreW;
        int height = PAD_Y * 2 + headerH + rows * ROW_H;
        int barX = centreX - width / 2;
        int barY = TOP;

        PvDraw.shadow(ctx, barX, barY, width, height, PvTheme.RADIUS);
        PvDraw.roundPanel(ctx, barX, barY, width, height, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.PANEL, 0.94F * appearT));
        PvDraw.textCenter(ctx, tr, roundLabel(payload), barX + width / 2, barY + 3,
                PvTheme.TEXT_DIM);

        int y = barY + PAD_Y + headerH;
        for (int team = 0; team < rows; team++) {
            List<Row> members = teams.get(team);
            int score = scoreOf(scores, team);
            int tint = score + 1 >= payload.winsNeeded() ? PvTheme.LIVE : PvTheme.TEXT;
            // Two pixels of wool per row identify the side without counting rows.
            PvDraw.rect(ctx, barX + PAD_X, y, STRIP_W, HEAD, PvTheme.team(team));
            int x = barX + PAD_X + STRIP_W + 4;
            for (int i = 0; i < members.size() && i < 4; i++) {
                Row row = members.get(i);
                PvDraw.roundRect(ctx, x - 1, y - 1, HEAD + 2, HEAD + 2, 2,
                        PvTheme.alpha(0xFF000000, 0.55F));
                PlayerFaceRenderer.draw(ctx, skinOf(client, row.uuid()), x, y, HEAD);
                x += HEAD + 2;
            }
            int textY = y + (HEAD - 8) / 2 + 1;
            PvDraw.textRight(ctx, tr, Integer.toString(score),
                    barX + width - PAD_X, textY, tint);
            y += ROW_H;
        }
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
        int leftW = blockWidth(tr, left, names, payload.showHealth());
        int rightW = blockWidth(tr, right, names, payload.showHealth());
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
        int rows = Math.max(left.size(), right.size());
        int height = Math.max(MIN_H, rows * ROW_H + PAD_Y * 2);
        return new Layout(width, height, leftW, centreW, rightW);
    }

    private static int blockWidth(Font tr, List<Row> rows, boolean names, boolean showHealth) {
        if (rows.isEmpty()) {
            return 0;
        }
        if (!names) {
            return HEAD;
        }
        // Reserved from the widest readout, 9.5, rather than the current value: a block that
        // resized when somebody drops to nine hearts would shift the bar mid-fight.
        int healthW = showHealth ? HEALTH_GAP + tr.width("9.5") : 0;
        int max = 0;
        for (Row row : rows) {
            max = Math.max(max, HEAD + NAME_GAP + tr.width(row.name()) + healthW);
        }
        return max;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the round counter in dim grey above the score in accent.
     *
     * <p>Two lines answer two questions and keep the centre block narrow enough for a 2v2 bar at 2x
     * GUI scale.
     */
    private static void drawCentre(GuiGraphics ctx, Font tr, MatchHudPayload payload,
                                   String score, int x, int barY, int width) {
        int centreX = x + width / 2;
        PvDraw.textCenter(ctx, tr, roundLabel(payload), centreX, barY + 4, PvTheme.TEXT_DIM);
        int color = PvTheme.mix(PvTheme.ACCENT, 0xFFFFFFFF, flashT * 0.85F);
        PvDraw.textCenter(ctx, tr, score, centreX, barY + 13, color);
    }

    private static void drawSide(GuiGraphics ctx, Font tr, Minecraft client,
                                 List<Row> rows, boolean left, int edgeX, int barY, int barH,
                                 boolean names, int score, int winsNeeded, boolean showHealth) {
        if (rows.isEmpty()) {
            return;
        }
        // A side at match point is tinted, so one more round reads without the numbers.
        int tint = score + 1 >= winsNeeded ? PvTheme.LIVE : PvTheme.TEXT;
        int blockH = rows.size() * ROW_H;
        int y = barY + (barH - blockH) / 2;

        for (Row row : rows) {
            int headX = left ? edgeX : edgeX - HEAD;
            PvDraw.roundRect(ctx, headX - 1, y - 1, HEAD + 2, HEAD + 2, 2,
                    PvTheme.alpha(0xFF000000, 0.55F));
            PlayerFaceRenderer.draw(ctx, skinOf(client, row.uuid()), headX, y, HEAD);

            if (names) {
                int textY = y + (HEAD - 8) / 2 + 1;
                int nameW = tr.width(row.name());
                if (left) {
                    PvDraw.text(ctx, tr, row.name(), headX + HEAD + NAME_GAP, textY, tint);
                } else {
                    PvDraw.textRight(ctx, tr, row.name(), headX - NAME_GAP, textY, tint);
                }
                // Health comes from the entity, not the payload: both sides stand in the same
                // arena, so this client knows it. The sender's own row is skipped.
                if (showHealth && !isSelf(client, row.uuid())) {
                    float health = healthOf(client, row.uuid());
                    if (health >= 0) {
                        String label = heartsLabel(health);
                        if (left) {
                            PvDraw.text(ctx, tr, label,
                                    headX + HEAD + NAME_GAP + nameW + HEALTH_GAP, textY,
                                    PvTheme.TEXT_DIM);
                        } else {
                            PvDraw.textRight(ctx, tr, label,
                                    headX - NAME_GAP - nameW - HEALTH_GAP, textY,
                                    PvTheme.TEXT_DIM);
                        }
                    }
                }
            }
            y += ROW_H;
        }
    }

    /** Health in hearts, one decimal, trimmed - {@code 20} HP is {@code 10}, not {@code 10.0}. */
    private static String heartsLabel(float health) {
        String text = String.format(Locale.ROOT, "%.1f", health / 2.0F);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** The participant's health, or -1 when their entity is not in this client's world. */
    private static float healthOf(Minecraft client, UUID uuid) {
        if (client.level != null) {
            for (AbstractClientPlayer player : client.level.players()) {
                if (player.getUUID().equals(uuid)) {
                    return player.getHealth();
                }
            }
        }
        return -1.0F;
    }

    private static boolean isSelf(Minecraft client, UUID uuid) {
        return client.player != null && client.player.getUUID().equals(uuid);
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
