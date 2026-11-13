package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Server to client: this player started or stopped blocking.
 *
 * <p>{@link CombatStatePayload} is a private mirror that only tells a client about itself, so this
 * packet carries the blocking flag of every other player. It is sent to every other player, not
 * just teammates, so the pose is readable across the arena.
 */
public record BlockingStatePayload(UUID player, boolean blocking) implements CustomPacketPayload {

    public static final Type<BlockingStatePayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "blocking_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockingStatePayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BlockingStatePayload::player,
            ByteBufCodecs.BOOL, BlockingStatePayload::blocking,
            BlockingStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
