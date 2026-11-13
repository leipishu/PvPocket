package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.i18n.PvLang;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses every command typed by a player standing in the duel dimension.
 *
 * <p>Hooks {@code performCommand} at HEAD rather than {@code executeWithPrefix}: a command typed
 * into chat is parsed by {@code ServerPlayNetworkHandler} and arrives at {@code performCommand},
 * while only the console and RCON go through {@code executeWithPrefix}. Chat, the prefix path and
 * anything else holding parse results all converge here.
 *
 * <p>The command is cancelled rather than failing through {@code CommandSyntaxException}, so the
 * player gets one plain sentence instead of an "unknown command" line. Only player sources are
 * filtered; the console and RCON keep working.
 */
@Mixin(Commands.class)
public abstract class CommandManagerMixin {

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void pvpocket$refuseCommandsInSandbox(ParseResults<CommandSourceStack> parse,
                                                  String command, CallbackInfo ci) {
        CommandSourceStack source = parse.getContext().getSource();
        if (Sandbox.commandAllowed(source, command)) {
            return;
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            player.displayClientMessage(PvLang.t("pvpocket.msg.sandbox.command_denied")
                    .withStyle(ChatFormatting.RED), false);
        }
        ci.cancel();
    }

    /**
     * Opens a game-mode authorisation scope for the duration of a non-player command.
     *
     * <p>Without it, {@code /gamemode} from the console runs through
     * {@code ServerPlayerInteractionManager#changeGameMode} and is refused by the sandbox rule.
     * Player-issued commands never reach here with the scope open.
     */
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void pvpocket$beginOperatorScope(ParseResults<CommandSourceStack> parse,
                                             String command, CallbackInfo ci) {
        if (!(parse.getContext().getSource().getEntity() instanceof ServerPlayer)) {
            Sandbox.beginAuthorised();
        }
    }

    @Inject(method = "performCommand", at = @At("RETURN"))
    private void pvpocket$endOperatorScope(ParseResults<CommandSourceStack> parse,
                                           String command, CallbackInfo ci) {
        if (!(parse.getContext().getSource().getEntity() instanceof ServerPlayer)) {
            Sandbox.endAuthorised();
        }
    }
}
