package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.hud.PossessionHud;
import com.leipishu.pvpocket.network.PossessPayload;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * First-person hand of the body a spectator is riding in.
 *
 * <p>Vanilla draws nothing here: {@code GameRenderer.renderHand} bails out on
 * {@code getCurrentGameMode() != SPECTATOR}, and even without that guard it would render
 * {@code client.player} — the empty-handed ghost — rather than the fighter being watched. So the
 * hand is the one part of the possessed view that has to be drawn by hand.</p>
 *
 * <h2>Coordinate space</h2>
 *
 * <p>This is the part that is easy to get wrong, and getting it wrong looks like "the off hand is
 * drawn in the main hand" rather than like a matrix bug. {@code GameRenderer.renderWorld} builds
 * {@code matrix4f = new Matrix4f().rotation(camera.getRotation().conjugate())} and hands it to both
 * {@code WorldRenderer.render} and {@code renderHand}. The world renderer pushes it onto the model
 * view stack and pops it again before returning; {@code renderHand} then does</p>
 *
 * <pre>{@code
 * matrixStack.multiplyPositionMatrix(matrix4f.invert(new Matrix4f()));
 * modelView.pushMatrix().mul(matrix4f);
 * }</pre>
 *
 * <p>The stack therefore holds the <em>inverse</em> view rotation — camera-to-world — so that a
 * {@code translate(0.56, -0.52, -0.72)} means "half a block right of the eye" instead of "half a
 * block along world +X". Multiplying the two back together cancels out, which is why the hand ends
 * up fixed to the screen while the world rotates around it.</p>
 *
 * <p>{@link WorldRenderEvents#LAST} fires at the tail of {@code WorldRenderer.render}, after the
 * model view pop but while the stack still holds the view rotation (the pop happens a few
 * instructions later, around {@code renderChunkDebugInfo}). So the stack is already in the state
 * {@code renderHand} expects and only the position-matrix inverse has to be supplied.</p>
 *
 * <h2>Depth</h2>
 *
 * <p>Vanilla clears the depth buffer before drawing the hand so that a wall 0.6 blocks from the eye
 * cannot slice it. The same clear is safe at {@code LAST}: the world is fully drawn, and the only
 * passes left are the block outline debug overlay and the GUI, neither of which reads depth.
 * Disabling the depth test instead would break face sorting <em>within</em> the item model.</p>
 */
@Environment(EnvType.CLIENT)
public final class PossessedHandRenderer {

    /**
     * Vanilla animates this from 1 to 0 while an item is being swapped into the hand. A remote body
     * is never observed mid-swap, so it is always fully equipped.
     */
    private static final float EQUIP_PROGRESS = 0.0F;

    private PossessedHandRenderer() {
    }

    /** One line per possession, not per frame: enough to tell "never ran" from "ran and drew". */
    private static int lastLoggedTarget = -1;

    public static void register() {
        WorldRenderEvents.LAST.register(PossessedHandRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        PossessPayload payload = PossessionHud.state();
        if (payload == null || payload.targetId() < 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.options.hudHidden) {
            return;
        }
        // Possession is a first-person experience; in third person the camera sits behind the body
        // and a hand floating in front of it would be a bug rather than a feature.
        if (client.options.getPerspective() != Perspective.FIRST_PERSON) {
            return;
        }
        if (!(client.world.getEntityById(payload.targetId()) instanceof AbstractClientPlayerEntity target)) {
            return;
        }
        // Vanilla hides both hands while a spyglass is up; the screen is a scope, not a view.
        if (target.isUsingSpyglass()) {
            return;
        }

        float tickDelta = context.tickCounter().getTickDelta(true);
        // The view rotation the world was just rendered with, rebuilt from the same camera so this
        // stays correct even if the stack were left dirty by another renderer.
        Matrix4f viewRotation = new Matrix4f()
                .rotation(context.camera().getRotation().conjugate(new Quaternionf()));

        MatrixStack matrices = new MatrixStack();
        matrices.push();
        matrices.multiplyPositionMatrix(viewRotation.invert(new Matrix4f()));

        int light = client.getEntityRenderDispatcher().getLight(target, tickDelta);
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();

        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        context.lightmapTextureManager().enable();

        ItemStack main = target.getMainHandStack();
        ItemStack off = target.getOffHandStack();
        drawHand(client, matrices, consumers, target, tickDelta, Hand.MAIN_HAND, main, light);
        drawHand(client, matrices, consumers, target, tickDelta, Hand.OFF_HAND, off, light);
        consumers.draw();

        context.lightmapTextureManager().disable();
        matrices.pop();

        if (lastLoggedTarget != payload.targetId()) {
            lastLoggedTarget = payload.targetId();
            PvPocket.LOGGER.info("Possessed hand: target={} main={} off={} light={}",
                    target.getName().getString(), main.getItem(), off.getItem(), light);
        }
    }

    /**
     * Mirrors {@code HeldItemRenderer.renderFirstPersonItem} for the two cases a spectator can
     * actually observe: an empty hand (draw the arm) and a held item (equip offset plus swing arc).
     *
     * <p>The use-action branches — eating, drinking, drawing a bow, blocking with a shield — are
     * deliberately not reproduced. They are driven by {@code getActiveHand()} and
     * {@code getItemUseTimeLeft()}, which vanilla does not synchronise for other players; guessing
     * would animate the wrong hand, which is exactly the "状态不太对" this class is fixing.</p>
     */
    private static void drawHand(MinecraftClient client, MatrixStack matrices,
                                 VertexConsumerProvider.Immediate consumers,
                                 AbstractClientPlayerEntity player, float tickDelta,
                                 Hand hand, ItemStack stack, int light) {
        boolean mainHand = hand == Hand.MAIN_HAND;
        Arm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        // Vanilla passes the swing progress to the main hand only; the off hand is drawn flat.
        float swing = mainHand ? player.getHandSwingProgress(tickDelta) : 0.0F;

        matrices.push();
        if (stack.isEmpty()) {
            // Vanilla never draws an empty off hand — only the main arm is visible.
            if (mainHand && !player.isInvisible()) {
                renderArmHoldingItem(client, matrices, consumers, light, EQUIP_PROGRESS, swing, arm, player);
            }
        } else {
            boolean right = arm == Arm.RIGHT;
            int side = right ? 1 : -1;
            // The little forward lunge the item makes as the swing starts.
            float lunge = -0.4F * MathHelper.sin(MathHelper.sqrt(swing) * (float) Math.PI);
            float rise = 0.2F * MathHelper.sin(MathHelper.sqrt(swing) * (float) (Math.PI * 2));
            float drop = -0.2F * MathHelper.sin(swing * (float) Math.PI);
            matrices.translate(side * lunge, rise, drop);
            applyEquipOffset(matrices, arm, EQUIP_PROGRESS);
            applySwingOffset(matrices, arm, swing);
            renderItem(client, matrices, consumers, player, stack, arm, light);
        }
        matrices.pop();
    }

    /** {@code HeldItemRenderer.applyEquipOffset}. */
    private static void applyEquipOffset(MatrixStack matrices, Arm arm, float equipProgress) {
        int side = arm == Arm.RIGHT ? 1 : -1;
        matrices.translate(side * 0.56F, -0.52F + equipProgress * -0.6F, -0.72F);
    }

    /** {@code HeldItemRenderer.applySwingOffset}. */
    private static void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress) {
        int side = arm == Arm.RIGHT ? 1 : -1;
        float arc = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * (45.0F + arc * -20.0F)));
        float lift = MathHelper.sin(MathHelper.sqrt(swingProgress) * (float) Math.PI);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * lift * -20.0F));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(lift * -80.0F));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * -45.0F));
    }

    /** {@code HeldItemRenderer.renderArmHoldingItem}, with the arm owner swapped for the target. */
    private static void renderArmHoldingItem(MinecraftClient client, MatrixStack matrices,
                                             VertexConsumerProvider.Immediate consumers, int light,
                                             float equipProgress, float swingProgress, Arm arm,
                                             AbstractClientPlayerEntity player) {
        boolean right = arm != Arm.LEFT;
        float side = right ? 1.0F : -1.0F;
        float root = MathHelper.sqrt(swingProgress);
        float back = -0.3F * MathHelper.sin(root * (float) Math.PI);
        float up = 0.4F * MathHelper.sin(root * (float) (Math.PI * 2));
        float down = -0.4F * MathHelper.sin(swingProgress * (float) Math.PI);
        matrices.translate(side * (back + 0.64000005F),
                up + -0.6F + equipProgress * -0.6F,
                down + -0.71999997F);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * 45.0F));
        float twist = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        float lift = MathHelper.sin(root * (float) Math.PI);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * lift * 70.0F));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * twist * -20.0F));
        matrices.translate(side * -1.0F, 3.6F, 3.5F);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * 120.0F));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(200.0F));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * -135.0F));
        matrices.translate(side * 5.6F, 0.0F, 0.0F);

        EntityRenderer<?> renderer = client.getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof PlayerEntityRenderer playerRenderer)) {
            return;
        }
        if (right) {
            playerRenderer.renderRightArm(matrices, consumers, light, player);
        } else {
            playerRenderer.renderLeftArm(matrices, consumers, light, player);
        }
    }

    private static void renderItem(MinecraftClient client, MatrixStack matrices,
                                   VertexConsumerProvider.Immediate consumers,
                                   AbstractClientPlayerEntity player, ItemStack stack, Arm arm,
                                   int light) {
        boolean right = arm == Arm.RIGHT;
        ModelTransformationMode mode = right
                ? ModelTransformationMode.FIRST_PERSON_RIGHT_HAND
                : ModelTransformationMode.FIRST_PERSON_LEFT_HAND;
        client.getItemRenderer().renderItem(player, stack, mode, !right, matrices, consumers,
                player.getWorld(), light, OverlayTexture.DEFAULT_UV,
                player.getId() + mode.ordinal());
    }
}
