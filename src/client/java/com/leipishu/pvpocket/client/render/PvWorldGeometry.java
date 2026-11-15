package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType;
//?} else {
import net.minecraft.client.renderer.RenderType;
//?}
//? if >=26.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}

/**
 * Hands immediate-mode geometry to one world render pass.
 *
 * <p>26.2 removed {@code MultiBufferSource} and the client-wide {@code RenderBuffers} together: a
 * pass no longer draws into a buffer that is flushed afterwards, it registers a callback with the
 * frame's submit queue and the geometry is built when the frame is assembled. The callback receives
 * the resolved {@code PoseStack.Pose}, which is the same matrix {@code getBuffer()} used to be
 * paired with, so the drawing code itself does not change.
 *
 * <p>The submit queue arrived in 1.21.11, but only 26.2 dropped the buffer source, so both branches
 * exist: before 26.2 the frame carries the buffer source, from 26.2 the queue is the only way in.
 */
public final class PvWorldGeometry {

    private PvWorldGeometry() {
    }

    /** One batch of vertices. {@code matrix} already carries the pass's own transform. */
    @FunctionalInterface
    public interface Batch {
        void draw(VertexConsumer buffer, Matrix4f matrix);
    }

    /**
     * Draws {@code batch} in the pass described by {@code frame}.
     *
     * <p>Returns without drawing when the frame carries no sink: the queue is null on loaders whose
     * render event does not expose one, and a frame without a buffer source cannot be on this
     * branch at all.
     */
    public static void draw(PvClientPlatform.WorldFrame frame, PoseStack poses, RenderType type,
                            Batch batch) {
//? if >=26.2 {
        SubmitNodeCollector collector = (SubmitNodeCollector) frame.nodes();
        if (collector == null) {
            return;
        }
        // The queue copies the pose, so popping the stack after this call is safe.
        collector.submitCustomGeometry(poses, type, (pose, buffer) -> batch.draw(buffer, pose.pose()));
//?} else {
        MultiBufferSource consumers = frame.buffers();
        if (consumers == null) {
            return;
        }
        batch.draw(consumers.getBuffer(type), poses.last().pose());
//?}
    }
}
