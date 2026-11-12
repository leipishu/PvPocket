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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;
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
 * {@code matrix4f = new Matrix4f().rotation(camera.rotation().conjugate())} and hands it to both
 * {@code WorldRenderer.render} and {@code renderHand}. The world renderer pushes it onto the model
 * view stack and pops it again before returning; {@code renderHand} then does</p>
 *
 * <pre>{@code
 * matrixStack.mulPose(matrix4f.invert(new Matrix4f()));
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
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.options.hideGui) {
            return;
        }
        // Possession is a first-person experience; in third person the camera sits behind the body
        // and a hand floating in front of it would be a bug rather than a feature.
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) {
            return;
        }
        if (!(client.level.getEntity(payload.targetId()) instanceof AbstractClientPlayer target)) {
            return;
        }
        // Vanilla hides both hands while a spyglass is up; the screen is a scope, not a view.
        if (target.isScoping()) {
            return;
        }

        float tickDelta = context.tickCounter().getGameTimeDeltaPartialTick(true);
        // The view rotation the world was just rendered with, rebuilt from the same camera so this
        // stays correct even if the stack were left dirty by another renderer.
        Matrix4f viewRotation = new Matrix4f()
                .rotation(context.camera().rotation().conjugate(new Quaternionf()));

        PoseStack matrices = new PoseStack();
        matrices.pushPose();
        matrices.mulPose(viewRotation.invert(new Matrix4f()));

        int light = client.getEntityRenderDispatcher().getPackedLightCoords(target, tickDelta);
        MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();

        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        context.lightmapTextureManager().turnOnLightLayer();

        ItemStack main = target.getMainHandItem();
        ItemStack off = target.getOffhandItem();
        drawHand(client, matrices, consumers, target, tickDelta, InteractionHand.MAIN_HAND, main, light);
        drawHand(client, matrices, consumers, target, tickDelta, InteractionHand.OFF_HAND, off, light);
        consumers.endBatch();

        context.lightmapTextureManager().turnOffLightLayer();
        matrices.popPose();

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
     * deliberately not reproduced. They are driven by {@code getUsedItemHand()} and
     * {@code getUseItemRemainingTicks()}, which vanilla does not synchronise for other players; guessing
     * would animate the wrong hand, which is exactly the "状态不太对" this class is fixing.</p>
     */
    private static void drawHand(Minecraft client, PoseStack matrices,
                                 MultiBufferSource.BufferSource consumers,
                                 AbstractClientPlayer player, float tickDelta,
                                 InteractionHand hand, ItemStack stack, int light) {
        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        // Vanilla passes the swing progress to the main hand only; the off hand is drawn flat.
        float swing = mainHand ? player.getAttackAnim(tickDelta) : 0.0F;

        matrices.pushPose();
        if (stack.isEmpty()) {
            // Vanilla never draws an empty off hand — only the main arm is visible.
            if (mainHand && !player.isInvisible()) {
                renderArmHoldingItem(client, matrices, consumers, light, EQUIP_PROGRESS, swing, arm, player);
            }
        } else {
            boolean right = arm == HumanoidArm.RIGHT;
            int side = right ? 1 : -1;
            // The little forward lunge the item makes as the swing starts.
            float lunge = -0.4F * Mth.sin(Mth.sqrt(swing) * (float) Math.PI);
            float rise = 0.2F * Mth.sin(Mth.sqrt(swing) * (float) (Math.PI * 2));
            float drop = -0.2F * Mth.sin(swing * (float) Math.PI);
            matrices.translate(side * lunge, rise, drop);
            applyEquipOffset(matrices, arm, EQUIP_PROGRESS);
            applySwingOffset(matrices, arm, swing);
            renderItem(client, matrices, consumers, player, stack, arm, light);
        }
        matrices.popPose();
    }

    /** {@code HeldItemRenderer.applyEquipOffset}. */
    private static void applyEquipOffset(PoseStack matrices, HumanoidArm arm, float equipProgress) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        matrices.translate(side * 0.56F, -0.52F + equipProgress * -0.6F, -0.72F);
    }

    /** {@code HeldItemRenderer.applySwingOffset}. */
    private static void applySwingOffset(PoseStack matrices, HumanoidArm arm, float swingProgress) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float arc = Mth.sin(swingProgress * swingProgress * (float) Math.PI);
        matrices.mulPose(Axis.YP.rotationDegrees(side * (45.0F + arc * -20.0F)));
        float lift = Mth.sin(Mth.sqrt(swingProgress) * (float) Math.PI);
        matrices.mulPose(Axis.ZP.rotationDegrees(side * lift * -20.0F));
        matrices.mulPose(Axis.XP.rotationDegrees(lift * -80.0F));
        matrices.mulPose(Axis.YP.rotationDegrees(side * -45.0F));
    }

    /** {@code HeldItemRenderer.renderArmHoldingItem}, with the arm owner swapped for the target. */
    private static void renderArmHoldingItem(Minecraft client, PoseStack matrices,
                                             MultiBufferSource.BufferSource consumers, int light,
                                             float equipProgress, float swingProgress, HumanoidArm arm,
                                             AbstractClientPlayer player) {
        boolean right = arm != HumanoidArm.LEFT;
        float side = right ? 1.0F : -1.0F;
        float root = Mth.sqrt(swingProgress);
        float back = -0.3F * Mth.sin(root * (float) Math.PI);
        float up = 0.4F * Mth.sin(root * (float) (Math.PI * 2));
        float down = -0.4F * Mth.sin(swingProgress * (float) Math.PI);
        matrices.translate(side * (back + 0.64000005F),
                up + -0.6F + equipProgress * -0.6F,
                down + -0.71999997F);
        matrices.mulPose(Axis.YP.rotationDegrees(side * 45.0F));
        float twist = Mth.sin(swingProgress * swingProgress * (float) Math.PI);
        float lift = Mth.sin(root * (float) Math.PI);
        matrices.mulPose(Axis.YP.rotationDegrees(side * lift * 70.0F));
        matrices.mulPose(Axis.ZP.rotationDegrees(side * twist * -20.0F));
        matrices.translate(side * -1.0F, 3.6F, 3.5F);
        matrices.mulPose(Axis.ZP.rotationDegrees(side * 120.0F));
        matrices.mulPose(Axis.XP.rotationDegrees(200.0F));
        matrices.mulPose(Axis.YP.rotationDegrees(side * -135.0F));
        matrices.translate(side * 5.6F, 0.0F, 0.0F);

        EntityRenderer<?> renderer = client.getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof PlayerRenderer playerRenderer)) {
            return;
        }
        if (right) {
            playerRenderer.renderRightHand(matrices, consumers, light, player);
        } else {
            playerRenderer.renderLeftHand(matrices, consumers, light, player);
        }
    }

    private static void renderItem(Minecraft client, PoseStack matrices,
                                   MultiBufferSource.BufferSource consumers,
                                   AbstractClientPlayer player, ItemStack stack, HumanoidArm arm,
                                   int light) {
        boolean right = arm == HumanoidArm.RIGHT;
        ItemDisplayContext mode = right
                ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        client.getItemRenderer().render(stack, mode, !right, matrices, consumers, light,
                OverlayTexture.NO_OVERLAY, client.getItemRenderer().getModel(stack,
                        player.level(), player, player.getId() + mode.ordinal()));
    }
}
