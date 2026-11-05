package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * What a spectator's screen needs to draw the body they are riding in.
 *
 * <p>Attaching the camera with {@code setCameraEntity} gives the first-person view for free — the
 * world, the held item, the swing animation, the damage flash are all rendered from the target
 * already. What it does <em>not</em> give is the HUD: the hotbar belongs to the spectator, and a
 * spectator is in {@link net.minecraft.world.GameMode#SPECTATOR}, where vanilla draws no hotbar at
 * all. So the one thing that has to travel over the wire is the target's hotbar, plus the two
 * numbers a viewer wants while watching a fight: health and armour.</p>
 *
 * <p><b>Encoding.</b> Nine slots as {@code id,count} separated by {@code ;}, empty for an empty
 * slot. Item ids rather than NBT: the client only needs to draw an icon and a count, and sending
 * full stacks nine times a second to every spectator is a lot of bytes for a glint. A malformed
 * entry is skipped rather than thrown on, for the same reason the match list tolerates bad rows —
 * a HUD is not worth a disconnect.</p>
 *
 * <p>The vitals are a nested record rather than two more fields because {@code PacketCodec.tuple}
 * stops at six components, and the alternative — flattening health and armour into a string — would
 * make the one thing on this payload that is genuinely numeric the one thing that has to be parsed.</p>
 *
 * @param active   false means "stop drawing the possessed HUD"; every other field is then ignored
 * @param targetId entity id of the body being ridden, or -1 when detached
 * @param name     display name of that body, so the HUD can label itself
 * @param selected hotbar slot 0-8 that the target currently has selected
 * @param slots    the nine hotbar slots, {@code id,count} joined by {@code ;}
 * @param vitals   health and armour points of that body
 */
public record PossessPayload(boolean active, int targetId, String name, int selected,
                             String slots, Vitals vitals) implements CustomPayload {

    /** The two numbers that change without the hotbar changing, kept together so they share a codec. */
    public record Vitals(float health, int armor) {

        public static final PacketCodec<RegistryByteBuf, Vitals> CODEC = PacketCodec.tuple(
                PacketCodecs.FLOAT, Vitals::health,
                PacketCodecs.VAR_INT, Vitals::armor,
                Vitals::new);
    }

    public static final Id<PossessPayload> ID = new Id<>(Identifier.of("pvpocket", "possess"));

    public static final PacketCodec<RegistryByteBuf, PossessPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOL, PossessPayload::active,
            PacketCodecs.VAR_INT, PossessPayload::targetId,
            PacketCodecs.STRING, PossessPayload::name,
            PacketCodecs.VAR_INT, PossessPayload::selected,
            PacketCodecs.STRING, PossessPayload::slots,
            Vitals.CODEC, PossessPayload::vitals,
            PossessPayload::new);

    public static PossessPayload hidden() {
        return new PossessPayload(false, -1, "", 0, "", new Vitals(0.0F, 0));
    }

    public float health() {
        return vitals.health();
    }

    public int armor() {
        return vitals.armor();
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
