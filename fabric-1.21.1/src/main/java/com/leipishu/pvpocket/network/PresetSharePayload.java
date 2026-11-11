package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: "publish this preset as a code" or "give me the preset behind this code".
 *
 * <p>One payload for both directions of the exchange because they are one feature and one round
 * trip each; two payloads would have to be registered, guarded and kept in step for no gain.</p>
 *
 * <p>Only text travels. A share code carries a loadout string or a rule string, both of which the
 * receiving side re-validates before they reach the editor — the server is a relay here, not a
 * source of truth, so it never needs to understand the contents.</p>
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
     * <p>The largest real loadout is a few kilobytes (36 entries of maybe 60 characters); 16 KB is
     * an order of magnitude of headroom that still refuses a client trying to park a megabyte in the
     * server's memory under the guise of a preset.</p>
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
