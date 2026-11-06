package com.leipishu.pvpocket.client.gui.match;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.network.MatchListPayload;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /pvpocket list} panel: every duel currently being fought, with a way in.
 *
 * <p>Rows are drawn by hand rather than built from a scroll pane of row widgets, and that is a
 * deliberate limit rather than a shortcut: the panel shows at most {@link #MAX_ROWS} matches, and a
 * server running more than six simultaneous duels is a server that needs a different UI, not a
 * scrollbar. Past the cap the panel says how many it is not showing and points at
 * {@code /pvpocket spectate <id>}, which is the escape hatch for the one operator who needs it.</p>
 *
 * <p>The buttons are real widgets in the screen's root list, positioned to sit on their row. That
 * keeps focus traversal, hover, tooltips and keyboard activation working exactly as they do in the
 * rule panel — the parts of a GUI that are easy to get subtly wrong are the parts this reuses.</p>
 */
public class MatchListScreen extends PvScreen {

    private static final int MAX_W = 500;
    private static final int HEADER_H = 46;
    private static final int ROW_H = 38;
    private static final int FOOTER_H = 40;
    private static final int MAX_ROWS = 6;
    private static final int BTN_W = 58;
    private static final int BTN_H = 22;

    /** One row of the list, parsed from the payload's flat encoding. */
    public record Entry(String id, String map, String stage, int round, int bestOf,
                        String score, int flags, int spectators, List<String> players) {

        public boolean spectatable() {
            return (flags & MatchListPayload.FLAG_SPECTATABLE) != 0;
        }

        public boolean participant() {
            return (flags & MatchListPayload.FLAG_PARTICIPANT) != 0;
        }

        public boolean watching() {
            return (flags & MatchListPayload.FLAG_WATCHING) != 0;
        }

        public String playersLine() {
            return players.isEmpty() ? "—" : String.join(" vs ", players);
        }

        /**
         * Parses the payload, dropping malformed rows.
         *
         * <p>A row that does not split into the expected number of fields is a version skew — the
         * server is running a build that knows about a field this client does not. Dropping the row
         * is the answer that keeps the rest of the panel usable; throwing would turn a cosmetic
         * mismatch into a crash on a screen the player cannot close.</p>
         */
        static List<Entry> parse(String encoded) {
            List<Entry> out = new ArrayList<>();
            if (encoded == null || encoded.isEmpty()) {
                return out;
            }
            for (String line : encoded.split("\n")) {
                String[] f = line.split("\\|", 9);
                if (f.length < 9) {
                    continue;
                }
                List<String> names = new ArrayList<>();
                for (String pair : f[8].split(",")) {
                    int colon = pair.indexOf(':');
                    if (colon > 0) {
                        names.add(pair.substring(0, colon));
                    }
                }
                try {
                    out.add(new Entry(f[0], f[1], f[2],
                            Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                            f[5], Integer.parseInt(f[6]), Integer.parseInt(f[7]), names));
                } catch (NumberFormatException ignored) {
                    // Same reasoning as the field-count check: one bad row, not one bad panel.
                }
            }
            return out;
        }
    }

    private final List<Entry> entries;
    private final float[] rowHot;
    private int rowTop;

    public MatchListScreen(MatchListPayload payload) {
        super(Text.literal("对局列表"));
        this.entries = Entry.parse(payload.entries());
        this.rowHot = new float[entries.size()];
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        int shown = Math.min(entries.size(), MAX_ROWS);
        int extra = entries.size() > MAX_ROWS ? 14 : 0;
        int rowsHeight = Math.max(1, shown) * ROW_H;
        layoutPanel(MAX_W, HEADER_H + rowsHeight + extra + FOOTER_H);
        rowTop = panelY + HEADER_H;

        for (int i = 0; i < shown; i++) {
            addRowButton(entries.get(i), i);
        }
        buildFooter();
        setFocus(null);
    }

    private void addRowButton(Entry entry, int index) {
        PvButton button;
        if (entry.participant()) {
            button = new PvButton("本局中", PvButton.Style.GHOST, null);
            button.enabled = false;
            button.tooltip = "你是这一局的参与者。";
        } else if (entry.watching()) {
            button = new PvButton("退出", PvButton.Style.DANGER, this::leave);
            button.tooltip = "停止观战并回到原来的位置。";
        } else {
            button = new PvButton("观战", PvButton.Style.NORMAL, () -> join(entry.id));
            if (!entry.spectatable()) {
                button.enabled = false;
                button.tooltip = "本局没有开启观战。";
            } else {
                button.tooltip = "以旁观模式进入，按 V 切换视角。";
            }
        }
        button.size(BTN_W, BTN_H);
        // Inset from the card's edge, not flush with it: the card border is at
        // panelX + panelW - 12, so a button ending there overlaps the border and reads as if it is
        // falling out of the row.
        button.x = panelX + panelW - 20 - BTN_W;
        button.y = rowTop + index * ROW_H + (ROW_H - BTN_H) / 2 - 2;
        roots.add(button);
    }

    private void buildFooter() {
        int y = panelY + panelH - FOOTER_H + 8;
        int right = panelX + panelW - 12;
        PvButton close = new PvButton("关闭", PvButton.Style.NORMAL, this::close);
        close.size(56, PvTheme.BTN_H);
        close.x = right - close.width;
        close.y = y;
        roots.add(close);

        PvButton refresh = new PvButton("刷新", PvButton.Style.GHOST, this::refresh);
        refresh.size(52, PvTheme.BTN_H);
        refresh.x = panelX + 12;
        refresh.y = y;
        roots.add(refresh);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = font();
        float d = PvAnim.dt(delta);

        PvDraw.title(ctx, tr, "对局列表", panelX + 16, panelY + 13, PvTheme.TEXT);
        PvDraw.text(ctx, tr, entries.isEmpty()
                        ? "当前没有进行中的对局"
                        : entries.size() + " 场进行中",
                panelX + 16, panelY + 28, PvTheme.TEXT_DIM);
        PvDraw.hLine(ctx, panelX + 12, panelY + 40, panelW - 24, PvTheme.DIVIDER);

        if (entries.isEmpty()) {
            PvDraw.textCenter(ctx, tr, "暂无进行中的对局",
                    panelX + panelW / 2, rowTop + 14, PvTheme.TEXT_MUTED);
        }

        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            drawRow(ctx, tr, entries.get(i), i, mouseX, mouseY, d);
        }
        if (entries.size() > MAX_ROWS) {
            PvDraw.textCenter(ctx, tr,
                    "另有 " + (entries.size() - MAX_ROWS) + " 场 · /pvpocket spectate <id>",
                    panelX + panelW / 2, rowTop + shown * ROW_H + 2, PvTheme.TEXT_MUTED);
        }

        PvDraw.hLine(ctx, panelX + 12, panelY + panelH - FOOTER_H, panelW - 24, PvTheme.DIVIDER);
        PvDraw.text(ctx, tr, "V 切换视角 · G 解除附身",
                panelX + 72, panelY + panelH - FOOTER_H + 16, PvTheme.TEXT_MUTED);
    }

    private void drawRow(DrawContext ctx, TextRenderer tr, Entry entry, int index,
                         int mouseX, int mouseY, float d) {
        int x = panelX + 12;
        int y = rowTop + index * ROW_H;
        int w = panelW - 24;
        int h = ROW_H - 4;
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

        rowHot[index] = PvAnim.damp(rowHot[index], hot ? 1F : 0F, d, 0.09F);
        float t = rowHot[index];

        PvDraw.roundPanel(ctx, x, y, w, h, PvTheme.RADIUS,
                PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, t),
                PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, t));

        // A watching row is marked with an accent bar rather than a different fill: the fill is
        // already carrying hover, and one visual channel cannot say two things at once.
        if (entry.watching()) {
            PvDraw.roundRect(ctx, x + 1, y + 6, 2, h - 12, 1, PvTheme.ACCENT);
        }

        // Two lines of 8px with 5px leading occupy 21px of the 34px card, so the block starts at
        // y+7 and ends at y+28 — centred, with the same 3px above and below. Every element on line
        // one shares that baseline, including the stage chip, which is why the chip is 12px tall at
        // y+5 rather than 13px at y+5: half a pixel of misalignment is visible on a label this small.
        int textX = x + 10;
        int line1 = y + 7;
        int line2 = y + 20;
        PvDraw.text(ctx, tr, entry.map(), textX, line1, PvTheme.TEXT);

        // Stage chip, right after the map name so the eye reads "where, then what state".
        int stageColor = "进行中".equals(entry.stage()) ? PvTheme.LIVE : PvTheme.ACCENT;
        int chipX = textX + tr.getWidth(entry.map()) + 8;
        int chipW = tr.getWidth(entry.stage()) + 10;
        PvDraw.roundRect(ctx, chipX, line1 - 2, chipW, 12, 3, PvTheme.alpha(stageColor, 0.14F));
        PvDraw.text(ctx, tr, entry.stage(), chipX + 5, line1, stageColor);

        PvDraw.text(ctx, tr, entry.playersLine(), textX, line2, PvTheme.TEXT_DIM);

        // Score and spectator count right-aligned against the button, on one line centred in the
        // card. They used to sit on the two text baselines on the left, which left the score in the
        // top third of the row next to a button centred in it — the two anchors disagreed.
        int anchor = panelX + panelW - 20 - BTN_W - 12;
        int scoreY = y + (h - 8) / 2 + 1;
        String score = entry.score();
        PvDraw.textRight(ctx, tr, score, anchor, scoreY, PvTheme.TEXT);
        if (entry.spectators() > 0) {
            String watchers = entry.spectators() + " 人观战";
            PvDraw.textRight(ctx, tr, watchers, anchor - tr.getWidth(score) - 10, scoreY,
                    PvTheme.TEXT_MUTED);
        }
        // Round number after the stage chip, where it reads as "which round of that state".
        if (entry.bestOf() > 1) {
            PvDraw.text(ctx, tr, "第 " + Math.max(1, entry.round()) + "/" + entry.bestOf() + " 局",
                    chipX + chipW + 6, line1, PvTheme.TEXT_MUTED);
        }
    }

    // ------------------------------------------------------------------ actions

    private void join(String sessionId) {
        send(SpectateRequestPayload.join(sessionId));
        // Closed immediately: the server is about to switch this player into spectator mode and
        // teleport them, and a modal panel over that transition would be the last thing they see.
        MinecraftClient.getInstance().setScreen(null);
    }

    private void leave() {
        send(SpectateRequestPayload.leave());
        refresh();
    }

    private void refresh() {
        send(SpectateRequestPayload.list());
    }

    private static void send(SpectateRequestPayload payload) {
        if (ClientPlayNetworking.canSend(SpectateRequestPayload.ID)) {
            ClientPlayNetworking.send(payload);
        }
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(null);
    }
}
