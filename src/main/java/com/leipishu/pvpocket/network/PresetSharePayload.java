package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: publish a preset as a code, or fetch the preset behind a code.
 *
 * <p>One payload for both directions of the exchange because each is one round trip. Only text
 * travels: a share code carries a loadout string or a rule string, and the receiving side
 * re-validates it before it reaches the editor.
 */
public record PresetSharePayload(int action, String kind, String name, String encoded, String code)
        implements CustomPacketPayload {

    /** Publish {@code kind}/{@code name}/{@code encoded}; the answer carries a code. */
    public static final int ACTION_PUBLISH = 0;
    /** Fetch whatever {@code code} points at; the answer carries the preset. */
    public static final int ACTION_FETCH = 1;

    /**
     * Hard ceiling on a shared preset string.
     *
     * <p>The largest real loadout is a few kilobytes, 36 entries of maybe 60 characters; 16 KB
     * leaves an order of magnitude of headroom.
     */
    public static final int MAX_ENCODED = 16_384;

    public static final Type<PresetSharePayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "preset_share"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PresetSharePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PresetSharePayload::action,
            ByteBufCodecs.STRING_UTF8, PresetSharePayload::kind,
            ByteBufCodecs.STRING_UTF8, PresetSharePayload::name,
            ByteBufCodecs.STRING_UTF8, PresetSharePayload::encoded,
            ByteBufCodecs.STRING_UTF8, PresetSharePayload::code,
            PresetSharePayload::new);

    public static PresetSharePayload publish(String kind, String name, String encoded) {
        return new PresetSharePayload(ACTION_PUBLISH, kind, name, encoded, "");
    }

    public static PresetSharePayload fetch(String code) {
        return new PresetSharePayload(ACTION_FETCH, "", "", "", code);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
