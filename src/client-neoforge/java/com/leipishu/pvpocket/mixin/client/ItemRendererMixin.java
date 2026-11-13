//? if <1.21.11 {
package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.client.render.ItemBlockPose;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge side of the 1.8 first-person sword block: skips the display transform and builds the
 * pose instead.
 *
 * <p>NeoForge patches {@code ItemRenderer.render} to call
 * {@code ClientHooks.handleCameraTransforms(poseStack, model, ctx, leftHand)}, so a mixin that
 * targets {@code ItemTransform.apply} finds no candidate. {@code MixinTargetCheck} cannot
 * detect that: the class and method both exist, only the call site moved.
 *
 * <p>Returning {@code model} instead of calling the original is correct because the wrapped
 * call only applies the transform and returns {@code this} for every vanilla model.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @WrapOperation(method = "render(Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;Z"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II"
                    + "Lnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;handleCameraTransforms("
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/resources/model/BakedModel;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;Z)"
                            + "Lnet/minecraft/client/resources/model/BakedModel;"))
    private BakedModel pvpocket$legacyBlockPose(PoseStack matrices, BakedModel model,
                                                ItemDisplayContext renderMode, boolean leftHanded,
                                                Operation<BakedModel> original, ItemStack stack) {
        if (leftHanded || !ItemBlockPose.wants(stack, renderMode)) {
            return original.call(matrices, model, renderMode, leftHanded);
        }
        ItemBlockPose.apply(matrices);
        return model;
    }
}
//?}
