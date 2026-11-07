# Usage

> Specification · English. Chinese version: [`../zh/05-usage.md`](../zh/05-usage.md)

## 1. Deployment

### Server

1. Fabric Loader 0.16.9+, Minecraft 1.21.1, Java 21.
2. Put `pvpocket-*.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in `mods/`.
3. The first start creates a `pvpocket/` directory in the world save (`journal`, `homes.json`,
   `guard.json`).
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

## 2. Player flow

### Starting a duel

1. A: `/pvpocket duel` → the rule panel opens on the **players page** (the first tab). It holds two
   team cards by default, each with `b.teamSize` slots, and a strip of that side's wool colour on top.
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

The right end of the title row holds a "Presets" link, one on the rule panel and one in the loadout
editor. It opens the list of presets on this machine. Loadouts and rule sets are stored separately
and are never interchangeable.

- **Save** names the current state and writes it to `config/pvpocket/presets.json`. A name that is
  already taken is overwritten.
- **Load** puts a preset back into the panel. It **does not submit anything** — a rule set still
  needs "Send invite", a loadout still needs "Save loadout".
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

## 3. Commands

The command surface is deliberately narrow: **anything the rule panel can do has no command**.
Propose, accept and reject exist only as panel buttons.

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

### Sandbox rules

Inside the duel dimension, player chat may only run commands starting with `/pvpocket`, and nobody
may change their own game mode. Console, RCON and command blocks are exempt — operators need to be
able to rescue a stuck player.

## 5. Troubleshooting

| Symptom | Look at |
| --- | --- |
| Panel does not appear | Server log line `Rules panel -> <player> (canSend=…)`; `false` means the client never declared the channel (version mismatch) |
| Start refused, "arena is rebuilding" | The previous `arena reset` has not finished stamping; wait until `/pvpocket arena` shows the full map count |
| Start refused, "in use by another duel" | One map, one duel; pick another |
| Player stuck in the duel dimension | `/pvpocket guard false`, teleport them out manually, or wait five seconds for the automatic ejection |
| Surrender does nothing | "Allow surrender" is off in rule group G |
| Player lost items | Find the session's `SNAPSHOT_TAKEN` / `RESTORED` sequence in `pvpocket/journal/` |

Logs: server `<world>/logs/latest.log`; dev clients `fabric-1.21.1/build/devlogs/client*.out`.
