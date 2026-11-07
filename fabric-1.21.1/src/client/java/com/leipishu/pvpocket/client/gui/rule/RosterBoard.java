package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The team cards: one card per side, each with a colour bar, a title and a column of slots.
 *
 * <p>Drawn as one widget rather than as a card widget plus a button per slot. The cards are a single
 * object — they are laid out against each other, they have to share a grid, and the empty slots have
 * to line up across cards or the whole thing stops reading as a roster. Splitting it into twenty
 * widgets would mean a layout pass that has to agree with the draw pass, which is the shape of bug
 * that produces a click landing one card over.</p>
 *
 * <p>Colour is the card's identity, and it is the wool colour, not the chat colour: the point is
 * that a player told "you are on the green side" can look at a green wool block and see the same
 * colour. Text is lifted towards white so it stays readable on the near-black panel — the bar keeps
 * the raw wool value, so the two never disagree about which side a card is.</p>
 */
public class RosterBoard extends PvWidget {

    /** One player on a card. {@code state} is 0 unanswered, 1 accepted, 2 declined. */
    public static final class Member {
        public final String name;
        public final String uuid;
        public int state;

        public Member(String name, String uuid, int state) {
            this.name = name;
            this.uuid = uuid;
            this.state = state;
        }
    }

    /** What the board asks the screen to do. The board never mutates the session itself. */
    public interface Listener {
        void onAddPlayer(int team);

        void onRemovePlayer(int team, int index);

        void onAddTeam();

        void onRemoveTeam(int team);
    }

    private static final int GAP = 8;
    private static final int CARD_HEAD = 16;
    private static final int SLOT_H = 14;
    private static final int CARD_PAD = 5;
    private static final int CAP = 12;
    private static final int HEAD = 10;
    private static final int MIN_TEAMS = 2;
    private static final int MAX_TEAMS = 4;
    /**
     * Narrowest card that still shows an avatar, a name and the remove glyph without truncating.
     *
     * <p>Two columns need two of these plus the gap. Below that the grid drops to one column: at the
     * 426x240 logical canvas the roster pane is about 200px wide, and two 96px cards truncate every
     * name to three characters — a roster you cannot read is worse than a roster you have to scroll.
     * </p>
     */
    private static final int MIN_CARD_W = 150;

    private final List<List<Member>> teams = new ArrayList<>();
    private final boolean editable;
    private int slots;
    private Listener listener;
    private String emptyKey = "pvpocket.gui.roster.slot_empty";

    public RosterBoard(boolean editable, int slots) {
        this.editable = editable;
        this.slots = Math.max(1, slots);
    }

    public void listener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Slot count follows the {@code b.teamSize} rule, which lives on another tab.
     *
     * <p>Mutable rather than constructor-only because the panel is one screen: a player who lowers
     * "每队人数" on page B and comes back has to see the cards shrink, or the setting looks inert.
     * Shrinking drops the members past the new last slot instead of refusing the change — they are
     * the ones the player added most recently, and refusing would leave the two pages disagreeing
     * about how many people are in the match.</p>
     */
    public void setSlots(int slots) {
        int next = Math.max(1, slots);
        if (next == this.slots) {
            return;
        }
        this.slots = next;
        for (List<Member> side : teams) {
            while (side.size() > next) {
                side.remove(side.size() - 1);
            }
        }
    }

    public int slots() {
        return slots;
    }

    /** Everybody on every card — the number the "at least two sides" check is a floor over. */
    public int playerCount() {
        int count = 0;
        for (List<Member> side : teams) {
            count += side.size();
        }
        return count;
    }

    public List<List<Member>> teams() {
        return teams;
    }

    public int teamCount() {
        return teams.size();
    }

    public void clear() {
        teams.clear();
    }

    public void addTeam() {
        if (teams.size() < MAX_TEAMS) {
            teams.add(new ArrayList<>());
        }
    }

    public void removeTeam(int team) {
        if (teams.size() > MIN_TEAMS && team >= 0 && team < teams.size()) {
            teams.remove(team);
        }
    }

    public boolean addPlayer(int team, String name, String uuid) {
        return addPlayer(team, name, uuid, 0);
    }

    /** @param state 0 unanswered, 1 accepted, 2 declined — only meaningful on a read-only board */
    public boolean addPlayer(int team, String name, String uuid, int state) {
        if (team < 0 || team >= teams.size() || teams.get(team).size() >= slots) {
            return false;
        }
        for (List<Member> side : teams) {
            for (Member member : side) {
                if (member.uuid.equals(uuid)) {
                    return false;
                }
            }
        }
        teams.get(team).add(new Member(name, uuid, state));
        return true;
    }

    public void removePlayer(int team, int index) {
        if (team >= 0 && team < teams.size()
                && index >= 0 && index < teams.get(team).size()) {
            teams.get(team).remove(index);
        }
    }

    /** {@code "uuid,uuid;uuid"} — the wire format the server parses back into cards. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (int team = 0; team < teams.size(); team++) {
            if (team > 0) {
                sb.append(';');
            }
            List<Member> side = teams.get(team);
            for (int i = 0; i < side.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(side.get(i).uuid);
            }
        }
        return sb.toString();
    }

    /** How many sides have somebody on them — the number the start condition actually uses. */
    public int playingTeams() {
        int count = 0;
        for (List<Member> side : teams) {
            if (!side.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ layout

    /** 2 when two readable cards fit, 1 when they do not. Never 0. */
    private int columns() {
        return width >= MIN_CARD_W * 2 + GAP ? 2 : 1;
    }

    private int cardW() {
        int cols = columns();
        return (width - GAP * (cols - 1)) / cols;
    }

    private int cardH() {
        return CARD_HEAD + slots * SLOT_H + CARD_PAD;
    }

    private int cardX(int index) {
        return x + (index % columns()) * (cardW() + GAP);
    }

    private int cardY(int index) {
        return y + (index / columns()) * (cardH() + GAP);
    }

    private int slotY(int cardY, int slot) {
        return cardY + CARD_HEAD + slot * SLOT_H;
    }

    /** Height the cards need, so the screen can decide whether it fits. */
    public int preferredHeight() {
        int cols = columns();
        int rows = (teams.size() + cols - 1) / cols;
        return rows * cardH() + Math.max(0, rows - 1) * GAP;
    }

    /**
     * The scroll pane hands out the width, and the grid has to re-answer "how tall am I" for it.
     *
     * <p>Column count is a function of width, so a card row that fits on two columns at 450px needs
     * two rows at 200px. Without this the pane would total the heights from the previous width and
     * cut the last card off.</p>
     */
    @Override
    public void onWidth(int width) {
        this.height = preferredHeight();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        for (int team = 0; team < teams.size(); team++) {
            drawCard(ctx, tr, team, mouseX, mouseY);
        }
    }

    private void drawCard(DrawContext ctx, TextRenderer tr, int team, int mouseX, int mouseY) {
        int cx = cardX(team);
        int cy = cardY(team);
        int cw = cardW();
        int ch = cardH();
        List<Member> side = teams.get(team);
        boolean empty = side.isEmpty();

        // The wash is what makes a card belong to a side without painting the panel over: 10% of the
        // wool colour on the card surface is enough to tell red from blue at a glance, and it leaves
        // the panel's five-step ladder intact underneath.
        PvDraw.roundPanel(ctx, cx, cy, cw, ch, PvTheme.RADIUS,
                PvTheme.BORDER_STRONG, PvTheme.teamWash(team, PvTheme.CARD, 0.10F));
        // The bar keeps the raw wool colour so it can be matched against a block on the floor.
        PvDraw.roundRect(ctx, cx + 1, cy + 1, cw - 2, 3, 1, PvTheme.team(team));

        String title = PvLang.str("pvpocket.gui.roster.team", team + 1);
        PvDraw.text(ctx, tr, title, cx + 8, cy + 7,
                empty ? PvTheme.TEXT_MUTED : PvTheme.teamInk(team));

        int capX = cx + cw - 6 - CAP;
        if (editable) {
            // Remove-team sits to the left of add-player, so the add button keeps the corner the
            // player is told to look for.
            if (teams.size() > MIN_TEAMS) {
                drawCap(ctx, cx + cw - 6 - CAP * 2 - 4, cy + 4, mouseX, mouseY, false);
            }
            drawCap(ctx, capX, cy + 4, mouseX, mouseY, true);
        }

        for (int slot = 0; slot < slots; slot++) {
            int sy = slotY(cy, slot);
            if (slot < side.size()) {
                drawMember(ctx, tr, team, cx, sy, side.get(slot), slot, mouseX, mouseY);
            } else {
                PvDraw.roundRect(ctx, cx + 6, sy + 1, cw - 12, SLOT_H - 3, 3, PvTheme.SUNKEN);
                PvDraw.text(ctx, tr, PvLang.str(emptyKey), cx + 10, sy + (SLOT_H - 8) / 2 + 1,
                        PvTheme.TEXT_MUTED);
            }
        }
    }

    private void drawMember(DrawContext ctx, TextRenderer tr, int team, int cx, int sy,
                            Member member, int slot, int mouseX, int mouseY) {
        int cw = cardW();
        int rowX = cx + 6;
        int rowW = cw - 12;
        boolean rejected = member.state == 2;
        boolean hoveredRow = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= sy && mouseY < sy + SLOT_H;

        if (hoveredRow && editable) {
            PvDraw.roundRect(ctx, rowX, sy, rowW, SLOT_H - 2, 3, PvTheme.CARD_HOVER);
        }

        int headY = sy + (SLOT_H - HEAD) / 2;
        PlayerSkinDrawer.draw(ctx, skinOf(member.uuid), rowX + 2, headY, HEAD);

        int textY = sy + (SLOT_H - 8) / 2;
        int nameColor = rejected ? PvTheme.TEXT_MUTED : PvTheme.TEXT;
        String name = member.name;
        int nameMax = rowW - HEAD - 22;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, name, nameMax), rowX + HEAD + 5, textY, nameColor);

        if (editable) {
            PvDraw.textRight(ctx, tr, "x", rowX + rowW - 4, textY,
                    hoveredRow ? PvTheme.DANGER : PvTheme.TEXT_MUTED);
        } else {
            // A dot, not a word: the reviewer's question is "who has answered", and a word per row
            // would not fit next to a name in a 180px card.
            int dotColor = switch (member.state) {
                case 1 -> PvTheme.LIVE;
                case 2 -> PvTheme.DANGER;
                default -> PvTheme.TEXT_MUTED;
            };
            PvDraw.dot(ctx, rowX + rowW - 5, sy + SLOT_H / 2, 2, dotColor);
        }
    }

    private void drawCap(DrawContext ctx, int capX, int capY, int mouseX, int mouseY, boolean plus) {
        boolean hot = mouseX >= capX && mouseX < capX + CAP && mouseY >= capY && mouseY < capY + CAP;
        PvDraw.roundRect(ctx, capX, capY, CAP, CAP, 3, hot ? PvTheme.CARD_ACTIVE : PvTheme.SUNKEN);
        int glyph = hot ? PvTheme.TEXT : PvTheme.TEXT_DIM;
        int mid = capY + CAP / 2;
        ctx.fill(capX + 4, mid, capX + CAP - 4, mid + 1, glyph);
        if (plus) {
            ctx.fill(capX + CAP / 2, capY + 4, capX + CAP / 2 + 1, capY + CAP - 4, glyph);
        }
    }

    private static SkinTextures skinOf(String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return DefaultSkinHelper.getSkinTextures(new UUID(0L, 0L));
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
                if (player.getUuid().equals(id)) {
                    return player.getSkinTextures();
                }
            }
        }
        return DefaultSkinHelper.getSkinTextures(id);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !editable) {
            return false;
        }
        for (int team = 0; team < teams.size(); team++) {
            int cx = cardX(team);
            int cy = cardY(team);
            int cw = cardW();
            if (mx < cx || mx >= cx + cw || my < cy || my >= cy + cardH()) {
                continue;
            }
            int capY = cy + 4;
            if (my >= capY && my < capY + CAP) {
                if (mx >= cx + cw - 6 - CAP && mx < cx + cw - 6) {
                    if (listener != null) {
                        listener.onAddPlayer(team);
                    }
                    return true;
                }
                if (teams.size() > MIN_TEAMS) {
                    int removeX = cx + cw - 6 - CAP * 2 - 4;
                    if (mx >= removeX && mx < removeX + CAP) {
                        if (listener != null) {
                            listener.onRemoveTeam(team);
                        }
                        return true;
                    }
                }
            }
            int slot = (int) ((my - cy - CARD_HEAD) / SLOT_H);
            if (slot >= 0 && slot < slots && slot < teams.get(team).size()) {
                if (listener != null) {
                    listener.onRemovePlayer(team, slot);
                }
                return true;
            }
            return false;
        }
        return false;
    }

    /** Read-only boards cannot be edited, so they are not focus stops either. */
    @Override
    public boolean focusable() {
        return false;
    }
}
