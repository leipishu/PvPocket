# Third-party notices

PvPocket's own source code is not covered by this file. What follows covers the **artwork** that
ships inside the jar under `data/pvpocket/structure/`.

## Arena maps

All arena templates in this repository are derived from maps by **zzuf** (https://github.com/zzufx),
published in the [zzuf-maps](https://github.com/zzufx/zzuf-maps) repository. Each source map carries
its own `LICENSE.txt`; all three used here are licensed under the same terms:

> **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)**
> https://creativecommons.org/licenses/by-sa/4.0/

| File in this repo | Source map | Source path | Size (x×y×z) |
|---|---|---|---|
| `arena_fun_house.nbt` | Fun House | `maps/tdm/fun_house` | 82 × 28 × 57 |
| `arena_trial_chambers.nbt` | Trial Chambers | `maps/tdm/trial_chambers` | 47 × 29 × 95 |
| `arena_thistle.nbt` | Thistle | `maps/tension/thistle` | 102 × 44 × 99 |

### Changes made

The files above are **adapted** versions of the originals, not copies. `scripts/world2struct.py`
performs the following, and this list is the "indicate if changes were made" requirement of CC BY-SA:

1. **Format conversion** — the source is a Minecraft world save (`region/*.mca`); the output is a
   vanilla structure template (gzipped NBT). Block positions, states and palette are preserved.
2. **Objective machinery removed** — `barrier`, `light`, `structure_void`, `structure_block`,
   `jigsaw` and `command_block` are stripped. These encode PGM region definitions and command
   logic; PvPocket has no use for them and they would be exploitable inside a duel.
3. **Block substitution** — blocks that do not exist in Minecraft 1.21.1 are replaced by an
   equivalent (`pale_oak_*` → `oak_*`, `waxed_exposed_copper_bars` → `iron_bars`, and 24 more).
4. **Gravity blocks stabilised** — `*_concrete_powder` becomes `*_concrete`, otherwise the block
   falls the moment it is placed.
5. **Unsupported blocks dropped** — pressure plates, torches and fire that would pop off on
   placement are deleted rather than left to fall out mid-duel.
6. **Entities and block entities dropped** — no item frames, armour stands, chests or their
   contents. The arena is blocks only.

Because these are adaptations of CC BY-SA 4.0 works, **the `.nbt` files in
`data/pvpocket/structure/` are themselves licensed CC BY-SA 4.0**, not under whatever licence
PvPocket's code uses. Redistributing a build that includes them means carrying this notice and the
same licence for those files. That obligation attaches to the artwork only; it does not reach the
mod's Java source.

### Adding your own map

If you add a map here, check its licence first. Shipping it in the jar is redistribution. See
`docs/PvPocket-场地模板-调研与工具.md` for the conversion workflow and what to record.
