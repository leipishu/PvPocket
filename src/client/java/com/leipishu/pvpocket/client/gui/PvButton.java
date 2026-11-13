package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Flat button with four explicit states: normal, hovered, pressed and disabled.
 *
 * <p>Disabled is a contrast reduction rather than a hide, so a locked option still tells the player
 * that the option exists.
 *
 * <p>Press is a one-pixel inset plus a darker fill rather than a colour swap, which is hard to see
 * on this palette.
 */
public class PvButton extends PvWidget {

    public enum Style {
        /** Filled accent. At most one per panel; it is the action the panel exists for. */
        PRIMARY,
        /** Outlined card. The default for everything else. */
        NORMAL,
        /** Outlined in danger red. Used for reject and cancel; never for a primary action. */
        DANGER,
        /** No surface until hovered. For low-stakes links such as restore and back. */
        GHOST
    }

    private String label;
    private final Runnable action;
    private Style style;
    private boolean pressed;

    /**
     * Height of a title-row action, matching the 14px chips beside it rather than the 20px footer
     * buttons.
     *
     * <p>The title row has 21px of usable height before the divider, so a full-size button there
     * would cross the rule or push it down.
     */
    public static final int CHIP_H = 16;

    public PvButton(String label, Style style, Runnable action) {
        this.label = label;
        this.style = style;
        this.action = action;
        this.height = PvTheme.BTN_H;
        this.width = Math.max(44, Minecraft.getInstance().font.width(label) + 22);
    }

    /**
     * Returns a compact action for a title row: text until hovered, sized to its word.
     *
     * <p>For panel-level verbs that open a dialog, such as the preset dialog. The result is quieter
     * than the chips it sits beside.
     */
    public static PvButton chip(String label, Runnable action) {
        PvButton button = new PvButton(label, Style.GHOST, action);
        button.width = Math.max(36, Minecraft.getInstance().font.width(label) + 16);
        button.height = CHIP_H;
        return button;
    }

    public PvButton style(Style s) {
        this.style = s;
        return this;
    }

    public PvButton size(int w, int h) {
        this.width = w;
        this.height = h;
        return this;
    }

    /**
     * Re-labels in place without touching the size.
     *
     * <p>For the two-step confirmation, where the same button asks first and acts second. Size the
     * button for the longer of the two labels when it is built.
     */
    public PvButton label(String text) {
        this.label = text;
        return this;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);
        pressT = PvAnim.damp(pressT, pressed ? 1F : 0F, d, pressed ? 0.04F : 0.08F);

        Font tr = Minecraft.getInstance().font;
        int inset = Math.round(pressT);
        int bx = x + inset;
        int by = y + inset;
        int bw = width - inset * 2;
        int bh = height - inset * 2;

        int fill;
        int border;
        int textColor;

        switch (style) {
            case PRIMARY -> {
                int base = PvTheme.mix(PvTheme.ACCENT, PvTheme.ACCENT_HOVER, hoverT);
                fill = PvTheme.mix(base, PvTheme.ACCENT_PRESSED, pressT);
                border = 0;
                textColor = PvTheme.ON_ACCENT;
                if (!enabled) {
                    fill = PvTheme.mix(PvTheme.ACCENT_DIM, PvTheme.PANEL, 0.45F);
                    textColor = PvTheme.TEXT_MUTED;
                }
            }
            case DANGER -> {
                // Tinted fill, no outline: a 1px red outline around a soft red fill reads as two
                // dark red bars on a 24px button rather than an edge.
                fill = PvTheme.alpha(PvTheme.DANGER, 0.10F + 0.22F * hoverT + 0.10F * pressT);
                border = 0;
                textColor = PvTheme.mix(PvTheme.DANGER, PvTheme.DANGER_HOVER, hoverT);
                if (!enabled) {
                    fill = PvTheme.SUNKEN;
                    border = PvTheme.DIVIDER;
                    textColor = PvTheme.TEXT_MUTED;
                }
            }
            case GHOST -> {
                fill = PvTheme.alpha(PvTheme.CARD_HOVER, hoverT);
                border = 0;
                textColor = PvTheme.mix(PvTheme.TEXT_MUTED, PvTheme.TEXT, hoverT);
                if (!enabled) {
                    textColor = PvTheme.TEXT_MUTED;
                }
            }
            default -> {
                fill = PvTheme.mix(PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, hoverT),
                        PvTheme.CARD_ACTIVE, pressT);
                border = PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, hoverT);
                textColor = PvTheme.TEXT;
                if (!enabled) {
                    fill = PvTheme.SUNKEN;
                    border = PvTheme.DIVIDER;
                    textColor = PvTheme.TEXT_MUTED;
                }
            }
        }

        if (border == 0) {
            PvDraw.roundRect(ctx, bx, by, bw, bh, PvTheme.RADIUS, fill);
        } else {
            PvDraw.roundPanel(ctx, bx, by, bw, bh, PvTheme.RADIUS, border, fill);
        }
        drawFocusRing(ctx, PvTheme.RADIUS);
        // Shrink, then wrap, then cut: a hand-sized button can be narrower than its label, a 20px
        // button spends a second line, and a 16px chip has no second line to spend.
        PvDraw.textWrapCenter(ctx, tr, label, bx + bw / 2, by, bh, bw - 4,
                height >= PvTheme.BTN_H ? 2 : 1, textColor);
        // If that still cuts the label, the full text is available as a hover tooltip.
        overflowTip(label, PvDraw.willCut(tr, label, bw - 4, height >= PvTheme.BTN_H ? 2 : 1));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        pressed = true;
        click();
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!pressed) {
            return false;
        }
        pressed = false;
        if (button == 0 && contains(mx, my) && action != null) {
            action.run();
        }
        return true;
    }

    private void click() {
        Minecraft client = Minecraft.getInstance();
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Keyboard activation path, used by Enter/Space on a focused button. */
    public void activate() {
        click();
        if (action != null) {
            action.run();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public String label() {
        return label;
    }
}
