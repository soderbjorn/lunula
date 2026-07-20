# Terminal background mismatch — diagnosis & fix

Reference doc for the change made on 2026-05-09 to address a visible
colour seam between `.terminal-inner` (the chrome padding around the
xterm canvas) and `.xterm-viewport` (the canvas itself) inside a
termtastic terminal pane. Keep this around so the next person — or the
next Claude — can pick up the trail if the symptom returns.

## Symptom

Inside a termtastic terminal window, the area immediately around the
xterm canvas (the ~10px `.terminal-inner` padding strip just under the
titlebar, and the strips on the left/right/bottom) renders as a subtly
*different* shade from the canvas itself. Both surfaces are nominally
"the terminal background colour"; the seam is visible at most ≤1–2 LSBs
per channel but reads as a thin inner ring.

User confirmed (2026-05-09) the mismatch they were seeing was specifically
"padding strip vs xterm canvas" — *not* titlebar-vs-content (the
titlebar/edges deriving a darker shade from `bg` is intentional via
`chrome.titlebar = mixColors(bg, BLACK, 0.06)`).

`web/src/jsMain/resources/styles.css:1148-1156` already documents an
earlier round of the same class of bug — the "double border" issue —
fixed previously by moving the visible card to `.terminal-inner` so
there'd only be one rounded surface. That fix removed one symptom but
did not eliminate the underlying *colour-source* divergence.

## Root cause

Two CSS strings are derived from the same `palette.terminal.bg` Long
value, by **different** conversion functions:

| Surface | Set by | Conversion fn | Output for `0xFFf5f0d8` | Output for `0xCCf5f0d8` |
|---|---|---|---|---|
| `.terminal-inner` (CSS var `--t-terminal-bg` / alias `--terminal-bg`) | `ResolvedPalette.toCssVarMap()` / `toCssAliasMap()` (termtastic `ThemeHelpers.kt:152`) | `argbToCss(...)` | `#f5f0d8` | `rgba(245,240,216,0.8)` |
| `.xterm-viewport` (xterm.js's `theme.background`, set inline by xterm onto the viewport element) | `buildXtermTheme()` (termtastic `WebStateActions.kt:177`) | `argbToHex(...)` — silently strips alpha | `#f5f0d8` | `#f5f0d8` |

When `palette.terminal.bg` is fully opaque (`alpha == 0xFF`), both
conversions produce identical hex strings → both surfaces are
pixel-identical. **When `palette.terminal.bg` carries any alpha < `0xFF`
the two diverge:**

- The CSS rgba composites *through* `.terminal-inner` against whatever
  paints behind it. That's `.terminal-cell`, which is overridden by the
  "windows" section palette (see `WebStateActions.kt:252`). Resulting
  visible colour depends on the windows-section bg.
- xterm.js receives the alpha-stripped opaque hex and paints the
  viewport with the "pure" colour.

→ `.terminal-inner` shows a blend, `.xterm-viewport` shows the
unblended colour, the eye sees an inner ring.

xterm.js does **not** honour a translucent terminal background unless
`allowTransparency: true` is set, and it isn't (see
`LayoutBuilder.kt:171-177`), so silently dropping alpha is wrong both
for fidelity *and* for matching the surrounding chrome.

`palette.terminal.bg` can end up non-opaque without obvious authoring,
e.g. through a saved override that copied a `withAlpha(...)`-derived
token (the resolver uses `withAlpha` liberally — see
`ThemeResolver.kt:103-118`), or a scheme override that points
`terminal.bg` at an alpha-bearing surface token, or future code that
derives `terminal.bg` from a translucent value.

## The fix

Make `palette.terminal.bg` opaque at the source so both downstream
consumers always see the same colour. Composite any alpha against the
resolver's own opaque `bg` seed.

### Files touched

1. **`lunula/develop/lunula-core/src/commonMain/kotlin/se/soderbjorn/lunula/core/ColorMath.kt`**
   Added `flattenOnto(top: Long, base: Long): Long` — alpha-composites
   `top` over the opaque `base` and returns an opaque ARGB value. No-op
   when `top` is already opaque.

2. **`lunula/develop/lunula-core/src/commonMain/kotlin/se/soderbjorn/lunula/core/ThemeResolver.kt:132`**
   Wrapped the existing `terminal.bg` resolution in `flattenOnto(..., bg)`:
   ```kotlin
   val terminalBg = flattenOnto(overrideFor(ovr, "terminal.bg", isDark) ?: bg, bg)
   ```

That's it. No changes to termtastic — once `palette.terminal.bg` is
guaranteed opaque, `argbToHex(...)` and `argbToCss(...)` produce the
same `#rrggbb` automatically and both consumers stay in sync.

### Why composite against `bg` (not the windows-section bg)

`bg` is the resolver's own opaque seed, always `0xFF`-alpha by
construction. Compositing against it keeps the resolved palette
internally consistent regardless of which section consumes it
downstream. Using the windows-section bg would couple the resolver to a
DOM-side override scheme it shouldn't know about.

## Why this approach over alternatives

- **"Use `argbToCss` in `buildXtermTheme()`"** — wrong direction: xterm
  doesn't honour translucent bg without `allowTransparency: true`, and
  turning that on would *introduce* compositing-against-page-bg as a
  new source of mismatch.
- **"Set `allowTransparency: true` and use rgba both places"** — would
  make xterm composite against whatever's behind its viewport
  (`.terminal-inner`'s computed colour), which then composites against
  `.terminal-cell`. Two stacked composites with the same nominal alpha
  are **not** equivalent to one composite, so the mismatch would
  persist in a different form.
- **Pre-flattening at the resolver** keeps the entire app's view of
  `terminal.bg` consistent with the user's authoring intent: "this is
  the colour you'll see in the terminal area," opaque, single source
  of truth.

## How to verify the fix worked

1. Rebuild termtastic web. Open a window using the cream theme that
   exhibited the bug.
2. In DevTools console, confirm both surfaces report the same opaque
   value:
   ```js
   getComputedStyle(document.querySelector('.terminal-inner')).backgroundColor
   document.querySelector('.xterm-viewport').style.backgroundColor
   ```
   Both should be the same `rgb(...)` triple. Neither should be `rgba(...)`.
3. Visual: zoom a screenshot to ≥400% in macOS Preview and colour-pick
   at the boundary between `.terminal-inner` padding (top ~10px just
   under the titlebar) and the xterm canvas (where text renders). The
   two samples should be indistinguishable.
4. Regression: switch themes (cream → dark → another light theme) and
   confirm the seam stays invisible across switches.
5. Synthetic: author a test theme whose `terminal.bg` has explicit
   alpha (e.g. `0x80f5f0d8`). Before the fix the seam is dramatic;
   after the fix both surfaces show the same composited opaque colour.

## What to do if the symptom returns after this fix

Run step 2 above. There are three possible outcomes:

- **Both surfaces report the same opaque rgb but the seam is still
  visible** → the cause is *not* the conversion path (this fix is
  irrelevant). Look next at:
  - DOM-renderer subpixel artefacts (xterm 5.3.0 with the default DOM
    renderer renders rows as `<div>`s positioned by `transform`; on
    fractional-DPI scaling they may anti-alias their bg against the
    viewport bg).
  - A new CSS rule or vendor stylesheet putting a different
    `background-color` on `.xterm-viewport`, `.xterm-screen`, or
    `.xterm-rows`.
  - A canvas/webgl renderer addon being added later — both apply
    canvas-level colour management that CSS doesn't go through.
- **`.terminal-inner` reports `rgba(...)` but `.xterm-viewport`
  reports opaque rgb** → `flattenOnto` ran but something downstream
  re-introduced alpha, e.g. the CSS path is being driven by a
  *different* palette than the xterm path. Compare what
  `currentResolvedPalette()` returns against what
  `sectionPalette("terminal")` returns at the moment the bug appears;
  they should be the same `terminal.bg` value.
- **Both report `rgba(...)`** → `flattenOnto` is not running. Check
  that `terminal.bg` is actually being resolved through
  `ThemeResolver.kt:132` (it's possible a new code path bypassed the
  resolver, or an override is being applied *after* resolution).

## Touched files / line numbers (snapshot at fix time)

- `lunula/develop/lunula-core/src/commonMain/kotlin/se/soderbjorn/lunula/core/ColorMath.kt` — `flattenOnto` added below `withAlpha`.
- `lunula/develop/lunula-core/src/commonMain/kotlin/se/soderbjorn/lunula/core/ThemeResolver.kt:132` — `terminalBg` now flattened.

Reference (read-only) call sites that rely on the new invariant:
- `termtastic/develop/web/src/jsMain/kotlin/se/soderbjorn/termtastic/WebStateActions.kt:177` (xterm consumer).
- `termtastic/develop/web/src/jsMain/kotlin/se/soderbjorn/termtastic/ThemeHelpers.kt:152` (CSS alias consumer).
- `termtastic/develop/web/src/jsMain/resources/styles.css:1148-1208` (the documented "double-border" history is the precedent for this fix).
