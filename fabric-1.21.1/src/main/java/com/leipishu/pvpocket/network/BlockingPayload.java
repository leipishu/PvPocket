package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server intent: "I am holding / released a sword block".
 *
 * <p>This is an intent, not a state change. The server re-validates that the sender is in a legacy
 * round and actually holding a sword before trusting it, so a modified client can at worst make
 * itself block while holding a sword — which it could do anyway.</p>
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
