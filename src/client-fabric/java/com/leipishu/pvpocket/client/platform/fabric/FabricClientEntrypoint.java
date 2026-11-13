package com.leipishu.pvpocket.client.platform.fabric;

import com.leipishu.pvpocket.client.PvPocketClient;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint: installs the client platform and wires it.
 *
 * <p>Exists because {@code fabric.mod.json} must name a {@link ClientModInitializer}. The wiring
 * itself lives in {@link PvPocketClient#wire}, which the NeoForge side calls too.
 */
public final class FabricClientEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PvClientPlatform.install(new FabricClientPlatform());
        PvPocketClient.wire(PvClientPlatform.get());
    }
}
