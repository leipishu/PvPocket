package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first-person half of the 1.8 block feedback: four corner brackets around the crosshair.
 *
 * <p>1.8 itself drew nothing in first person — the sword sat in the default position and the only
 * evidence of a block was the reduced damage. That was survivable in 1.8 because everybody had spent
 * years with the mechanic; here it reads as "right-click does nothing", which is a bug report.</p>
 *
 * <p>Brackets rather than a ring or a tint: the crosshair has to stay readable, and the middle of the
 * screen is where a player is aiming. Drawn in the mod's single warm accent so it is recognisably
 * this mod's cue and not a vanilla one.</p>
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    /** Length of each bracket arm, in GUI pixels. */
    private static final int ARM = 4;

    /** Half the box around the 15x15 crosshair. */
    private static final int SPAN = 11;

    /** {@code #F5A524} at 72% alpha — the accent, dimmed so it does not compete with the crosshair. */
    private static final int GUARD_COLOR = 0xB8F5A524;

    @Inject(method = "renderCrosshair", at = @At("TAIL"))
    private void pvpocket$blockingGuard(DrawContext context, RenderTickCounter tickCounter,
                                        CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !CombatState.isBlocking(client.player.getUuid())) {
            return;
        }
        int centerX = context.getScaledWindowWidth() / 2;
        int centerY = context.getScaledWindowHeight() / 2;
        int left = centerX - SPAN;
        int top = centerY - SPAN;
        int right = centerX + SPAN;
        int bottom = centerY + SPAN;
        // Eight 1px bars: a horizontal and a vertical arm per corner. fill() rather than a rounded
        // rectangle for the same reason the panel code uses it — a 1px shape is the one case where
        // the per-row slicing of roundRect degenerates and draws nothing.
        context.fill(left, top, left + ARM, top + 1, GUARD_COLOR);
        context.fill(left, top, left + 1, top + ARM, GUARD_COLOR);
        context.fill(right - ARM, top, right, top + 1, GUARD_COLOR);
        context.fill(right - 1, top, right, top + ARM, GUARD_COLOR);
        context.fill(left, bottom - 1, left + ARM, bottom, GUARD_COLOR);
        context.fill(left, bottom - ARM, left + 1, bottom, GUARD_COLOR);
        context.fill(right - ARM, bottom - 1, right, bottom, GUARD_COLOR);
        context.fill(right - 1, bottom - ARM, right, bottom, GUARD_COLOR);
    }
}
