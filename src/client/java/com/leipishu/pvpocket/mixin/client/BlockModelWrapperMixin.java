//? if >=1.21.11 {
package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.client.render.ItemBlockPose;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The 1.8 first-person sword block pose, for the 1.21.9 item model pipeline.
 *
 * <p>Both loaders share this one. {@code ItemRenderer.render} does not exist, and the display
 * transform is not a line that can be skipped: {@code BlockModelWrapper.update} resolves one per
 * layer, stores it on the render state, and it is applied when the state is submitted. Wrapping
 * the resolve step stores the block pose there instead.
 *
 * <p>The stack is only reachable as an enclosing parameter of {@code update}, which is why the
 * handler takes the whole signature rather than the wrapped call's arguments alone.
 */
@Mixin(BlockModelWrapper.class)
public abstract class BlockModelWrapperMixin {

    @WrapOperation(method = "update",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/item/ModelRenderProperties;"
                            + "applyToLayer("
                            + "Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;)V"))
    private void pvpocket$blockPose(ModelRenderProperties properties,
                                    ItemStackRenderState.LayerRenderState layer,
                                    ItemDisplayContext context,
                                    Operation<Void> original,
                                    ItemStackRenderState state, ItemStack stack,
                                    ItemModelResolver resolver, ItemDisplayContext displayContext,
                                    ClientLevel level, ItemOwner owner, int seed) {
        if (ItemBlockPose.wants(stack, context)) {
            layer.setTransform(ItemBlockPose.transform());
            return;
        }
        original.call(properties, layer, context);
    }
}
//?}
