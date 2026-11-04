package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.PvPocket;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint. PvPocket ships a mandatory client mod, so this is where the custom
 * GUI layer, HUD and combat feedback rendering will be registered.
 */
public final class PvPocketClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PvPocket.LOGGER.info("PvPocket client bootstrap complete");
    }
}
