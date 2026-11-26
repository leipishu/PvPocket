package com.leipishu.pvpocket.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * The handful of cues a duel plays, all of them vanilla sounds.
 *
 * <p>No sound files ship with the mod: the point of these is to tell the player that something
 * changed while they were looking somewhere else (an invite arrived, the round went live, they are
 * out, the series is over). Note blocks and the toast chime are already in the game's own mix, so
 * they cost nothing and match whatever resource pack is loaded.
 *
 * <p>Kept quiet: a duel has no ambient track, so anything loud here becomes the loudest thing in
 * the match.
 */
public final class PvCues {

    private PvCues() {
    }

    /** An invite arrived: one soft bell, the same note as a villager's trade screen. */
    public static void invite() {
        ui(bell(), 0.35F, 1.15F);
    }

    /** The round went live. */
    public static void roundStart() {
        ui(pling(), 0.45F, 1.30F);
    }

    /** This player is out of the round: a low thud, not a fanfare. */
    public static void eliminated() {
        ui(bass(), 0.50F, 0.62F);
    }

    /** The series is over. A win gets the advancement chime, a loss the same low note. */
    public static void seriesEnd(boolean won) {
        if (won) {
            ui(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.55F, 1.0F);
        } else {
            ui(bass(), 0.40F, 0.50F);
        }
    }

    /** A match is still running and this player's place is on the roster. */
    public static void reconnect() {
        ui(bell(), 0.50F, 0.80F);
    }

    private static void ui(SoundEvent event, float volume, float pitch) {
        Minecraft client = Minecraft.getInstance();
        if (client.getSoundManager() == null || event == null) {
            return;
        }
        client.getSoundManager().play(SimpleSoundInstance.forUI(event, volume, pitch));
    }

    // The note-block sounds are holders in every cell, so the cue code above is written against the
    // SoundEvent behind them.

    private static SoundEvent bell() {
        return SoundEvents.NOTE_BLOCK_BELL.value();
    }

    private static SoundEvent pling() {
        return SoundEvents.NOTE_BLOCK_PLING.value();
    }

    private static SoundEvent bass() {
        return SoundEvents.NOTE_BLOCK_BASS.value();
    }
}
