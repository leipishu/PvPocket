package com.leipishu.pvpocket;

import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.platform.PvBootstrap;
import com.leipishu.pvpocket.platform.PvPlatform;
import com.leipishu.pvpocket.platform.fabric.FabricPlatform;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 入口：装平台、注册 payload、挂接线。就这三件事。
 *
 * <p>所有「发生某事时该做什么」都在 {@link PvBootstrap} 里，那部分不含加载器的名字，所以
 * NeoForge / Forge 的入口类写出来和这里一样短，只是换一个 {@link PvPlatform} 实现。</p>
 *
 * <p>{@link #onInitialize()} 里不要加业务代码：这个类被加载的那一刻平台还没装好，
 * 任何走 {@code PvPlatform.get()} 的调用都会抛。</p>
 */
public final class PvPocket implements ModInitializer {

    public static final String MOD_ID = "pvpocket";
    public static final Logger LOGGER = LoggerFactory.getLogger("PvPocket");

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        PvPlatform.install(new FabricPlatform());
        // payload 的注册留在网络层：codec 的类型是 Fabric 独有的（`PacketCodec`），
        // 而 NeoForge 用的是 `StreamCodec`。这一处差异由各自的网络层承担，不上升到平台接口。
        PvPocketNetworking.registerCommon();
        PvBootstrap.wire(PvPlatform.get());
    }
}
