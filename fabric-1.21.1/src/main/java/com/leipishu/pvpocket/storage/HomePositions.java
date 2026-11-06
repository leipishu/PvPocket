package com.leipishu.pvpocket.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where each player was the last time they were <em>not</em> in the duel dimension.
 *
 * <p>This is a backstop, not the primary path. Everything in the mod that moves a player into
 * {@code pvpocket:arena} — a duel, a spectator camera — has a matching restore that puts them back.
 * The restores all live in memory, though, and the one thing that cannot survive is the server dying
 * with somebody inside. Their {@code playerdata} then records a position in the arena, and the next
 * login spawns them on a floating plot in a world they cannot leave.</p>
 *
 * <p>The snapshot a duel takes is the right answer for a participant and useless for a spectator:
 * spectators are never snapshotted, because nothing about them is frozen. So the position is
 * remembered continuously instead — cheaply, since it only changes when the player crosses a block
 * boundary — and written to disk in one file that is rewritten at most every half minute.</p>
 *
 * <p>Recording is skipped while the player is in the arena, which is what makes the saved value
 * mean "before the duel" rather than "the last place I stood".</p>
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
         * <p>Saved for one reason: a spectator whose restore never ran logs back in still in
         * {@code SPECTATOR}, and a spectator outside the arena cannot break blocks, take damage or
         * be targeted — they are stuck as a ghost in their own base. Position alone would move them
         * and leave that behind.</p>
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
        Path file = server.getSavePath(WorldSavePath.ROOT).resolve("pvpocket").resolve("homes.json");
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
     * <p>Called for every online player every tick, so it has to be free in the common case: one
     * world check, one map lookup, one comparison against a squared distance.</p>
     */
    public void remember(ServerPlayerEntity player) {
        World world = player.getWorld();
        if (ArenaManager.isArena(world)) {
            return;
        }
        String key = player.getUuidAsString();
        Home previous = homes.get(key);
        String dimension = world.getRegistryKey().getValue().toString();
        String mode = player.interactionManager.getGameMode().getName();
        if (previous != null && previous.dimension.equals(dimension) && mode.equals(previous.gameMode)) {
            double dx = previous.x - player.getX();
            double dy = previous.y - player.getY();
            double dz = previous.z - player.getZ();
            if (dx * dx + dy * dy + dz * dz < MOVE_EPSILON_SQ) {
                return;
            }
        }
        homes.put(key, new Home(dimension, player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(), mode));
        dirty = true;
    }

    /**
     * Moves a player who logged in inside the duel dimension back out.
     *
     * <p>There is no legitimate way to log in standing in the arena: every arrival there is a
     * teleport the mod itself performs, and each of those has a restore that runs before the
     * playerdata is written. So an arrival there means a restore was lost — a crash, a kill, a
     * dimension unloaded mid-duel — and the only useful thing to do is undo it.</p>
     *
     * @return true if the player was moved
     */
    public boolean rescue(ServerPlayerEntity player) {
        return moveOut(player, "pvpocket.msg.home.reason.rescue", "login");
    }

    /**
     * Moves out a player who is in the duel dimension without a match to be in.
     *
     * <p>Same destination and the same fallback as {@link #rescue}, different cause: this one is
     * the live guard, for somebody who walked, teleported or fell in without a session. Only the
     * sentence changes, because "你上次在决斗场地里退出" would be a lie about what just happened.</p>
     *
     * @return true if the player was moved
     */
    public boolean evict(ServerPlayerEntity player) {
        return moveOut(player, "pvpocket.msg.home.reason.evict", "guard");
    }

    /**
     * The shared body of both exits.
     *
     * @param whyKey translation key for the reason, used as the first half of the sentence
     * @param reason short tag for the log line
     */
    private boolean moveOut(ServerPlayerEntity player, String whyKey, String reason) {
        if (!ArenaManager.isArena(player.getWorld())) {
            return false;
        }
        Home home = homes.get(player.getUuidAsString());
        ServerWorld target = home == null ? null : resolve(player, home.dimension);
        if (target == null) {
            // No record, or the record points at a dimension that no longer exists. Spawn is worse
            // than where they were but better than a plot floating in the void.
            target = player.getServer().getOverworld();
            player.teleport(target, target.getSpawnPos().getX() + 0.5D,
                    target.getSpawnPos().getY(), target.getSpawnPos().getZ() + 0.5D,
                    player.getYaw(), player.getPitch());
            releaseSpectatorMode(player, null);
            PvPocket.LOGGER.warn("Moved {} out of the arena ({}, no usable home); sent to spawn",
                    player.getName().getString(), reason);
            player.sendMessage(PvLang.t("pvpocket.msg.home.sent_spawn", PvLang.t(whyKey))
                    .formatted(Formatting.YELLOW), false);
            return true;
        }
        player.teleport(target, home.x, home.y, home.z, home.yaw, home.pitch);
        player.setVelocity(0.0D, 0.0D, 0.0D);
        releaseSpectatorMode(player, home);
        PvPocket.LOGGER.info("Moved {} out of the arena ({}) to {} {} {} in {}",
                player.getName().getString(), reason, home.x, home.y, home.z, home.dimension);
        player.sendMessage(PvLang.t("pvpocket.msg.home.sent_home", PvLang.t(whyKey))
                .formatted(Formatting.YELLOW), false);
        return true;
    }

    /**
     * Undoes a spectator game mode that outlived the session that set it.
     *
     * <p>Only when the player is <em>still</em> a spectator: that is the state nothing else can fix,
     * because {@code SPECTATOR} cannot be escaped from inside the game. Any other mode is left
     * alone — the player may have changed it deliberately, and this class is a backstop, not an
     * authority on how somebody wants to play.</p>
     */
    private static void releaseSpectatorMode(ServerPlayerEntity player, Home home) {
        if (player.interactionManager.getGameMode() != GameMode.SPECTATOR) {
            return;
        }
        GameMode fallback = home == null ? GameMode.SURVIVAL : GameMode.byName(home.gameMode, GameMode.SURVIVAL);
        // Authorised change: this runs while the player is still standing in the arena dimension,
        // which is exactly the situation the sandbox rule exists to block for everyone else.
        Sandbox.changeGameMode(player, fallback);
    }

    private static ServerWorld resolve(ServerPlayerEntity player, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) {
            return null;
        }
        return player.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
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
