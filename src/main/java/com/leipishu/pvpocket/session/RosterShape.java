package com.leipishu.pvpocket.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The arrangement as one string: sides separated by {@code ;}, members by {@code ,}.
 *
 * <p>The same form the roster panel sends on the invite, so an arrangement can travel as a branch
 * value and be compared with main by string equality. Canonical form drops empty sides, so two
 * arrangements that differ only in how many blank cards they were drawn with are the same one.
 */
public final class RosterShape {

    private RosterShape() {
    }

    /** The session's current arrangement, empty sides dropped. */
    public static String of(DuelSession session) {
        List<List<String>> sides = new ArrayList<>();
        for (int team = 0; team < Math.max(1, session.teamCount); team++) {
            List<Participant> members = new ArrayList<>(session.activeMembers(team));
            // Member order decides who stands where on the pad, so it is part of the arrangement.
            members.sort(Comparator.comparingInt(p -> p.member));
            if (members.isEmpty()) {
                continue;
            }
            List<String> side = new ArrayList<>();
            for (Participant p : members) {
                side.add(p.uuid);
            }
            sides.add(side);
        }
        return encode(sides);
    }

    public static String encode(List<List<String>> sides) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sides.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(String.join(",", sides.get(i)));
        }
        return sb.toString();
    }

    /** Decoded arrangement, or null when the string is not a roster at all. */
    public static List<List<String>> decode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<List<String>> sides = new ArrayList<>();
        for (String part : raw.split(";", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            List<String> side = new ArrayList<>();
            for (String token : part.split(",")) {
                String uuid = token.trim();
                if (uuid.isEmpty()) {
                    continue;
                }
                try {
                    UUID.fromString(uuid);
                } catch (IllegalArgumentException e) {
                    return null;
                }
                side.add(uuid);
            }
            if (!side.isEmpty()) {
                sides.add(side);
            }
        }
        return sides.isEmpty() ? null : sides;
    }

    /** Canonical form: decoded, empty sides dropped, re-encoded. Null when malformed. */
    public static String normalise(String raw) {
        List<List<String>> sides = decode(raw);
        return sides == null ? null : encode(sides);
    }
}
