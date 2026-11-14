package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.hud.PossessionHud;
import com.leipishu.pvpocket.network.PossessPayload;
//? if <26.1 {
import net.minecraft.client.renderer.LightTexture;
//?}
//? if >=1.21.11 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;
//?} else {
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.MultiBufferSource;
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
 * <p>Vanilla draws nothing in spectator mode, and would render {@code client.player} rather
 * than the watched fighter, so the hand is drawn here. The pose stack still holds the view
 * rotation, so only the position-matrix inverse is supplied: {@code translate(0.56, -0.52,
 * -0.72)} then means half a block right of the eye.
 *
 * <p>The sink the geometry goes to is a {@code MultiBufferSource} before 1.21.11 and a
 * {@code SubmitNodeCollector} after it; it travels as {@code Object} and is unwrapped at the
 * two leaves, because the pose maths above them is identical.
 */
public final class PossessedHandRenderer {

    /**
     * Vanilla animates this from 1 to 0 while an item is swapped into the hand. A remote body is
     * never observed mid-swap, so it is 0.
     */
    private static final float EQUIP_PROGRESS = 0.0F;

    private PossessedHandRenderer() {
    }

    /** One line per possession, not per frame: enough to tell "never ran" from "ran and drew". */
    private static int lastLoggedTarget = -1;

    public static void register() {
        PvClientPlatform.get().onWorldRenderLast(PossessedHandRenderer::render);
    }

    private static void render(PvClientPlatform.WorldFrame frame) {
        PossessPayload payload = PossessionHud.state();
        if (payload == null || payload.targetId() < 0) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.options.hideGui) {
            return;
        }
        // Possession is a first-person view: in third person a hand before the camera is wrong.
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

        float tickDelta = frame.delta().getGameTimeDeltaPartialTick(true);
        // The view rotation the world was rendered with, rebuilt from the same camera so a stack
        // left dirty by another renderer cannot affect it.
        Matrix4f viewRotation = new Matrix4f()
                .rotation(frame.camera().rotation().conjugate(new Quaternionf()));

        PoseStack matrices = new PoseStack();
        matrices.pushPose();
        matrices.mulPose(viewRotation.invert(new Matrix4f()));

        int light = client.getEntityRenderDispatcher().getPackedLightCoords(target, tickDelta);
        ItemStack main = target.getMainHandItem();
        ItemStack off = target.getOffhandItem();
//? if >=1.21.11 {
        Object sink = frame.nodes();
        if (sink == null) {
            return;
        }
        drawHand(client, matrices, sink, target, tickDelta, InteractionHand.MAIN_HAND, main, light);
        drawHand(client, matrices, sink, target, tickDelta, InteractionHand.OFF_HAND, off, light);
//?} else {
        MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();
        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        ((LightTexture) frame.lightmap()).turnOnLightLayer();
        drawHand(client, matrices, consumers, target, tickDelta, InteractionHand.MAIN_HAND, main, light);
        drawHand(client, matrices, consumers, target, tickDelta, InteractionHand.OFF_HAND, off, light);
        consumers.endBatch();
        ((LightTexture) frame.lightmap()).turnOffLightLayer();
//?}
        matrices.popPose();

        if (lastLoggedTarget != payload.targetId()) {
            lastLoggedTarget = payload.targetId();
            PvPocket.LOGGER.info("Possessed hand: target={} main={} off={} light={}",
                    target.getName().getString(), main.getItem(), off.getItem(), light);
        }
    }

    /**
     * Draws one hand, mirroring vanilla's first-person item renderer for the two cases a
     * spectator can observe: an empty hand (the arm) and a held item (equip offset plus swing arc).
     *
     * <p>The use-action branches (eating, drinking, drawing a bow, blocking with a shield) are not
     * reproduced, because they depend on state vanilla does not synchronise for other players.
     */
    private static void drawHand(Minecraft client, PoseStack matrices, Object sink,
                                 AbstractClientPlayer player, float tickDelta,
                                 InteractionHand hand, ItemStack stack, int light) {
        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        // Vanilla passes the swing progress to the main hand only; the off hand is drawn flat.
        float swing = mainHand ? player.getAttackAnim(tickDelta) : 0.0F;

        matrices.pushPose();
        if (stack.isEmpty()) {
            // Vanilla never draws an empty off hand; only the main arm is visible.
            if (mainHand && !player.isInvisible()) {
                renderArmHoldingItem(client, matrices, sink, light, EQUIP_PROGRESS, swing, arm,
                        player);
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
            renderItem(client, matrices, sink, player, stack, arm, light);
        }
        matrices.popPose();
    }

    /** Mirrors {@code ItemInHandRenderer.applyEquipOffset}. */
    private static void applyEquipOffset(PoseStack matrices, HumanoidArm arm, float equipProgress) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        matrices.translate(side * 0.56F, -0.52F + equipProgress * -0.6F, -0.72F);
    }

    /** Mirrors {@code ItemInHandRenderer.applySwingOffset}. */
    private static void applySwingOffset(PoseStack matrices, HumanoidArm arm, float swingProgress) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float arc = Mth.sin(swingProgress * swingProgress * (float) Math.PI);
        matrices.mulPose(Axis.YP.rotationDegrees(side * (45.0F + arc * -20.0F)));
        float lift = Mth.sin(Mth.sqrt(swingProgress) * (float) Math.PI);
        matrices.mulPose(Axis.ZP.rotationDegrees(side * lift * -20.0F));
        matrices.mulPose(Axis.XP.rotationDegrees(lift * -80.0F));
        matrices.mulPose(Axis.YP.rotationDegrees(side * -45.0F));
    }

    /** Mirrors vanilla's arm draw, with the target as the arm owner. */
    private static void renderArmHoldingItem(Minecraft client, PoseStack matrices, Object sink,
                                             int light, float equipProgress, float swingProgress,
                                             HumanoidArm arm, AbstractClientPlayer player) {
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

        if (!(client.getEntityRenderDispatcher().getRenderer(player)
                instanceof PlayerRenderer playerRenderer)) {
            return;
        }
//? if >=1.21.11 {
        // The texture and the sleeve flag are arguments: the renderer is not bound to a player.
        SubmitNodeCollector nodes = (SubmitNodeCollector) sink;
        ResourceLocation skin = player.getSkin().body().texturePath();
        PlayerModelPart part = right ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE;
        if (right) {
            playerRenderer.renderRightHand(matrices, nodes, light, skin,
                    player.isModelPartShown(part));
        } else {
            playerRenderer.renderLeftHand(matrices, nodes, light, skin,
                    player.isModelPartShown(part));
        }
//?} else {
        MultiBufferSource.BufferSource consumers = (MultiBufferSource.BufferSource) sink;
        if (right) {
            playerRenderer.renderRightHand(matrices, consumers, light, player);
        } else {
            playerRenderer.renderLeftHand(matrices, consumers, light, player);
        }
//?}
    }

    private static void renderItem(Minecraft client, PoseStack matrices, Object sink,
                                   AbstractClientPlayer player, ItemStack stack, HumanoidArm arm,
                                   int light) {
        boolean right = arm == HumanoidArm.RIGHT;
        ItemDisplayContext mode = right
                ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
//? if >=1.21.11 {
        // Vanilla's own first-person renderer, which resolves the model and submits the geometry.
        client.gameRenderer.itemInHandRenderer.renderItem(player, stack, mode, matrices,
                (SubmitNodeCollector) sink, light);
//?} else {
        MultiBufferSource.BufferSource consumers = (MultiBufferSource.BufferSource) sink;
        client.getItemRenderer().render(stack, mode, !right, matrices, consumers, light,
                OverlayTexture.NO_OVERLAY, client.getItemRenderer().getModel(stack,
                        player.level(), player, player.getId() + mode.ordinal()));
//?}
    }
}
