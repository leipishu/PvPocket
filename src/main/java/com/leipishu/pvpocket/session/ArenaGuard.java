package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.compat.PvCompat;

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
 * Removes players who stand in {@code pvpocket:arena} without a session or spectator registration.
 *
 * <p>A stray player gets a five-second countdown, then a teleport back to the position recorded
 * before they entered. A persisted per-player exemption flag keeps an operator in place.
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
            // An unreadable file loads no exemptions, which fails toward protecting players.
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
     * <p>Disabling cancels a countdown that is already running.
     *
     * @return whether the guard is enabled for this player after the call
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
     * Per-tick scan. Most players fail the arena dimension test first, so the common case is cheap.
     */
    void tick() {
        MinecraftServer server = sessions.server();
        long now = System.currentTimeMillis();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            if (!shouldLeave(player, uuid)) {
                // Legitimate exits stop the countdown; see shouldLeave.
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
                // Action bar rather than chat, so five messages do not flood the chat.
                player.displayClientMessage(PvLang.t("pvpocket.msg.guard.countdown", second)
                        .withStyle(ChatFormatting.YELLOW), true);
            }
        }

        // The loop above skips disconnected players, so drop their countdowns here.
        if (!pending.isEmpty()) {
            Iterator<UUID> it = pending.keySet().iterator();
            while (it.hasNext()) {
                if (server.getPlayerList().getPlayer(it.next()) == null) {
                    it.remove();
                }
            }
        }
    }

    /** True when the player is in the arena with no session, spectator slot or exemption. */
    private boolean shouldLeave(ServerPlayer player, UUID uuid) {
        if (!ArenaManager.isArena(player.level())) {
            return false;
        }
        // A dead player is on the respawn screen; the next scan catches them after respawn.
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
     * Sends the stray-player message with the button that cancels the removal.
     *
     * <p>The button dispatches {@code /pvpocket guard false} as a click event, so it goes through
     * the normal permission check and command handling.
     *
     * <p>Only operators get the button, because the command behind it is operator-only.
     */
    private void announce(ServerPlayer player) {
        player.displayClientMessage(PvLang.t("pvpocket.msg.guard.stray")
                .withStyle(ChatFormatting.YELLOW)
                .append(PvLang.t("pvpocket.msg.guard.will_move", COUNTDOWN_SECONDS)
                        .withStyle(ChatFormatting.YELLOW)), false);

        if (!PvCompat.hasPermission(player, 2)) {
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
