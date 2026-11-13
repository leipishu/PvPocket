package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The 2D transform stack behind a {@link GuiGraphics}.
 *
 * <p>1.21.11 replaced the 3D {@code PoseStack} used for GUI drawing with JOML's
 * {@code Matrix3x2fStack}, which drops the z argument and renames the push/pop pair. Both shapes
 * are two lines, so they live here instead of at every call site.
 */
public final class PvMatrix {

    private PvMatrix() {
    }

    /** Saves the current transform. */
    public static void push(GuiGraphics ctx) {
//? if >=1.21.11 {
        ctx.pose().pushMatrix();
//?} else {
        ctx.pose().pushPose();
//?}
    }

    /** Restores the transform saved by the matching {@link #push}. */
    public static void pop(GuiGraphics ctx) {
//? if >=1.21.11 {
        ctx.pose().popMatrix();
//?} else {
        ctx.pose().popPose();
//?}
    }

    /** Moves the origin by a screen-space offset. */
    public static void translate(GuiGraphics ctx, float x, float y) {
//? if >=1.21.11 {
        ctx.pose().translate(x, y);
//?} else {
        ctx.pose().translate(x, y, 0F);
//?}
    }

    /** Scales both axes about the current origin. */
    public static void scale(GuiGraphics ctx, float x, float y) {
//? if >=1.21.11 {
        ctx.pose().scale(x, y);
//?} else {
        ctx.pose().scale(x, y, 1F);
//?}
    }
}
