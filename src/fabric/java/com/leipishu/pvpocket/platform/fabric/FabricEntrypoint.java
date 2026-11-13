package com.leipishu.pvpocket.platform.fabric;

import com.leipishu.pvpocket.platform.PvBootstrap;
import com.leipishu.pvpocket.platform.PvPlatform;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoint: installs the platform and wires the mod.
 *
 * <p>Lives in {@code platform.fabric} because it imports {@code net.fabricmc.api}, which the
 * shared root package must not. {@code scripts/check-boundary.py} enforces that split.
 *
 * <p>Add no business code here. Calls through {@code PvPlatform.get()} require
 * {@link PvPlatform#install} to have run first, and that order holds only inside this method.
 */
public final class FabricEntrypoint implements ModInitializer {

    @Override
    public void onInitialize() {
        PvPlatform.install(new FabricPlatform());
        PvBootstrap.wire(PvPlatform.get());
    }
}
