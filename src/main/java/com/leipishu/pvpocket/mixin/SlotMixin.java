package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code d.allowCrafting}: the result slot cannot be emptied.
 *
 * <p>Hooks {@code mayPickup} at HEAD, the check every branch of {@code internalOnSlotClick} makes
 * before moving a stack out. {@code ResultSlot} only consumes its ingredients in
 * {@code onTakeItem}, so a refused take also refuses the craft.
 *
 * <p>Injected into {@link Slot} because {@code ResultSlot} does not declare {@code mayPickup}; the
 * {@code instanceof} narrows it to the 3x3 table grid and the 2x2 inventory grid.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noCrafting(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player != null
                && (Object) this instanceof ResultSlot
                && !SessionManager.craftingAllowed(player.getUUID())) {
            cir.setReturnValue(false);
            SessionManager.reportCraftingDenial(player);
        }
    }
}
