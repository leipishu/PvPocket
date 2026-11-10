package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.loadout.LoadoutLock;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 禁止丢弃, the other half: the Q key inside an open inventory.
 *
 * <p>With a screen open the Q key does not reach {@code dropSelectedItem} at all — {@code
 * HandledScreen} turns it into a {@code ClickSlotC2SPacket} with {@link SlotActionType#THROW}, and
 * the throw happens inside {@code onSlotClick}. Guarding only the hand would leave the inventory as
 * an obvious way around the lock.</p>
 *
 * <p>Only {@code THROW} is vetoed. Deliberately not the {@code PICKUP} on {@code
 * EMPTY_SPACE_SLOT_INDEX} branch — dragging a stack out of the window to throw it away is the same
 * discard, but it is a multi-pixel gesture the player has to mean, and a lock that also eats that
 * would be a lock on "moving items", not on "one key that loses your sword".</p>
 *
 * <p>The condition mirrors vanilla's own guard for the branch ({@code slotIndex >= 0} and an empty
 * cursor), so the hint only appears when a throw would actually have happened. A refusal on a click
 * that was already a no-op would read as a bug.</p>
 */
@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noThrowLocked(int slotIndex, int button, SlotActionType actionType,
                                        PlayerEntity player, CallbackInfo ci) {
        if (actionType != SlotActionType.THROW || player == null) {
            return;
        }
        ScreenHandler self = (ScreenHandler) (Object) this;
        if (!self.getCursorStack().isEmpty() || slotIndex < 0 || slotIndex >= self.slots.size()) {
            return;
        }
        ItemStack stack = self.slots.get(slotIndex).getStack();
        if (LoadoutLock.isLocked(stack)) {
            SessionManager.reportDropDenial(player);
            ci.cancel();
        }
    }
}
