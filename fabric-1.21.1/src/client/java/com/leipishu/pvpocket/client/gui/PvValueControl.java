package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.DrawContext;

import java.util.function.IntConsumer;

/**
 * A widget that edits one integer value.
 *
 * <p>Every rule in the catalog is an int on the wire, so the rule panel only ever talks to controls
 * through this interface. Adding a new control type therefore never touches the row or the screen —
 * it just has to render an int and report when the user changed it.</p>
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
    public abstract void render(DrawContext ctx, int mouseX, int mouseY, float delta);
}
