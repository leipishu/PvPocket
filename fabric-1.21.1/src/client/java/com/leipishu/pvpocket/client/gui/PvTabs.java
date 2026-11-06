package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleGroup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.List;

/**
 * Vertical tab strip for the rule groups, drawn as a sidebar.
 *
 * <p>Vertical rather than horizontal because the labels are short and the panel is wider than it is
 * tall: a vertical strip costs 96px of width and saves 26px of the scarce axis, and it leaves room
 * for all seven groups without a scroll or an overflow menu.</p>
 *
 * <p>The strip owns its own surface — one step lighter than the panel, one step darker than a card.
 * Before this it had no background at all, which made the tabs read as a list of links rather than
 * as a place; the sidebar is what tells the eye that the panel has two regions.</p>
 */
public class PvTabs extends PvWidget {

    private static final int INSET = 5;

    private final List<String> labels;
    /** Right-aligned short badge per tab; null for strips that do not need one. */
    private final List<String> badges;
    /** Non-null only for the rule-group strip, which is the only caller that needs the group back. */
    private final List<RuleGroup> groups;
    private int selected;
    private Runnable onChange;
    private int tabHeight = PvTheme.TAB_H;
    private float indicatorY = -1F;
    private float[] hotT = new float[0];

    public PvTabs(List<String> labels, int selected) {
        this(labels, null, null, selected);
    }

    /**
     * The rule-group strip.
     *
     * <p>A static factory rather than an overload: {@code List<RuleGroup>} and {@code List<String>}
     * erase to the same constructor signature, so the two variants cannot coexist as constructors.</p>
     */
    public static PvTabs ofRules(List<RuleGroup> groups, int selected) {
        return new PvTabs(
                groups.stream().map(RuleGroup::label).toList(),
                groups.stream().map(g -> g.letter).toList(),
                groups, selected);
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
     * the screen knows to draw the roster board instead of rule rows. The badge is blank because the
     * other badges are the group letters, and a letter here would claim it is a rule group.</p>
     */
    public static PvTabs ofPanel(List<RuleGroup> groups, String rosterLabel, int selected) {
        List<String> labels = new java.util.ArrayList<>();
        List<String> badges = new java.util.ArrayList<>();
        List<RuleGroup> slots = new java.util.ArrayList<>();
        labels.add(rosterLabel);
        badges.add("");
        slots.add(null);
        for (RuleGroup group : groups) {
            labels.add(group.label());
            badges.add(group.letter);
            slots.add(group);
        }
        return new PvTabs(labels, badges, slots, selected);
    }

    private PvTabs(List<String> labels, List<String> badges, List<RuleGroup> groups, int selected) {
        this.labels = labels;
        this.badges = badges;
        this.groups = groups;
        this.selected = selected;
        this.width = 96;
        this.hotT = new float[labels.size()];
        this.height = INSET * 2 + labels.size() * PvTheme.TAB_H + (labels.size() - 1);
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
        tabHeight = Math.max(13, Math.min(PvTheme.TAB_H, pitch));
        height = INSET * 2 + labels.size() * tabHeight + (labels.size() - 1);
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

        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, PvTheme.BORDER, PvTheme.SIDEBAR);

        // The selection marker slides between tabs instead of jumping. It is the one animation in
        // the panel that crosses widgets, and it is what makes a tab switch read as one movement
        // rather than as two independent repaints.
        float targetY = tabY(selected) + (tabHeight - 14) / 2F;
        indicatorY = indicatorY < 0F
                ? targetY
                : PvAnim.damp(indicatorY, targetY, d, 0.055F);

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
            PvDraw.text(ctx, tr, labels.get(i), tabLeft() + 10, textY, textColor);
            if (badges != null) {
                PvDraw.textRight(ctx, tr, badges.get(i), tabLeft() + tabWidth() - 8, textY,
                        active ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);
            }
        }

        PvDraw.roundRect(ctx, tabLeft(), Math.round(indicatorY), 2, 14, 1, PvTheme.ACCENT);
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
