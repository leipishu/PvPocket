# PvPocket — Overview

> Specification · English. Chinese version: [`../zh/01-overview.md`](../zh/01-overview.md)

## What this is

PvPocket is a Minecraft Fabric 1.21.1 mod that gives a survival server **a duel you can start on the
spot**: the players agree on terms, drop into a dedicated dimension, fight under faithfully
reproduced vanilla mechanics, and leave with nothing changed.

It is not a server's core gameplay and not a matchmaking lobby. The pitch is one sentence:
**two people want a fight, they get one in thirty seconds, and nothing is left behind.**

That sentence is the default: two players, one life, best of three. There is one layer above it —
a single duel holds up to 4 sides of up to 5 players each, 20 people total. `/pvpocket duel` opens
the panel, and picking the sides is a matter of putting names on team cards. Multiplayer is not a
separate game mode; it is the same rule set with a different roster.

## Four decisions

### 1. Terms first, then the fight

The challenger opens a rule panel, sets the terms and sends them to everyone else. Each of them can
accept, edit and re-send, or decline. What everyone agrees to is a **versioned snapshot of 39
rules**, not a list of toggles. Any edit bumps the revision, and agreement to the old one stops
counting.

The cost is one extra exchange before every duel. The benefit is that nobody gets dragged into a
best-of-five with friendly fire on. In a four-way match this matters more than anywhere else: if
three players have already accepted and the fourth changes a loadout, all three acceptances are
void at once — the fourth player does not get outvoted into a fight on someone else's terms.

### 2. Server authoritative, client expresses intent only

The client never decides state. Every packet is an intent: `RulesSubmitPayload` says "I want to
accept", and the server re-checks the session stage, the loadout fingerprint and the map lease
before anything happens. Combat numbers, knockback and armour are all resolved server-side.

This reduces "edit the client, get an advantage" to the input itself.

### 3. When it ends, the world goes back

Before entering, every participant is snapshotted in full — position, inventory, health, hunger,
status effects, experience, game mode — and every field is restored afterwards. Arenas are leased
per cell, block changes made during the round are recorded, and the terrain is restored in batches
at the end.

Any failure in that restore path means a player permanently loses items, so snapshots and restore
use their own storage under `<world>/pvpocket/` and are reconciled at startup.

### 4. One dimension, many cells

Every map sits side by side in its own cell of the duel dimension, 16 blocks apart. Choosing a map
is a teleport to fixed coordinates, not a paste at the origin. That is what makes several duels
genuinely simultaneous and mutually invisible.

## Scope

**In:** negotiated rules, 1.8 and modern combat, multi-round series, per-player loadout editor,
spectating with camera binding, disconnect recovery, terrain protection and restore, stray-player
protection, arena rebuild.

**Out:** matchmaking, persistent rankings, cross-server, spectator chat, replays.

## Reading order

| Document | Contents |
| --- | --- |
| [02-rules](02-rules.md) | The 38 rules, in full, with meanings |
| [03-architecture](03-architecture.md) | Server architecture, session state machine, dimension and cells |
| [04-visual](04-visual.md) | UI visual and motion specification |
| [05-usage](05-usage.md) | Deployment, commands, player flow, operations |
| [06-maps](06-maps.md) | Map sources, conversion pipeline, licensing |

Building, two-client testing, repository layout and commit conventions are in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) at the repository root; hard rules and known traps for
coding agents are in [`AGENTS.md`](../../AGENTS.md).

History is in [`CHANGELOG.md`](../../CHANGELOG.md) at the repository root. Process documents are
archived under [`../archive/`](../archive/) and **are not specifications**.

## Licence

Code licence: see the repository root. The built-in map artwork is licensed **differently**: four
maps are adapted from CC BY-SA 4.0 works by zzuf and stay under that licence. See
[`../../THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md). Shipping the map files inside the jar
is redistribution and carries that notice with it.
