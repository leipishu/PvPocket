package com.leipishu.pvpocket.platform.fabric;

import com.leipishu.pvpocket.platform.PvBootstrap;
import com.leipishu.pvpocket.platform.PvPlatform;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric 的入口点。装平台、挂接线，两行。
 *
 * <p>这个类之所以在 {@code platform.fabric} 而不是模组的根包里，是因为它 import 了
 * {@code net.fabricmc.api}——根包属于所有加载器共用的一侧，而入口每个加载器都不同。
 * {@code scripts/check-boundary.py} 会盯着这条线。</p>
 *
 * <p>这里不要加业务代码：这个类被加载的那一刻平台刚装好，但任何走
 * {@code PvPlatform.get()} 的调用都依赖 {@link PvPlatform#install} 已经跑过，而顺序只在这一个
 * 方法里成立。</p>
 */
public final class FabricEntrypoint implements ModInitializer {

    @Override
    public void onInitialize() {
        PvPlatform.install(new FabricPlatform());
        PvBootstrap.wire(PvPlatform.get());
    }
}
