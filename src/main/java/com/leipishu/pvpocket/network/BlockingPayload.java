package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server intent: the sender is holding or has released a sword block.
 *
 * <p>An intent, not a state change. The server re-validates that the sender is in a legacy round
 * and holding a sword before trusting it.
 */
public record BlockingPayload(boolean blocking) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BlockingPayload> ID =
            new CustomPacketPayload.Type<>(PvPocket.id("blocking"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockingPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, BlockingPayload::blocking, BlockingPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
