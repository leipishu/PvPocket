package com.leipishu.pvpocket.command;

import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutCatalog;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.loadout.WeaponFlag;
import com.leipishu.pvpocket.session.SessionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Command surface for S1. The rule GUI replaces most of this later; the commands stay as the
 * scriptable, testable path.
 *
 * <p><b>Command syntax is never translated.</b> {@code /pvpocket}, its literals, and the argument
 * placeholders in the help text ({@code <player>}, {@code <true|false>}) are tokens the dispatcher
 * parses; they appear identically in every language file and are passed to the translation as
 * literal arguments rather than as part of a translated sentence. Only the description beside them
 * changes with the reader's language.</p>
 */
public final class PvPocketCommands {

    /**
     * Column the descriptions start at, in characters.
     *
     * <p>The font is proportional, so this lines the help text up approximately rather than exactly —
     * which is what the full-width spaces it replaces did as well, and what a translated command list
     * can do at all: the usage column is a fixed ASCII token, so it is the one thing that can be
     * padded to a stable width.</p>
     */
    private static final int USAGE_COLUMN = 34;

    private PvPocketCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("pvpocket")
                .executes(PvPocketCommands::help);

        // No arguments. Who fights whom is a two-dimensional list, and Brigadier has no grammar for
        // one: a selector can say "everyone here" but cannot say "these two on one side and those
        // two on the other", which is the only thing that matters once a match has more than two
        // sides. The roster is built in the panel instead, and the sides are then explicit.
        root.then(CommandManager.literal("duel").executes(PvPocketCommands::duel));

        // No accept/propose/deny here. Those three live on the rule panel and only there: the panel
        // is opened by the server at every point where one of them could be pressed, so a command
        // was a second path to the same three intents with nothing the panel cannot do — and three
        // lines of the help text spent explaining a slower way to click a button.
        root.then(CommandManager.literal("help").executes(PvPocketCommands::help));
        root.then(CommandManager.literal("review").executes(PvPocketCommands::review));
        root.then(CommandManager.literal("surrender").executes(PvPocketCommands::surrender));
        root.then(CommandManager.literal("status").executes(PvPocketCommands::status));
        root.then(CommandManager.literal("arena")
                .executes(PvPocketCommands::arena)
                // Operator-only: this destroys terrain and ends other people's matches.
                .then(CommandManager.literal("reset")
                        .requires(source -> source.hasPermissionLevel(3))
                        .executes(PvPocketCommands::arenaReset)));
        root.then(CommandManager.literal("guard")
                // Operator-only, because the flag is the one way to stay in the sandbox on purpose.
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> guard(ctx, BoolArgumentType.getBool(ctx, "enabled")))));
        root.then(CommandManager.literal("list").executes(PvPocketCommands::list));
        root.then(CommandManager.literal("spectate")
                .executes(PvPocketCommands::spectateHelp)
                .then(CommandManager.literal("leave").executes(PvPocketCommands::spectateLeave))
                .then(CommandManager.argument("session", StringArgumentType.word())
                        .executes(ctx -> spectate(ctx, StringArgumentType.getString(ctx, "session")))));
        root.then(CommandManager.literal("loadout")
                .executes(PvPocketCommands::loadout)
                .then(CommandManager.literal("give").executes(PvPocketCommands::loadoutGive))
                .then(CommandManager.literal("edit").executes(PvPocketCommands::loadoutEdit))
                .then(CommandManager.literal("accept").executes(PvPocketCommands::loadoutAccept))
                .then(CommandManager.literal("decline").executes(PvPocketCommands::loadoutDecline))
                .then(CommandManager.literal("reset").executes(PvPocketCommands::loadoutReset)));

        dispatcher.register(root);
    }

    /**
     * One help line: the usage as a literal, the description from the language file.
     *
     * <p>Split rather than baked into a single translated sentence so that a translator cannot
     * accidentally translate {@code /pvpocket spectate leave} into something the dispatcher does not
     * accept.</p>
     */
    private static void helpLine(ServerCommandSource source, String usage, String descriptionKey) {
        String pad = " ".repeat(Math.max(2, USAGE_COLUMN - usage.length()));
        source.sendFeedback(() -> Text.literal(usage + pad).formatted(Formatting.GRAY)
                .append(PvLang.t(descriptionKey)), false);
    }

    private static int help(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        source.sendFeedback(() -> PvLang.t("pvpocket.command.help.header", "/pvpocket help")
                .formatted(Formatting.AQUA), false);
        helpLine(source, "/pvpocket duel", "pvpocket.command.help.duel");
        helpLine(source, "/pvpocket review", "pvpocket.command.help.review");
        helpLine(source, "/pvpocket surrender", "pvpocket.command.help.surrender");
        helpLine(source, "/pvpocket status", "pvpocket.command.help.status");
        helpLine(source, "/pvpocket arena", "pvpocket.command.help.arena");
        helpLine(source, "/pvpocket arena reset", "pvpocket.command.help.arena_reset");
        helpLine(source, "/pvpocket guard <true|false>", "pvpocket.command.help.guard");
        helpLine(source, "/pvpocket list", "pvpocket.command.help.list");
        helpLine(source, "/pvpocket spectate <prefix>", "pvpocket.command.help.spectate");
        helpLine(source, "/pvpocket spectate leave", "pvpocket.command.help.spectate_leave");
        helpLine(source, "/pvpocket loadout [give|edit|reset]", "pvpocket.command.help.loadout");
        helpLine(source, "/pvpocket loadout [accept|decline]", "pvpocket.command.help.loadout_swap");
        source.sendFeedback(() -> PvLang.t("pvpocket.command.help.footer")
                .formatted(Formatting.DARK_GRAY), false);
        return 1;
    }

    private static int duel(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity self = ctx.getSource().getPlayerOrThrow();
            SessionManager.get().openRoster(self);
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

        source.sendFeedback(() -> PvLang.t("pvpocket.command.arena.header",
                        gallery.size(), com.leipishu.pvpocket.arena.ArenaManager.slotCount(),
                        leased.size())
                .formatted(Formatting.AQUA), false);

        // The gallery is the point: every map sits in its own cell of the dimension, so "select the
        // colosseum" is a teleport to fixed coordinates rather than a paste at the origin.
        if (gallery.isEmpty()) {
            source.sendFeedback(() -> PvLang.t("pvpocket.command.arena.empty")
                    .formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> PvLang.t("pvpocket.command.arena.regions")
                    .formatted(Formatting.DARK_AQUA), false);
            for (var region : gallery) {
                source.sendFeedback(() -> PvLang.t("pvpocket.command.arena.region_line",
                                region.id, region.map.labelText(),
                                region.sizeX, region.sizeY, region.sizeZ,
                                region.minX, region.minZ,
                                region.centerX(), region.centerZ())
                        .formatted(Formatting.GRAY), false);
            }
        }

        if (leased.isEmpty()) {
            source.sendFeedback(() -> PvLang.t("pvpocket.command.arena.none_active")
                    .formatted(Formatting.GRAY), false);
            return 1;
        }
        source.sendFeedback(() -> PvLang.t("pvpocket.command.arena.active")
                .formatted(Formatting.DARK_AQUA), false);
        for (var entry : leased) {
            var region = entry.getValue();
            source.sendFeedback(() -> PvLang.t("pvpocket.command.arena.leased_line",
                            region.id, region.map.labelText(),
                            region.minX, region.minZ,
                            region.changeCount(),
                            PvLang.t(region.protectTerrain
                                    ? "pvpocket.value.on" : "pvpocket.value.off"),
                            entry.getKey().substring(0, 8))
                    .formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    /**
     * Hard reset of the duel dimension.
     *
     * <p>Announced to everyone, not just the operator. The command ends other people's matches
     * without their consent and then freezes the world for a few seconds while the gallery
     * re-stamps, and a server that goes quiet for no visible reason reads as a crash.</p>
     */
    private static int arenaReset(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        SessionManager manager = SessionManager.get();
        if (manager == null) {
            source.sendError(PvLang.t("pvpocket.command.session_not_ready"));
            return 0;
        }
        int ended = manager.resetArena();
        if (ended < 0) {
            source.sendError(PvLang.t("pvpocket.command.arena.rebuilding"));
            return 0;
        }
        MutableText line = PvLang.t("pvpocket.command.arena.reset_done", ended)
                .formatted(Formatting.AQUA);
        source.sendFeedback(() -> line, true);
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            // The operator already got it through sendFeedback; a second copy reads as two events.
            if (player != source.getEntity()) {
                player.sendMessage(line, false);
            }
        }
        return ended;
    }

    /**
     * Turns the automatic removal of stray players on or off for the calling player.
     *
     * <p>Self-targeting on purpose. The flag is an "I am going in there to look" permission, and a
     * command that could hand it to somebody else would be a way to park a player in the sandbox
     * indefinitely without them ever seeing the prompt.</p>
     */
    private static int guard(CommandContext<ServerCommandSource> ctx, boolean enabled) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception e) {
            source.sendError(PvLang.t("pvpocket.command.guard.players_only"));
            return 0;
        }
        SessionManager manager = SessionManager.get();
        if (manager == null) {
            source.sendError(PvLang.t("pvpocket.command.session_not_ready"));
            return 0;
        }
        boolean now = manager.setGuardEnabled(player, enabled);
        player.sendMessage(PvLang.t(now
                        ? "pvpocket.command.guard.on"
                        : "pvpocket.command.guard.off")
                .formatted(now ? Formatting.GREEN : Formatting.YELLOW), false);
        return 1;
    }

    /**
     * Opens the match panel.
     *
     * <p>The panel, not a chat listing: the point of the command is to get into a duel as a
     * spectator, and a chat line cannot be clicked. The server pushes the list as a payload and the
     * client draws it, which is the same split the rule panel uses.</p>
     */
    private static int list(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().listMatches(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int spectateHelp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        source.sendFeedback(() -> PvLang.t("pvpocket.command.spectate.header")
                .formatted(Formatting.AQUA), false);
        helpLine(source, "  /pvpocket list", "pvpocket.command.spectate.list");
        helpLine(source, "  /pvpocket spectate <prefix>", "pvpocket.command.spectate.direct");
        helpLine(source, "  /pvpocket spectate leave", "pvpocket.command.spectate.leave");
        source.sendFeedback(() -> Text.literal("  ").append(PvLang.t("pvpocket.command.spectate.key_hint"))
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int spectateLeave(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().stopSpectating(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int spectate(CommandContext<ServerCommandSource> ctx, String prefix) {
        try {
            SessionManager.get().spectate(ctx.getSource().getPlayerOrThrow(), prefix);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadout(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
            Loadout loadout = SessionManager.get().loadoutFor(player);
            player.sendMessage(PvLang.t("pvpocket.command.loadout.header",
                            loadout.displayName(), loadout.size(), loadout.digest())
                    .formatted(Formatting.AQUA), false);
            for (int i = 0; i < loadout.entries.size(); i++) {
                LoadoutEntry entry = loadout.entries.get(i);
                player.sendMessage(Text.literal("  " + (i + 1) + ". ").formatted(Formatting.GRAY)
                        .append(describe(entry)), false);
            }
            var problems = loadout.validate();
            if (problems.isEmpty()) {
                player.sendMessage(PvLang.t("pvpocket.command.loadout.valid")
                        .formatted(Formatting.GREEN), false);
            } else {
                for (Text problem : problems) {
                    player.sendMessage(Text.literal("  - ").formatted(Formatting.RED)
                            .append(problem.copy().formatted(Formatting.RED)), false);
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
            Loadout loadout = SessionManager.get().loadoutFor(player);
            int given = com.leipishu.pvpocket.loadout.LoadoutBuilder.give(player, loadout, 0);
            player.sendMessage(PvLang.t("pvpocket.command.loadout.given",
                            given, loadout.digest())
                    .formatted(Formatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Opens the loadout editor without going through the rule panel.
     *
     * <p>The panel's loadout button is the normal path; this is the same call for players who closed
     * the panel by accident, and the only way to drive the editor from the console during testing. It
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

    /**
     * {@code /pvpocket loadout accept|decline} — the between-round loadout handshake.
     *
     * <p>A command rather than a panel button because there is no mid-series panel to put a button
     * on: the rules panel is a draft/negotiation screen and opening it mid-series would be a
     * different feature. These two leaves are the first path to this decision, not a second one
     * beside a GUI.</p>
     */
    private static int loadoutAccept(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().acceptLoadoutSwap(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadoutDecline(CommandContext<ServerCommandSource> ctx) {
        try {
            SessionManager.get().declineLoadoutSwap(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadoutReset(CommandContext<ServerCommandSource> ctx) {        try {
            SessionManager.get().resetPersonalLoadout(ctx.getSource().getPlayerOrThrow());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * One-line summary of a loadout row, for the diagnostic command.
     *
     * <p>Built as {@link Text} rather than a string because each part is a translated word —
     * "damage", "unbreakable", the flag names — and the row has to read correctly in whichever
     * language the reader's client is running. Separators stay literal: a space and a multiplication
     * sign mean the same thing everywhere.</p>
     */
    private static MutableText describe(LoadoutEntry entry) {
        MutableText out = entry.kind.labelText().copy();
        if (entry.kind.materialised) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.material", entry.material.labelText()));
        }
        if (!entry.variant.isEmpty()) {
            String key = LoadoutCatalog.labelKeyOf(entry.variant);
            out.append(Text.literal(" ")).append(key == null ? Text.literal(entry.variant) : PvLang.t(key));
        }
        out.append(Text.literal(" ×" + entry.count));
        out.append(PvLang.t("pvpocket.command.loadout.entry.slot", entry.slot));
        if (entry.damageTenths > 0) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.damage", entry.damage()));
        }
        if (entry.armorTenths > 0) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.armor", entry.armor()));
        }
        if (entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.unbreakable"));
        } else if (entry.durability > 0) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.durability", entry.durability));
        }
        if (entry.knockback > 0) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.knockback", entry.knockback));
        }
        for (WeaponFlag flag : WeaponFlag.decode(entry.flags)) {
            out.append(Text.literal(" ")).append(flag.labelText());
        }
        if (entry.kind == ItemKind.BOW
                && !entry.has(WeaponFlag.INFINITY)
                && entry.arrowCount > 0) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.arrows", entry.arrowCount));
        }
        if (entry.kind == ItemKind.POTION) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.potion",
                    entry.durationSec, entry.amplifier + 1));
        }
        return out;
    }
}
