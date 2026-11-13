package com.leipishu.pvpocket.snapshot;

import com.leipishu.pvpocket.compat.PvNbt;

import com.leipishu.pvpocket.compat.PvCompat;

import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A copy of a player's survival state, taken before the first round and written back afterwards.
 *
 * <p>The player tag is copied verbatim and restored with {@code player.load()}, which covers
 * inventory, ender chest, experience, effects, hunger and mod-attached data. Fields the tag does
 * not carry (dimension, team, fall distance) are stored beside it.
 *
 * <p>Base attribute values are stored separately: vanilla saves an attribute only when it has been
 * modified, and {@code load()} merges rather than resets, so the value the arena wrote to
 * {@code max_health} would otherwise survive the restore.
 */
public final class PlayerSnapshot {

    private static final int FORMAT_VERSION = 2;

    /** NaN when the snapshot predates base-value capture; readers fall back to the defaults. */
    private static final float UNKNOWN_BASE = Float.NaN;

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
    private final float maxHealthBase;
    private final float knockbackResistanceBase;
    private final String fingerprint;

    private PlayerSnapshot(CompoundTag playerNbt, String dimensionId, double x, double y, double z,
                           float yaw, float pitch, String gameMode, String teamName,
                           float fallDistance, float maxHealthBase, float knockbackResistanceBase,
                           String fingerprint) {
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
        this.maxHealthBase = maxHealthBase;
        this.knockbackResistanceBase = knockbackResistanceBase;
        this.fingerprint = fingerprint;
    }

    public static PlayerSnapshot capture(ServerPlayer player) {
        CompoundTag nbt = PvNbt.save(player);

        MinecraftServer server = PvCompat.server(player);
        String dimension = PvCompat.dimensionId(player.level().dimension()).toString();

        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(player.getScoreboardName());

        return new PlayerSnapshot(
                nbt,
                dimension,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer().getName(),
                team == null ? null : team.getName(),
                (float) player.fallDistance,
                baseOf(player, Attributes.MAX_HEALTH),
                baseOf(player, Attributes.KNOCKBACK_RESISTANCE),
                fingerprintOf(nbt)
        );
    }

    private static float baseOf(ServerPlayer player, Holder<Attribute> attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? UNKNOWN_BASE : (float) instance.getBaseValue();
    }

    /**
     * Writes the snapshot back onto the player.
     *
     * <p>Teleport first, then load the tag, then repair the fields the tag does not carry.
     */
    public void restore(ServerPlayer player) {
        MinecraftServer server = PvCompat.server(player);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId));
        ServerLevel world = server.getLevel(key);
        if (world == null) {
            throw new IllegalStateException("Snapshot points at unknown dimension " + dimensionId);
        }

        player.stopRiding();
        PvCompat.teleport(player, world, x, y, z, yaw, pitch);
        PvNbt.load(player, playerNbt.copy(), player.level().registryAccess());

        // load() merges: an attribute absent from the tag keeps the value the arena wrote.
        resetBase(player, Attributes.MAX_HEALTH, maxHealthBase);
        resetBase(player, Attributes.KNOCKBACK_RESISTANCE, knockbackResistanceBase);

        float maxHealth = player.getMaxHealth();
        if (player.getHealth() <= 0.0F || player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }
        player.fallDistance = fallDistance;
        // The restore path may change mode even inside the arena dimension.
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
     * Sets one attribute base back to its pre-match value.
     *
     * <p>A snapshot that predates the recorded base falls back to the entity type's default, which
     * is the server's normal rather than a hard-coded 20.
     */
    private static void resetBase(ServerPlayer player, Holder<Attribute> attribute, float saved) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.setBaseValue(Float.isNaN(saved) ? defaultBase(attribute) : saved);
    }

    private static double defaultBase(Holder<Attribute> attribute) {
        return DefaultAttributes.getSupplier(EntityType.PLAYER).getValue(attribute);
    }

    private static double defaultBase(String attributeId) {
        ResourceLocation id = ResourceLocation.tryParse(attributeId);
        if (id == null) {
            return Double.NaN;
        }
        return BuiltInRegistries.ATTRIBUTE.getHolder(id)
                .map(holder -> defaultBase((Holder<Attribute>) holder))
                .orElse(Double.NaN);
    }

    /**
     * Re-reads the player and compares it against the snapshot.
     *
     * @return true when every digested field matches
     */
    public boolean verify(ServerPlayer player) {
        CompoundTag current = PvNbt.save(player);
        return fingerprintOf(current).equals(fingerprint);
    }

    /**
     * Names the digested fields that differ from the snapshot, for the anomaly line.
     *
     * @return {@code key before -> after} entries joined by {@code ;}, or {@code (none)}
     */
    public String mismatch(ServerPlayer player) {
        CompoundTag current = PvNbt.save(player);
        List<String> out = new ArrayList<>();
        for (String key : DIGEST_KEYS) {
            String before = describe(key, playerNbt);
            String after = describe(key, current);
            if (!before.equals(after)) {
                out.add(key + " " + before + " -> " + after);
            }
        }
        return out.isEmpty() ? "(none)" : String.join("; ", out);
    }

    private static String describe(String key, CompoundTag nbt) {
        Tag element = nbt.get(key);
        if (element == null) {
            return "<absent>";
        }
        // Same normalisation as the fingerprint, or every restore would report `attributes`.
        Tag shown = key.equals("attributes") && element instanceof ListTag list
                ? canonicalAttributes(list)
                : element;
        String text = shown.toString();
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
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
        root.putFloat("maxHealthBase", maxHealthBase);
        root.putFloat("knockbackResistanceBase", knockbackResistanceBase);
        root.putString("fingerprint", fingerprint);

        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static PlayerSnapshot readFrom(Path source) throws IOException {
        CompoundTag root = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
        int format = PvNbt.getInt(root, "format");
        // Format 1 recorded no base values. Reading it must still work: a session can be in flight
        // across an upgrade, and its snapshot is the only way back out of the arena.
        if (format < 1 || format > FORMAT_VERSION) {
            throw new IOException("Unsupported snapshot format " + format + " in " + source);
        }
        CompoundTag playerNbt = PvNbt.getCompound(root, "player");
        return new PlayerSnapshot(
                playerNbt,
                PvNbt.getString(root, "dimension"),
                PvNbt.getDouble(root, "x"), PvNbt.getDouble(root, "y"), PvNbt.getDouble(root, "z"),
                root.getFloat("yaw"), root.getFloat("pitch"),
                root.getString("gameMode"),
                PvNbt.getStringOrNull(root, "team"),
                root.getFloat("fallDistance"),
                root.contains("maxHealthBase")
                        ? root.getFloat("maxHealthBase") : UNKNOWN_BASE,
                root.contains("knockbackResistanceBase")
                        ? root.getFloat("knockbackResistanceBase") : UNKNOWN_BASE,
                root.getString("fingerprint")
        );
    }

    /**
     * Fields that must survive a round trip unchanged. Position, motion and on-ground flags are
     * excluded because they legitimately differ between capture and restore.
     *
     * <p>Key names follow the player tag, where the attribute list is lower-case {@code attributes}
     * since 1.20.5.
     */
    private static final String[] DIGEST_KEYS = {
            "Inventory", "EnderItems", "XpLevel", "XpP", "XpTotal",
            "Health", "foodLevel", "foodSaturationLevel", "foodTickTimer", "foodExhaustionLevel",
            "active_effects", "attributes", "abilities", "SelectedItemSlot"
    };

    private static String fingerprintOf(CompoundTag nbt) {
        CompoundTag subset = new CompoundTag();
        for (String key : DIGEST_KEYS) {
            Tag element = nbt.get(key);
            if (element == null) {
                continue;
            }
            if (key.equals("attributes") && element instanceof ListTag list) {
                subset.put(key, canonicalAttributes(list));
            } else {
                subset.put(key, element.copy());
            }
        }
        return PvPocketStorage.sha256(subset.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Drops attribute entries sitting at their default and sorts the rest by id.
     *
     * <p>Restore creates entries that vanilla would have omitted, so without this every correct
     * restore would report the attribute it just repaired.
     */
    private static ListTag canonicalAttributes(ListTag list) {
        List<CompoundTag> kept = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = PvNbt.getCompound(list, i);
            boolean modified = entry.contains("modifiers")
                    && !PvNbt.getList(entry, "modifiers").isEmpty();
            // NaN (an attribute this build does not know) compares false, so unknown entries stay.
            boolean atDefault = Math.abs(entry.getDouble("base")
                    - defaultBase(PvNbt.getString(entry, "id"))) < 1.0E-4;
            if (modified || !atDefault) {
                kept.add(entry);
            }
        }
        kept.sort(Comparator.comparing(entry -> PvNbt.getString(entry, "id")));
        ListTag out = new ListTag();
        kept.forEach(out::add);
        return out;
    }
}
