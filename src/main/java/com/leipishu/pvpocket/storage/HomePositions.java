package com.leipishu.pvpocket.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where each player was the last time they were not in the duel dimension.
 *
 * <p>This is a backstop. Every move into {@code pvpocket:arena} has a matching restore, but a
 * server that dies with a player inside leaves an arena position in their {@code playerdata}, and
 * the next login spawns them on a floating plot.
 *
 * <p>The position is remembered continuously and written to one file, rewritten at most every 30
 * seconds. Recording is skipped while the player is in the arena, so the saved value means "before
 * the duel".
 */
public final class HomePositions {

    /** One saved location. Plain fields rather than a record: Gson reflects into these directly. */
    public static final class Home {
        public String dimension;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        /**
         * Game mode as it was outside the arena, e.g. {@code survival}.
         *
         * <p>A spectator whose restore never ran logs back in still in {@code SPECTATOR}, which
         * cannot be escaped from inside the game. Position alone would leave that behind.
         */
        public String gameMode;

        Home() {
        }

        Home(String dimension, double x, double y, double z, float yaw, float pitch, String gameMode) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.gameMode = gameMode;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, Home>>() {
    }.getType();

    /** Rewriting the file is a disk write; there is no reason to do it more often than this. */
    private static final long FLUSH_INTERVAL_MS = 30_000L;
    /** Squared distance below which a movement is not worth recording. */
    private static final double MOVE_EPSILON_SQ = 4.0D;

    private final Path file;
    private final Map<String, Home> homes = new LinkedHashMap<>();
    private boolean dirty;
    private long lastFlush;

    private HomePositions(Path file) {
        this.file = file;
    }

    public static HomePositions load(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("pvpocket").resolve("homes.json");
        HomePositions store = new HomePositions(file);
        if (!Files.exists(file)) {
            return store;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Home> parsed = GSON.fromJson(json, FILE_TYPE);
            if (parsed != null) {
                store.homes.putAll(parsed);
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt file costs one player a trip to spawn. Refusing to start over it would cost
            // everybody the server.
            PvPocket.LOGGER.warn("Could not read {}; starting with no saved home positions", file, e);
        }
        return store;
    }

    /**
     * Notes where the player is, if they are somewhere worth noting.
     *
     * <p>Called for every online player every tick, so the common case is one world check, one map
     * lookup and one squared-distance comparison.
     */
    public void remember(ServerPlayer player) {
        Level world = player.level();
        if (ArenaManager.isArena(world)) {
            return;
        }
        String key = player.getStringUUID();
        Home previous = homes.get(key);
        String dimension = world.dimension().location().toString();
        String mode = player.gameMode.getGameModeForPlayer().getName();
        if (previous != null && previous.dimension.equals(dimension) && mode.equals(previous.gameMode)) {
            double dx = previous.x - player.getX();
            double dy = previous.y - player.getY();
            double dz = previous.z - player.getZ();
            if (dx * dx + dy * dy + dz * dz < MOVE_EPSILON_SQ) {
                return;
            }
        }
        homes.put(key, new Home(dimension, player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), mode));
        dirty = true;
    }

    /**
     * Moves a player who logged in inside the duel dimension back out.
     *
     * <p>Every arrival in the arena is a teleport the mod performs, so an arrival on login means a
     * restore was lost.
     *
     * @return true if the player was moved
     */
    public boolean rescue(ServerPlayer player) {
        return moveOut(player, "pvpocket.msg.home.reason.rescue", "login");
    }

    /**
     * Moves out a player who is in the duel dimension without a match to be in.
     *
     * <p>Same destination and fallback as {@link #rescue}, different cause: this is the live guard,
     * for somebody who walked, teleported or fell in without a session.
     *
     * @return true if the player was moved
     */
    public boolean evict(ServerPlayer player) {
        return moveOut(player, "pvpocket.msg.home.reason.evict", "guard");
    }

    /**
     * The shared body of both exits.
     *
     * <p>{@code whyKey} is the translation key for the reason, used as the first half of the
     * sentence; {@code reason} is a short tag for the log line.
     */
    private boolean moveOut(ServerPlayer player, String whyKey, String reason) {
        if (!ArenaManager.isArena(player.level())) {
            return false;
        }
        Home home = homes.get(player.getStringUUID());
        ServerLevel target = home == null ? null : resolve(player, home.dimension);
        if (target == null) {
            // No record, or the record names a missing dimension. Spawn beats a plot in the void.
            target = player.getServer().overworld();
            player.teleportTo(target, target.getSharedSpawnPos().getX() + 0.5D,
                    target.getSharedSpawnPos().getY(), target.getSharedSpawnPos().getZ() + 0.5D,
                    player.getYRot(), player.getXRot());
            releaseSpectatorMode(player, null);
            PvPocket.LOGGER.warn("Moved {} out of the arena ({}, no usable home); sent to spawn",
                    player.getName().getString(), reason);
            player.displayClientMessage(PvLang.t("pvpocket.msg.home.sent_spawn", PvLang.t(whyKey))
                    .withStyle(ChatFormatting.YELLOW), false);
            return true;
        }
        player.teleportTo(target, home.x, home.y, home.z, home.yaw, home.pitch);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        releaseSpectatorMode(player, home);
        PvPocket.LOGGER.info("Moved {} out of the arena ({}) to {} {} {} in {}",
                player.getName().getString(), reason, home.x, home.y, home.z, home.dimension);
        player.displayClientMessage(PvLang.t("pvpocket.msg.home.sent_home", PvLang.t(whyKey))
                .withStyle(ChatFormatting.YELLOW), false);
        return true;
    }

    /**
     * Undoes a spectator game mode that outlived the session that set it.
     *
     * <p>Applies only when the player is still a spectator, the state that cannot be escaped from
     * inside the game. Any other mode is left alone.
     */
    private static void releaseSpectatorMode(ServerPlayer player, Home home) {
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            return;
        }
        GameType fallback = home == null ? GameType.SURVIVAL : GameType.byName(home.gameMode, GameType.SURVIVAL);
        // Authorised change: this runs while the player is still in the arena dimension.
        Sandbox.changeGameMode(player, fallback);
    }

    private static ServerLevel resolve(ServerPlayer player, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) {
            return null;
        }
        return player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** Writes the file if enough time has passed and anything actually changed. */
    public void flushIfDue() {
        if (!dirty || System.currentTimeMillis() - lastFlush < FLUSH_INTERVAL_MS) {
            return;
        }
        flush();
    }

    public void flush() {
        if (!dirty) {
            return;
        }
        dirty = false;
        lastFlush = System.currentTimeMillis();
        try {
            PvPocketStorage.writeAtomically(file,
                    GSON.toJson(homes, FILE_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            dirty = true;
            PvPocket.LOGGER.error("Could not write {}", file, e);
        }
    }

    /** Diagnostics: how many players have a usable home on record. */
    public int size() {
        return homes.size();
    }

    /** Test hook: the stored home for a player, or null. */
    public Home home(UUID uuid) {
        return homes.get(uuid.toString());
    }
}
