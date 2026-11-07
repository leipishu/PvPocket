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
 *
 * <p>Two different behaviours share one knob. While dragging, the fill follows the cursor with no
 * smoothing at all — a knob that lags the pointer feels broken. While stepping with the arrow keys,
 * the fill eases into place, because the only feedback for a keyboard press is the motion itself.</p>
 */
public class PvSlider extends PvValueControl {

    /** Floor for the read-out column; the real width is measured from the value range below. */
    private static final int READOUT = 44;
    private static final int KNOB = 10;
    private static final int TRACK_H = 4;

    private final RuleDefinition def;
    private boolean dragging;
    private float display = -1F;
    /** -1 until the first frame, then the widest read-out this rule can produce. */
    private int readoutW = -1;

    public PvSlider(RuleDefinition def, int value) {
        this.def = def;
        this.value = def.clamp(value);
        this.width = 140;
        this.height = 20;
    }

    private int trackLeft() {
        return x + 2;
    }

    /**
     * Width of the read-out, measured once from the extremes of the range.
     *
     * <p>A fixed 44 was one character too narrow for "500 uses": the read-out was ellipsised, which
     * reads as a broken number rather than as a short column. Measuring the endpoints and keeping the
     * result fixed avoids the other failure — sizing to the current value would make the track jump
     * sideways every time a digit is added while dragging.</p>
     */
    private int readoutWidth(TextRenderer tr) {
        if (readoutW < 0) {
            readoutW = Math.max(READOUT, Math.max(
                    tr.getWidth(def.format(def.min)),
                    tr.getWidth(def.format(def.max))));
        }
        return readoutW;
    }

    private int trackWidth() {
        return Math.max(1, width - readoutW - 6);
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
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);

        float target = ratio();
        if (display < 0F || dragging) {
            display = target;
        } else {
            display = PvAnim.damp(display, target, d, 0.05F);
        }

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int readoutW = readoutWidth(tr);
        int trackX = trackLeft();
        int trackW = trackWidth();
        int trackY = y + height / 2 - TRACK_H / 2;

        PvDraw.roundRect(ctx, trackX, trackY, trackW, TRACK_H, TRACK_H / 2, PvTheme.SUNKEN);

        int filled = Math.round(trackW * PvAnim.clamp01(display));
        int fillColor = enabled
                ? PvTheme.mix(PvTheme.ACCENT, PvTheme.ACCENT_HOVER, hoverT * 0.6F)
                : PvTheme.ACCENT_DIM;
        if (filled > 0) {
            PvDraw.roundRect(ctx, trackX, trackY, filled, TRACK_H, TRACK_H / 2, fillColor);
        }

        int kr = KNOB / 2 + Math.round(hoverT * 1.5F) + (dragging ? 1 : 0);
        int kx = MathHelper.clamp(trackX + filled, trackX, trackX + trackW);
        int knobColor = !enabled ? PvTheme.TEXT_MUTED
                : PvTheme.mix(PvTheme.TEXT, 0xFFFFFFFF, hoverT);
        PvDraw.dot(ctx, kx, y + height / 2, kr, knobColor);

        String readout = def.format(value);
        int color = enabled ? PvTheme.TEXT : PvTheme.TEXT_MUTED;
        if (focused && enabled) {
            color = PvTheme.ACCENT;
        }
        PvDraw.textRight(ctx, tr, PvDraw.fit(tr, readout, readoutW), x + width,
                y + (height - 8) / 2 + 1, color);

        drawFocusRing(ctx, PvTheme.RADIUS);
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
        // The read-out is normally measured on the first frame, but a click can arrive before any
        // render if the widget was just added; measuring here keeps the mapping identical.
        readoutWidth(MinecraftClient.getInstance().textRenderer);
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
