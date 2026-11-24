package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.compat.PvScreens;
import com.leipishu.pvpocket.client.compat.PvSkins;
import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvMatrix;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.rule.VoteText;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.ResultOpenPayload;
import com.leipishu.pvpocket.session.RosterShape;
import com.leipishu.pvpocket.session.VoteItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The result page, drawn as a HUD instead of as a screen.
 *
 * <p>A banner across the top carries the two faces with their side names, a corner plate carries
 * the countdown, and a card down the left edge lists what was decided. No screen is opened, so the
 * countdown runs while the player walks around the lobby.
 *
 * <p>Layout comes from the screen corners, not from the player's HUD preferences: a scoreboard
 * moved to the bottom left does not take the banner with it. The names are the vanilla face drawn
 * at up to twice its size with a one-unit outline, which makes a display face out of a bitmap font
 * without a second font file, and survives a resource pack.
 */
public final class ResultHud implements PvClientPlatform.HudLayer {

    /** Banner plate height, countdown bar included. */
    private static final int BANNER_H = 46;
    /** Inner padding either side of the VS row. */
    private static final int BANNER_PAD_X = 18;
    /** Air either side of the VS mark. */
    private static final int VS_GAP = 9;
    /** The VS mark is this fraction of the names' size: present, but never the loudest thing. */
    private static final float VS_RATIO = 0.5F;
    /** Largest the names are drawn at. Beyond this the banner competes with the world. */
    private static final float MAX_NAME_SCALE = 2.0F;
    /** Smallest the names are drawn at before the faces are dropped to make room. */
    private static final float MIN_NAME_SCALE = 0.6F;
    /** Side faces, square, in pixels. Not scaled: a face at 2x is a blurred face. */
    private static final int HEAD = 18;
    /** Air between a face and the name it belongs to. */
    private static final int HEAD_GAP = 5;
    /** Countdown bar along the banner's bottom edge. */
    private static final int BAR_H = 2;
    private static final int BAR_INSET = 4;
    /**
     * Outline offset, in text units.
     *
     * <p>Scaled with the glyphs, so it lands at two to three screen pixels at the sizes used here:
     * a display face has a chunky edge, and a one-pixel edge at 3x would read as a hairline.
     */
    private static final int OUTLINE_UNITS = 1;
    private static final int OUTLINE = 0xFF0C0C10;

    /** Rules card: one column of {@code label / value} rows. */
    private static final int CARD_W = 252;
    private static final int CARD_GAP = 6;
    private static final int CARD_PAD = 8;
    private static final int CARD_HEAD = 18;
    private static final int CARD_ROW = 11;
    /** Tighter pitch, used before the card splits into two columns. */
    private static final int CARD_ROW_TIGHT = 9;
    /** Air between the banner's bottom edge and the band the card lives in. */
    private static final int BAND_GAP = 6;
    /**
     * Share of a row the label may take, in percent.
     *
     * <p>Measured from the label rather than fixed: a short label hands the room to the value, and
     * a long one stops at this share so the value keeps a column of its own instead of a sliver.
     */
    private static final int LABEL_SHARE = 45;

    /** Null means "no result page on screen". Replaced wholesale by every payload. */
    private static ResultOpenPayload state;
    private static long openedAt;
    private static float appearT;

    private ResultHud() {
    }

    public static void register() {
        PvClientPlatform.get().onHudRender(new ResultHud());
    }

    /** Called on the client thread when a payload arrives. */
    public static void update(ResultOpenPayload payload) {
        if (payload == null) {
            state = null;
            return;
        }
        state = payload;
        openedAt = System.currentTimeMillis();
        appearT = 0F;
    }

    /**
     * Drops the page.
     *
     * <p>The server closes the rules panel when the match starts, which is the same moment the
     * result page stops being true. Disconnect clears it too, so a stale banner cannot survive
     * into the next server.
     */
    public static void clear() {
        state = null;
        appearT = 0F;
    }

    // ------------------------------------------------------------------ layout

    @Override
    public void draw(GuiGraphics ctx, DeltaTracker delta) {
        ResultOpenPayload payload = state;
        if (payload == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        // F1 hides the HUD and a screen draws over it; both leave the page off screen rather than
        // half-drawn underneath something else.
        if (client.options.hideGui || PvScreens.current(client) != null) {
            return;
        }
        appearT = PvAnim.damp(appearT, 1F, PvAnim.dt(delta.getGameTimeDeltaPartialTick(false)),
                0.06F);

        Font tr = client.font;
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();

        String stage = PvLang.str("pvpocket.stage.result");
        String clock = countdown(payload);
        int clockW = HudPlate.width(tr, clock);
        // The banner is centred between the two corner plates, so it gives up the wider of them on
        // both sides rather than only on the side it happens to be nearer.
        int side = Math.max(HudPlate.width(tr, stage), clockW) + HudPlate.MARGIN + 8;
        int room = Math.max(120, screenW - side * 2);

        drawBanner(ctx, tr, payload, screenW, room);
        HudPlate.draw(ctx, tr, stage, HudPlate.MARGIN, HudPlate.TOP, PvTheme.TEXT_DIM, appearT);
        HudPlate.draw(ctx, tr, clock, screenW - HudPlate.MARGIN - clockW, HudPlate.TOP,
                PvTheme.ACCENT, appearT);
        drawCard(ctx, tr, payload, screenW, screenH);
    }

    /**
     * Draws the faces, the names and the VS mark, sized to fill the room between the corner plates.
     *
     * <p>Faces are a fixed size and only the names scale, so the row reads as two people first and
     * as text second. The scale is whatever fits, up to {@link #MAX_NAME_SCALE}; below
     * {@link #MIN_NAME_SCALE} the faces are dropped and the names take the room back, because a
     * name that cannot be read is worse than a picture that is not there.
     */
    private static void drawBanner(GuiGraphics ctx, Font tr, ResultOpenPayload payload,
                                   int screenW, int room) {
        List<Side> sides = sides(payload);
        if (sides.isEmpty()) {
            return;
        }
        String vs = PvLang.str("pvpocket.hud.result.vs");
        int vsNatural = tr.width(vs);
        int textW = Math.round((sides.size() - 1) * vsNatural * VS_RATIO);
        for (Side side : sides) {
            textW += tr.width(side.label());
        }
        int gaps = (sides.size() - 1) * VS_GAP * 2;
        int faceW = sides.size() * (HEAD + HEAD_GAP);
        boolean faces = true;
        float scale = Math.min(MAX_NAME_SCALE,
                (float) (room - gaps - faceW) / Math.max(1, textW));
        if (scale < MIN_NAME_SCALE) {
            faces = false;
            faceW = 0;
            scale = Math.min(MAX_NAME_SCALE, (float) (room - gaps) / Math.max(1, textW));
        }
        int plateW = Math.round(textW * scale) + gaps + faceW + BANNER_PAD_X * 2;
        int x = (screenW - plateW) / 2;
        int y = HudPlate.TOP;

        PvDraw.roundPanel(ctx, x, y, plateW, BANNER_H, PvTheme.RADIUS_PANEL,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.CARD, 0.80F * appearT));
        drawBar(ctx, payload, x, y, plateW);

        // The bar owns the bottom two pixels, so the row is centred in what is left.
        int inner = BANNER_H - BAR_H;
        int nameY = y + (inner - Math.round(8F * scale)) / 2;
        int faceY = y + (inner - HEAD) / 2;
        float vsScale = scale * VS_RATIO;
        int vsY = y + (inner - Math.round(8F * vsScale)) / 2;
        // Two sides put the faces on the outside, which is the versus shape; four sides have no
        // outside to speak of, so each name carries its own face instead.
        boolean outer = sides.size() == 2;
        int cx = x + BANNER_PAD_X;
        for (int i = 0; i < sides.size(); i++) {
            Side side = sides.get(i);
            if (faces && (i == 0 || !outer)) {
                drawFace(ctx, side.uuid(), cx, faceY);
                cx += HEAD + HEAD_GAP;
            }
            drawBig(ctx, tr, side.label(), cx, nameY, scale, PvTheme.TEXT);
            cx += Math.round(tr.width(side.label()) * scale);
            if (i < sides.size() - 1) {
                cx += VS_GAP;
                drawBig(ctx, tr, vs, cx, vsY, vsScale, PvTheme.ACCENT);
                cx += Math.round(vsNatural * vsScale) + VS_GAP;
            } else if (faces && outer) {
                cx += HEAD_GAP;
                drawFace(ctx, side.uuid(), cx, faceY);
            }
        }
    }

    /** One side's face, with the same dark ring the scoreboard's heads carry. */
    private static void drawFace(GuiGraphics ctx, String uuid, int x, int y) {
        PvDraw.roundRect(ctx, x - 1, y - 1, HEAD + 2, HEAD + 2, 2,
                PvTheme.alpha(0xFF000000, 0.55F * appearT));
        PvSkins.draw(ctx, uuid, x, y, HEAD);
    }

    /** The countdown as a bar draining along the banner's bottom edge. */
    private static void drawBar(GuiGraphics ctx, ResultOpenPayload payload, int x, int y,
                                int plateW) {
        long total = Math.max(1, payload.deadlineMsLeft());
        float left = Math.max(0F, Math.min(1F, (float) remainingMs(payload) / total));
        int track = plateW - BAR_INSET * 2;
        int w = Math.round(track * left);
        if (w <= 0) {
            return;
        }
        PvDraw.roundRect(ctx, x + BAR_INSET, y + BANNER_H - BAR_H - 1, w, BAR_H, 1,
                PvTheme.alpha(PvTheme.ACCENT, appearT));
    }

    /**
     * Draws one run at {@code scale} with an outline underneath.
     *
     * <p>Four offset passes plus the fill. The offsets are one text unit, so the outline is as
     * thick on screen as the glyphs are big; a resource pack that changes the face changes the
     * outline with it.
     */
    private static void drawBig(GuiGraphics ctx, Font tr, String s, int x, int y, float scale,
                                int colour) {
        int fill = PvTheme.alpha(colour, appearT);
        int edge = PvTheme.alpha(OUTLINE, appearT);
        PvMatrix.push(ctx);
        PvMatrix.translate(ctx, x, y);
        PvMatrix.scale(ctx, scale, scale);
        ctx.drawString(tr, s, -OUTLINE_UNITS, 0, edge, false);
        ctx.drawString(tr, s, OUTLINE_UNITS, 0, edge, false);
        ctx.drawString(tr, s, 0, -OUTLINE_UNITS, edge, false);
        ctx.drawString(tr, s, 0, OUTLINE_UNITS, edge, false);
        ctx.drawString(tr, s, 0, 0, fill, false);
        PvMatrix.pop(ctx);
    }

    // ------------------------------------------------------------------ rules card

    /**
     * One row of the card: a section heading when {@code value} is null, a decision otherwise.
     *
     * <p>{@code changed} is what the vote did to the item, not whether it differs from the
     * catalog: an item nobody changed reads muted, an item the vote moved reads in the accent.
     */
    private record Line(String label, String value, boolean changed) {
    }

    private static void drawCard(GuiGraphics ctx, Font tr, ResultOpenPayload payload,
                                 int screenW, int screenH) {
        List<VoteItem> items = VoteItem.decodeAll(payload.items());
        Map<String, Integer> tallies = winners(payload.tallies());
        List<Line> lines = lines(items, tallies);

        int bandTop = HudPlate.TOP + BANNER_H + BAND_GAP;
        int bandH = Math.max(1, screenH - bandTop - HudPlate.MARGIN);
        int rows = lines.size();
        int rowH = CARD_ROW;
        int perColumn = rows;
        int columns = 1;
        int height = CARD_HEAD + rows * rowH + CARD_PAD;
        if (height > bandH) {
            rowH = CARD_ROW_TIGHT;
            height = CARD_HEAD + rows * rowH + CARD_PAD;
        }
        if (height > bandH && rows > 2) {
            columns = 2;
            rowH = CARD_ROW;
            perColumn = splitRows(lines);
            height = CARD_HEAD + perColumn * rowH + CARD_PAD;
        }
        if (height > bandH && columns == 2) {
            rowH = CARD_ROW_TIGHT;
            height = CARD_HEAD + perColumn * rowH + CARD_PAD;
        }
        int cardW = columns == 1
                ? Math.min(CARD_W, screenW - HudPlate.MARGIN * 2)
                : Math.min(CARD_W * 2 + CARD_GAP, screenW - HudPlate.MARGIN * 2);
        int x = HudPlate.MARGIN;
        int y = bandTop + Math.max(0, (bandH - height) / 2);

        PvDraw.roundPanel(ctx, x, y, cardW, height, PvTheme.RADIUS_PANEL,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.CARD, 0.78F * appearT));
        drawCardHead(ctx, tr, x, y, cardW, summary(items, tallies));
        PvDraw.hLine(ctx, x + CARD_PAD, y + CARD_HEAD - 4, cardW - CARD_PAD * 2, PvTheme.DIVIDER);

        int colW = columns == 1 ? cardW : (cardW - CARD_GAP) / 2;
        int first = 0;
        for (int col = 0; col < columns; col++) {
            int end = col == columns - 1 ? rows : perColumn;
            drawColumn(ctx, tr, lines, first, end, x + col * (colW + CARD_GAP),
                    y + CARD_HEAD, colW, rowH);
            first = end;
        }
    }

    private static void drawCardHead(GuiGraphics ctx, Font tr, int x, int y, int cardW,
                                     String summary) {
        String title = PvLang.str("pvpocket.hud.result.rules");
        PvDraw.text(ctx, tr, title, x + CARD_PAD, y + 5, PvTheme.TEXT);
        int from = x + CARD_PAD + tr.width(title) + 8;
        PvDraw.textFitRight(ctx, tr, summary, x + cardW - CARD_PAD, y + 5,
                Math.max(1, x + cardW - CARD_PAD - from), PvTheme.TEXT_DIM);
    }

    private static void drawColumn(GuiGraphics ctx, Font tr, List<Line> lines, int from, int to,
                                   int x, int top, int colW, int rowH) {
        int inner = colW - CARD_PAD * 2;
        int y = top;
        for (int i = from; i < to; i++) {
            Line line = lines.get(i);
            if (line.value() == null) {
                PvDraw.textFitLeft(ctx, tr, line.label(), x + CARD_PAD, y + 1, inner,
                        PvTheme.TEXT_MUTED);
            } else {
                int labelCap = Math.max(1, inner * LABEL_SHARE / 100);
                int labelW = Math.min(tr.width(line.label()), labelCap);
                int valueW = Math.max(1, inner - labelW - 6);
                PvDraw.textFitRight(ctx, tr, line.value(), x + colW - CARD_PAD, y + 1, valueW,
                        line.changed() ? PvTheme.ACCENT : PvTheme.TEXT_DIM);
                PvDraw.textFitLeft(ctx, tr, line.label(), x + CARD_PAD, y + 1, labelW,
                        PvTheme.TEXT);
            }
            y += rowH;
        }
    }

    /**
     * Where the second column starts.
     *
     * <p>A heading owns the rows under it, so the split is pushed past one: a column that opens
     * with "Loadout" and nothing under it reads as an empty section.
     */
    private static int splitRows(List<Line> lines) {
        int half = (lines.size() + 1) / 2;
        while (half < lines.size() && lines.get(half).value() == null) {
            half++;
        }
        return Math.min(half, lines.size() - 1);
    }

    /**
     * The decided items, grouped and in ballot order.
     *
     * <p>The arrangement is left out: the banner already names it, and a card that repeats it
     * spends its tallest rows on the one thing the player is looking at anyway.
     */
    private static List<Line> lines(List<VoteItem> items, Map<String, Integer> tallies) {
        List<Line> out = new ArrayList<>();
        boolean ruleHeading = false;
        boolean loadoutHeading = false;
        for (VoteItem item : items) {
            if (item.kind == VoteItem.KIND_ROSTER) {
                continue;
            }
            boolean rule = item.kind == VoteItem.KIND_RULE;
            if (rule && !ruleHeading) {
                out.add(new Line(PvLang.str("pvpocket.gui.result.section.rules"), null, false));
                ruleHeading = true;
            }
            if (!rule && !loadoutHeading) {
                out.add(new Line(PvLang.str("pvpocket.gui.result.section.loadout"), null, false));
                loadoutHeading = true;
            }
            int winner = tallies.getOrDefault(item.id, 0);
            out.add(new Line(VoteText.itemTitle(item), VoteText.decidedValue(item, winner),
                    winner > 0));
        }
        if (out.isEmpty()) {
            out.add(new Line(PvLang.str("pvpocket.gui.result.no_changes"), null, false));
        }
        return out;
    }

    // ------------------------------------------------------------------ text

    /** One entry per side, its members joined by the language's list separator. */
    /** One side of the banner: the name as drawn, and a face to put beside it. */
    private record Side(String label, String uuid) {
    }

    private static List<Side> sides(ResultOpenPayload payload) {
        List<Side> out = new ArrayList<>();
        List<List<String>> roster = RosterShape.decode(payload.roster());
        if (roster == null) {
            return out;
        }
        Map<String, String> names = VoteText.parseNames(payload.names());
        String join = PvLang.str("pvpocket.hud.result.join");
        for (List<String> side : roster) {
            List<String> members = new ArrayList<>();
            String face = "";
            for (String uuid : side) {
                String name = names.get(uuid);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (face.isEmpty()) {
                    face = uuid;
                }
                members.add(name);
            }
            if (!members.isEmpty()) {
                out.add(new Side(String.join(join, members), face));
            }
        }
        return out;
    }

    private static String summary(List<VoteItem> items, Map<String, Integer> tallies) {
        int changed = 0;
        int total = 0;
        for (VoteItem item : items) {
            if (item.kind == VoteItem.KIND_ROSTER) {
                continue;
            }
            total++;
            if (tallies.getOrDefault(item.id, 0) > 0) {
                changed++;
            }
        }
        if (total == 0) {
            return PvLang.str("pvpocket.gui.result.no_changes");
        }
        return PvLang.str("pvpocket.gui.result.footer.summary", changed, total - changed);
    }

    private static String countdown(ResultOpenPayload payload) {
        int seconds = (int) Math.ceil(Math.max(0L, remainingMs(payload)) / 1000D);
        return PvLang.str("pvpocket.gui.result.footer.countdown", seconds);
    }

    private static long remainingMs(ResultOpenPayload payload) {
        return payload.deadlineMsLeft() - (System.currentTimeMillis() - openedAt);
    }

    /**
     * Per-item winners from {@code id \t winner \t counts}, one line per item.
     *
     * <p>A malformed line is dropped rather than thrown: the HUD is decoration and must not take
     * the client down over a count it cannot parse.
     */
    private static Map<String, Integer> winners(String encoded) {
        Map<String, Integer> out = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String line : encoded.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            try {
                out.put(parts[0], Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                // Keep the item, drop the tally: the row still draws as unchanged.
            }
        }
        return out;
    }
}
