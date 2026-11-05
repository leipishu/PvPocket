package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client to server: one of four spectator intents.
 *
 * <p>One payload with an action byte rather than four payload types, because all four are the same
 * request — "do something about spectating" — and the session id is the only field any of them
 * carries. Four types would mean four registrations, four receivers and four {@code canSend}
 * checks for a feature that is one screen.</p>
 *
 * <p>The server re-validates every action against the live session; this payload expresses intent
 * only. A client that sends {@link #ACTION_JOIN} for a session with spectating disabled, for a
 * finished series, or for a session it is already a participant of, is refused with a chat line and
 * nothing else happens.</p>
 */
public record SpectateRequestPayload(int action, String sessionId) implements CustomPayload {

    /** Re-send the match list to the sender. The panel's 刷新 button. */
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
     * <p>Separate from {@link #ACTION_LEAVE} because they answer different questions. Leaving is
     * "I am done watching, put me back where I was"; detaching is "I want to fly around and look at
     * this myself". Vanilla spectator mode has this too, and a spectator who attaches the camera and
     * then cannot get out of it is a spectator who has to disconnect to see their own inventory
     * again.</p>
     */
    public static final int ACTION_DETACH = 4;

    public static final Id<SpectateRequestPayload> ID =
            new Id<>(Identifier.of("pvpocket", "spectate_request"));

    public static final PacketCodec<RegistryByteBuf, SpectateRequestPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SpectateRequestPayload::action,
            PacketCodecs.STRING, SpectateRequestPayload::sessionId,
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
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
