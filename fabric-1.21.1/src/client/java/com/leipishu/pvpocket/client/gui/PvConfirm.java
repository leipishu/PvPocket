package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * "Are you sure?" for an action that cannot be undone.
 *
 * <p>A dialog rather than a second press on the same button, which is what the preset row does for
 * its delete. The difference is the blast radius: deleting a preset costs its author one preset, and
 * the two callers here end every duel on the server or throw away a loadout the player has just spent
 * five minutes on. A mis-click that a second press can confirm is not enough of a speed bump for
 * either, so the question gets its own surface, its own sentence, and its own cancel button.</p>
 *
 * <p>The body is two strings rather than one paragraph: the first says what will happen, the second
 * says what cannot be undone. Both are fitted and wrapped rather than trusted — a translated
 * sentence is routinely twice the width of the English one, and a confirmation that cuts its own
 * warning mid-word is a confirmation nobody reads.</p>
 *
 * <p>Lives in {@code gui} rather than beside its first caller because the loadout editor asks the
 * same question with a different colour on the button: "discard these changes" is destructive for
 * the draft and reversible for the panel, so the caller owns the button style and the note colour.</p>
 */
public class PvConfirm extends PvWidget {

    private static final int CARD_W = 268;
    private static final int CARD_H = 112;
    private static final int BTN_H = 20;

    private final String title;
    private final String body;
    private final String note;
    private final int noteColor;
    private final PvButton confirm;
    private final PvButton cancel;

    public PvConfirm(String title, String body, String note, int noteColor,
                     String confirmLabel, PvButton.Style confirmStyle, String cancelLabel,
                     Runnable onConfirm, Runnable onClose) {
        this.title = title;
        this.body = body;
        this.note = note;
        this.noteColor = noteColor;
        // The colour is on the confirming button, not on the trigger: by the time this is drawn the
        // question has already been asked, and the answer is the half that acts.
        this.confirm = new PvButton(confirmLabel, confirmStyle, onConfirm).size(84, BTN_H);
        this.cancel = new PvButton(cancelLabel, PvButton.Style.GHOST, onClose).size(56, BTN_H);
    }

    private int cardX() {
        return x + (width - CARD_W) / 2;
    }

    private int cardY() {
        return y + (height - CARD_H) / 2;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        PvDraw.rect(ctx, x, y, width, height, PvTheme.alpha(PvTheme.SCRIM, 0.85F));
        int cx = cardX();
        int cy = cardY();
        PvDraw.roundPanel(ctx, cx, cy, CARD_W, CARD_H, PvTheme.RADIUS_PANEL, PvTheme.BORDER,
                PvTheme.PANEL);

        Font tr = Minecraft.getInstance().font;
        PvDraw.textFitLeft(ctx, tr, title, cx + 16, cy + 12, CARD_W - 32, PvTheme.TEXT);
        // Two lines of body at most: a third would collide with the buttons, and the sentence is a
        // warning, not documentation.
        PvDraw.textWrapLeft(ctx, tr, body, cx + 16, cy + 30, 26, CARD_W - 32, 2, PvTheme.TEXT_DIM);
        PvDraw.textFitLeft(ctx, tr, note, cx + 16, cy + 60, CARD_W - 32, noteColor);

        cancel.x = cx + 16;
        cancel.y = cy + CARD_H - 16 - BTN_H;
        confirm.x = cx + CARD_W - 16 - confirm.width;
        confirm.y = cancel.y;
        cancel.render(ctx, mouseX, mouseY, delta);
        confirm.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (PvWidget widget : children()) {
            if (widget.visible && widget.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        // A click anywhere on the scrim is swallowed: this is modal, and letting it fall through
        // would act on a panel the player cannot see behind the dialog.
        return contains(mx, my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = false;
        for (PvWidget widget : children()) {
            handled |= widget.mouseReleased(mx, my, button);
        }
        return handled;
    }

    private List<PvWidget> children() {
        return List.of(cancel, confirm);
    }

    @Override
    public List<PvWidget> childrenForAudit() {
        return children();
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        for (PvWidget widget : children()) {
            if (widget.visible && widget.contains(mouseX, mouseY)) {
                return widget.tooltipAt(mouseX, mouseY);
            }
        }
        return null;
    }
}
