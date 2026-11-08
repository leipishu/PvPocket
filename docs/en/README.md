<p align="center">  
  <img src="../assets/logo.png" alt="PvPocket" width="480">  
</p>

<p align="center">  
  <a href="../../README.md">中文</a> | <strong>English</strong>  
</p>

<h1 align="center">PvPocket</h1>

<p align="center">An isolated duel pocket inside a survival SMP: a few players fight a best-of-N in a dedicated arena under terms they negotiated, and leave with nothing changed — inventory, experience, health and position all restored.</p>

<p align="center"><strong>Two people want a fight, it starts in thirty seconds, and it leaves no trace.</strong></p>

## What it does

- **Negotiated rules**: seven groups, 40 rule items (39 configurable plus one read-only conflict  
  note). Both sides walk through the same panel before the first round, and nothing starts until  
  everyone agrees. Rules carry a revision number, so any edit invalidates the earlier "yes" — there  
  is no "I thought it was set to that".
- **Two combat modes**: modern (1.9+ attack cooldown, shield blocking) and Legacy 1.8 (no cooldown,  
  sword blocking, 1.8 knockback and damage), switched per match.
- **Best-of-N**: round limit, per-round time limit, timeout verdict; every round resets completely  
  while the score carries over.
- **Custom loadouts**: weapons, armour and consumables configured item by item. Enchantments follow  
  the item type — a trident picks one of Loyalty / Riptide / neither, an elytra has a firework flight  
  duration, a bow has Infinity and an arrow count, an end crystal comes with obsidian, TNT with flint  
  and steel. A loadout can be saved as a preset, and a preset can be published as a share code valid  
  for five minutes; whoever imports it keeps it locally.
- **Spectating**: free camera, or lock the camera to a fighter with their health and gear on the  
  spectator bar.
- **No trace**: inventory, experience, health, hunger, position, game mode and status effects all  
  restored, dropped items included. The duel dimension is separate, so the survival world is never  
  touched.
- **Safety nets**: disconnect forfeit, stray-player protection, terrain protection and restore,  
  arena rebuild.

## What a duel looks like

1. Player A runs `/pvpocket duel`; the rule panel opens on the players page with two team cards.
2. Put people on the cards and drag the sliders; player B sees the same draft on their own panel.
3. Both sides build a loadout, optionally from a preset.
4. Both press "Accept & start" — rules and loadouts lock together, a countdown runs, and everyone is  
   teleported in.
5. After the agreed number of rounds a winner is declared; a few seconds later everyone is back  
   where they started, restored.

If the panel is closed with Esc, `/pvpocket review` reopens it.

## What the rules cover

| Group             | Scope                           | Examples                                                                           |
| ----------------- | ------------------------------- | ---------------------------------------------------------------------------------- |
| A Match structure | Rounds, victory, time           | Rounds to win, per-round limit, timeout verdict                                    |
| B Participants    | Players, teams, friendly fire   | Team size, player cap, friendly-fire toggle                                        |
| C Combat          | Combat mode and survival stats  | Modern / 1.8, max health, natural regen, hunger, fall damage, knockback resistance |
| D Loadout         | Entry items and pickup          | Swapping between rounds, pickup, crafting, consumables                             |
| E Arena           | Map, bounds, environment        | Map, size, boundary rule, time of day, weather, hazards                            |
| F Blocks          | Placement, breaking, explosions | Placement, terrain protection, block filter, explosives                            |
| G Other           | Pearls, surrender, display      | Ender pearls, surrender, opponent health, chat scope                               |

Full meanings, and what each one actually changes, are in [`02-rules.md`](02-rules.md).

## Requirements

| Item          | Version         |
| ------------- | --------------- |
| Minecraft     | 1.21.1          |
| Fabric Loader | 0.16.9 or newer |
| Fabric API    | 0.116.17+1.21.1 |
| Java          | 21              |

**Server and client must run the same build of the mod.** The protocol is custom payloads; a version  
mismatch shows up as a panel that never appears or rules that never apply, not as a clear error.

## Installation

1. Drop `pvpocket-*.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) into the server's  
   `mods/`.
2. Do the same on every client that will fight — without the client there is no rule panel, no  
   loadout editor and no scoreboard.
3. First start creates `pvpocket/` inside the world save (`journal`, `homes.json`, `guard.json`). The  
   five built-in maps are pasted tick by tick, which takes about ten seconds; until it finishes  
   `/pvpocket arena` reports that the cells are not laid out yet and no match can start.

Deployment details, permissions, operations and troubleshooting are in [`05-usage.md`](05-usage.md).

## Documentation

The specification lives in [`docs/`](../) — Chinese under [`zh/`](../zh/), English under `en/`,  
parallel and one-to-one.

| Want to know                                               | Read                              |
| ---------------------------------------------------------- | --------------------------------- |
| What this is, the four core decisions, scope               | [`01 Overview`](01-overview.md)   |
| All 40 rules in full                                       | [`02 Rules`](02-rules.md)         |
| Deployment, permissions, player flow, commands, operations | [`05 Usage`](05-usage.md)         |
| What changed in each version                               | [`CHANGELOG`](../../CHANGELOG.md) |
| How to change code, add a map, report a problem            | [`CONTRIBUTING`](CONTRIBUTING.md) |

Implementation details are in [`03 Architecture`](03-architecture.md), [`04 Visual`](04-visual.md)  
and [`06 Maps`](06-maps.md), aimed at people changing code.

## Licence

The mod's code is distributed under the **GNU Lesser General Public License v3.0**; the full text is  
in [`LICENSE`](../../LICENSE). LGPL-3.0 incorporates the terms of GPL-3.0, a copy of which is in  
[`LICENSE-GPL-3.0.txt`](../../LICENSE-GPL-3.0.txt). `fabric.mod.json` carries the SPDX identifier  
`LGPL-3.0-only`.

**The release jar ships a copy.** `LICENSE`, `LICENSE-GPL-3.0.txt` and `THIRD-PARTY-NOTICES.md` are
packed into the jar root by `processResources` — LGPL-3.0 §4 requires that whoever receives the object
code also receives the licence, and what gets distributed is the jar, not this repository. Unzip the
jar and they are there; licence scanners find them there too.

**The built-in maps are not covered by that.** Four of the five come from  
[zzuf-maps](https://github.com/zzufx/zzuf-maps) and stay under **CC BY-SA 4.0**; the colosseum is  
generated by `scripts/colosseum.py` and carries no third-party obligation. Attribution and change  
statements are in [`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md).
