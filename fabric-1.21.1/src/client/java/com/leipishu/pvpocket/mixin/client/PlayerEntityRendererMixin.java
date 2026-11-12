package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a 1.8 sword block visible on everybody else's screen.
 *
 * <p>Without this the mechanic is invisible: the damage is reduced and the movement is slowed, but
 * the model stands there with its arm down, so the only way to know an opponent is blocking is to
 * hit them and compare numbers. In 1.8 the sword crossed the body and that pose <em>was</em> the
 * signal.</p>
 *
 * <p>The pose is taken from vanilla rather than reproduced: {@code ArmPose.BLOCK} already positions
 * the arm the way a raised shield does, and re-deriving the angles would have meant owning a copy of
 * {@code positionBlockingArm} that silently drifts on the next Minecraft update.</p>
 *
 * <p>This is the pose of <em>other</em> players. The local player's own hand is drawn by
 * {@code HeldItemRenderer}, which is a separate path — see {@code InGameHudMixin} for the cue that
 * covers first person.</p>
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(method = "getArmPose", at = @At("RETURN"), cancellable = true)
    private static void pvpocket$legacySwordBlock(AbstractClientPlayer player, InteractionHand hand,
                                                  CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        // Main hand only: the flag is about the sword, and the off hand in a legacy round is either
        // empty or something the loadout was not supposed to hand out.
        if (hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().getItem() instanceof SwordItem
                && CombatState.isBlocking(player.getUUID())) {
            cir.setReturnValue(HumanoidModel.ArmPose.BLOCK);
        }
    }
}
