package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * "Are you sure?" for an action that cannot be undone.
 *
 * <p>A dialog rather than a second press on the same button, which is what the preset row does for
 * its delete. The difference is the blast radius: deleting a preset costs its author one preset, and
 * this ends every duel on the server and freezes the world for a few seconds while the maps are
 * re-stamped. A mis-click that a second press can confirm is not enough of a speed bump for that, so
 * the question gets its own surface, its own sentence, and its own cancel button.</p>
 *
 * <p>The body is two strings rather than one paragraph: the first says what will happen, the second
 * says what cannot be undone. Both are fitted and wrapped rather than trusted — a translated
 * sentence is routinely twice the width of the English one, and a confirmation that cuts its own
 * warning mid-word is a confirmation nobody reads.</p>
 */
public class ConfirmOverlay extends PvWidget {

    private static final int CARD_W = 268;
    private static final int CARD_H = 112;
    private static final int BTN_H = 20;

    private final String title;
    private final String body;
    private final String note;
    private final Runnable onConfirm;
    private final Runnable onClose;
    private final PvButton confirm;
    private final PvButton cancel;

    public ConfirmOverlay(String title, String body, String note, String confirmLabel,
                          Runnable onConfirm, Runnable onClose) {
        this.title = title;
        this.body = body;
        this.note = note;
        this.onConfirm = onConfirm;
        this.onClose = onClose;
        // Danger red on the confirming button, not on the trigger: by the time this is drawn the
        // question has already been asked, and the answer is the destructive half.
        this.confirm = new PvButton(confirmLabel, PvButton.Style.DANGER, this::accept).size(84, BTN_H);
        this.cancel = new PvButton(PvLang.str("pvpocket.gui.home.admin.cancel"),
                PvButton.Style.GHOST, onClose).size(56, BTN_H);
    }

    private int cardX() {
        return x + (width - CARD_W) / 2;
    }

    private int cardY() {
        return y + (height - CARD_H) / 2;
    }

    private void accept() {
        onConfirm.run();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        PvDraw.rect(ctx, x, y, width, height, PvTheme.alpha(PvTheme.SCRIM, 0.85F));
        int cx = cardX();
        int cy = cardY();
        PvDraw.roundPanel(ctx, cx, cy, CARD_W, CARD_H, PvTheme.RADIUS_PANEL, PvTheme.BORDER,
                PvTheme.PANEL);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        PvDraw.textFitLeft(ctx, tr, title, cx + 16, cy + 12, CARD_W - 32, PvTheme.TEXT);
        // Two lines of body at most: a third would collide with the buttons, and the sentence is a
        // warning, not documentation.
        PvDraw.textWrapLeft(ctx, tr, body, cx + 16, cy + 30, 26, CARD_W - 32, 2, PvTheme.TEXT_DIM);
        PvDraw.textFitLeft(ctx, tr, note, cx + 16, cy + 60, CARD_W - 32, PvTheme.DANGER);

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
