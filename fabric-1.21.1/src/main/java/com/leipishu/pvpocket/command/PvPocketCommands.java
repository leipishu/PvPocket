package com.leipishu.pvpocket.command;

import com.leipishu.pvpocket.session.SessionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Command surface for S1. The rule GUI replaces most of this later; the commands stay as the
 * scriptable, testable path.
 */
public final class PvPocketCommands {

    private PvPocketCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("pvpocket")
                .executes(PvPocketCommands::help);

        root.then(CommandManager.literal("duel")
                .then(CommandManager.argument("target", EntityArgumentType.player())
                        .executes(PvPocketCommands::duel)));

        root.then(CommandManager.literal("accept").executes(PvPocketCommands::accept));
        root.then(CommandManager.literal("deny").executes(PvPocketCommands::deny));
        root.then(CommandManager.literal("surrender").executes(PvPocketCommands::surrender));
        root.then(CommandManager.literal("status").executes(PvPocketCommands::status));

        dispatcher.register(root);
    }

    private static int help(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        source.sendFeedback(() -> Text.literal("PvPocket 指令：").formatted(Formatting.AQUA), false);
        source.sendFeedback(() -> Text.literal("/pvpocket duel <玩家>  发起决斗").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket accept|deny  接受或拒绝邀请").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket surrender    认输").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket status       查看当前对局").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int duel(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity self = ctx.getSource().getPlayerOrThrow();
            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
            SessionManager.get().requestDuel(self, target);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int accept(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().accept(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int deny(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().deny(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int surrender(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().surrender(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().status(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }
}
