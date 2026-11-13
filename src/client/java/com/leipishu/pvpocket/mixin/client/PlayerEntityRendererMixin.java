package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a 1.8 sword block visible on other players' screens.
 *
 * <p>Hooks {@code PlayerRenderer.getArmPose} at RETURN and returns {@code ArmPose.BLOCK} for a
 * blocking player holding a sword in the main hand. The pose comes from vanilla, so the arm angles
 * track a raised shield.
 *
 * <p>This covers other players. The local player's own hand is drawn by {@code HeldItemRenderer};
 * {@code InGameHudMixin} covers first person.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(method = "getArmPose", at = @At("RETURN"), cancellable = true)
    private static void pvpocket$legacySwordBlock(AbstractClientPlayer player, InteractionHand hand,
                                                  CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        // Main hand only; the flag describes the sword.
        if (hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().is(ItemTags.SWORDS)
                && CombatState.isBlocking(player.getUUID())) {
            cir.setReturnValue(HumanoidModel.ArmPose.BLOCK);
        }
    }
}
