package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: open the rule panel with this rule set.
 *
 * <p>The payload carries values only. The rule catalog - labels, ranges, choices, hints - is
 * compiled into the jar on both sides, so the panel looks the same for the proposer and for the
 * reviewer.
 *
 * <p>{@code role} selects the footer buttons: {@link #ROLE_PROPOSER} can only send a proposal,
 * {@link #ROLE_REVIEWER} can accept, reject, or edit and send it back. The display fields sit in
 * the nested {@link Details} record because {@code PacketCodec.tuple} stops at six components.
 */
public record RulesOpenPayload(String sessionId, String rules, int role, int revision, Details details)
        implements CustomPacketPayload {

    /**
     * The three panels one screen can be.
     *
     * <p>{@link #ROLE_DRAFT} means nothing has been sent yet: the roster is still the proposer's to
     * draw and there is no counterpart to name. Roster and rules are decided together, once.
     */
    public static final int ROLE_PROPOSER = 0;
    public static final int ROLE_REVIEWER = 1;
    public static final int ROLE_DRAFT = 2;

    /**
     * Everything the panel shows that is not a rule value.
     *
     * <p>None of it is pre-formatted text: the panel builds its sentence from these numbers in the
     * reader's language. {@code otherName} is the opponent for the subtitle. {@code loadoutCounts}
     * holds {@code name|uuid:items:team:state} quads joined by commas, one per participant; the
     * uuid rides in the name field to stay under the codec's six-component ceiling. {@code agreed}
     * and {@code total} count participants, declined invites excluded. {@code busyMaps} is a
     * bitmask over {@code ArenaMaps.ALL} of maps another duel is standing on. {@code candidates}
     * holds {@code name|uuid} pairs joined by commas and is empty for reviewers.
     */
    public record Details(String otherName, String loadoutCounts, int agreed, int total, int busyMaps,
                          String candidates) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Details> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Details::otherName,
                ByteBufCodecs.STRING_UTF8, Details::loadoutCounts,
                ByteBufCodecs.VAR_INT, Details::agreed,
                ByteBufCodecs.VAR_INT, Details::total,
                ByteBufCodecs.VAR_INT, Details::busyMaps,
                ByteBufCodecs.STRING_UTF8, Details::candidates,
                Details::new);
    }

    public static final Type<RulesOpenPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "rules_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RulesOpenPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RulesOpenPayload::sessionId,
            ByteBufCodecs.STRING_UTF8, RulesOpenPayload::rules,
            ByteBufCodecs.VAR_INT, RulesOpenPayload::role,
            ByteBufCodecs.VAR_INT, RulesOpenPayload::revision,
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

    public String candidates() {
        return details.candidates();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
