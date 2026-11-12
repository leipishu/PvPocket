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
 * Refuses every command typed by a player who is standing in the duel dimension.
 *
 * <p><b>Hooked at {@code execute}, not at {@code executeWithPrefix}.</b> That is not the obvious
 * choice and it is the one that matters: a command typed into chat is parsed by
 * {@code ServerPlayNetworkHandler} itself and arrives as
 * {@code CommandManager.performCommand(ParseResults, String)}. Only the console and RCON go through
 * {@code executeWithPrefix}, which parses and then calls {@code execute}. Hooking the prefix
 * variant would have blocked the operator and left the players — the entire point — untouched.</p>
 *
 * <p>Hooking {@code execute} is also the most complete single point available: chat, the prefix
 * path, and anything else that already holds parse results all converge here, so there is no second
 * entry point to keep in sync.</p>
 *
 * <p>Cancelling is deliberate rather than reporting through {@code CommandSyntaxException}: the
 * vanilla error path would print "unknown command" or a usage line, which reads as a typo rather
 * than as a rule. The player gets one plain sentence instead.</p>
 *
 * <p>Only player sources are filtered — see {@link Sandbox} for why the console and RCON keep
 * working.</p>
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
     * <p>Without this the operator is locked out of the one repair that matters: {@code /gamemode}
     * from the console is executed by the server, reaches
     * {@code ServerPlayerInteractionManager#changeGameMode}, and is refused by the sandbox rule —
     * silently, because the command reports nothing when the change returns false. An operator would
     * have to teleport the player out of the dimension first, and there is no reason to make them
     * discover that.</p>
     *
     * <p>Player-issued commands never reach here with the scope open: they are either refused above,
     * or they are {@code /pvpocket} commands, which do not change game modes through the command
     * system at all.</p>
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
