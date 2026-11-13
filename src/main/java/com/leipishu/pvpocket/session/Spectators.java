package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.compat.PvCompat;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.ArenaMap;
import com.leipishu.pvpocket.arena.ArenaRegion;
import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.MatchListPayload;
import com.leipishu.pvpocket.network.PossessPayload;
import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.network.SpectateStatePayload;
import com.leipishu.pvpocket.rules.DuelRules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spectators: who is watching which duel, and how to put them back afterwards.
 *
 * <p>A spectator is not part of a session, so only three things are changed and restored: the game
 * mode, the dimension and the position. The camera is attached to a participant with
 * {@code setCameraEntity}, as the vanilla {@code /spectate} command does.
 *
 * <p>{@link SpectateStatePayload} carries the participant names so the cycle key can show what it
 * will switch to.
 */
final class Spectators {

    /**
     * One spectator's saved context.
     *
     * <p>The world is stored as a registry key rather than as a {@link ServerWorld} instance, so a
     * dimension unload and reload does not leave a stale world object behind.
     */
    private static final class Watching {
        final String sessionId;
        final ResourceKey<Level> world;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final GameType mode;
        int target;
        /**
         * Last hotbar/vitals signature sent for {@link #target}, so the possession HUD is only
         * re-sent when something on it actually moved. Empty forces the next tick to send.
         */
        String lastSignature = "";
        /**
         * Whether the "you have reached the edge" line has already been shown.
         *
         * <p>The flight box is a rule rather than geometry, and the boundary curtain is drawn
         * twelve blocks further in. One line per contact; the flag clears once the spectator has
         * flown a few blocks back into the box.
         */
        boolean limitWarned;

        Watching(String sessionId, ServerPlayer player) {
            this.sessionId = sessionId;
            this.world = player.level().dimension();
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.mode = player.gameMode.getGameModeForPlayer();
        }
    }

    private final SessionManager sessions;
    private final Map<UUID, Watching> watching = new LinkedHashMap<>();

    Spectators(SessionManager sessions) {
        this.sessions = sessions;
    }

    private MinecraftServer server() {
        return sessions.server();
    }

    boolean isWatching(UUID uuid) {
        return watching.containsKey(uuid);
    }

    int count() {
        return watching.size();
    }

    /** Online spectators of one session, for the HUD and bounds broadcasts. */
    List<ServerPlayer> watchersOf(String sessionId) {
        List<ServerPlayer> out = new ArrayList<>();
        for (Map.Entry<UUID, Watching> entry : watching.entrySet()) {
            if (!entry.getValue().sessionId.equals(sessionId)) {
                continue;
            }
            ServerPlayer player = server().getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                out.add(player);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ match list

    /**
     * Builds and sends the {@code /pvpocket list} panel.
     *
     * <p>Only {@code ROUND_READY} and {@code ROUND_ACTIVE} sessions are listed; a negotiation has
     * nothing to watch yet.
     */
    void openList(ServerPlayer player) {
        PvPocketNetworking.openMatchList(player, new MatchListPayload(rowsFor(player)));
    }

    /**
     * The list as one encoded string, shared by the list panel and the home screen.
     *
     * <p>Both views read the same builder, so a field added here reaches the home screen too.
     */
    String rowsFor(ServerPlayer player) {
        StringBuilder sb = new StringBuilder();
        for (DuelSession session : sessions.allSessions()) {
            if (session.stage != SessionStage.ROUND_READY
                    && session.stage != SessionStage.ROUND_ACTIVE) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            int flags = 0;
            if (DuelRules.decode(session.rules).flag("b.spectator")) {
                flags |= MatchListPayload.FLAG_SPECTATABLE;
            }
            if (session.isParticipant(player.getStringUUID())) {
                flags |= MatchListPayload.FLAG_PARTICIPANT;
            }
            if (isWatching(player.getUUID())
                    && session.id.equals(watching.get(player.getUUID()).sessionId)) {
                flags |= MatchListPayload.FLAG_WATCHING;
            }
            sb.append(session.id).append('|')
                    .append(mapId(session)).append('|')
                    .append(session.stage.name()).append('|')
                    .append(session.round).append('|')
                    .append(session.maxRounds).append('|')
                    .append(leftScore(session)).append(':').append(rightScore(session)).append('|')
                    .append(flags).append('|')
                    .append(watchersOf(session.id).size()).append('|')
                    .append(playersField(session));
        }
        return sb.toString();
    }

    /**
     * The map's id, which the client turns into {@code pvpocket.map.<id>}.
     *
     * <p>Read from the leased region, falling back to the rule when no region is leased, which
     * happens between rounds. An id rather than a name because the client's language is unknown.
     */
    static String mapId(DuelSession session) {
        ArenaRegion region = ArenaManager.region(session.id);
        if (region != null) {
            return region.map.id;
        }
        ArenaMap map = com.leipishu.pvpocket.arena.ArenaMaps.byIndex(
                DuelRules.decode(session.rules).get("e.template"));
        return map.id;
    }

    /**
     * Rounds won by the sides the spectator list puts on the left.
     *
     * <p>Summed over even team indices because the list groups sides into two columns. In a
     * four-way match columns 0+2 face columns 1+3; the grouping is for display only.
     */
    private static int leftScore(DuelSession session) {
        int sum = 0;
        for (int team = 0; team < Math.max(1, session.teamCount); team += 2) {
            sum += session.wins(team);
        }
        return sum;
    }

    private static int rightScore(DuelSession session) {
        int sum = 0;
        for (int team = 1; team < Math.max(1, session.teamCount); team += 2) {
            sum += session.wins(team);
        }
        return sum;
    }

    private static String playersField(DuelSession session) {
        StringBuilder sb = new StringBuilder();
        for (Participant p : session.participants) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.name).append(':').append(p.uuid);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ join / leave

    /**
     * Puts a player into the arena as a spectator.
     *
     * <p>Every reason to refuse is checked here, since the client that sends the request is not
     * trusted. A refusal is a chat line, not a silent failure.
     */
    boolean join(ServerPlayer player, String sessionId) {
        DuelSession session = sessions.byId(sessionId);
        if (session == null) {
            fail(player, "pvpocket.msg.spectate.gone");
            return false;
        }
        if (session.stage != SessionStage.ROUND_READY && session.stage != SessionStage.ROUND_ACTIVE) {
            fail(player, "pvpocket.msg.spectate.not_running");
            return false;
        }
        if (!DuelRules.decode(session.rules).flag("b.spectator")) {
            fail(player, "pvpocket.msg.spectate.disabled");
            return false;
        }
        if (session.isParticipant(player.getStringUUID())) {
            fail(player, "pvpocket.msg.spectate.is_participant");
            return false;
        }
        if (sessions.sessionOf(player.getUUID()) != null) {
            fail(player, "pvpocket.msg.spectate.in_duel");
            return false;
        }
        ArenaRegion region = ArenaManager.region(session.id);
        ServerLevel arena = ArenaManager.world(server());
        if (region == null || arena == null) {
            fail(player, "pvpocket.msg.spectate.arena_not_ready");
            return false;
        }

        // Re-joining while already watching is a no-op swap, so the saved position survives.
        if (watching.containsKey(player.getUUID())) {
            leave(player, "SWITCH", false);
        }

        Watching state = new Watching(session.id, player);
        // Enters detached, so the spectator free-flies before attaching to a body.
        state.target = -1;
        watching.put(player.getUUID(), state);

        // Goes through Sandbox, so the mode rule does not refuse the switch.
        Sandbox.changeGameMode(player, GameType.SPECTATOR);
        // Dropped above the middle of the plot rather than onto a spawn pad: this is where the
        // spectator's own body sits until they attach, and where it returns when they detach.
        player.teleportTo(arena,
                region.centerX() + 0.5D,
                Math.max(region.spawnY(0), region.spawnY(1)) + 6.0D,
                region.centerZ() + 0.5D,
                0.0F, 40.0F);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        pointCamera(player, null);
        PvPocketNetworking.sendPossess(player, PossessPayload.hidden());
        pushState(player);
        // Same scoreboard and boundary as the participants get.
        sessions.pushSpectatorView(player, session);

        sessions.journal(session).append("SPECTATOR_JOINED", "player", player.getName().getString());
        player.displayClientMessage(PvLang.t("pvpocket.msg.spectate.joined", mapId(session))
                .withStyle(ChatFormatting.AQUA), false);
        return true;
    }

    /**
     * Stops watching and puts the player back where they were.
     *
     * <p>Order matters. The camera is detached first, or the client keeps rendering the old entity
     * for a tick. The teleport happens before the game mode change, so no tick is spent falling or
     * suffocating inside the arena.
     *
     * @param announce whether to tell the player; false when the cause is already visible
     */
    boolean leave(ServerPlayer player, String reason, boolean announce) {
        Watching state = watching.remove(player.getUUID());
        if (state == null) {
            return false;
        }
        restore(player, state);
        PvPocketNetworking.sendSpectate(player, SpectateStatePayload.hidden());
        PvPocketNetworking.sendPossess(player, PossessPayload.hidden());
        // The scoreboard and boundary were pushed for the spectator, so remove them here.
        sessions.clearSpectatorView(player);

        DuelSession session = sessions.byId(state.sessionId);
        if (session != null) {
            sessions.journal(session).append("SPECTATOR_LEFT",
                    "player", player.getName().getString(), "reason", reason);
        }
        if (announce) {
            player.displayClientMessage(PvLang.t("pvpocket.msg.spectate.left").withStyle(ChatFormatting.GRAY), false);
        }
        return true;
    }

    private void restore(ServerPlayer player, Watching state) {
        pointCamera(player, null);
        ServerLevel back = server().getLevel(state.world);
        if (back != null) {
            PvCompat.teleport(player, back, state.x, state.y, state.z, state.yaw, state.pitch);
            player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        } else {
            // The origin dimension is unloaded, so fall back to the overworld spawn.
            PvPocket.LOGGER.warn("Spectator {} came from an unloaded dimension {}; sending to spawn",
                    player.getName().getString(), state.world.location());
            ServerLevel overworld = server().overworld();
            player.teleportTo(overworld, PvCompat.sharedSpawn(overworld).getX() + 0.5D,
                    PvCompat.sharedSpawn(overworld).getY(), PvCompat.sharedSpawn(overworld).getZ() + 0.5D,
                    state.yaw, state.pitch);
        }
        if (player.gameMode.getGameModeForPlayer() != state.mode) {
            Sandbox.changeGameMode(player, state.mode);
        }
    }

    /** Drops every spectator of a session. Called at every terminal transition. */
    void ejectAll(String sessionId, String reason) {
        List<UUID> doomed = new ArrayList<>();
        for (Map.Entry<UUID, Watching> entry : watching.entrySet()) {
            if (entry.getValue().sessionId.equals(sessionId)) {
                doomed.add(entry.getKey());
            }
        }
        for (UUID uuid : doomed) {
            ServerPlayer player = server().getPlayerList().getPlayer(uuid);
            if (player == null) {
                // Offline: the entry goes, and their saved game mode is already restored by
                // onDisconnect. Nothing else can be done to a player who is not here.
                watching.remove(uuid);
                continue;
            }
            leave(player, reason, true);
        }
    }

    /** Drops every spectator of every session. Used on server stop. */
    void ejectEverything(String reason) {
        for (UUID uuid : new ArrayList<>(watching.keySet())) {
            ServerPlayer player = server().getPlayerList().getPlayer(uuid);
            if (player == null) {
                watching.remove(uuid);
                continue;
            }
            // Silent: the server is stopping, and a chat line nobody has time to read is noise on
            // top of the shutdown messages.
            leave(player, reason, false);
        }
    }

    /** Disconnect path: restore before the playerdata is written, or they rejoin as a spectator. */
    void onDisconnect(ServerPlayer player) {
        Watching state = watching.remove(player.getUUID());
        if (state == null) {
            return;
        }
        restore(player, state);
        DuelSession session = sessions.byId(state.sessionId);
        if (session != null) {
            sessions.journal(session).append("SPECTATOR_LEFT",
                    "player", player.getName().getString(), "reason", "DISCONNECT");
        }
    }

    // ------------------------------------------------------------------ camera

    /** Moves the camera to the next participant, wrapping around. */
    void cycle(ServerPlayer player) {
        Watching state = watching.get(player.getUUID());
        if (state == null) {
            fail(player, "pvpocket.msg.spectate.not_watching");
            return;
        }
        DuelSession session = sessions.byId(state.sessionId);
        if (session == null || session.participants.isEmpty()) {
            return;
        }
        // From the detached state (target -1) this lands on the first participant rather than
        // skipping one: pressing V after detaching should attach, not attach-and-then-advance.
        state.target = Math.floorMod(state.target + 1, session.participants.size());
        state.lastSignature = "";
        attach(player, session, state);
        pushState(player);
    }

    /**
     * Puts the camera back on the spectator's own body without ending the watch.
     *
     * <p>The state stays alive, so the bar and scoreboard keep updating and {@link #cycle} attaches
     * again from here.
     */
    void detach(ServerPlayer player) {
        Watching state = watching.get(player.getUUID());
        if (state == null) {
            fail(player, "pvpocket.msg.spectate.not_watching");
            return;
        }
        state.target = -1;
        state.lastSignature = "";
        pointCamera(player, null);
        PvPocketNetworking.sendPossess(player, PossessPayload.hidden());
        pushState(player);
    }

    /**
     * Points the camera at {@code state.target}, skipping anybody who is offline.
     *
     * <p>The loop walks the whole list once, so a camera whose target disconnected lands on the
     * next online participant instead of failing.
     */
    private void attach(ServerPlayer spectator, DuelSession session, Watching state) {
        int size = session.participants.size();
        for (int i = 0; i < size; i++) {
            int index = Math.floorMod(state.target + i, size);
            ServerPlayer target = sessions.online(session.participants.get(index));
            if (target != null && !target.isRemoved()) {
                state.target = index;
                pointCamera(spectator, target);
                // Sent now, so the hotbar matches the frame the camera moves.
                pushPossess(spectator, state, target);
                return;
            }
        }
        state.target = -1;
        pointCamera(spectator, null);
        PvPocketNetworking.sendPossess(spectator, PossessPayload.hidden());
    }

    /**
     * Sends the possessed body's hotbar and vitals, if they changed since the last send.
     *
     * <p>Compared by signature so an unchanged inventory produces no packet.
     */
    private void pushPossess(ServerPlayer spectator, Watching state, ServerPlayer target) {
        if (target == null) {
            PvPocketNetworking.sendPossess(spectator, PossessPayload.hidden());
            state.lastSignature = "";
            return;
        }
        String signature = possessSignature(target);
        if (signature.equals(state.lastSignature)) {
            return;
        }
        state.lastSignature = signature;
        PvPocketNetworking.sendPossess(spectator, new PossessPayload(
                true,
                target.getId(),
                target.getName().getString(),
                target.getInventory().selected,
                hotbarField(target),
                new PossessPayload.Vitals(target.getHealth(), target.getArmorValue())));
    }

    /**
     * The hotbar as {@code id,count} groups joined by {@code ;}, empty slots included.
     *
     * <p>The field always holds exactly nine groups, and an empty slot is an empty group.
     */
    private static String hotbarField(ServerPlayer target) {
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < 9; slot++) {
            if (slot > 0) {
                sb.append(';');
            }
            ItemStack stack = target.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            sb.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(',').append(stack.getCount());
        }
        return sb.toString();
    }

    /** Cheap change detector: anything the possession HUD draws is part of it. */
    private static String possessSignature(ServerPlayer target) {
        return target.getInventory().selected + "|" + target.getHealth() + "|"
                + target.getArmorValue() + "|" + hotbarField(target);
    }

    /**
     * Moves one client's camera.
     *
     * <p>{@code setCameraEntity} is the authoritative half, and the packet is the visible half. A
     * duplicate packet is a no-op on the client.
     *
     * <p>Detaching points the camera at the spectator's own body, since the packet's entity id has
     * no null form.
     */
    private static void pointCamera(ServerPlayer spectator, ServerPlayer target) {
        // The field points at the spectator's own body, not null: tick() re-attaches a null camera.
        ServerPlayer camera = target == null ? spectator : target;
        spectator.setCamera(camera);
        spectator.connection.send(new ClientboundSetCameraPacket(camera));
    }

    /**
     * Re-sends the spectator bar.
     *
     * <p>Called on join and on a camera change. The "not watching" branch clears the bar for a
     * client that reconnected mid-match.
     */
    void pushState(ServerPlayer player) {
        Watching state = watching.get(player.getUUID());
        if (state == null) {
            PvPocketNetworking.sendSpectate(player, SpectateStatePayload.hidden());
            return;
        }
        DuelSession session = sessions.byId(state.sessionId);
        if (session == null) {
            PvPocketNetworking.sendSpectate(player, SpectateStatePayload.hidden());
            return;
        }
        StringBuilder targets = new StringBuilder();
        for (Participant p : session.participants) {
            if (targets.length() > 0) {
                targets.append(',');
            }
            targets.append(p.name).append(':').append(p.uuid);
        }
        PvPocketNetworking.sendSpectate(player, new SpectateStatePayload(
                true,
                state.sessionId,
                mapId(session),
                Math.max(1, session.round),
                state.target,
                targets.toString()));
    }

    /**
     * Per-tick upkeep: drops spectators of finished sessions and repairs dead cameras.
     *
     * <p>When a participant dies their entity is replaced, so the camera is re-attached on
     * {@code isRemoved}.
     */
    void tick() {
        if (watching.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Watching>> it = watching.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Watching> entry = it.next();
            ServerPlayer player = server().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            DuelSession session = sessions.byId(entry.getValue().sessionId);
            if (session == null || session.stage.isTerminal()
                    || session.stage == SessionStage.ROUND_SETTLED
                    || session.stage == SessionStage.RESTORING) {
                restore(player, entry.getValue());
                it.remove();
                PvPocketNetworking.sendSpectate(player, SpectateStatePayload.hidden());
                PvPocketNetworking.sendPossess(player, PossessPayload.hidden());
                sessions.clearSpectatorView(player);
                player.displayClientMessage(PvLang.t("pvpocket.msg.spectate.ended")
                        .withStyle(ChatFormatting.GRAY), false);
                continue;
            }
            // Detached spectators are skipped before the repair, which would otherwise re-attach.
            if (entry.getValue().target < 0) {
                // Free flight is the only state in which the spectator's body actually moves, so
                // it is the only state the flight box has anything to constrain.
                ArenaRegion region = ArenaManager.region(entry.getValue().sessionId);
                if (region != null) {
                    clampFlight(player, entry.getValue(), region);
                }
                continue;
            }
            var camera = player.getCamera();
            if (camera == null || camera.isRemoved()) {
                attach(player, session, entry.getValue());
                pushState(player);
                continue;
            }
            if (camera == player) {
                continue;
            }
            if (camera instanceof ServerPlayer body) {
                pushPossess(player, entry.getValue(), body);
            }
        }
    }

    /**
     * Keeps a detached spectator inside {@link ArenaManager#spectatorBox}.
     *
     * <p>The box is the plot plus a margin, capped vertically, so a spectator can back off to see
     * the map without reaching a neighbouring duel.
     *
     * <p>The warning is edge-triggered and re-armed by distance: pressing against the limit warns
     * once, and leaving and coming back warns again.
     */
    private static void clampFlight(ServerPlayer player, Watching state, ArenaRegion region) {
        if (ArenaManager.pullSpectatorInside(player, region)) {
            if (!state.limitWarned) {
                state.limitWarned = true;
                player.displayClientMessage(PvLang.t("pvpocket.msg.spectate.limit")
                        .withStyle(ChatFormatting.GRAY), false);
            }
            return;
        }
        // Re-armed four blocks inside the limit, so a spectator on the edge does not oscillate.
        if (state.limitWarned
                && ArenaManager.spectatorBox(region).inflate(-4.0D).contains(player.position())) {
            state.limitWarned = false;
        }
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(PvLang.t(key, args).withStyle(ChatFormatting.RED), false);
    }

    /** Read-only view for diagnostics. */
    Collection<String> describe() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Watching> entry : watching.entrySet()) {
            ServerPlayer player = server().getPlayerList().getPlayer(entry.getKey());
            out.add((player == null ? entry.getKey().toString() : player.getName().getString())
                    + " -> " + entry.getValue().sessionId.substring(0, 8)
                    + " -> " + entry.getValue().target);
        }
        return out;
    }
}
