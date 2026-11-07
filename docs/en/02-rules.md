# Rules

> Specification · English. Chinese version: [`../zh/02-rules.md`](../zh/02-rules.md)

## 1. The model

A duel's rules are an **ordered key/value snapshot**: 39 configurable rules plus one read-only
informational entry, in seven groups.

| Group | Subject | Count |
| --- | --- | --- |
| A | Match (rounds, victory, timing) | 6 |
| B | Players (sizes, teams, friendly fire, spectating) | 5 |
| C | Combat (mode, survival stats) | 7 |
| D | Loadout (items, pickups, crafting) | 5 |
| E | Arena (size, map, boundary, environment) | 7 |
| F | Blocks (placing, breaking, explosives) | 5 |
| G | Other (pearls, surrender, display, chat) | 4 |

Rules are declared in `RuleCatalog`; their text lives in the language files
(`pvpocket.rule.<id>.label` / `.hint` / `.c<N>` / `.unit`). **The rule id is the wire format** —
renaming one changes the protocol. New rules may only be appended; existing ids never move.

## 2. Full list

Format: `rule id` · type · default · choices. Text and choices come from the language files and match
what the panel shows.

**Match** — Rounds, victory, timing

- **Round cap** — `a.maxRounds` · int 1–15 · default 3 rounds. The most rounds a match can run; reaching it ends the match.
- **Rounds to win** — `a.winsNeeded` · int 1–15 · default 2 rounds. The first side to win this many rounds takes the match. The panel keeps it at or below the round cap, so "4 wins out of 3" is not a state the panel can sit in.
  Two numbers rather than one "best of N" choice because they answer two different questions — "how long until we stop" and "how much is enough". Collapsed into one, an even round count cannot be expressed and **a draw is structurally impossible**: wins are derived from the round count, so somebody always gets there first. Split, a match that runs out of rounds with nobody at the line goes to the highest score, and a tie is a draw.
- **Round time limit** — `a.roundLimit` · choice · default No limit · No limit / 1 minute / 3 minutes / 5 minutes / 10 minutes / 15 minutes. After this the round is settled by the timeout rule. Unlimited when off.
- **Timeout verdict** — `a.timeoutVerdict` · choice · default Higher health wins · Higher health wins / Draw / Both lose. How a round is decided when the clock runs out.
- **Preparation time** — `a.countdown` · int 0–30 · default 5 s. Time between being teleported in and the first blow, counted down in chat one second at a time. Nobody can deal or take damage during it, and a solid white wall stands around the plot until the round goes live.
- **Intermission** — `a.intermission` · int 1–10 · default 3 s. The break between two rounds of a series.
- **Win conditions** — `a.winConditions` · multi · Kill / Out of bounds / Surrender / Timeout. Which events can end a round. "Kill" is locked on; keep at least one of the other three.

**Players** — Teams, sizes, friendly fire

- **Players per team** — `b.teamSize` · int 1–5 · default 5 players. The **cap** on each side, and
  the number of empty slots a team card shows. Above it the match refuses to start.
- **Player cap** — `b.maxPlayers` · int 2–20 · default 20 players. The duel cannot start above this many players. The hard ceiling of 20 is enforced by `SessionManager.MAX_PARTICIPANTS`; the smaller of the two wins.
- **Friendly fire** — `b.friendlyFire` · toggle · default Off. When on, teammates can damage each other.
- **Allow spectating** — `b.spectator` · toggle · default On. When on, anyone not in the duel can see it in /pvpocket list and press Watch to enter. Spectating uses spectator mode with the camera bound to a participant, V cycles the view, and leaving puts you back where you were.

> **How many sides is no longer a rule.** It is however many non-empty cards the proposer drew on the
> roster page — two to four; an empty card simply does not play. The old `b.teamCount` could only say
> "split into N sides" and then had the server deal people round-robin in invite order; that could
> not express "these two together, those two together", and changing the number re-dealt a roster
> that had already been invited. The sides are drawn explicitly now, so the rules do not need the
> number. Sides do not have to be the same size.

**Combat** — Combat mode and survival stats

- **Combat mode** — `c.mode` · choice · default Modern · Modern / Legacy 1.8. Legacy mode removes the attack cooldown and applies 1.8 rules to blocking, knockback and armour.
- **Mode conflict** — `c.modeConflict` · fixed. The combat mode needs everyone's consent; the side that proposes cannot decide it alone.
- **Max health** — `c.maxHealth` · int 1–40 · default 20 HP. Health cap for everyone once inside. Vanilla is 20.
- **Natural regeneration** — `c.naturalRegen` · toggle · default On. When off, only potions and golden apples restore health.
- **Hunger** — `c.hunger` · choice · default Normal · Normal / Off. When off, hunger never drops and starvation never hurts.
- **Fall damage** — `c.fallDamage` · toggle · default On. When off, landing from a height does no damage.
- **Knockback resistance** — `c.knockbackResistance` · percent · default 0%. Knockback resistance applied equally to every participant.

**Loadout** — What you bring in and pick up

- **Loadout scope** — `d.loadoutScope` · choice · default Per player · Per player / Per team. When set to per team, everyone on a side uses the same loadout.
- **Swap loadout between rounds** — `d.allowSwapBetweenRounds` · toggle · default Off. When on, the loadout can be changed before every round.
- **Pick up dropped items** — `d.allowPickup` · toggle · default Off. When off, items dropped in the arena cannot be picked up.
- **Allow crafting** — `d.allowCrafting` · toggle · default Off. When off, crafting tables and the inventory grid are disabled.
- **Allow consumables** — `d.allowConsumables` · toggle · default On. When off, potions, food and golden apples cannot be used.

**Arena** — Arena, boundary, hazards

- **Arena length (X)** — `e.sizeX` · int 16–160 · default 48 blocks. Plot size along X. Taken over by the map's own size once one is picked.
- **Arena width (Z)** — `e.sizeZ` · int 16–160 · default 48 blocks. Plot size along Z. Taken over by the map's own size once one is picked.
- **Arena height (Y)** — `e.sizeY` · int 8–96 · default 32 blocks. Plot size along Y, which is how much vertical room there is to build in. Taken over by the map's own size once one is picked.
- **Arena map** — `e.template` · choice · default Flat · Flat / Fun House / Sky Island / Dark Nebula / Deadwood / Colosseum. Flat is a floating platform whose size is the length and width below; the rest are built-in maps, and picking one makes the plot follow the map's bounding box. Every arena floats in the void, so leaving the plot means falling into it. A map can only host one duel at a time; maps in use are marked and skipped.
- **Boundary rule** — `e.boundary` · choice · default Push back · Push back / Countdown forfeit. Push back: stepping out puts you back at the nearest point inside. Forfeit: stepping out starts a five-second countdown, and still being outside when it ends loses the round. A translucent curtain marks all four edges (blue = push back, red = forfeit); you can walk through it, it grows with your height, and only the stretch near you is drawn. Maps with an inner pit (the colosseum) ring the fighting floor only, not the seating.
- **Time of day** — `e.timeOfDay` · choice · default Natural · Natural / Dawn / Noon / Dusk / Midnight. A fixed time freezes the clock and switches the daylight cycle off; Natural keeps the vanilla cycle.
- **Weather** — `e.weather` · choice · default Clear · Clear / Rain / Thunder. Set at the start of every round. Weather is a dimension-wide property, so with several duels running at once the last one to start wins.
- **Environmental hazards** — `e.hazard` · multi · None / Fall damage / Lava / Void. Extra sources of damage that apply inside the arena.

**Blocks** — Placing, breaking, explosives

- **Allow placing blocks** — `f.allowPlace` · toggle · default On. When off, no blocks can be placed inside the arena. Entries ticked under Block restrictions stay banned either way.
- **Terrain protection** — `f.protectTerrain` · toggle · default On. When on, the map's own blocks cannot be broken and only blocks placed this round can be removed. When off the plot can be dug through, and the terrain is still restored when the round ends.
- **Allow breaking your own blocks** — `f.allowBreakOwn` · toggle · default On. Applies only to blocks placed during this duel; whether the map itself can be broken is up to Terrain protection.
- **Block restrictions** — `f.blockFilter` · multi · Beds / Respawn anchors / TNT / Fluid buckets / End crystals. Selected entries are banned. Beds, respawn anchors, TNT and fluid buckets are banned by default.
- **Allow explosives** — `f.allowExplosives` · toggle · default Off. When off, TNT, creepers and end crystals produce no explosion.

**Other** — Pearls, surrender, display

- **Allow ender pearls** — `g.enderPearl` · toggle · default Off. When off, ender pearls cannot be thrown.
- **Allow surrender** — `g.allowSurrender` · toggle · default On. When off, a round can only end by a kill or by the clock.
- **Show opponent health** — `g.showOpponentHealth` · toggle · default On. Show the opponent's health at the top of the screen.
- **Chat during the duel** — `g.chatScope` · choice · default Server-wide · Server-wide / Team only / Muted. Restricts who can see chat during the match.

## 3. Enforcement status (important)

**All 40 rules exist, 39 of them are configurable** (`c.modeConflict` is `fixed` and only prints the
conflict note), **and 33 of those currently affect a duel.** This is the section that matters. Do
not look at the panel and assume the whole set is in effect.

Method: whether the rule id has a reader outside `RuleCatalog`. Measured on the current tree:

### In effect (33)

| Group | Rules |
| --- | --- |
| A | `a.maxRounds`, `a.winsNeeded`, `a.countdown`, `a.intermission`, `a.roundLimit`, `a.timeoutVerdict`, `a.winConditions` |
| B | `b.teamSize`, `b.maxPlayers`, `b.friendlyFire`, `b.spectator` |
| C | `c.mode`, `c.maxHealth`, `c.naturalRegen`, `c.hunger`, `c.fallDamage`, `c.knockbackResistance` |
| D | `d.loadoutScope`, `d.allowConsumables` |
| E | `e.template`, `e.sizeX`, `e.sizeY`, `e.sizeZ`, `e.boundary`, `e.timeOfDay`, `e.weather`, `e.hazard` |
| F | `f.allowPlace`, `f.protectTerrain`, `f.allowBreakOwn`, `f.blockFilter` |
| G | `g.enderPearl`, `g.allowSurrender` |

**Every rule is read through `Rulebook`** (`rules/Rulebook.java`): "does this rule apply" is a method
there, and callers name no ids and do no bit arithmetic. A new rule needs a getter in that file or it
is not wired up.

The first two in group A arrived with the series split, the four in group B were wired up together
with NvN, the eight in groups C/E with the damage-and-survival slice, the last three in group A with
the timing-and-outcome slice, and the four in D/F/G with the use-restriction slice. Where each one
is read:

- **`a.maxRounds` / `a.winsNeeded`** — `seriesDecided` looks at the round cap first and the win
  target second at the end of every round; with the cap reached and nobody at the line, `leaderTeam`
  takes the highest score and a tie sets `seriesEndReason = "DRAW"`. The pair is clamped against
  each other in `applyRulesToSession` (`winsNeeded = min(winsNeeded, maxRounds)`), and the panel does
  the same clamp, so an unreachable target never reaches the session.
- **`b.teamSize`** — the per-side cap, checked side by side in `submitRoster` before the invite goes
  out and again in `validateRoster` before the match starts; exceeding it refuses with the offending
  side named. It is also the slot count on the roster cards, so changing it changes the card height.
- **`b.maxPlayers`** — the player cap, again in `submitRoster` / `validateRoster`. The hard ceiling of
  20 is enforced by `SessionManager.MAX_PARTICIPANTS`; the smaller of the two wins.
- **`b.friendlyFire`** — when off, damage between team-mates is vetoed by
  `ServerLivingEntityEvents.ALLOW_DAMAGE` and leaves a `FRIENDLY_FIRE_BLOCKED` journal line. Only a
  direct attacker is blocked (melee, arrows, tridents, TNT), not the fire a team-mate set — the same
  line vanilla draws for its own team checks.
- **`d.loadoutScope`** — with "one set per side", every member is given the loadout of the side's
  first participant.
- **`c.fallDamage` / `e.hazard`** — both live in the same `ALLOW_DAMAGE` handler, keyed on the
  `DamageSource`. Fall damage needs both switches on (they are both off-switches, so either one
  turns it off); lava and void are one bit each in `e.hazard`, and its "none" chip turns all three
  off. Starvation damage is gated by `c.hunger`. This branch is **deliberately not journalled**:
  standing in lava would write a line every ten ticks and bury everything else, and the rules
  themselves are journalled when the session starts.
- **`c.naturalRegen`** — injected into `PlayerEntity#canFoodHeal`; returning false removes
  hunger-driven regeneration. Golden apples and potions go through `heal()` and are unaffected.
- **`c.hunger`** — injected into `PlayerEntity#addExhaustion` and cancelled, so the bar never drains.
  `HungerManager#update` is left alone on purpose: cancelling it would also freeze the regeneration
  timer and starvation damage, which belong to two other rules.
- **`c.knockbackResistance`** — the base value of `GENERIC_KNOCKBACK_RESISTANCE` is set from the
  percentage at the start of every round. No separate undo: the player snapshot restores the
  attribute map wholesale, the same path that restores max health.
- **`a.roundLimit`** — the round clock, driven by `roundStartedAt` (the same timestamp the boundary
  grace period uses). A timeout settles through `settle(..., "TIMEOUT" / "TIMEOUT_DRAW" /
  "TIMEOUT_DOUBLE")`, and it **broadcasts before settling** — otherwise a round just stops, with
  nothing to explain it. The clock only runs when `a.winConditions` has "timeout" ticked: a round
  that may not end on the clock should not have one ticking.
- **`a.timeoutVerdict`** — "highest health wins" compares the **sum of remaining health per side**
  (in 2v2 one player at ten hearts and one at one heart is not "the ten-heart side"), and a tie falls
  back to a draw. A draw and a double loss both award nothing; they differ only in the broadcast and
  in the journal `reason`.
- **`a.winConditions`** — the gate sits in four places: out-of-bounds (unticked, players are pulled
  back instead of eliminated), surrender (an AND with `g.allowSurrender` — both must allow it),
  timeout (unticked, the clock does not run) and kills. **"Kill" cannot be turned off**: a round
  cannot both not end on a wipe and end on a wipe; making that real would mean respawning the wiped
  side, which is a different mode. So that bit is locked in the panel (`RuleCatalog.lockedBits`) and
  `DuelRules.set` forces it back — a hand-rolled packet cannot change it either.
- **`f.allowPlace` / `f.blockFilter` / `d.allowConsumables` / `g.enderPearl`** — all four run through
  one use gate: the rule values are packed into two masks (`gate` and `bans`) at round start, written
  into `CombatState`, and the decision lives in `combat/UsePolicy`. There are two entry points —
  placement through `ItemStack#useOnBlock` (the `ItemStackMixin` injection) and everything else
  through `UseItemCallback`.

  Splitting them is not tidiness. A refused `useOnBlock` returns `PASS` rather than `FAIL`, so the
  caller goes on to the block's own `onUse` — "the dirt in your hand is refused" therefore does not
  also swallow opening a chest. Vetoing in `interactBlock` instead would. Buckets are the mirror
  case: `BucketItem#use` is what pours the water, so filling one has nothing to do with the
  placement switch and only the fluid-bucket restriction touches it.

  **Both masks are shipped to the client.** The client never derives rules, but if it does not know
  a placement will be refused it predicts one, puts the block in its own world, and the server never
  confirms it — a block only the placer can see. So `gate`/`bans` ride along in
  `CombatStatePayload`, both sides ask `UsePolicy` the same question, and get the same answer. The
  server remains the only arbiter; the client's copy just keeps the prediction honest.

  The message is server-only and throttled to one per 20 ticks: holding right-click re-attempts the
  placement every few ticks, and five lines a second is a wall, not an explanation. A
  `f.blockFilter` refusal names the entry that was tripped rather than saying "something is banned".

### Recorded only (6)

`d.allowSwapBetweenRounds`, `d.allowPickup`, `d.allowCrafting`, `f.allowExplosives`,
`g.chatScope`, `g.showOpponentHealth`

These are written into `DuelSession.rules` and the session journal; no code reads them. They can be
changed in the panel, appear in the rule summary, and are frozen by consent — and they change nothing.

There is also one non-setting: `c.modeConflict` is `fixed` and only prints the "1.8 mode conflicts with
some rules" note; it has no value.

**Why keep them:** deleting them would decouple the summary from the protocol, and the enforcement
work is spread across several slices (interaction gates, explosion checks, chat filtering, the HUD).
Keeping a declared-but-unenforced state converges faster than removing and re-adding. The backlog is
being closed slice by slice: **update the three counts in this section whenever a slice lands**, so the
document and the code cannot drift apart. **Any public claim about features must follow this
section, not the panel.**

## 4. Series arithmetic

A series is two independent numbers, no longer one "best of N" choice:

| Setting | `a.maxRounds` | `a.winsNeeded` | Outcome |
| --- | --- | --- | --- |
| Single round | 1 | 1 | over after one round |
| Best of three | 3 | 2 | first to 2 takes it; at 1-1 the third round decides |
| Best of seven | 7 | 4 | the classic BO7 |
| Three wins out of three | 3 | 3 | must sweep; at 2-1 the cap decides on score |
| Two rounds, one win | 2 | 1 | win once and go; may only run one round |

**Reaching the cap ends the match.** Once `a.maxRounds` rounds have been played it stops regardless
of the score: somebody at the line wins, nobody at the line and the highest score wins, a tie is a
draw (journaled as `SERIES_DRAWN`, announced as "a draw").

The ceiling is 15 rounds: beyond that a duel runs over an hour, and this mod is "a match you can have
any time on a survival server", not a league.

## 5. Negotiation and freeze

1. The challenger runs `/pvpocket duel`, which creates a session (`DRAFT`) with default rules and
   opens the panel on the **roster page** (tab 0).
2. The challenger draws the cards: two by default, four at most, each with `b.teamSize` slots, and a
   `+` in the corner opens the player picker. An empty card means that side does not play; at least
   two non-empty cards are needed to send.
3. "Send invite" sends the roster and the current rules together. The server checks the side count
   (2–4), the per-side cap, the player cap, duplicates, that everyone is online and idle, and that
   the sender is on the roster — **against the rules that were just sent**, not the defaults the
   draft opened with. Empty cards are compacted away, and the session moves to `NEGOTIATING`.
4. Everybody invited gets the panel at once, also opening on the roster page, where they can see
   which side they are on and who has not answered yet (grey = waiting, green = accepted, red =
   declined). The roster is read-only for reviewers.
5. "Accept and start" means agreeing to the rules **and to everybody's loadout**. The combined
   loadout fingerprint is recorded; if anyone changes their loadout afterwards the agreement lapses
   and the panel reopens.
6. **The match starts when everybody has answered and at least two non-empty sides remain.** A
   refusal is not a cancellation — it takes that player off the roster: invite four, one declines,
   and the remaining three still play as a 2v1. Only when fewer than two non-empty sides are left is
   the whole invite voided.
7. The rules freeze (`FROZEN`) and the teleport/snapshot phase begins.

Every snapshot carries `rulesRevision`, shown in the panel's top right. Each "send proposal" bumps
it and invalidates earlier agreement — the only defence against "I agreed to the previous version".
