package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * Boolean switch: a pill track with a sliding knob.
 *
 * <p>The knob animates on a fixed time constant rather than a frame count, so the motion looks the
 * same at 30, 60 and 240 fps. Animation is presentation only — the value flips immediately, so a
 * fast click never loses an input to the tween.</p>
 */
public class PvToggle extends PvValueControl {

    private float anim;

    public PvToggle(boolean on) {
        this.value = on ? 1 : 0;
        this.anim = this.value;
        this.width = 30;
        this.height = 14;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float speed = 1F - (float) Math.pow(0.72D, Math.max(0.05F, delta));
        anim += (value - anim) * speed;
        if (Math.abs(value - anim) < 0.01F) {
            anim = value;
        }

        int radius = height / 2;
        int trackOff = enabled ? PvTheme.SURFACE_SUNKEN : PvTheme.SURFACE_SUNKEN;
        int trackOn = enabled ? PvTheme.ACCENT : PvTheme.ACCENT_DIM;
        int track = PvTheme.mix(trackOff, trackOn, anim);
        int border = !enabled ? PvTheme.DIVIDER
                : hovered ? PvTheme.mix(PvTheme.PANEL_BORDER, PvTheme.ACCENT, 0.5F)
                : PvTheme.PANEL_BORDER;

        PvDraw.roundPanel(ctx, x, y, width, height, radius, border, track);

        int knob = height - 4;
        int travel = width - knob - 4;
        int kx = x + 2 + Math.round(travel * anim);
        int knobColor = !enabled ? PvTheme.TEXT_MUTED
                : anim > 0.5F ? 0xFFFFFFFF : PvTheme.TEXT_DIM;
        PvDraw.roundRect(ctx, kx, y + 2, knob, knob, knob / 2, knobColor);

        if (focused && enabled) {
            PvDraw.roundRect(ctx, x - 1, y - 1, width + 2, height + 2, radius + 1, PvTheme.ACCENT_SOFT);
        }
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
