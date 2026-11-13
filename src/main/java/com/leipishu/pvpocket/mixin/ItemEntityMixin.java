package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code d.allowPickup}: dropped items stay on the ground.
 *
 * <p>Hooks {@code playerTouch} at HEAD, the only place an item moves itself into an inventory.
 * Cancelling leaves the entity intact, so the drop stays visible and can still be picked up by a
 * player who is allowed to.
 *
 * <p>Silent: an item that does not move explains itself.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noPickup(Player player, CallbackInfo ci) {
        if (!SessionManager.pickupAllowed(player.getUUID())) {
            ci.cancel();
        }
    }
}
