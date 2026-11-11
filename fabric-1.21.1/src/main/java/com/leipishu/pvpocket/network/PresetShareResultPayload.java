package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the answer to a {@link PresetSharePayload}, success or refusal.
 *
 * <p>The status is an int rather than a message because the refusal has to be shown in the reader's
 * own language — the server has no language, so it answers with a code and the client turns it into
 * a sentence. The same reason the rule panel's problems travel as translation keys.</p>
 *
 * <p>On success the whole preset comes back, including the name: the receiver stores it under the
 * name the sender chose, so "同事的弓" arrives as "同事的弓" rather than as "imported-1".</p>
 */
public record PresetShareResultPayload(int status, String kind, String name, String encoded,
                                       String code, int ttlSeconds) implements CustomPacketPayload {

    public static final int STATUS_PUBLISHED = 0;
    public static final int STATUS_FETCHED = 1;
    /** The code never existed, or it existed and has been swept. Deliberately not distinguished. */
    public static final int STATUS_UNKNOWN_CODE = 2;
    /** The publish request was unusable: unknown kind, blank name, or an oversized payload. */
    public static final int STATUS_REJECTED = 3;
    /** No room for another live code. */
    public static final int STATUS_FULL = 4;

    public static final Type<PresetShareResultPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "preset_share_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PresetShareResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PresetShareResultPayload::status,
            ByteBufCodecs.STRING_UTF8, PresetShareResultPayload::kind,
            ByteBufCodecs.STRING_UTF8, PresetShareResultPayload::name,
            ByteBufCodecs.STRING_UTF8, PresetShareResultPayload::encoded,
            ByteBufCodecs.STRING_UTF8, PresetShareResultPayload::code,
            ByteBufCodecs.VAR_INT, PresetShareResultPayload::ttlSeconds,
            PresetShareResultPayload::new);

    public boolean ok() {
        return status == STATUS_PUBLISHED || status == STATUS_FETCHED;
    }

    public static PresetShareResultPayload published(String kind, String name, String encoded,
                                                     String code, int ttlSeconds) {
        return new PresetShareResultPayload(STATUS_PUBLISHED, kind, name, encoded, code, ttlSeconds);
    }

    public static PresetShareResultPayload fetched(String kind, String name, String encoded,
                                                   String code) {
        return new PresetShareResultPayload(STATUS_FETCHED, kind, name, encoded, code, 0);
    }

    public static PresetShareResultPayload failure(int status) {
        return new PresetShareResultPayload(status, "", "", "", "", 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
