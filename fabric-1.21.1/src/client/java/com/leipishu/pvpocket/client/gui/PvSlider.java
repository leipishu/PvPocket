package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * Numeric slider with live read-out.
 *
 * <p>The track maps the rule's own range onto pixels, so the step size is honoured exactly: dragging
 * can only ever produce values the rule accepts, and there is no separate validation step that can
 * disagree with the visual position.</p>
 */
public class PvSlider extends PvValueControl {

    private static final int READOUT = 38;
    private static final int KNOB = 10;

    private final RuleDefinition def;
    private boolean dragging;

    public PvSlider(RuleDefinition def, int value) {
        this.def = def;
        this.value = def.clamp(value);
        this.width = 132;
        this.height = 18;
    }

    private int trackLeft() {
        return x + 2;
    }

    private int trackWidth() {
        return Math.max(1, width - READOUT - 4);
    }

    private float ratio() {
        int span = def.max - def.min;
        return span <= 0 ? 0F : (float) (value - def.min) / span;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int trackX = trackLeft();
        int trackW = trackWidth();
        int trackY = y + height / 2 - 2;

        PvDraw.roundRect(ctx, trackX, trackY, trackW, 4, 2, PvTheme.SURFACE_SUNKEN);

        int filled = Math.round(trackW * ratio());
        int fillColor = enabled ? PvTheme.ACCENT : PvTheme.ACCENT_DIM;
        if (filled > 0) {
            PvDraw.roundRect(ctx, trackX, trackY, filled, 4, 2, fillColor);
        }

        int kx = trackX + filled - KNOB / 2;
        kx = MathHelper.clamp(kx, trackX - 2, trackX + trackW - KNOB + 2);
        int knobColor = !enabled ? PvTheme.TEXT_MUTED
                : (hovered || dragging) ? 0xFFFFFFFF : PvTheme.TEXT;
        PvDraw.roundRect(ctx, kx, y + height / 2 - KNOB / 2, KNOB, KNOB, KNOB / 2, knobColor);

        String readout = def.format(value);
        int color = enabled ? PvTheme.TEXT : PvTheme.TEXT_MUTED;
        if (focused && enabled) {
            color = PvTheme.ACCENT;
        }
        PvDraw.textRight(ctx, tr, PvDraw.fit(tr, readout, READOUT), x + width, y + (height - 8) / 2 + 1, color);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0) {
            return false;
        }
        // The whole widget is a hit target, not just the 4px track: a thin track is unusable at
        // GUI scale 1, and the read-out on the right is part of the same control.
        if (!contains(mx, my)) {
            return false;
        }
        dragging = true;
        applyMouse(mx);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging || !enabled) {
            return false;
        }
        applyMouse(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    @Override
    public void nudge(int direction) {
        if (!enabled) {
            return;
        }
        int next = def.clamp(value + direction * Math.max(1, def.step));
        if (next != value) {
            value = next;
            emit();
        }
    }

    private void applyMouse(double mx) {
        int trackW = trackWidth();
        float t = (float) ((mx - trackLeft()) / trackW);
        t = MathHelper.clamp(t, 0F, 1F);
        int raw = Math.round(def.min + t * (def.max - def.min));
        // Snap onto the step grid so the value the slider produces is always one the rule can hold.
        int step = Math.max(1, def.step);
        int snapped = def.min + Math.round((float) (raw - def.min) / step) * step;
        snapped = def.clamp(snapped);
        if (snapped != value) {
            value = snapped;
            emit();
        }
    }
}
