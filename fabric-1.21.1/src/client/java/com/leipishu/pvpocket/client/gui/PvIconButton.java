package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * A square, label-free button whose glyph is drawn rather than typed.
 *
 * <p>Exists because the visual specification forbids graphic characters in strings: a close button
 * labelled {@code ×} would render through whatever glyph the font happens to have, at a weight the
 * panel does not control. {@link PvDraw#cross} and {@link PvDraw#chevron} are hard pixels at every
 * GUI scale, which is the only way a 5px glyph stays a 5px glyph.</p>
 *
 * <p>It is a real widget rather than a rect checked inside its parent so that the layout audit sees
 * it, and so hover and focus animate through the same tweens as every other control.</p>
 */
public class PvIconButton extends PvWidget {

    public enum Glyph {
        /** Dismiss. Drawn 1.5px thick, centred. */
        CROSS,
        /** Pointing right or left; {@link #direction} chooses. */
        CHEVRON
    }

    private final Glyph glyph;
    private final Runnable action;
    /** +1 or -1, used by {@link Glyph#CHEVRON}. */
    public int direction = 1;
    public int color = PvTheme.TEXT_DIM;
    public int hoverColor = PvTheme.TEXT;

    public PvIconButton(Glyph glyph, int size, Runnable action) {
        this.glyph = glyph;
        this.action = action;
        this.width = size;
        this.height = size;
    }

    /**
     * A disabled icon button does not hover.
     *
     * <p>The pager is why: on the first page the "previous" arrow is disabled, and a button that
     * lights up under the pointer while refusing the click is the interface promising something it
     * will not do. Damping the highlight on {@code enabled} rather than skipping the fill also
     * means the glow fades out if the control is disabled while the pointer is on it, instead of
     * snapping off.</p>
     */
    @Override
    public void updateHover(int mouseX, int mouseY) {
        hovered = enabled && visible && contains(mouseX, mouseY);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);
        if (hoverT > 0.01F) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS,
                    PvTheme.alpha(PvTheme.CARD_HOVER, hoverT));
        }
        int ink = enabled ? PvTheme.mix(color, hoverColor, hoverT) : PvTheme.TEXT_MUTED;
        int cx = x + width / 2;
        int cy = y + height / 2;
        switch (glyph) {
            case CROSS -> PvDraw.cross(ctx, cx, cy, 3, ink);
            case CHEVRON -> PvDraw.chevron(ctx, cx, cy, direction, 7, ink);
        }
        drawFocusRing(ctx, PvTheme.RADIUS);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.1F));
        if (action != null) {
            action.run();
        }
        return true;
    }

    @Override
    public void activate() {
        if (action != null) {
            action.run();
        }
    }
}
