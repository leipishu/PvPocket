package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.preset.Preset;
import com.leipishu.pvpocket.preset.PresetKind;
import com.leipishu.pvpocket.preset.PresetShareRegistry;
import com.leipishu.pvpocket.platform.PvPlatform;
import com.leipishu.pvpocket.session.SessionManager;


import net.minecraft.world.item.SwordItem;
import net.minecraft.server.level.ServerPlayer;

/**
 * Payload registration plus the server-side gate for client combat input.
 *
 * <p>The client expresses intent, never state. Every payload that arrives here is re-validated
 * against the authoritative session data before it may change anything.
 */
public final class PvPocketNetworking {

    private PvPocketNetworking() {
    }


    /**
     * One snapshot per player per second.
     *
     * <p>The home screen sends a request on open, and each answer walks the player's history file.
     * A second request in the same second would repeat that read for a payload the client is about
     * to overwrite.
     */
    private static final java.util.Map<java.util.UUID, Long> HOME_REQUESTS = new java.util.HashMap<>();

    static boolean allowHomeRequest(java.util.UUID uuid) {
        long now = System.currentTimeMillis();
        Long last = HOME_REQUESTS.get(uuid);
        if (last != null && now - last < 1000L) {
            return false;
        }
        HOME_REQUESTS.put(uuid, now);
        // Bounded: one entry per player who has ever opened the screen on this server.
        if (HOME_REQUESTS.size() > 512) {
            HOME_REQUESTS.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
        }
        return true;
    }

    /**
     * Publishes or fetches a preset share code.
     *
     * <p>Validation lives here because this is the boundary where untrusted input arrives: an
     * unknown kind, a blank name and an oversized string are rejected before anything is stored.
     */
    static void onPresetShare(ServerPlayer player, PresetSharePayload payload) {
        if (!PvPlatform.get().canSend(player, PresetShareResultPayload.ID)) {
            // A client without the channel would never see the answer, so there is nothing to do.
            // The mod is mandatory in practice; this is the protocol-level guard.
            return;
        }
        if (payload.action() == PresetSharePayload.ACTION_FETCH) {
            PresetShareRegistry.Shared shared = PresetShareRegistry.fetch(payload.code());
            PvPlatform.get().send(player, shared == null
                    ? PresetShareResultPayload.failure(PresetShareResultPayload.STATUS_UNKNOWN_CODE)
                    : PresetShareResultPayload.fetched(shared.kind().id, shared.name(),
                            shared.encoded(), PresetShareRegistry.normalise(payload.code())));
            return;
        }
        PresetKind kind = PresetKind.byId(payload.kind());
        String name = Preset.cleanName(payload.name());
        if (kind == null
                || !Preset.isValidName(name)
                || payload.encoded().isEmpty()
                || payload.encoded().length() > PresetSharePayload.MAX_ENCODED) {
            PvPlatform.get().send(player,
                    PresetShareResultPayload.failure(PresetShareResultPayload.STATUS_REJECTED));
            return;
        }
        String code = PresetShareRegistry.publish(player.getUUID(), kind, name, payload.encoded());
        if (code == null) {
            PvPlatform.get().send(player,
                    PresetShareResultPayload.failure(PresetShareResultPayload.STATUS_FULL));
            return;
        }
        // A share code has no server-side record, so this line is the only evidence it existed.
        PvPocket.LOGGER.info("Preset code {} for {} ({} / {})", code,
                player.getName().getString(), kind.id, name);
        PvPlatform.get().send(player, PresetShareResultPayload.published(kind.id, name,
                payload.encoded(), code, (int) (PresetShareRegistry.TTL_MS / 1000L)));
    }

    /**
     * Pushes the scoreboard to one client.
     *
     * <p>Silent: the HUD is sent on every round boundary to every participant, and a log line per
     * send would bury the state transitions it sits next to.
     */
    public static void sendHud(ServerPlayer player, MatchHudPayload payload) {
        if (PvPlatform.get().canSend(player, MatchHudPayload.ID)) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Pushes the plot outline to one client, or clears it.
     *
     * <p>Silent like {@link #sendHud}: it is sent at every round boundary for every participant.
     */
    public static void sendBounds(ServerPlayer player, ArenaBoundsPayload payload) {
        if (PvPlatform.get().canSend(player, ArenaBoundsPayload.ID)) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Pushes the preparation screens to one client, or clears them.
     *
     * <p>Silent like {@link #sendHud}: it is sent next to the bounds at every round boundary.
     */
    public static void sendScreens(ServerPlayer player, PrepareScreensPayload payload) {
        if (PvPlatform.get().canSend(player, PrepareScreensPayload.ID)) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Opens the rule panel on the client.
     *
     * <p>Guarded by {@code canSend}: Fabric throws if a payload is sent to a client that never
     * declared the channel, and a vanilla client can still connect. A rule proposal degrades to
     * nothing happening rather than to a server-side exception.
     */
    public static void openRules(ServerPlayer player, RulesOpenPayload payload) {
        boolean canSend = PvPlatform.get().canSend(player, RulesOpenPayload.ID);
        PvPocket.LOGGER.info("Rules panel -> {} (canSend={}, revision={})",
                player.getName().getString(), canSend, payload.revision());
        if (canSend) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Tells a client to drop its rule panel because the negotiation it belongs to is finished.
     *
     * <p>Sent at every terminal transition of a negotiation: accepted, rejected, dismissed or
     * aborted, so a panel cannot outlive its session. The client only closes a panel whose session
     * id matches, so a late packet cannot shut a newer negotiation's panel.
     */
    public static void closeRules(ServerPlayer player, String sessionId) {
        if (PvPlatform.get().canSend(player, RulesClosePayload.ID)) {
            PvPlatform.get().send(player, new RulesClosePayload(sessionId));
        }
    }

    /**
     * Pushes the home screen's snapshot to one client.
     *
     * <p>Silent: this answers a request the player just made, and the useful log line for "the
     * screen is empty" is on the client side.
     */
    public static void sendHomeState(ServerPlayer player, HomeStatePayload payload) {
        if (PvPlatform.get().canSend(player, HomeStatePayload.ID)) {
            PvPlatform.get().send(player, payload);
        }
    }

    /** Opens the loadout editor. Guarded by {@code canSend}, as {@link #openRules}. */
    public static void openLoadout(ServerPlayer player, LoadoutOpenPayload payload) {
        boolean canSend = PvPlatform.get().canSend(player, LoadoutOpenPayload.ID);
        PvPocket.LOGGER.info("Loadout editor -> {} (canSend={}, session={})",
                player.getName().getString(), canSend, payload.sessionId());
        if (canSend) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Opens or refreshes the match list panel on the client.
     *
     * <p>Logged like the other panel openers: "the panel did not appear" cannot be diagnosed from
     * the client, and the answer is either that {@code canSend} was false or that the payload never
     * left the server.
     */
    public static void openMatchList(ServerPlayer player, MatchListPayload payload) {
        boolean canSend = PvPlatform.get().canSend(player, MatchListPayload.ID);
        PvPocket.LOGGER.info("Match list -> {} (canSend={}, {} rows)",
                player.getName().getString(), canSend,
                payload.entries().isEmpty() ? 0 : payload.entries().split("\n").length);
        if (canSend) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Pushes the spectator bar state to one client, or clears it.
     *
     * <p>Silent: it is sent on join, on every camera switch and on every leave.
     */
    public static void sendSpectate(ServerPlayer player, SpectateStatePayload payload) {
        if (PvPlatform.get().canSend(player, SpectateStatePayload.ID)) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Pushes the possessed body's hotbar and vitals to one spectator.
     *
     * <p>Only sent while the camera is attached and only when the content changed; the caller
     * compares signatures first.
     */
    public static void sendPossess(ServerPlayer player, PossessPayload payload) {
        if (PvPlatform.get().canSend(player, PossessPayload.ID)) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Hands one participant the card for a series that just ended.
     *
     * <p>Silent: sent once per player per series, next to the chat line that says the same thing.
     * Whether the card is drawn is the client's decision, behind a settings switch.
     */
    public static void sendSeriesResult(ServerPlayer player, SeriesResultPayload payload) {
        if (PvPlatform.get().canSend(player, SeriesResultPayload.ID)) {
            PvPlatform.get().send(player, payload);
        }
    }

    /**
     * Re-validates a blocking request and pushes the resulting authoritative state back down.
     *
     * <p>Only the client knows whether a mouse button is held, but every reason to refuse is
     * checked here: wrong rule set, no sword in hand, dead, spectator. A refusal is still echoed
     * back so the client mirror cannot drift.
     */
    static void applyBlocking(ServerPlayer player, boolean requested) {
        boolean accepted = requested
                && CombatState.isLegacy(player.getUUID())
                && player.getMainHandItem().getItem() instanceof SwordItem
                && !player.isDeadOrDying()
                && !player.isSpectator();
        setBlocking(player, accepted);
    }

    /** Writes the blocking flag and mirrors it to the client only when it actually changed. */
    public static void setBlocking(ServerPlayer player, boolean blocking) {
        boolean changed = CombatState.isBlocking(player.getUUID()) != blocking;
        CombatState.setBlocking(player.getUUID(), blocking);
        if (changed) {
            // Blocking exists only because the client said so, so "never arrived" and "refused"
            // have to be distinguishable. One line per transition is cheap.
            PvPocket.LOGGER.info("Blocking {} for {}", blocking ? "on" : "off",
                    player.getName().getString());
            sendState(player);
            broadcastBlocking(player, blocking);
        }
    }

    /**
     * Tells every other client that {@code player} started or stopped blocking.
     *
     * <p>The blocker is skipped: their own client learns the flag from {@link #sendState}.
     * {@link #setBlocking} already logs the transition, so this fan-out does not.
     */
    public static void broadcastBlocking(ServerPlayer player, boolean blocking) {
        BlockingStatePayload payload = new BlockingStatePayload(player.getUUID(), blocking);
        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (target != player && PvPlatform.get().canSend(target, BlockingStatePayload.ID)) {
                PvPlatform.get().send(target, payload);
            }
        }
    }

    /**
     * Clears every remote blocking pose for a player whose combat state is being dropped.
     *
     * <p>Called where the server drops combat state wholesale rather than player by player, such as
     * arena reset and shutdown. Without it the client keeps the last pose it saw.
     */
    public static void clearBlocking(ServerPlayer player) {
        broadcastBlocking(player, false);
    }

    /**
     * Sends the current authoritative state unconditionally.
     *
     * <p>Used where the session layer flips the rule set (round start, restore, join), where the
     * client's mirror is known to be stale and a change-gated send could be skipped.
     */
    public static void sendState(ServerPlayer player) {
        if (!PvPlatform.get().canSend(player, CombatStatePayload.ID)) {
            return;
        }
        PvPlatform.get().send(player, new CombatStatePayload(
                CombatState.isLegacy(player.getUUID()),
                CombatState.isBlocking(player.getUUID()),
                CombatState.useGate(player.getUUID()),
                CombatState.useBans(player.getUUID()),
                SessionManager.explosivesAllowedFor(player)));
    }
}
