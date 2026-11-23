package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: a surrender request waiting on this player.
 *
 * <p>{@code active=false} closes the prompt, whether the request was approved, refused or timed
 * out. {@code agreed}/{@code total} count the side, so a team-mate can see that the answer is
 * wanted without reading the chat. {@code secondsLeft} is the window the server is running; the
 * client never counts down on its own.
 *
 * <p>Only sent to the side that asked, and only to the members who still owe an answer.
 */
public record SurrenderStatePayload(boolean active, String requester, int agreed, int total,
                                    int secondsLeft) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SurrenderStatePayload> ID =
            new CustomPacketPayload.Type<>(PvPocket.id("surrender_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurrenderStatePayload> CODEC =
            StreamCodec.ofMember(
                    (value, buf) -> {
                        buf.writeBoolean(value.active);
                        buf.writeUtf(value.requester);
                        buf.writeVarInt(value.agreed);
                        buf.writeVarInt(value.total);
                        buf.writeVarInt(value.secondsLeft);
                    },
                    buf -> new SurrenderStatePayload(
                            buf.readBoolean(),
                            buf.readUtf(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()));

    /** The "prompt is over" packet. One instance is enough; it is immutable. */
    public static SurrenderStatePayload closed() {
        return new SurrenderStatePayload(false, "", 0, 0, 0);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
