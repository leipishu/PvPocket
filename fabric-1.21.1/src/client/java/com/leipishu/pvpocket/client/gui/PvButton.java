package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * Flat button with four explicit states: normal, hovered, pressed, disabled.
 *
 * <p>Disabled is a contrast reduction rather than a hide, so a locked option still tells the player
 * that the option exists — the panel is a contract, and a button that vanishes is a contract that
 * appears to change.</p>
 *
 * <p>Press is expressed as a one-pixel inset plus a darker fill, not as a colour swap. On a palette
 * this dark a colour swap between #F5A524 and #D98A12 is nearly invisible; the inset is what the
 * eye actually reads as "the click landed".</p>
 */
public class PvButton extends PvWidget {

    public enum Style {
        /** Filled accent. At most one per panel; it is the action the panel exists for. */
        PRIMARY,
        /** Outlined card. The default for everything else. */
        NORMAL,
        /** Outlined in danger red. Used for reject and cancel; never for a primary action. */
        DANGER,
        /** No surface until hovered. For low-stakes links such as 还原 and 返回. */
        GHOST
    }

    private String label;
    private final Runnable action;
    private Style style;
    private boolean pressed;

    /**
     * Height of a title-row action, matching the 14px chips it sits beside rather than the 20px
     * footer buttons. The title row is 21px of usable height before the divider, so a full-size
     * button there would either cross the rule or push it down on every panel.
     */
    public static final int CHIP_H = 16;

    public PvButton(String label, Style style, Runnable action) {
        this.label = label;
        this.style = style;
        this.action = action;
        this.height = PvTheme.BTN_H;
        this.width = Math.max(44, MinecraftClient.getInstance().textRenderer.getWidth(label) + 22);
    }

    /**
     * A compact action for a title row: text until hovered, sized to its word.
     *
     * <p>Used for panel-level verbs that open something rather than commit something — the preset
     * dialog on both the rule panel and the loadout editor. It is a link, not a button, so it is
     * deliberately quieter than the chips it sits next to.</p>
     */
    public static PvButton chip(String label, Runnable action) {
        PvButton button = new PvButton(label, Style.GHOST, action);
        button.width = Math.max(36, MinecraftClient.getInstance().textRenderer.getWidth(label) + 16);
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
     * Re-labels in place, without touching the size.
     *
     * <p>For the two-step confirmation: the same button asks first and acts second, and rebuilding
     * the page between the two presses would move the widget out from under the pointer mid-click.
     * The width is left alone on purpose — a caller that wants both labels to fit sizes for the
     * longer one when it is built.</p>
     */
    public PvButton label(String text) {
        this.label = text;
        return this;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);
        pressT = PvAnim.damp(pressT, pressed ? 1F : 0F, d, pressed ? 0.04F : 0.08F);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
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
                // Tinted fill, no outline. Same reasoning as the chips: a 1px red outline around a
                // soft red fill is two dark red bars on a 24px button, not an edge.
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
        // Measured, then shrunk, then wrapped, then cut — in that order. A button is the one widget
        // whose size callers set by hand, and in two of the three cases (a fixed 44px default, a row
        // of equal buttons) the label can be wider than the box. Shrinking is what keeps the word:
        // the same row of buttons used to read "Copy res… / Acc… / Rej…" in English, where the
        // Chinese labels had fitted at full size. A 20px button then spends a second line rather
        // than an ellipsis — "Accept & / start" at 55px — and only a label that needs more than two
        // lines at the floor is truncated. A 16px title-row chip has no second line to spend.
        PvDraw.textWrapCenter(ctx, tr, label, bx + bw / 2, by, bh, bw - 4,
                height >= PvTheme.BTN_H ? 2 : 1, textColor);
        // If even that was not enough, the rest of the word is one hover away.
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
        MinecraftClient client = MinecraftClient.getInstance();
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
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
