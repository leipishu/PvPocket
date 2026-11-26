package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.ClientPrefs;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Switches the shader pack off in the arena and back on when the player leaves.
 *
 * <p>The arena's sky is ordinary geometry drawn after the translucent pass, so a pack treats it as
 * distant terrain: its fog, ambient occlusion and tone mapping all apply to something meant to be
 * sky, and the pack's own sky stays hidden underneath. Off is the honest state for a dimension the
 * pack knows nothing about.
 *
 * <p>Reached by reflection, since neither Iris nor Oculus is a dependency; every call is guarded,
 * so a loader that moved a method leaves the feature inert. Only a switch this class threw is
 * thrown back, and a player who turns the pack on inside the arena is left alone.
 */
public final class ShaderGuard {

    /** Cached class lookup; null until the first tick asks. */
    private static Boolean present;
    private static boolean inArena;
    private static boolean disabledByUs;

    private ShaderGuard() {
    }

    public static void register(PvClientPlatform platform) {
        platform.onClientTick(ShaderGuard::tick);
    }

    /** Whether a shader loader this mod can drive is installed. */
    public static boolean present() {
        if (present == null) {
            present = find("net.irisshaders.iris.api.v0.IrisApi") != null
                    || find("net.irisshaders.iris.Iris") != null;
        }
        return present;
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        boolean now = level != null && ArenaFog.inArena(level);
        if (now == inArena) {
            if (now && disabledByUs && shadersInUse()) {
                // The player turned it back on: that is their call, and this class stops owning
                // the switch until the next time they enter.
                disabledByUs = false;
            }
            return;
        }
        inArena = now;
        if (now) {
            if (ClientPrefs.disableShadersInArena() && shadersInUse()) {
                disabledByUs = setShaders(false);
            }
        } else if (disabledByUs) {
            // Re-enabled whatever the preference says now: this class is the one that turned it
            // off, and leaving it off after the arena would be a change the player never made.
            setShaders(true);
            disabledByUs = false;
        }
    }

    /** Whether a pack is currently driving the pipeline. */
    public static boolean shadersInUse() {
        Class<?> api = find("net.irisshaders.iris.api.v0.IrisApi");
        if (api != null) {
            try {
                Object instance = api.getMethod("getInstance").invoke(null);
                Object inUse = api.getMethod("isShaderPackInUse").invoke(instance);
                return Boolean.TRUE.equals(inUse);
            } catch (Throwable ignored) {
                // Fall through: the API module can be absent while the loader is present.
            }
        }
        return false;
    }

    /**
     * Turns the pack on or off.
     *
     * @return whether the switch was thrown, so the caller knows if it owns it
     */
    private static boolean setShaders(boolean enabled) {
        Class<?> iris = find("net.irisshaders.iris.Iris");
        if (iris == null) {
            return false;
        }
        // Two shapes across Iris versions: a direct setter, or the config object. Both are followed
        // by a reload, which is what actually rebuilds the pipeline.
        try {
            iris.getMethod("setShadersEnabled", boolean.class).invoke(null, enabled);
            iris.getMethod("reload").invoke(null);
            PvPocket.LOGGER.info("Shader pack {} for the arena", enabled ? "re-enabled" : "disabled");
            return true;
        } catch (Throwable ignored) {
            // Try the config object.
        }
        try {
            // In memory only, never saved: the state lasts a match, and walking in and out of an
            // arena should not rewrite the player's own iris.properties.
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
            iris.getMethod("reload").invoke(null);
            PvPocket.LOGGER.info("Shader pack {} for the arena", enabled ? "re-enabled" : "disabled");
            return true;
        } catch (Throwable e) {
            PvPocket.LOGGER.warn("Cannot {} the shader pack: {}", enabled ? "re-enable" : "disable", e);
            return false;
        }
    }

    /** Loads a class by name, or null when it is not on the classpath. */
    private static Class<?> find(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
