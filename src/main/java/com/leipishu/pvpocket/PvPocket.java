package com.leipishu.pvpocket;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组的身份与常量：id、日志、命名空间下的 {@link ResourceLocation}。
 *
 * <p><b>这里没有加载器的东西，也不该有。</b>入口点不在这个类里——Fabric 的入口是
 * {@code platform.fabric.FabricEntrypoint}，NeoForge 的会是另一个。</p>
 *
 * <p>身份与入口分开的理由很实际：身份被九十多处引用，它必须对所有加载器是同一个类；而入口
 * 每个加载器都不一样，还要 import 各自的 {@code ModInitializer}。混在一起的结果就是这个类
 * 出现在「加载器边界」的违规清单里。</p>
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
