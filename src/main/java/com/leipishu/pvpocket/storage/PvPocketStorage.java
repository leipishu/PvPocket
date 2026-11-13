package com.leipishu.pvpocket.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Owns the on-disk layout under {@code <world>/pvpocket/}.
 *
 * <pre>
 *   sessions/&lt;sessionId&gt;.json      session record
 *   snapshots/&lt;sessionId&gt;/&lt;uuid&gt;.dat  survival snapshots (compressed NBT)
 *   journal/&lt;sessionId&gt;.jsonl      append-only event log
 *   loadouts/&lt;uuid&gt;.json           a player's saved loadout
 * </pre>
 *
 * <p>Every write goes through {@link #writeAtomically}, so no crash leaves a partial file.
 */
public final class PvPocketStorage {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path root;

    public PvPocketStorage(MinecraftServer server) {
        this.root = server.getWorldPath(LevelResource.ROOT).resolve("pvpocket");
    }

    public Path root() {
        return root;
    }

    public Path sessionsDir() {
        return root.resolve("sessions");
    }

    public Path snapshotsDir() {
        return root.resolve("snapshots");
    }

    public Path journalDir() {
        return root.resolve("journal");
    }

    public Path loadoutsDir() {
        return root.resolve("loadouts");
    }

    public Path loadoutFile(String playerUuid) {
        return loadoutsDir().resolve(playerUuid + ".json");
    }

    public Path sessionFile(String sessionId) {
        return sessionsDir().resolve(sessionId + ".json");
    }

    public Path snapshotDir(String sessionId) {
        return snapshotsDir().resolve(sessionId);
    }

    public Path snapshotFile(String sessionId, String playerUuid) {
        return snapshotDir(sessionId).resolve(playerUuid + ".dat");
    }

    public Path journalFile(String sessionId) {
        return journalDir().resolve(sessionId + ".jsonl");
    }

    public void ensureLayout() throws IOException {
        Files.createDirectories(sessionsDir());
        Files.createDirectories(snapshotsDir());
        Files.createDirectories(journalDir());
        Files.createDirectories(loadoutsDir());
    }

    public void writeJson(Path target, Object value) throws IOException {
        writeAtomically(target, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    public <T> T readJson(Path source, Class<T> type) throws IOException {
        return GSON.fromJson(Files.readString(source, StandardCharsets.UTF_8), type);
    }

    /**
     * Writes to a sibling temp file, flushes, then moves into place. The rename is atomic on
     * the same filesystem, so readers either see the old file or the new one, never a partial.
     */
    public static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
