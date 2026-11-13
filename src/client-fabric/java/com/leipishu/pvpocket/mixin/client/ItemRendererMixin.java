//? if <1.21.11 {
package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.client.render.ItemBlockPose;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fabric side of the 1.8 first-person sword block: skips the display transform and builds the
 * pose instead.
 *
 * <p>Fabric leaves {@code ItemRenderer.render} calling
 * {@code ItemTransforms.getTransform(ctx).apply(leftHand, poseStack)}. NeoForge replaces that
 * line with {@code ClientHooks.handleCameraTransforms(...)}, so the injection cannot be shared.
 * The numbers can: {@link ItemBlockPose} holds the pose itself.
 *
 * <p>Shares its name with the NeoForge twin. Each build sees one of them, from the loader's own
 * srcDir, so {@code pvpocket.client.mixins.json} keeps a single entry.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @WrapOperation(method = "render(Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;Z"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II"
                    + "Lnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/ItemTransform;apply("
                            + "ZLcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void pvpocket$legacyBlockPose(ItemTransform display, boolean leftHanded,
                                          PoseStack matrices, Operation<Void> original,
                                          ItemStack stack, ItemDisplayContext renderMode) {
        if (leftHanded || !ItemBlockPose.wants(stack, renderMode)) {
            original.call(display, leftHanded, matrices);
            return;
        }
        ItemBlockPose.apply(matrices);
    }
}
//?}
