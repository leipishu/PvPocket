package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Client -> server intent: "I am holding / released a sword block".
 *
 * <p>This is an intent, not a state change. The server re-validates that the sender is in a legacy
 * round and actually holding a sword before trusting it, so a modified client can at worst make
 * itself block while holding a sword — which it could do anyway.</p>
 */
public record BlockingPayload(boolean blocking) implements CustomPayload {

    public static final CustomPayload.Id<BlockingPayload> ID =
            new CustomPayload.Id<>(PvPocket.id("blocking"));

    public static final PacketCodec<RegistryByteBuf, BlockingPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.BOOL, BlockingPayload::blocking, BlockingPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
