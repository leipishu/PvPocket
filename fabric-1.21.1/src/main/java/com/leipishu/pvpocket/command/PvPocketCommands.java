package com.leipishu.pvpocket.command;

import com.leipishu.pvpocket.combat.CombatMode;
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
                        .executes(ctx -> duel(ctx, CombatMode.MODERN))
                        .then(CommandManager.literal("legacy")
                                .executes(ctx -> duel(ctx, CombatMode.LEGACY_1_8)))));

        root.then(CommandManager.literal("accept").executes(PvPocketCommands::accept));
        root.then(CommandManager.literal("propose").executes(PvPocketCommands::propose));
        root.then(CommandManager.literal("deny").executes(PvPocketCommands::deny));
        root.then(CommandManager.literal("review").executes(PvPocketCommands::review));
        root.then(CommandManager.literal("surrender").executes(PvPocketCommands::surrender));
        root.then(CommandManager.literal("status").executes(PvPocketCommands::status));
        root.then(CommandManager.literal("arena").executes(PvPocketCommands::arena));
        root.then(CommandManager.literal("loadout")
                .executes(PvPocketCommands::loadout)
                .then(CommandManager.literal("give").executes(PvPocketCommands::loadoutGive))
                .then(CommandManager.literal("edit").executes(PvPocketCommands::loadoutEdit))
                .then(CommandManager.literal("reset").executes(PvPocketCommands::loadoutReset)));

        dispatcher.register(root);
    }

    private static int help(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        source.sendFeedback(() -> Text.literal("PvPocket 指令：").formatted(Formatting.AQUA), false);
        source.sendFeedback(() -> Text.literal("/pvpocket duel <玩家> [legacy]  发起决斗并打开规则面板").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket review    重新打开规则面板").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket propose   把当前规则发送给对方（等同面板上的「发送征询」）").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket accept|deny  接受或拒绝当前规则版本").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket surrender    认输").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket status       查看当前对局").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket arena        查看场地分区占用").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("/pvpocket loadout [give|edit|reset]  查看配装；edit 打开配装界面（无需决斗）；give 发到手上；reset 清空个人配装").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int duel(CommandContext<ServerCommandSource> ctx, CombatMode mode) {
        try {
            ServerPlayerEntity self = ctx.getSource().getPlayerOrThrow();
            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
            SessionManager.get().requestDuel(self, target, mode);
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

    private static int propose(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().propose(ctx.getSource().getPlayerOrThrow());
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

    private static int review(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().review(ctx.getSource().getPlayerOrThrow());
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

    private static int arena(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        var gallery = com.leipishu.pvpocket.arena.ArenaManager.gallery();
        var leased = com.leipishu.pvpocket.arena.ArenaManager.leased();

        source.sendFeedback(() -> Text.literal("场地分区：" + gallery.size() + " / "
                + com.leipishu.pvpocket.arena.ArenaManager.slotCount() + " 已布置，"
                + leased.size() + " 场进行中").formatted(Formatting.AQUA), false);

        // The gallery is the point: every map sits in its own cell of the dimension, so "select the
        // colosseum" is a teleport to fixed coordinates rather than a paste at the origin.
        if (gallery.isEmpty()) {
            source.sendFeedback(() -> Text.literal("分区尚未布置（服务端预热中或竞技世界未加载）。")
                    .formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.literal("分区坐标：").formatted(Formatting.DARK_AQUA), false);
            for (var region : gallery) {
                String line = "  " + region.id + "  " + region.map.id
                        + "  " + region.sizeX + "x" + region.sizeY + "x" + region.sizeZ
                        + "  " + region.minX + "," + region.minZ
                        + "  中心 " + region.centerX() + "," + region.centerZ();
                source.sendFeedback(() -> Text.literal(line).formatted(Formatting.GRAY), false);
            }
        }

        if (leased.isEmpty()) {
            source.sendFeedback(() -> Text.literal("当前没有进行中的决斗。").formatted(Formatting.GRAY), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal("进行中：").formatted(Formatting.DARK_AQUA), false);
        for (var entry : leased) {
            var region = entry.getValue();
            String line = "  " + region.id + "  " + region.map.id
                    + "  原点 " + region.minX + "," + region.minZ
                    + "  变更 " + region.changeCount()
                    + "  保护 " + (region.protectTerrain ? "开" : "关")
                    + "  会话 " + entry.getKey().substring(0, 8);
            source.sendFeedback(() -> Text.literal(line).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int loadout(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
            com.leipishu.pvpocket.loadout.Loadout loadout = SessionManager.get().loadoutFor(player);
            player.sendMessage(Text.literal("配装：" + loadout.name + " · " + loadout.size()
                    + " 项 · " + loadout.digest()).formatted(Formatting.AQUA), false);
            for (int i = 0; i < loadout.entries.size(); i++) {
                var entry = loadout.entries.get(i);
                player.sendMessage(Text.literal("  " + (i + 1) + ". " + describe(entry))
                        .formatted(Formatting.GRAY), false);
            }
            var problems = loadout.validate();
            if (problems.isEmpty()) {
                player.sendMessage(Text.literal("校验通过。").formatted(Formatting.GREEN), false);
            } else {
                for (String problem : problems) {
                    player.sendMessage(Text.literal("  ✗ " + problem).formatted(Formatting.RED), false);
                }
            }
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadoutGive(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
            com.leipishu.pvpocket.loadout.Loadout loadout = SessionManager.get().loadoutFor(player);
            int given = com.leipishu.pvpocket.loadout.LoadoutBuilder.give(player, loadout, 0);
            player.sendMessage(Text.literal("已发放 " + given + " 项（配装 " + loadout.digest() + "）")
                    .formatted(Formatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Opens the loadout editor without going through the rule panel.
     *
     * <p>The panel's 配装 button is the normal path; this is the same call for players who closed the
     * panel by accident, and the only way to drive the editor from the console during testing. It
     * works with no duel open at all — that is the "configure before challenging" case.</p>
     */
    private static int loadoutEdit(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().onLoadoutRequest(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadoutReset(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().resetPersonalLoadout(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** One-line summary of a loadout row, for the diagnostic command. */
    private static String describe(com.leipishu.pvpocket.loadout.LoadoutEntry entry) {
        StringBuilder sb = new StringBuilder(entry.kind.label);
        if (entry.kind.materialised) {
            sb.append('（').append(entry.material.label).append('）');
        }
        if (!entry.variant.isEmpty()) {
            sb.append(' ').append(com.leipishu.pvpocket.loadout.LoadoutCatalog.labelOf(entry.variant));
        }
        sb.append(" ×").append(entry.count);
        sb.append(" 槽").append(entry.slot);
        if (entry.damageTenths > 0) {
            sb.append(" 伤害").append(entry.damage());
        }
        if (entry.armorTenths > 0) {
            sb.append(" 护甲").append(entry.armor());
        }
        if (entry.durability == com.leipishu.pvpocket.loadout.LoadoutEntry.DURABILITY_UNBREAKABLE) {
            sb.append(" 不可破坏");
        } else if (entry.durability > 0) {
            sb.append(" 耐久").append(entry.durability);
        }
        if (entry.knockback > 0) {
            sb.append(" 击退").append(entry.knockback);
        }
        for (var flag : com.leipishu.pvpocket.loadout.WeaponFlag.decode(entry.flags)) {
            sb.append(' ').append(flag.label);
        }
        if (entry.kind == com.leipishu.pvpocket.loadout.ItemKind.BOW
                && !entry.has(com.leipishu.pvpocket.loadout.WeaponFlag.INFINITY)
                && entry.arrowCount > 0) {
            sb.append(" 箭×").append(entry.arrowCount);
        }
        if (entry.kind == com.leipishu.pvpocket.loadout.ItemKind.POTION) {
            sb.append(' ').append(entry.durationSec).append("s");
            sb.append(" Lv").append(entry.amplifier + 1);
        }
        return sb.toString();
    }
}
