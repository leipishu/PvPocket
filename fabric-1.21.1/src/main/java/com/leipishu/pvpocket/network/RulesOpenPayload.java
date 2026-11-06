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
 *
 * <p>The display fields live in a nested {@link Details} record rather than as more top-level fields
 * because {@code PacketCodec.tuple} stops at six components, and the alternative — a second payload
 * racing the first — would let the panel open before the data it needs arrives.</p>
 */
public record RulesOpenPayload(String sessionId, String rules, int role, int revision, Details details)
        implements CustomPayload {

    public static final int ROLE_PROPOSER = 0;
    public static final int ROLE_REVIEWER = 1;

    /**
     * Everything the panel shows that is not a rule value.
     *
     * <p>None of it is pre-formatted text. The server has no language, so a summary assembled there
     * would arrive already spelled in whatever the server's locale happened to be — the panel builds
     * the sentence from these numbers instead, in the reader's language.</p>
     *
     * @param otherName     the opponent, for the subtitle
     * @param loadoutCounts {@code name:items} pairs joined by commas, one per participant. The panel
     *                      never edits a loadout, and sending the whole thing twice per open would be
     *                      a lot of bytes to render eight characters
     * @param agreed        how many participants have agreed to the loadout set as it stands
     * @param total         how many participants there are
     * @param busyMaps      bitmask over {@code ArenaMaps.ALL} of maps another duel is standing on,
     *                      so the picker can refuse to select them instead of letting the player
     *                      find out at "accept and start" time
     */
    public record Details(String otherName, String loadoutCounts, int agreed, int total, int busyMaps) {

        public static final PacketCodec<RegistryByteBuf, Details> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Details::otherName,
                PacketCodecs.STRING, Details::loadoutCounts,
                PacketCodecs.VAR_INT, Details::agreed,
                PacketCodecs.VAR_INT, Details::total,
                PacketCodecs.VAR_INT, Details::busyMaps,
                Details::new);
    }

    public static final Id<RulesOpenPayload> ID =
            new Id<>(Identifier.of("pvpocket", "rules_open"));

    public static final PacketCodec<RegistryByteBuf, RulesOpenPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RulesOpenPayload::sessionId,
            PacketCodecs.STRING, RulesOpenPayload::rules,
            PacketCodecs.VAR_INT, RulesOpenPayload::role,
            PacketCodecs.VAR_INT, RulesOpenPayload::revision,
            Details.CODEC, RulesOpenPayload::details,
            RulesOpenPayload::new);

    public String otherName() {
        return details.otherName();
    }

    public String loadoutCounts() {
        return details.loadoutCounts();
    }

    public int agreed() {
        return details.agreed();
    }

    public int total() {
        return details.total();
    }

    public int busyMaps() {
        return details.busyMaps();
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
