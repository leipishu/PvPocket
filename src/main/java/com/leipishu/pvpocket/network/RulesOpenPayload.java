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
 * the nested {@link Details} record because {@code PacketCodec.tuple} stops at six components;
 * {@link Window} carries the branch-window state.
 */
public record RulesOpenPayload(String sessionId, String rules, int role, int revision, Details details,
                               Window window) implements CustomPacketPayload {

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

    /**
     * The branch window, as the panel needs it.
     *
     * <p>{@code deadlineMsLeft} is a countdown rather than an absolute instant: the client has no
     * clock sync with the server, and a wall-clock comparison would be wrong by the player's time
     * zone offset. {@code submitted} is 1 once this player has a branch on record.
     * {@code myRules} is that branch's rule set, empty when there is none, so reopening the panel
     * shows what the player submitted rather than main. {@code myRoster} is the same for the
     * arrangement, in the roster panel's own {@code uuid,uuid;uuid} form. {@code answered} is 1
     * once this player has given their one answer, which puts the panel in its waiting state.
     */
    public record Window(int deadlineMsLeft, int answered, String myRules, String myRoster) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Window> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Window::deadlineMsLeft,
                ByteBufCodecs.VAR_INT, Window::answered,
                ByteBufCodecs.STRING_UTF8, Window::myRules,
                ByteBufCodecs.STRING_UTF8, Window::myRoster,
                Window::new);

        /** A panel opened outside the branch window, where no countdown is drawn. */
        public static Window none() {
            return new Window(0, 0, "", "");
        }
    }

    public static final Type<RulesOpenPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "rules_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RulesOpenPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RulesOpenPayload::sessionId,
            ByteBufCodecs.STRING_UTF8, RulesOpenPayload::rules,
            ByteBufCodecs.VAR_INT, RulesOpenPayload::role,
            ByteBufCodecs.VAR_INT, RulesOpenPayload::revision,
            Details.CODEC, RulesOpenPayload::details,
            Window.CODEC, RulesOpenPayload::window,
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
