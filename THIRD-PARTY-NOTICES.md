# Third-party notices

PvPocket's own source code is licensed under the GNU Lesser General Public License v3.0 — see
[`LICENSE`](LICENSE) and [`LICENSE-GPL-3.0.txt`](LICENSE-GPL-3.0.txt) — and is not covered by this
file. What follows covers the **artwork** that ships inside the jar under
`data/pvpocket/structure/`.

## Arena maps

Four of the five templates in this repository are derived from maps by **zzuf**
(https://github.com/zzufx), published in the [zzuf-maps](https://github.com/zzufx/zzuf-maps)
repository. Each source map carries its own `LICENSE.txt`; all four used here are licensed under
the same terms:

> **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)**
> https://creativecommons.org/licenses/by-sa/4.0/

| File in this repo | Source map | Source path | Size (x×y×z) |
|---|---|---|---|
| `arena_fun_house.nbt` | Fun House | `maps/tdm/fun_house` | 82 × 28 × 57 |
| `arena_earthborn.nbt` | GS: Earthborn | `maps/gs/gs_earthborn` | 45 × 34 × 60 |
| `arena_dark_nebula.nbt` | BN: Dark Nebula | `maps/arcade/bn_dark_nebula` | 87 × 43 × 79 |
| `arena_deadwood.nbt` | Deadwood | `maps/oitq/deadwood` | 88 × 36 × 87 |

Three further zzuf maps (Trial Chambers, Thistle, Avalon) were converted and shipped earlier and
have since been withdrawn at the project owner's request; their files are no longer in this
repository.

### Changes made

The files above are **adapted** versions of the originals, not copies. `scripts/map/world2struct.py`
performs the following, and this list is the "indicate if changes were made" requirement of CC BY-SA:

1. **Format conversion** — the source is a Minecraft world save (`region/*.mca`); the output is a
   vanilla structure template (gzipped NBT). Block positions, states and palette are preserved.
   Four of these maps are 1.8-era saves with numeric block ids, so the conversion also flattens
   those ids to 1.13+ names through a legacy id table.
2. **Objective machinery removed** — `barrier`, `light`, `structure_void`, `structure_block`,
   `jigsaw` and `command_block` are stripped. These encode PGM region definitions and command
   logic; PvPocket has no use for them and they would be exploitable inside a duel.
3. **Block substitution** — blocks that do not exist in Minecraft 1.21.1 are replaced by an
   equivalent (`pale_oak_*` → `oak_*`, `waxed_exposed_copper_bars` → `iron_bars`, and 24 more).
4. **Gravity blocks stabilised** — `*_concrete_powder` becomes `*_concrete`, otherwise the block
   falls the moment it is placed.
5. **Unstable blocks dropped** — pressure plates, torches and rails that would pop off on
   placement are deleted rather than left to fall out mid-duel; so is `fire`, which burns out on
   its own, and `tripwire`, which changes state when a player walks through it. Both would leave
   the arena permanently disagreeing with its own baseline.
6. **Entities and block entities dropped** — no item frames, armour stands, chests or their
   contents. The arena is blocks only.

Because these are adaptations of CC BY-SA 4.0 works, **the `.nbt` files in
`data/pvpocket/structure/` are themselves licensed CC BY-SA 4.0**, not under whatever licence
PvPocket's code uses. Redistributing a build that includes them means carrying this notice and the
same licence for those files. That obligation attaches to the artwork only; it does not reach the
mod's Java source.

## `arena_colosseum.nbt`

This one is **not** third-party artwork. It is generated from scratch by
`scripts/map/colosseum.py` — an ellipse in a loop, not a converted download — so it carries no
attribution requirement and no share-alike. It was written because a Roman amphitheatre was wanted
and no redistributable one could be found: the PGM community repositories ship XML without world
saves, and the builds on the usual map sites are all-rights-reserved by default, which is not
something a file inside a jar can be shipped under.

The generator is in the repository so the shape can be changed rather than merely used.

## The waiting lobby (`lobby.nbt`)

`data/pvpocket/structure/lobby.nbt` is the waiting lobby, and it is **not** PvPocket's own build:

| | |
|---|---|
| Map | **House Island / LOBBY** |
| Author | **Mirai_Minecraft** (https://www.planetminecraft.com/member/mirai_minecraft/) |
| Source | https://www.planetminecraft.com/project/house-island-lobby/ |
| Published | 2022-07-28 |

The author states, in the project description:

> House Island is a map created specifically for developers of minecraft servers. You can use it as
> your servers lobby.
>
> You can use this map for your own purposes as you like.

That is a permission written by the author rather than a standard licence, so it is reproduced here
verbatim instead of being summarised: it is the only statement of terms this file ships under, and
it grants use without conditions. Nothing in PvPocket's own LGPL-3.0 licence applies to this file,
and nothing here restricts the mod's source code.

### Changes made

Converted with `scripts/map/world2struct.py`; the build is not byte-identical to the download:

1. **Format conversion** — the source is a Minecraft 1.16.2 world save (`level.dat` plus `region/`);
   the output is a vanilla structure template (gzipped NBT). Block positions, states and palette are
   preserved.
2. **Entities and block entities dropped** — no item frames, armour stands, chests or their contents,
   and no sign text. The walls and furniture stay; what was written on them does not.
3. **Water at the cut edge filled** — the map was cropped out of a larger world, leaving a one-deep
   sea running off its right side. Left alone it ends in a one-block water wall at the island's rim,
   so `--fill-edge-water` replaced water connected to the box edge with the ground beneath it (sand
   and clay). The pond inside the island is untouched.
4. **Four lily pads dropped** — they floated on that sea, and a lily pad needs water under it, so
   they would have popped on the first tick.
5. **Unstable blocks dropped** — anything that would not survive placement was removed rather than
   left to fall out, the same pass the arena maps go through.

If you are the author and would rather this build were not redistributed inside a mod jar, say so and
it will be replaced.

## Adding your own map

If you add a map here, check its licence first. Shipping it in the jar is redistribution. See
`docs/zh/06-maps.md` (English: `docs/en/06-maps.md`) for the conversion workflow and what to record.
The original licence survey that produced this file is kept at
`docs/archive/research/PvPocket-场地模板-调研与工具.md`.
