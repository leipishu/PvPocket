package com.leipishu.pvpocket.session;

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
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.SetCameraEntityS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

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
 * <p>Spectating is the one feature in this mod that moves a player who is <em>not</em> part of a
 * session. That makes it the one place where "restore what you changed" cannot lean on
 * {@link PlayerSnapshot} — a spectator's inventory, effects and experience are never touched, so
 * freezing the whole player would be a heavy answer to a small question. What is changed is exactly
 * three things: the game mode, the dimension and the position. Those three are saved here and put
 * back on the way out.</p>
 *
 * <p>The camera is the interesting part. A spectator is put in {@link GameMode#SPECTATOR} and their
 * camera is attached to a participant with {@code setCameraEntity}, which is the same mechanism the
 * vanilla {@code /spectate} command uses. That gives true first-person view rather than a chase cam,
 * and it costs nothing per frame: the client is already rendering that player.</p>
 *
 * <p><b>Why the participant list is sent to the client.</b> The cycle key has to be able to say what
 * it will switch to. A key that silently changes an invisible target is a key nobody presses twice,
 * so {@link SpectateStatePayload} carries the names and the client draws "1 / 2".</p>
 */
final class Spectators {

    /**
     * One spectator's saved context.
     *
     * <p>The world is stored as a registry key rather than as a {@link ServerWorld}: a world object
     * can be replaced when a dimension is unloaded and reloaded, and holding the old instance would
     * teleport the player into a world the server no longer ticks.</p>
     */
    private static final class Watching {
        final String sessionId;
        final RegistryKey<World> world;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final GameMode mode;
        int target;
        /**
         * Last hotbar/vitals signature sent for {@link #target}, so the possession HUD is only
         * re-sent when something on it actually moved. Empty forces the next tick to send.
         */
        String lastSignature = "";
        /**
         * Whether the "you have reached the edge" line has already been shown.
         *
         * <p>The flight box is an invisible wall by nature — it is a rule, not geometry — and the
         * boundary curtain is drawn twelve blocks further in. Without a word of explanation the
         * limit reads as the client refusing to move. One line per contact is enough; it is cleared
         * again once the spectator has flown a few blocks back into the box, so the next attempt
         * says it again.</p>
         */
        boolean limitWarned;

        Watching(String sessionId, ServerPlayerEntity player) {
            this.sessionId = sessionId;
            this.world = player.getWorld().getRegistryKey();
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yaw = player.getYaw();
            this.pitch = player.getPitch();
            this.mode = player.interactionManager.getGameMode();
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
    List<ServerPlayerEntity> watchersOf(String sessionId) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (Map.Entry<UUID, Watching> entry : watching.entrySet()) {
            if (!entry.getValue().sessionId.equals(sessionId)) {
                continue;
            }
            ServerPlayerEntity player = server().getPlayerManager().getPlayer(entry.getKey());
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
     * <p>Only sessions whose players are actually in the arena are listed — {@code ROUND_READY} and
     * {@code ROUND_ACTIVE}. A negotiation is not a match yet: there is nothing to watch, and a row
     * that can never be entered is a row that teaches the player the button does not work.</p>
     */
    void openList(ServerPlayerEntity player) {
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
            if (session.isParticipant(player.getUuidAsString())) {
                flags |= MatchListPayload.FLAG_PARTICIPANT;
            }
            if (isWatching(player.getUuid())
                    && session.id.equals(watching.get(player.getUuid()).sessionId)) {
                flags |= MatchListPayload.FLAG_WATCHING;
            }
            sb.append(session.id).append('|')
                    .append(mapId(session)).append('|')
                    .append(session.stage.name()).append('|')
                    .append(session.round).append('|')
                    .append(session.bestOf).append('|')
                    .append(leftScore(session)).append(':').append(rightScore(session)).append('|')
                    .append(flags).append('|')
                    .append(watchersOf(session.id).size()).append('|')
                    .append(playersField(session));
        }
        PvPocketNetworking.openMatchList(player, new MatchListPayload(sb.toString()));
    }

    /**
     * The map's id, which the client turns into {@code pvpocket.map.<id>}.
     *
     * <p>Read from the leased region rather than from the rule set: the region is what is actually
     * stamped in the world, so if the two ever disagree the panel names the arena the players are
     * standing in. Falls back to the rule when no region is leased, which happens between rounds.</p>
     *
     * <p>An id rather than a name because the payload is read by a client whose language this class
     * cannot know — the same reason the stage is sent as an enum name.</p>
     */
    private static String mapId(DuelSession session) {
        ArenaRegion region = ArenaManager.region(session.id);
        if (region != null) {
            return region.map.id;
        }
        ArenaMap map = com.leipishu.pvpocket.arena.ArenaMaps.byIndex(
                DuelRules.decode(session.rules).get("e.template"));
        return map.id;
    }

    private static int leftScore(DuelSession session) {
        int sum = 0;
        for (Participant p : session.participants) {
            if (p.teamIndex() % 2 == 0) {
                sum += session.score.getOrDefault(p.uuid, 0);
            }
        }
        return sum;
    }

    private static int rightScore(DuelSession session) {
        int sum = 0;
        for (Participant p : session.participants) {
            if (p.teamIndex() % 2 != 0) {
                sum += session.score.getOrDefault(p.uuid, 0);
            }
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
     * <p>Every reason to refuse is checked here rather than in the panel, because the panel is a
     * client and the client is not trusted: a hand-crafted payload naming a session with spectating
     * off, or a session that ended a second ago, has to be refused by the same code that refuses a
     * legitimate request.</p>
     *
     * <p>Refusals are chat lines, not silent failures. The player pressed a button; a button that
     * does nothing is indistinguishable from a broken button.</p>
     */
    boolean join(ServerPlayerEntity player, String sessionId) {
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
        if (session.isParticipant(player.getUuidAsString())) {
            fail(player, "pvpocket.msg.spectate.is_participant");
            return false;
        }
        if (sessions.sessionOf(player.getUuid()) != null) {
            fail(player, "pvpocket.msg.spectate.in_duel");
            return false;
        }
        ArenaRegion region = ArenaManager.region(session.id);
        ServerWorld arena = ArenaManager.world(server());
        if (region == null || arena == null) {
            fail(player, "pvpocket.msg.spectate.arena_not_ready");
            return false;
        }

        // Re-joining while already watching is a no-op swap, not an error: the panel's 观战 button
        // stays clickable for the row you are already on, and losing the saved position because of
        // a second click would send the player home afterwards instead of back where they were.
        if (watching.containsKey(player.getUuid())) {
            leave(player, "SWITCH", false);
        }

        Watching state = new Watching(session.id, player);
        // Enters detached. Landing straight inside somebody's eyes is disorienting — the first
        // thing a spectator sees would be a sword swing from a metre away with no explanation of
        // whose head they are in. Free-flying first also lets them find the body they want before
        // committing to it, and it keeps the possession HUD (hotbar, vitals) off the screen until
        // there is actually a body to describe.
        state.target = -1;
        watching.put(player.getUuid(), state);

        // Authorised change: entering spectator mode is the whole feature, and the sandbox rule
        // that forbids players from switching modes would otherwise refuse it silently.
        Sandbox.changeGameMode(player, GameMode.SPECTATOR);
        // Dropped above the middle of the plot rather than onto a spawn pad: this is where the
        // spectator's own body sits until they attach, and where it returns when they detach.
        player.teleport(arena,
                region.centerX() + 0.5D,
                Math.max(region.spawnY(0), region.spawnY(1)) + 6.0D,
                region.centerZ() + 0.5D,
                0.0F, 40.0F);
        player.setVelocity(0.0D, 0.0D, 0.0D);
        pointCamera(player, null);
        PvPocketNetworking.sendPossess(player, PossessPayload.hidden());
        pushState(player);
        // The same scoreboard and boundary the participants are looking at. Without it a spectator
        // sees two players fighting in a box with no edges and no score, which is a stream, not a
        // spectator mode.
        sessions.pushSpectatorView(player, session);

        sessions.journal(session).append("SPECTATOR_JOINED", "player", player.getName().getString());
        player.sendMessage(PvLang.t("pvpocket.msg.spectate.joined", mapId(session))
                .formatted(Formatting.AQUA), false);
        return true;
    }

    /**
     * Stops watching and puts the player back exactly where they were.
     *
     * <p>Order matters. The camera is detached first, because a client whose camera is on another
     * entity would keep rendering that entity for a tick after the teleport. The teleport happens
     * before the game mode change, because switching to survival while still standing inside the
     * arena is one tick of falling and, on a bad day, one tick of suffocating.</p>
     *
     * @param announce whether to tell the player. False when the cause is already visible — the
     *                 series ending announces itself, and a second "you have left spectator mode"
     *                 line on top of it is noise.
     */
    boolean leave(ServerPlayerEntity player, String reason, boolean announce) {
        Watching state = watching.remove(player.getUuid());
        if (state == null) {
            return false;
        }
        restore(player, state);
        PvPocketNetworking.sendSpectate(player, SpectateStatePayload.hidden());
        PvPocketNetworking.sendPossess(player, PossessPayload.hidden());
        // The scoreboard and the boundary curtain were pushed for the spectator's benefit and are
        // not part of the player's own state, so nothing else will ever take them down: a player who
        // leaves with the match still running would keep a live score bar over their survival world.
        sessions.clearSpectatorView(player);

        DuelSession session = sessions.byId(state.sessionId);
        if (session != null) {
            sessions.journal(session).append("SPECTATOR_LEFT",
                    "player", player.getName().getString(), "reason", reason);
        }
        if (announce) {
            player.sendMessage(PvLang.t("pvpocket.msg.spectate.left").formatted(Formatting.GRAY), false);
        }
        return true;
    }

    private void restore(ServerPlayerEntity player, Watching state) {
        pointCamera(player, null);
        ServerWorld back = server().getWorld(state.world);
        if (back != null) {
            player.teleport(back, state.x, state.y, state.z, state.yaw, state.pitch);
            player.setVelocity(0.0D, 0.0D, 0.0D);
        } else {
            // The dimension they came from is gone (a datapack reload). Sending them to the overworld
            // spawn is worse than putting them back but better than leaving them in the arena as a
            // spectator with no way out.
            PvPocket.LOGGER.warn("Spectator {} came from an unloaded dimension {}; sending to spawn",
                    player.getName().getString(), state.world.getValue());
            ServerWorld overworld = server().getOverworld();
            player.teleport(overworld, overworld.getSpawnPos().getX() + 0.5D,
                    overworld.getSpawnPos().getY(), overworld.getSpawnPos().getZ() + 0.5D,
                    state.yaw, state.pitch);
        }
        if (player.interactionManager.getGameMode() != state.mode) {
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
            ServerPlayerEntity player = server().getPlayerManager().getPlayer(uuid);
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
            ServerPlayerEntity player = server().getPlayerManager().getPlayer(uuid);
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
    void onDisconnect(ServerPlayerEntity player) {
        Watching state = watching.remove(player.getUuid());
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
    void cycle(ServerPlayerEntity player) {
        Watching state = watching.get(player.getUuid());
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
     * <p>The state stays alive — the bar keeps saying which match is being watched, the scoreboard
     * keeps updating, and {@link #cycle} attaches again from here. This is the escape hatch from a
     * possessed view; without it the only way out of somebody else's eyes is to leave entirely,
     * which loses the spectator's position and their place in the match.</p>
     */
    void detach(ServerPlayerEntity player) {
        Watching state = watching.get(player.getUuid());
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
     * <p>Skipping rather than refusing matters mid-round: the participant you were watching can
     * disconnect, and a camera attached to a removed entity is a black screen. The loop walks the
     * whole list once, so a 1v1 with one side gone lands on the other side instead of failing.</p>
     */
    private void attach(ServerPlayerEntity spectator, DuelSession session, Watching state) {
        int size = session.participants.size();
        for (int i = 0; i < size; i++) {
            int index = Math.floorMod(state.target + i, size);
            ServerPlayerEntity target = sessions.online(session.participants.get(index));
            if (target != null && !target.isRemoved()) {
                state.target = index;
                pointCamera(spectator, target);
                // Pushed right here rather than on the next tick so the hotbar appears in the same
                // frame the camera moves; a tick of somebody else's empty hotbar looks like a bug.
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
     * <p>Compared by signature rather than sent every tick: an inventory does not change most ticks,
     * and this is the only per-tick packet the spectator feature would otherwise produce.</p>
     */
    private void pushPossess(ServerPlayerEntity spectator, Watching state, ServerPlayerEntity target) {
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
                target.getInventory().selectedSlot,
                hotbarField(target),
                new PossessPayload.Vitals(target.getHealth(), target.getArmor())));
    }

    /**
     * The hotbar as {@code id,count} groups joined by {@code ;}, empty slots included.
     *
     * <p>Positions matter — an empty slot in the middle has to stay in the middle — so the field
     * always has exactly nine groups and an empty group is written as nothing between two
     * separators.</p>
     */
    private static String hotbarField(ServerPlayerEntity target) {
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < 9; slot++) {
            if (slot > 0) {
                sb.append(';');
            }
            ItemStack stack = target.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            sb.append(Registries.ITEM.getId(stack.getItem())).append(',').append(stack.getCount());
        }
        return sb.toString();
    }

    /** Cheap change detector: anything the possession HUD draws is part of it. */
    private static String possessSignature(ServerPlayerEntity target) {
        return target.getInventory().selectedSlot + "|" + target.getHealth() + "|"
                + target.getArmor() + "|" + hotbarField(target);
    }

    /**
     * Moves one client's camera.
     *
     * <p>{@code setCameraEntity} is the authoritative half — it is what {@code getCameraEntity()}
     * reads, and what decides whether the camera is still valid on the next tick. The packet is the
     * visible half, and it is sent explicitly rather than assumed: the two are separate concerns,
     * a duplicate packet is a no-op on the client, and a missing one is a spectator staring at a
     * frozen frame with no way to tell that anything went wrong.</p>
     *
     * <p>Detaching points the camera at the spectator's own body, which is what the client means by
     * "no camera entity" — the packet's entity id has no null form.</p>
     */
    private static void pointCamera(ServerPlayerEntity spectator, ServerPlayerEntity target) {
        // Detaching points the field at the spectator's own body rather than null. The packet has no
        // null form either, and a null field is indistinguishable from "this camera is broken" to
        // the repair pass in tick(), which would re-attach on the next tick.
        ServerPlayerEntity camera = target == null ? spectator : target;
        spectator.setCameraEntity(camera);
        spectator.networkHandler.sendPacket(new SetCameraEntityS2CPacket(camera));
    }

    /**
     * Re-sends the spectator bar.
     *
     * <p>Also called on join, which is why it has a "not watching" branch: a client that reconnects
     * mid-match has no bar state, and the payload is otherwise only pushed on a camera change.</p>
     */
    void pushState(ServerPlayerEntity player) {
        Watching state = watching.get(player.getUuid());
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
     * Per-tick upkeep: drop spectators of finished sessions and repair dead cameras.
     *
     * <p>The camera check is what makes a respawn survivable. When a participant dies their entity
     * is replaced, and the spectator's camera keeps pointing at the old one — which is not an error
     * the client reports, it is just a frozen frame. Re-attaching on {@code isRemoved} turns that
     * into a camera that follows the new body.</p>
     */
    void tick() {
        if (watching.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Watching>> it = watching.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Watching> entry = it.next();
            ServerPlayerEntity player = server().getPlayerManager().getPlayer(entry.getKey());
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
                player.sendMessage(PvLang.t("pvpocket.msg.spectate.ended")
                        .formatted(Formatting.GRAY), false);
                continue;
            }
            // Detached on purpose. This has to be checked before the repair below, not after: a
            // detached spectator's camera entity is their own body, and any code path that leaves
            // it null would be "repaired" straight back into somebody else's eyes -- which is
            // exactly the state the detach key exists to get out of.
            if (entry.getValue().target < 0) {
                // Free flight is the only state in which the spectator's body actually moves, so
                // it is the only state the flight box has anything to constrain.
                ArenaRegion region = ArenaManager.region(entry.getValue().sessionId);
                if (region != null) {
                    clampFlight(player, entry.getValue(), region);
                }
                continue;
            }
            var camera = player.getCameraEntity();
            if (camera == null || camera.isRemoved()) {
                attach(player, session, entry.getValue());
                pushState(player);
                continue;
            }
            if (camera == player) {
                continue;
            }
            if (camera instanceof ServerPlayerEntity body) {
                pushPossess(player, entry.getValue(), body);
            }
        }
    }

    /**
     * Keeps a detached spectator inside {@link ArenaManager#spectatorBox}.
     *
     * <p>Spectating is not a free camera. An unconstrained one can leave the plot entirely, fly
     * until the arena is a speck, and — worse — wander into the neighbouring cell and watch a
     * different duel, which is the one thing the plot grid exists to prevent. The box is the plot
     * plus a margin rather than the plot itself so there is room to back off and see the whole map,
     * and it is capped vertically so "back off" does not mean "straight up".</p>
     *
     * <p>The warning is edge-triggered and re-armed by distance rather than by a timer: a player
     * pressing against the limit should not be told twenty times a second, but a player who leaves
     * and comes back should be told again.</p>
     */
    private static void clampFlight(ServerPlayerEntity player, Watching state, ArenaRegion region) {
        if (ArenaManager.pullSpectatorInside(player, region)) {
            if (!state.limitWarned) {
                state.limitWarned = true;
                player.sendMessage(PvLang.t("pvpocket.msg.spectate.limit")
                        .formatted(Formatting.GRAY), false);
            }
            return;
        }
        // Re-armed four blocks inside the limit, so a spectator hovering exactly on the edge does
        // not oscillate between warned and unwarned and get a line every other tick.
        if (state.limitWarned
                && ArenaManager.spectatorBox(region).expand(-4.0D).contains(player.getPos())) {
            state.limitWarned = false;
        }
    }

    private static void fail(ServerPlayerEntity player, String key, Object... args) {
        player.sendMessage(PvLang.t(key, args).formatted(Formatting.RED), false);
    }

    /** Read-only view for diagnostics. */
    Collection<String> describe() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Watching> entry : watching.entrySet()) {
            ServerPlayerEntity player = server().getPlayerManager().getPlayer(entry.getKey());
            out.add((player == null ? entry.getKey().toString() : player.getName().getString())
                    + " -> " + entry.getValue().sessionId.substring(0, 8)
                    + " -> " + entry.getValue().target);
        }
        return out;
    }
}
