package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.combat.CombatState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.render.model.json.Transformation;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Arm;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The 1.8 first-person sword block.
 *
 * <p><b>Why the display transform is replaced rather than added to.</b> 1.8's first person never
 * applied {@code item/handheld}'s {@code firstperson_righthand} transform — {@code
 * transformFirstPersonItem} supplied the whole pose itself ({@code translate(0.56, -0.52, -0.72)},
 * {@code rotateY(45)}, {@code scale(0.4)}), which is why a 1.8 sword is a flat quad squarely facing
 * you. 1.21 moved that pose into the display transform ({@code rotation [0, -90, 25], translation
 * [1.13, 3.2, 1.13], scale 0.68}), which is why a modern sword is a foreshortened model seen from the
 * side. Adding 1.8's {@code doBlockTransformations} <em>in front of</em> the modern transform leaves
 * the modern rotation in the stack and throws the blade off screen — verified, the sword vanished
 * entirely. So the transform is skipped and the pose is built from scratch.</p>
 *
 * <p><b>The pose.</b> With the display transform gone the model is a flat quad in the hand frame,
 * flat side on {@code +Z}, blade running bottom-left to top-right along the texture diagonal. 1.8's
 * block reads as that same quad rolled a quarter turn in the screen plane: flat side still square to
 * the camera, hilt swinging down to the right, blade now running bottom-right to top-left at 45°.
 * That is a single {@code rotateZ(90)} about the model centre. It is stated that way rather than as
 * 1.8's {@code doBlockTransformations} numbers because those were written against 1.8's own hand
 * frame and land the blade pointing down-left once the display transform is out of the way.</p>
 *
 * <p><b>Why the lift is computed rather than tuned.</b> A quarter turn about the model centre swings
 * the hilt out to the corner of the quad, roughly {@link #GRIP} model units away from where the hand
 * is — which is what "the hilt looks like it is floating" was. The lift below is derived from the
 * scale so the <em>hilt</em>, not the quad's centre, lands on the hand; changing the scale therefore
 * keeps the grip attached instead of needing a second magic number retuned by eye.</p>
 *
 * <p>The hand position is {@code renderArmHoldingItem}'s origin, {@code (0.64, -0.60, -0.72)},
 * against {@code applyEquipOffset}'s item anchor of {@code (0.56, -0.52, -0.72)}.</p>
 *
 * <p>Left-handed players keep the vanilla pose. A quarter turn cannot mirror, and mirroring the quad
 * itself would flip its winding and make it invisible from the front, so there is no cheap correct
 * answer here; the crosshair brackets still show the block is up.</p>
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    /**
     * 1.8's own hand scale is 0.4, and it is what 1.8 shipped — but 1.8 also drew the sprite at a
     * fixed 1.7 display scale that modern does not have, and at 0.4 the blade reads noticeably
     * undersized next to a modern held sword. 0.5 is the value that keeps the two modes from looking
     * like the weapon shrank when the mode changes.
     */
    private static final float HAND_SCALE = 0.5F;

    /** The quarter turn that takes 1.8's idle silhouette to its block silhouette. */
    private static final float BLOCK_ROLL = 90.0F;

    /** Where the hand sits relative to the item anchor, in blocks. */
    private static final float HAND_OFFSET_X = 0.08F;

    private static final float HAND_OFFSET_Y = -0.08F;

    /**
     * Distance from the quad's centre to the hilt, in model units.
     *
     * <p>The hilt is the bottom-left corner of a 1x1 sprite inset by the usual two transparent
     * pixels: {@code (0.10, 0.10)} in model space, which after {@code translate(-0.5, -0.5, -0.5)}
     * is {@code (-0.40, -0.40)}.</p>
     */
    private static final float GRIP = 0.40F;

    @WrapOperation(method = "renderItem(Lnet/minecraft/item/ItemStack;"
                    + "Lnet/minecraft/client/render/model/json/ModelTransformationMode;Z"
                    + "Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;II"
                    + "Lnet/minecraft/client/render/model/BakedModel;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/model/json/Transformation;apply("
                            + "ZLnet/minecraft/client/util/math/MatrixStack;)V"))
    private void pvpocket$legacyBlockPose(Transformation display, boolean leftHanded,
                                          MatrixStack matrices, Operation<Void> original,
                                          ItemStack stack, ModelTransformationMode renderMode) {
        if (leftHanded || !wantsBlockPose(stack, renderMode)) {
            original.call(display, leftHanded, matrices);
            return;
        }

        // Where the quarter turn puts the hilt, measured from the anchor. Rotating +90 about Z maps
        // (-GRIP, -GRIP) to (+GRIP, -GRIP), so the lift that cancels it is exactly its negation.
        float gripX = GRIP * HAND_SCALE;
        float gripY = -GRIP * HAND_SCALE;

        matrices.translate(HAND_OFFSET_X - gripX, HAND_OFFSET_Y - gripY, 0.0F);
        matrices.scale(HAND_SCALE, HAND_SCALE, HAND_SCALE);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(BLOCK_ROLL));

        // ItemRenderer's own translate(-0.5, -0.5, -0.5) follows this call, so the quarter turn above
        // happens about the model's centre rather than its corner.
    }

    private static boolean wantsBlockPose(ItemStack stack, ModelTransformationMode renderMode) {
        if (!(stack.getItem() instanceof SwordItem)) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity player = client.player;
        if (player == null || !CombatState.isBlocking(player.getUuid())) {
            return false;
        }

        // Only the hand that is blocking: a sword in the off hand keeps its idle pose.
        ModelTransformationMode mainHand = player.getMainArm() == Arm.RIGHT
                ? ModelTransformationMode.FIRST_PERSON_RIGHT_HAND
                : ModelTransformationMode.FIRST_PERSON_LEFT_HAND;
        return renderMode == mainHand;
    }
}
