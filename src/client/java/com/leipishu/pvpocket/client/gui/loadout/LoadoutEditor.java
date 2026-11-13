package com.leipishu.pvpocket.client.gui.loadout;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvChips;
import com.leipishu.pvpocket.client.gui.PvCycle;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvLayout;
import com.leipishu.pvpocket.client.gui.PvScrollPane;
import com.leipishu.pvpocket.client.gui.PvSlider;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvToggle;
import com.leipishu.pvpocket.client.gui.PvValueControl;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.client.gui.rule.RuleRow;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The item editor, as a modal over the loadout list.
 *
 * <p>The controls write into a copy of the entry, and only confirm copies it back, so cancel
 * leaves the original untouched.
 *
 * <p>The field rows are the same {@link RuleRow}s the rule panel uses, so a slider here and a
 * slider there share one widget and one keyboard path.
 */
public class LoadoutEditor extends PvWidget {

    /** Called with the edited draft; the screen decides whether to replace an entry or add one. */
    public interface Listener {
        void onConfirm(LoadoutEntry draft);

        void onCancel();
    }

    private static final int HEAD_H = 30;
    private static final int FOOT_H = 30;
    private static final int MIN_W = 240;
    private static final int MAX_W = 340;

    private final LoadoutEntry draft;
    private final PvScrollPane pane = new PvScrollPane();
    private final PvButton confirm;
    private final PvButton cancel;
    private Listener listener;
    private float appearT;

    /**
     * @param source the entry to edit, or a fresh one for an add; it is copied, never touched
     */
    public LoadoutEditor(LoadoutEntry source) {
        this.draft = source.copy();
        this.confirm = new PvButton(PvLang.str("pvpocket.gui.loadout.editor.confirm"),
                PvButton.Style.PRIMARY, this::fireConfirm);
        this.cancel = new PvButton(PvLang.str("pvpocket.gui.loadout.editor.cancel"),
                PvButton.Style.GHOST, this::fireCancel);

        pane.gap = 2;
        for (LoadoutFields.Field field : LoadoutFields.of(draft)) {
            int value = field.get().getAsInt();
            PvValueControl control = switch (field.def().kind) {
                case TOGGLE -> new PvToggle(value != 0);
                case INT -> new PvSlider(field.def(), value);
                case ENUM -> new PvCycle(field.def(), value);
                case MULTI -> new PvChips(field.def(), value);
                case FIXED -> null;
            };
            if (control != null) {
                control.onChange(field.set()::accept);
            }
            pane.add(new RuleRow(field.def(), control, value, true));
        }
    }

    public void listener(Listener listener) {
        this.listener = listener;
    }

    /** Returns the draft the confirm button would submit, for the live list summary. */
    public LoadoutEntry draft() {
        return draft;
    }

    private void fireConfirm() {
        if (listener != null) {
            listener.onConfirm(draft);
        }
    }

    private void fireCancel() {
        if (listener != null) {
            listener.onCancel();
        }
    }

    private int panelW() {
        return PvLayout.clamp(width - 40, MIN_W, MAX_W);
    }

    private int panelH() {
        return PvLayout.clamp(height - 40, HEAD_H + FOOT_H + 40, 240);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private void placeChrome() {
        int px = panelX();
        int py = panelY();
        pane.x = px + 12;
        pane.y = py + HEAD_H;
        pane.width = panelW() - 24;
        pane.height = panelH() - HEAD_H - FOOT_H;

        int buttonY = py + panelH() - FOOT_H + 5;
        confirm.width = 56;
        confirm.height = PvTheme.BTN_H;
        confirm.x = px + panelW() - 12 - confirm.width;
        confirm.y = buttonY;
        cancel.width = 48;
        cancel.height = PvTheme.BTN_H;
        cancel.x = confirm.x - 8 - cancel.width;
        cancel.y = buttonY;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        appearT = PvAnim.damp(appearT, 1F, d, 0.05F);
        Font tr = Minecraft.getInstance().font;
        placeChrome();

        // Darker than the screen's own backdrop, so the modal reads above the panel's 77% scrim.
        // Item icons are not GUI pixels and are hidden by the overlay (see PvDraw.itemsVisible).
        ctx.fill(0, 0, width, height, PvTheme.alpha(0xA0000000, appearT));

        int px = panelX();
        int py = panelY();
        int pw = panelW();
        int ph = panelH();
        PvDraw.shadow(ctx, px, py, pw, ph, PvTheme.RADIUS_PANEL);
        PvDraw.roundPanel(ctx, px, py, pw, ph, PvTheme.RADIUS_PANEL,
                PvTheme.alpha(PvTheme.BORDER_STRONG, appearT),
                PvTheme.alpha(PvTheme.PANEL, appearT));

        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.loadout.editor.title", draft.kind.labelText()),
                px + 12, py + 8, PvTheme.TEXT);
        String hint = PvLang.str("pvpocket.gui.loadout.editor.hint");
        PvDraw.text(ctx, tr, PvDraw.fit(tr, hint, pw - 24), px + 12, py + 19, PvTheme.TEXT_MUTED);
        PvDraw.hLine(ctx, px + 12, py + HEAD_H - 4, pw - 24, PvTheme.DIVIDER);

        pane.render(ctx, mouseX, mouseY, delta);

        PvDraw.hLine(ctx, px + 12, py + ph - FOOT_H, pw - 24, PvTheme.DIVIDER);
        confirm.updateHover(mouseX, mouseY);
        cancel.updateHover(mouseX, mouseY);
        cancel.render(ctx, mouseX, mouseY, delta);
        confirm.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0) {
            return false;
        }
        placeChrome();
        int px = panelX();
        int py = panelY();
        if (mx < px || mx >= px + panelW() || my < py || my >= py + panelH()) {
            // A click on the scrim cancels. Consumed either way, so it cannot press whatever the
            // dialog was covering.
            fireCancel();
            return true;
        }
        if (confirm.mouseClicked(mx, my, button) || cancel.mouseClicked(mx, my, button)) {
            return true;
        }
        pane.mouseClicked(mx, my, button);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        placeChrome();
        confirm.mouseReleased(mx, my, button);
        cancel.mouseReleased(mx, my, button);
        pane.mouseReleased(mx, my, button);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        pane.mouseDragged(mx, my, button, dx, dy);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        pane.mouseScrolled(mx, my, amount);
        return true;
    }

    /** The dialog is a single Tab stop; its rows keep their own keyboard handling via the pane. */
    @Override
    public boolean focusable() {
        return false;
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible) {
            return null;
        }
        String tip = pane.tooltipAt(mouseX, mouseY);
        if (tip != null) {
            return tip;
        }
        return tooltip;
    }
}
