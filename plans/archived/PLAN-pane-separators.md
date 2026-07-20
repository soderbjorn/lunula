# Dynamic invisible draggable pane separators

## Context

Termtastic's pane grid only exposes corner handles for resizing. In a tiled-looking layout (e.g. one tall left pane, two stacked right panes), the natural gesture is to drag the *shared edge* between panes — like in tiling WMs (i3, yabai). The user wants invisible, draggable separator bars to be re-derived after every re-layout so the gesture "just works" on whatever the current layout happens to be.

This lives in **lunula** (per memory: layout system in toolkit) so termtastic and notegrow both get it without app-side changes. The toolkit already owns drag-to-move, drag-to-resize via corners, presets, and layout-changed events; this is a pure addition on top.

## Recommended design — smallest viable shape

One new file in `lunula-web`. One render-hook. Existing pane-update callbacks reused. No new persistence shape, no new menu items, no settings toggle.

### Algorithm: edge detection (pure function)

Given the current `List<FloatingPaneSpec>` (fields: `xPct/yPct/widthPct/heightPct`, all normalized 0..1, snapped to 5% grid), produce a `List<SeparatorSpec>`.

For **vertical separators** (drag changes width):
1. Collect every distinct x where some pane's left or right edge sits (with epsilon ≈ half a grid cell, e.g. 0.005, to absorb float drift).
2. For each such x:
   - `leftGroup` = panes whose right edge ≈ x
   - `rightGroup` = panes whose left edge ≈ x
   - If either group is empty → not an internal edge, skip.
   - Compute the y-overlap between the union of leftGroup's y-ranges and rightGroup's y-ranges. Result is zero or more `[yStart, yEnd]` segments.
3. Emit one `SeparatorSpec` per contiguous y-segment.
4. Filter out segments that touch the container edges only on one side with no opposing pane (already handled by step 2's empty-group check).

Mirror exactly for **horizontal separators** swapping x↔y.

```kotlin
data class SeparatorSpec(
    val orientation: Orientation,        // Vertical | Horizontal
    val positionPct: Double,             // x for V, y for H
    val startPct: Double,                // y-start for V, x-start for H
    val endPct: Double,
    val leftPaneIds: List<PaneId>,       // right/bottom edge sits at positionPct
    val rightPaneIds: List<PaneId>,      // left/top edge sits at positionPct
)
```

In the user's example (left pane 0..0.5×0..1, right-top 0.5..1×0..0.5, right-bot 0.5..1×0.5..1):
- One V-separator at x=0.5, y=0..1, leftPanes=[left], rightPanes=[right-top, right-bot]
- One H-separator at y=0.5, x=0.5..1, leftPanes=[right-top], rightPanes=[right-bot]

### Drag semantics

On `mousedown` over a separator:
- Call `layoutController.markCustom()` (already exists for corner-drag, line ~752 in `AppShellMount.kt`).
- Record initial position and each affected pane's pre-drag geometry.

On `mousemove`:
- New `positionPct` = cursor mapped to container fraction, snapped to the 5% grid via the existing `snapPct()` helper (`LayoutRenderer.kt:446`).
- Clamp so no affected pane shrinks below one grid cell (5%).
- For every pane in `leftPaneIds`: new width/height = positionPct − pane.start.
- For every pane in `rightPaneIds`: new x/y = positionPct, new width/height = pane.end − positionPct.
- Update the panes' CSS vars (`--dt-fp-x/y/w/h`) live, just like corner-drag.

On `mouseup`:
- Fire `onFloatingMoved(paneId, x, y)` and/or `onFloatingResized(paneId, w, h)` for each affected pane (these callbacks already exist; the host persists and re-renders normally).

### Render hook

At the end of `LayoutRenderer.render()` (`LayoutRenderer.kt:282`), after panes are mounted, call `recomputeSeparators(layout)`:
- Compute fresh `SeparatorSpec`s.
- Diff against the previously-mounted bars and reconcile (add new, remove stale, update geometry vars on retained ones).
- Mount each as `<div class="dt-pane-separator dt-pane-separator-v|h">` as siblings to `.dt-pane` inside the root container.

### When NOT to render separators

- **Auto preset is active** → skip emission. Auto recomputes the layout on every pane change, so a manually-dragged separator would be undone the moment a new pane spawned, which is misleading. Pass `presetIsAuto: Boolean` through `PaneLayout`, or read it from the controller. (Corner handles also already mark Custom on use; this matches that pattern.)
- **Overlapping panes** at the candidate edge → if a third pane crosses the edge segment, drop that segment. The corner handles still work for the overlapping case; we just don't pretend the edge is a clean boundary.

### Styling

Minimal — invisible default, cursor + faint highlight on hover:

```css
.dt-pane-separator {
  position: absolute;
  z-index: 999;
  background: transparent;
}
.dt-pane-separator-v { cursor: col-resize; width: 8px; transform: translateX(-4px); }
.dt-pane-separator-h { cursor: row-resize; height: 8px; transform: translateY(-4px); }
.dt-pane-separator:hover { background: color-mix(in srgb, currentColor 10%, transparent); }
```

## Files

**New:**
- `lunula/develop/lunula-web/src/jsMain/kotlin/se/soderbjorn/lunula/web/layout/PaneSeparators.kt`
  - `data class SeparatorSpec`, `enum Orientation`
  - `fun computeSeparators(panes: List<FloatingPaneSpec>, epsilon: Double = 0.005): List<SeparatorSpec>` (pure)
  - `fun mountSeparators(root: HTMLElement, separators: List<SeparatorSpec>, callbacks: SeparatorCallbacks)` (DOM)

**Modified:**
- `lunula/develop/lunula-web/src/jsMain/kotlin/se/soderbjorn/lunula/web/layout/LayoutRenderer.kt`
  - At end of `render()` (~line 282–376): call into `mountSeparators`. Reuse existing `onFloatingMoved` / `onFloatingResized` callbacks (lines 100–101 in `LayoutController.kt`) — no new callback shape.
  - Reuse `snapPct()` (line 446) for grid alignment.
  - Reuse the corner-drag mousedown/mousemove/mouseup template (`wireFloatingCornerResize`, lines 872–974) as the wiring pattern for separator drag.

**CSS:**
- Add three rules to the toolkit's stylesheet (same file that currently injects `.dt-pane-floating`).

**No changes needed in termtastic or notegrow.** They already handle the resize/move callbacks for corner-drag; separator-drag rides the same path. They only need to bump the toolkit version.

**No persistence changes.** Separator positions are derived from `FloatingPaneSpec`s, which are already persisted.

## Verification

1. **Unit test the pure function.** New `PaneSeparatorsTest.kt` (jsTest):
   - Single pane → 0 separators.
   - Two side-by-side panes → 1 vertical separator spanning full height.
   - User's screenshot layout (1 left tall + 2 right stacked) → 1 V at x=0.5 y=0..1; 1 H at y=0.5 x=0.5..1.
   - 4-quad grid → 1 V (full height) + 1 H (full width) + their crossing handled cleanly.
   - Overlapping panes → segment dropped where a third pane crosses.
   - Edge at container boundary (pane flush against x=0) → no separator emitted.
2. **End-to-end in termtastic dev build.**
   - Run `./gradlew :termtastic-web:jsBrowserDevelopmentRun` (or app-equivalent) in `termtastic/main/`.
   - Apply a non-Auto preset that produces the screenshot's layout.
   - Hover the midline → cursor flips to `col-resize`, faint highlight visible.
   - Drag the midline → all three panes (1 left, 2 right) resize in sync; release persists; refresh page → geometry restored.
   - Drag the horizontal bar between right panes → only those two resize.
   - Switch preset to Auto → separators disappear. Switch back to Custom → they reappear at the new edges.
   - Spawn a fresh pane that overlaps → no broken separator on the overlap region; corner handles still work.
3. **Smoke test in notegrow** with the same toolkit version to confirm no regression in its pane chrome.

## Open judgment calls (defaults chosen, easy to redirect)

- **Auto mode**: separators hidden (rationale above). Alternative: show them and let dragging auto-promote to Custom. Defaulting to hidden because Auto's whole contract is "I own geometry."
- **Hit zone**: 8px wide, centered on the edge (4px overhang each side). Could go thinner/thicker.
- **Hover cue**: cursor change + 10% currentColor wash. Could be silent (cursor only) or louder (visible bar).
- **Min pane size during drag**: one grid cell (5%). Could be a hard pixel floor instead.

## Note on plan location

Per durable feedback, plans should live in the working repo. After approval, copy this file to `lunula/main/PLAN-pane-separators.md` (or similar) so it sits alongside the code being changed.
