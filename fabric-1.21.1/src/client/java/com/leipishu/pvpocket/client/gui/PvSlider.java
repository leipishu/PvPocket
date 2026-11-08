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
 *
 * <p>The knob itself never changes size. Hover and drag light a halo behind it instead, and only the
 * halo's alpha animates: the disc is drawn at one fixed radius, so there is no integer to round and
 * nothing to step between. Anything that animates a drawn <em>size</em> on this scale has to be a
 * whole number of pixels, which is two states over a 1.5px range and reads as a stutter no matter how
 * smooth the underlying tween is.</p>
 */
public class PvSlider extends PvValueControl {

    /** Floor for the read-out column; the real width is measured from the value range below. */
    private static final int READOUT = 44;
    private static final int KNOB = 10;
    private static final int TRACK_H = 4;
    /**
     * Half the widest the knob ever draws: the dot itself plus its hover halo.
     *
     * <p>The track is inset by this at both ends. Without it the knob sits at the very end of the
     * track when the value is at its maximum, and its radius reaches past the track into the
     * read-out column — "5 players" with a white circle on the 5. Reserving the radius is the only
     * way to keep the read-out clear without moving the read-out itself, and the read-out cannot
     * move: it is right-aligned to the row's control edge, which is the column every other control
     * shares.</p>
     */
    private static final int KNOB_MAX = KNOB / 2 + 3;

    /** How opaque the halo gets at full hover. Alpha has 256 steps, so the fade needs no rounding. */
    private static final float HALO = 0.20F;

    private final RuleDefinition def;
    private boolean dragging;
    private float display = -1F;
    /** Hover and drag share this one tween; see {@link #render}. */
    private float glow;
    /** -1 until the first frame, then the widest read-out this rule can produce. */
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

    /**
     * Track width: what is left after the read-out column and the knob's radius at <em>both</em>
     * ends.
     *
     * <p>The right-hand reservation is the whole point. The track used to stop one gap short of the
     * read-out, which is correct for the bar and wrong for the knob: the knob is centred on the
     * end of the track, so at maximum value half of it hung over "5 players" and the number came
     * out from under a white disc. Reserving the radius at the right end moves the end of the
     * track left instead of moving the read-out, and the read-out cannot move — it is right-aligned
     * to the row's control edge, the column every other control shares.</p>
     *
     * <p>The 2px of air plus the 4px gap are what is left of the old spacing once the radius is
     * taken out; they are not a fudge factor, they keep the halo off the glyphs.</p>
     */
    private int trackWidth() {
        return Math.max(1, width - readoutW - 4 - KNOB_MAX * 2);
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
        // Hover and drag drive the same cue, so they drive the same tween. The old code kept two
        // states and let the drag one snap the knob a pixel wider on the frame the button went
        // down, which is a pop, not a transition.
        glow = PvAnim.damp(glow, (hovered || dragging) && enabled ? 1F : 0F, d, 0.09F);
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
                ? PvTheme.mix(PvTheme.ACCENT, PvTheme.ACCENT_HOVER, glow * 0.6F)
                : PvTheme.ACCENT_DIM;
        if (filled > 0) {
            PvDraw.roundRect(ctx, trackX, trackY, filled, TRACK_H, TRACK_H / 2, fillColor);
        }

        // The knob is a fixed-size disc with a halo behind it whose alpha is what animates.
        //
        // Sizing the disc itself from hoverT was the stutter: `Math.round(hoverT * 1.5F)` maps a
        // continuous 0..1 tween onto two integer radii, so the hover arrived as one 1px jump at
        // 33% and another at 67%, and un-hovering jumped back the same way. Alpha is quantised to
        // 1/255, which is below the eye's threshold on a 20px-tall control, so the same tween read
        // out as an alpha looks like what it is: a fade.
        int kx = MathHelper.clamp(trackX + filled, trackX, trackX + trackW);
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
