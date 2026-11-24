package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.compat.PvSkins;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Map;

/**
 * An arrangement drawn as the invite's cards, at a smaller pitch.
 *
 * <p>One renderer for the vote row and the result page, so the arrangement a player votes on looks
 * like the one they were shown when invited. The roster panel keeps its own card code because it
 * also draws the edit hotspots; the colours, the team bar and the header are shared through
 * {@link PvTheme} rather than through a common method.
 */
public final class RosterCards {

    private static final int GAP = 6;
    private static final int HEAD_H = 13;
    private static final int ROW_H = 10;
    private static final int MIN_W = 96;
    private static final int FACE = 8;

    private RosterCards() {
    }

    /** How many cards fit on one line at this width, capped at four sides. */
    public static int columns(int width, int sides) {
        int fit = Math.max(1, (width + GAP) / (MIN_W + GAP));
        return Math.max(1, Math.min(Math.min(4, sides), fit));
    }

    /** Height the whole grid needs at this width. */
    public static int height(List<List<String>> sides, int width) {
        int cols = columns(width, sides.size());
        int rows = (sides.size() + cols - 1) / cols;
        int tallest = 1;
        for (List<String> side : sides) {
            tallest = Math.max(tallest, side.size());
        }
        int cardH = HEAD_H + tallest * ROW_H + 4;
        return rows * cardH + Math.max(0, rows - 1) * GAP;
    }

    public static void draw(GuiGraphics ctx, Font tr, List<List<String>> sides,
                            Map<String, String> names, int slots, int x, int y, int width) {
        if (sides.isEmpty()) {
            return;
        }
        int cols = columns(width, sides.size());
        int cardW = (width - GAP * (cols - 1)) / cols;
        int tallest = 1;
        for (List<String> side : sides) {
            tallest = Math.max(tallest, side.size());
        }
        int cardH = HEAD_H + tallest * ROW_H + 4;
        for (int i = 0; i < sides.size(); i++) {
            int cx = x + (i % cols) * (cardW + GAP);
            int cy = y + (i / cols) * (cardH + GAP);
            drawCard(ctx, tr, sides.get(i), names, slots, i, cx, cy, cardW, cardH);
        }
    }

    private static void drawCard(GuiGraphics ctx, Font tr, List<String> side,
                                 Map<String, String> names, int slots, int team,
                                 int cx, int cy, int cw, int ch) {
        PvDraw.roundPanel(ctx, cx, cy, cw, ch, PvTheme.RADIUS, PvTheme.BORDER_STRONG,
                PvTheme.teamWash(team, PvTheme.CARD, 0.10F));
        PvDraw.roundRect(ctx, cx + 1, cy + 1, cw - 2, 2, 1, PvTheme.team(team));
        String head = slots > 0
                ? PvLang.str("pvpocket.gui.roster.team_slots", team + 1, side.size(), slots)
                : PvLang.str("pvpocket.gui.roster.team_people", team + 1, side.size());
        PvDraw.textFitLeft(ctx, tr, head, cx + 6, cy + 4, cw - 12, PvTheme.teamInk(team));

        for (int row = 0; row < side.size(); row++) {
            int ry = cy + HEAD_H + row * ROW_H;
            String uuid = side.get(row);
            PvSkins.draw(ctx, uuid, cx + 6, ry, FACE);
            PvDraw.textFitLeft(ctx, tr, nameOf(uuid, names), cx + 6 + FACE + 4, ry,
                    cw - FACE - 18, PvTheme.TEXT);
        }
    }

    /** Falls back to a short uuid, which is what a client that missed the name map has. */
    private static String nameOf(String uuid, Map<String, String> names) {
        String name = names.get(uuid);
        if (name != null) {
            return name;
        }
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }
}
