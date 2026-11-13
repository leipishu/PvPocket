package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: one spectator intent.
 *
 * <p>One payload with an action byte rather than a payload type per intent, because the session id
 * is the only other field any of them carries. The server re-validates every action against the
 * live session, so this payload expresses intent only; a refused action answers with a chat line.
 */
public record SpectateRequestPayload(int action, String sessionId) implements CustomPacketPayload {

    /** Re-send the match list to the sender. The panel's refresh button. */
    public static final int ACTION_LIST = 0;
    /** Enter the named session as a spectator. */
    public static final int ACTION_JOIN = 1;
    /** Stop watching whatever the sender is watching. */
    public static final int ACTION_LEAVE = 2;
    /** Move the camera to the next participant. Bound to a key client-side. */
    public static final int ACTION_CYCLE = 3;
    /**
     * Drop the camera back onto the spectator's own body, without leaving spectator mode.
     *
     * <p>Distinct from {@link #ACTION_LEAVE}, which stops watching and puts the player back where
     * they were. Vanilla spectator mode offers the same detach.
     */
    public static final int ACTION_DETACH = 4;

    public static final Type<SpectateRequestPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "spectate_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpectateRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpectateRequestPayload::action,
            ByteBufCodecs.STRING_UTF8, SpectateRequestPayload::sessionId,
            SpectateRequestPayload::new);

    public static SpectateRequestPayload list() {
        return new SpectateRequestPayload(ACTION_LIST, "");
    }

    public static SpectateRequestPayload join(String sessionId) {
        return new SpectateRequestPayload(ACTION_JOIN, sessionId);
    }

    public static SpectateRequestPayload leave() {
        return new SpectateRequestPayload(ACTION_LEAVE, "");
    }

    public static SpectateRequestPayload cycle() {
        return new SpectateRequestPayload(ACTION_CYCLE, "");
    }

    public static SpectateRequestPayload detach() {
        return new SpectateRequestPayload(ACTION_DETACH, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
