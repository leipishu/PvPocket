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
 */
public class PvButton extends PvWidget {

    public enum Style {
        /** Filled accent. At most one per panel; it is the action the panel exists for. */
        PRIMARY,
        /** Outlined surface. The default for everything else. */
        NORMAL,
        /** Outlined in danger red, used for reject/cancel. */
        DANGER,
        /** No surface until hovered. For low-stakes links such as the version chip. */
        GHOST
    }

    private final String label;
    private final Runnable action;
    private Style style;
    private boolean pressed;

    public PvButton(String label, Style style, Runnable action) {
        this.label = label;
        this.style = style;
        this.action = action;
        this.height = PvTheme.BTN_H;
        this.width = Math.max(40, MinecraftClient.getInstance().textRenderer.getWidth(label) + 20);
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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        boolean hot = hovered && enabled;
        int border;
        int fill;
        int textColor;

        switch (style) {
            case PRIMARY -> {
                border = enabled ? PvTheme.ACCENT : PvTheme.ACCENT_DIM;
                fill = !enabled ? PvTheme.ACCENT_DIM
                        : pressed ? PvTheme.mix(PvTheme.ACCENT, 0xFF000000, 0.18F)
                        : hot ? PvTheme.mix(PvTheme.ACCENT, 0xFFFFFFFF, 0.12F)
                        : PvTheme.ACCENT;
                textColor = enabled ? PvTheme.ON_ACCENT : PvTheme.TEXT_MUTED;
            }
            case DANGER -> {
                border = enabled ? PvTheme.DANGER : PvTheme.mix(PvTheme.DANGER, PvTheme.SURFACE, 0.7F);
                fill = !enabled ? PvTheme.SURFACE
                        : pressed ? PvTheme.mix(PvTheme.SURFACE, PvTheme.DANGER, 0.35F)
                        : hot ? PvTheme.mix(PvTheme.SURFACE, PvTheme.DANGER, 0.20F)
                        : PvTheme.SURFACE;
                textColor = enabled ? (hot ? PvTheme.DANGER : PvTheme.TEXT) : PvTheme.TEXT_MUTED;
            }
            case GHOST -> {
                border = hot ? PvTheme.SURFACE_HOVER : 0;
                fill = hot ? PvTheme.SURFACE : 0;
                textColor = enabled ? PvTheme.TEXT_DIM : PvTheme.TEXT_MUTED;
            }
            default -> {
                border = enabled ? (hot ? PvTheme.ACCENT_DIM : PvTheme.PANEL_BORDER) : PvTheme.DIVIDER;
                fill = !enabled ? PvTheme.SURFACE_SUNKEN
                        : pressed ? PvTheme.SURFACE_ACTIVE
                        : hot ? PvTheme.SURFACE_HOVER
                        : PvTheme.SURFACE;
                textColor = enabled ? PvTheme.TEXT : PvTheme.TEXT_MUTED;
            }
        }

        if (focused && enabled) {
            border = PvTheme.ACCENT;
        }
        if (border == 0) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS, fill);
        } else {
            PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, border, fill);
        }
        PvDraw.textCenter(ctx, tr, label, x + width / 2, y + (height - 8) / 2 + 1, textColor);
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
