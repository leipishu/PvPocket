package com.leipishu.pvpocket.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append-only JSON Lines journal, one event per line.
 *
 * <p>The journal is written <em>before</em> the corresponding state change, so a crash at any
 * point leaves the last completed intent recoverable. Lines are flushed immediately; losing at
 * most the final line is acceptable because the reconciler treats an unparseable tail as
 * "the operation did not happen".</p>
 */
public final class SessionJournal implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Path file;
    private BufferedWriter writer;

    public SessionJournal(Path file) {
        this.file = file;
    }

    public synchronized void append(String type, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be pairs");
        }
        JsonObject json = new JsonObject();
        json.addProperty("t", System.currentTimeMillis());
        json.addProperty("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            if (value == null) {
                json.addProperty(key, (String) null);
            } else if (value instanceof Number number) {
                json.addProperty(key, number);
            } else if (value instanceof Boolean bool) {
                json.addProperty(key, bool);
            } else {
                json.addProperty(key, String.valueOf(value));
            }
        }

        try {
            if (writer == null) {
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            writer.write(GSON.toJson(json));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to journal " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Journal is best-effort on shutdown; the reconciler tolerates a truncated tail.
        } finally {
            writer = null;
        }
    }
}
