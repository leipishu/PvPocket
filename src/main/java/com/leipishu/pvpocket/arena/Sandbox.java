package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * Sandbox policy of the duel dimension: a player inside it is a guest with no admin rights.
 *
 * <p>Two things are refused inside the arena. A player may only run {@code /pvpocket}, checked
 * before the command tree sees the command. Game mode changes requested by a player are refused at
 * the interaction manager, which also covers direct calls that never touch a command.
 *
 * <p>Changes made by the server are allowed: sources without a player entity (console, RCON,
 * command blocks) pass, and the mod's own changes go through {@link #changeGameMode}, which raises
 * an authorisation for the duration of the call.
 */
public final class Sandbox {

    /** The only command root a player may use inside the arena. */
    public static final String ALLOWED_ROOT = "pvpocket";

    /**
     * Depth of the current authorised game-mode change.
     *
     * <p>A counter rather than a boolean so a nested call, such as a snapshot restore inside a
     * session transition, does not unwind the outer scope early.
     */
    private static int authorised;

    private Sandbox() {
    }

    /** True when this world is the duel dimension, where the sandbox policy applies. */
    public static boolean isSandbox(Level world) {
        return ArenaManager.isArena(world);
    }

    /**
     * Whether this command may run.
     *
     * <p>Sources without a player entity, such as console, RCON, command blocks and the mod's own
     * scripted commands, are always allowed, as are null arguments. The check is on the sender,
     * not on the world.
     */
    public static boolean commandAllowed(CommandSourceStack source, String rawCommand) {
        if (source == null || rawCommand == null) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return true;
        }
        if (!isSandbox(player.level())) {
            return true;
        }
        return ALLOWED_ROOT.equals(rootOf(rawCommand));
    }

    /**
     * The command's root literal, with any namespace stripped.
     *
     * <p>Stripping the namespace lets {@code /pvpocket:pvpocket list} match the allow-list.
     */
    static String rootOf(String rawCommand) {
        String trimmed = rawCommand.trim();
        // The slash is already gone by the time a command reaches the manager. Accepting it anyway
        // costs one branch and covers callers that pass the raw typed text.
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        String root = trimmed.substring(0, end);
        int colon = root.indexOf(':');
        return colon < 0 ? root : root.substring(colon + 1);
    }

    /**
     * Changes a player's game mode with the sandbox rule lifted for the duration of the call.
     *
     * <p>Call this instead of {@code player.setGameMode} while the player is in the arena. A direct
     * call is refused by {@code ServerPlayerInteractionManagerMixin} and fails silently.
     */
    public static void changeGameMode(ServerPlayer player, GameType mode) {
        beginAuthorised();
        try {
            player.setGameMode(mode);
        } finally {
            endAuthorised();
        }
    }

    /**
     * Opens an authorisation scope. Pair with {@link #endAuthorised} in a finally block.
     *
     * <p>Used by the command hook so a console or RCON command that changes a game mode still
     * works on a player standing in the arena.
     */
    public static void beginAuthorised() {
        authorised++;
    }

    /** Closes an authorisation scope opened by {@link #beginAuthorised}. */
    public static void endAuthorised() {
        if (authorised > 0) {
            authorised--;
        }
    }

    /**
     * True while a game mode change is authorised.
     *
     * <p>Public only because the mixins live in another package. Not part of the mod's API.
     */
    public static boolean gameModeChangeAllowed() {
        return authorised > 0;
    }

    /** Logs one line per refused change, so an ignored third-party change leaves a trace. */
    public static void logRefusal(ServerPlayer player, GameType mode) {
        PvPocket.LOGGER.info("Refused game mode change to {} for {} inside the sandbox",
                mode.getShortDisplayName(), player.getName().getString());
    }
}
