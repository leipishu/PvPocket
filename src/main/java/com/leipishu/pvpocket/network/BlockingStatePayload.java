package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Server to client: "this player started or stopped blocking".
 *
 * <p>Needed because {@link CombatStatePayload} is a <em>private</em> mirror — it only ever tells a
 * client about itself, since its job is to keep the local player's own prediction in step. The
 * blocking flag therefore existed on exactly one machine: the blocker's. Everybody else saw a
 * player standing with their arm down while the server quietly halved the damage, which made
 * blocking unreadable in a fight — you had to hit somebody and compare numbers to find out.</p>
 *
 * <p>Broadcast rather than tracked-data on purpose. A tracked flag would mean registering a
 * {@code TrackedData} entry for every entity type and keeping it in step with a state the server
 * already owns; this is one small packet per transition, and a duel is not a thousand blocks.</p>
 *
 * <p>Sent to every other player, not just teammates: in a duel everybody in the arena can see
 * everybody, and "who is holding right-click" is not information the opponent should be denied —
 * it is the whole point of the pose.</p>
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
