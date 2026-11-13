package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if >=1.21.11 {
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
//?} else {
import net.minecraft.client.player.AbstractClientPlayer;
//?}

/**
 * Makes a 1.8 sword block visible on other players' screens.
 *
 * <p>Hooks {@code PlayerRenderer.getArmPose} at RETURN and returns {@code ArmPose.BLOCK} for a
 * blocking player holding a sword in the main hand. The pose comes from vanilla, so the arm angles
 * track a raised shield.
 *
 * <p>This covers other players. The local player's own hand is drawn by {@code HeldItemRenderer};
 * {@code InGameHudMixin} covers first person. 1.21.11 moved the player entity up to
 * {@code Avatar} and passed the held stack in, so the handler gained a slot.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerEntityRendererMixin {

    //? if >=1.21.11 {
    // The full descriptor, not the bare name: 1.21.11 has a second getArmPose(Avatar, HumanoidArm)
    // overload, and a bare name binds the handler to whichever one Mixin reaches first.
    @Inject(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/InteractionHand;)"
                    + "Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
            at = @At("RETURN"), cancellable = true)
    private static void pvpocket$legacySwordBlock(Avatar player, ItemStack stack, InteractionHand hand,
                                                  CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
    //?} else {
    @Inject(method = "getArmPose", at = @At("RETURN"), cancellable = true)
    private static void pvpocket$legacySwordBlock(AbstractClientPlayer player, InteractionHand hand,
                                                  CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
    //?}
        // Main hand only; the flag describes the sword.
        if (hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().is(ItemTags.SWORDS)
                && CombatState.isBlocking(player.getUUID())) {
            cir.setReturnValue(HumanoidModel.ArmPose.BLOCK);
        }
    }
}
