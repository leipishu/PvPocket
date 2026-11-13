package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.compat.PvCompat;
import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.PossessPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * The hotbar of the body a spectator is riding in.
 *
 * <p>Vanilla draws no hotbar in spectator mode. Nine slots arrive from the server as
 * {@code id,count} pairs. The held item is drawn by {@code PossessedHandRenderer}, which reads
 * {@link #state()} to know whose hand to draw.
 *
 * <p>The bar sits where the vanilla hotbar would be, at the same size. Only the selected slot is
 * highlighted.
 *
 * <p>Nothing here is interactive: a spectator cannot change somebody else's held item.
 */
public final class PossessionHud implements PvClientPlatform.HudLayer {

    private static final int SLOT = 20;
    private static final int PANEL_W = SLOT * 9 + 2;
    private static final int PANEL_H = SLOT + 2;
    /** Same offset vanilla uses for the hotbar, so both agree on where "bottom" is. */
    private static final int BOTTOM = 22;

    private static PossessPayload state;
    /** Parsed once per payload; nine registry lookups per frame is wasteful. */
    private static ItemStack[] slots = new ItemStack[9];
    private static String name = "";
    private static float health;
    private static int armor;
    private static int selected;
    private static float appearT;

    private PossessionHud() {
    }

    public static void register() {
        PvClientPlatform.get().onHudRender(new PossessionHud());
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
     * <p>An unknown id becomes an empty slot and a malformed group is skipped, so a render callback
     * never throws. The worst case is one blank square in the HUD.
     */
    private static ItemStack[] parse(String encoded) {
        ItemStack[] out = new ItemStack[9];
        for (int i = 0; i < out.length; i++) {
            out[i] = ItemStack.EMPTY;
        }
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        // -1 keeps trailing empty slots, which this encoding has to preserve.
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
            ResourceLocation id = ResourceLocation.tryParse(group.substring(0, comma));
            if (id == null) {
                continue;
            }
            int count;
            try {
                count = Integer.parseInt(group.substring(comma + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            Item item = PvCompat.value(BuiltInRegistries.ITEM, id);
            if (item == null) {
                continue;
            }
            out[i] = new ItemStack(item, Math.max(1, count));
        }
        return out;
    }

    @Override
    public void render(GuiGraphics ctx, DeltaTracker tickCounter) {
        if (state == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) {
            return;
        }
        float d = PvAnim.dt(tickCounter.getGameTimeDeltaPartialTick(false));
        appearT = PvAnim.damp(appearT, 1F, d, 0.07F);
        Font tr = client.font;

        int x = (ctx.guiWidth() - PANEL_W) / 2;
        int y = ctx.guiHeight() - BOTTOM;

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
                // A full rounded ring, one pixel thick, inset by one.
                PvDraw.ring(ctx, sx + 1, sy + 1, SLOT - 2, SLOT - 2, 3, 1,
                        PvTheme.alpha(PvTheme.ACCENT, appearT));
            }
            ItemStack stack = i < slots.length ? slots[i] : ItemStack.EMPTY;
            if (stack.isEmpty()) {
                continue;
            }
            ctx.renderItem(stack, sx + 2, sy + 2);
            // renderItemDecorations, not renderItem: items sit at z=150, text at z=0, so the helper
            // pushes text to z=200. It also draws the durability bar and the cooldown wipe.
            ctx.renderItemDecorations(tr, stack, sx + 2, sy + 2);
        }

        String label = PvLang.str("pvpocket.gui.hud.vitals",
                name, Math.round(health), armor);
        PvDraw.textCenter(ctx, tr, label, x + PANEL_W / 2, y - 12,
                PvTheme.alpha(PvTheme.TEXT_DIM, appearT));
    }
}
