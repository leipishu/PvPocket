package com.leipishu.pvpocket.client.gui.result;

import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.SeriesResultPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * One finished series, as the result screen and its poster read it.
 *
 * <p>Parsed once when the payload arrives rather than once per frame: the card is redrawn sixty times
 * a second and re-splitting three strings that often to draw the same twelve rows is work nobody
 * asked for. The screen holds one of these and nothing else.</p>
 *
 * <p>Deliberately <b>not</b> {@code HomeState.History}. That type is the reader's own row out of a
 * stored history file, with the reasons flattened to "did I win this round" — enough for a list, not
 * enough for a card. This one keeps every side's roster and every round's absolute winner, because
 * the card has to show both sides and the poster has to show the whole match.</p>
 *
 * <p>{@link #winnerSide} is {@code -1} when the series ended without a winner. That is two different
 * outcomes and {@link #reason} tells them apart: {@code DRAW} is a cap reached with the lead shared,
 * anything else is a series that was cut short.</p>
 */
public record MatchResult(String sessionId, String map, String mode, int mySide, int winnerSide,
                          long endedAt, long duration, String reason,
                          List<Side> sides, List<Round> rounds) {

    /** One side of the match: its index, its round wins, and who was on it. */
    public record Side(int index, int score, List<String> names) {

        /** The roster as one string, in the order the proposer filled the card in. */
        public String roster() {
            return names.isEmpty() ? "-" : String.join(" + ", names);
        }
    }

    /** One settled round: which side took it ({@code -1} for none), why, and how long it ran. */
    public record Round(int winner, String reason, long ms) {
    }

    /** True when the reader's side took the series. */
    public boolean won() {
        return winnerSide >= 0 && winnerSide == mySide;
    }

    /** True when the series ended with nobody ahead. */
    public boolean drawn() {
        return winnerSide < 0;
    }

    /** The reader's own side, or {@code null} when the payload named a side that is not in the list. */
    public Side mine() {
        for (Side side : sides) {
            if (side.index() == mySide) {
                return side;
            }
        }
        return null;
    }

    /** The map's name in the reader's language; an unknown id falls back to the id itself. */
    public String mapLabel() {
        String key = "pvpocket.map." + map;
        String text = PvLang.str(key);
        return text.equals(key) ? map : text;
    }

    /** {@code 1v1} / {@code 2v2} for an even split, {@code 3v3} for anything wider. */
    public String teamsLabel() {
        int perSide = 1;
        for (Side side : sides) {
            perSide = Math.max(perSide, side.names().size());
        }
        return sides.size() <= 2 ? perSide + "v" + perSide : sides.size() + "v" + sides.size();
    }

    /** Rounds the reader's side took. The score block carries the same number per side. */
    public int roundsWon() {
        int won = 0;
        for (Round round : rounds) {
            if (round.winner() == mySide) {
                won++;
            }
        }
        return won;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses the payload, or returns {@code null} when it is not readable.
     *
     * <p>Null rather than an empty card: a payload this client cannot parse is a version skew, and a
     * result screen that says "0 : 0, no rounds" is worse than no result screen — it looks like the
     * match never happened. The receiver drops it and the chat line remains, which is the same
     * sentence the player would have got from an older build.</p>
     */
    public static MatchResult parse(SeriesResultPayload payload) {
        if (payload == null) {
            return null;
        }
        String[] head = payload.head().split("\\|", 8);
        if (head.length < 8) {
            return null;
        }
        int mySide;
        int winnerSide;
        long endedAt;
        long duration;
        try {
            mySide = Integer.parseInt(head[3]);
            winnerSide = Integer.parseInt(head[4]);
            endedAt = Long.parseLong(head[5]);
            duration = Long.parseLong(head[6]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new MatchResult(head[0], head[1], head[2], mySide, winnerSide, endedAt, duration,
                head[7], parseSides(payload.sides()), parseRounds(payload.rounds()));
    }

    private static List<Side> parseSides(String block) {
        List<Side> out = new ArrayList<>();
        if (block == null || block.isEmpty()) {
            return out;
        }
        for (String line : block.split("\n")) {
            String[] f = line.split(",", -1);
            if (f.length < 2) {
                continue;
            }
            try {
                List<String> names = new ArrayList<>(f.length - 2);
                for (int i = 2; i < f.length; i++) {
                    if (!f[i].isEmpty()) {
                        names.add(f[i]);
                    }
                }
                out.add(new Side(Integer.parseInt(f[0]), Integer.parseInt(f[1]), names));
            } catch (NumberFormatException ignored) {
                // One unreadable side loses that side, not the card.
            }
        }
        return out;
    }

    private static List<Round> parseRounds(String block) {
        List<Round> out = new ArrayList<>();
        if (block == null || block.isEmpty()) {
            return out;
        }
        for (String line : block.split("\n")) {
            String[] f = line.split(",", 3);
            if (f.length < 3) {
                continue;
            }
            try {
                out.add(new Round(Integer.parseInt(f[0]), f[1],
                        Math.max(0L, Long.parseLong(f[2]))));
            } catch (NumberFormatException ignored) {
                // Same contract as parseSides: a malformed round is dropped, not fatal.
            }
        }
        return out;
    }
}
