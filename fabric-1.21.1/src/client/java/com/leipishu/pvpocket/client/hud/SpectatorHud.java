package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.network.SpectateStatePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

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
@Environment(EnvType.CLIENT)
public final class SpectatorHud implements HudRenderCallback {

    private static final int PAD_X = 10;
    private static final int PAD_Y = 5;
    private static final int BOTTOM_GAP = 54;

    /** Null means "not spectating". Replaced wholesale by every payload. */
    private static SpectateStatePayload state;

    private SpectatorHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(new SpectatorHud());
    }

    /** Called on the client thread when a payload arrives. */
    public static void update(SpectateStatePayload payload) {
        state = payload != null && payload.active() ? payload : null;
    }

    public static void clear() {
        state = null;
    }

    /** True while the bar is up, which is also "this player is spectating". */
    public static boolean active() {
        return state != null;
    }

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        SpectateStatePayload payload = state;
        if (payload == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            // F1 hides the HUD, and a spectator bar that ignores that is a bar the player cannot
            // get rid of while trying to watch a fight cleanly.
            return;
        }
        TextRenderer tr = client.textRenderer;

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
                : "自由视角";
        String counter = attached
                ? " (" + (Math.floorMod(payload.index(), names.size()) + 1) + "/" + names.size() + ")"
                : "";

        String left = "观战 · " + payload.label();
        String right = attached
                ? "视角 " + who + counter + " · V 切换 · G 解除"
                : "视角 " + who + " · V 附身";

        int leftW = tr.getWidth(left);
        int rightW = tr.getWidth(right);
        int width = PAD_X * 2 + leftW + 14 + rightW;
        int height = 16;
        int x = (ctx.getScaledWindowWidth() - width) / 2;
        int y = ctx.getScaledWindowHeight() - BOTTOM_GAP;

        PvDraw.shadow(ctx, x, y, width, height, PvTheme.RADIUS, 3);
        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, PvTheme.ACCENT_DIM, 0xE0141721);
        // A live dot: the same accent the primary action uses, because this is a state, not a label.
        PvDraw.roundRect(ctx, x + 6, y + height / 2 - 2, 4, 4, 2, PvTheme.ACCENT);
        PvDraw.text(ctx, tr, left, x + PAD_X + 4, y + PAD_Y, PvTheme.TEXT);
        PvDraw.text(ctx, tr, right, x + PAD_X + 4 + leftW + 14, y + PAD_Y, PvTheme.TEXT_DIM);
    }
}
