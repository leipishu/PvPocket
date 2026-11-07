package com.leipishu.pvpocket.client.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.preset.Preset;
import com.leipishu.pvpocket.preset.PresetKind;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The player's own presets, on the player's own machine.
 *
 * <p><b>Client-side, not world-side, and that is the point.</b> A loadout or a rule set is something
 * a player carries between servers; keeping it in the world save would mean re-creating it on every
 * server they join. {@code config/pvpocket/presets.json} is the one place that survives all of them.
 * The server never reads this file — when a preset is actually used it travels as an ordinary rule
 * or loadout submission, exactly like a hand-built one, so nothing downstream needs to know presets
 * exist.</p>
 *
 * <p>No locking and no cache invalidation: the file is read once, and every mutation rewrites the
 * whole thing through {@link PvPocketStorage#writeAtomically}. Presets are small and edited by hand
 * at human speed, so an append-only log would be more machinery than the problem deserves.</p>
 */
public final class PresetStore {

    /** Upper bound on stored presets. Well past what anyone names by hand; a guard, not a policy. */
    private static final int MAX_PRESETS = 64;
    private static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static PresetStore instance;

    /** On-disk shape. Public fields so Gson needs no adapters, and flat so old files stay readable. */
    private static final class File_ {
        int version = VERSION;
        List<Entry> presets = new ArrayList<>();
    }

    private static final class Entry {
        String kind;
        String name;
        String encoded;
        long savedAt;
    }

    private final Path file;
    private final List<Preset> presets = new ArrayList<>();
    private boolean loaded;

    private PresetStore(Path file) {
        this.file = file;
    }

    public static PresetStore get() {
        if (instance == null) {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("pvpocket");
            instance = new PresetStore(dir.resolve("presets.json"));
        }
        return instance;
    }

    /** Newest first — the one just saved is the one being looked for. */
    public List<Preset> of(PresetKind kind) {
        ensureLoaded();
        List<Preset> out = new ArrayList<>();
        for (Preset preset : presets) {
            if (preset.kind() == kind) {
                out.add(preset);
            }
        }
        out.sort(Comparator.comparingLong(Preset::savedAt).reversed());
        return out;
    }

    public Preset find(PresetKind kind, String name) {
        ensureLoaded();
        for (Preset preset : presets) {
            if (preset.kind() == kind && preset.name().equals(name)) {
                return preset;
            }
        }
        return null;
    }

    public int count(PresetKind kind) {
        return of(kind).size();
    }

    /**
     * Saves, replacing any preset of the same kind and name.
     *
     * <p>Returns false when the name is unusable or the store is full. The caller shows the reason:
     * a save button that silently does nothing is worse than one that refuses out loud.</p>
     */
    public boolean put(PresetKind kind, String rawName, String encoded) {
        ensureLoaded();
        String name = Preset.cleanName(rawName);
        if (!Preset.isValidName(name) || encoded == null || encoded.isEmpty()) {
            return false;
        }
        boolean replacing = false;
        for (int i = 0; i < presets.size(); i++) {
            Preset preset = presets.get(i);
            if (preset.kind() == kind && preset.name().equals(name)) {
                presets.set(i, new Preset(kind, name, encoded, System.currentTimeMillis()));
                replacing = true;
                break;
            }
        }
        if (!replacing) {
            if (presets.size() >= MAX_PRESETS) {
                return false;
            }
            presets.add(new Preset(kind, name, encoded, System.currentTimeMillis()));
        }
        save();
        return true;
    }

    public void remove(PresetKind kind, String name) {
        ensureLoaded();
        if (presets.removeIf(preset -> preset.kind() == kind && preset.name().equals(name))) {
            save();
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            File_ parsed = GSON.fromJson(json, File_.class);
            if (parsed == null || parsed.presets == null) {
                return;
            }
            for (Entry entry : parsed.presets) {
                PresetKind kind = PresetKind.byId(entry.kind);
                String name = Preset.cleanName(entry.name);
                // Unknown kinds and blank names are dropped one at a time rather than discarding the
                // whole file: one bad row from a newer build must not cost the player the other nine.
                if (kind == null || !Preset.isValidName(name) || entry.encoded == null) {
                    continue;
                }
                presets.add(new Preset(kind, name, entry.encoded, entry.savedAt));
            }
        } catch (Exception e) {
            PvPocket.LOGGER.warn("Discarding unreadable preset file {}", file, e);
        }
    }

    private void save() {
        File_ out = new File_();
        for (Preset preset : presets) {
            Entry entry = new Entry();
            entry.kind = preset.kind().id;
            entry.name = preset.name();
            entry.encoded = preset.encoded();
            entry.savedAt = preset.savedAt();
            out.presets.add(entry);
        }
        try {
            PvPocketStorage.writeAtomically(file, GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot save presets to {}", file, e);
        }
    }
}
