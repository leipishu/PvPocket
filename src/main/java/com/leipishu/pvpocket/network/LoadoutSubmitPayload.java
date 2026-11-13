package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: save this loadout onto the sender's side of the session.
 *
 * <p>The payload carries a loadout string, never item stacks. The string is re-validated against
 * the whitelist and the caps on the server before any item is constructed.
 */
public record LoadoutSubmitPayload(String sessionId, String loadout) implements CustomPacketPayload {

    public static final Type<LoadoutSubmitPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "loadout_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LoadoutSubmitPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoadoutSubmitPayload::sessionId,
            ByteBufCodecs.STRING_UTF8, LoadoutSubmitPayload::loadout,
            LoadoutSubmitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
