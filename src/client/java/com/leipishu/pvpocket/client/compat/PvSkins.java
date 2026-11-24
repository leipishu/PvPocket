package com.leipishu.pvpocket.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.UUID;

/**
 * Player faces, resolved from a uuid that arrived over the wire.
 *
 * <p>Three steps every caller needs: parse the uuid, find that player in the level, fall back to
 * the default skin. The level is the source rather than the tab list, because a participant the
 * server is drawing a panel about is in the level by definition, and in the lobby that is
 * everybody the panel can name.
 *
 * <p>Kept in one file so the client has a single answer to "what does this uuid look like". The
 * default skin is not an error path: a player who left between two packets still gets a face.
 */
public final class PvSkins {

    private static final UUID NIL = new UUID(0L, 0L);

    private PvSkins() {
    }

    /** The skin behind this uuid, or the default one when nobody in the level owns it. */
    public static PlayerSkin of(String uuid) {
        try {
            return of(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return DefaultPlayerSkin.get(NIL);
        }
    }

    /** The same lookup for a uuid that arrived already parsed. */
    public static PlayerSkin of(UUID id) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            for (AbstractClientPlayer player : client.level.players()) {
                if (player.getUUID().equals(id)) {
                    return player.getSkin();
                }
            }
        }
        return DefaultPlayerSkin.get(id);
    }

    /** Draws one face, {@code size} pixels square, at the top left corner given. */
    public static void draw(GuiGraphics ctx, String uuid, int x, int y, int size) {
        PlayerFaceRenderer.draw(ctx, of(uuid), x, y, size);
    }
}
