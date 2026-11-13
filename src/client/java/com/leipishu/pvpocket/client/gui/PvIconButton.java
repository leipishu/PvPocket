package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * A square, label-free button whose glyph is drawn rather than typed.
 *
 * <p>The glyph comes from {@link PvDraw#cross} or {@link PvDraw#chevron}. Both are hard pixels at
 * every GUI scale, so a 5px glyph stays 5px.
 *
 * <p>A real widget, so the layout audit sees it and hover and focus animate like other controls.
 */
public class PvIconButton extends PvWidget {

    public enum Glyph {
        /** Dismiss. Drawn 1.5px thick, centred. */
        CROSS,
        /** Pointing right or left, chosen by {@link #direction}. */
        CHEVRON
    }

    private final Glyph glyph;
    private final Runnable action;
    /** Chevron direction: +1 or -1, read by {@link Glyph#CHEVRON}. */
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
     * <p>The pager disables "previous" on the first page, and a highlight there would promise a
     * click that is refused. Damping the fill on {@code enabled} also fades the glow out instead
     * of snapping it off.
     */
    @Override
    public void updateHover(int mouseX, int mouseY) {
        hovered = enabled && visible && contains(mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
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
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1F));
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
