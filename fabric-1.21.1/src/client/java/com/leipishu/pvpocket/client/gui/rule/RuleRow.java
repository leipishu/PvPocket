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
 * <p>Multi-select rules get a taller row with the chips on their own line. Squeezing five chips into
 * a 150px column would either shrink the labels below legibility or force a nested scroll, and both
 * are worse than spending 18 extra vertical pixels.</p>
 *
 * <p>The row tracks its own "differs from the received version" state against the baseline value it
 * was built with, and shows it as a 2px accent bar. That is the whole reason a reviewer can tell at
 * a glance what the proposer changed without reading all thirty-six rows.</p>
 */
public class RuleRow extends PvWidget {

    /**
     * Below this row width, label and control stop sharing a line.
     *
     * <p>The narrow layout used to be reserved for multi-select rules, whose chips genuinely need a
     * full-width line. A 170px column is the same situation for every kind: a 104px stepper plus a
     * 10px label inset plus the label itself does not fit, and right-aligning the control anyway put
     * its left edge outside the row — the control drew over the column beside it. Stacking costs 16px
     * of height and cannot overflow, which is the right trade in a scrollable list.</p>
     */
    private static final int NARROW_W = 190;

    /**
     * Top edge of the control inside a stacked row, and the air under it.
     *
     * <p>The stacked layout used to place the control by subtracting from the row's bottom edge
     * ({@code y + height - control.height - 6}), which only agrees with the label if every control
     * is the same height. Chips are 22 and a stepper is 20, so the chip row started at {@code y+12}
     * while the label ran to {@code y+14} — the chips were drawn over the last two pixels of
     * "Environmental hazards". Two pixels is enough to cut the bottom off every glyph in the word,
     * and it only showed on the multi-select rows, which are the ones tall enough to hide it.</p>
     *
     * <p>So the label owns the top of the row by construction: 5px of air, 8px of glyph, 1px of gap,
     * then the control, then 4px of air. The row height is derived from the control rather than
     * fixed, so a taller control makes a taller row instead of eating the label.</p>
     */
    private static final int STACK_CONTROL_TOP = 14;
    private static final int STACK_PAD = 4;
    /** y of the label's glyphs inside a stacked row. */
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

    /** Label band plus the control's own height plus the air under it; see {@link #STACK_CONTROL_TOP}. */
    private int stackedHeight() {
        return STACK_CONTROL_TOP + (control == null ? 0 : control.height) + STACK_PAD;
    }

    /**
     * Picks the layout from the width the container just gave us, and reports the height it costs.
     *
     * <p>The container asks before it totals the heights, so this is the only place the row can
     * change its own height in response to the column being narrow.</p>
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
        // starts to the left of the row's own x, which is how a stepper ended up painted over the
        // list beside it.
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

    /** Read-only value chip for {@link RuleKind#FIXED} rules. Looks like a control, cannot be one. */
    private void renderLockedValue(GuiGraphics ctx, Font tr, int labelY) {
        String value = def.format(0);
        int w = tr.width(value) + 16;
        int cx = x + width - w - 8;
        PvDraw.roundPanel(ctx, cx, y + (height - 18) / 2, w, 18, PvTheme.RADIUS,
                PvTheme.DIVIDER, PvTheme.SUNKEN);
        PvDraw.textCenter(ctx, tr, value, cx + w / 2, y + (height - 8) / 2 + 1, PvTheme.TEXT_MUTED);
    }

    /**
     * The row is the tab stop, the control is what the keys act on.
     *
     * <p>Focus is forwarded to the control before it renders so the control draws its own ring —
     * without this, Tab moved through thirty-six rows and lit up none of them, which made the whole
     * keyboard path unusable even though it was wired.</p>
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
     * Asks the control first.
     *
     * <p>A control can have something more specific to say than the rule's own hint — the map picker
     * says why a map is unavailable — and the row is the only thing the screen asks.</p>
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
     * The control is drawn inline rather than added to the screen's root list, so nothing else would
     * ever measure it — which is how a chips row sat two pixels over its own label with every check
     * reporting clean. Exposing it here puts it in front of {@link com.leipishu.pvpocket.client.gui.PvAudit}
     * like any other box.
     */
    @Override
    public java.util.List<PvWidget> childrenForAudit() {
        return control == null ? java.util.List.of() : java.util.List.of(control);
    }
}
