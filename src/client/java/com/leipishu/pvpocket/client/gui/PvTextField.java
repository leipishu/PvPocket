package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * A single-line text field, drawn by hand like everything else in this panel.
 *
 * <p>It provides a caret, insertion, deletion and a horizontal scroll offset.
 *
 * <p>No selection: every field holds a name or a six-character code. Ctrl+C copies the whole field
 * and Ctrl+A is not bound.
 *
 * <p>Focus is owned by the host: this widget draws the caret when {@link #focused} is set and never
 * asks the screen for focus itself.
 */
public class PvTextField extends PvWidget {

    private static final int PAD = 4;
    /** Caret blink, in seconds on and off. */
    private static final float BLINK = 0.5F;

    private String text = "";
    private int caret;
    private int maxLength = 32;
    private String placeholder = "";
    private Runnable onChange;
    private float blinkT;

    public PvTextField(int width) {
        this.width = width;
        this.height = PvTheme.BTN_H;
    }

    public PvTextField placeholder(String value) {
        this.placeholder = value == null ? "" : value;
        return this;
    }

    public PvTextField maxLength(int value) {
        this.maxLength = Math.max(1, value);
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
            caret = text.length();
        }
        return this;
    }

    public PvTextField onChange(Runnable callback) {
        this.onChange = callback;
        return this;
    }

    public String text() {
        return text;
    }

    /** Replaces the contents and puts the caret at the end, where a fresh value is edited. */
    public void text(String value) {
        String next = value == null ? "" : value;
        text = next.length() > maxLength ? next.substring(0, maxLength) : next;
        caret = text.length();
        blinkT = 0F;
        if (onChange != null) {
            onChange.run();
        }
    }

    public void clear() {
        text("");
    }

    private void changed() {
        blinkT = 0F;
        if (onChange != null) {
            onChange.run();
        }
    }

    // ------------------------------------------------------------------ editing

    /** Appends one typed character. The screen filters out control characters first. */
    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused || !enabled || text.length() >= maxLength) {
            return false;
        }
        if (Character.isISOControl(chr)) {
            return false;
        }
        text = text.substring(0, caret) + chr + text.substring(caret);
        caret++;
        changed();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!enabled) {
            return false;
        }
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        Minecraft client = Minecraft.getInstance();
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (caret > 0) {
                    text = text.substring(0, caret - 1) + text.substring(caret);
                    caret--;
                    changed();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (caret < text.length()) {
                    text = text.substring(0, caret) + text.substring(caret + 1);
                    changed();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                caret = Math.max(0, caret - 1);
                blinkT = 0F;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                caret = Math.min(text.length(), caret + 1);
                blinkT = 0F;
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                caret = 0;
                blinkT = 0F;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                caret = text.length();
                blinkT = 0F;
                return true;
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl) {
                    client.keyboardHandler.setClipboard(text);
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    String pasted = client.keyboardHandler.getClipboard();
                    // A pasted code usually arrives with spaces or a trailing newline from chat, so
                    // control characters are stripped here.
                    StringBuilder sb = new StringBuilder(text);
                    int at = caret;
                    for (int i = 0; i < pasted.length() && sb.length() < maxLength; i++) {
                        char c = pasted.charAt(i);
                        if (Character.isISOControl(c)) {
                            continue;
                        }
                        sb.insert(at++, c);
                    }
                    if (at != caret) {
                        text = sb.toString();
                        caret = at;
                        changed();
                    }
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickFocus(d);
        Font tr = Minecraft.getInstance().font;

        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS,
                focused ? PvTheme.ACCENT_DIM : PvTheme.BORDER, PvTheme.SUNKEN);

        boolean empty = text.isEmpty();
        String shown = empty ? placeholder : text;
        int color = empty ? PvTheme.TEXT_MUTED : PvTheme.TEXT;
        int textY = y + (height - 8) / 2 + 1;
        int inner = width - PAD * 2;

        // Scroll so the caret stays inside the box, measured on the real font: a code is all caps
        // and digits, a name is whatever the player typed.
        int offset = 0;
        if (!empty) {
            int caretPx = tr.width(text.substring(0, Math.min(caret, text.length())));
            if (caretPx > inner - 2) {
                offset = caretPx - (inner - 2);
            }
        }
        if (empty) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr, shown, inner), x + PAD, textY, color);
        } else {
            PvDraw.text(ctx, tr, shown, x + PAD - offset, textY, color);
        }

        if (focused) {
            blinkT += d;
            boolean on = (blinkT % (BLINK * 2F)) < BLINK;
            if (on) {
                int caretPx = empty ? 0 : tr.width(text.substring(0, Math.min(caret, text.length())));
                int cx = x + PAD + Math.max(0, Math.min(inner, caretPx - offset));
                PvDraw.roundRect(ctx, cx, y + 4, 1, height - 8, 0, PvTheme.ACCENT);
            }
        }
        drawFocusRing(ctx, PvTheme.RADIUS);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        // Caret placement by click is not implemented: the field is at most 24 characters, and
        // "click to focus, Home/End to move" is two keys against a pixel hunt.
        caret = text.length();
        blinkT = 0F;
        return true;
    }
}
