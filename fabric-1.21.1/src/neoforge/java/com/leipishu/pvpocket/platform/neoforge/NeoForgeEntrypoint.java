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
 * NeoForge 的入口。
 *
 * <p>与 {@code FabricEntrypoint} 的差别只有一处：Fabric 有独立的客户端入口，NeoForge 只有一个
 * 构造器，客户端那一半靠 {@link FMLEnvironment#dist} 判断。所以这里多一个 if——但那个 if 是
 * 「这个 jar 跑在哪个物理端」，不是业务判断，属于平台。</p>
 *
 * <p>客户端类只在 {@code Dist.CLIENT} 分支里被引用。单 jar 打包意味着专用服务端也带着这些
 * class，但只要不执行到这一行，JVM 就不会去解析它们——这是 NeoForge 侧保证「专用服务端不加载
 * 客户端代码」的方式，Fabric 那边靠的是 split source set。</p>
 */
@Mod(PvPocket.MOD_ID)
public final class NeoForgeEntrypoint {

    public NeoForgeEntrypoint(IEventBus modBus, ModContainer container) {
        NeoForgePlatform platform = new NeoForgePlatform(modBus, NeoForge.EVENT_BUS);
        PvPlatform.install(platform);
        PvBootstrap.wire(platform);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForgeClientPlatform client =
                    new NeoForgeClientPlatform(platform, modBus, NeoForge.EVENT_BUS);
            PvClientPlatform.install(client);
            PvPocketClient.wire(client);
        }
    }
}
