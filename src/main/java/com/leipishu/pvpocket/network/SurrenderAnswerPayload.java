package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: one team-mate's answer to a surrender request.
 *
 * <p>An intent, not a state change. The server decides whether a request is open, whether the
 * sender is on that side, and whether this answer completes it.
 */
public record SurrenderAnswerPayload(boolean approve) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SurrenderAnswerPayload> ID =
            new CustomPacketPayload.Type<>(PvPocket.id("surrender_answer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurrenderAnswerPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, SurrenderAnswerPayload::approve,
                    SurrenderAnswerPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
