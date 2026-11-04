package com.leipishu.pvpocket;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Loaded on both the client and the dedicated server.
 *
 * <p>This class is intentionally thin: it only holds shared constants and the initialization
 * hook. Feature wiring is added as the implementation stages land.</p>
 */
public final class PvPocket implements ModInitializer {

    public static final String MOD_ID = "pvpocket";
    public static final Logger LOGGER = LoggerFactory.getLogger("PvPocket");

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("PvPocket common bootstrap complete (mod id: {})", MOD_ID);
    }
}
