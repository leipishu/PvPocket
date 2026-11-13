package com.leipishu.pvpocket.platform.neoforge;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.PvPocketClient;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.client.platform.neoforge.NeoForgeClientPlatform;
import com.leipishu.pvpocket.platform.PvBootstrap;
import com.leipishu.pvpocket.platform.PvPlatform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entrypoint: installs the server and client platforms and wires both sides.
 *
 * <p>NeoForge has a single constructor rather than a separate client entrypoint, so the client
 * half is selected by the physical side {@link FMLEnvironment} reports. That branch describes
 * where the jar runs, not a business decision.
 *
 * <p>Client classes are referenced only inside the {@code Dist.CLIENT} branch. A dedicated
 * server ships these classes but never resolves them, which keeps client code unloaded there.
 */
@Mod(PvPocket.MOD_ID)
public final class NeoForgeEntrypoint {

    public NeoForgeEntrypoint(IEventBus modBus, ModContainer container) {
        NeoForgePlatform platform = new NeoForgePlatform(modBus, NeoForge.EVENT_BUS);
        PvPlatform.install(platform);
        PvBootstrap.wire(platform);

        // 21.9 turned the public `dist` field into a getter.
//? if >=1.21.11 {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
//?} else {
        if (FMLEnvironment.dist == Dist.CLIENT) {
//?}
            NeoForgeClientPlatform client =
                    new NeoForgeClientPlatform(platform, modBus, NeoForge.EVENT_BUS);
            PvClientPlatform.install(client);
            PvPocketClient.wire(client);
        }
    }
}
