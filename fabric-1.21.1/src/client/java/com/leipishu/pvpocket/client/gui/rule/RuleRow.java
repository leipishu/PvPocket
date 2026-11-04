package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvValueControl;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.rules.RuleKind;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * One rule: label on the left, control on the right.
 *
 * <p>Multi-select rules get a taller row with the chips on their own line. Squeezing five chips into
 * a 130px column would either shrink the labels below legibility or force a nested scroll, and both
 * are worse than spending 18 extra vertical pixels.</p>
 *
 * <p>The row tracks its own "differs from the received version" state against the baseline value it
 * was built with, and shows it as a 2px accent bar. That is the whole reason a reviewer can tell at
 * a glance what the proposer changed without reading all thirty-six rows.</p>
 */
public class RuleRow extends PvWidget {

    private final RuleDefinition def;
    private final PvValueControl control;
    private final int baseline;
    private final boolean editable;

    public RuleRow(RuleDefinition def, PvValueControl control, int baseline, boolean editable) {
        this.def = def;
        this.control = control;
        this.baseline = baseline;
        this.editable = editable;
        this.height = def.kind == RuleKind.MULTI ? PvTheme.ROW_H_TALL : PvTheme.ROW_H;
        this.tooltip = def.hint;
    }

    private boolean changed() {
        return control != null && control.value() != baseline;
    }

    private void layoutControl() {
        if (control == null) {
            return;
        }
        control.enabled = editable;
        if (def.kind == RuleKind.MULTI) {
            control.x = x + 8;
            control.y = y + height - control.height - 5;
            control.width = width - 16;
        } else {
            control.x = x + width - control.width - 8;
            control.y = y + (height - control.height) / 2;
        }
    }

    /** Locked rows are not tab stops: focusing something that cannot be changed is a dead end. */
    @Override
    public boolean focusable() {
        return super.focusable() && control != null;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        layoutControl();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        if (hovered) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS, 0x10FFFFFF);
        }
        if (changed()) {
            PvDraw.roundRect(ctx, x, y + 4, 2, height - 8, 1, PvTheme.ACCENT);
        }

        int labelColor = editable ? PvTheme.TEXT : PvTheme.TEXT_DIM;
        int labelX = x + 8;

        if (def.kind == RuleKind.MULTI) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr, def.label, width - 16), labelX, y + 5, labelColor);
            if (control != null) {
                control.updateHover(mouseX, mouseY);
                control.render(ctx, mouseX, mouseY, delta);
            }
        } else {
            int labelY = y + (height - 8) / 2 + 1;
            int reserved = control == null ? 96 : control.width + 12;
            PvDraw.text(ctx, tr, PvDraw.fit(tr, def.label, width - reserved - 12), labelX, labelY, labelColor);
            if (control != null) {
                control.updateHover(mouseX, mouseY);
                control.render(ctx, mouseX, mouseY, delta);
            } else {
                renderLockedValue(ctx, tr, labelY);
            }
        }
    }

    /** Read-only value chip for {@link RuleKind#FIXED} rules. Looks like a control, cannot be one. */
    private void renderLockedValue(DrawContext ctx, TextRenderer tr, int labelY) {
        String value = def.format(0);
        int w = tr.getWidth(value) + 14;
        int cx = x + width - w - 8;
        PvDraw.roundPanel(ctx, cx, y + (height - 16) / 2, w, 16, PvTheme.RADIUS,
                PvTheme.DIVIDER, PvTheme.SURFACE_SUNKEN);
        PvDraw.textCenter(ctx, tr, value, cx + w / 2, y + (height - 8) / 2 + 1, PvTheme.TEXT_MUTED);
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

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY)) {
            return null;
        }
        return tooltip;
    }
}
