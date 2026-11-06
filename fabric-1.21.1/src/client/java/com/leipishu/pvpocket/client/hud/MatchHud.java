package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.MatchHudPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The scoreboard drawn across the top of the screen during a match.
 *
 * <p>Two layouts, chosen by how many sides the payload carries:</p>
 *
 * <ul>
 *   <li><b>Two sides</b> — one bar, three blocks: left side's heads, round and score, right side's
 *       heads. This is every ordinary duel and it is unchanged.</li>
 *   <li><b>Three or four sides</b> — a stacked list: one row per side with its heads on the left and
 *       its round wins on the right, under the round counter. A left/right split has no third place
 *       to put a third team, and squeezing four columns into the middle of the screen at 2× GUI
 *       scale leaves no room for names.</li>
 * </ul>
 *
 * <p>The bar is centred rather than pinned to the screen corners. Corners are not free real estate in
 * Minecraft: the top-right is where vanilla draws status-effect icons, and a scoreboard that sits
 * under them is a scoreboard that is unreadable exactly when it matters — while a player is poisoned,
 * slowed or on fire. Centring the whole assembly costs nothing and removes the collision entirely.</p>
 *
 * <p>It is a HUD overlay rather than a boss bar or a sidebar because both of those are vanilla
 * furniture the player can already be using for something else. A mod that hijacks the boss bar for
 * its own scoreboard breaks every boss fight on the server.</p>
 */
@Environment(EnvType.CLIENT)
public final class MatchHud implements HudRenderCallback {

    private static final int ROW_H = 20;
    private static final int HEAD = 16;
    private static final int PAD_X = 10;
    private static final int PAD_Y = 5;
    private static final int NAME_GAP = 5;
    private static final int BLOCK_GAP = 12;
    private static final int MIN_H = 30;
    private static final int TOP = 4;
    private static final float FLASH_TIME = 0.32F;

    /** Null means "no match on screen". Replaced wholesale by every payload. */
    private static MatchHudPayload state;
    private static float appearT;
    private static float flashT;
    private static String lastScore = "";

    private MatchHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(new MatchHud());
    }

    /** Called on the client thread when a payload arrives. */
    public static void update(MatchHudPayload payload) {
        state = payload != null && payload.active() ? payload : null;
        if (state != null) {
            appearT = 0F;
        }
    }

    /** Dropped on disconnect so a stale scoreboard cannot survive into the next server. */
    public static void clear() {
        state = null;
        appearT = 0F;
        flashT = 0F;
        lastScore = "";
    }

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        MatchHudPayload payload = state;
        if (payload == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        // F1 hides the HUD; a scoreboard that ignores that is a scoreboard the player cannot get rid
        // of, and the first thing anyone does when they want a clean screenshot is press F1.
        if (client.options.hudHidden || client.currentScreen != null) {
            return;
        }

        List<List<Row>> teams = parseTeams(payload.teams());
        int[] scores = parseScores(payload.scores());
        if (teams.isEmpty()) {
            return;
        }

        float d = PvAnim.dt(tickCounter.getTickDelta(false));
        appearT = PvAnim.damp(appearT, 1F, d, 0.06F);

        // A score change is the one event on this bar worth a flash: the number is 9px tall and sits
        // between two heads, so without it a round win is easy to miss while fighting.
        String scoreKey = payload.scores();
        if (!scoreKey.equals(lastScore)) {
            if (!lastScore.isEmpty()) {
                flashT = 1F;
            }
            lastScore = scoreKey;
        }
        flashT = Math.max(0F, flashT - d / FLASH_TIME);

        TextRenderer tr = client.textRenderer;
        int screenW = client.getWindow().getScaledWidth();

        if (teams.size() == 2) {
            drawHeadToHead(ctx, client, tr, payload, teams, scores, screenW);
        } else {
            drawStacked(ctx, client, tr, payload, teams, scores, screenW);
        }
    }

    // ------------------------------------------------------------------ two sides

    private void drawHeadToHead(DrawContext ctx, MinecraftClient client, TextRenderer tr,
                                MatchHudPayload payload, List<List<Row>> teams, int[] scores,
                                int screenW) {
        List<Row> left = teams.get(0);
        List<Row> right = teams.get(1);
        if (left.isEmpty() && right.isEmpty()) {
            return;
        }
        int leftScore = scoreOf(scores, 0);
        int rightScore = scoreOf(scores, 1);

        // Two passes over the same measurement: with names first, and — only if that does not fit —
        // heads alone. Shrinking the type instead would make the score unreadable on a 2× GUI scale,
        // and dropping a whole side would be worse than dropping the labels.
        boolean names = true;
        Layout layout = measure(tr, left, right, payload, leftScore, rightScore, names);
        if (layout.width() > screenW - 8) {
            names = false;
            layout = measure(tr, left, right, payload, leftScore, rightScore, names);
        }

        int barX = (screenW - layout.width()) / 2;
        int barY = TOP;
        PvDraw.shadow(ctx, barX, barY, layout.width(), layout.height(), PvTheme.RADIUS);
        PvDraw.roundPanel(ctx, barX, barY, layout.width(), layout.height(), PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.PANEL, 0.94F * appearT));

        drawSide(ctx, tr, client, left, true, barX + PAD_X, barY, layout.height(), names,
                leftScore, payload.winsNeeded());
        drawSide(ctx, tr, client, right, false, barX + layout.width() - PAD_X, barY, layout.height(),
                names, rightScore, payload.winsNeeded());
        String score = leftScore + " : " + rightScore;
        drawCentre(ctx, tr, payload, score, barX + PAD_X + layout.leftW() + BLOCK_GAP,
                barY, layout.centreW());

        // Divider ticks between the side blocks and the centre, so the three parts read as three
        // parts even when a name happens to be the same colour as the score.
        int divTop = barY + 6;
        int divH = layout.height() - 12;
        PvDraw.vLine(ctx, barX + PAD_X + layout.leftW() + BLOCK_GAP / 2, divTop, divH, PvTheme.DIVIDER);
        PvDraw.vLine(ctx, barX + PAD_X + layout.leftW() + BLOCK_GAP + layout.centreW()
                + BLOCK_GAP / 2, divTop, divH, PvTheme.DIVIDER);
    }

    // ------------------------------------------------------------------ three or four sides

    /**
     * One row per side: heads, then the round wins right-aligned.
     *
     * <p>Heads are capped at four per row. A 4×4 match is sixteen players and sixteen heads is a bar
     * as wide as the screen; four plus the count is enough to see who is on which side, and the
     * exact roster is a tab-list question, not a scoreboard one.</p>
     */
    private void drawStacked(DrawContext ctx, MinecraftClient client, TextRenderer tr,
                             MatchHudPayload payload, List<List<Row>> teams, int[] scores,
                             int screenW) {
        int rows = teams.size();
        int headerH = 13;
        int headsW = 4 * (HEAD + 2);
        int scoreW = tr.getWidth("00") + 6;
        int width = PAD_X * 2 + headsW + BLOCK_GAP + scoreW;
        int height = PAD_Y * 2 + headerH + rows * ROW_H;
        int barX = (screenW - width) / 2;
        int barY = TOP;

        PvDraw.shadow(ctx, barX, barY, width, height, PvTheme.RADIUS);
        PvDraw.roundPanel(ctx, barX, barY, width, height, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.PANEL, 0.94F * appearT));
        PvDraw.textCenter(ctx, tr, roundLabel(payload), barX + width / 2, barY + 4,
                PvTheme.TEXT_DIM);

        int y = barY + PAD_Y + headerH;
        for (int team = 0; team < rows; team++) {
            List<Row> members = teams.get(team);
            int score = scoreOf(scores, team);
            int tint = score + 1 >= payload.winsNeeded() ? PvTheme.LIVE : PvTheme.TEXT;
            int x = barX + PAD_X;
            for (int i = 0; i < members.size() && i < 4; i++) {
                Row row = members.get(i);
                PvDraw.roundRect(ctx, x - 1, y - 1, HEAD + 2, HEAD + 2, 2,
                        PvTheme.alpha(0xFF000000, 0.55F));
                PlayerSkinDrawer.draw(ctx, skinOf(client, row.uuid()), x, y, HEAD);
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

    private static Layout measure(TextRenderer tr, List<Row> left, List<Row> right,
                                  MatchHudPayload payload, int leftScore, int rightScore,
                                  boolean names) {
        int leftW = blockWidth(tr, left, names);
        int rightW = blockWidth(tr, right, names);
        String round = roundLabel(payload);
        String score = leftScore + " : " + rightScore;
        int centreW = Math.max(tr.getWidth(round), tr.getWidth(score));

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

    private static int blockWidth(TextRenderer tr, List<Row> rows, boolean names) {
        if (rows.isEmpty()) {
            return 0;
        }
        if (!names) {
            return HEAD;
        }
        int max = 0;
        for (Row row : rows) {
            max = Math.max(max, HEAD + NAME_GAP + tr.getWidth(row.name()));
        }
        return max;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Two stacked lines: the round counter in dim grey above, the score in accent below.
     *
     * <p>Two lines rather than one because they answer different questions — "how far into the series
     * are we" is read once at the round boundary, "who is ahead" is read constantly — and stacking
     * them keeps the centre block narrow enough that a 2v2 bar still fits at 2× GUI scale.</p>
     */
    private static void drawCentre(DrawContext ctx, TextRenderer tr, MatchHudPayload payload,
                                   String score, int x, int barY, int width) {
        int centreX = x + width / 2;
        PvDraw.textCenter(ctx, tr, roundLabel(payload), centreX, barY + 5, PvTheme.TEXT_DIM);
        int color = PvTheme.mix(PvTheme.ACCENT, 0xFFFFFFFF, flashT * 0.85F);
        PvDraw.textCenter(ctx, tr, score, centreX, barY + 16, color);
    }

    private static void drawSide(DrawContext ctx, TextRenderer tr, MinecraftClient client,
                                 List<Row> rows, boolean left, int edgeX, int barY, int barH,
                                 boolean names, int score, int winsNeeded) {
        if (rows.isEmpty()) {
            return;
        }
        // A side at match point is tinted, so "one more round and this is over" is readable at a
        // glance without reading the numbers.
        int tint = score + 1 >= winsNeeded ? PvTheme.LIVE : PvTheme.TEXT;
        int blockH = rows.size() * ROW_H;
        int y = barY + (barH - blockH) / 2;

        for (Row row : rows) {
            int headX = left ? edgeX : edgeX - HEAD;
            PvDraw.roundRect(ctx, headX - 1, y - 1, HEAD + 2, HEAD + 2, 2,
                    PvTheme.alpha(0xFF000000, 0.55F));
            PlayerSkinDrawer.draw(ctx, skinOf(client, row.uuid()), headX, y, HEAD);

            if (names) {
                int textY = y + (HEAD - 8) / 2 + 1;
                if (left) {
                    PvDraw.text(ctx, tr, row.name(), headX + HEAD + NAME_GAP, textY, tint);
                } else {
                    PvDraw.textRight(ctx, tr, row.name(), headX - NAME_GAP, textY, tint);
                }
            }
            y += ROW_H;
        }
    }

    private static String roundLabel(MatchHudPayload payload) {
        if (payload.bestOf() <= 1) {
            return PvLang.str("pvpocket.gui.hud.single_round");
        }
        return PvLang.str("pvpocket.gui.hud.round", payload.round(), payload.bestOf());
    }

    /**
     * The player's skin, from the live entity when it is loaded and from the default table otherwise.
     *
     * <p>The fallback matters: the other side may be in the arena while this client is still standing
     * in the overworld (spectating, or between rounds), and their entity is then not in this client's
     * world at all. Drawing a Steve head is a worse answer than the real skin but a much better one
     * than a missing row.</p>
     */
    private static SkinTextures skinOf(MinecraftClient client, UUID uuid) {
        if (client.world != null) {
            for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
                if (player.getUuid().equals(uuid)) {
                    return player.getSkinTextures();
                }
            }
        }
        return DefaultSkinHelper.getSkinTextures(uuid);
    }

    // ------------------------------------------------------------------ parsing

    private record Row(String name, UUID uuid) {
    }

    /**
     * Parses {@code "Dev1|uuid,Dev2|uuid;Dev3|uuid"} into one list per side.
     *
     * <p>A malformed row or side is dropped, not thrown: the HUD is decoration, and a crash here
     * would take the client down mid-duel for a reason the player could neither see nor act on.</p>
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
