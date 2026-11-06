# Visual specification v0.6

> Specification · English. Chinese version: [`../zh/04-visual.md`](../zh/04-visual.md)
>
> This document supersedes the visual language table in §9.1 of the v0.4 technical plan (archived
> under `../archive/design/`). Where they disagree, this wins.
>
> Scope: the self-drawn GUI layer (`client/gui/**`) and the three HUDs (`client/hud/**`) of
> `fabric-1.21.1`.

## 1. Goals

The interface's "smoothness" does not come from textures. It comes from four things, each of which
this specification states as an enforceable constraint:

1. **Depth** — near-black surfaces with a five-step lightness ladder, not one grey for every panel.
2. **Restraint** — one warm accent; red only for destructive actions; radii never above 6px.
3. **Motion** — every state change has a 60–320ms transition, framerate independent.
4. **Copy** — one line carries one fact; if a state can be drawn, do not also write it.

## 2. Colour tokens

Every value is defined in `PvTheme`; bare hex inside a component is forbidden. ARGB.

### 2.1 Surfaces (dark to light)

| Token | Value | Use |
| --- | --- | --- |
| `SCRIM` | `0xC4000000` | Backdrop behind a modal panel (77% black) |
| `SUNKEN` | `0xFF060607` | Recesses: slider tracks, read-only slots, disabled fills |
| `PANEL` | `0xF70A0A0B` | Main panels, HUD cards |
| `SIDEBAR` | `0xFF121214` | Sidebar container |
| `CARD` | `0xFF151517` | Row cards, control backgrounds |
| `CARD_HOVER` | `0xFF1E1E21` | Hover |
| `CARD_ACTIVE` | `0xFF26262B` | Selected, pressed |
| `TOOLTIP` | `0xFA101012` | Tooltip bubble |

### 2.2 Lines

| Token | Value | Use |
| --- | --- | --- |
| `BORDER` | `0xFF26262A` | Default 1px stroke |
| `BORDER_STRONG` | `0xFF34343A` | Hover / selected stroke |
| `DIVIDER` | `0xFF1C1C1F` | Separators |
| `HIGHLIGHT` | `0x0AFFFFFF` | 1px highlight on a panel's inner top edge — the only light source in the system |

### 2.3 Accent and danger

| Token | Value | Use |
| --- | --- | --- |
| `ACCENT` | `0xFFF5A524` | Selection, primary buttons, toggle tracks, slider fill, focus ring |
| `ACCENT_HOVER` | `0xFFFFB84D` | Accent hover |
| `ACCENT_PRESSED` | `0xFFD98A12` | Accent pressed |
| `ACCENT_DIM` | `0xFF7A5410` | Accent disabled / de-emphasised |
| `ACCENT_SOFT` | `0x24F5A524` | 14% accent wash (selected chips, revision chip) |
| `ON_ACCENT` | `0xFF0A0A0B` | Text on accent |
| `DANGER` | `0xFFE5484D` | Reject, cancel, remove |
| `DANGER_HOVER` | `0xFFF2666B` | Danger hover |
| `DANGER_PRESSED` | `0xFFC03338` | Danger pressed |
| `DANGER_DIM` | `0xFF7A2A2E` | Danger disabled |
| `DANGER_SOFT` | `0x24E5484D` | 14% danger wash |

Red never carries "selected"; accent never carries "destructive". Both hover variants shift only
within their own hue, never across hues.

### 2.4 Text and state

| Token | Value | Contrast on `PANEL` | Use |
| --- | --- | --- | --- |
| `TEXT` | `0xFFEDEDEF` | 17:1 | Body, values |
| `TEXT_DIM` | `0xFF9A9AA0` | 7.4:1 | Secondary information, subtitles |
| `TEXT_MUTED` | `0xFF616167` | 3.4:1 | Metadata only; never the sole carrier of meaning |
| `LIVE` | `0xFF4E9E6A` | — | HUD only: in progress, match point |

`LIVE` is the one deliberately non-brand colour. It appears only in the HUD, to separate "live /
match point" from accent; panels never use green.

## 3. Shape and metrics

| Item | Value | Notes |
| --- | --- | --- |
| Panel radius | 6px | Beyond 6px it reads as a card feed, not a tool panel |
| Card / control radius | 4px | |
| Chip radius | 3px | |
| Toggle track radius | `h/2` | The only pill shape in the system, so it never reads as a slider |
| Toggle size | 28 × 14, knob 10 | Reduced from the first v0.6 draft so it does not compete with row text |
| Grid | 8px | Panel padding 16px |
| Row height | 28 / 46 for multi-line | |
| Button height | 24 | |
| Tab height | 26 | |
| Scrollbar width | 6 | |

**Exactly one type size: 1.0×.** Titles gain weight through `Formatting.BOLD`, never through scale.
MC's bitmap font at a non-integer scale such as 1.3× is resampled by GL and the strokes smear into
grey edges — that is the main source of the "pixelated" look, and it is gone.

### 3.1 Rasterisation rules (anti-pixelation)

`PvDraw.roundRect`'s corners are not staircases: edge positions come from the circle equation and the
edge column is flattened with partial alpha (`aaRow`).

- **Split by rows, not columns.** The straight band (`ctx.fill(x, y+r, x+w, y+h-r)`) covers only the
  middle rows `[r, h-r)` the corners cannot reach, but it spans the **full width**; the remaining 2r
  rows all go through `aaRow`, each corner row assembled from "left edge column (partial alpha) +
  solid bar + right edge column". The two row groups are disjoint, so "one pixel, one draw"
  follows by construction.
- Overlap was the real v0.6 bug: a translucent wash (e.g. `ACCENT_SOFT` at 14%) doubles to 26% on
  overlapping rows, showing up as "a darker stripe above and below every tinted label". No two draws
  may cover the same pixel.
- **Splitting by columns was the v0.6.1 bug.** That version cut corner rows into "left / middle
  `[x+r, x+w-r)` / right", with the middle drawn as a full-height bar — so in those `h-2r` rows the
  left and right `r` pixels were **never drawn**, exposing the darker panel behind: every button and
  scrollbar had a black stripe on both sides. Worse, when `w - 2r <= 0` (the 2px "modified" marker,
  the 6px scrollbar) the middle width was zero and the shape collapsed to two corner tips.
- The criterion is therefore not "do not draw twice" but "**check row by row that the union covers
  the full row**". After touching `roundRect` / `ring`, verify: zero hole pixels, zero double-drawn
  pixels.
- `PvDraw.ring` (focus ring, selection ring) rasterises the outer edge row by row and **cannot** be
  implemented as "solid outer plus transparent inner fill" — passing 0 to `fill` means "do not draw",
  not "cut a hole".

All translucent fills therefore happen in a single draw call; to get denser colour, raise the alpha,
do not stack.

## 4. Component state matrix

Every interactive component implements four states, and transitions between them.

| Component | Normal | Hover | Pressed | Selected | Disabled |
| --- | --- | --- | --- | --- | --- |
| Primary button | Accent fill + black text | `ACCENT_HOVER` | `ACCENT_PRESSED` + 1px inset | — | `ACCENT_DIM` + dimmed text |
| Normal button | `CARD` + `BORDER` | `CARD_HOVER` + `BORDER_STRONG` | `CARD_ACTIVE` + inset | — | `SUNKEN` + `TEXT_MUTED` |
| Danger button | No stroke, 10% red wash + red text | 32% wash + `DANGER_HOVER` text | 42% wash + 1px inset | — | `SUNKEN` + `DIVIDER` text |
| Ghost button | No fill | 6% white + brighter text | — | — | Dimmed text |
| Toggle | Off: `CARD_ACTIVE` track, no stroke | Track brightens | — | On: `ACCENT` track + white knob | `SUNKEN` |
| Slider | `SUNKEN` track + accent fill | Fill brightens, knob 10→12px | Knob 13px | — | `ACCENT_DIM` |
| Stepper | `CARD` + `BORDER` | Hovered half lifts to `CARD_ACTIVE` | — | — | `SUNKEN` |
| Chip | `CARD` + grey text, no stroke | `CARD_HOVER` + brighter text | — | `ACCENT_SOFT` (14%) + accent text | `SUNKEN` |
| Tab | Transparent | `CARD_HOVER` | — | `CARD_ACTIVE` + 2px accent bar | — |
| List row | Transparent | `CARD_HOVER` at 75% | — | `CARD_ACTIVE` + 2px accent bar | Dimmed text |

**Strokes are used only on normal buttons, steppers and panels.** Coloured components (danger
buttons, chips, toggles, list rows) drop the stroke and separate states by wash density and text
colour. A coloured stroke layered outside a translucent wash is visually just "a darker stripe
again" — the same problem as §3.1 in another costume.

Focus: a 1px accent ring drawn 2px outside the control (`PvDraw.ring`), with opacity following
`focusT`. The old implementation drew an expanded translucent rectangle, which reads as a glow on a
black background; it is gone.

### 4.1 Row alignment

A row is a two-column structure — information on the left, actions on the right — with a fixed right
edge. Elements do not position themselves independently.

| Constraint | Value | Notes |
| --- | --- | --- |
| Row right padding | 20px | Row card's right edge to button's right edge |
| Gap between button and left content | 12px | Also the right anchor for score and metadata |
| Single-line vertical baseline | `y + (h - 8) / 2 + 1` | MC glyphs are 8px; the +1 after rounding is what looks centred |
| Two-line text | `y + 7` / `y + 20` | Fixed slots for a 46px row |

Scores and counts share the button's horizontal centre line rather than taking their own line — in a
two-line row, a number sunk onto the second line reads as belonging to neither.

### 4.2 HUD selection state

The HUD does not participate in the panel's depth ladder (it sits on top of the game, where the
background is uncontrolled), therefore:

- A selected slot is `CARD_ACTIVE` fill plus a 1px accent ring (`PvDraw.ring`, radius 3), **not** two
  heavy lines above and below. Heavy lines under an item icon read as "the frame is broken at both
  ends".
- The ring is inset by 1px so it does not touch the slot boundary.
- HUD fade-in multiplies `appearT` into alpha and does not move anything.

## 5. Motion

Implemented in `PvAnim`. Three principles:

1. **Framerate independent.** `dt = delta × 0.05`, clamped to `[0, 0.1]` seconds. Adding a fixed
   amount per frame is forbidden.
2. **Damping first.** Hover, press, focus and scroll-following use exponential damping
   `damp(cur, target, dt, tau)`; the target may change mid-flight without a jump.
3. **Timed tweens only for motions with a definite end**: the toggle knob and panel entry.

| Case | Duration / time constant | Curve |
| --- | --- | --- |
| Panel entry | 160ms | Scrim and panel easeOutCubic together |
| Hover fill | τ 90ms | Exponential damping |
| Press | 40ms in / 80ms out | Exponential damping + 1px inset |
| Focus ring | τ 80ms | Exponential damping |
| Toggle knob | 140ms | easeOutBack (~10% overshoot) |
| Slider fill (keyboard) | τ 50ms | Exponential damping (no damping while dragging) |
| Chip selection | τ 50ms | Exponential damping, per chip |
| Sidebar indicator | τ 55ms | Exponential damping, slides across items |
| Scroll following | τ 37ms | Exponential damping |
| Scroll fade mask | τ 80ms | Exponential damping |
| Tooltip | 150ms delay + τ 50ms fade | Exponential damping |
| Score change flash | 320ms | Linear decay to `ACCENT` |
| Spectator red dot pulse | 2000ms period | Sine |

`PvTheme.MOTION = false` disables every transition at once (all states snap into place).

## 6. Copy rules

This is the hard constraint added in this revision; it ranks with colour and radius.

1. **Do not restate state.** If the visuals already say it, the text does not repeat it. Selection is
   carried by fill, stroke and accent text; never append "(in use)" or "(selected)".
   - Example: when an arena is taken, the stepper uses accent text plus a 3px accent marker plus a
     hover tooltip, and the label itself stays the map's name.
2. **One line, one fact.** Subtitles, footer hints and group descriptions are designed as one line;
   overflow is a design error, not something `fit()` should truncate.
3. **Length budgets** (estimated at 9px per full-width glyph in MC's font):

| Location | Budget | Notes |
| --- | --- | --- |
| Panel title | 4–6 characters | |
| Subtitle | ≤ 14 characters | Shares a line with the revision chip |
| Group description | ≤ 12 characters | Shares a line with the loadout summary |
| Footer left hint | ≤ 12 characters | Shares a line with the button group |
| Row label | ≤ 8 characters | The right side belongs to the control |
| Tooltip | ≤ 40 characters | Wraps beyond that, at most 3 lines |

4. **No emoji or graphical symbol characters.** Arrows, plus and cross are drawn by
   `PvDraw.chevron/plus/cross`, never by font glyphs; command and chat text carry no symbol prefixes
   either.
5. Full-width punctuation in Chinese; `·` as separator; `…` as ellipsis.

## 7. Acceptance checklist

- [ ] Screenshots at GUI scale 1 / 2 / 3 / 4: no truncation, no overlap, nothing out of bounds
- [ ] No visible stair-stepping on corners at scale 2 and above; no darker stripes above or below a
      translucent wash (overlap regression)
- [ ] No black or dark stripes on either side of buttons, scrollbars or 2px markers (coverage-hole
      regression, see §3.1)
- [ ] Coloured components (danger buttons, chips, toggles) carry no stroke
- [ ] Right-hand elements of a list row share one right edge; single-line text is vertically centred
- [ ] Keyboard path: Tab / Shift-Tab / Enter / Space / ←→ / Esc / F2
- [ ] Repository-wide scan for emoji and graphical symbols returns nothing
- [ ] Four states present, with transitions between all of them
- [ ] Panel text stays at or above 7:1 contrast over bright terrain (sand, snow, sky)
- [ ] `gradlew compileClientJava` passes, and the animation layer allocates nothing per frame

## 8. Change log

| Version | Contents |
| --- | --- |
| v0.6 | Full rebuild: near-black ladder, amber accent, red retained, slight radii, unified motion layer, copy length budgets, emoji banned |
| v0.6.1 | Anti-pixelation: corner anti-aliasing and removal of overlapping draws; titles switched to bold at 1.0×; toggle reduced to 28×14; strokes removed from coloured components; row right-alignment and vertical centring rules; HUD selection changed to an enclosing thin ring |
| v0.6.2 | Fixed v0.6.1's axis error: `roundRect` split by rows instead of columns, eliminating the undrawn side stripes on every component and the vanished middle of narrow shapes (2px markers, 6px scrollbars) |
