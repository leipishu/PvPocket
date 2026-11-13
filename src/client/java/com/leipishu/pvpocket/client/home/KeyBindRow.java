package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * One key binding row: label on the left, key cap on the right, capture on click.
 *
 * <p>Writes through to the vanilla {@code KeyMapping} and saves the options file, so the binding
 * persists and appears on the vanilla controls screen.
 *
 * <p>Call {@link #isCapturing()} before handling Escape or Tab: an open capture consumes every key.
 */
public class KeyBindRow extends PvWidget {

    private static final int CAP_W = 92;
    private static final int CAP_H = 18;
    /** Keys that exist to be held with another key. Binding one of them alone is never intended. */
    private static final int[] MODIFIERS = {
            GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
            GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT,
            GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER,
            GLFW.GLFW_KEY_CAPS_LOCK,
    };

    /** The row currently waiting for a key, or null. One at a time by construction. */
    private static KeyBindRow capturing;

    private final String label;
    private final KeyMapping binding;
    private float capT;

    public KeyBindRow(String label, KeyMapping binding) {
        this.label = label;
        this.binding = binding;
        this.height = 24;
    }

    public static boolean isCapturing() {
        return capturing != null;
    }

    /**
     * Feeds a key to the row that is waiting for one.
     *
     * @return true when the key was consumed, which is every key while a capture is open
     */
    public static boolean capture(int keyCode, int scanCode) {
        KeyBindRow row = capturing;
        if (row == null) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            capturing = null;
            return true;
        }
        for (int modifier : MODIFIERS) {
            if (keyCode == modifier) {
                return true;
            }
        }
        row.apply(keyCode, scanCode);
        capturing = null;
        return true;
    }

    /** Drops a pending capture, for when the screen closes underneath it. */
    public static void cancelCapture() {
        capturing = null;
    }

    private void apply(int keyCode, int scanCode) {
        KeyMapping conflict = conflicting(keyCode, scanCode);
        if (conflict != null && conflict != binding) {
            // A key held by another binding is refused and reported in the action bar.
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.displayClientMessage(PvLang.t("pvpocket.msg.home.key_conflict",
                        conflict.getTranslatedKeyMessage()), true);
            }
            return;
        }
        binding.setKey(InputConstants.getKey(keyCode, scanCode));
        // resetMapping makes the new key fire this tick; options.save persists it across sessions.
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    /**
     * Returns another binding that already holds this key, or null.
     *
     * <p>The comparison uses {@code matchesKey}, which is how the game dispatches input, since
     * {@code KeyMapping} exposes no getter for the bound key. A mouse button never matches a
     * keyboard code.
     */
    private KeyMapping conflicting(int keyCode, int scanCode) {
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
            if (other != binding && !other.isUnbound() && other.matches(keyCode, scanCode)) {
                return other;
            }
        }
        return null;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        Font tr = Minecraft.getInstance().font;
        capT = com.leipishu.pvpocket.client.gui.PvAnim.damp(capT,
                capturing == this ? 1F : 0F, dt(delta), 0.08F);

        int capX = x + width - CAP_W;
        int capY = y + (height - CAP_H) / 2;
        boolean hot = contains(mouseX, mouseY);

        int fill = capturing == this
                ? PvTheme.ACCENT_SOFT
                : hot ? PvTheme.CARD_HOVER : PvTheme.CARD;
        PvDraw.roundPanel(ctx, capX, capY, CAP_W, CAP_H, PvTheme.RADIUS,
                capturing == this ? PvTheme.ACCENT : PvTheme.BORDER, fill);

        String text = capturing == this
                ? PvLang.str("pvpocket.gui.home.settings.press_key")
                : binding.getTranslatedKeyMessage().getString();
        int color = capturing == this ? PvTheme.ACCENT
                : hot ? PvTheme.TEXT : PvTheme.TEXT_DIM;
        // The cap is 92px wide, so a long key name shrinks before it is cut.
        PvDraw.textFitCenter(ctx, tr, text, capX + CAP_W / 2, capY + (CAP_H - 8) / 2 + 1,
                CAP_W - 6, color);

        // The label takes what the cap leaves, 74px on a 174px row, and wraps to two lines.
        PvDraw.textWrapLeft(ctx, tr, label, x, y, height, capX - 8 - x, 2, PvTheme.TEXT);
        overflowTip(label, PvDraw.willCut(tr, label, capX - 8 - x, 2));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0 || !contains(mx, my)) {
            return false;
        }
        capturing = capturing == this ? null : this;
        return true;
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }
        // A cut label takes priority over the rebind hint.
        return overflow() != null ? overflow()
                : PvLang.str("pvpocket.gui.home.settings.key_tip");
    }
}
