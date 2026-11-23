package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.surrender.SurrenderInput;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.SurrenderStatePayload;
import com.leipishu.pvpocket.client.compat.PvScreens;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The prompt that asks a team-mate to agree to a surrender.
 *
 * <p>Bottom-right, because the left of the screen is where the match cards may be moved to and the
 * centre belongs to the crosshair. Two lines: who asked and how far the side has got, then the two
 * keys and the window left.
 *
 * <p>The keys come from the bindings rather than from literals, so a rebind reads correctly here.
 * The countdown is the server's number, re-sent once a second; the client never counts on its own.
 */
public final class SurrenderHud implements PvClientPlatform.HudLayer {

    private static final int PAD_X = 8;
    private static final int PAD_Y = 5;
    private static final int LINE_H = 9;
    private static final int GAP = 10;
    private static final int MARGIN = 4;
    /** Width of the card when the two lines are narrower than this, so it does not look cramped. */
    private static final int MIN_W = 116;

    /** Null means "no request on this player". Replaced wholesale by every payload. */
    private static SurrenderStatePayload state;
    private static float appearT;

    private SurrenderHud() {
    }

    public static void register() {
        PvClientPlatform.get().onHudRender(new SurrenderHud());
    }

    /** Called on the client thread when a payload arrives. */
    public static void update(SurrenderStatePayload payload) {
        SurrenderStatePayload next = payload != null && payload.active() ? payload : null;
        if (next != null && state == null) {
            appearT = 0F;
        }
        state = next;
    }

    /** Dropped on disconnect, so a stale prompt cannot survive into the next server. */
    public static void clear() {
        state = null;
        appearT = 0F;
    }

    /** True while an answer is owed, which is also when the two keys do anything. */
    public static boolean active() {
        return state != null;
    }

    @Override
    public void draw(GuiGraphics ctx, DeltaTracker tickCounter) {
        SurrenderStatePayload payload = state;
        if (payload == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui || PvScreens.current(client) != null) {
            return;
        }
        float d = PvAnim.dt(tickCounter.getGameTimeDeltaPartialTick(false));
        appearT = PvAnim.damp(appearT, 1F, d, 0.08F);
        Font tr = client.font;

        String title = PvLang.str("pvpocket.hud.surrender.title", payload.requester());
        String count = PvLang.str("pvpocket.hud.surrender.count", payload.agreed(), payload.total());
        String agree = PvLang.str("pvpocket.hud.surrender.agree", SurrenderInput.approveLabel());
        String decline = PvLang.str("pvpocket.hud.surrender.decline", SurrenderInput.declineLabel());
        String left = PvLang.str("pvpocket.hud.surrender.left", payload.secondsLeft());

        int titleW = tr.width(title) + 6 + tr.width(count);
        int keysW = tr.width(agree) + GAP + tr.width(decline);
        int width = Math.max(MIN_W, PAD_X * 2 + Math.max(titleW, keysW + GAP + tr.width(left)));
        int height = PAD_Y * 2 + LINE_H * 2;
        int x = ctx.guiWidth() - MARGIN - width;
        int y = ctx.guiHeight() - MARGIN - height;

        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.CARD, 0.72F * appearT));

        PvDraw.text(ctx, tr, title, x + PAD_X, y + PAD_Y,
                PvTheme.alpha(PvTheme.TEXT, appearT));
        PvDraw.text(ctx, tr, count, x + PAD_X + tr.width(title) + 6, y + PAD_Y,
                PvTheme.alpha(PvTheme.TEXT_DIM, appearT));

        int keysY = y + PAD_Y + LINE_H;
        PvDraw.text(ctx, tr, agree, x + PAD_X, keysY, PvTheme.alpha(PvTheme.ACCENT, appearT));
        PvDraw.text(ctx, tr, decline, x + PAD_X + tr.width(agree) + GAP, keysY,
                PvTheme.alpha(PvTheme.TEXT_DIM, appearT));
        PvDraw.textRight(ctx, tr, left, x + width - PAD_X, keysY,
                PvTheme.alpha(PvTheme.TEXT_DIM, appearT));
    }
}
