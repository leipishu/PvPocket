package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.network.PossessPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * The hotbar of the body a spectator is riding in.
 *
 * <p>Vanilla draws no hotbar in spectator mode, which is correct for a free-flying ghost and wrong
 * for someone attached to a fighter: the whole point of riding in somebody's head is to see what
 * they have in their hand and what they are about to switch to. Nine slots come from the server as
 * {@code id,count} pairs. The held item in front of the camera is a separate job — vanilla skips
 * the first-person hand entirely in spectator mode, so {@code PossessedHandRenderer} draws it and
 * reads {@link #state()} from here to know whose hand to draw.</p>
 *
 * <p>Drawn where the vanilla hotbar would be, at the same size, so it reads as "this player's
 * hotbar" rather than as another overlay. The selected slot is the only thing highlighted, because
 * it is the only slot whose state is about to matter.</p>
 *
 * <p>Nothing here is interactive. A spectator cannot press 1-9 to change somebody else's held item,
 * and a HUD that looked clickable would be lying.</p>
 */
@Environment(EnvType.CLIENT)
public final class PossessionHud implements HudRenderCallback {

    private static final int SLOT = 20;
    private static final int PANEL_W = SLOT * 9 + 2;
    private static final int PANEL_H = SLOT + 2;
    /** Same offset vanilla uses for the hotbar, so the two never disagree about where "bottom" is. */
    private static final int BOTTOM = 22;

    private static PossessPayload state;
    /** Parsed once per payload rather than once per frame: nine registry lookups per frame is silly. */
    private static ItemStack[] slots = new ItemStack[9];
    private static String name = "";
    private static float health;
    private static int armor;
    private static int selected;
    private static float appearT;

    private PossessionHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(new PossessionHud());
    }

    /** Called on the client thread when a payload arrives. */
    public static void update(PossessPayload payload) {
        if (payload == null || !payload.active() || payload.targetId() < 0) {
            clear();
            return;
        }
        state = payload;
        name = payload.name();
        health = payload.health();
        armor = payload.armor();
        selected = payload.selected();
        slots = parse(payload.slots());
        appearT = 0F;
    }

    public static void clear() {
        state = null;
        slots = new ItemStack[9];
        appearT = 0F;
    }

    /** True while a body is being ridden, which is also "draw the hotbar". */
    public static boolean active() {
        return state != null;
    }

    /** The current payload, or null when not possessing. Read by the hand renderer. */
    public static PossessPayload state() {
        return state;
    }

    /**
     * Turns the flat field into stacks.
     *
     * <p>Tolerant on purpose: an unknown id becomes an empty slot and a malformed group is skipped.
     * The worst case is one blank square in a HUD, and no HUD is worth throwing inside a render
     * callback — that would take the frame down, not the slot.</p>
     */
    private static ItemStack[] parse(String encoded) {
        ItemStack[] out = new ItemStack[9];
        for (int i = 0; i < out.length; i++) {
            out[i] = ItemStack.EMPTY;
        }
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        // -1 keeps trailing empty slots, which is exactly the case this encoding has to preserve.
        String[] groups = encoded.split(";", -1);
        for (int i = 0; i < Math.min(groups.length, out.length); i++) {
            String group = groups[i];
            if (group.isEmpty()) {
                continue;
            }
            int comma = group.lastIndexOf(',');
            if (comma <= 0) {
                continue;
            }
            Identifier id = Identifier.tryParse(group.substring(0, comma));
            if (id == null) {
                continue;
            }
            int count;
            try {
                count = Integer.parseInt(group.substring(comma + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            Item item = Registries.ITEM.get(id);
            if (item == null) {
                continue;
            }
            out[i] = new ItemStack(item, Math.max(1, count));
        }
        return out;
    }

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        if (state == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) {
            return;
        }
        float d = PvAnim.dt(tickCounter.getTickDelta(false));
        appearT = PvAnim.damp(appearT, 1F, d, 0.07F);
        TextRenderer tr = client.textRenderer;

        int x = (ctx.getScaledWindowWidth() - PANEL_W) / 2;
        int y = ctx.getScaledWindowHeight() - BOTTOM;

        PvDraw.shadow(ctx, x, y, PANEL_W, PANEL_H, PvTheme.RADIUS);
        PvDraw.roundPanel(ctx, x, y, PANEL_W, PANEL_H, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER, appearT),
                PvTheme.alpha(PvTheme.PANEL, 0.94F * appearT));

        for (int i = 0; i < 9; i++) {
            int sx = x + 1 + i * SLOT;
            int sy = y + 1;
            boolean chosen = i == selected;
            PvDraw.roundRect(ctx, sx, sy, SLOT, SLOT, 3,
                    PvTheme.alpha(chosen ? PvTheme.CARD_ACTIVE : PvTheme.SUNKEN, appearT));
            if (chosen) {
                // A full rounded ring, one pixel thick, inset by one. The previous version drew two
                // 2px bars on the top and bottom edges, which read as a strikethrough rather than as
                // a selection: it framed the slot in one axis and left the other open.
                PvDraw.ring(ctx, sx + 1, sy + 1, SLOT - 2, SLOT - 2, 3, 1,
                        PvTheme.alpha(PvTheme.ACCENT, appearT));
            }
            ItemStack stack = i < slots.length ? slots[i] : ItemStack.EMPTY;
            if (stack.isEmpty()) {
                continue;
            }
            ctx.drawItem(stack, sx + 2, sy + 2);
            // The count has to go through drawItemInSlot rather than drawText. Items are drawn at
            // z=150 and text at z=0, so a hand-placed number renders *behind* the icon it is
            // labelling; that helper pushes the text to z=200 first. It also brings the durability
            // bar and the cooldown wipe along for free.
            ctx.drawItemInSlot(tr, stack, sx + 2, sy + 2);
        }

        String label = name + " · HP " + Math.round(health) + " · 护甲 " + armor;
        PvDraw.textCenter(ctx, tr, label, x + PANEL_W / 2, y - 12,
                PvTheme.alpha(PvTheme.TEXT_DIM, appearT));
    }
}
