package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.SpectateStatePayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * The bar that says you are watching somebody else's fight.
 *
 * <p>Two facts have to be on screen at once and neither is obvious from inside spectator mode: that
 * the camera belongs to another player, and whose it is. A spectator who attaches to a body and
 * forgets is a spectator who reports "my health is wrong" — the health bar they are looking at is
 * the target's.</p>
 *
 * <p>Bottom-centre rather than top: the scoreboard already owns the top of the screen, and the two
 * would overlap exactly when a match is most interesting. The hotbar is below and the chat is to the
 * left, so the strip just above the hotbar is the one region nothing else claims.</p>
 *
 * <p>The position counter ("1 / 2") is what makes the cycle key usable. Pressing V with no feedback
 * is indistinguishable from pressing V on a key that does nothing.</p>
 */
public final class SpectatorHud implements PvClientPlatform.HudLayer {

    private static final int PAD_X = 11;
    private static final int PAD_Y = 6;
    private static final int BOTTOM_GAP = 54;
    private static final float PULSE_PERIOD = 2.0F;

    /** Null means "not spectating". Replaced wholesale by every payload. */
    private static SpectateStatePayload state;
    private static float appearT;
    private static float phase;

    private SpectatorHud() {
    }

    public static void register() {
        PvClientPlatform.get().onHudRender(new SpectatorHud());
    }

    /** Called on the client thread when a payload arrives. */
    public static void update(SpectateStatePayload payload) {
        state = payload != null && payload.active() ? payload : null;
        if (state != null) {
            appearT = 0F;
        }
    }

    public static void clear() {
        state = null;
        appearT = 0F;
    }

    /** True while the bar is up, which is also "this player is spectating". */
    public static boolean active() {
        return state != null;
    }

    @Override
    public void render(GuiGraphics ctx, DeltaTracker tickCounter) {
        SpectateStatePayload payload = state;
        if (payload == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) {
            // F1 hides the HUD, and a spectator bar that ignores that is a bar the player cannot
            // get rid of while trying to watch a fight cleanly.
            return;
        }
        float d = PvAnim.dt(tickCounter.getGameTimeDeltaPartialTick(false));
        appearT = PvAnim.damp(appearT, 1F, d, 0.07F);
        phase += d;
        Font tr = client.font;

        List<String> names = new ArrayList<>();
        for (String pair : payload.targets().split(",")) {
            int colon = pair.indexOf(':');
            if (colon > 0) {
                names.add(pair.substring(0, colon));
            }
        }
        // A negative index is the detached state: the camera is back on the spectator's own body, so
        // there is no "who" to name. Saying so is what makes the two modes tellable apart, because
        // in both of them the same match is being watched.
        boolean attached = payload.index() >= 0 && !names.isEmpty();
        String who = attached
                ? names.get(Math.floorMod(payload.index(), names.size()))
                : PvLang.str("pvpocket.gui.hud.free_camera");
        String counter = attached
                ? " " + (Math.floorMod(payload.index(), names.size()) + 1) + "/" + names.size()
                : "";

        // The map name and the round arrive as an id and a number, not as a sentence: the server
        // that sends them has no language, and this bar is drawn on a client that does.
        String where = payload.mapId().isEmpty()
                ? PvLang.str("pvpocket.gui.hud.unknown_map")
                : PvLang.str("pvpocket.map." + payload.mapId());
        String left = PvLang.str("pvpocket.gui.hud.watching",
                PvLang.str("pvpocket.gui.hud.where", where, Math.max(1, payload.round())));
        String right = attached
                ? who + counter + PvLang.str("pvpocket.gui.hud.keys_attached")
                : who + PvLang.str("pvpocket.gui.hud.keys_detached");

        int leftW = tr.width(left);
        int rightW = tr.width(right);
        int width = PAD_X * 2 + leftW + 14 + rightW;
        int height = 18;
        int x = (ctx.guiWidth() - width) / 2;
        int y = ctx.guiHeight() - BOTTOM_GAP;

        PvDraw.shadow(ctx, x, y, width, height, PvTheme.RADIUS);
        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.PANEL, 0.94F * appearT));

        // A live dot rather than a word: this is a state, not a label. It breathes on a 2s period so
        // it reads as "running" instead of as a static bullet, at an amplitude small enough that it
        // never pulls the eye off the fight.
        float pulse = 0.55F + 0.45F * (0.5F + 0.5F * (float) Math.sin(phase / PULSE_PERIOD * Math.PI * 2D));
        PvDraw.dot(ctx, x + PAD_X + 3, y + height / 2, 3,
                PvTheme.alpha(PvTheme.ACCENT, appearT * pulse));

        PvDraw.text(ctx, tr, left, x + PAD_X + 12, y + PAD_Y, PvTheme.alpha(PvTheme.TEXT, appearT));
        PvDraw.text(ctx, tr, right, x + PAD_X + 12 + leftW + 14, y + PAD_Y,
                PvTheme.alpha(PvTheme.TEXT_DIM, appearT));
    }
}
