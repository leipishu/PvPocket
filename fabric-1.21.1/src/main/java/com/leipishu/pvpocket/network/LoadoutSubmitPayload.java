package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: "save this loadout onto my side of the session".
 *
 * <p>The payload carries a loadout string, never item stacks. Stacks would let a client ask for any
 * item in the game; a loadout string is re-validated against the whitelist and the caps on the server
 * before a single item is constructed, so the worst a tampered client can do is submit a loadout that
 * gets rejected.</p>
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
