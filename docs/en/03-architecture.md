# Architecture

> Specification · English. Chinese version: [`../zh/03-architecture.md`](../zh/03-architecture.md)

## 1. Layers

Loom's `splitEnvironmentSourceSets()` splits the build into two source sets:

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
cells, 16 blocks apart. Choosing a map is a teleport to fixed coordinates.

- **Lease**: a duel claims one cell based on `e.template` and records the session id. A map can only
  be leased once at a time.
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
| S→C | `RulesOpenPayload` / `RulesClosePayload` | Rule panel open / close |
| S→C | `LoadoutOpenPayload` | Loadout editor open |
| S→C | `MatchListPayload` | Match list |
| S→C | `MatchHudPayload` | Scoreboard |
| S→C | `ArenaBoundsPayload` | Plot outline |
| S→C | `SpectateStatePayload` / `PossessPayload` | Spectator bar / possessed hotbar |
| S→C | `CombatStatePayload` | Combat mode and blocking mirror |

`PacketCodec.tuple` tops out at six components; beyond that fields move into a nested record (which
is where `RulesOpenPayload.Details` comes from).

Every inbound payload is handled inside `server.execute(...)` so it runs on the main thread.

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

Writes go through `PvPocketStorage.writeAtomically` (temp file then rename) so a power cut cannot
leave half a JSON file. A failed read of `guard.json` is treated as "no exemptions": better to eject
once too often than once too rarely.

## 9. Known trade-offs

- **Snapshots do not survive a restart** (§4). After a restart a player returns to their pre-entry
  position but with whatever the save says they carry.
- **There is no team model.** `b.teamCount` / `b.teamSize` appear only in the rule summary line;
  session creation always inserts exactly two participants (challenger and target), and while
  `Participant.team` exists, no third player can ever join. Choosing "2 teams × 2 players" in the
  panel pulls nobody into the arena — it just makes the summary claim four players. Friendly fire,
  team colouring and per-team scoring all hang off the same missing piece. This is the largest
  "panel has it, code does not" gap in the project.
- **There is no round timer.** `a.roundLimit` / `a.timeoutVerdict` are unconnected, so a round in
  which neither side acts never ends by itself.
- **Weather is dimension-wide**: with several duels running, the last one to start sets it for
  everyone.
