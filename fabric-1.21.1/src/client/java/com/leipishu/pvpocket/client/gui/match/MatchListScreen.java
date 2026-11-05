package com.leipishu.pvpocket.client.gui.match;

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

    private static final int MAX_W = 470;
    private static final int HEADER_H = 46;
    private static final int ROW_H = 36;
    private static final int FOOTER_H = 36;
    private static final int MAX_ROWS = 6;
    private static final int BTN_W = 56;
    private static final int BTN_H = 18;

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
    private int rowTop;

    public MatchListScreen(MatchListPayload payload) {
        super(Text.literal("对局列表"));
        this.entries = Entry.parse(payload.entries());
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        int shown = Math.min(entries.size(), MAX_ROWS);
        int extra = entries.size() > MAX_ROWS ? 12 : 0;
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
                button.tooltip = "本局没有开启观战。发起方可以在规则面板 B 组打开「允许观战」。";
            } else {
                button.tooltip = "以旁观模式进入，按 V 切换视角。";
            }
        }
        button.size(BTN_W, BTN_H);
        button.x = panelX + panelW - 12 - BTN_W;
        button.y = rowTop + index * ROW_H + (ROW_H - BTN_H) / 2;
        roots.add(button);
    }

    private void buildFooter() {
        int y = panelY + panelH - FOOTER_H + 8;
        int right = panelX + panelW - 12;
        PvButton close = new PvButton("关闭", PvButton.Style.NORMAL, this::close);
        close.size(52, PvTheme.BTN_H);
        close.x = right - close.width;
        close.y = y;
        roots.add(close);

        PvButton refresh = new PvButton("刷新", PvButton.Style.GHOST, this::refresh);
        refresh.size(48, PvTheme.BTN_H);
        refresh.x = panelX + 12;
        refresh.y = y;
        roots.add(refresh);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = font();
        PvDraw.textScaled(ctx, tr, "对局列表", panelX + 16, panelY + 11,
                PvTheme.TITLE_SCALE, PvTheme.TEXT);
        PvDraw.text(ctx, tr, entries.isEmpty()
                        ? "服务器上当前没有进行中的对局"
                        : entries.size() + " 场进行中 · 点「观战」以旁观模式进入",
                panelX + 16, panelY + 28, PvTheme.TEXT_DIM);
        PvDraw.hLine(ctx, panelX + 12, panelY + 40, panelW - 24, PvTheme.DIVIDER);

        if (entries.isEmpty()) {
            PvDraw.textCenter(ctx, tr, "等有人开打之后，这里会列出他们的对局。",
                    panelX + panelW / 2, rowTop + 12, PvTheme.TEXT_MUTED);
        }

        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            drawRow(ctx, tr, entries.get(i), i, mouseX, mouseY);
        }
        if (entries.size() > MAX_ROWS) {
            PvDraw.textCenter(ctx, tr,
                    "还有 " + (entries.size() - MAX_ROWS) + " 场未显示，可用 /pvpocket spectate <id> 进入",
                    panelX + panelW / 2, rowTop + shown * ROW_H + 2, PvTheme.TEXT_MUTED);
        }

        PvDraw.hLine(ctx, panelX + 12, panelY + panelH - FOOTER_H, panelW - 24, PvTheme.DIVIDER);
        PvDraw.text(ctx, tr, "观战中按 V 切换视角 · /pvpocket spectate leave 退出",
                panelX + 68, panelY + panelH - FOOTER_H + 14, PvTheme.TEXT_MUTED);
    }

    private void drawRow(DrawContext ctx, TextRenderer tr, Entry entry, int index,
                         int mouseX, int mouseY) {
        int x = panelX + 12;
        int y = rowTop + index * ROW_H;
        int w = panelW - 24;
        int h = ROW_H - 4;
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

        PvDraw.roundRect(ctx, x, y, w, h, PvTheme.RADIUS,
                hot ? PvTheme.SURFACE_HOVER : PvTheme.SURFACE);
        // A watching row is marked with an accent bar rather than a different fill: the fill is
        // already carrying hover, and one visual channel cannot say two things at once.
        if (entry.watching()) {
            PvDraw.roundRect(ctx, x, y, 3, h, 2, PvTheme.ACCENT);
        }

        int textX = x + 10;
        PvDraw.text(ctx, tr, entry.map(), textX, y + 6, PvTheme.TEXT);

        // Stage chip, right after the map name so the eye reads "where, then what state".
        int stageColor = "进行中".equals(entry.stage()) ? PvTheme.OK : PvTheme.WARN;
        int chipX = textX + tr.getWidth(entry.map()) + 8;
        int chipW = tr.getWidth(entry.stage()) + 10;
        PvDraw.roundRect(ctx, chipX, y + 5, chipW, 12, 3, PvTheme.alpha(stageColor, 0.16F));
        PvDraw.text(ctx, tr, entry.stage(), chipX + 5, y + 7, stageColor);

        PvDraw.text(ctx, tr, entry.playersLine(), textX, y + 20, PvTheme.TEXT_DIM);

        // Score and spectator count right-aligned against the button, which is the one anchor in
        // the row that does not move with the length of a player name.
        int anchor = panelX + panelW - 12 - BTN_W - 10;
        PvDraw.textRight(ctx, tr, entry.score(), anchor, y + 6, PvTheme.TEXT);
        String watchers = entry.spectators() > 0 ? entry.spectators() + " 人观战" : "";
        if (!watchers.isEmpty()) {
            PvDraw.textRight(ctx, tr, watchers, anchor, y + 20, PvTheme.TEXT_MUTED);
        }
        // Round number after the stage chip, where it reads as "which round of that state".
        if (entry.bestOf() > 1) {
            PvDraw.text(ctx, tr, "第 " + Math.max(1, entry.round()) + "/" + entry.bestOf() + " 局",
                    chipX + chipW + 6, y + 7, PvTheme.TEXT_MUTED);
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
