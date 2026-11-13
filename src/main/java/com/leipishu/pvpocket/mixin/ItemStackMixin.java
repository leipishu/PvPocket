package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.UsePolicy;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code f.allowPlace} and the block half of {@code f.blockFilter}.
 *
 * <p>Hooks {@code useOn} at HEAD, the funnel every item-driven placement goes through: block items,
 * beds, respawn anchors and end crystals. Returning {@code PASS} rather than {@code FAIL} keeps the
 * block's own interaction alive, so a chest still opens when the dirt in hand was refused.
 *
 * <p>Both sides run this. The server's copy decides; the client's copy keeps the prediction from
 * creating a block only the placer can see. Both read the same two masks from {@link CombatState}.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void pvpocket$refusePlacement(UseOnContext context,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        Player player = context.getPlayer();
        if (player == null) {
            // Dispensers and other non-player callers place blocks too, but no rule set is in
            // question for them: the gate is per player.
            return;
        }
        ItemStack self = (ItemStack) (Object) this;
        UsePolicy.Denial denial = UsePolicy.placementDenial(
                CombatState.useGate(player.getUUID()), CombatState.useBans(player.getUUID()), self);
        if (denial == UsePolicy.Denial.NONE) {
            return;
        }
        cir.setReturnValue(InteractionResult.PASS);
        SessionManager.reportUseDenial(player, denial, self);
    }
}
