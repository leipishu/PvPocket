package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.arena.Lobby;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The lobby's sky light, taken from the overworld instead of from the match's fixed time.
 *
 * <p>{@link ArenaSky} already draws the lobby under the overworld's sky. Lighting is a separate
 * path, and without this the island would be lit by the round's fixed {@code e.timeOfDay}: a noon
 * sky over blocks lit as midnight, or the reverse.
 *
 * <p>Scoped to the lobby because the value is level-wide. No plot is visible from the lobby: the
 * fog ends at 384 blocks and the nearest one is about 680 away, so overriding it while standing
 * there changes nothing that is on screen.
 */
public final class LobbyLight {

    /** Vanilla's sky darkening runs from 0 at noon to 11 at midnight. */
    private static final float DARKEN_STEPS = 11.0F;

    private LobbyLight() {
    }

    /** The darkness the lobby should use, or -1 when vanilla's answer stands. */
    public static int darken() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return -1;
        }
        if (!ArenaFog.inArena(client.level)) {
            return -1;
        }
        BlockPos at = BlockPos.containing(client.player.getX(), client.player.getY(),
                client.player.getZ());
        if (!Lobby.contains(client.level, at)) {
            return -1;
        }
        long dayTime = OverworldClock.now(ArenaSky.dayTimeOf(client.level));
        return Math.round((1.0F - ArenaSky.daylight(dayTime)) * DARKEN_STEPS);
    }
}
