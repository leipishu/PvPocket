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
 * <p>{@code ItemStack#useOnBlock} is the one funnel every item-driven placement goes through:
 * {@code BlockItem}, beds and respawn anchors (both {@code BlockItem} subclasses) and end crystals.
 * Blocking there rather than in {@code interactBlock} is what keeps a refused placement from also
 * swallowing the block's own interaction — a chest still opens when the dirt in your hand was
 * refused, because the caller treats {@code PASS} as "the item had nothing to say" and moves on to
 * {@code BlockState#onUse}.</p>
 *
 * <p>Both sides run this. The server's copy is the one that decides; the client's copy exists so the
 * prediction does not create a block only the placer can see. They agree because both read the same
 * two masks from {@link CombatState}, and the client's masks arrive from the server.</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void pvpocket$refusePlacement(UseOnContext context,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        Player player = context.getPlayer();
        if (player == null) {
            // Dispensers and other non-player callers place blocks too, but no rule set is in
            // question for them: the gate is per player, and an empty gate is "nothing restricted".
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
