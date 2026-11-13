package com.leipishu.pvpocket.command;

import com.leipishu.pvpocket.compat.PvCompat;

import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutCatalog;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.loadout.TridentStyle;
import com.leipishu.pvpocket.loadout.WeaponFlag;
import com.leipishu.pvpocket.session.SessionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Command surface for the mod. The rule GUI covers most of the same ground; these commands remain
 * the scriptable, testable path.
 *
 * <p>Command syntax is never translated. {@code /pvpocket}, its literals, and the argument
 * placeholders in the help text ({@code <player>}, {@code <true|false>}) are tokens the dispatcher
 * parses, so they are identical in every language file. Only the description beside them changes
 * with the reader's language.
 */
public final class PvPocketCommands {

    /**
     * Column the descriptions start at, in characters.
     *
     * <p>The font is proportional, so this lines the help text up approximately. The usage
     * column is a fixed ASCII token, which is what makes it the part that can be padded.
     */
    private static final int USAGE_COLUMN = 34;

    private PvPocketCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("pvpocket")
                .executes(PvPocketCommands::help);

        // No arguments: Brigadier has no grammar for "these two on one side and those two on the
        // other", so the roster is built in the panel and the sides are explicit.
        root.then(Commands.literal("duel").executes(PvPocketCommands::duel));

        // Accept, propose and deny live on the rule panel only; the server opens it wherever one of
        // them could be pressed.
        root.then(Commands.literal("help").executes(PvPocketCommands::help));
        root.then(Commands.literal("review").executes(PvPocketCommands::review));
        root.then(Commands.literal("surrender").executes(PvPocketCommands::surrender));
        root.then(Commands.literal("status").executes(PvPocketCommands::status));
        root.then(Commands.literal("arena")
                .executes(PvPocketCommands::arena)
                // Operator-only: this destroys terrain and ends other people's matches.
                .then(Commands.literal("reset")
                        .requires(source -> PvCompat.hasPermission(source, 3))
                        .executes(PvPocketCommands::arenaReset))
                // Operator-only: it sets how many duels the server can run and writes the saved
                // layout.
                .then(Commands.literal("copy")
                        .requires(source -> PvCompat.hasPermission(source, 3))
                        .then(Commands.argument("map", StringArgumentType.word())
                                .suggests(PvPocketCommands::suggestMaps)
                                .executes(ctx -> arenaCopy(ctx, 1))
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(1, 8))
                                        .executes(ctx -> arenaCopy(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                // Operator-only, as copy: it writes the saved layout and erases a stamped arena.
                .then(Commands.literal("remove")
                        .requires(source -> PvCompat.hasPermission(source, 3))
                        .then(Commands.argument("map", StringArgumentType.word())
                                .suggests(PvPocketCommands::suggestMaps)
                                .executes(ctx -> arenaRemove(ctx, 1))
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(1, 8))
                                        .executes(ctx -> arenaRemove(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count")))))));
        root.then(Commands.literal("guard")
                // Operator-only: the flag keeps a player in the sandbox.
                .requires(source -> PvCompat.hasPermission(source, 2))
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> guard(ctx, BoolArgumentType.getBool(ctx, "enabled")))));
        root.then(Commands.literal("list").executes(PvPocketCommands::list));
        root.then(Commands.literal("spectate")
                .executes(PvPocketCommands::spectateHelp)
                .then(Commands.literal("leave").executes(PvPocketCommands::spectateLeave))
                .then(Commands.argument("session", StringArgumentType.word())
                        .executes(ctx -> spectate(ctx, StringArgumentType.getString(ctx, "session")))));
        root.then(Commands.literal("loadout")
                .executes(PvPocketCommands::loadout)
                .then(Commands.literal("give").executes(PvPocketCommands::loadoutGive))
                .then(Commands.literal("edit").executes(PvPocketCommands::loadoutEdit))
                .then(Commands.literal("accept").executes(PvPocketCommands::loadoutAccept))
                .then(Commands.literal("decline").executes(PvPocketCommands::loadoutDecline))
                .then(Commands.literal("reset").executes(PvPocketCommands::loadoutReset)));

        dispatcher.register(root);
    }

    /**
     * Writes one help line: the usage as a literal, the description from the language file.
     *
     * <p>Split so that a translator cannot translate {@code /pvpocket spectate leave} into
     * something the dispatcher does not accept.
     */
    private static void helpLine(CommandSourceStack source, String usage, String descriptionKey) {
        String pad = " ".repeat(Math.max(2, USAGE_COLUMN - usage.length()));
        source.sendSuccess(() -> Component.literal(usage + pad).withStyle(ChatFormatting.GRAY)
                .append(PvLang.t(descriptionKey)), false);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> PvLang.t("pvpocket.command.help.header", "/pvpocket help")
                .withStyle(ChatFormatting.AQUA), false);
        helpLine(source, "/pvpocket duel", "pvpocket.command.help.duel");
        helpLine(source, "/pvpocket review", "pvpocket.command.help.review");
        helpLine(source, "/pvpocket surrender", "pvpocket.command.help.surrender");
        helpLine(source, "/pvpocket status", "pvpocket.command.help.status");
        helpLine(source, "/pvpocket arena", "pvpocket.command.help.arena");
        helpLine(source, "/pvpocket arena reset", "pvpocket.command.help.arena_reset");
        helpLine(source, "/pvpocket arena copy <map> [count]", "pvpocket.command.help.arena_copy");
        helpLine(source, "/pvpocket arena remove <map> [count]",
                "pvpocket.command.help.arena_remove");
        helpLine(source, "/pvpocket guard <true|false>", "pvpocket.command.help.guard");
        helpLine(source, "/pvpocket list", "pvpocket.command.help.list");
        helpLine(source, "/pvpocket spectate <prefix>", "pvpocket.command.help.spectate");
        helpLine(source, "/pvpocket spectate leave", "pvpocket.command.help.spectate_leave");
        helpLine(source, "/pvpocket loadout [give|edit|reset]", "pvpocket.command.help.loadout");
        helpLine(source, "/pvpocket loadout [accept|decline]", "pvpocket.command.help.loadout_swap");
        source.sendSuccess(() -> PvLang.t("pvpocket.command.help.footer")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int duel(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer self = ctx.getSource().getPlayerOrException();
            SessionManager.get().openRoster(self);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int review(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().review(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int surrender(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().surrender(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().status(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int arena(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var gallery = com.leipishu.pvpocket.arena.ArenaManager.gallery();
        var leased = com.leipishu.pvpocket.arena.ArenaManager.leased();

        source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.header",
                        gallery.size(), com.leipishu.pvpocket.arena.ArenaManager.slotCount(),
                        leased.size())
                .withStyle(ChatFormatting.AQUA), false);

        // Every map sits in its own cell of the dimension, so selecting one is a teleport to fixed
        // coordinates rather than a paste at the origin.
        if (gallery.isEmpty()) {
            source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.empty")
                    .withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.regions")
                    .withStyle(ChatFormatting.DARK_AQUA), false);
            for (var region : gallery) {
                source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.region_line",
                                region.id, region.map.labelText(),
                                region.sizeX, region.sizeY, region.sizeZ,
                                region.minX, region.minZ,
                                region.centerX(), region.centerZ())
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }

        if (leased.isEmpty()) {
            source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.none_active")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.active")
                .withStyle(ChatFormatting.DARK_AQUA), false);
        for (var entry : leased) {
            var region = entry.getValue();
            source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.leased_line",
                            region.id, region.map.labelText(),
                            region.minX, region.minZ,
                            region.changeCount(),
                            PvLang.t(region.protectTerrain
                                    ? "pvpocket.value.on" : "pvpocket.value.off"),
                            entry.getKey().substring(0, 8))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    /**
     * Suggests map ids for {@code /pvpocket arena copy}.
     *
     * <p>Ids rather than display names: the argument is a token the dispatcher matches, and a
     * translated name would be a different token in every language.
     */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestMaps(CommandContext<CommandSourceStack> ctx,
                        com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String rest = builder.getRemainingLowerCase();
        for (var map : com.leipishu.pvpocket.arena.ArenaMaps.ALL) {
            if (map.id.toLowerCase(java.util.Locale.ROOT).startsWith(rest)) {
                builder.suggest(map.id);
            }
        }
        return builder.buildFuture();
    }

    /**
     * Gives a map more cells: {@code /pvpocket arena copy <map> [count]}.
     *
     * <p>A cell is a whole arena of the dimension, and a map owns only as many as it was given.
     * Copies are made here rather than on demand because the number of concurrent duels is a
     * capacity decision. Each copy is a real cell with its own coordinates, recorded in
     * {@code pvpocket/arena.json}, so it survives a restart.
     */
    private static int arenaCopy(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "map");
        com.leipishu.pvpocket.arena.ArenaMap map = null;
        for (var candidate : com.leipishu.pvpocket.arena.ArenaMaps.ALL) {
            if (candidate.id.equalsIgnoreCase(id)) {
                map = candidate;
                break;
            }
        }
        if (map == null) {
            source.sendFailure(PvLang.t("pvpocket.command.arena.copy_unknown", id));
            return 0;
        }
        // Loop variables are not effectively final, and the feedback below is a lazy supplier.
        final com.leipishu.pvpocket.arena.ArenaMap chosen = map;
        int made = 0;
        for (int i = 0; i < count; i++) {
            int slot = com.leipishu.pvpocket.arena.ArenaManager.addCopy(source.getServer(), chosen);
            if (slot < 0) {
                break;
            }
            made++;
            final int[] centre = com.leipishu.pvpocket.arena.ArenaManager.cellCentre(slot);
            final var region = com.leipishu.pvpocket.arena.ArenaManager.regionAtSlot(slot);
            // Two lines, not one: "reserved" and "built" are different promises. A flat copy has no
            // blocks until a duel asks for a size.
            if (region == null) {
                source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.copy_reserved",
                        chosen.labelText(), centre[0], centre[1]), false);
            } else {
                source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.copy_built",
                        chosen.labelText(), region.id, centre[0], centre[1]), false);
            }
        }
        if (made == 0) {
            source.sendFailure(PvLang.t("pvpocket.command.arena.copy_full",
                    com.leipishu.pvpocket.arena.ArenaManager.slotCount()));
            return 0;
        }
        return made;
    }

    /**
     * Takes cells away from a map: {@code /pvpocket arena remove <map> [count]}.
     *
     * <p>The inverse of {@code copy}. There is no lower bound: a map can be taken down to no cells,
     * at which point the rule panel stops offering it.
     */
    private static int arenaRemove(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "map");
        com.leipishu.pvpocket.arena.ArenaMap map = null;
        for (var candidate : com.leipishu.pvpocket.arena.ArenaMaps.ALL) {
            if (candidate.id.equalsIgnoreCase(id)) {
                map = candidate;
                break;
            }
        }
        if (map == null) {
            source.sendFailure(PvLang.t("pvpocket.command.arena.copy_unknown", id));
            return 0;
        }
        final com.leipishu.pvpocket.arena.ArenaMap chosen = map;
        int gone = 0;
        int last = com.leipishu.pvpocket.arena.ArenaManager.REMOVE_NONE;
        for (int i = 0; i < count; i++) {
            last = com.leipishu.pvpocket.arena.ArenaManager.removeCopy(source.getServer(), chosen);
            if (last < 0) {
                break;
            }
            gone++;
            final int left = com.leipishu.pvpocket.arena.ArenaManager.copiesOf(chosen);
            final int cell = last;
            source.sendSuccess(() -> PvLang.t("pvpocket.command.arena.remove_done",
                    chosen.labelText(), cell, left), false);
        }
        if (gone == 0) {
            // Two refusals, two sentences: "it is in use" is a wait, "there is nothing left" is a
            // statement about the map.
            if (last == com.leipishu.pvpocket.arena.ArenaManager.REMOVE_BUSY) {
                source.sendFailure(PvLang.t("pvpocket.command.arena.remove_busy", chosen.labelText()));
            } else {
                source.sendFailure(PvLang.t("pvpocket.command.arena.remove_none", chosen.labelText()));
            }
            return 0;
        }
        if (com.leipishu.pvpocket.arena.ArenaManager.copiesOf(chosen) == 0) {
            MutableComponent line = PvLang.t("pvpocket.command.arena.remove_empty", chosen.labelText())
                    .withStyle(ChatFormatting.AQUA);
            source.sendSuccess(() -> line, false);
        }
        return gone;
    }

    /**
     * Hard reset of the duel dimension.
     *
     * <p>Announced to everyone, not just the operator: the command ends other players' matches and
     * then freezes the world for a few seconds while the gallery re-stamps.
     */
    private static int arenaReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        SessionManager manager = SessionManager.get();
        if (manager == null) {
            source.sendFailure(PvLang.t("pvpocket.command.session_not_ready"));
            return 0;
        }
        int ended = manager.resetArena();
        if (ended < 0) {
            source.sendFailure(PvLang.t("pvpocket.command.arena.rebuilding"));
            return 0;
        }
        MutableComponent line = PvLang.t("pvpocket.command.arena.reset_done", ended)
                .withStyle(ChatFormatting.AQUA);
        source.sendSuccess(() -> line, true);
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            // The operator already got it through sendFeedback; a second copy reads as two events.
            if (player != source.getEntity()) {
                player.displayClientMessage(line, false);
            }
        }
        return ended;
    }

    /**
     * Turns the automatic removal of stray players on or off for the calling player.
     *
     * <p>Self-targeting: the flag is a permission to look around the sandbox, and a command that
     * could hand it to somebody else would let a player park there indefinitely.
     */
    private static int guard(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(PvLang.t("pvpocket.command.guard.players_only"));
            return 0;
        }
        SessionManager manager = SessionManager.get();
        if (manager == null) {
            source.sendFailure(PvLang.t("pvpocket.command.session_not_ready"));
            return 0;
        }
        boolean now = manager.setGuardEnabled(player, enabled);
        player.displayClientMessage(PvLang.t(now
                        ? "pvpocket.command.guard.on"
                        : "pvpocket.command.guard.off")
                .withStyle(now ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return 1;
    }

    /**
     * Opens the match panel.
     *
     * <p>The panel rather than a chat listing: the command exists to get into a duel as a
     * spectator, and a chat line cannot be clicked. The server pushes the list as a payload and
     * the client draws it.
     */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().listMatches(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int spectateHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> PvLang.t("pvpocket.command.spectate.header")
                .withStyle(ChatFormatting.AQUA), false);
        helpLine(source, "  /pvpocket list", "pvpocket.command.spectate.list");
        helpLine(source, "  /pvpocket spectate <prefix>", "pvpocket.command.spectate.direct");
        helpLine(source, "  /pvpocket spectate leave", "pvpocket.command.spectate.leave");
        source.sendSuccess(() -> Component.literal("  ").append(PvLang.t("pvpocket.command.spectate.key_hint"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int spectateLeave(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().stopSpectating(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int spectate(CommandContext<CommandSourceStack> ctx, String prefix) {
        try {
            SessionManager.get().spectate(ctx.getSource().getPlayerOrException(), prefix);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadout(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            Loadout loadout = SessionManager.get().loadoutFor(player);
            player.displayClientMessage(PvLang.t("pvpocket.command.loadout.header",
                            loadout.displayName(), loadout.size(), loadout.digest())
                    .withStyle(ChatFormatting.AQUA), false);
            for (int i = 0; i < loadout.entries.size(); i++) {
                LoadoutEntry entry = loadout.entries.get(i);
                player.displayClientMessage(Component.literal("  " + (i + 1) + ". ").withStyle(ChatFormatting.GRAY)
                        .append(describe(entry)), false);
            }
            var problems = loadout.validate();
            if (problems.isEmpty()) {
                player.displayClientMessage(PvLang.t("pvpocket.command.loadout.valid")
                        .withStyle(ChatFormatting.GREEN), false);
            } else {
                for (Component problem : problems) {
                    player.displayClientMessage(Component.literal("  - ").withStyle(ChatFormatting.RED)
                            .append(problem.copy().withStyle(ChatFormatting.RED)), false);
                }
            }
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadoutGive(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            Loadout loadout = SessionManager.get().loadoutFor(player);
            int given = com.leipishu.pvpocket.loadout.LoadoutBuilder.give(player, loadout, 0);
            player.displayClientMessage(PvLang.t("pvpocket.command.loadout.given",
                            given, loadout.digest())
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Opens the loadout editor without going through the rule panel.
     *
     * <p>The panel's loadout button is the normal path; this is the same call for players who
     * closed the panel, and the only way to drive the editor from the console during testing.
     * It works with no duel open.
     */
    private static int loadoutEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().onLoadoutRequest(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Accepts or declines the between-round loadout swap.
     *
     * <p>A command rather than a panel button because there is no mid-series panel to put a button
     * on: the rules panel is a draft and negotiation screen.
     */
    private static int loadoutAccept(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().acceptLoadoutSwap(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadoutDecline(CommandContext<CommandSourceStack> ctx) {
        try {
            SessionManager.get().declineLoadoutSwap(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int loadoutReset(CommandContext<CommandSourceStack> ctx) {        try {
            SessionManager.get().resetPersonalLoadout(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * One-line summary of a loadout row, for the diagnostic command.
     *
     * <p>Built as a component rather than a string because each part is a translated word, so the
     * row reads correctly in the reader's language. Separators stay literal.
     */
    private static MutableComponent describe(LoadoutEntry entry) {
        MutableComponent out = entry.kind.labelText().copy();
        if (entry.kind.materialised) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.material", entry.material.labelText()));
        }
        if (!entry.variant.isEmpty()) {
            String key = LoadoutCatalog.labelKeyOf(entry.variant);
            out.append(Component.literal(" ")).append(key == null ? Component.literal(entry.variant) : PvLang.t(key));
        }
        out.append(Component.literal(" ×" + entry.count));
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
            out.append(Component.literal(" ")).append(flag.labelText());
        }
        if (entry.kind == ItemKind.TRIDENT && entry.tridentStyle != TridentStyle.NONE) {
            out.append(Component.literal(" ")).append(entry.tridentStyle.labelText());
        }
        if (entry.kind == ItemKind.FIREWORK_ROCKET) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.firework_flight", entry.fireworkFlight));
        }
        // The companion stack is named per kind rather than as a generic "companion", so the line
        // says which item it is.
        if (entry.kind.hasCompanion() && entry.companionCount > 0
                && !(entry.kind == ItemKind.BOW && entry.has(WeaponFlag.INFINITY))) {
            String key = switch (entry.kind) {
                case END_CRYSTAL -> "pvpocket.command.loadout.entry.obsidian";
                case TNT -> "pvpocket.command.loadout.entry.flint";
                default -> "pvpocket.command.loadout.entry.arrows";
            };
            out.append(PvLang.t(key, entry.companionCount));
        }
        if (entry.kind == ItemKind.POTION) {
            out.append(PvLang.t("pvpocket.command.loadout.entry.potion",
                    entry.durationSec, entry.amplifier + 1));
        }
        return out;
    }
}
