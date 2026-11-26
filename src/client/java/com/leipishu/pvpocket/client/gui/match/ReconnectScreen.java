package com.leipishu.pvpocket.client.gui.match;

import com.leipishu.pvpocket.client.compat.PvScreens;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvConfirm;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.ReconnectAnswerPayload;
import com.leipishu.pvpocket.network.ReconnectOfferPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The modal a returning participant answers: take the place back, or give it up.
 *
 * <p>A screen rather than a chat line. By the time the question arrives the player is already
 * standing in the arena as a spectator, and a line in chat reads as "put back in without being
 * asked"; the screen is the question.
 *
 * <p>Esc closes it without answering, which leaves the player on the bench. That is the safe
 * outcome: reconnecting is a decision, and giving up is the one action here that cannot be undone.
 */
public class ReconnectScreen extends PvScreen {

    private static final int MAX_W = 320;
    private static final int HEADER_H = 30;
    private static final int BODY_H = 50;
    private static final int FOOTER_H = 36;

    private final String sessionId;
    private final int round;
    private final int maxRounds;
    private final String score;

    public ReconnectScreen(ReconnectOfferPayload payload) {
        super(PvLang.t("pvpocket.gui.reconnect.title"));
        this.sessionId = payload.sessionId();
        this.round = payload.round();
        this.maxRounds = payload.maxRounds();
        this.score = payload.score();
    }

    /** A synthetic offer for the UI audit; see {@code PvAuditHarness}. */
    public static ReconnectScreen sample() {
        return new ReconnectScreen(new ReconnectOfferPayload("audit", 2, 3, "1:0"));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        layoutPanel(MAX_W, HEADER_H + BODY_H + FOOTER_H);
        chromeH = HEADER_H;
        chromeW = 0;
        int y = panelY + panelH - FOOTER_H + 8;
        PvButton accept = new PvButton(PvLang.str("pvpocket.gui.reconnect.accept"),
                PvButton.Style.PRIMARY, () -> answer(true)).size(72, PvTheme.BTN_H);
        accept.x = panelX + panelW - 12 - accept.width;
        accept.y = y;
        roots.add(accept);
        PvButton decline = new PvButton(PvLang.str("pvpocket.gui.reconnect.decline"),
                PvButton.Style.DANGER, this::askDecline).size(64, PvTheme.BTN_H);
        decline.x = accept.x - decline.width - 6;
        decline.y = y;
        roots.add(decline);
        setFocus(accept);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        Font tr = font();
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.reconnect.title"),
                panelX + 16, panelY + 11, PvTheme.TEXT);
        int top = panelY + chromeH + 8;
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.reconnect.round", round, maxRounds, score),
                panelX + 16, top, PvTheme.TEXT);
        PvDraw.textWrapLeft(ctx, tr, PvLang.str("pvpocket.gui.reconnect.hint"),
                panelX + 16, top + 14, BODY_H - 14, panelW - 32, 2, PvTheme.TEXT_DIM);
    }

    // ------------------------------------------------------------------ actions

    /**
     * Asks before giving the place up.
     *
     * <p>A dialog rather than a second press: this is the only button on the screen that cannot be
     * taken back, and it sits next to the one the player actually came to press.
     */
    private void askDecline() {
        setOverlay(new PvConfirm(
                PvLang.str("pvpocket.gui.reconnect.decline_title"),
                PvLang.str("pvpocket.gui.reconnect.decline_body"),
                PvLang.str("pvpocket.gui.reconnect.decline_note"),
                PvTheme.DANGER,
                PvLang.str("pvpocket.gui.reconnect.decline"),
                PvButton.Style.DANGER,
                PvLang.str("pvpocket.gui.reconnect.cancel"),
                () -> {
                    dismissOverlay();
                    answer(false);
                },
                this::dismissOverlay));
    }

    private void answer(boolean accept) {
        if (PvClientPlatform.get().canSend(ReconnectAnswerPayload.ID)) {
            PvClientPlatform.get().send(new ReconnectAnswerPayload(sessionId, accept));
        }
        PvScreens.open(Minecraft.getInstance(), null);
    }
}
