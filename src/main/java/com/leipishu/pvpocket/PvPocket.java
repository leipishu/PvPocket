package com.leipishu.pvpocket;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's identity and shared constants: id, logger, namespaced resource locations.
 *
 * <p>Loader entry points live elsewhere: {@code platform.fabric.FabricEntrypoint} for Fabric, a
 * separate class for NeoForge. Identity stays here because it is referenced from many places and
 * must be the same class on every loader.
 */
public final class PvPocket {

    public static final String MOD_ID = "pvpocket";
    public static final Logger LOGGER = LoggerFactory.getLogger("PvPocket");

    private PvPocket() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
