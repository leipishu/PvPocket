package com.leipishu.pvpocket.snapshot;

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
 * A frozen copy of a player's survival state.
 *
 * <p>The bulk of the state is the player's own NBT, copied verbatim. That is deliberate: the
 * vanilla player tag carries inventory, ender chest, experience, effects, hunger, attributes and
 * any mod-attached persistent data, and hand-picking fields is how data loss happens. A handful of
 * things the player tag does not carry (dimension, team, fall distance) are stored alongside.</p>
 *
 * <p>A snapshot is the single source of truth for "what the player looked like before the duel".
 * It is never merged with live state, only written once and read back on restore.</p>
 *
 * <p><b>Attributes are stored twice on purpose.</b> The player tag carries them, but only the ones
 * that have been modified: vanilla's {@code AttributeMap.save()} writes nothing for an attribute
 * still sitting at its default. {@code max_health} is precisely the attribute the arena always
 * changes and precisely the one a clean player has never modified — so the tag says nothing about
 * it, {@code player.load()} leaves whatever the arena wrote in place, and the player walks out with
 * the duel's health cap. {@link #maxHealthBase} and {@link #knockbackResistanceBase} are therefore
 * read from the live entity and written back explicitly, which is what makes "back to normal"
 * mean the server's normal rather than "whatever the last round set".</p>
 */
public final class PlayerSnapshot {

    private static final int FORMAT_VERSION = 2;

    /** Sentinel for a snapshot written before the base values were recorded: fall back to defaults. */
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

        // Explicit, because load() is a merge and not a reset: an attribute the snapshot does not
        // mention keeps the value the arena gave it. This is the whole "两行血" bug — the health cap
        // was written by the round and never written back, and the restore reported success every
        // time because the fingerprint was looking at a key name vanilla had renamed.
        resetBase(player, Attributes.MAX_HEALTH, maxHealthBase);
        resetBase(player, Attributes.KNOCKBACK_RESISTANCE, knockbackResistanceBase);

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
     * Puts one attribute back to what it was before the match.
     *
     * <p>Falls back to the entity type's default when the snapshot predates the recorded base. That
     * default is the server's own idea of normal — the value another mod or a datapack configured —
     * which is exactly the "服务器正常是多少就是多少" the player expects, rather than a hard-coded 20.</p>
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
        int format = root.getInt("format");
        // Format 1 wrote no base values. Accepting it is not optional: a session can be in flight
        // across an upgrade, and refusing to read its snapshot would strand a player in the arena
        // with the duel's kit. The base falls back to the entity default, which is the same answer
        // the explicit value would have given for anyone who had not been tampered with.
        if (format < 1 || format > FORMAT_VERSION) {
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
                root.contains("maxHealthBase", Tag.TAG_FLOAT)
                        ? root.getFloat("maxHealthBase") : UNKNOWN_BASE,
                root.contains("knockbackResistanceBase", Tag.TAG_FLOAT)
                        ? root.getFloat("knockbackResistanceBase") : UNKNOWN_BASE,
                root.getString("fingerprint")
        );
    }

    /**
     * Fingerprint over the fields that must survive a round-trip exactly. Position, motion and
     * on-ground flags are deliberately excluded: they are expected to differ between capture and
     * restore, and including them would turn every restore into a false-positive anomaly.
     *
     * <p>{@code attributes} is lower-case because that is the key 1.20.5 renamed it to. The list used
     * to say {@code Attributes}, which matches nothing in a modern player tag — so the self-check
     * could not see an attribute at all, and the one bug it existed to catch (a health cap that
     * survived the match) reported {@code verified:true} for weeks.</p>
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
     * Rewrites an attribute list into a form where "at the default" and "not mentioned" compare
     * equal.
     *
     * <p>They have to, because restore creates the entry: vanilla omits a default-valued attribute
     * from the tag, but once {@code setBaseValue} has been called the attribute is written on the
     * next save. Without this normalisation every correct restore would report an anomaly for the
     * one attribute it just repaired — and an alert that always fires is the same as no alert.</p>
     */
    private static ListTag canonicalAttributes(ListTag list) {
        List<CompoundTag> kept = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            boolean modified = entry.contains("modifiers", Tag.TAG_LIST)
                    && !entry.getList("modifiers", Tag.TAG_COMPOUND).isEmpty();
            // NaN (an attribute this build does not know) compares false, so unknown entries stay.
            boolean atDefault = Math.abs(entry.getDouble("base")
                    - defaultBase(entry.getString("id"))) < 1.0E-4;
            if (modified || !atDefault) {
                kept.add(entry);
            }
        }
        kept.sort(Comparator.comparing(entry -> entry.getString("id")));
        ListTag out = new ListTag();
        kept.forEach(out::add);
        return out;
    }
}
