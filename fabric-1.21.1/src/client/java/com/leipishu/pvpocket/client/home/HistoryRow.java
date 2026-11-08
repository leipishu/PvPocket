package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * One finished series in the history list.
 *
 * <p>Two lines in a 38px card, the same rhythm the match list uses, so the two lists read as the same
 * kind of object. What differs is the leading element: a match in progress leads with the map and a
 * state chip, a finished one leads with the score, because the score is the reason anyone opens this
 * page.</p>
 *
 * <p>The result is carried by an accent-tinted chip rather than by red/green. Red is reserved for
 * destructive actions in this system, and a red "lost" badge would be the only place in the interface
 * where red means something other than "this removes something".</p>
 */
public class HistoryRow extends PvWidget {

    private static final int H = 42;
    /** Room a round dot needs, including its gap. */
    private static final int DOT_PITCH = 8;
    private static final int MAX_DOTS = 12;

    private final HomeState.History record;
    private final java.util.List<HomeState.Round> rounds;
    private final Runnable onClick;
    private float hotT;

    public HistoryRow(HomeState.History record, Runnable onClick) {
        this.record = record;
        // Parsed once, not once per frame: the strip is redrawn on every render pass, and a thirty-row
        // list would otherwise re-split thirty strings sixty times a second.
        this.rounds = record.roundList();
        this.onClick = onClick;
        this.height = H;
        this.tooltip = PvLang.str("pvpocket.gui.home.history.tooltip");
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        hotT = PvAnim.damp(hotT, hovered ? 1F : 0F, d, 0.09F);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        int h = H - 4;
        PvDraw.roundPanel(ctx, x, y, width, h, PvTheme.RADIUS,
                PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, hotT),
                PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, hotT));

        // A two-pixel bar on the leading edge says the outcome without spending width on it. Won is
        // the accent; lost is the plain border, because "nothing happened here" is the honest reading.
        PvDraw.roundRect(ctx, x + 1, y + 7, 2, h - 14, 1,
                record.won() ? PvTheme.ACCENT : PvTheme.BORDER);

        int textX = x + 10;
        int line1 = y + 6;
        int line2 = y + 22;

        String score = record.myScore() + " : " + record.theirScore();
        PvDraw.text(ctx, tr, score, textX, line1, PvTheme.TEXT);

        String chip = PvLang.str(record.won()
                ? "pvpocket.gui.home.result.win" : "pvpocket.gui.home.result.loss");
        int chipW = tr.getWidth(chip) + 10;
        int chipX = textX + tr.getWidth(score) + 8;
        PvDraw.roundRect(ctx, chipX, line1 - 2, chipW, 12, 3,
                record.won() ? PvTheme.ACCENT_SOFT : PvTheme.CARD_ACTIVE);
        PvDraw.text(ctx, tr, chip, chipX + 5, line1,
                record.won() ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);

        // The round strip is measured first: it is right-aligned and fixed, so the map name gets
        // whatever is left rather than the other way round.
        int dots = Math.min(MAX_DOTS, rounds.size());
        int dotsW = dots * DOT_PITCH;
        int dotsX = x + width - 10 - dotsW;
        drawDots(ctx, dotsX, line1 + 2);

        int mapX = chipX + chipW + 8;
        String meta = record.mapLabel()
                + " · " + PvLang.str(record.mode().equals("legacy")
                ? "pvpocket.gui.home.mode.legacy" : "pvpocket.gui.home.mode.modern")
                + " · " + teamsLabel();
        PvDraw.text(ctx, tr, PvDraw.fit(tr, meta, Math.max(8, dotsX - 8 - mapX)), mapX, line1,
                PvTheme.TEXT_DIM);

        String who = record.opponents().isEmpty()
                ? PvLang.str("pvpocket.gui.home.history.solo")
                : PvLang.str("pvpocket.gui.home.history.vs", record.opponents());
        String second = HomeText.stamp(record.t()) + " · " + who;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, second, width - 20), textX, line2, PvTheme.TEXT_MUTED);
    }

    /**
     * One square per round, left to right.
     *
     * <p>Six pixels rather than a number: a "2-1-0-1" string needs a legend and thirty pixels, while
     * four squares say the same thing at a glance. The strip is capped, and the cap is stated by the
     * card simply stopping — a series longer than twelve rounds is beyond what the rules allow.</p>
     */
    private void drawDots(DrawContext ctx, int startX, int top) {
        int i = 0;
        for (HomeState.Round round : rounds) {
            if (i >= MAX_DOTS) {
                break;
            }
            PvDraw.roundRect(ctx, startX + i * DOT_PITCH, top, 6, 6, 1,
                    round.won() ? PvTheme.ACCENT : PvTheme.CARD_ACTIVE);
            i++;
        }
    }

    private String teamsLabel() {
        int perSide = Math.max(1, record.teamSize());
        if (record.teams() <= 2) {
            return perSide + "v" + perSide;
        }
        return record.teams() + "v" + record.teams();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0 || !contains(mx, my)) {
            return false;
        }
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.1F));
        if (onClick != null) {
            onClick.run();
        }
        return true;
    }
}
