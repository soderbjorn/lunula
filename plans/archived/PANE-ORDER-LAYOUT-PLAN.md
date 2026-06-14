# Pane order as the layout-importance signal

## Context

Today the toolkit's layout dropdown (every preset, including Auto) puts the **active pane** in the "primary" slot. The active pane is whatever you clicked last — it bubbles to the head of `LayoutController.paneOrder`, which is the slot-0 input for `applyPresetToPanes`. This is opaque ("why is _this_ pane huge? oh, because I clicked it") and gives every preset only **one** privileged slot.

The user wants two changes:

1. **User-controlled ordering.** The left sidebar lists panes under each tab. Make those rows drag-to-reorder. The new order is the importance order: first row = most important, second = second-most, etc. The order is persisted (per app — termtastic via its server `SettingsPersister` flat KV; notegrow via its blob persister) and survives reloads.

2. **Layouts read from the order, not from focus.** Today `recordFocus` mutates `paneOrder`; that link is severed. Clicking a pane no longer changes its size. The sidebar order is the only source of importance. Existing presets continue to work (they already consume `paneOrder`); decoupling unlocks a new family of presets with **multiple** weighted slots (biggest + 2nd biggest + rest, etc.) and Auto variants that use 2–3 importance ranks instead of 1.

Net effect: importance becomes an explicit, persisted, user-edited list — not a side effect of clicking around.

## Critical files

| File | Role |
| --- | --- |
| `toolkit-web/.../shell/AppShellMount.kt` | Sidebar render (`buildLeftSidebarContent`, `buildPaneRow` ~L1423–1523), focus plumbing (L866, L959, L1518, L1759), `applyPresetToPanes` host loop (L1789–1818), persist/restore (L1606–1621, L406+, codec L152–224) |
| `toolkit-web/.../layout/LayoutController.kt` | `paneOrder`, `recordFocus`, `recordCreate`, `recordRemove`, `applyPresetToPanes` |
| `toolkit-core/.../layout/LayoutPreset.kt` | Preset enum, `computeBoxes`, `autoBoxes`, dropdown order |
| `toolkit-web/.../shell/Sidebar.kt` | Existing mouse-drag pattern (`attachSidebarResizeHandle` L192–269) — reusable for row drag |
| `toolkit-web/.../ElectronIpcPersister.kt` / `LocalStoragePersister.kt` | Already flat-KV persisters; no change |

`paneOrderByTab` is already in `PersistedLayoutState` (AppShellMount L110–114) and is already round-tripped — no new persisted key, no codec migration. Per the user's "no persistence compat" memory, if we ever change shape we just discard old data; today we don't need to.

## Design

### 1. Decouple focus from importance

In `LayoutController`:

- Add `private val _activePaneByTab` is **not** needed at controller level — controllers are already per-tab. Add `private var _activePaneId: PaneId? = null` and expose `val activePaneId: PaneId?`.
- Replace `recordFocus(paneId)` with `setActive(paneId)`:
  - Sets `_activePaneId`. **Does not** touch `_paneOrder`.
  - Fires `onChange()` so the sidebar highlight re-renders. (Active pane is in-memory only — see persistence note below.)
- New method `reorderPane(paneId: PaneId, targetIndex: Int)`:
  - Removes `paneId` from `_paneOrder`, inserts at clamped `targetIndex`, fires `onChange()`.
  - No-op if order unchanged.
- `recordCreate(newPaneId, parentPaneId)` — append at **tail** of `_paneOrder` (least-important by default; user can drag up). Parent linkage retained for future heuristics. (Was: insert at head.)
- `recordRemove` — unchanged. Also clears `_activePaneId` if it matched.
- `reset(panes)` — unchanged semantics (importance order from persistence, head-first); clears `_activePaneId`.
- `applyPresetToPanes` — unchanged. It already reads `paneOrder`, which now reflects the user's drag order.

In `AppShellMount`:

- Every existing `controllerFor(tabId).recordFocus(paneId)` call (L866, L959, L1518, L1759) becomes `setActive(paneId)`.
- `activePaneForActiveTab()` (L1004) returns `controller.activePaneId ?: paneOrder.firstOrNull()` — falls back to "primary" only when nothing has been clicked yet (e.g. fresh load).

### 2. Drag-to-reorder in the sidebar

Implement directly in DOM (no library), modeled on `attachSidebarResizeHandle`:

- In `buildPaneRow` (AppShellMount L1481):
  - Set `draggable="true"` on the whole `.dt-sidebar-row`, with `cursor: grab` on hover and `cursor: grabbing` during drag.
  - Use **HTML5 drag events** (`dragstart`, `dragover`, `drop`, `dragend`) — supported in the toolkit-web target and gives free OS visuals (cursor, drop-not-allowed).
  - On `dragstart`, set `dataTransfer.setData("application/x-darkness-pane", JSON.stringify({tabId, paneId}))` and `dataTransfer.effectAllowed = "move"`. Reject drops from other tabs (compare `tabId`) — reordering is intra-tab only.
  - The existing click handler on the row continues to fire normally; a click without a `dragstart` activates the pane as before.
- In `buildLeftSidebarContent` (L1423):
  - Each section body becomes a drop zone. On `dragover`, compute insertion index by midpoint-of-row (row above/below cursor `Y`) and render a 2 px insertion indicator (CSS `.dt-sidebar-row-drop-before` / `…-after`).
  - On `drop`, call `controllerFor(tabId).reorderPane(paneId, insertionIndex)`. The controller's `onChange` fires, which already triggers `persistLayoutState()` and a sidebar re-render via the existing render loop.
- Visual feedback: ghost row stays as the browser default; add `.dt-sidebar-row.dt-dragging { opacity: 0.4 }` on the source row.

### 3. Reorder behaviour vs. active preset

Behaviour by current preset:

- **`Auto` active**: reorder retiles **immediately** with the new order (Auto's whole point is "it follows my intent"). Implementation: after `reorderPane` fires `onChange`, the existing host hook checks `if (controller.activePreset == LayoutPreset.Auto) maybeReapplyPresetForActiveTab(...)`. No code change to `applyPresetToPanes`; just an extra branch in the change listener.
- **Any other preset active** (Hero / Big-two / Sidebar / …): reorder updates the list and persists; geometry is **not** retiled. The user picks the preset again (or any other) from the dropdown to apply the new order. This keeps the drag predictable for static presets where users may have hand-anchored expectations.
- **`Custom`**: reorder updates list and persists; geometry untouched.

### 4. New multi-slot presets

The current preset family already has multi-slot shapes: `BigTwoStack*` (primary 60% + secondary 40% × 60% + rest stacked) and the L/T variants. Auto has only one privileged slot.

Adding three presets in `LayoutPreset.kt` (`computeLayoutBoxes` + enum + `key` + `label` + `DROPDOWN_ORDER`):

| New preset | Slots | Geometry (n ≥ slots; smaller n collapses gracefully) |
| --- | --- | --- |
| `AutoBigTwo` | 1, 2, rest | Auto-style with **two** privileged ranks. Primary 60% × 100% on the left. Secondary takes the **top half** of the 40% right strip; the rest equal-stack the bottom half. For n=2: 60/40 split. For n=3: identical to current Auto-3 (since rest is empty, secondary fills the strip). For n≥4 the rest stacks. |
| `AutoBigThree` | 1, 2, 3, rest | Primary 50% × 100% on the left. Slots 2 and 3 stacked top-right at 50% × 50% each. Rest equal strip across the bottom-right (50% × 50%, divided horizontally). For n<4 collapses to AutoBigTwo / Auto. |
| `HeroPair` | primary, secondary, rest | Static (non-Auto) two-tier: primary 55% × 100% left; secondary 45% × 40% top-right; rest equal-stack the 45% × 60% bottom-right. |

The dropdown miniatures highlight slots 0/1/2 with decreasing emphasis so the user can see the rank-to-slot mapping. Add all three to `DROPDOWN_ORDER` in the Auto/multi-slot cluster.

### 5. Persistence

- `paneOrderByTab` is **already** persisted (AppShellMount L110, L159, L1611). After this change it's the **only** authority for layout importance, so the same field carries the user's drag order with no codec change.
- "Active pane" is **not persisted** in this plan — on reload it defaults to `paneOrder.firstOrNull()` (i.e. the first pane in the user's list). If a persistent active-pane signal turns out to be needed later, it's a single new field on `PersistedLayoutState`.
- Termtastic: zero changes. Its `ElectronIpcPersister` already round-trips the JSON blob through the server's flat KV via the existing `Persister` interface. Notegrow: zero changes (same path through `LocalStoragePersister`).

### 6. Sidebar UX details

- Drag affordance: **whole row** is draggable. A short threshold (≥4 px movement before `dragstart` is committed) prevents micro-movements during a click from starting a drag; the existing click handler still wins for taps. Cursor switches to `grab` on hover, `grabbing` during drag.
- Keyboard reorder: out of scope for the first cut.
- Cross-tab drag: rejected (drop on a different tab's section is an `event.preventDefault()` no-op).
- A11y: `aria-roledescription="draggable pane row"` on each row + a live-region announcement on drop ("Moved <label> to position N").

## Verification

End-to-end manual checks (no test infra in toolkit-web that exercises drag — the rest of the codebase verifies via running apps):

1. **Notegrow web** (`pnpm dev` in `notegrow/main`):
   - Open a tab with ≥3 panes. Drag the third pane to the top in the sidebar.
   - Pick `Auto` from the layout dropdown. Confirm the dragged pane now occupies the primary slot.
   - Click a different pane in the sidebar. Confirm sidebar highlight moves but pane sizes do **not** change.
   - Reload the page. Confirm the pane order persists; first pane is highlighted on reload.
   - Hand-resize a pane (transitions to `Custom`). Reorder in sidebar. Confirm geometry untouched.
2. **Termtastic** (`pnpm dev` in `termtastic/main`, with the JVM server):
   - Same flow. Additionally, kill and restart the server; confirm `paneOrderByTab` round-trips through the server `SettingsPersister`.
3. **New presets** (any app):
   - Pick `AutoBigTwo` / `AutoBigThree` / `HeroPair`. With 2/3/4/6 panes, confirm slot assignments match the spec sketches and that slots 0/1/2 follow the sidebar order.
4. **Edge cases**:
   - Drag a pane onto itself → no change, no `onChange` fired.
   - Drag during a layout dropdown open → drag wins (dropdown stays open or closes per existing dropdown rules; nothing wedges).
   - Single-pane tab → no drag affordance shown (or shown but no-op).

## Out of scope

- Right-sidebar / non-tab pane lists (only the tab-section pane rows on the **left** sidebar reorder).
- Persisting the active pane across reloads.
- Drag a pane between tabs.
- Touch / pointer events beyond mouse (HTML5 DnD covers pointer; mobile-tier polish later).
- Reordering tabs themselves (this plan is panes-within-a-tab only).
