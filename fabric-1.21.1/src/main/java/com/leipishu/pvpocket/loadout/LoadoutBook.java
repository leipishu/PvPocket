package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.storage.PvPocketStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Each player's saved loadout, outside of any duel.
 *
 * <p>Without this there is nowhere to stand: the editor was only reachable from a duel's rule panel,
 * so a player who wanted to decide what to bring <em>before</em> challenging anyone had to first
 * challenge someone and then edit — which is backwards, and also means the challenge carries a
 * loadout nobody chose.</p>
 *
 * <p>One file per player rather than one map file. A shared map would need read-modify-write on every
 * save, and two players saving in the same tick would lose one of the two. Per-player files are
 * independent, so {@link PvPocketStorage#writeAtomically} is enough and no locking is needed.</p>
 *
 * <p>This is a convenience, not a source of truth: the loadout that actually gets granted is the one
 * stored on the {@code Participant} and journalled as {@code LOADOUT_APPLIED}. Editing this book mid
 * -duel cannot change a round that is already running.</p>
 */
public final class LoadoutBook {

    /** On-disk shape. Kept deliberately flat so an old file stays readable after a field is added. */
    private static final class Saved {
        String encoded;
        String savedAt;
    }

    private final PvPocketStorage storage;
    /** Decoded copies, so the common path (one player opening the editor twice) touches no disk. */
    private final Map<UUID, Loadout> cache = new HashMap<>();
    /** Players whose file has been consulted; distinguishes "no file" from "not looked yet". */
    private final Map<UUID, Boolean> known = new HashMap<>();

    public LoadoutBook(PvPocketStorage storage) {
        this.storage = storage;
    }

    /**
     * The player's saved loadout, or the default preset if they have never saved one.
     *
     * <p>Returns a copy every time. Callers mutate what they get back (the editor does, and so does
     * slot assignment); handing out the cached instance would let one caller's edit leak into the
     * next caller's view before it was ever saved.</p>
     */
    public Loadout get(UUID player) {
        Loadout cached = cache.get(player);
        if (cached != null) {
            return cached.copy();
        }
        if (known.containsKey(player)) {
            return Loadout.defaultDuel();
        }
        known.put(player, Boolean.TRUE);
        Loadout loaded = read(player);
        if (loaded == null) {
            return Loadout.defaultDuel();
        }
        cache.put(player, loaded);
        return loaded.copy();
    }

    public void put(UUID player, Loadout loadout) {
        cache.put(player, loadout.copy());
        known.put(player, Boolean.TRUE);
        Saved saved = new Saved();
        saved.encoded = loadout.encode();
        saved.savedAt = Long.toString(System.currentTimeMillis());
        try {
            storage.writeJson(file(player), saved);
        } catch (IOException e) {
            // A failed write is not fatal: the in-memory copy still applies to the next duel this
            // session, and the player is told when they next look. Losing a cosmetic preference is
            // not worth aborting a duel over.
            PvPocket.LOGGER.error("Cannot save loadout for {}", player, e);
        }
    }

    /** Drops the saved loadout, falling back to the default preset. */
    public void clear(UUID player) {
        cache.remove(player);
        known.put(player, Boolean.TRUE);
        try {
            Files.deleteIfExists(file(player));
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot delete loadout for {}", player, e);
        }
    }

    private Path file(UUID player) {
        return storage.loadoutFile(player.toString());
    }

    private Loadout read(UUID player) {
        Path path = file(player);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            Saved saved = storage.readJson(path, Saved.class);
            if (saved == null || saved.encoded == null || saved.encoded.isBlank()) {
                return null;
            }
            Loadout loadout = Loadout.decode(saved.encoded);
            // A saved loadout that no longer validates (catalog shrank between versions) is dropped
            // rather than partially honoured: granting half of it would be worse than the preset.
            return loadout.validate().isEmpty() ? loadout : null;
        } catch (Exception e) {
            PvPocket.LOGGER.warn("Discarding unreadable loadout file {}", path, e);
            return null;
        }
    }
}
