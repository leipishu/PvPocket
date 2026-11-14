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
 * The bar that says the camera is watching somebody else's fight, and whose it is.
 *
 * <p>Two facts have to be on screen at once: whose camera this is, and that it belongs to another
 * player. Otherwise a spectator reads the target's health bar as their own.
 *
 * <p>The bar sits bottom-centre, above the hotbar, where nothing else is drawn. The counter
 * ("1 / 2") is what makes the cycle key usable.
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
    public void draw(GuiGraphics ctx, DeltaTracker tickCounter) {
        SpectateStatePayload payload = state;
        if (payload == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) {
            // F1 hides the HUD, and the bar respects it so a fight can be watched cleanly.
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
        // A negative index is the detached state: the camera is back on the spectator's own body.
        // That is what tells the two modes apart.
        boolean attached = payload.index() >= 0 && !names.isEmpty();
        String who = attached
                ? names.get(Math.floorMod(payload.index(), names.size()))
                : PvLang.str("pvpocket.gui.hud.free_camera");
        String counter = attached
                ? " " + (Math.floorMod(payload.index(), names.size()) + 1) + "/" + names.size()
                : "";

        // The map name and the round arrive as an id and a number. The server has no language, and
        // this bar is drawn on a client that does.
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

        // A live dot rather than a word: this is a state, not a label. It breathes on a 2s period,
        // so it reads as running.
        float pulse = 0.55F + 0.45F * (0.5F + 0.5F * (float) Math.sin(phase / PULSE_PERIOD * Math.PI * 2D));
        PvDraw.dot(ctx, x + PAD_X + 3, y + height / 2, 3,
                PvTheme.alpha(PvTheme.ACCENT, appearT * pulse));

        PvDraw.text(ctx, tr, left, x + PAD_X + 12, y + PAD_Y, PvTheme.alpha(PvTheme.TEXT, appearT));
        PvDraw.text(ctx, tr, right, x + PAD_X + 12 + leftW + 14, y + PAD_Y,
                PvTheme.alpha(PvTheme.TEXT_DIM, appearT));
    }
}
