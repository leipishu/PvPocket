package com.leipishu.pvpocket.client.gui.prepare;

import com.leipishu.pvpocket.client.compat.PvScreens;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.11 {
import net.minecraft.client.input.KeyEvent;
//?}

/**
 * The screen shown after the accept click while the server readies the arena.
 *
 * <p>It is a passive notice, not a dialog: there is nothing to press and Esc does not dismiss it,
 * because closing it would only hide the feedback that the click succeeded. The server dismisses it
 * with {@link #dismiss(String)} once the round starts, and a local watchdog closes it if that
 * packet never arrives. {@link #sessionId} gates late packets so a previous match cannot close a
 * newer screen. {@link PrepareNotice} decides when it opens: a start that finishes quickly shows
 * nothing at all.
 */
public final class PrepareScreen extends PvScreen {

    /** Milliseconds before the watchdog gives up and returns the player to the world. */
    private static final long WATCHDOG_MS = 22_000L;

    private final String session;
    private final long openedAt;

    public PrepareScreen(String sessionId) {
        super(PvLang.t("pvpocket.gui.prepare.title"));
        this.session = sessionId == null ? "" : sessionId;
        this.openedAt = System.currentTimeMillis();
    }

    /** A dependency-free instance for the layout audit. */
    public static PrepareScreen sample() {
        return new PrepareScreen("audit");
    }

    public String sessionId() {
        return session;
    }

    /** Dismisses the screen only when it belongs to the given session. */
    public static void dismiss(String sessionId) {
        Minecraft client = Minecraft.getInstance();
        if (PvScreens.current(client) instanceof PrepareScreen screen
                && screen.session.equals(sessionId)) {
            PvScreens.open(client, null);
        }
    }

    @Override
    protected void init() {
        roots.clear();
        layoutPanel(220, 92);
        chromeW = 0;
        chromeH = 0;
        setFocus(null);
    }

    /**
     * Esc is ignored while the server is preparing the round.
     *
     * <p>Allowing it would drop the only confirmation that the click registered, leaving the player
     * to click accept again.
     */
    //? if >=1.21.11 {
    @Override
    public boolean keyPressed(KeyEvent event) {
        return true;
    }
    //?} else {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return true;
    }
    //?}

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        long age = System.currentTimeMillis() - openedAt;
        if (age >= WATCHDOG_MS) {
            PvScreens.open(Minecraft.getInstance(), null);
            return;
        }
        Font tr = font();
        PvDraw.textCenter(ctx, tr, PvLang.str("pvpocket.gui.prepare.title"),
                panelX + panelW / 2, panelY + panelH / 2 - 14, PvTheme.TEXT);
        int dots = (int) (age / 500L) % 4;
        String hint = PvLang.str("pvpocket.gui.prepare.hint") + ".".repeat(dots);
        PvDraw.textCenter(ctx, tr, hint, panelX + panelW / 2, panelY + panelH / 2 + 6,
                PvTheme.TEXT_DIM);
    }

    @Override
    public int auditVariantCount() {
        return 1;
    }

    @Override
    public void auditVariant(int index) {
        // The single variant is the idle notice; nothing to vary.
    }
}
