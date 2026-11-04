package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: "open the rule panel with this rule set".
 *
 * <p>The payload carries values only. The catalog of rules — labels, ranges, choices, hints — is
 * compiled into the jar on both sides, so the panel is identical whether it is opened by the
 * proposer or by the reviewer and nothing about the layout crosses the wire.</p>
 *
 * <p>{@code role} decides which footer buttons exist: {@link #ROLE_PROPOSER} can only send a
 * proposal, {@link #ROLE_REVIEWER} can accept, reject, or edit and send it back.</p>
 */
public record RulesOpenPayload(String sessionId, String rules, int role, int revision, String otherName)
        implements CustomPayload {

    public static final int ROLE_PROPOSER = 0;
    public static final int ROLE_REVIEWER = 1;

    public static final Id<RulesOpenPayload> ID =
            new Id<>(Identifier.of("pvpocket", "rules_open"));

    public static final PacketCodec<RegistryByteBuf, RulesOpenPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RulesOpenPayload::sessionId,
            PacketCodecs.STRING, RulesOpenPayload::rules,
            PacketCodecs.VAR_INT, RulesOpenPayload::role,
            PacketCodecs.VAR_INT, RulesOpenPayload::revision,
            PacketCodecs.STRING, RulesOpenPayload::otherName,
            RulesOpenPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
