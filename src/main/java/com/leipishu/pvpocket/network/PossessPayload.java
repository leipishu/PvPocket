package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: what a spectator's screen needs to draw the body they are riding in.
 *
 * <p>Attaching the camera with {@code setCameraEntity} gives the first-person view already, but not
 * the HUD: a spectator is in {@link net.minecraft.world.level.GameType#SPECTATOR}, where vanilla
 * draws no hotbar. The wire carries the target's hotbar plus its health and armour.
 *
 * <p>Nine slots are encoded as {@code id,count} separated by {@code ;}, empty for an empty slot; a
 * malformed entry is skipped rather than thrown on. {@code active = false} means stop drawing and
 * every other field is ignored, {@code targetId} is -1 when detached, {@code selected} is the
 * hotbar slot 0-8, and {@code name} labels the HUD.
 */
public record PossessPayload(boolean active, int targetId, String name, int selected,
                             String slots, Vitals vitals) implements CustomPacketPayload {

    /** Health and armour of the possessed body, kept together so they share a codec. */
    public record Vitals(float health, int armor) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Vitals> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Vitals::health,
                ByteBufCodecs.VAR_INT, Vitals::armor,
                Vitals::new);
    }

    public static final Type<PossessPayload> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "possess"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PossessPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PossessPayload::active,
            ByteBufCodecs.VAR_INT, PossessPayload::targetId,
            ByteBufCodecs.STRING_UTF8, PossessPayload::name,
            ByteBufCodecs.VAR_INT, PossessPayload::selected,
            ByteBufCodecs.STRING_UTF8, PossessPayload::slots,
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
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
