package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

/**
 * The policy of the duel dimension: a player inside it is a guest with no admin rights.
 *
 * <p>The dimension is called the sandbox in the rules panel and this class is what makes the name
 * true. Two things are refused there, and both exist for the same reason — the outcome of a duel is
 * supposed to be decided by the duel:</p>
 *
 * <ol>
 *   <li><b>Commands.</b> A player standing in the arena may only run {@code /pvpocket}. Everything
 *       else is refused before the command tree ever sees it, which is what makes the refusal
 *       uniform: {@code /gamemode}, {@code /tp}, {@code /give}, {@code /effect} and any command
 *       another mod adds are all blocked by the same rule rather than by a deny-list that has to be
 *       kept up to date.</li>
 *   <li><b>Game mode changes.</b> Refused at the interaction manager, not at the command, so the
 *       ban also covers the paths that never touch a command: another mod calling
 *       {@code changeGameMode} directly, or a client-side shortcut that ends up doing the same.</li>
 * </ol>
 *
 * <p><b>The mod's own changes are exempt</b>, and they have to be: entering a round switches a
 * participant to survival, entering spectator mode switches a watcher to spectator, and leaving
 * puts the old mode back. Those go through {@link #changeGameMode}, which raises an authorisation
 * for the duration of the call. A blanket ban without that exemption would make spectating and
 * round start silently do nothing, which is a far worse failure than the one this class is
 * preventing.</p>
 *
 * <p><b>The operator keeps a way in.</b> The rule is about who asks, not about who is affected: a
 * change requested by a player is refused, a change requested by the server is not. So the console,
 * RCON and command blocks can still run {@code /gamemode} on a player standing in the arena, which
 * is the only way to get somebody out of a state the mod itself failed to restore. A sandbox that
 * nobody can fix from outside is not a sandbox, it is a trap.</p>
 */
public final class Sandbox {

    /** The only command root a player may use inside the arena. */
    public static final String ALLOWED_ROOT = "pvpocket";

    /**
     * Depth of the current authorised game-mode change.
     *
     * <p>A counter rather than a boolean because a nested call is possible: {@code PlayerSnapshot}
     * restoring a game mode can run from inside a session transition that is itself already
     * authorised. Unwinding to zero one level too early would refuse the inner change.</p>
     */
    private static int authorised;

    private Sandbox() {
    }

    /** True when this world is the duel dimension, where the sandbox policy applies. */
    public static boolean isSandbox(World world) {
        return ArenaManager.isArena(world);
    }

    /**
     * Whether this command may run.
     *
     * <p>Sources without a player entity — console, RCON, command blocks, the mod's own scripted
     * commands — are always allowed. The check is on the <em>sender</em>, not on the world, because
     * the whole point is to restrict players rather than to disable the command system in the
     * dimension.</p>
     */
    public static boolean commandAllowed(ServerCommandSource source, String rawCommand) {
        if (source == null || rawCommand == null) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return true;
        }
        if (!isSandbox(player.getWorld())) {
            return true;
        }
        return ALLOWED_ROOT.equals(rootOf(rawCommand));
    }

    /**
     * The command's root literal, with any namespace stripped.
     *
     * <p>Stripping the namespace is what makes {@code /pvpocket:pvpocket list} work: Brigadier
     * accepts a fully qualified root, and a player who types one has not left the allow-list.</p>
     */
    static String rootOf(String rawCommand) {
        String trimmed = rawCommand.trim();
        // The slash is already gone by the time a command reaches the manager, but accepting it
        // costs one branch and removes a whole class of "the rule did not fire" bug reports.
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
     * <p>Every game-mode change the mod makes on purpose has to come through here. A direct
     * {@code player.changeGameMode} from inside the arena is refused by
     * {@code ServerPlayerInteractionManagerMixin}, and the failure is silent — the round would
     * simply start in the wrong mode.</p>
     */
    public static void changeGameMode(ServerPlayerEntity player, GameMode mode) {
        beginAuthorised();
        try {
            player.changeGameMode(mode);
        } finally {
            endAuthorised();
        }
    }

    /**
     * Opens an authorisation scope. Must be paired with {@link #endAuthorised} in a finally block.
     *
     * <p>Used by the command hook to cover the operator: a console or RCON command that changes a
     * game mode has to work, otherwise an operator cannot get a player out of a broken state without
     * first teleporting them out of the dimension. The rule is about players, not about the command
     * system.</p>
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
     * Read by {@code ServerPlayerInteractionManagerMixin}: true while a mode change is authorised.
     *
     * <p>Public only because the mixins live in another package. Not part of the mod's API.</p>
     */
    public static boolean gameModeChangeAllowed() {
        return authorised > 0;
    }

    /** Logged once per refusal so a silently ignored third-party change leaves a trace. */
    public static void logRefusal(ServerPlayerEntity player, GameMode mode) {
        PvPocket.LOGGER.info("Refused game mode change to {} for {} inside the sandbox",
                mode.getName(), player.getName().getString());
    }
}
