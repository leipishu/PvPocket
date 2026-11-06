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

- **Series** — `a.series` · choice · default 1 round · 1 round / Best of 3 / Best of 5 / Best of 7 / Best of 9 / Best of 11 / Best of 13 / Best of 15. The first side to win (rounds ÷ 2 + 1) takes the series. Items and terrain are restored after every round.
- **Round time limit** — `a.roundLimit` · choice · default No limit · No limit / 1 minute / 3 minutes / 5 minutes / 10 minutes / 15 minutes. After this the round is settled by the timeout rule. Unlimited when off.
- **Timeout verdict** — `a.timeoutVerdict` · choice · default Higher health wins · Higher health wins / Draw / Both lose. How a round is decided when the clock runs out.
- **Preparation time** — `a.countdown` · int 0–30 · default 5 s. Time between being teleported in and the first blow, counted down in chat one second at a time. Nobody can deal or take damage during it, and a solid white wall stands around the plot until the round goes live.
- **Intermission** — `a.intermission` · int 1–10 · default 3 s. The break between two rounds of a series.
- **Win conditions** — `a.winConditions` · multi · Kill / Out of bounds / Surrender / Timeout. Which events can end a round. Keep at least one.

**Players** — Teams, sizes, friendly fire

- **Teams** — `b.teamCount` · int 1–4 · default 2 teams. How many sides fight. 2 is the classic 1v1 / 2v2.
- **Players per team** — `b.teamSize` · int 1–4 · default 1 players. Players on each side. Total = teams × players per team.
- **Player cap** — `b.maxPlayers` · int 2–16 · default 4 players. The duel cannot start above this many players.
- **Friendly fire** — `b.friendlyFire` · toggle · default Off. When on, teammates can damage each other.
- **Allow spectating** — `b.spectator` · toggle · default On. When on, anyone not in the duel can see it in /pvpocket list and press Watch to enter. Spectating uses spectator mode with the camera bound to a participant, V cycles the view, and leaving puts you back where you were.

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

- **Allow placing blocks** — `f.allowPlace` · toggle · default Off. When on, whitelisted blocks can be placed inside the arena.
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

**All 39 rules are configurable, all are written into the session, all reach the journal — but only
16 of them currently affect a duel.** This is the section that matters. Do not look at the panel and
assume the whole set is in effect.

Method: whether the rule id has a reader outside `RuleCatalog`. As of this revision (`2ed3f3f`):

### In effect (16)

| Group | Rules |
| --- | --- |
| A | `a.series`, `a.countdown`, `a.intermission` |
| B | `b.spectator` |
| C | `c.mode`, `c.maxHealth` |
| E | `e.template`, `e.sizeX`, `e.sizeY`, `e.sizeZ`, `e.boundary`, `e.timeOfDay`, `e.weather` |
| F | `f.protectTerrain`, `f.allowBreakOwn` |
| G | `g.allowSurrender` |

### Summary text only (2)

`b.teamCount`, `b.teamSize` — their only reader is `DuelRules.summaryText()`, i.e. the "2 players"
part of the chat line. **Every duel is 1v1**: session creation always inserts exactly two
participants (challenger and target), and no team model exists. Choosing "2 teams × 2 players" pulls
nobody into the arena; it only makes the summary claim four players.

### Recorded only (23)

`a.roundLimit`, `a.timeoutVerdict`, `a.winConditions`, `b.friendlyFire`, `b.maxPlayers`,
`c.modeConflict`, `c.naturalRegen`, `c.hunger`, `c.fallDamage`, `c.knockbackResistance`,
`d.loadoutScope`, `d.allowSwapBetweenRounds`, `d.allowPickup`, `d.allowCrafting`,
`d.allowConsumables`, `e.hazard`, `f.allowPlace`, `f.allowExplosives`, `f.blockFilter`,
`g.enderPearl`, `g.chatScope`, `g.showOpponentHealth`

These are written into `DuelSession.rules` and the session journal; no code reads them. They can be
changed in the panel, appear in the rule summary, and are frozen by consent — and they change nothing.

Three of them mislead more than the rest and are worth calling out:

- **`a.roundLimit` + `a.timeoutVerdict`** — the panel promises "settled by the timeout verdict once
  exceeded", but there is no timer. If neither player acts, the round runs forever; only surrender
  or a disconnect ends it.
- **`a.winConditions`** — you can untick "kill", and a kill still ends the round.
- **`f.allowPlace`** — off by default, yet **blocks can be placed with it off**: placement is never
  vetoed, only breaking is (`PlayerBlockBreakEvents.BEFORE`). Every change is rolled back at settle,
  so the next duel is unaffected — the switch itself just does not hold.

**Why keep them:** deleting them would decouple the summary from the protocol, and the enforcement
work is spread across several slices (team model, block change set, timers, chat filtering). Keeping
a declared-but-unenforced state converges faster than removing and re-adding. **Any public claim
about features must follow this section, not the panel.**

## 4. Series arithmetic

`a.series` holds the **number of rounds** (1 / 3 / 5 / … / 15); wins needed is derived:

```
wins = rounds ÷ 2 + 1
```

| Rounds | Wins | Common name |
| --- | --- | --- |
| 1 | 1 | BO1 |
| 3 | 2 | BO3 |
| 5 | 3 | BO5 |
| 7 | 4 | BO7 |
| 15 | 8 | BO15 |

The ceiling is 15 rounds / 8 wins: beyond that a duel runs over an hour, and this mod is "a match
you can have any time on a survival server", not a league.

## 5. Negotiation and freeze

1. The challenger creates a session (`DRAFT`); rules take defaults, and `c.mode` is pre-selected by
   `/pvpocket duel … legacy`.
2. "Send proposal" delivers the current rules, bumps the revision, and moves to `NEGOTIATING`.
3. "Accept and start" means agreeing to the rules **and to both loadouts**. The combined loadout
   fingerprint is recorded; if either side changes their loadout afterwards the agreement lapses and
   the panel reopens.
4. On acceptance the rules freeze (`FROZEN`) and the teleport/snapshot phase begins.

Every snapshot carries `rulesRevision`, shown in the panel's top right. Each "send proposal" bumps
it and invalidates earlier agreement — the only defence against "I agreed to the previous version".
