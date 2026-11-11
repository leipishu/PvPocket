package com.leipishu.pvpocket.snapshot;

import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A frozen copy of a player's survival state.
 *
 * <p>The bulk of the state is the player's own NBT, copied verbatim. That is deliberate: the
 * vanilla player tag carries inventory, ender chest, experience, effects, hunger, attributes and
 * any mod-attached persistent data, and hand-picking fields is how data loss happens. A handful of
 * things the player tag does not carry (dimension, team, fall distance) are stored alongside.</p>
 *
 * <p>A snapshot is the single source of truth for "what the player looked like before the duel".
 * It is never merged with live state, only written once and read back on restore.</p>
 */
public final class PlayerSnapshot {

    private static final int FORMAT_VERSION = 1;

    private final CompoundTag playerNbt;
    private final String dimensionId;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final String gameMode;
    private final String teamName;
    private final float fallDistance;
    private final String fingerprint;

    private PlayerSnapshot(CompoundTag playerNbt, String dimensionId, double x, double y, double z,
                           float yaw, float pitch, String gameMode, String teamName,
                           float fallDistance, String fingerprint) {
        this.playerNbt = playerNbt;
        this.dimensionId = dimensionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.gameMode = gameMode;
        this.teamName = teamName;
        this.fallDistance = fallDistance;
        this.fingerprint = fingerprint;
    }

    public static PlayerSnapshot capture(ServerPlayer player) {
        CompoundTag nbt = new CompoundTag();
        player.saveWithoutId(nbt);

        MinecraftServer server = player.getServer();
        String dimension = player.level().dimension().location().toString();

        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(player.getScoreboardName());

        return new PlayerSnapshot(
                nbt,
                dimension,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer().getName(),
                team == null ? null : team.getName(),
                player.fallDistance,
                fingerprintOf(nbt)
        );
    }

    /**
     * Writes the snapshot back onto the player.
     *
     * <p>Order matters: teleport first so the player is in the right dimension, then read the tag
     * (which restores position, inventory, effects and hunger), then fix up the fields that are not
     * part of the entity tag.</p>
     */
    public void restore(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId));
        ServerLevel world = server.getLevel(key);
        if (world == null) {
            throw new IllegalStateException("Snapshot points at unknown dimension " + dimensionId);
        }

        player.stopRiding();
        player.teleportTo(world, x, y, z, yaw, pitch);
        player.load(playerNbt.copy());

        float maxHealth = player.getMaxHealth();
        if (player.getHealth() <= 0.0F || player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }
        player.fallDistance = fallDistance;
        // Authorised change: this is the restore path, and it runs while the player may still be
        // inside the arena dimension, where the sandbox rule refuses mode changes.
        Sandbox.changeGameMode(player, GameType.byName(gameMode, GameType.SURVIVAL));
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);

        if (teamName != null) {
            PlayerTeam team = server.getScoreboard().getPlayerTeam(teamName);
            if (team != null) {
                server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
            }
        }

        player.inventoryMenu.broadcastFullState();
        player.containerMenu.broadcastChanges();
        player.connection.teleport(x, y, z, yaw, pitch);
    }

    /**
     * Re-reads the player after a restore and compares against the snapshot. This is the self-check
     * that turns "restore silently lost an item" into a logged anomaly instead of a support ticket.
     */
    public boolean verify(ServerPlayer player) {
        CompoundTag current = new CompoundTag();
        player.saveWithoutId(current);
        return fingerprintOf(current).equals(fingerprint);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public void writeTo(Path target) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT_VERSION);
        root.put("player", playerNbt);
        root.putString("dimension", dimensionId);
        root.putDouble("x", x);
        root.putDouble("y", y);
        root.putDouble("z", z);
        root.putFloat("yaw", yaw);
        root.putFloat("pitch", pitch);
        root.putString("gameMode", gameMode);
        if (teamName != null) {
            root.putString("team", teamName);
        }
        root.putFloat("fallDistance", fallDistance);
        root.putString("fingerprint", fingerprint);

        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static PlayerSnapshot readFrom(Path source) throws IOException {
        CompoundTag root = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
        int format = root.getInt("format");
        if (format != FORMAT_VERSION) {
            throw new IOException("Unsupported snapshot format " + format + " in " + source);
        }
        CompoundTag playerNbt = root.getCompound("player");
        return new PlayerSnapshot(
                playerNbt,
                root.getString("dimension"),
                root.getDouble("x"), root.getDouble("y"), root.getDouble("z"),
                root.getFloat("yaw"), root.getFloat("pitch"),
                root.getString("gameMode"),
                root.contains("team", Tag.TAG_STRING) ? root.getString("team") : null,
                root.getFloat("fallDistance"),
                root.getString("fingerprint")
        );
    }

    /**
     * Fingerprint over the fields that must survive a round-trip exactly. Position, motion and
     * on-ground flags are deliberately excluded: they are expected to differ between capture and
     * restore, and including them would turn every restore into a false-positive anomaly.
     */
    private static final String[] DIGEST_KEYS = {
            "Inventory", "EnderItems", "XpLevel", "XpP", "XpTotal",
            "Health", "foodLevel", "foodSaturationLevel", "foodTickTimer", "exhaustion",
            "active_effects", "Attributes", "abilities", "SelectedItemSlot"
    };

    private static String fingerprintOf(CompoundTag nbt) {
        CompoundTag subset = new CompoundTag();
        for (String key : DIGEST_KEYS) {
            Tag element = nbt.get(key);
            if (element != null) {
                subset.put(key, element.copy());
            }
        }
        return PvPocketStorage.sha256(subset.toString().getBytes(StandardCharsets.UTF_8));
    }
}
