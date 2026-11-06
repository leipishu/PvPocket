package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.arena.Sandbox;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses game mode changes for a player inside the duel dimension.
 *
 * <p>Hooked here rather than on {@code /gamemode} because this is the method every path funnels
 * through: the command, another mod's admin tool, and anything else that decides to switch a
 * player's mode. The command block in {@link CommandManagerMixin} already stops the command, so
 * this is the second layer — the one that holds when the caller is not a command at all.</p>
 *
 * <p>{@code ServerPlayerEntity#changeGameMode} is the only vanilla caller, and the playerdata
 * restore path uses {@code setGameMode} instead, so this hook cannot corrupt a login. The mod's own
 * changes come through {@link Sandbox#changeGameMode}, which lifts the rule for the duration of the
 * call.</p>
 *
 * <p>Returning {@code false} is what vanilla returns for "nothing changed", so a refused switch is
 * indistinguishable from a no-op to the caller — which is the correct answer for a caller that has
 * no business changing it.</p>
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class ServerPlayerInteractionManagerMixin {

    @Shadow
    @Final
    protected ServerPlayerEntity player;

    @Inject(method = "changeGameMode", at = @At("HEAD"), cancellable = true)
    private void pvpocket$refuseGameModeChangeInSandbox(GameMode mode,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (Sandbox.gameModeChangeAllowed() || !Sandbox.isSandbox(player.getWorld())) {
            return;
        }
        // Already in that mode: let it through so a redundant call still updates `previousGameMode`
        // the way vanilla would. Nothing is being changed, so nothing is being permitted.
        if (player.interactionManager.getGameMode() == mode) {
            return;
        }
        Sandbox.logRefusal(player, mode);
        cir.setReturnValue(false);
    }
}
