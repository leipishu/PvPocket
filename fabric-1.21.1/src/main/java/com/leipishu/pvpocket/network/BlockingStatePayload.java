package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

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
public record BlockingStatePayload(UUID player, boolean blocking) implements CustomPayload {

    public static final Id<BlockingStatePayload> ID =
            new Id<>(Identifier.of("pvpocket", "blocking_state"));

    public static final PacketCodec<RegistryByteBuf, BlockingStatePayload> CODEC = PacketCodec.tuple(
            Uuids.PACKET_CODEC, BlockingStatePayload::player,
            PacketCodecs.BOOL, BlockingStatePayload::blocking,
            BlockingStatePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
