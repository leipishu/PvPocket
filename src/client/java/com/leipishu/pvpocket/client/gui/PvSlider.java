package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Numeric slider with a live read-out.
 *
 * <p>The track maps the rule's own range onto pixels, so dragging can only produce values the rule
 * accepts.
 *
 * <p>While dragging the fill follows the cursor with no smoothing; while stepping with the arrow
 * keys it eases into place. The knob never changes size: hover and drag animate the alpha of a halo
 * behind it instead.
 */
public class PvSlider extends PvValueControl {

    /** Floor for the read-out column; the real width is measured from the value range below. */
    private static final int READOUT = 44;
    private static final int KNOB = 10;
    private static final int TRACK_H = 4;
    /**
     * Half the widest the knob ever draws: the dot plus its hover halo.
     *
     * <p>The track is inset by this at both ends, so at maximum value the knob does not reach into
     * the read-out column. The read-out cannot move: it is right-aligned to the row's control edge.
     */
    private static final int KNOB_MAX = KNOB / 2 + 3;

    /** How opaque the halo gets at full hover. Alpha has 256 steps, so no rounding is needed. */
    private static final float HALO = 0.20F;

    private final RuleDefinition def;
    private boolean dragging;
    private float display = -1F;
    /** Hover and drag share this one tween; see {@link #render}. */
    private float glow;
    /** Sentinel -1 until the first frame, then the widest read-out this rule can produce. */
    private int readoutW = -1;

    public PvSlider(RuleDefinition def, int value) {
        this.def = def;
        this.value = def.clamp(value);
        this.width = 140;
        this.height = 20;
    }

    /** Left edge of the track: 2px of air, then the widest the knob's halo ever draws. */
    private int trackLeft() {
        return x + 2 + KNOB_MAX;
    }

    /**
     * Width of the read-out, measured once from the extremes of the range.
     *
     * <p>A fixed 44 is too narrow for "500 uses". Sizing to the current value would make the track
     * jump sideways as digits are added while dragging.
     */
    private int readoutWidth(Font tr) {
        if (readoutW < 0) {
            readoutW = Math.max(READOUT, Math.max(
                    tr.width(def.format(def.min)),
                    tr.width(def.format(def.max))));
        }
        return readoutW;
    }

    /**
     * Track width: what is left after the read-out column and the knob radius at both ends.
     *
     * <p>The knob is centred on the end of the track, so without the right-hand reservation half of
     * it hangs over the read-out at maximum value. Reserving the radius moves the end of the track
     * left instead of moving the read-out, which is right-aligned to the row's control edge.
     *
     * <p>The 2px of air plus the 4px gap keep the halo off the glyphs.
     */
    private int trackWidth() {
        return Math.max(1, width - readoutW - 4 - KNOB_MAX * 2);
    }

    private float ratio() {
        int span = def.max - def.min;
        return span <= 0 ? 0F : (float) (value - def.min) / span;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        // Hover and drag drive the same cue, so they drive the same tween.
        glow = PvAnim.damp(glow, (hovered || dragging) && enabled ? 1F : 0F, d, 0.09F);
        tickFocus(d);

        float target = ratio();
        if (display < 0F || dragging) {
            display = target;
        } else {
            display = PvAnim.damp(display, target, d, 0.05F);
        }

        Font tr = Minecraft.getInstance().font;
        int readoutW = readoutWidth(tr);
        int trackX = trackLeft();
        int trackW = trackWidth();
        int trackY = y + height / 2 - TRACK_H / 2;

        PvDraw.roundRect(ctx, trackX, trackY, trackW, TRACK_H, TRACK_H / 2, PvTheme.SUNKEN);

        int filled = Math.round(trackW * PvAnim.clamp01(display));
        int fillColor = enabled
                ? PvTheme.mix(PvTheme.ACCENT, PvTheme.ACCENT_HOVER, glow * 0.6F)
                : PvTheme.ACCENT_DIM;
        if (filled > 0) {
            PvDraw.roundRect(ctx, trackX, trackY, filled, TRACK_H, TRACK_H / 2, fillColor);
        }

        // The knob is a fixed-size disc with a halo behind it whose alpha animates. A radius driven
        // by the tween would jump in 1px steps, while alpha quantises to 1/255.
        int kx = Mth.clamp(trackX + filled, trackX, trackX + trackW);
        int ky = y + height / 2;
        int knobColor = enabled ? PvTheme.mix(PvTheme.TEXT, 0xFFFFFFFF, glow) : PvTheme.TEXT_MUTED;
        if (glow > 0.02F) {
            PvDraw.dot(ctx, kx, ky, KNOB_MAX, PvTheme.alpha(knobColor, glow * HALO));
        }
        PvDraw.dot(ctx, kx, ky, KNOB / 2, knobColor);

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
        readoutWidth(Minecraft.getInstance().font);
        int trackW = trackWidth();
        float t = (float) ((mx - trackLeft()) / trackW);
        t = Mth.clamp(t, 0F, 1F);
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
