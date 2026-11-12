package com.leipishu.pvpocket.client.platform.fabric;

import com.leipishu.pvpocket.client.PvPocketClient;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 的客户端入口。
 *
 * <p>三行，与 {@code FabricEntrypoint} 同形。这个类存在的唯一理由是
 * {@code fabric.mod.json} 的 {@code client} 入口必须指向一个实现了
 * {@link ClientModInitializer} 的类——那是 Fabric 的类型。接线的全部内容在
 * {@link PvPocketClient#wire} 里，NeoForge 那一格调的是同一个方法。</p>
 */
public final class FabricClientEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PvClientPlatform.install(new FabricClientPlatform());
        PvPocketClient.wire(PvClientPlatform.get());
    }
}
