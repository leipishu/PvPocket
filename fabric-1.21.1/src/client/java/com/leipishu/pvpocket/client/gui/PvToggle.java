package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * Boolean switch: a pill track with a sliding knob.
 *
 * <p>The only pill-shaped control in the system. A 4px radius on a 14px-tall track reads as a
 * slider, and the one thing a switch must never be confused with is the control next to it that
 * also has a fill and a knob.</p>
 *
 * <p>No border. A 1px outline on a 14px pill is 7% of its height and turns the track into a
 * three-tone object — outline, track, knob — which at this size reads as a heavier control than it
 * is. The fill alone carries the state.</p>
 *
 * <p>The knob is driven by a fixed-duration tween with a slight overshoot rather than by damping:
 * a switch has exactly two destinations and the motion is the confirmation that the state changed,
 * so a small amount of weight is worth more here than retargetability. The value itself flips
 * immediately, so a fast click never loses an input to the animation.</p>
 */
public class PvToggle extends PvValueControl {

    private static final int TRACK_W = 28;
    private static final int TRACK_H = 14;
    private static final int KNOB = 10;
    private static final float TRAVEL_TIME = 0.14F;

    private float anim;
    private float from;
    private float to;
    private float elapsed = 1F;

    public PvToggle(boolean on) {
        this.value = on ? 1 : 0;
        this.anim = this.value;
        this.from = this.value;
        this.to = this.value;
        this.width = TRACK_W;
        this.height = TRACK_H;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);

        if (to != value) {
            from = anim;
            to = value;
            elapsed = 0F;
        }
        elapsed = Math.min(1F, elapsed + d / TRAVEL_TIME);
        anim = from + (to - from) * PvAnim.easeOutBack(elapsed);

        float pos = PvAnim.clamp01(anim);
        int radius = TRACK_H / 2;

        int trackOff = PvTheme.mix(PvTheme.CARD_ACTIVE, PvTheme.CARD_HOVER, hoverT);
        int trackOn = PvTheme.mix(PvTheme.ACCENT, PvTheme.ACCENT_HOVER, hoverT);
        int track = enabled ? PvTheme.mix(trackOff, trackOn, pos) : PvTheme.SUNKEN;

        PvDraw.roundRect(ctx, x, y, width, height, radius, track);

        int travel = width - KNOB - 4;
        int kx = x + 2 + Math.round(travel * pos);
        int knob = enabled
                ? PvTheme.mix(PvTheme.TEXT_MUTED, 0xFFFFFFFF, pos)
                : PvTheme.TEXT_MUTED;
        PvDraw.roundRect(ctx, kx, y + 2, KNOB, KNOB, KNOB / 2, knob);

        drawFocusRing(ctx, radius);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        value = value == 0 ? 1 : 0;
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, value == 1 ? 1.4F : 1.0F));
        emit();
        return true;
    }

    /** Keyboard path: Enter and either arrow all flip, matching the other value controls. */
    @Override
    public void activate() {
        flip();
    }

    @Override
    public void nudge(int direction) {
        flip();
    }

    public void flip() {
        value = value == 0 ? 1 : 0;
        emit();
    }
}
