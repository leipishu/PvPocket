package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

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
        implements CustomPayload {

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

    public static final Id<PresetSharePayload> ID =
            new Id<>(Identifier.of("pvpocket", "preset_share"));

    public static final PacketCodec<RegistryByteBuf, PresetSharePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, PresetSharePayload::action,
            PacketCodecs.STRING, PresetSharePayload::kind,
            PacketCodecs.STRING, PresetSharePayload::name,
            PacketCodecs.STRING, PresetSharePayload::encoded,
            PacketCodecs.STRING, PresetSharePayload::code,
            PresetSharePayload::new);

    public static PresetSharePayload publish(String kind, String name, String encoded) {
        return new PresetSharePayload(ACTION_PUBLISH, kind, name, encoded, "");
    }

    public static PresetSharePayload fetch(String code) {
        return new PresetSharePayload(ACTION_FETCH, "", "", "", code);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
