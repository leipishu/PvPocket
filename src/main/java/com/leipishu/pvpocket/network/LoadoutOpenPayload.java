package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: "open the loadout editor for this session".
 *
 * <p>Only the values cross the wire. The catalog of kinds, materials, effects, blocks and limits is
 * compiled into the jar on both sides, so the editor is the same screen for the proposer and the
 * reviewer and a version mismatch cannot produce a half-populated list.</p>
 */
public record LoadoutOpenPayload(String sessionId, String loadout, int role, String otherName)
        implements CustomPacketPayload {

    public static final Type<LoadoutOpenPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "loadout_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LoadoutOpenPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoadoutOpenPayload::sessionId,
            ByteBufCodecs.STRING_UTF8, LoadoutOpenPayload::loadout,
            ByteBufCodecs.VAR_INT, LoadoutOpenPayload::role,
            ByteBufCodecs.STRING_UTF8, LoadoutOpenPayload::otherName,
            LoadoutOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
