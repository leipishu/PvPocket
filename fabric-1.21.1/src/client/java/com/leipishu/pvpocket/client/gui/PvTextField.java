package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * A single-line text field, drawn by hand like everything else in this panel.
 *
 * <p>Vanilla's {@code TextFieldWidget} was not reused because it brings its own colours, its own
 * border and its own idea of padding, and matching it to this palette costs more than writing the
 * four things a field actually needs: a caret, insertion, deletion and a horizontal scroll offset.</p>
 *
 * <p><b>No selection.</b> Every field in this panel holds a name or a six-character code, and a
 * selection model would add drag-select, shift-arrows and a highlight pass to every render for a
 * feature nobody will reach for in a 24-character field. Ctrl+C therefore copies the whole field and
 * Ctrl+A is not bound — both are stated in the class rather than left to be discovered.</p>
 *
 * <p>Focus is owned by whoever hosts the field: this widget only draws the caret when
 * {@link #focused} is set, and never asks the screen for focus itself.</p>
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

    /** Replaces the contents and puts the caret at the end, which is where a fresh value is edited. */
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

    /** Appends one typed character. Control characters never arrive here; the screen filters them. */
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
                    // A pasted code usually arrives with spaces or a trailing newline from a chat
                    // message; stripping control characters here is what makes "copy from chat"
                    // work without the player editing it by hand.
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

        // Scroll so the caret stays inside the box. Measured on the real font rather than estimated:
        // a code is all caps and digits, a name is whatever the player typed, and the two widths are
        // far enough apart that a fixed character budget clips one of them.
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
        // Caret placement by click is deliberately not implemented: the field is at most 24
        // characters, and "click to focus, Home/End to move" is two keys against a pixel hunt.
        caret = text.length();
        blinkT = 0F;
        return true;
    }
}
