package com.leipishu.pvpocket.mixin.client;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 往一个已经初始化过的界面里加控件。
 *
 * <p>原版把这个能力藏起来了：{@code Screen#addRenderableWidget} 是 {@code protected}，而公开的
 * {@code children()} 返回 {@code List<? extends GuiEventListener>}——读得到、加不进去。加载器各自
 * 绕开这一点（Fabric 的 {@code Screens.getButtons(screen)} 就是把那个 list 强转回可写形态），
 * 而绕法一旦落在业务代码里，客户端就再也搬不到别的加载器上了。</p>
 *
 * <p>用一个 {@code @Invoker} 比强转稳：签名由编译器核对，方法名写错时构建失败而不是运行时
 * {@code NoSuchMethodError}。这也是这个项目里唯一一个「为了加一个按钮」而动原版类的 mixin，
 * 所以它只做转发，不含任何逻辑。</p>
 */
@Mixin(Screen.class)
public interface ScreenInvoker {

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T pvpocket$addRenderableWidget(T widget);
}
