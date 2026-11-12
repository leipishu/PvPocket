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
 * NeoForge side of the 1.8 first-person sword block: skip the display transform, build the pose.
 *
 * <p><b>Why this is a second file.</b> NeoForge's patch replaces the vanilla line
 * {@code model.getTransforms().getTransform(ctx).apply(leftHand, poseStack)} inside
 * {@code ItemRenderer.render} with {@code ClientHooks.handleCameraTransforms(poseStack, model, ctx,
 * leftHand)}. The Fabric mixin's {@code @At(target = "…ItemTransform;apply(…)V")} therefore scans
 * zero candidates here and the client dies on {@code RenderType} load with
 * {@code Critical injection failure … (0/1) succeeded}. {@code MixinTargetCheck} cannot see this:
 * the class and the method both still exist, it is the <em>call site</em> that moved.</p>
 *
 * <p>Returning {@code model} instead of calling the original is the whole point — the wrapped call
 * does nothing but apply the transform and hand the model back ({@code BakedModel.applyTransform}
 * returns {@code this} for every vanilla model), so skipping it is exactly "skip the transform".</p>
 *
 * <p>Same class name as the Fabric twin on purpose: each build sees exactly one of them, so
 * {@code pvpocket.client.mixins.json} keeps a single entry.</p>
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
