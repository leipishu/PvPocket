package com.leipishu.pvpocket.mixin.client;

// 26.2 moved the screen stack from Minecraft to Gui. Below 26.2 this file generates empty: the
// Minecraft hook in MinecraftClientMixin covers those versions.
//? if >=26.2 {
import com.leipishu.pvpocket.client.hud.MatchHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the death screen during a match and respawns immediately.
 *
 * <p>Hooks {@code Gui.setScreen}: a {@link DeathScreen} is cancelled and a respawn request is sent
 * instead. From 26.2 the screen stack lives on {@code Gui}, so this cannot share a class with the
 * {@code Minecraft} hooks.
 *
 * <p>Gated on the match HUD rather than the arena dimension, because spectators also stand in that
 * dimension and should still see the screen.
 */
@Mixin(Gui.class)
public abstract class GuiScreenMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void pvpocket$skipArenaDeathScreen(Screen screen, CallbackInfo ci) {
        if (!(screen instanceof DeathScreen)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null || !MatchHud.isActive()) {
            return;
        }
        client.getConnection().send(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ci.cancel();
    }
}
//?}
