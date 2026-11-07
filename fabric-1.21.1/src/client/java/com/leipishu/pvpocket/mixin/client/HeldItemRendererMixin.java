package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.combat.CombatState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The 1.8 first-person sword block: the blade tilted across the screen.
 *
 * <p>1.8 got this for free, because a sword block <em>was</em> an item-use state and
 * {@code ItemRenderer#renderItemInFirstPerson} ran {@code doBlockTransformations()} on the
 * {@code EnumAction.BLOCK} branch. 1.21 has no such branch for a sword — the shield pose lives in
 * the shield's own model transform — so the transform has to be applied here or the block is
 * invisible in the one view a player actually uses.</p>
 *
 * <p>The numbers are 1.8's, verbatim:</p>
 *
 * <pre>{@code
 * GlStateManager.translate(-0.5F, 0.2F, 0.0F);
 * GlStateManager.rotate(30.0F, 0.0F, 1.0F, 0.0F);
 * GlStateManager.rotate(-80.0F, 1.0F, 0.0F, 0.0F);
 * GlStateManager.rotate(60.0F, 0.0F, 1.0F, 0.0F);
 * }</pre>
 *
 * <p>Two deliberate deviations, both forced by the version gap. The translate is scaled by the 0.4
 * that 1.8 applied to the whole held item and modern applies inside the item model instead, so the
 * raw {@code -0.5} would sit 2.5× too far to the left. The {@code 45°} yaw from
 * {@code transformFirstPersonItem} is not repeated, because modern's {@code firstperson_righthand}
 * display transform already orients the sword.</p>
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {

    @WrapOperation(method = "renderFirstPersonItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem("
                            + "Lnet/minecraft/entity/LivingEntity;"
                            + "Lnet/minecraft/item/ItemStack;"
                            + "Lnet/minecraft/client/render/model/json/ModelTransformationMode;"
                            + "Z"
                            + "Lnet/minecraft/client/util/math/MatrixStack;"
                            + "Lnet/minecraft/client/render/VertexConsumerProvider;"
                            + "I)V"))
    private void pvpocket$legacyBlockPose(HeldItemRenderer renderer, LivingEntity entity,
                                          ItemStack stack, ModelTransformationMode mode,
                                          boolean leftHanded, MatrixStack matrices,
                                          VertexConsumerProvider vertexConsumers, int light,
                                          Operation<Void> original) {
        if (mode == ModelTransformationMode.FIRST_PERSON_RIGHT_HAND
                && stack.getItem() instanceof SwordItem
                && CombatState.isBlocking(entity.getUuid())) {
            matrices.translate(-0.2F, 0.08F, 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(30.0F));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-80.0F));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(60.0F));
        }
        original.call(renderer, entity, stack, mode, leftHanded, matrices, vertexConsumers, light);
    }
}
