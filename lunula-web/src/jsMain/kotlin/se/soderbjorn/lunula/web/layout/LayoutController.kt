/**
 * Per-tab state holder + pure operations for the toolkit's layout
 * system. The controller owns the bits of layout state the apps care
 * about — which preset is active, the importance order of panes, and
 * the parent-pane linkage recorded at creation — and exposes a small
 * set of operations that drive the auto re-tile algorithm.
 *
 * The controller deliberately does **not** own the list of
 * [FloatingPaneSpec]s; that stays with the host app, which already
 * persists it. Instead, the host calls
 * [LayoutController.applyPresetToPanes] when it wants the current
 * preset reflected in the spec list, and the controller returns a
 * fresh list with each pane's geometry updated.
 *
 * This keeps the toolkit's existing "stateless renderer" model intact:
 * the host owns the source of truth, the controller owns the
 * importance state, and the renderer paints whatever the host hands it.
 *
 * Auto-mode behaviour is the user-facing reason this class exists —
 * see the class kdoc body for the [LayoutPreset.Auto] semantics.
 *
 * @see LayoutPreset.Auto
 * @see LayoutPreset.Custom
 * @see GridSpec
 */
package se.soderbjorn.lunula.web.layout

/**
 * Stateful helper that owns the importance ordering of panes within
 * one tab and the active layout preset. Used by both notegrow and
 * termtastic clients to drive auto re-tiling.
 *
 * **Importance order.** [paneOrder] is the single ordered list that
 * drives slot assignment when a preset is applied. Slot 0 (largest /
 * primary) goes to the head of the list. The order is decoupled from
 * focus: it only changes when the user explicitly reorders panes (via
 * the sidebar drag handle), or when panes are added or removed.
 *
 * - **User reorder** ([reorderPane]): a sidebar drag-and-drop moves a
 *   pane to a new index in the importance list.
 * - **Create** ([recordCreate]): the new pane is appended at the tail
 *   of the order — it's the least-important by default, so it doesn't
 *   disturb the user's hand-arranged ordering. The parent pane (active
 *   at creation time) is recorded in [parentByPane] for downstream
 *   heuristics but does not move in the order.
 * - **Remove** ([recordRemove]): the closed pane is dropped from the
 *   order and from [activePaneId] if it matched.
 *
 * **Active pane.** [activePaneId] tracks "which pane the user clicked
 * last" purely for sidebar highlighting and other focus-aware UI. It
 * does **not** influence layout — the order does. Set via [setActive].
 *
 * **Custom transition.** Manual move / resize gestures are
 * preset-overriding — the controller transitions [activePreset] to
 * [LayoutPreset.Custom] when the host calls [markCustom]. The user
 * picks a preset again from the dropdown to leave Custom mode.
 *
 * The controller is per-tab; hosts that have multiple tabs keep one
 * instance per tab (or compose them in a map keyed by tab id).
 *
 * @param initialPreset preset the controller starts in. Defaults to
 *   [LayoutPreset.Custom] — "no preset is driving" — so a host that
 *   loads previously hand-placed panes doesn't get an unwanted
 *   re-tile on startup.
 * @param grid optional snap grid that [applyPresetToPanes] passes to
 *   [LayoutPreset.computeBoxes]. `null` disables snapping. Hosts
 *   typically pass a grid that matches their drag-handler snap.
 * @param onChange invoked whenever the controller's state mutates —
 *   preset change, pane order change, parent linkage change. Hosts use
 *   it to trigger persistence + re-render. Default no-op.
 */
class LayoutController(
    initialPreset: LayoutPreset = LayoutPreset.Custom,
    val grid: GridSpec? = null,
    val onChange: () -> Unit = {},
) {
    private val _paneOrder: MutableList<PaneId> = ArrayList()
    private val _parentByPane: MutableMap<PaneId, PaneId> = HashMap()
    private var _activePreset: LayoutPreset = initialPreset
    private var _activePaneId: PaneId? = null

    /**
     * Importance order of panes within this tab; head is the most
     * important (primary slot). The list is read-only; mutate via
     * [reorderPane], [recordCreate], or [recordRemove].
     */
    val paneOrder: List<PaneId> get() = _paneOrder

    /**
     * Pane the user has most recently focused (e.g. clicked) within
     * this tab, or `null` if no pane has been activated yet. Used by
     * the host purely as a UI signal — for the sidebar highlight and
     * for routing keyboard / focus events to the right pane. Decoupled
     * from layout: changing the active pane never reorders [paneOrder]
     * and never re-tiles. Mutated via [setActive].
     */
    val activePaneId: PaneId? get() = _activePaneId

    /**
     * Parent linkage recorded by [recordCreate]: the pane active when
     * a new pane was spawned. Available to host apps for future
     * heuristics (e.g. "place sibling next to parent"); not consumed
     * by the auto preset today.
     */
    val parentByPane: Map<PaneId, PaneId> get() = _parentByPane

    /**
     * Currently active preset. [LayoutPreset.Custom] means "no preset
     * is driving — the user has hand-tweaked geometry." Changes via
     * [setPreset] or [markCustom].
     */
    val activePreset: LayoutPreset get() = _activePreset

    /**
     * Replaces the current preset and fires [onChange]. Use this when
     * the user picks a preset from the dropdown. The host typically
     * follows with a call to [applyPresetToPanes] to reshape geometry.
     *
     * @param preset new preset; passing the current preset is a no-op
     *   that does not fire [onChange].
     */
    fun setPreset(preset: LayoutPreset) {
        if (_activePreset == preset) return
        _activePreset = preset
        onChange()
    }

    /**
     * Marks the controller as having user-overridden geometry by
     * transitioning [activePreset] to [LayoutPreset.Custom]. The host
     * calls this from manual drag-to-move / drag-to-resize handlers
     * so subsequent pane add/remove events don't re-tile with the
     * old preset.
     *
     * Idempotent when already in Custom mode.
     */
    fun markCustom() {
        if (_activePreset == LayoutPreset.Custom) return
        _activePreset = LayoutPreset.Custom
        onChange()
    }

    /**
     * Records that the user activated [paneId] (e.g. clicked it in the
     * sidebar or pressed its header). Updates [activePaneId] only —
     * importantly, **does not** mutate [paneOrder] and does not re-tile.
     * The host calls this from click / mousedown handlers and on
     * snapshot updates that surface a new active pane.
     *
     * Fires [onChange] when the active pane actually changes so the
     * host can repaint the sidebar highlight.
     *
     * @param paneId the pane that just became active, or `null` to
     *   clear the active selection (e.g. when the active pane was
     *   removed and no replacement has been chosen yet).
     */
    fun setActive(paneId: PaneId?) {
        if (_activePaneId == paneId) return
        _activePaneId = paneId
        onChange()
    }

    /**
     * Same as [setActive] but does **not** fire [onChange]. Use this when
     * the caller is reacting to a mid-gesture focus signal (e.g. a
     * capture-phase `mousedown` on a pane) and a host-side `rerender()`
     * triggered through `onChange` would replace the very DOM node the
     * user is pressing — causing the subsequent `click` to be lost.
     *
     * Callers are responsible for any persistence and any UI repaint
     * (e.g. sidebar highlight) that they would otherwise have got for
     * free from `onChange`.
     *
     * @param paneId the pane that just became active, or `null` to
     *   clear the active selection.
     */
    fun setActiveQuiet(paneId: PaneId?) {
        if (_activePaneId == paneId) return
        _activePaneId = paneId
    }

    /**
     * Moves [paneId] to [targetIndex] in the importance order. Called
     * by the host's sidebar drag-and-drop handler when the user
     * rearranges panes. The target index is clamped to the current
     * order's bounds; passing the pane's existing index is a no-op.
     *
     * If [paneId] is not currently in the order it's inserted at
     * [targetIndex] (defensive — shouldn't happen in practice but lets
     * the host reorder a freshly-created pane without first calling
     * [recordCreate]).
     *
     * Fires [onChange] when the order changes so the host can persist
     * the new order and, when [activePreset] is [LayoutPreset.Auto],
     * retile.
     *
     * @param paneId      the pane being moved.
     * @param targetIndex destination index, clamped to `[0, size]`.
     */
    fun reorderPane(paneId: PaneId, targetIndex: Int) {
        val current = _paneOrder.indexOf(paneId)
        val tracked = current >= 0
        // After we'd remove the existing entry the list shrinks by one;
        // clamp to that smaller range so a "drop at end" stays valid.
        val maxIndex = if (tracked) _paneOrder.size - 1 else _paneOrder.size
        val clamped = targetIndex.coerceIn(0, maxIndex.coerceAtLeast(0))
        if (tracked && current == clamped) return
        if (tracked) _paneOrder.removeAt(current)
        _paneOrder.add(clamped, paneId)
        onChange()
    }

    /**
     * Records the creation of a new pane spawned from [parentPaneId].
     * Appends the new pane at the **tail** of [paneOrder] — least
     * important by default, so a freshly-spawned pane never disturbs
     * the user's hand-arranged ordering. The user can drag it up in
     * the sidebar if they want it more prominent.
     *
     * The parent pane is recorded in [parentByPane] for downstream
     * heuristics (e.g. "place sibling near parent") but is not moved
     * in the order.
     *
     * The host is still responsible for actually creating the
     * [FloatingPaneSpec] and adding it to its float list; this method
     * only updates the controller's state.
     *
     * @param newPaneId    id of the freshly-created pane.
     * @param parentPaneId the pane that was active at creation time,
     *   or `null` when no parent context exists.
     */
    fun recordCreate(newPaneId: PaneId, parentPaneId: PaneId?) {
        // Append at the tail only when the pane is not already tracked.
        //
        // A pane that is ALREADY in [paneOrder] is not, in fact, new — it was
        // just seeded there by a [reset] (e.g. a world switch loading its saved
        // per-world layout, or boot hydration from persistence). Re-appending it
        // (remove-then-add) would drag it to the tail and destroy the very order
        // that reset just restored — the host's diff loop calls recordCreate for
        // *every* pane of a freshly-appeared tab, so on a world switch that
        // rewrote the whole saved importance order to raw snapshot order and the
        // sidebar rows visibly re-sorted (the world-switch flicker). Leaving a
        // tracked pane untouched keeps its restored slot; the no-duplicate
        // invariant is preserved either way (we only add when absent). A
        // genuinely new pane (not in the order) still lands at the tail — least
        // important by default — so it never disturbs the user's arrangement.
        if (newPaneId !in _paneOrder) _paneOrder.add(newPaneId)
        if (parentPaneId != null && parentPaneId != newPaneId) {
            _parentByPane[newPaneId] = parentPaneId
        }
        onChange()
    }

    /**
     * Drops [paneId] from the importance order and parent linkage.
     * The host calls this when the user closes a pane.
     *
     * Any pane whose recorded parent was [paneId] keeps the linkage
     * pointing at the now-closed pane; the entry is harmless once the
     * child itself is closed and only used as a tie-break by future
     * heuristics.
     *
     * @param paneId the pane that was just closed.
     */
    fun recordRemove(paneId: PaneId) {
        val removed = _paneOrder.remove(paneId)
        val unlinked = _parentByPane.remove(paneId) != null
        val wasActive = _activePaneId == paneId
        if (wasActive) _activePaneId = null
        if (removed || unlinked || wasActive) onChange()
    }

    /**
     * Resets the controller's state to match a known set of panes —
     * used on host startup when the persisted layout is hydrated.
     *
     * Restores [paneOrder] verbatim (head = most important) and clears
     * [parentByPane] and [activePaneId]. The host emits its own
     * [setActive] call once it knows which pane should appear focused
     * (typically the previously-active one if persisted, or the head
     * of the order as a fallback).
     *
     * @param panes pane ids in importance order, head first. Empty
     *   list clears all state.
     */
    fun reset(panes: List<PaneId>) {
        val changed = _paneOrder != panes || _parentByPane.isNotEmpty() || _activePaneId != null
        _paneOrder.clear()
        _paneOrder.addAll(panes)
        _parentByPane.clear()
        _activePaneId = null
        if (changed) onChange()
    }

    /**
     * Applies the current preset to a list of pane specs and returns
     * the result. Pure function — does not mutate the controller's
     * state, does not fire [onChange].
     *
     * Slot assignment follows [paneOrder]: the head of [paneOrder]
     * goes to slot 0 (largest), the next to slot 1, etc. Panes in
     * [panes] that aren't in [paneOrder] are appended at the tail
     * preserving their existing relative order — useful when the host
     * has just loaded panes from persistence and hasn't yet emitted a
     * focus event for them.
     *
     * Minimised panes are not laid out (the renderer omits them);
     * they're returned unchanged at the end of the list.
     *
     * If [activePreset] is [LayoutPreset.Custom] the input list is
     * returned unchanged — Custom means "don't drive geometry."
     *
     * @param panes  current floating pane specs from the host.
     * @return a new list with geometry fields updated to match the
     *   current preset; non-geometry fields (id, title, zIndex,
     *   isMaximized, isMinimized) are preserved per pane.
     */
    fun applyPresetToPanes(panes: List<FloatingPaneSpec>): List<FloatingPaneSpec> {
        if (_activePreset == LayoutPreset.Custom) return panes

        val visible = panes.filter { !it.isMinimized }
        val hidden = panes.filter { it.isMinimized }
        if (visible.isEmpty()) return panes

        val byId = visible.associateBy { it.id }
        val ordered = ArrayList<FloatingPaneSpec>(visible.size)
        // Start with paneOrder entries that exist in the spec list.
        for (id in _paneOrder) {
            byId[id]?.let { ordered.add(it) }
        }
        // Append any visible panes the controller hasn't seen yet.
        for (spec in visible) {
            if (ordered.none { it.id == spec.id }) ordered.add(spec)
        }

        val boxes = _activePreset.computeBoxes(ordered.size, grid)
        val laidOut = ordered.mapIndexed { i, spec ->
            val box = boxes[i]
            spec.copy(
                xPct = box.x,
                yPct = box.y,
                widthPct = box.width,
                heightPct = box.height,
            )
        }
        return laidOut + hidden
    }
}
