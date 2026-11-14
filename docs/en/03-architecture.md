# Architecture

> Specification · English. Chinese version: [`../zh/03-architecture.md`](../zh/03-architecture.md)

## 1. Layers

Sources split into two halves by environment (Fabric cells use Loom's
`splitEnvironmentSourceSets()` for two source sets; NeoForge cells use one source set with several
srcDirs, so client classes go into main):

| Source set | Contents | Notes |
| --- | --- | --- |
| `src/main/java` | Session, rules, arena, combat, networking registration, commands, storage | Loaded on both sides |
| `src/client/java` | Self-drawn GUI, HUD, input, rendering | Client only |

The mod **requires the same version on the client**: the rule panel, loadout editor, match list and
scoreboard are all drawn by the mod, so no client means no interface. The network layer still checks
`canSend` on every outbound packet — "required" is policy, not protocol, and a vanilla client can
still connect. In that case opening a panel must degrade to "nothing happens" rather than to a
server-side exception.

## 2. Authority

**The client expresses intent; the server decides.**

| What the client may say | What the server does about it |
| --- | --- |
| "I accept these rules" (`RulesSubmitPayload`) | Re-checks stage, whether it is their turn, and whether the loadout fingerprint still matches |
| "I want to watch this match" (`SpectateRequestPayload`) | Checks the session exists, spectating is on, and they are not already a participant |
| "I am holding right-click to block" (`BlockingPayload`) | Checks legacy mode, sword in main hand, alive, not a spectator |
| "Save this loadout" (`LoadoutSubmitPayload`) | Validates item by item, drops illegal entries, reports back |

No packet carries "I dealt this much damage" or "my health is this". Combat numbers, knockback and
armour are recomputed in `LivingEntityMixin` / `PlayerEntityMixin` using the 1.8 formulas; the client
only displays the result.

## 3. Session state machine

```
DRAFT ──send proposal──> NEGOTIATING ──all agree──> FROZEN
                          │                          │
                          │                          ├─> ROUND_READY ─> ROUND_ACTIVE ─> ROUND_SETTLED
                          │                          │                        │              │
                          └──reject/cancel──┐        │                        └──────────────┘
                                            ↓        ↓                             (more rounds)
                                         VOIDED  RESTORING ─> ARCHIVED
```

| Stage | Meaning | Snapshotted |
| --- | --- | --- |
| `DRAFT` | Challenger is editing, not sent yet | No |
| `NEGOTIATING` | Sent, awaiting a reply | No |
| `FROZEN` | Rules and loadouts locked, teleporting and snapshotting | Yes |
| `ROUND_READY` | Preparation period, wall still standing | Yes |
| `ROUND_ACTIVE` | Round in progress | Yes |
| `ROUND_SETTLED` | Round resolved, awaiting the next or the end | Yes |
| `RESTORING` | Restoring player state field by field | Yes |
| `ARCHIVED` / `VOIDED` | Terminal: archived normally / abandoned before starting | — |

`isPreFreeze()` (`DRAFT` / `NEGOTIATING`) is the dividing line: cancelling before it requires no
restore, and every terminal state after it must go through one. **"Has snapshots" is equivalent to
"must restore"**, and that invariant is what crash reconciliation is built on.

## 4. Snapshots and restore

Before entering, each participant is snapshotted in full: position and rotation, inventory (36 slots
plus offhand and armour), health, hunger and saturation, status effects, experience level and
progress, game mode, attribute modifiers.

- The snapshot lives in the in-memory `DuelSession`, and the pre-entry position is also written to
  `<world>/pvpocket/homes.json`.
- Restore runs through `PlayerSnapshot.restore`, writing each field back and resyncing the client.
- After a crash or restart `homes.json` is all that is left: a player who logs in inside the duel
  dimension is sent straight back to the recorded position. **Snapshots do not survive a restart**,
  deliberately — restoring an inventory across a restart means merging with whatever the player
  picked up in the meantime, and that costs far more than it is worth.

Game mode is restored through `setGameMode(GameMode, GameMode)` (the playerdata path), not
`changeGameMode`, so the sandbox's mode lock does not interfere.

## 5. Dimension and cells

Every map sits in its own cell of the `pvpocket:arena` dimension; `ArenaManager.CELLS` defines 16
cells of 160 blocks square, 256 apart from centre to centre, which leaves 96 blocks of empty space
between neighbours. Choosing a map is a teleport to fixed coordinates.

- **Lease**: a duel claims one cell based on `e.template` and records the session id. A map can be
  leased as many times as it owns cells — its *home* is its index in `ArenaMaps.ALL`, extra cells
  (copies) are recorded in `pvpocket/arena.json`, handed out by `/pvpocket arena copy` and taken back
  by `/pvpocket arena remove`. A copy's cell number is an address and does not move across restarts.
  A map can be taken down to zero copies — giving up its *home* is recorded as `noHome` in that file —
  and `allocate` then has no cell to give it.
- **Terrain protection**: blocks changed during the round are recorded in `ArenaRegion`'s change set
  and restored in batches at the end.
- **Rebuild**: `/pvpocket arena reset` aborts every session, ejects everyone, clears the dimension and
  then **re-stamps one map per tick**. Five maps is roughly 150,000 blocks; doing it in one go stops
  the main thread for 4.5 seconds, trips the watchdog and drops clients.

The dimension is void, with a barrier buffer ring around each map. Boundaries are drawn client-side
as a light curtain; the server only judges position.

## 6. Networking

All traffic uses Fabric custom payloads, registered in `PvPocketNetworking`.

| Dir | Payload | Purpose |
| --- | --- | --- |
| C→S | `BlockingPayload` | Block held / released |
| C→S | `RulesSubmitPayload` | Propose / accept / reject / dismiss |
| C→S | `LoadoutRequestPayload` | Ask for the loadout editor |
| C→S | `LoadoutSubmitPayload` | Submit a loadout |
| C→S | `SpectateRequestPayload` | List / join / leave / cycle / detach |
| C→S | `PresetSharePayload` | Publish / fetch a share code |
| C→S | `HomeRequestPayload` | "Give me a home-panel snapshot" |
| C→S | `HomeActionPayload` | Start a duel / from a rule preset / from a loadout preset; at OP 3 also copy or take back a plot, rebuild the arena and toggle the stray guard |
| S→C | `RulesOpenPayload` / `RulesClosePayload` | Rule panel open / close |
| S→C | `LoadoutOpenPayload` | Loadout editor open |
| S→C | `MatchListPayload` | Match list |
| S→C | `MatchHudPayload` | Scoreboard |
| S→C | `ArenaBoundsPayload` | Plot outline |
| S→C | `SpectateStatePayload` / `PossessPayload` | Spectator bar / possessed hotbar |
| S→C | `CombatStatePayload` | Combat mode and blocking mirror |
| S→C | `PresetShareResultPayload` | Share-code outcome: a status code plus the preset body |
| S→C | `HomeStatePayload` | Home snapshot: career counters, history, live matches, state flags; operators also get the plot table |
| S→C | `SeriesResultPayload` | End-of-series card: score, round-by-round outcomes, both rosters, length and reason |

**The home panel is the only screen the client asks for**; every other screen is pushed by the
server. It therefore carries its own request/response pair: `HomeRequestPayload` (empty) →
`HomeStatePayload`. The snapshot is built on demand rather than pushed, because nothing else needs
this data — pushing it would burn bandwidth for nobody. Counters, history and live matches all
travel as flat `|`-separated strings and are parsed on the client, for the same reason as share
codes: the server has no language and should not decide what the panel looks like.

**"Does this server speak the home protocol" must be asked of the outbound channel.** `canSend`
answers "may I send on this channel", and `HomeStatePayload` only ever travels server-to-client, so
asking about it returns false everywhere — which shows up as a permanently greyed-out button on a
server that answered the request in the same tick. `HomeRequestPayload` is the right question.

Every inbound payload is handled inside `server.execute(...)` so it runs on the main thread. Home
requests are additionally throttled (one per player per second, table capped at 512 entries),
because it is the one payload a client can send without limit.

`PacketCodec.tuple` tops out at six components; beyond that fields move into a nested record (which
is where `RulesOpenPayload.Details` comes from). **Longer lists go in as flat strings**:
`SeriesResultPayload` has three fields, each holding `|`- and newline-separated rows — a round has
three attributes and a side has a variable-length roster, so a list-of-records encoding would need two
levels of nesting plus a length prefix, while each of the three strings is parsed in exactly one place.

**The result card is one per participant, not one per series.** `SeriesResultPayload` carries a
`mySide`, because the perspective is the whole message: which score row is highlighted, which rounds
count as wins, and whether the headline says victory or encouragement all derive from it. Sending one
shared payload and letting each client work out which side it is on would need the reader's name or
uuid in the payload too — and the server already knows the answer. Nothing in it is authoritative:
the record of the match is the `MatchRecord` written just before it, so losing every one of these
payloads loses a card and no state.

**The switch lives on the client, not the server.** "Result screen" is a per-installation preference
in `ClientPrefs`; the server sends the card to every participant and each client decides whether to
draw it. Making the server guess at a preference it does not own would couple "the server supports
this" to "the player wants this", and every future toggle would become a protocol change.

**A share code travels as a status code, never as a sentence.** `PresetShareResultPayload.status` is
one of `PUBLISHED` / `FETCHED` / `UNKNOWN_CODE` / `REJECTED` / `FULL`, and the client assembles the
wording in its own language — a server has no language, and a Chinese "this code has expired" sent
to an English client is mojibake. `PresetShareRegistry` is memory-only and main-thread-only: five
minute TTL, swept every 100 ticks, re-checked on read, no locking and no persistence. The code is a
courier; the preset file is the library.

## 7. Sandbox rules

Inside the duel dimension:

| Restriction | Implementation |
| --- | --- |
| Chat may only run `/pvpocket` commands | `CommandManagerMixin` at the HEAD of `CommandManager.execute` |
| Nobody may change their own game mode | Command layer plus `ServerPlayerInteractionManager.changeGameMode` |

**The hook must sit on `CommandManager.execute`, not `executeWithPrefix`.** Player chat commands are
parsed by `ServerPlayNetworkHandler.onChatMessage` and dispatched straight to `execute`;
`executeWithPrefix` is only used by the console and RCON. Hooking the wrong one produces "operators
are blocked, players are not", while startup, compilation and `/pvpocket` all look perfectly fine.

Allow-list, not deny-list: only the root literal `pvpocket` (with any namespace stripped) is allowed.
A deny-list has to maintain `/gamemode`, `/tp`, `/give`, `/fill`… and every one you miss is a hole,
as is every command another mod adds.

**Operators need a way through**: mode changes from the console, RCON or a command block are always
allowed, otherwise a player stuck in the arena cannot be rescued. This is a `Sandbox.authorised`
counter — `CommandManagerMixin` lifts the restriction for the duration of a non-player command, and
the mod's own switches go through `Sandbox.changeGameMode` with a `try/finally` pair.

## 8. Storage

| File | Location | Contents |
| --- | --- | --- |
| `journal/<session>.log` | `<world>/pvpocket/` | Per-session event stream for reconciliation and diagnosis |
| `homes.json` | same | Pre-entry positions, disconnect recovery |
| `guard.json` | same | Per-player exemptions from the stray-player guard |
| `stats/stats.json` | same | Career counters per player, rewritten whole |
| `history/<uuid>.jsonl` | same | One line per finished series per player, appended |
| `presets.json` | `config/pvpocket/` (**client**) | The player's loadout and rule presets |
| `client.json` | same | Theme and motion preference (**not** key bindings — those live in vanilla `options.txt`) |

**Two stats files, because there are two write patterns.** Counters change on every hit, so they are
held in memory and flushed at series end, on disconnect and on shutdown — a write per hit is a disk
write per half second of combat. History is append-only, one file per player, read straight through
in one sequential pass; a shared file would rewrite everybody's records on every append.

The 200-series cap is enforced **on read**: the file may grow to 250 rows before it is compacted, so
an append stays O(1), but `history()` hands back only the newest 200 — the promise is 200, the file
layout is an implementation detail. Compaction keeps the newest 200 rows and drops the oldest.

Before appending, the file is checked for a trailing newline. A crash can leave a partial row with no
terminator; without the separator the next record is glued onto it and *both* become unreadable.

Where the numbers come from:

| Value | Hook | Why |
| --- | --- | --- |
| Damage | HEAD of `LivingEntity#applyDamage` | Already past armour, before absorption — "damage actually dealt". Both sides must also be in the same `ROUND_ACTIVE` and not on the same side |
| Kills | `ServerPlayerEntity#getAttacker()` at death | Falling, self-damage and environment kills count for nobody |
| Rounds | A `RoundLog` appended in `settle` | The winner is stored as a side index and converted to "did my side win this one" when it is written into `MatchRecord` |

`MatchRecord` is **one per player**, not one per series: every participant gets their own record with
their own side's score and their own list of opponents. A shared record would force every reader to
be told which side it was on.

`presets.json` is the one file that does not live in the world save, and the only one the client
reads and writes. A loadout or a rule set is something a player carries between servers; keeping it
in the save would mean re-creating it on every server they join. The server never reads it — using a
preset goes through an ordinary `RulesSubmitPayload` / `LoadoutSubmitPayload`, so nothing downstream
needs to know presets exist.

Writes go through `PvPocketStorage.writeAtomically` (temp file then rename) so a power cut cannot
leave half a JSON file. A failed read of `guard.json` is treated as "no exemptions": better to eject
once too often than once too rarely.

## 9. Known trade-offs

- **Snapshots do not survive a restart** (§4). After a restart a player returns to their pre-entry
  position but with whatever the save says they carry.
- **Teams are an explicit roster, but the shape is still hard.** A side holds at most five players,
  a match at most four sides and twenty players — set jointly by `ArenaRegion.PAD_COUNT`,
  `SessionManager.MAX_TEAMS` / `MAX_PARTICIPANTS` and `b.teamSize` / `b.maxPlayers`; anything larger
  is refused by `submitRoster`. The sides are drawn by hand on the roster page and arrive as
  `RosterBoard.encode()` → `"uuid,uuid;uuid"` on `rules_submit`; `submitRoster` compacts the empty
  cards away and writes `team` / `member` onto `Participant` — **teams are no longer derived**, so
  there is no round-robin and no "changing the team count re-deals the roster in place". The cost is
  that the roster is the only place the sides exist: `b.teamCount` has been deleted, and the number
  of sides in a match is the number of non-empty cards.
- **A refusal is a terminal mark, not a cancellation.** Once `Participant.rejected` is set that
  player is out of scoring, spawning and the HUD, but the session carries on; `settleNegotiation`
  only voids the match when everybody has answered *and* fewer than two non-empty sides remain.
- **Spectators cannot see the teams.** The spectator list names who is in the match but not who is
  on whose side; the grouping is only visible on the HUD.
- **There is no round timer.** `a.roundLimit` / `a.timeoutVerdict` are unconnected, so a round in
  which neither side acts never ends by itself.
- **Weather is dimension-wide**: with several duels running, the last one to start sets it for
  everyone.
