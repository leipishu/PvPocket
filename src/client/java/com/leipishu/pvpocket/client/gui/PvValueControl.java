package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.function.IntConsumer;

/**
 * A widget that edits one integer value.
 *
 * <p>Every rule value is an int, so the rule panel talks to its controls through this interface.
 * Subclasses render the value and call {@link #emit()} when the user changes it.
 */
public abstract class PvValueControl extends PvWidget {

    protected int value;
    protected IntConsumer listener;

    public int value() {
        return value;
    }

    public void setValue(int v) {
        value = v;
    }

    public void onChange(IntConsumer listener) {
        this.listener = listener;
    }

    protected void emit() {
        if (listener != null) {
            listener.accept(value);
        }
    }

    @Override
    public abstract void render(GuiGraphics ctx, int mouseX, int mouseY, float delta);
}
