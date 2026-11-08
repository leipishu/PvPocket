package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleGroup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.List;
import java.util.function.IntPredicate;

/**
 * Vertical tab strip for the rule groups, drawn as a sidebar rail.
 *
 * <p>Vertical rather than horizontal because the labels are short and the panel is wider than it is
 * tall: a vertical strip costs 72px of width and saves 26px of the scarce axis, and it leaves room
 * for all eight entries without a scroll or an overflow menu.</p>
 *
 * <p>The strip no longer owns a surface. It used to draw its own rounded, bordered panel inside the
 * screen's panel, which is what made it read as a card dropped into the layout — the sidebar is
 * chrome now, drawn once by {@link PvScreen#drawPanelFrame} and continuous with the title band, so
 * the strip only draws what belongs to the tabs themselves: the selected pill and the labels.
 * Nothing here may draw a background again; a second surface over the chrome reintroduces the seam
 * the one-piece layout exists to remove.</p>
 *
 * <p>There is no selection marker either. An accent bar pinned to the sidebar's outer edge used to
 * slide between tabs, and it made the strip read as a second navigation column stacked on the
 * first: the pill already says which entry is current, and a bar beside it says it a second time in
 * a louder colour. One selection cue, and it is the one the pointer and the keyboard both move.</p>
 */
public class PvTabs extends PvWidget {

    private static final int INSET = 6;
    /**
     * Shortest legible tab pitch.
     *
     * <p>A label is 8px tall, so anything under 11 either clips the glyphs or puts two labels closer
     * together than the eye can separate them. Below this the strip cannot compress any further,
     * which is what {@link #minHeight} reports to the panel.</p>
     */
    private static final int MIN_TAB_H = 11;
    /** Radius of the "this page differs from what was received" dot. */
    private static final int DOT_R = 2;

    private final List<String> labels;
    /** Non-null only for the rule-group strip, which is the only caller that needs the group back. */
    private final List<RuleGroup> groups;
    private int selected;
    private Runnable onChange;
    /**
     * Asked once per tab per frame; true draws a dot on that tab.
     *
     * <p>A predicate rather than a precomputed {@code List<Boolean>}: the values change while the
     * strip is on screen (a slider is dragged on the page beside it), and a stale copy would leave
     * the dot off until the next rebuild.</p>
     */
    private IntPredicate dirtyTest;
    private int tabHeight = PvTheme.TAB_H;
    private float[] hotT = new float[0];

    public PvTabs(List<String> labels, int selected) {
        this(labels, null, selected);
    }

    /**
     * The rule panel's strip: the roster page first, then the rule groups.
     *
     * <p>The roster is a tab of this panel rather than a screen of its own because the two halves of
     * "who is playing" and "under what rules" are one decision — the player builds the sides, sets
     * the terms, and sends once. A separate screen would need its own footer, its own cancel path
     * and its own idea of when a draft is abandoned.</p>
     *
     * <p>The first slot has no {@link RuleGroup}: {@link #group()} returns null for it, which is how
     * the screen knows to draw the roster board instead of rule rows.</p>
     */
    public static PvTabs ofPanel(List<RuleGroup> groups, String rosterLabel, int selected) {
        List<String> labels = new java.util.ArrayList<>();
        List<RuleGroup> slots = new java.util.ArrayList<>();
        labels.add(rosterLabel);
        slots.add(null);
        for (RuleGroup group : groups) {
            labels.add(group.label());
            slots.add(group);
        }
        return new PvTabs(labels, slots, selected);
    }

    private PvTabs(List<String> labels, List<RuleGroup> groups, int selected) {
        this.labels = labels;
        this.groups = groups;
        this.selected = selected;
        this.width = 96;
        this.hotT = new float[labels.size()];
        this.height = INSET * 2 + labels.size() * PvTheme.TAB_H + (labels.size() - 1);
    }

    /**
     * Marks tabs whose content differs from what the server sent.
     *
     * <p>The dot replaced the group letter that used to sit there. The letter was a duplicate of the
     * rule id prefix — it told the reader something they could not act on — while this space is the
     * one place where "you changed something in here" can be shown without opening the tab.</p>
     */
    public void dirtyTest(IntPredicate test) {
        this.dirtyTest = test;
    }

    /**
     * Shrinks the tab pitch so every tab stays reachable.
     *
     * <p>A tab strip that clips its last entries is worse than a dense one: the hidden groups are
     * not merely off-screen, they are unreachable, because there is nothing to scroll. The panel
     * calls this with whatever vertical space is actually available, so at a small window the tabs
     * compress instead of disappearing.</p>
     */
    public void fitInto(int availableHeight) {
        int n = Math.max(1, labels.size());
        int pitch = (availableHeight - INSET * 2 - (n - 1)) / n;
        // The floor is 11 rather than 13: the sidebar also carries the commit buttons at its foot,
        // and on a 220px panel eight entries plus three buttons only fit if a tab may go this
        // short. Anything less and the last entry would be drawn past the panel edge, which is worse
        // than a dense strip — a clipped tab is an unreachable tab.
        tabHeight = Math.max(MIN_TAB_H, Math.min(PvTheme.TAB_H, pitch));
        height = INSET * 2 + labels.size() * tabHeight + (labels.size() - 1);
    }

    /**
     * Height the strip needs for {@code count} entries at the shortest legible pitch.
     *
     * <p>The panel uses this to decide whether a sidebar can hold the strip and the commit buttons
     * at all. Below this height the strip would be drawn over whatever is under it, because
     * {@link #fitInto} refuses to go shorter and there is nothing else to give.</p>
     */
    public static int minHeight(int count) {
        int n = Math.max(1, count);
        return INSET * 2 + n * MIN_TAB_H + (n - 1);
    }

    public int tabHeight() {
        return tabHeight;
    }

    public int selected() {
        return selected;
    }

    public RuleGroup group() {
        return groups == null ? null : groups.get(selected);
    }

    public void select(int index) {
        int next = Math.max(0, Math.min(labels.size() - 1, index));
        if (next != selected) {
            selected = next;
            if (onChange != null) {
                onChange.run();
            }
        }
    }

    public void onChange(Runnable callback) {
        this.onChange = callback;
    }

    private int tabLeft() {
        return x + INSET;
    }

    private int tabWidth() {
        return width - INSET * 2;
    }

    private int tabY(int index) {
        return y + INSET + index * (tabHeight + 1);
    }

    private int tabAt(double my) {
        int top = y + INSET;
        if (my < top || my >= top + labels.size() * (tabHeight + 1)) {
            return -1;
        }
        int index = (int) ((my - top) / (tabHeight + 1));
        return index >= 0 && index < labels.size() ? index : -1;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickFocus(d);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int hoverIndex = hovered ? tabAt(mouseY) : -1;

        for (int i = 0; i < labels.size(); i++) {
            if (i >= hotT.length) {
                break;
            }
            hotT[i] = PvAnim.damp(hotT[i], i == hoverIndex ? 1F : 0F, d, 0.09F);
            int ty = tabY(i);
            if (ty + tabHeight > y + height - INSET + 1) {
                break;
            }
            boolean active = i == selected;
            if (active) {
                PvDraw.roundRect(ctx, tabLeft(), ty, tabWidth(), tabHeight, 3, PvTheme.CARD_ACTIVE);
            } else if (hotT[i] > 0.01F) {
                PvDraw.roundRect(ctx, tabLeft(), ty, tabWidth(), tabHeight, 3,
                        PvTheme.alpha(PvTheme.CARD_HOVER, hotT[i]));
            }

            int textY = ty + (tabHeight - 8) / 2 + 1;
            int textColor = active
                    ? PvTheme.TEXT
                    : PvTheme.mix(PvTheme.TEXT_DIM, PvTheme.TEXT, hotT[i]);
            // Labels are measured, not trusted: "Consumables" is 64px in the default font and the
            // strip is 96px wide, so a caller that shrinks the strip would otherwise paint the label
            // straight over its own edge and into the content beside it. The dot column is reserved
            // even when the tab is clean, so a label never shifts sideways when it lights up.
            // Shrinking before truncating matters most here: a tab is the only name a page has, and
            // "Consumabl…" beside "Blocks" is a tab strip with one tab that looks broken.
            PvDraw.textFitLeft(ctx, tr, labels.get(i), tabLeft() + 10, textY,
                    tabWidth() - 10 - (DOT_R * 2 + 8), textColor);
            if (dirtyTest != null && dirtyTest.test(i)) {
                PvDraw.dot(ctx, tabLeft() + tabWidth() - DOT_R - 6, ty + tabHeight / 2, DOT_R,
                        PvTheme.ACCENT);
            }
        }

        drawFocusRing(ctx, PvTheme.RADIUS);
    }

    /**
     * Arrow keys walk the strip when it has focus.
     *
     * <p>Reached through the screen's existing nudge path rather than a private key handler, so the
     * strip behaves like every other focusable control: Tab to it, arrows to change it.</p>
     */
    @Override
    public void nudge(int direction) {
        int next = Math.max(0, Math.min(labels.size() - 1, selected + direction));
        if (next != selected) {
            MinecraftClient.getInstance().getSoundManager()
                    .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.1F));
            select(next);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        int index = tabAt(my);
        if (index < 0) {
            return false;
        }
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.1F));
        select(index);
        return true;
    }
}
