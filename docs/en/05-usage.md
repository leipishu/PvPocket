# Usage

> Specification · English. Chinese version: [`../zh/05-usage.md`](../zh/05-usage.md)

## 1. Deployment

### Server

1. Fabric Loader 0.16.9+, Minecraft 1.21.1, Java 21.
2. Put `pvpocket-*.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in `mods/`.
3. The first start creates a `pvpocket/` directory in the world save (`journal`, `homes.json`,
   `guard.json`, `stats`, `history`).
4. The duel dimension loads and pre-stamps at server start; five maps take about 10 seconds, one per
   tick. Until then `/pvpocket arena` reports "gallery not stamped" and no duel can start.

**Clients must run the same mod.** Without it there is no rule panel, no loadout editor and no
scoreboard.

### Permissions

| Command | Requires |
| --- | --- |
| Most `/pvpocket` subcommands | Nothing (any player) |
| `/pvpocket guard <bool>` | OP level 2 |
| `/pvpocket arena reset` | OP level 3 |
| `/pvpocket arena copy <map> [count]` | OP level 3 |
| `/pvpocket arena remove <map> [count]` | OP level 3 |
| The home panel's "Admin" page | OP level 3 |

## 2. Player flow

### The home panel

Two entry points, equivalent:

- The `B` key (rebindable on the panel's Settings page; the change is written straight into the
  vanilla key bindings, so it shows up — and can be undone — in the vanilla Controls screen)
- The "Duel" button to the left of the inventory

The key only fires when **no screen is open**. That is deliberate: pressing `B` over an open rule
draft would bury it, and the draft's unsent edits are not recoverable by closing the panel again.

Five tabs:

| Tab | Contents |
| --- | --- |
| Home | Greeting, one random encouraging line, one primary action (start a duel / resume an open draft / surrender / stop spectating, depending on state), and the matches running right now — each with a way in |
| Stats | Matches, win rate, total damage, KD, rounds won, best streak, plus the last ten results as squares |
| History | Recent series: score, win/loss, map, mode, team size, per-round outcomes; open one for the round-by-round detail and a one-line result to copy; paged at the bottom, with the page size set in Settings |
| Presets | The loadouts and rule sets on this machine: start from one, share, import, delete |
| Settings | Version, licence, config path, light/dark theme, motion toggle, scoreboard size, result-screen toggle, history rows per page, key rebinding |

The top bar carries the date, weekday and time.

**The numbers belong to this server.** Career counters and history live in the **world save**, under
`pvpocket/stats` and `pvpocket/history`, and the panel clears the moment you disconnect. That is
intentional: two servers' win rates are not comparable.

What the numbers mean:

- **Total damage** is health dealt, after armour and before absorption. A 20-point hit on somebody
  with 3 health left counts as 20, not 3 — otherwise the total would depend on how the last hit
  landed rather than on how hard it hit.
- **Kills** are attributed to the attacker at the moment of death. Falling, self-damage and
  environment kills count for nobody; so do kills inside your own side.
- **Rounds won** counts each round's winner, summed across every series.
- **Best streak** is a high-water mark: a loss resets the current streak, never the best one.
- History keeps the newest 200 series per player; the lifetime counters are never trimmed.

### Starting a duel

1. A: press "Start a duel" on the home panel, or `/pvpocket duel` → the rule panel opens on the
   **players page** (the first tab). It holds two team cards by default, each with `b.teamSize` slots,
   and a strip of that side's wool colour on top.
2. A: press the `+` in a card's corner and pick people out of the dialog; "+ Add team" under the cards
   takes it up to four. An empty card does not play, and at least two sides need somebody before the
   invite can go out. Sides do not have to be the same size.
3. A: switch to the A/B/… pages for the rules, press "Edit loadout" if the items need work, then
   "Send invite".
4. Everybody invited gets the panel at once, opening on the players page, showing which side they are
   on and who has not answered yet.
5. Each of them picks "Accept and start", "Edit and re-propose" or "Reject". **The match starts once
   everybody has answered and two sides still have somebody on them.** A refusal only takes that
   player off the roster: invite four, one declines, and the remaining three still play.
6. On start every side is teleported in; the round goes live when the countdown ends.
7. After the last round everyone returns to where they entered, items and terrain restored.

If the panel is dismissed with Esc, `/pvpocket review` reopens it — that is the only way back.
Closing it while the draft is still open cancels the invite.

### Presets and share codes

Three places open the preset list: the home panel's Presets tab, the "Presets" link at the right end
of the rule panel's title row, and the same link in the loadout editor. Loadouts and rule sets are
stored separately and are never interchangeable.

- **Save** names the current state and writes it to `config/pvpocket/presets.json`. A name that is
  already taken is overwritten.
- **Load** puts a preset back into the panel. It **does not submit anything** — a rule set still
  needs "Send invite", a loadout still needs "Save loadout".
- **Start from a preset**: every row on the home panel's Presets tab has "Start". A rule preset opens
  a draft with those rules applied; a loadout preset opens a draft and swaps the loadout on your side.
  The invite still goes out the normal way.
- **Delete** lives behind "More" on the same row and takes two presses.
- **Share** asks the server for a six-character code, valid for five minutes, and copies it to the
  clipboard.
- **Import** takes somebody else's code. The preset **lands in the local file immediately**; the code
  is never needed again.

A rule preset **does not contain the roster**. Loading one changes the settings and leaves the team
cards exactly as they were — who is playing is a decision about this match and these people, and a
preset that re-seats players is a preset that rearranges somebody else's team.

Presets live on the client, not on the server: change servers and they are still there. A share code
is a temporary thing held in memory and swept after five minutes; case, spaces and dashes in a typed
code make no difference.

### Locked loadout items

The last row of every entry in the loadout editor is **No dropping**, and it is **on by default**. While
it is on, Q does nothing on the stacks that entry grants — not in the hand, and not on the slot while
the inventory is open. Both cases leave the item where it is and print "This item is locked and cannot
be dropped" in chat.

- **Only the stacks the loadout handed out.** The lock lives on the item, not on the slot: move it,
  stash it in a chest, drop it and pick it back up, and it is still locked. The arrows, obsidian or
  flint and steel the same row brings are locked too; anything picked up off the ground, crafted, or
  taken from a map is an ordinary item and drops as usual.
- **Ctrl+Q on the whole stack is refused too.** The lock is on the "throw with Q" gesture, not on a
  particular quantity.
- **Dragging a stack out of the inventory window still works.** That takes a deliberate drag past the
  edge of the window rather than a stray keypress, and blocking it would be blocking "moving items".
- Turned off, the entry behaves exactly like any other. The switch is saved with the loadout and
  travels with it in share codes.

### Spectating

`/pvpocket list` opens the match list; press "Watch" to enter as a spectator. While watching:

- `V` cycles the camera between participants
- `G` detaches, returning to free flight
- Free flight is confined to the arena bounding box expanded by 12 blocks
- To leave: "Leave" in the match list, or `/pvpocket spectate leave`

After possessing a player a bar appears at the bottom of the screen (`Spectating · <map> · round N`
plus the camera hint), and the hotbar shows the possessed player's nine slots with
`name · HP · armour`. Vanilla draws no hand at all in spectator mode (`GameRenderer.renderHand`
returns early), so the held item is redrawn by the mod using the vanilla `HeldItemRenderer`
transform — main and off hand separately, a bare arm when empty, swing arc and equipment offsets
included — pixel-identical to what that player sees on their own screen.

Leaving restores game mode, dimension, position and rotation. Inventory, effects and experience
were never touched, so there is nothing to restore.

Spectating is allowed by default; the challenger can turn it off in rule group B.

### The result card

When a whole series ends — not a single round — every participant gets a result card. The server
pushes it, so no panel has to be opened for it; `Esc` closes it and returns to the game.

From top to bottom:

- **One headline.** In Chinese it is two lines, the verdict and the same verdict in English, because
  this is the one screen in the mod a player is likely to screenshot for somebody who does not read
  their language. A loss reads 「再接再厉 / Well fought!」 and a draw 「势均力敌 / Evenly matched」 —
  never "defeat": the score is right below, and the same fact does not need saying twice.
- **One row per side**: a wool-coloured bar, that side's roster, and the rounds it won. Your own side
  has a tinted row and your name carries a marker.
- **Round by round**, two columns: which round, won/lost/drawn, how it ended (kill, out of bounds,
  disconnect, timed out…), and how long it ran. When it does not all fit, the last slot says how many
  rounds are missing rather than dropping them silently.
- **At the bottom**: map, mode, team size, total length, and the timestamp plus the reason the series
  ended (surrender, cut short, draw).

`Export` redraws the same card **full-bleed** — no scrim, no world behind it, no buttons — and writes
the framebuffer to:

```
screenshots/pvpocket/result-2026-11-10_20-46-25.png
```

Resolution follows the window and GUI scale: a 1280x720 window at scale 3 gives a 1280x720 image, at
the panel's 1.0x type size (the bitmap font is not resampled at integer scales, so it stays sharp).
The poster is taller than the panel, which matters for long series: fifteen rounds fit the poster and
only six fit the panel on a 320x180 canvas.

To stop seeing it: Settings → Matches → Result screen. The switch is **per installation**, not per
account — the server still sends the card to every participant and each client decides whether to
draw it.

## 3. Commands

The command surface is deliberately narrow: **anything the rule panel can do has no command**.
Propose, accept and reject exist only as panel buttons.

The home panel does not replace the commands. Everything it does — starting a duel, starting from a
preset, watching, leaving — is still reachable as a command, because the console and RCON have no
client and scripts and operators can only speak commands.

| Command | Purpose |
| --- | --- |
| `/pvpocket help` | Show help (same as bare `/pvpocket`) |
| `/pvpocket duel` | Open the rule panel: pick the players, set the sides and the rules there; takes no arguments |
| `/pvpocket review` | Reopen the rule panel |
| `/pvpocket surrender` | Forfeit the whole series (not one round) |
| `/pvpocket status` | Current session's stage, round, score and rule summary |
| `/pvpocket list` | Open the match list |
| `/pvpocket spectate <id prefix>` | Enter a match directly, bypassing the panel |
| `/pvpocket spectate leave` | Stop spectating |
| `/pvpocket loadout` | Print the loadout, its fingerprint and validation result |
| `/pvpocket loadout edit` | Open the loadout editor (no duel required) |
| `/pvpocket loadout give` | Hand the loadout to yourself (testing) |
| `/pvpocket loadout accept` | Agree to a proposed between-round loadout change (rule must be on) |
| `/pvpocket loadout decline` | Refuse a proposed change; the old loadout stands |
| `/pvpocket loadout reset` | Clear the personal loadout, back to the default preset |
| `/pvpocket arena` | Cell occupancy, change counts, live sessions |
| `/pvpocket arena reset` | Abort every session and rebuild the dimension (OP 3) |
| `/pvpocket arena copy <map> [count]` | Give a map more plots so more rooms run at once (OP 3) |
| `/pvpocket arena remove <map> [count]` | Take a map's plots back and clear them; with none left the map cannot be picked (OP 3) |
| `/pvpocket guard <true\|false>` | Toggle automatic ejection of stray players, per player (OP 2) |

The match list shows at most six matches; beyond that it points at `/pvpocket spectate <id>`.

## 4. Operations

### Stray-player guard

A player inside the duel dimension who is neither a participant nor a spectator (walked in, or the
server crashed mid-duel) is sent back to their pre-entry position after five seconds. The
`[ Do not eject ]` button in the prompt is exactly `/pvpocket guard false`.

```bash
/pvpocket guard false   # stop the ejection, go in and inspect
/pvpocket guard true    # restore afterwards
```

Exemptions are **per player** and persisted to `<world>/pvpocket/guard.json`, surviving restarts.
Per player rather than global because a global switch forgotten once means the protection is off for
everybody.

The button is drawn only for OP level ≥2 — for anyone else the click returns "unknown command", and
showing it would be worse than hiding it.

### Arena rebuild

When terrain looks damaged (wrong block states, missing structure):

```bash
/pvpocket arena reset
```

It ejects every spectator → `abort`s every live session (not settle: no rewards, no next round) →
sends out anyone still in the dimension → clears it → re-stamps every map one tick at a time. The
whole server is notified because it goes quiet for a few seconds, and silence with no explanation
reads as a crash. No new duel can start until it finishes.

### Not enough plots

A map can only run as many duels at once as it owns plots; anything beyond that is refused and the
map is greyed out in the panel. Defaults are four for the flat plot, two for the colosseum, one for
everything else. To add more:

```bash
/pvpocket arena copy flat 2            # two more flat plots
/pvpocket arena copy arena_colosseum   # no count means one
/pvpocket arena remove flat            # take one back; the cell is cleared and freed
```

A copy gets a **fixed cell number**, recorded in `<world>/pvpocket/arena.json`, and it does not move
across restarts — so `/tp` to it and the spot stays valid. Copies of a non-flat map are pasted when
the command runs; copies of a flat map only reserve a cell and are paved by the first duel that
lands on one. The grid holds 16 cells; when it is full the dimension has to grow.

`remove` takes back **the copy added last**, so the remaining ones keep their addresses. A cell that
is in a live duel is skipped, and if every cell is leased the command refuses until the round ends.
**There is no lower bound**: with none left the map disappears from the rule panel, and pressing
"Copy" again brings it back at its own address rather than at a new cell at the end of the grid.
Details in [`06-maps.md`](06-maps.md#5-plots-and-copies).

### The operator page

At operator level 3 the home panel (default key `B`) grows an **Admin** tab just above Settings:

| Control | Equivalent |
| --- | --- |
| "Copy" beside each map | `/pvpocket arena copy <map>` |
| "Remove" beside each map | `/pvpocket arena remove <map>` |
| "Rebuild arena" | `/pvpocket arena reset` (asks first, and says how many duels it will end) |
| The "stray guard" switch | `/pvpocket guard true\|false` |

The plot table is a live snapshot: after a copy, a removal, a rebuild or a flip the server sends a
fresh one and the page follows. "Remove" asks nothing first — it ends no duel (a map whose cells are
all in use has the button greyed out) and the "Copy" beside it undoes it. The confirmation is kept
for the rebuild, which is the one action that interrupts other people. The panel is only another door onto the same server code — permission is checked
server-side, and the panel not drawing a button is a convenience, not the lock.

### Sandbox rules

Inside the duel dimension, player chat may only run commands starting with `/pvpocket`, and nobody
may change their own game mode. Console, RCON and command blocks are exempt — operators need to be
able to rescue a stuck player.

## 5. Troubleshooting

| Symptom | Look at |
| --- | --- |
| Panel does not appear | Server log line `Rules panel -> <player> (canSend=…)`; `false` means the client never declared the channel (version mismatch) |
| The home panel's button is grey | The server never declared the home protocol (version mismatch). The button's tooltip says so |
| Stats stay empty | They start with the first *finished* series; a match in progress is not counted until it settles |
| The numbers vanished after switching servers | Expected. They belong to the world save, not to the account |
| Start refused, "arena is rebuilding" | The previous `arena reset` has not finished stamping; wait until `/pvpocket arena` shows the full map count |
| Start refused, "in use by another duel" | One map, one duel; pick another |
| Player stuck in the duel dimension | `/pvpocket guard false`, teleport them out manually, or wait five seconds for the automatic ejection |
| Surrender does nothing | "Allow surrender" is off in rule group G |
| Player lost items | Find the session's `SNAPSHOT_TAKEN` / `RESTORED` sequence in `pvpocket/journal/` |
| The text in the panels looks smeared, Chinese especially | Not the mod — it is the client's font. Vanilla draws Chinese with a 16×16 bitmap squeezed into a 9px line height; install a TrueType resource pack covering both scripts. See the note at the top of the [`README`](README.md) |

Logs: server `<world>/logs/latest.log`; dev clients `fabric-1.21.1/build/devlogs/client*.out`.
