package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvValueControl;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.rules.RuleKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One rule: label on the left, control on the right.
 *
 * <p>Multi-select rules get a taller row with the chips on their own line.
 *
 * <p>The row compares its control's value with the baseline it was built with and shows the
 * difference as a 2px accent bar.
 */
public class RuleRow extends PvWidget {

    /**
     * Row width below which label and control stop sharing a line.
     *
     * <p>A 104px stepper plus a 10px label inset plus the label itself does not fit in a 170px
     * column. Stacking costs 16px of height and cannot overflow.
     */
    private static final int NARROW_W = 190;

    /**
     * Top edge of the control inside a stacked row, and the air under it.
     *
     * <p>The label owns the top of the row: 5px of air, 8px of glyph, 1px of gap, then the
     * control, then 4px of air. The row height is derived from the control, so a taller control
     * makes a taller row.
     */
    private static final int STACK_CONTROL_TOP = 14;
    private static final int STACK_PAD = 4;
    /** Y offset of the label's glyphs inside a stacked row. */
    private static final int STACK_LABEL_TOP = 5;

    private final RuleDefinition def;
    private final PvValueControl control;
    private final int baseline;
    private final boolean editable;
    private float changedT;
    private boolean narrow;

    public RuleRow(RuleDefinition def, PvValueControl control, int baseline, boolean editable) {
        this.def = def;
        this.control = control;
        this.baseline = baseline;
        this.editable = editable;
        this.height = stacked() ? stackedHeight() : PvTheme.ROW_H;
        this.tooltip = def.hint();
    }

    private boolean stacked() {
        return def.kind == RuleKind.MULTI || narrow;
    }

    /** Label band, the control's height and the air under it; see {@link #STACK_CONTROL_TOP}. */
    private int stackedHeight() {
        return STACK_CONTROL_TOP + (control == null ? 0 : control.height) + STACK_PAD;
    }

    /**
     * Picks the layout from the width the container gave, and reports the height it costs.
     *
     * <p>The container asks before it totals the heights.
     */
    @Override
    public void onWidth(int width) {
        boolean next = def.kind != RuleKind.MULTI && width < NARROW_W;
        if (next != narrow) {
            narrow = next;
            this.height = stacked() ? stackedHeight() : PvTheme.ROW_H;
        }
    }

    private boolean changed() {
        return control != null && control.value() != baseline;
    }

    private void layoutControl() {
        if (control == null) {
            return;
        }
        control.enabled = editable;
        // Never wider than the row: a control that keeps its designed width in a narrow column
        // starts left of the row's x and paints over the list beside it.
        control.width = Math.min(control.width, Math.max(24, width - 16));
        if (stacked()) {
            control.x = x + 8;
            control.y = y + STACK_CONTROL_TOP;
            control.width = Math.max(24, width - 16);
        } else {
            control.x = x + width - control.width - 8;
            control.y = y + (height - control.height) / 2;
        }
    }

    /** Locked rows are not tab stops: focusing something that cannot be changed is a dead end. */
    @Override
    public boolean focusable() {
        return super.focusable() && control != null && editable;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        changedT = PvAnim.damp(changedT, changed() ? 1F : 0F, d, 0.07F);
        layoutControl();
        Font tr = Minecraft.getInstance().font;

        if (hoverT > 0.01F) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS,
                    PvTheme.alpha(PvTheme.CARD_HOVER, hoverT * 0.75F));
        }
        if (changedT > 0.01F) {
            PvDraw.roundRect(ctx, x, y + 6, 2, height - 12, 1, PvTheme.alpha(PvTheme.ACCENT, changedT));
        }

        int labelColor = editable ? PvTheme.TEXT : PvTheme.TEXT_DIM;
        int labelX = x + 10;

        if (stacked()) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr, def.label(), width - 20), labelX, y + STACK_LABEL_TOP,
                    labelColor);
            if (control != null) {
                syncControlFocus();
                control.updateHover(mouseX, mouseY);
                control.render(ctx, mouseX, mouseY, delta);
            }
        } else {
            int labelY = y + (height - 8) / 2 + 1;
            int reserved = control == null ? 96 : control.width + 16;
            PvDraw.text(ctx, tr, PvDraw.fit(tr, def.label(), width - reserved - 10), labelX, labelY,
                    labelColor);
            if (control != null) {
                syncControlFocus();
                control.updateHover(mouseX, mouseY);
                control.render(ctx, mouseX, mouseY, delta);
            } else {
                renderLockedValue(ctx, tr, labelY);
            }
        }
    }

    /** Read-only value chip for {@link RuleKind#FIXED} rules. It looks like a control. */
    private void renderLockedValue(GuiGraphics ctx, Font tr, int labelY) {
        String value = def.format(0);
        int w = tr.width(value) + 16;
        int cx = x + width - w - 8;
        PvDraw.roundPanel(ctx, cx, y + (height - 18) / 2, w, 18, PvTheme.RADIUS,
                PvTheme.DIVIDER, PvTheme.SUNKEN);
        PvDraw.textCenter(ctx, tr, value, cx + w / 2, y + (height - 8) / 2 + 1, PvTheme.TEXT_MUTED);
    }

    /**
     * Copies the row's focus onto the control, so the control draws its own focus ring.
     */
    private void syncControlFocus() {
        if (control != null) {
            control.focused = focused;
        }
    }

    @Override
    public void activate() {
        if (control != null) {
            control.activate();
        }
    }

    @Override
    public void nudge(int direction) {
        if (control != null) {
            control.nudge(direction);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || control == null) {
            return false;
        }
        layoutControl();
        return control.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return control != null && control.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return control != null && control.mouseDragged(mx, my, button, dx, dy);
    }

    /**
     * Returns the control's own tooltip when it has one, and the rule's hint otherwise.
     */
    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY)) {
            return null;
        }
        if (control != null) {
            String specific = control.tooltipAt(mouseX, mouseY);
            if (specific != null) {
                return specific;
            }
        }
        return tooltip;
    }

    /**
     * Returns the inline control, so the layout audit can measure it.
     */
    @Override
    public java.util.List<PvWidget> childrenForAudit() {
        return control == null ? java.util.List.of() : java.util.List.of(control);
    }
}
