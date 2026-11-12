package com.leipishu.pvpocket.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the duel dimension from becoming a place people get stuck in.
 *
 * <p>There are exactly three legitimate reasons to be standing in {@code pvpocket:arena}: you are a
 * participant, you are a spectator, or the server is in the half-second between the two. Everything
 * else — a teleport that landed wrong, a crash that ate a restore, a datapack command, an operator
 * poking at the wrong coordinate — leaves a player in a void world they cannot walk out of, cannot
 * break blocks in, and, if they are in spectator mode, cannot even die out of.</p>
 *
 * <p>{@code HomePositions} already catches the login case. This catches the live one: a periodic
 * scan, a five-second grace period with a visible countdown, then the same teleport back to the
 * position recorded before they entered.</p>
 *
 * <p><b>The grace period is not politeness.</b> A participant's session is registered before the
 * teleport and a spectator's is registered before theirs, so the only way to be seen here is to be
 * genuinely outside both — but the scan runs on the server tick and the client is a tick or two
 * behind, so a single-tick check would fire on a player who is in the middle of a legitimate
 * transition. Five seconds is several orders of magnitude more than that, and it also gives the
 * player time to read the message and react.</p>
 *
 * <p><b>The escape hatch is a per-player flag, not a global switch.</b> An operator inspecting the
 * dimension has to be able to stay; letting them flip a global "do not evict anyone" would be one
 * forgotten command away from disabling the protection for everybody. The flag is persisted, so a
 * restart during the inspection does not silently throw the operator out mid-check.</p>
 */
final class ArenaGuard {

    /** Grace period before a stray player is moved out. */
    private static final int COUNTDOWN_SECONDS = 5;
    private static final long COUNTDOWN_MS = COUNTDOWN_SECONDS * 1000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type FILE_TYPE = new TypeToken<LinkedHashSet<String>>() {
    }.getType();

    /** One player's countdown. Mutable because the announced second is what gets throttled. */
    private static final class Deadline {
        final long at;
        int shown = -1;

        Deadline(long at) {
            this.at = at;
        }
    }

    private final SessionManager sessions;
    private final Path file;
    /** Players allowed to be in the dimension without a match. Stored by uuid string for Gson. */
    private final Set<String> exempt = new LinkedHashSet<>();
    private final Map<UUID, Deadline> pending = new LinkedHashMap<>();

    private ArenaGuard(SessionManager sessions, Path file) {
        this.sessions = sessions;
        this.file = file;
    }

    static ArenaGuard load(SessionManager sessions, MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("pvpocket").resolve("guard.json");
        ArenaGuard guard = new ArenaGuard(sessions, file);
        if (!Files.exists(file)) {
            return guard;
        }
        try {
            Set<String> parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FILE_TYPE);
            if (parsed != null) {
                guard.exempt.addAll(parsed);
            }
        } catch (IOException | RuntimeException e) {
            // Failing to read this file means everybody gets the protection back, which is the safe
            // direction. Refusing to start over it would not be.
            PvPocket.LOGGER.warn("Could not read {}; no guard exemptions loaded", file, e);
        }
        return guard;
    }

    /** True when this player has opted out of the automatic removal. */
    boolean isExempt(UUID uuid) {
        return exempt.contains(uuid.toString());
    }

    /**
     * Turns the automatic removal on or off for one player.
     *
     * <p>Opting out cancels a countdown that is already running, so the button in the message works
     * on the same second it is clicked rather than a few seconds later.</p>
     *
     * @return the new state, i.e. whether the guard is enabled for this player
     */
    boolean setEnabled(UUID uuid, boolean enabled) {
        boolean changed = enabled ? exempt.remove(uuid.toString()) : exempt.add(uuid.toString());
        if (!enabled) {
            pending.remove(uuid);
        }
        if (changed) {
            save();
        }
        return enabled;
    }

    /** How many players are currently exempt, for diagnostics. */
    int exemptCount() {
        return exempt.size();
    }

    // ------------------------------------------------------------------ the scan

    /**
     * Per-tick scan. Cheap in the common case: the player list is short, and the first test for
     * almost everybody is "am I even in the arena dimension".
     */
    void tick() {
        MinecraftServer server = sessions.server();
        long now = System.currentTimeMillis();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            if (!shouldLeave(player, uuid)) {
                // Covers every legitimate exit: joined a session, started spectating, left the
                // dimension, died, or flipped the flag. In all of them the countdown simply stops.
                pending.remove(uuid);
                continue;
            }
            Deadline deadline = pending.get(uuid);
            if (deadline == null) {
                pending.put(uuid, new Deadline(now + COUNTDOWN_MS));
                announce(player);
                continue;
            }
            long left = deadline.at - now;
            if (left <= 0) {
                pending.remove(uuid);
                sessions.homes().evict(player);
                continue;
            }
            int second = (int) Math.ceil(left / 1000.0D);
            if (second != deadline.shown) {
                deadline.shown = second;
                // Action bar, not chat: five chat lines in five seconds pushes everything else off
                // the screen, and the countdown is not worth that.
                player.displayClientMessage(PvLang.t("pvpocket.msg.guard.countdown", second)
                        .withStyle(ChatFormatting.YELLOW), true);
            }
        }

        // Disconnects are not visited by the loop above, so their countdowns would otherwise sit in
        // the map until the player happened to rejoin. Only paid when something is actually pending.
        if (!pending.isEmpty()) {
            Iterator<UUID> it = pending.keySet().iterator();
            while (it.hasNext()) {
                if (server.getPlayerList().getPlayer(it.next()) == null) {
                    it.remove();
                }
            }
        }
    }

    /** The three legitimate reasons to be in the dimension, plus the two reasons to wait. */
    private boolean shouldLeave(ServerPlayer player, UUID uuid) {
        if (!ArenaManager.isArena(player.level())) {
            return false;
        }
        // A dead player is on the respawn screen. Moving them would teleport a corpse; they get
        // caught on the next scan after they respawn, wherever that puts them.
        if (!player.isAlive()) {
            return false;
        }
        if (sessions.sessionOf(uuid) != null) {
            return false;
        }
        if (sessions.isSpectating(uuid)) {
            return false;
        }
        return !isExempt(uuid);
    }

    /**
     * The one chat message, with the button that cancels the removal.
     *
     * <p>The button runs the same command an operator would type, rather than a bespoke packet: a
     * click event that dispatches a command goes through the normal permission check and the normal
     * sandbox allow-list (it is {@code /pvpocket}, so it is allowed inside the dimension), and there
     * is no second code path that could drift from the command's behaviour.</p>
     *
     * <p>Only drawn for operators, because the command behind it is operator-only. A button that
     * answers a click with "unknown command" is worse than no button: it tells a player there is an
     * escape and then denies it. Everyone else just gets the sentence, which is accurate — there is
     * nothing they can do about it and nothing they should be doing in there.</p>
     */
    private void announce(ServerPlayer player) {
        player.displayClientMessage(PvLang.t("pvpocket.msg.guard.stray")
                .withStyle(ChatFormatting.YELLOW)
                .append(PvLang.t("pvpocket.msg.guard.will_move", COUNTDOWN_SECONDS)
                        .withStyle(ChatFormatting.YELLOW)), false);

        if (!player.hasPermissions(2)) {
            return;
        }
        MutableComponent button = PvLang.t("pvpocket.msg.guard.button").setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/pvpocket guard false"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        PvLang.t("pvpocket.msg.guard.button_hover"))));
        player.displayClientMessage(button.append(PvLang.t("pvpocket.msg.guard.button_hint")
                .withStyle(ChatFormatting.DARK_GRAY)), false);
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        try {
            PvPocketStorage.writeAtomically(file,
                    GSON.toJson(exempt, FILE_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            PvPocket.LOGGER.error("Could not write {}", file, e);
        }
    }
}
