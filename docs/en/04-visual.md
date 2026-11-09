# Visual specification v0.7.2

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
| `SIDEBAR` | `0xFF151519` | Chrome: the title band and the sidebar share this surface, against the `PANEL` well |
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

### 2.5 Light theme

The home panel's Settings page can switch to light. Light is **not the five greys inverted** — it is a
full parallel palette in `PvTheme.apply(Theme)`, with token names in one-to-one correspondence.

Two hard constraints:

- **Colour tokens must not be `static final`.** javac inlines `static final int` into every call site,
  so changing the value in `PvTheme` has no effect on the 282 references already compiled in: no
  runtime theming, only a recompile. Colour fields are therefore mutable `static int`. Metrics
  (radii, row heights) and `MOTION` are mutable too.
- **The accent has to be darkened in the light theme.** `ACCENT #F5A524` is 1.9:1 on white, and it is
  used as text in 38 places (version chip, win chip, focus labels). Light uses `#FFB26A00` (about
  4.6:1); dark keeps the original. `teamInk` blends toward black for the same reason — team colours
  otherwise smear into white.

Light is not a different product: surfaces still step through five levels of luminance, the accent is
still the same warm hue, and red still means only "this destroys something".

The `Light theme` row on the Settings page carries a `beta` pill. **Labelled, not hidden** — the light
palette is complete enough to use and young enough to change, and the pill says exactly that without a
second line of prose nobody reads. The pill is `PvBadge`: 14px tall, 3px radius, `ACCENT_SOFT` fill
with `ACCENT` text — the same definition as the `v0`/`v1` revision chip in the rule panel's title row
(that one is drawn inline, right-anchored to a button, but takes its width and height from
`PvBadge.widthOf()` and `PvBadge.H`, so the two pills cannot drift apart). The width is measured in the
constructor and stored in the ordinary `width` field; measured on demand instead, the audit would see a
zero-size control.

## 3. Shape and metrics

| Item | Value | Notes |
| --- | --- | --- |
| Panel radius | 6px | Beyond 6px it reads as a card feed, not a tool panel |
| Card / control radius | 4px | |
| Chip radius | 3px | |
| Toggle track radius | `h/2` | The only pill shape in the system, so it never reads as a slider |
| Toggle size | 28 × 14, knob 10 | Reduced from the first v0.6 draft so it does not compete with row text |
| Grid | 8px | Panel padding 16px |
| Row height | 24 / 38 upward for two-line | Two-line height is derived from the control, see §4.1 |
| Button height | 20 | |
| Sidebar button height | 18 | Stacked, as wide as the column |
| Title-row chip height | 16 | |
| Header height | 30 | One line of text, no second row |
| Status line height | 18 | Text and chips only, no button group |
| Sidebar width | 72–100 | Derived from tab *and* button labels, see §3.2 |
| Tab height | 24 (floor 11) | The column also holds the commit buttons, so tabs compress or move |
| Scrollbar width | 6 | |

**Metrics are sized against a 426×240 logical canvas.** That is all a 1280×720 window gives at GUI
scale 3: the rule panel is really 406×220 with a 172px content area. The v0.6 numbers (row height 28,
button 24, tab 26, header 52, footer 40) were estimated for a large screen and could not fit — what
shrank is row height and inset, never the type size.

Column widths always go through `PvLayout.share(total, ratio, lo, hi)` instead of fixed pixels: a fixed
column gets squeezed to a negative width on a narrow canvas, and `enableScissor` does not validate its
rectangle — a negative width flips the scissor and the children paint over the column beside them.
Below 190px of row width `RuleRow` switches to two lines (label, then control) and clamps the control
width, so nothing can leave its own row at any width.

**Exactly one type size: 1.0×.** Titles do not gain weight through scale, and not through bold either.
They used to use `Formatting.BOLD`, but vanilla bold is not a typeface weight: it is the same glyph
drawn a second time shifted one pixel right. The bundled bitmap font ships a hand-made bold sheet, so
it reads as heavier; a resource pack's TrueType font does not, and the same code reads as a smear.
This interface is laid out for players who replace the font (see the note in the `README`), so bold is
gone everywhere and hierarchy comes from colour (`TEXT` against `TEXT_DIM`) and from position — a
header band with a divider under it. MC's bitmap font at a non-integer scale such as 1.3× is resampled
by GL and the strokes smear into grey edges — that is the main source of the "pixelated" look, and it
is gone too.

The exception is the **match scoreboard**. It is not part of a panel but floats over the world, and
it scales as a whole in four steps (75% / 90% / 100% / 120%) that the player picks in Settings. The
reason is different in kind: it is not a typographic decision but a trade between covering the view
and being too small to read, and only the person sitting at that screen knows which way to move it.
Type inside the panels never follows it.

**Text inside a fixed frame gives way in four steps: as written → shrunk → wrapped → cut.** This
interface is built for two languages whose words differ in length by up to a factor of two, and every
frame in it is measured from the short one. The ladder lives in `PvDraw`:

| Step | Condition | Result |
|---|---|---|
| 1 | `natural ≤ frame` | drawn at 1.0× |
| 2 | `max(0.75, frame/natural)` fits | drawn scaled, still one line |
| 3 | 0.75× does not fit and the frame is tall enough for a second line | wrapped, trying 1.0/0.9/0.8/0.75 |
| 4 | even wrapped it does not fit | cut at 0.75× with an ellipsis |

- **0.75 is a floor, not a preference.** The body face is 8px tall; below three quarters the glyphs
  stop resolving into letters at GUI scale 1, and a label nobody can read is worse than one that ends
  in an ellipsis.
- **Shrinking comes before wrapping.** A label that fits on one line at 0.9× stays on one line —
  a second line breaks the one-row-per-item rhythm of every panel. Wrapping is only reached where a
  single line would have to be cut.
- **Two pixels of slack.** Landing within 2px of the floor is not a label that does not fit: `0.733`
  and `0.75` are indistinguishable on screen, and turning `Accept & start` into `Accept & sta…` over
  1.6px is pure loss.
- Wrapping is only used where the **height allows** it: a 22px chip, a 24px row and a 20px button each
  hold two lines (a 16px title-row chip holds one), and a hint sentence holds up to three with its
  **height derived from the width** (`SettingRow.hint` computes it in `onWidth`).
- Change the size when the size can change. The `PvCycle` label column, the `KeyBindRow` label column
  and the history row's map name all give up something decorative first — arrow inset, round strip,
  stage chip — before they touch the type.

The audit reports shrunk and cut separately (`labels: N shrunk, M cut`). **Shrunk is the mechanism
working; cut is the frame genuinely being too narrow** — the latter comes with the screen, the canvas
and what the string needs at the floor. Fix the frame from that list, not the type size.

### 3.1 Rasterisation rules (anti-pixelation)

Every shape goes through `PvRaster`: per-pixel coverage comes from a **signed distance field**, not
from a single-axis inset.

```
sdRoundBox(p) = length(max(|p−c| − (h/2 − r), 0)) + min(max(qx,qy), 0) − r
fill coverage   cov = clamp(0.5 − d, 0, 1)
stroke coverage cov = clamp(|[d−0.5, d+0.5] ∩ [−t, 0]|, 0, 1)
```

- **Why one axis is not enough.** The old implementation only asked where the true edge crossed the
  row, so it knew the horizontal position of the arc and nothing about how much of the pixel the
  curve cut away vertically. At 45 degrees the two differ by a quarter of a pixel of alpha, and at
  GUI scale 3 one logical pixel is a 3×3 block — that difference is a visible step. The SDF gives the
  perpendicular distance, so a straight edge, a diagonal and an arc share one formula.
- **Do not supersample `0.5 − d`.** That ramp *is* the pixel's box filter; averaging four of them
  inside one pixel averages four 1px filters into a 1.5px one, which reported 0.75 on a straight edge
  where the truth is 1.0. That was a real bug during implementation.
- **A row is at most three spans, not one per pixel.** Each row is scanned from both ends to the
  first fully covered pixel, the run between them is one `fill`, and the boundary pixels are merged
  into runs of equal colour. The merge is a correctness matter, not a nicety: the stroke band of
  `roundPanel` runs the full width of the top and bottom rows (the whole row sits inside `[−t, 0]`),
  so emitting pixel by pixel gave 300 spans for a single row of a 300px card — 1,686 `fill` calls
  for one 300×38 row, and a scrolling list spent every frame building quads instead of drawing. The
  band is now located by distance first and merged by colour: 202 spans for the same card, against
  66 for `roundRect`. Measured: 93µs → 29µs.
- **One pixel, one draw.** The three spans of a row are disjoint, and the boundary scan hands each
  pixel to exactly one emitter. `PvRaster.main` checks it, once for `roundedRect` and once for
  `roundPanel`: zero hole pixels, zero double-drawn pixels, within 0.12 of an 8×8 reference area,
  and pure fill colour well inside the border. Run it after touching `PvRaster`:
  `java -cp build/classes/java/client com.leipishu.pvpocket.client.gui.PvRaster`.
- **The stroke is inset, not centred.** A 1px stroke owns the outermost pixel (`[−t, 0]`), matching
  the "draw the border, then fill one pixel in" pair it replaces. A centred 1px stroke covers only
  half the outer row and reads lighter than the panel beside it.
- **`roundPanel` composites in one pass.** A boundary pixel computes both coverages, mixes the two
  colours by the border's share, and takes the fill's coverage as its alpha. Two stacked shapes
  would blend a feathered stroke edge into a feathered fill edge and leave a half-transparent ring
  of the wrong colour along every straight edge.
- **`ring` cannot be "solid outer plus transparent inner fill"** — passing 0 to `fill` means "do not
  draw", so the inner shape is never cut out. It is carved arithmetically as the distance band
  `[−t, 0]`.
- **`chevron` / `plus` / `cross` stay hard-pixeled**: a 1.5px diagonal turns into grey mush at scale
  1, while hard pixels stay hard at every scale.

All translucent fills therefore happen in a single draw call; to get denser colour, raise the alpha,
do not stack.

### 3.2 Panel regions: chrome and well

A panel is not a card inside a card. It is two surfaces:

1. **Chrome.** The whole panel is filled with `SIDEBAR` at radius 6. The title band and the left
   column are **the same surface**, so they are continuous by construction: no stroke, no divider
   between them.
2. **Well.** A multi-radius rectangle is laid over it, from `(panelX + sidebar, panelY + header)` to
   the panel's bottom-right corner, filled with `PANEL`. Square at top-left, top-right and
   bottom-left; rounded at the bottom-right to follow the panel.

Two constraints:

- **Nothing is drawn between chrome and well.** The regions separate by value alone (`0x15` against
  `0x0A`), which is why `SIDEBAR` has to sit a full step above `PANEL`: at `0x12` against `0x0A` the
  difference is 3% of luminance and reads as a smudge rather than as a boundary.
- **Dividers only appear inside the well.** There is no rule under the title band (the value step is
  the rule); the status line has one `DIVIDER` above it, drawn from the well's left edge and never
  across the sidebar.

**The sidebar width is derived from content**: `clamp(max(longest tab label + 34, longest button label
+ 16), 72, 100)`, where 34 is 12 of side inset + 10 of text inset + 12 for the dirty-dot column, and 16
is the button's 8px on each side. A fixed width wastes space in one language and truncates in the other
("Consumables" is 64px).

**Tab labels alone are not enough.** The English commit button is wider than any tab ("Accept & start"
is 84px against "Players" at 42px), and a 76px column measured from the tabs truncates it to
"Accept & st…". So the buttons are built first, their labels measured, and only then is the column
sized: `init()` runs `buildStack()` → `sidebarWidth(stack)` → `buildActions(stack)`.

**The commit buttons stack at the foot of the sidebar**, primary lowest, where a form's confirm
button belongs. Eight tabs plus three buttons in a 190px column compresses the tabs to 12px, which
is the acceptable floor; moving non-commit actions to the status line (`revert`) and
open-somewhere-else actions out of the column is what makes that budget close. The rule is a single
vertical budget: sidebar height = everything below the header, and tabs × count + buttons × count +
gaps has to fit — when it does not, remove a button first.

**When it still does not fit, change the arrangement rather than squeeze harder.** Eight tabs and three
buttons need 213px; on a 320×180 canvas the panel is 160px tall. `PvTabs.minHeight(n)` reports the
shortest legible pitch (11px per entry), and below that the screen switches to `compact`: the tabs take
the whole column and the buttons move to a single row under the well, each sized from its own label.
The budget not closing is a reason to re-arrange, not a reason to keep compressing — compressing costs
legibility first and the button second.

**There is no selection marker in the sidebar.** Selection is one `CARD_ACTIVE` block, and the pointer
and the keyboard move the same highlight. A 2px accent bar used to slide along the outer edge between
tabs, but it said the same thing as the selected fill, and having both made the bar look like a
separate state that could be dragged somewhere else.

## 4. Component state matrix

Every interactive component implements four states, and transitions between them.

| Component | Normal | Hover | Pressed | Selected | Disabled |
| --- | --- | --- | --- | --- | --- |
| Primary button | Accent fill + black text | `ACCENT_HOVER` | `ACCENT_PRESSED` + 1px inset | — | `ACCENT_DIM` + dimmed text |
| Normal button | `CARD` + `BORDER` | `CARD_HOVER` + `BORDER_STRONG` | `CARD_ACTIVE` + inset | — | `SUNKEN` + `TEXT_MUTED` |
| Danger button | No stroke, 10% red wash + red text | 32% wash + `DANGER_HOVER` text | 42% wash + 1px inset | — | `SUNKEN` + `DIVIDER` text |
| Ghost button | No fill | 6% white + brighter text | — | — | Dimmed text |
| Toggle | Off: `CARD_ACTIVE` track, no stroke | Track brightens | — | On: `ACCENT` track + white knob | `SUNKEN` |
| Slider | `SUNKEN` track + accent fill + 10px white knob | Fill brightens + 8px halo at 20% alpha | Same as hover, halo held | — | `ACCENT_DIM` |
| Stepper | `CARD` + `BORDER` | Hovered half lifts to `CARD_ACTIVE` | — | — | `SUNKEN` |
| Chip | `CARD` + grey text, no stroke | `CARD_HOVER` + brighter text | — | `ACCENT_SOFT` (14%) + accent text | `SUNKEN` |
| Tab | Transparent | `CARD_HOVER` | — | `CARD_ACTIVE` | — |
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

**In the two-line layout the row height is derived from the control, not from a constant.** The label
owns the top of the row and the control gets what is left:

```
y + 0  … y + 5    air
y + 5  … y + 13   label glyphs (8px)
y + 13 … y + 14   1px gap
y + 14 … y + 14+control height   control
                                 + 4px air = row height
```

Subtracting from the row's bottom edge (`y + height - control height - 6`) used to be the way, and it
only agrees with the label when every control is the same height: chips are 22px and a stepper is
20px, so the chip row's control started at `y+12` while the label ran to `y+14` — **the control was
drawn over the last two rows of pixels of the label**, visible only on the multi-select rows because
only they are tall enough to hide it. The height has to be counted from the control, so the control
cannot eat the label.

### 4.2 HUD selection state

The HUD does not participate in the panel's depth ladder (it sits on top of the game, where the
background is uncontrolled), therefore:

- A selected slot is `CARD_ACTIVE` fill plus a 1px accent ring (`PvDraw.ring`, radius 3), **not** two
  heavy lines above and below. Heavy lines under an item icon read as "the frame is broken at both
  ends".
- The ring is inset by 1px so it does not touch the slot boundary.
- HUD fade-in multiplies `appearT` into alpha and does not move anything.

## 5. Motion

Implemented in `PvAnim`. Four principles:

1. **Framerate independent.** `dt = delta × 0.05`, clamped to `[0, 0.1]` seconds. Adding a fixed
   amount per frame is forbidden.
2. **Damping first.** Hover, press, focus and scroll-following use exponential damping
   `damp(cur, target, dt, tau)`; the target may change mid-flight without a jump.
3. **Timed tweens only for motions with a definite end**: the toggle knob and panel entry.
4. **Animate continuous quantities only.** Position, alpha and wash density are fine; **drawn size is
   not**. A 1.5px change of radius can only land on two integers, so however smooth the underlying
   damping is, it reads as two hard steps — the slider's hover used to grow the dot from radius 5 to 7
   via `round(hoverT × 1.5)`, which looked like a stutter. The dot's radius is now constant and what
   animates is the alpha of the halo behind it (256 levels, well below the eye's threshold).

| Case | Duration / time constant | Curve |
| --- | --- | --- |
| Panel entry | 160ms | Scrim and panel easeOutCubic together |
| Hover fill | τ 90ms | Exponential damping |
| Press | 40ms in / 80ms out | Exponential damping + 1px inset |
| Focus ring | τ 80ms | Exponential damping |
| Toggle knob | 140ms | easeOutBack (~10% overshoot) |
| Slider fill (keyboard) | τ 50ms | Exponential damping (no damping while dragging) |
| Slider halo | τ 90ms | Exponential damping, alpha only; hover and drag share one target |
| Chip selection | τ 50ms | Exponential damping, per chip |
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
2. **One line, one fact.** Subtitles and status-line hints are designed as one line; overflow is a
   design error, not something `fit()` should truncate.
3. **Length budgets** (estimated at 9px per full-width glyph in MC's font):

| Location | Budget | Notes |
| --- | --- | --- |
| Panel title | 4–6 characters | |
| Subtitle | ≤ 14 characters | Shares a line with the revision chip |
| Status line hint | ≤ 12 characters | Shares a line with the loadout tally and revert |
| Row label | ≤ 8 characters | The right side belongs to the control |
| Tooltip | ≤ 40 characters | Wraps beyond that, at most 3 lines |

4. **No emoji or graphical symbol characters.** Arrows, plus and cross are drawn by
   `PvDraw.chevron/plus/cross`, never by font glyphs; command and chat text carry no symbol prefixes
   either.
5. Full-width punctuation in Chinese; `·` as separator; `…` as ellipsis.

## 7. Acceptance checklist

- [ ] `scripts/audit-ui.sh` passes: 60 layouts (5 screens × every page and dialog × 2 canvases) with
      zero findings
- [ ] Screenshots at GUI scale 1 / 2 / 3 / 4: no truncation, no overlap, nothing out of bounds
- [ ] No visible stair-stepping on corners at scale 2 and above; no darker stripes above or below a
      translucent wash (overlap regression)
- [ ] No black or dark stripes on either side of buttons, scrollbars or 2px markers (coverage-hole
      regression, see §3.1)
- [ ] `PvRaster.main` passes: zero hole pixels, zero double-drawn pixels, coverage within 0.12
- [ ] No line between chrome and well; the regions read by value alone (see §3.2)
- [ ] The sidebar's vertical budget holds: tabs and commit buttons stay inside the panel, checked in
      all three roles and both languages
- [ ] Coloured components (danger buttons, chips, toggles) carry no stroke
- [ ] Right-hand elements of a list row share one right edge; single-line text is vertically centred
- [ ] A slider dragged to its maximum does not cover its read-out; a control in a two-line row does
      not cover its label
- [ ] Keyboard path: Tab / Shift-Tab / Enter / Space / ←→ / Esc / F2
- [ ] Repository-wide scan for emoji and graphical symbols returns nothing
- [ ] Four states present, with transitions between all of them
- [ ] Panel text stays at or above 7:1 contrast over bright terrain (sand, snow, sky)
- [ ] `gradlew compileClientJava` passes, and the animation layer allocates nothing per frame

Overlap is no longer a thing a human looks for. `PvAudit` records every widget box and every string of
text during the real render frame, then checks for zero-size boxes, boxes escaping their clip rect, and
same-layer pairs that intersect. Layers are declared explicitly (chrome, each scroll pane, modal,
tooltip) — without them a modal covering the panel is reported as 26 000 square pixels of overlap. It
measures the render path itself rather than a model of it, so a new widget that forgets to take part in
layout is still visible to it.

## 8. Change log

| Version | Contents |
| --- | --- |
| v0.6 | Full rebuild: near-black ladder, amber accent, red retained, slight radii, unified motion layer, copy length budgets, emoji banned |
| v0.6.1 | Anti-pixelation: corner anti-aliasing and removal of overlapping draws; titles switched to bold at 1.0×; toggle reduced to 28×14; strokes removed from coloured components; row right-alignment and vertical centring rules; HUD selection changed to an enclosing thin ring |
| v0.6.2 | Fixed v0.6.1's axis error: `roundRect` split by rows instead of columns, eliminating the undrawn side stripes on every component and the vanished middle of narrow shapes (2px markers, 6px scrollbars) |
| v0.7 | Rasterisation moved to a signed distance field (`PvRaster`): coverage comes from the perpendicular distance rather than a single-axis inset, so arcs and small radii (dots, knobs, toggle tracks, scrollbars) stop stair-stepping; strokes became inset distance bands composited in one pass; panels became two surfaces — chrome and well — with the title band and sidebar continuous and stroke-free, the header cut to 30px and its second row removed, commit buttons stacked in the sidebar, and the footer band dropped |
| v0.7.1 | The sidebar's selection marker is gone (selection is the fill alone); its width is derived from tab *and* button labels, ceiling raised to 100; when the vertical budget fails the buttons move to a row under the well; two-line row height is derived from the control, so the control no longer covers the label; the slider track reserves the knob's radius at both ends, so the knob at maximum no longer covers the read-out; slider hover animates the halo's alpha instead of the dot's radius; the overlap audit (`scripts/audit-ui.sh`) is now an acceptance item |
| v0.7.2 | Bold removed everywhere: vanilla `§l` under a TrueType resource pack is "the same glyph drawn twice, one pixel apart", so title hierarchy now comes from colour and position and `PvDraw.title()` is gone; the match scoreboard scales in four steps (75/90/100/120%) |
