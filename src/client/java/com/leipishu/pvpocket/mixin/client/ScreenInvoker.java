package com.leipishu.pvpocket.mixin.client;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Adds a widget to a screen that has already run {@code init()}.
 *
 * <p>{@code Screen#addRenderableWidget} is {@code protected}, and {@code children()} only exposes a
 * read-only list. An {@code @Invoker} keeps the signature checked by the compiler, so a renamed
 * method fails the build instead of throwing at runtime. This mixin only forwards.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T pvpocket$addRenderableWidget(T widget);
}
