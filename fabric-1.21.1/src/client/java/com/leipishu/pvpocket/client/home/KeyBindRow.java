package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * One key binding, with the key cap on the right and capture on click.
 *
 * <p><b>It writes to vanilla's binding, not to a copy.</b> {@code KeyBinding#setBoundKey} plus
 * {@code Options#write} is the same path the vanilla controls screen uses, so the mod's keys appear
 * there, survive a restart, and there is exactly one place a binding can be wrong. A private key map
 * would need its own conflict resolution, its own persistence and its own way of disagreeing with the
 * screen the player actually thinks of as "the controls".</p>
 *
 * <p>Capture is a static hand-off rather than a focus state: the screen asks
 * {@link #isCapturing()} before it handles Escape or Tab, because otherwise pressing Esc to cancel a
 * capture would close the whole panel — and Esc is the one key a player is most likely to press while
 * a row says "press a key".</p>
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
    private final KeyBinding binding;
    private float capT;

    public KeyBindRow(String label, KeyBinding binding) {
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
        KeyBinding conflict = conflicting(keyCode, scanCode);
        if (conflict != null && conflict != binding) {
            // Refused rather than stolen: silently taking the jump key would leave a player unable to
            // jump with no clue which panel did it.
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(PvLang.t("pvpocket.msg.home.key_conflict",
                        conflict.getBoundKeyLocalizedText()), true);
            }
            return;
        }
        binding.setBoundKey(InputUtil.fromKeyCode(keyCode, scanCode));
        // Both halves matter: the index is what makes the new key fire this tick, the write is what
        // makes it survive the session.
        KeyBinding.updateKeysByCode();
        MinecraftClient.getInstance().options.write();
    }

    /**
     * Finds another binding already holding this key.
     *
     * <p>Compared with {@code matchesKey} rather than by reading the bound key, because
     * {@code KeyBinding} exposes no getter for it — only {@code setBoundKey} and the localised
     * forms. {@code matchesKey} is the same comparison the game itself uses to dispatch input, so
     * "conflict" here means exactly what "two actions on one key" means to vanilla. A binding held
     * by a mouse button never matches a keyboard code, which is the right answer: the two cannot
     * collide.</p>
     */
    private KeyBinding conflicting(int keyCode, int scanCode) {
        for (KeyBinding other : MinecraftClient.getInstance().options.allKeys) {
            if (other != binding && !other.isUnbound() && other.matchesKey(keyCode, scanCode)) {
                return other;
            }
        }
        return null;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
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
                : binding.getBoundKeyLocalizedText().getString();
        int color = capturing == this ? PvTheme.ACCENT
                : hot ? PvTheme.TEXT : PvTheme.TEXT_DIM;
        // The key cap is a fixed 92px box, and a bound key's name is whatever vanilla calls it
        // ("Left Shift", "Mouse Button 4"), so it shrinks before it is cut.
        PvDraw.textFitCenter(ctx, tr, text, capX + CAP_W / 2, capY + (CAP_H - 8) / 2 + 1,
                CAP_W - 6, color);

        // The label column is whatever the cap leaves, which on a 174px row is 74px: enough for
        // "Cycle spectator / camera" over two lines, not enough for 91px on one. The row is 24px
        // tall, so the second line costs nothing.
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
        // A cut label outranks "click to rebind": the tip is discoverable by trying, the end of a
        // truncated action name is not.
        return overflow() != null ? overflow()
                : PvLang.str("pvpocket.gui.home.settings.key_tip");
    }
}
