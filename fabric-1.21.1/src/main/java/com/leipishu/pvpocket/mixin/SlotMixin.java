package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code d.allowCrafting}: the result slot cannot be emptied.
 *
 * <p>Injected into {@link Slot} rather than into {@link CraftingResultSlot} because the latter does
 * not declare {@code canTakeItems} — it inherits it, and a mixin cannot inject into an inherited
 * method. The {@code instanceof} narrows it back to the two result slots that exist: the 3x3 grid in
 * {@code CraftingScreenHandler} and the 2x2 grid in the player's own inventory. Both matter; blocking
 * only the table would leave the smaller grid as a way around the rule.</p>
 *
 * <p>{@code canTakeItems} is the check every branch of {@code internalOnSlotClick} makes before
 * moving a stack out — pick-up, shift-click and drag alike — and {@code CraftingResultSlot} only
 * consumes its ingredients in {@code onTakeItem}, which never runs if the take never happens. So a
 * refused take also refuses the craft, rather than quietly eating the ingredients.</p>
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noCrafting(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (player != null
                && (Object) this instanceof CraftingResultSlot
                && !SessionManager.craftingAllowed(player.getUuid())) {
            cir.setReturnValue(false);
            SessionManager.reportCraftingDenial(player);
        }
    }
}
