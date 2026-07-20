/**
 * DOM renderer for a [PaneLayout]'s float list.
 *
 * Walks [PaneLayout.floatingPanes] and produces one absolutely-positioned
 * `.dt-pane` per spec, stacked by `--dt-fp-z`. Apps supply a
 * [PaneCallbacks.contentRenderer] callback that, given a [PaneId] and an
 * already-attached `.dt-pane-content` slot, fills the slot with the pane's
 * content. A [PaneCallbacks.paneHeader] callback is also supplied to build
 * the per-pane title bar; it returns a [PaneHeaderSpec] which the renderer
 * turns into DOM via [renderPaneHeader] and wires for inline rename, action
 * clicks, and drag-to-move on the header.
 *
 * The renderer is **stateless** — call [render] whenever the layout changes
 * and it produces a fresh DOM subtree. Apps put [LayoutRenderer] inside
 * their own re-render loop, MutationObserver-driven flow, or just call
 * [render] manually after each pane mutation.
 *
 * Visual styles ride on the `.dt-pane*` classes shipped in
 * `lunula.css`; consumers must call `injectLunulaStyles()`
 * once at boot.
 *
 * @see PaneLayout
 * @see FloatingPaneSpec
 * @see PaneHeaderSpec
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings
import se.soderbjorn.lunula.web.hotkey.StandardHotkeys
import se.soderbjorn.lunula.web.hotkey.ToolkitHotkeyIds
import se.soderbjorn.lunula.web.confirmClosePane

/**
 * Per-pane callbacks provided to [LayoutRenderer].
 *
 * @property contentRenderer  given a pane id and the pane's content slot
 *   (`.dt-pane-content`, already attached to the live document), fills the
 *   slot with the pane's content. Called once per pane per [LayoutRenderer.render]
 *   call. The slot is attached *before* this callback runs, so any DOM
 *   measurements the host performs (e.g. reading `clientWidth` to compute
 *   a wrap width) reflect real layout.
 * @property paneHeader       given a pane id and its (optional) title,
 *   returns a [PaneHeaderSpec] that the renderer converts to DOM. The
 *   default is [defaultPaneHeader] which produces a title-only header.
 *   Apps that want rename / nav actions build a richer spec — typically
 *   using [PaneActions] factories.
 * @property onPaneFocused    fired whenever [LayoutRenderer.focusPane] is
 *   invoked — both when the user clicks (mousedown) inside a pane and when
 *   a host explicitly drives focus (e.g. a sidebar pane list). Fires *even
 *   when the focused id didn't change* so hosts that mirror focus state
 *   (sidebar highlights, persistence) get a definitive signal on every
 *   user-confirmed focus action; the alternative — skipping the callback
 *   on idempotent calls — leaves host state stuck at whatever was recorded
 *   before, which is the wrong answer when the user just told us "I want
 *   *this* pane focused". The renderer also drives the
 *   [LayoutClassNames.PANE_FOCUSED] class on the matching `.dt-pane`
 *   element directly, so hosts only need to wire this if they want to
 *   persist focus or react to it. Receives the pane id of the newly
 *   focused pane. Default no-op.
 * @property onFloatingMoved  fired while the user drags a pane's header.
 *   Receives the pane id and the new top-left position as fractions of the
 *   renderer container. `null` to disable drag (the pane stays pinned to
 *   its spec).
 * @property onFloatingResized fired while the user drags a pane's
 *   bottom-right corner. Receives the pane id and the new size as fractions
 *   of the renderer container. `null` to disable resize.
 * @property onFloatingFocused fired on a single primary-button `click`
 *   (and, redundantly, `dblclick`) anywhere inside a pane — the "raise"
 *   gesture. Hosts should bump the pane's [FloatingPaneSpec.zIndex] to
 *   `max(existing) + 1` so the clicked pane comes to the front. Raise is
 *   deferred to `click` rather than `mousedown` so a drag/resize gesture
 *   (which suppresses the trailing `click`) never triggers it mid-motion;
 *   `mousedown` still marks the pane focused (via [onPaneFocused])
 *   immediately. Any stationary click brings a background pane forward —
 *   no double-click and no titlebar targeting required. Default no-op.
 * @property onFloatingClosed fired when the user clicks the close action
 *   in a pane's header. Hosts remove the matching entry from
 *   [PaneLayout.floatingPanes]. `null` hides the close button.
 * @property onFloatingMaximizeToggled fired when the user clicks the
 *   maximize/restore toggle. Hosts flip the matching
 *   [FloatingPaneSpec.isMaximized] flag and re-render. `null` hides the
 *   maximize button entirely.
 * @property onFloatingMaximizeCleared fired by [focusPane] when the
 *   focus target differs from the currently-maximized pane. Hosts flip
 *   the matching [FloatingPaneSpec.isMaximized] flag back to `false`
 *   and re-render; the renderer's existing maximize-restore CSS
 *   transition animates the unmaximize so the user sees the previously
 *   hidden pane appear smoothly. Receives the pane id of the maximized
 *   pane that needs unmaximizing. `null` to leave the maximized pane
 *   on top — focus still moves but the target pane stays hidden, which
 *   is the pre-existing behaviour. Default `null`.
 * @property onFloatingMinimized fired when the user clicks the minimize
 *   action. Hosts flip the matching [FloatingPaneSpec.isMinimized] flag
 *   (which drops the pane from the layout and parks a chip in the dock —
 *   see [LayoutRenderer.render]). `null` hides the minimize button.
 * @property onFloatingRestored fired when the user clicks a dock chip (or
 *   its restore button) for a minimized pane. Hosts clear the matching
 *   [FloatingPaneSpec.isMinimized] flag and re-render; the pane re-enters
 *   the layout at its preserved geometry. A separate entry point from
 *   [onFloatingMinimized] is required because a minimized pane has no
 *   chrome header in the layout — the dock chip is the only restore
 *   affordance. `null` makes the dock chips inert (no restore).
 * @property confirmFloatingClose when `true` (the default) the close action
 *   shows a [confirmClosePane] dialog before invoking [onFloatingClosed],
 *   using the pane's title (or "this pane" if untitled). Apps that already
 *   wrap their own confirmation around close should pass `false`. Has no
 *   effect when [onFloatingClosed] is `null`.
 */
class PaneCallbacks(
    val contentRenderer: (PaneId, HTMLElement) -> Unit,
    val paneHeader: (PaneId, String?) -> PaneHeaderSpec = { id, title ->
        defaultPaneHeader(id, title)
    },
    val onPaneFocused: (PaneId) -> Unit = { _ -> },
    val onFloatingMoved: ((PaneId, xPct: Double, yPct: Double) -> Unit)? = null,
    val onFloatingResized: ((PaneId, widthPct: Double, heightPct: Double) -> Unit)? = null,
    val onFloatingFocused: ((PaneId) -> Unit)? = null,
    val onFloatingClosed: ((PaneId) -> Unit)? = null,
    val onFloatingMaximizeToggled: ((PaneId) -> Unit)? = null,
    val onFloatingMaximizeCleared: ((PaneId) -> Unit)? = null,
    val onFloatingMinimized: ((PaneId) -> Unit)? = null,
    val onFloatingRestored: ((PaneId) -> Unit)? = null,
    val confirmFloatingClose: Boolean = true,
)

/** Toolkit DOM class names used by the renderer (also referenced by stylesheets). */
object LayoutClassNames {
    const val ROOT = "dt-pane-root"

    /**
     * Inner positioning context the renderer mounts inside [ROOT]. Holds
     * every absolutely-positioned `.dt-pane-floating` plus the resize
     * separators. Split out from [ROOT] so the dock ([DOCK]) can sit as a
     * flow sibling *below* the pane area and genuinely reserve vertical
     * space, rather than overlay the bottom of the panes. The pane area
     * flex-grows; the dock flex-shrinks to its content.
     */
    const val PANE_AREA = "dt-pane-area"

    /**
     * Flow-row strip rendered under [PANE_AREA] when ≥1 floating pane is
     * minimized. Hosts a parked-title-bar chip ([DOCK_ITEM]) per minimized
     * pane. Absent (not rendered) when nothing is minimized, so no empty
     * bar sits at the bottom.
     */
    const val DOCK = "dt-pane-dock"

    /**
     * One chip in the [DOCK] — a parked title bar for a minimized pane.
     * Shows the pane's icon + label and a restore button; clicking the chip
     * body (or the button) fires [PaneCallbacks.onFloatingRestored].
     */
    const val DOCK_ITEM = "dt-pane-dock-item"
    const val PANE = "dt-pane"

    /** @see PaneHeaderClassNames.HEADER — kept here for backward source compatibility. */
    const val PANE_HEADER = "dt-pane-header"

    /** @see PaneHeaderClassNames.TITLE */
    const val PANE_TITLE = "dt-pane-title"
    const val PANE_CONTENT = "dt-pane-content"
    const val PANE_PLACEHOLDER = "dt-pane-placeholder"

    /**
     * Added by [LayoutRenderer] to the pane currently considered "focused"
     * — the most recently clicked one, or the first pane on initial render
     * when no other has been clicked. The toolkit stylesheet paints an
     * accent outline on the matching `.dt-pane`.
     */
    const val PANE_FOCUSED = "dt-pane-focused"

    /**
     * Added by [LayoutRenderer] to every rendered pane (entries from
     * [PaneLayout.floatingPanes]). The toolkit stylesheet positions
     * marked panes absolutely from the inline `--dt-fp-x / -y / -w / -h`
     * CSS variables and stacks them by `--dt-fp-z`.
     */
    const val PANE_FLOATING = "dt-pane-floating"

    /** Resize handle CSS class — one per corner so styles can position each
     *  handle independently and apply per-corner cursors. */
    const val CORNER_RESIZE = "dt-pane-corner-resize"
    const val CORNER_RESIZE_TL = "dt-pane-corner-resize-tl"
    const val CORNER_RESIZE_TR = "dt-pane-corner-resize-tr"
    const val CORNER_RESIZE_BL = "dt-pane-corner-resize-bl"
    const val CORNER_RESIZE_BR = "dt-pane-corner-resize-br"
}

/**
 * One of the four pane corners a resize handle can be anchored to.
 * The opposite corner stays fixed during the drag — e.g. dragging
 * the [TopLeft] handle moves the pane's origin while the bottom-right
 * stays put.
 */
enum class Corner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    ;

    /** Returns the CSS class that styles this corner's handle. */
    val cssClass: String get() = when (this) {
        TopLeft -> LayoutClassNames.CORNER_RESIZE_TL
        TopRight -> LayoutClassNames.CORNER_RESIZE_TR
        BottomLeft -> LayoutClassNames.CORNER_RESIZE_BL
        BottomRight -> LayoutClassNames.CORNER_RESIZE_BR
    }
}

/**
 * Stateless renderer that turns a [PaneLayout] into a DOM subtree.
 *
 * Apps create one instance per layout area and call [render] every time
 * their pane state changes; the renderer wipes the container and builds
 * a fresh subtree.
 */
class LayoutRenderer(
    /** The root container that the rendered subtree is mounted into. */
    val container: HTMLElement,
    /** Per-pane content + header + window-control callbacks. */
    val callbacks: PaneCallbacks,
) {
    /**
     * Id of the pane currently flagged as focused (i.e. the one that gets
     * the [LayoutClassNames.PANE_FOCUSED] outline). Survives re-renders so
     * the focus ring doesn't blink off when the float list mutates around
     * the focused pane. `null` until [render] sees a layout containing
     * panes or the user clicks a pane.
     */
    private var focusedLeafId: PaneId? = null

    /**
     * Per-pane snapshot of the previous render's `isMaximized` flag. Used
     * by [buildFloatingPane] to stage the maximize/restore CSS transition:
     * when the flag flipped between renders, the new pane element is
     * mounted in the OLD state, then a `requestAnimationFrame` tick
     * toggles it to the NEW state so the browser actually animates
     * `left/top/width/height` instead of snapping (the renderer rebuilds
     * every pane element on each render, so without staging there's no
     * "from" geometry to transition from). Mirrors termtastic's
     * `previousMaximizedStates` trick in `LayoutBuilder.kt`.
     */
    private val previousMaximizedStates: MutableMap<PaneId, Boolean> = mutableMapOf()

    /**
     * Whether [render] has run at least once. Gates the new-pane entry
     * animation in [buildFloatingPane]: on the very first render of
     * the renderer's lifetime we don't want every pane to pop-in
     * (that would animate the whole layout on app boot or the first
     * paint of every tab); we only animate panes that appear in a
     * subsequent render relative to a previous render's pane set.
     */
    private var hasRenderedOnce: Boolean = false

    /**
     * Most recently rendered "context" — typically the active tab id.
     * When it changes between calls to [render], the renderer suppresses
     * entry/exit animations: a tab switch is conceptually a fresh
     * render, not an "add/remove panes" event. Same-context renders
     * (pane added, closed, maximize-toggled within one tab) animate
     * normally.
     */
    private var lastContextKey: String? = null

    /**
     * Set true at the start of a context-changed [render] call so
     * [buildFloatingPane] skips both the maximize-restore stage and
     * the entry pop-in. Reset to false at end of [render].
     */
    private var suppressAnimationsForThisRender: Boolean = false

    /**
     * CSS class on closing-ghost elements. Tagged so [render]'s wipe
     * loop can preserve them across subsequent renders (so an
     * unrelated render firing within the 280ms close animation
     * doesn't snap the ghost out of existence).
     */
    private val CLOSING_GHOST_CLASS = "dt-pane-closing-ghost"

    /**
     * Most recently rendered layout. Captured at the top of [render] and
     * read by the [StandardHotkeys.NextPane] / [StandardHotkeys.PreviousPane]
     * handlers installed in [init], so cycling always reflects the latest
     * pane list without the host having to re-register hotkeys.
     */
    private var lastLayout: PaneLayout = PaneLayout()

    /**
     * Inner element that holds the absolutely-positioned panes + resize
     * separators. Sits inside [container] (which carries the
     * [LayoutClassNames.ROOT] class and is laid out as a flex column);
     * the dock — when present — is appended after this as a flow sibling
     * so it reserves space below the panes instead of overlaying them.
     *
     * All pane DOM operations (wipe, append, query, focus-class, and the
     * drag/resize geometry math) target [paneArea], not [container]; pane
     * geometry fractions are therefore measured against this element's box.
     */
    private val paneArea: HTMLElement = (document.createElement("div") as HTMLElement).also {
        it.className = LayoutClassNames.PANE_AREA
    }

    init {
        if (!container.classList.contains(LayoutClassNames.ROOT)) {
            container.classList.add(LayoutClassNames.ROOT)
        }
        container.appendChild(paneArea)
        // Register spatial pane navigation hotkeys against this renderer.
        // Ctrl+Opt+Arrow (by default) moves focus to the nearest pane in
        // the pressed direction (see [focusPaneInDirection]); there is no
        // wrap-around. Registration goes through [HotkeyBindings] so the
        // user's custom chords (persisted under
        // `PersistKeys.HOTKEY_BINDINGS`) override the defaults; re-
        // registering on each LayoutRenderer construction (e.g. on tab
        // switch) replaces the handler so the binding always targets the
        // live renderer — no per-renderer unregister cost and no risk of
        // stale renderers handling keys after a tab change.
        HotkeyBindings.registerAction(
            HotkeyActionSpec(ToolkitHotkeyIds.PANE_FOCUS_LEFT, "Focus pane left", listOf(StandardHotkeys.PreviousPane))
        ) { focusPaneInDirection(Direction.LEFT) }
        HotkeyBindings.registerAction(
            HotkeyActionSpec(ToolkitHotkeyIds.PANE_FOCUS_RIGHT, "Focus pane right", listOf(StandardHotkeys.NextPane))
        ) { focusPaneInDirection(Direction.RIGHT) }
        HotkeyBindings.registerAction(
            HotkeyActionSpec(ToolkitHotkeyIds.PANE_FOCUS_UP, "Focus pane up", listOf(StandardHotkeys.FocusPaneUp))
        ) { focusPaneInDirection(Direction.UP) }
        HotkeyBindings.registerAction(
            HotkeyActionSpec(ToolkitHotkeyIds.PANE_FOCUS_DOWN, "Focus pane down", listOf(StandardHotkeys.FocusPaneDown))
        ) { focusPaneInDirection(Direction.DOWN) }
        // Pane state cycling: Opt+Cmd+Up expands one step (docked →
        // normal → maximized), Opt+Cmd+Down collapses one step
        // (maximized → normal → docked). Implemented on top of the
        // host's existing maximize/minimize/restore callbacks, so any
        // host that wires those into [PaneCallbacks] gets the chords
        // for free; hosts that leave a callback null simply lose the
        // corresponding step (matching the hidden header button).
        HotkeyBindings.registerAction(
            HotkeyActionSpec(ToolkitHotkeyIds.PANE_EXPAND, "Expand pane", listOf(StandardHotkeys.ExpandPane))
        ) { cyclePaneStateUp() }
        HotkeyBindings.registerAction(
            HotkeyActionSpec(ToolkitHotkeyIds.PANE_COLLAPSE, "Collapse pane", listOf(StandardHotkeys.CollapsePane))
        ) { cyclePaneStateDown() }
    }

    /**
     * Renders [layout] into the container, replacing any prior content.
     *
     * The skeleton (empty pane wrappers) is built and attached to
     * [container] first; only then are per-pane
     * [PaneCallbacks.contentRenderer] callbacks invoked and their results
     * appended into the prepared content slots. This ordering matters for
     * content renderers that measure their host element synchronously
     * (e.g. computing a wrap width from `clientWidth`) — at that point the
     * pane is already in the live document and the measurement reflects
     * the real layout instead of zero.
     *
     * Minimised panes are skipped — the host surfaces them in its sidebar
     * / overflow menu so the user can restore them.
     *
     * @param layout the layout to render
     * @param contextKey optional identifier of the render context (e.g.
     *   the active tab id). When this changes between calls the
     *   renderer suppresses entry/exit animations and skips closing
     *   ghosts — a context switch is not an "add/remove panes" event.
     * @param suppressSeparators when `true`, skips emission of the
     *   invisible draggable separator bars between adjacent panes.
     *   Hosts pass `true` while a non-Custom preset (notably
     *   [LayoutPreset.Auto]) is active, since the preset reclaims
     *   geometry on every pane change and a manually-dragged separator
     *   would be undone immediately. Defaults to `false`.
     */
    fun render(
        layout: PaneLayout,
        contextKey: String? = null,
        suppressSeparators: Boolean = false,
    ) {
        val previousLayout = lastLayout
        val contextChanged = contextKey != null && contextKey != lastContextKey

        // Header-only fast path: when the new layout differs from the
        // previous one only in per-pane header content (typical for
        // termtastic's cwd-driven title updates), patch the existing pane
        // wrappers' header children instead of rebuilding the wrappers.
        // A full rebuild would detach and reattach each pane's
        // `.dt-pane-content` slot, which the browser treats as removing
        // the focused element from the document — that's why an idle
        // xterm loses focus when its titlebar refreshes after `cd`. The
        // fast path leaves the content slot alone, so focus survives.
        // Skipped on context changes (tab switches): those need the full
        // suppress-animations + skeleton-rebuild dance.
        if (!contextChanged && canDoHeaderOnlyUpdate(previousLayout, layout)) {
            applyHeaderOnlyUpdate(layout)
            lastContextKey = contextKey
            lastLayout = layout
            return
        }

        suppressAnimationsForThisRender = contextChanged
        lastContextKey = contextKey
        lastLayout = layout

        // Identify panes that were in the previous render but aren't
        // in the current one. We'll repurpose their existing live DOM
        // elements as "closing ghosts" rather than rebuilding empty
        // replicas — that way the user sees the actual pane content
        // (e.g. notegrow's editor, termtastic's terminal output)
        // shrink + fade on close, instead of a blank rectangle that
        // can be hard to see against a similarly-coloured page
        // background. Skipped on context changes so tab switches
        // don't animate the previous tab's panes leaving.
        val visibleIds = layout.floatingPanes
            .filterNot { it.isMinimized }
            .map { it.id }
        val currentIds = visibleIds.toSet()
        val departingIds: Set<String> = if (!contextChanged) {
            previousLayout.floatingPanes
                .filterNot { it.isMinimized }
                .map { it.id }
                .filter { it !in currentIds }
                .toSet()
        } else emptySet()

        // FLIP groundwork for the minimize/restore animation. Snapshot the
        // current on-screen rect of every live pane and every dock chip
        // BEFORE the wipe/rebuild relocates them; keyed by pane id (a pane is
        // either a layout pane OR a dock chip in any given render, so no
        // collision). After the rebuild we animate the transitioning element
        // between its old rect (here) and its new rect.
        val prevRectById = HashMap<String, org.w3c.dom.DOMRect>()
        // Ids transitioning between layout and dock this render (skip on a
        // tab switch — that's a fresh paint, not a user minimize/restore).
        val prevMinimizedIds = previousLayout.floatingPanes
            .filter { it.isMinimized }.map { it.id }.toSet()
        val nowMinimizedIds = layout.floatingPanes
            .filter { it.isMinimized }.map { it.id }.toSet()
        val minimizingIds: Set<String> =
            if (!contextChanged) departingIds.filter { it in nowMinimizedIds }.toSet()
            else emptySet()
        val restoringIds: Set<String> =
            if (!contextChanged) prevMinimizedIds.filter { it in currentIds }.toSet()
            else emptySet()
        if (minimizingIds.isNotEmpty() || restoringIds.isNotEmpty()) {
            val paneEls = paneArea.querySelectorAll("[data-pane-id]")
            for (i in 0 until paneEls.length) {
                val el = paneEls.item(i) as? HTMLElement ?: continue
                if (el.classList.contains(CLOSING_GHOST_CLASS)) continue
                el.getAttribute("data-pane-id")?.let { prevRectById[it] = el.getBoundingClientRect() }
            }
            val chipEls = container.querySelectorAll(".${LayoutClassNames.DOCK_ITEM}")
            for (i in 0 until chipEls.length) {
                val el = chipEls.item(i) as? HTMLElement ?: continue
                el.getAttribute("data-dock-pane-id")?.let { prevRectById[it] = el.getBoundingClientRect() }
            }
        }
        // Elements that should fly into the dock after the dock is built,
        // paired with the screen rect they were pinned at (their FLIP
        // start rect — captured before the dock resizes the pane area).
        val minimizingGhosts = HashMap<String, HTMLElement>()
        val minimizingGhostRects = HashMap<String, org.w3c.dom.DOMRect>()

        // Wipe non-ghost, non-departing children. Ghosts (already in
        // mid-animation) stay; departing panes are left in place and
        // marked as ghosts in the next pass so they animate out.
        val toRemove = mutableListOf<org.w3c.dom.Node>()
        val toAnimateOut = mutableListOf<HTMLElement>()
        var node = paneArea.firstChild
        while (node != null) {
            val el = node as? HTMLElement
            when {
                el == null -> toRemove += node
                el.classList.contains(CLOSING_GHOST_CLASS) -> {
                    // already animating; leave alone
                }
                el.getAttribute("data-pane-id") in departingIds -> {
                    toAnimateOut += el
                }
                else -> toRemove += node
            }
            node = node.nextSibling
        }
        toRemove.forEach { paneArea.removeChild(it) }
        toAnimateOut.forEach { el ->
            val id = el.getAttribute("data-pane-id")
            if (id != null && id in minimizingIds) {
                // A minimizing pane flies into its dock chip (built below)
                // rather than shrinking into the screen centre. Preserve it
                // as a ghost so neither this render's separator/dock work nor
                // a follow-up render wipes it before the flight finishes.
                //
                // Freeze the ghost's box at its current px rect BEFORE
                // `renderDock` runs. The dock reserves vertical space, which
                // flex-shrinks the pane area — and since a floating pane is
                // sized in *percentages of the pane area*, that shrink would
                // both resize the ghost mid-flight and fire its own
                // `left/top/width/height` 220ms transition, fighting the FLIP
                // (the visible symptom: the pane appears to snap straight to
                // the dock instead of flying there). Replacing the `%` geometry
                // with explicit px (still `position: absolute` within the
                // pane area, so no transform-ancestor containing-block
                // surprises that `position: fixed` would risk) decouples the
                // ghost from the resize. The pane area hasn't shrunk yet at
                // this point — the dock is built below — so the offsets are
                // measured against its full size and stay valid for the flight.
                val rect = el.getBoundingClientRect()
                val areaRect = paneArea.getBoundingClientRect()
                el.classList.add(CLOSING_GHOST_CLASS)
                el.style.setProperty("pointer-events", "none")
                el.style.setProperty("--dt-fp-z", "9999")
                el.style.setProperty("transition", "none")
                el.style.margin = "0"
                el.style.left = "${rect.left - areaRect.left}px"
                el.style.top = "${rect.top - areaRect.top}px"
                el.style.width = "${rect.width}px"
                el.style.height = "${rect.height}px"
                minimizingGhosts[id] = el
                minimizingGhostRects[id] = rect
            } else {
                startCloseAnimation(el)
            }
        }
        val pendingPanes = mutableListOf<Pair<PaneId, HTMLElement>>()
        // Reconcile the renderer's focus memory with the layout we're about
        // to paint. If the previously-focused id is gone (closed or now
        // minimised), fall back to the first visible pane so the user
        // always sees an outline somewhere.
        if (focusedLeafId == null || focusedLeafId !in visibleIds) {
            focusedLeafId = visibleIds.firstOrNull()
        }
        for (float in layout.floatingPanes) {
            if (float.isMinimized) continue
            paneArea.appendChild(buildFloatingPane(float, pendingPanes))
        }
        // Dock: a flow strip under the pane area holding one chip per
        // minimized pane. Rebuilt from scratch each render (cheap: at most
        // a handful of chips) and only present when something is minimized,
        // so the bottom strip appears/disappears with the minimized set.
        renderDock(layout.floatingPanes.filter { it.isMinimized })
        // Minimize/restore FLIP. The dock chips and the laid-out panes are
        // now in the DOM, so their destination/source rects measure true.
        //  - Minimizing: the held ghost (full pane) flies + shrinks into its
        //    chip, fading out as it merges with the (already-visible) chip.
        //  - Restoring: the freshly-built pane starts at its old chip rect
        //    and grows out to its real geometry.
        for ((id, ghost) in minimizingGhosts) {
            // Prefer the rect the ghost was pinned at over the pre-wipe
            // snapshot — they agree, but the pinned rect is exactly the
            // ghost's current (fixed) box, keeping frame 0 a true identity.
            val from = minimizingGhostRects[id] ?: prevRectById[id]
            val chip = container.querySelector(
                ".${LayoutClassNames.DOCK_ITEM}[data-dock-pane-id=\"$id\"]"
            ) as? HTMLElement
            val to = chip?.getBoundingClientRect()
            if (from == null || to == null) {
                ghost.parentNode?.removeChild(ghost)
                continue
            }
            // Ghost's box rests at `from` (old pane rect); fly it to the
            // chip (`to`) and fade out so it merges into the visible chip.
            animateFlip(
                el = ghost,
                startRect = from,
                endRect = to,
                startOpacity = 1.0,
                endOpacity = 0.0,
                removeOnFinish = true,
            )
        }
        for (id in restoringIds) {
            val el = paneArea.querySelector("[data-pane-id=\"$id\"]") as? HTMLElement ?: continue
            val from = prevRectById[id] ?: continue
            val to = el.getBoundingClientRect()
            // Pane's box rests at `to` (real geometry); start it at the old
            // chip rect (`from`) and grow it out to its layout slot.
            animateFlip(
                el = el,
                startRect = from,
                endRect = to,
                startOpacity = 0.4,
                endOpacity = 1.0,
                removeOnFinish = false,
            )
        }
        // Drop stale entries so a long-running session doesn't accumulate
        // maximize-state snapshots for panes that no longer exist.
        val livePaneIds = layout.floatingPanes.map { it.id }.toSet()
        previousMaximizedStates.keys.retainAll(livePaneIds)
        hasRenderedOnce = true
        suppressAnimationsForThisRender = false
        // Skeleton is now attached to the live DOM tree; let each pane's
        // contentRenderer populate its slot in place. Because the slot is
        // already attached, anything the host appends to it (and queries
        // sizes on, e.g. `clientWidth`) sees real layout values.
        for ((paneId, contentSlot) in pendingPanes) {
            callbacks.contentRenderer(paneId, contentSlot)
        }

        // Invisible draggable separator bars between adjacent panes —
        // recomputed from scratch every render so the bars always match
        // the current geometry. Skipped when the host has signalled an
        // auto-tiling preset is in charge (manual drag would conflict),
        // when fewer than two panes are visible (no shared edges), or
        // when the host hasn't supplied a resize callback (without one
        // we can't apply the drag).
        val onResized = callbacks.onFloatingResized
        val onMoved = callbacks.onFloatingMoved
        if (!suppressSeparators && onResized != null && onMoved != null) {
            val visiblePanes = layout.floatingPanes.filterNot { it.isMinimized || it.isMaximized }
            val separators = computeSeparators(visiblePanes)
            mountSeparators(
                container = paneArea,
                separators = separators,
                callbacks = SeparatorCallbacks(
                    onFloatingMoved = onMoved,
                    onFloatingResized = onResized,
                    snap = ::snapPct,
                    minSize = FLOATING_MIN_SIZE,
                    paneById = { id -> lastLayout.floatingPanes.firstOrNull { it.id == id } },
                ),
            )
        } else {
            // Clear any prior separators so a switch to Auto / Maximized
            // immediately drops the bars instead of leaving stale ones in
            // the DOM until the next non-suppressed render.
            mountSeparators(paneArea, emptyList(), noopSeparatorCallbacks)
        }
    }

    /**
     * Rebuilds the dock under the pane area from [minimized].
     *
     * Removes any existing `.dt-pane-dock` first, then — when [minimized]
     * is non-empty — appends a fresh one to [container] (after [paneArea])
     * so it reads as a strip below the panes. Each minimized pane becomes
     * one [buildDockItem] chip. When [minimized] is empty nothing is
     * appended, so the dock disappears entirely (no empty bar).
     *
     * Called at the end of every full [render]; the header-only fast path
     * never changes the minimized set so it leaves the dock untouched.
     *
     * @param minimized the layout's minimized floating panes, in layout
     *   order. Empty means "no dock".
     */
    private fun renderDock(minimized: List<FloatingPaneSpec>) {
        (container.querySelector(".${LayoutClassNames.DOCK}") as? HTMLElement)
            ?.let { container.removeChild(it) }
        if (minimized.isEmpty()) return
        val dock = document.createElement("div") as HTMLElement
        dock.className = LayoutClassNames.DOCK
        for (spec in minimized) {
            dock.appendChild(buildDockItem(spec))
        }
        container.appendChild(dock)
    }

    /**
     * Builds one dock chip for the minimized pane [spec] — a parked title
     * bar showing the pane's leading icon + label and a restore button.
     *
     * Icon and label are pulled from the host's [PaneCallbacks.paneHeader]
     * callback (the same source the in-layout chrome header uses) so a
     * docked pane reads identically to its title bar. Clicking the chip
     * body or its restore button fires [PaneCallbacks.onFloatingRestored];
     * the host clears [FloatingPaneSpec.isMinimized] and re-renders, and
     * the pane animates back from the dock into its preserved geometry.
     *
     * @param spec the minimized pane to represent.
     * @return a detached `.dt-pane-dock-item` element with restore wired.
     */
    private fun buildDockItem(spec: FloatingPaneSpec): HTMLElement {
        val item = document.createElement("div") as HTMLElement
        item.className = LayoutClassNames.DOCK_ITEM
        // Not `data-pane-id`: hosts that focus on `data-pane-id` presses
        // (termtastic's pointerdown net) shouldn't treat a dock click as a
        // focus of a pane that isn't even in the layout — the restore
        // handler re-adds and focuses it instead.
        item.setAttribute("data-dock-pane-id", spec.id)
        val headerSpec = callbacks.paneHeader.invoke(spec.id, spec.title)

        headerSpec.leadingIcon?.let { iconHtml ->
            val icon = document.createElement("span") as HTMLElement
            icon.className = "${LayoutClassNames.DOCK_ITEM}-icon"
            icon.innerHTML = iconHtml
            item.appendChild(icon)
        }

        // Live status badge (e.g. a working spinner) — the same element the
        // chrome header would surface via `PaneHeaderSpec.leadingBadge`. A
        // minimized pane has no chrome header, so showing it on the chip
        // keeps the "this pane is busy" signal visible while it's docked.
        headerSpec.leadingBadge?.let { badge ->
            val slot = document.createElement("span") as HTMLElement
            slot.className = "${LayoutClassNames.DOCK_ITEM}-badge"
            slot.appendChild(badge)
            item.appendChild(slot)
        }

        val label = document.createElement("span") as HTMLElement
        label.className = "${LayoutClassNames.DOCK_ITEM}-label"
        val text = headerSpec.title ?: spec.title ?: spec.id
        label.textContent = text
        label.setAttribute("title", text)
        item.appendChild(label)

        callbacks.onFloatingRestored?.let { onRestore ->
            // Explicit restore affordance — the diagonal-inward arrows
            // (ICON_RESTORE) the toolkit uses for "bring back from a larger
            // state". stopPropagation so the chip-body click below doesn't
            // also fire (harmless duplicate, but keep it to a single call).
            val action = PaneActions.restore { onRestore(spec.id) }
            val btn = document.createElement("button") as org.w3c.dom.HTMLButtonElement
            btn.type = "button"
            btn.className = "${PaneHeaderClassNames.ACTION} ${LayoutClassNames.DOCK_ITEM}-restore"
            btn.title = action.tooltip
            btn.setAttribute("aria-label", action.tooltip)
            btn.innerHTML = action.iconHtml
            btn.addEventListener("click", { ev ->
                ev.stopPropagation()
                onRestore(spec.id)
            })
            item.appendChild(btn)
            // Clicking anywhere on the chip body restores too — the chip is
            // a parked title bar, and clicking a title bar to un-minimize is
            // the convention the user described.
            item.addEventListener("click", { _ -> onRestore(spec.id) })
            item.style.cursor = "pointer"
        }
        return item
    }

    /**
     * No-op callbacks used when [mountSeparators] is invoked solely to
     * clear stale bars. The empty-separator-list path never invokes any
     * callback, so the dummies are safe.
     */
    private val noopSeparatorCallbacks = SeparatorCallbacks(
        onFloatingMoved = { _, _, _ -> },
        onFloatingResized = { _, _, _ -> },
        snap = { it },
        minSize = 0.0,
        paneById = { null },
    )

    /**
     * Marks the pane with [paneId] as the focused one, updating DOM
     * classes on every `.dt-pane` in this renderer's container and firing
     * [PaneCallbacks.onPaneFocused]. Always fires the callback — even when
     * `paneId` already equals [focusedLeafId] — because hosts mirror focus
     * into sidebars / persistence and need to be told about every
     * user-confirmed focus, not just transitions. Skipping the callback on
     * the idempotent path used to leave the host's mirror stuck at a
     * stale id (e.g. a sidebar click on a tab whose first visible pane
     * matches the freshly-mounted renderer's auto-focus would silently
     * fail to update the sidebar mark).
     *
     * Public so hosts can drive focus from outside (e.g. on tab switch
     * or from a sidebar pane list).
     *
     * @param paneId the pane id to mark focused
     * @param autoUnmaximize when `true` (the default), focusing a pane
     *   other than the currently-maximized one fires
     *   [PaneCallbacks.onFloatingMaximizeCleared] on the maximized pane.
     *   Host post-render reconciliation passes `false` because that path
     *   is the toolkit realigning its mirror against an already-rendered
     *   layout, not a user-initiated focus move — and the host's source-
     *   mode activePaneId can lag the controller's, so a stale id there
     *   would otherwise yank the maximize the user just toggled on. User-
     *   initiated focus paths (capture-phase pane mousedown, hotkey
     *   cycling, sidebar pane row clicks) keep the default and the
     *   auto-unmaximize behaviour they rely on.
     */
    fun focusPane(paneId: PaneId, autoUnmaximize: Boolean = true) {
        if (autoUnmaximize) {
            val maximized = lastLayout.floatingPanes.firstOrNull { it.isMaximized }
            if (maximized != null && maximized.id != paneId) {
                callbacks.onFloatingMaximizeCleared?.invoke(maximized.id)
            }
        }
        focusedLeafId = paneId
        applyFocusClass(paneId)
        callbacks.onPaneFocused(paneId)
    }

    /** A cardinal direction for [focusPaneInDirection]. */
    private enum class Direction { LEFT, RIGHT, UP, DOWN }

    /**
     * One on-screen pane, reduced to the data spatial navigation needs:
     * its id and the centre of its rendered rectangle (viewport px).
     */
    private class PaneCenter(val id: PaneId, val cx: Double, val cy: Double)

    /**
     * Move focus to the nearest visible pane in [dir] relative to the
     * currently focused pane, using the panes' actual rendered geometry.
     *
     * Replaces the old wrap-around cycle: pressing → from the rightmost
     * pane does nothing (there's nothing to the right), so the four arrows
     * map to real spatial movement instead of a linear ring. This is what
     * makes Ctrl+Opt+Arrow feel like "move to the pane over there."
     *
     * Geometry is read live from the DOM (`getBoundingClientRect` on each
     * `[data-pane-id]` element) rather than from [lastLayout], so it always
     * reflects what the user sees — including after drags/resizes and while
     * a pane is maximized. Minimised panes aren't in the DOM and are thus
     * naturally excluded.
     *
     * Selection: among panes whose centre lies strictly in [dir] from the
     * focused pane's centre, pick the one minimising
     * `primaryAxisDistance + PERPENDICULAR_WEIGHT * perpendicularOffset`,
     * so a well-aligned neighbour beats a closer-but-offset one. When no
     * pane is focused yet, focus the top-left-most pane so the first press
     * has a sensible anchor.
     *
     * No-op when there are fewer than two panes, or when no pane sits in
     * the requested direction.
     *
     * Called by the arrow bindings registered in [init].
     *
     * @param dir the direction to move focus in.
     */
    private fun focusPaneInDirection(dir: Direction) {
        val centers = paneCenters()
        if (centers.size < 2) return

        val current = centers.firstOrNull { it.id == focusedLeafId }
        if (current == null) {
            // No anchor yet — enter at the top-left-most pane.
            val entry = centers.minByOrNull { it.cx + it.cy } ?: return
            if (entry.id != focusedLeafId) focusPane(entry.id)
            moveDomFocusIntoPane(entry.id)
            return
        }

        var best: PaneCenter? = null
        var bestScore = Double.MAX_VALUE
        for (c in centers) {
            if (c.id == current.id) continue
            val dx = c.cx - current.cx
            val dy = c.cy - current.cy
            val primary: Double
            val perpendicular: Double
            when (dir) {
                Direction.RIGHT -> { primary = dx; perpendicular = kotlin.math.abs(dy) }
                Direction.LEFT -> { primary = -dx; perpendicular = kotlin.math.abs(dy) }
                Direction.DOWN -> { primary = dy; perpendicular = kotlin.math.abs(dx) }
                Direction.UP -> { primary = -dy; perpendicular = kotlin.math.abs(dx) }
            }
            // Must actually be in the pressed direction.
            if (primary <= DIRECTION_EPSILON_PX) continue
            val score = primary + PERPENDICULAR_WEIGHT * perpendicular
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }

        val target = best ?: return
        focusPane(target.id)
        moveDomFocusIntoPane(target.id)
    }

    /**
     * Move real keyboard/DOM focus into a pane's content after a KEYBOARD
     * focus move (spatial Ctrl+Opt+Arrow navigation).
     *
     * [focusPane] only flips the focus-ring class and fires
     * [PaneCallbacks.onPaneFocused]; it deliberately does NOT touch DOM
     * focus (it also runs during mousedown — where the browser already
     * moved focus natively — and during host reconciliation, where stealing
     * focus would be wrong). A mouse click therefore moves DOM focus for
     * free: the browser focuses whatever was pressed, which is how a host
     * that tracks focus with a `focusin` listener on its pane content
     * (e.g. termtastic's xterm `<textarea>`) learns about the click and
     * starts routing keystrokes there. Keyboard navigation has no such
     * native focus move, so without this the ring jumps to the new pane
     * while the terminal cursor — and the host's notion of the focused
     * pane — stay behind on the old one.
     *
     * Focusing the first genuinely focusable descendant of the new pane's
     * content slot reproduces the click's effect: the terminal (or other
     * focusable content) receives keys, and the host's `focusin` handler
     * fires exactly as it would for a click. Panes whose content has no
     * focusable element are left as-is (only the ring moves) — the previous
     * behaviour, with no regression.
     *
     * @param paneId the pane the keyboard navigation just moved focus to.
     */
    private fun moveDomFocusIntoPane(paneId: PaneId) {
        val paneEl = paneArea.querySelector("[data-pane-id=\"$paneId\"]") as? HTMLElement ?: return
        val content = paneEl.querySelector(".${LayoutClassNames.PANE_CONTENT}") as? HTMLElement ?: return
        val focusable = content.querySelector(
            "textarea, input, select, [contenteditable=\"true\"], [tabindex]:not([tabindex=\"-1\"])"
        ) as? HTMLElement ?: return
        focusable.focus()
    }

    /**
     * Move real DOM focus into the currently ring-focused pane's content.
     *
     * Public entry point for host gestures that rebuild the pane DOM WITHOUT
     * a native focus move and want the active terminal to keep receiving keys
     * afterwards — notably applying a layout preset from the layout switcher.
     * That gesture re-tiles geometry and re-renders (detaching the focused
     * pane's `<textarea>` from the document, which the browser treats as a
     * blur), but the switcher click itself put DOM focus on the dropdown, not
     * the terminal — so nothing restores it. Calling this after the re-render
     * hands focus back, exactly as a click would.
     *
     * Deliberately NOT invoked from the generic per-render reconciliation
     * (which flips the focus ring on every re-render): stealing DOM focus on
     * every render would yank the caret out of, say, a settings field the
     * moment an unrelated background render fired. This is opt-in per gesture.
     *
     * No-op when no pane is ring-focused or its content has no focusable
     * element.
     *
     * @see moveDomFocusIntoPane
     */
    fun focusActivePaneContent() {
        focusedLeafId?.let { moveDomFocusIntoPane(it) }
    }

    /**
     * Panes minimized via [cyclePaneStateDown], most recent last. Lets
     * [cyclePaneStateUp] undo a hotkey-minimize even though [render]
     * moves the focus ring off a docked pane (a minimized pane has no
     * DOM, so `focusedLeafId` falls back to the first visible pane —
     * without this memory, Opt+Cmd+Down / Opt+Cmd+Up wouldn't
     * round-trip). Entries whose pane was meanwhile restored (dock chip
     * click) or closed are dropped lazily when [cyclePaneStateUp] pops
     * them.
     */
    private val hotkeyMinimizedIds = mutableListOf<PaneId>()

    /**
     * Expand one pane state step — the [StandardHotkeys.ExpandPane]
     * (Opt+Cmd+Up) handler registered in [init].
     *
     * Resolution order:
     *  1. The most recently hotkey-minimized pane that is still docked
     *     ([hotkeyMinimizedIds]) is restored — so Opt+Cmd+Down followed
     *     by Opt+Cmd+Up round-trips.
     *  2. Otherwise, when *every* pane is docked, the last docked pane
     *     in the layout is restored (covers panes minimized via the
     *     header button / menu when nothing is left to focus).
     *  3. Otherwise the focused pane is maximized (no-op when it
     *     already is).
     *
     * Steps delegate to the host's [PaneCallbacks.onFloatingRestored] /
     * [PaneCallbacks.onFloatingMaximizeToggled]; a `null` callback
     * disables that step, matching the corresponding hidden header
     * affordance.
     *
     * @see cyclePaneStateDown for the opposite direction.
     */
    private fun cyclePaneStateUp() {
        val restore = callbacks.onFloatingRestored
        if (restore != null) {
            // Prefer undoing the most recent hotkey-minimize. Pop stale
            // entries (pane restored elsewhere or closed) as we go.
            while (hotkeyMinimizedIds.isNotEmpty()) {
                val id = hotkeyMinimizedIds.removeAt(hotkeyMinimizedIds.size - 1)
                val spec = lastLayout.floatingPanes.firstOrNull { it.id == id }
                if (spec != null && spec.isMinimized) {
                    restore(id)
                    return
                }
            }
            if (lastLayout.floatingPanes.none { !it.isMinimized }) {
                val docked = lastLayout.floatingPanes.lastOrNull { it.isMinimized }
                if (docked != null) {
                    restore(docked.id)
                    return
                }
            }
        }
        val focused = lastLayout.floatingPanes
            .firstOrNull { it.id == focusedLeafId && !it.isMinimized } ?: return
        if (!focused.isMaximized) {
            callbacks.onFloatingMaximizeToggled?.invoke(focused.id)
        }
    }

    /**
     * Collapse one pane state step — the [StandardHotkeys.CollapsePane]
     * (Opt+Cmd+Down) handler registered in [init]. A maximized focused
     * pane is restored to its normal geometry; a normal one is
     * minimized to the dock (and remembered in [hotkeyMinimizedIds] so
     * [cyclePaneStateUp] can bring it straight back).
     *
     * Delegates to the host's [PaneCallbacks.onFloatingMaximizeToggled]
     * / [PaneCallbacks.onFloatingMinimized]; a `null` callback disables
     * that step, matching the corresponding hidden header affordance.
     *
     * @see cyclePaneStateUp for the opposite direction.
     */
    private fun cyclePaneStateDown() {
        val focused = lastLayout.floatingPanes
            .firstOrNull { it.id == focusedLeafId && !it.isMinimized } ?: return
        if (focused.isMaximized) {
            callbacks.onFloatingMaximizeToggled?.invoke(focused.id)
            return
        }
        val minimize = callbacks.onFloatingMinimized ?: return
        // Re-append rather than duplicate so the pane sits at the
        // "most recent" end even if a stale entry for it lingered.
        hotkeyMinimizedIds.remove(focused.id)
        hotkeyMinimizedIds.add(focused.id)
        minimize(focused.id)
    }

    /**
     * Snapshot the centre point of every currently-rendered floating pane.
     *
     * Reads the live DOM (`[data-pane-id]` elements inside [paneArea]) so
     * the result reflects real on-screen geometry. Backing data for
     * [focusPaneInDirection].
     *
     * @return one [PaneCenter] per visible pane, in DOM order.
     */
    private fun paneCenters(): List<PaneCenter> {
        val els = paneArea.querySelectorAll("[data-pane-id]")
        val out = mutableListOf<PaneCenter>()
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            val id = el.getAttribute("data-pane-id") ?: continue
            val rect = el.getBoundingClientRect()
            out.add(PaneCenter(id, rect.left + rect.width / 2.0, rect.top + rect.height / 2.0))
        }
        return out
    }

    private fun applyFocusClass(paneId: PaneId) {
        val all = paneArea.querySelectorAll(".${LayoutClassNames.PANE}")
        for (i in 0 until all.length) {
            val el = all.item(i) as HTMLElement
            val isMatch = el.getAttribute("data-pane-id") == paneId
            if (isMatch) el.classList.add(LayoutClassNames.PANE_FOCUSED)
            else el.classList.remove(LayoutClassNames.PANE_FOCUSED)
        }
    }

    /**
     * Snaps [v] to the nearest 5% step (the same grid termtastic's
     * `PaneGeometry.SNAP` uses). Integer-arithmetic round-then-divide
     * avoids floating-point drift across many drags. Used for both the
     * pane move and resize handlers so apps share termtastic's
     * "click into a grid cell" feel.
     */
    private fun snapPct(v: Double): Double = kotlin.math.round(v * 20.0) / 20.0

    /** Minimum pane size on either axis, in container fractions.
     *  Two snap steps — matches termtastic's `PaneGeometry.MIN_SIZE`. */
    private val FLOATING_MIN_SIZE = 0.10

    /**
     * How strongly [focusPaneInDirection] penalises perpendicular offset
     * versus travel along the pressed axis. `> 1` biases selection toward
     * panes that line up with the current one, so e.g. pressing → prefers a
     * horizontally-aligned neighbour over a nearer but vertically-offset
     * pane.
     */
    private val PERPENDICULAR_WEIGHT = 2.0

    /**
     * Minimum centre-to-centre travel (px) along the pressed axis for a
     * pane to count as "in that direction" in [focusPaneInDirection].
     * Guards against near-coincident centres registering as a move.
     */
    private val DIRECTION_EPSILON_PX = 1.0

    /**
     * Builds one absolutely-positioned pane. The host moves it via
     * `--dt-fp-x / -y`, stacks it via `--dt-fp-z`, and the toolkit wires
     * drag-on-header / click-to-front gestures.
     */
    private fun buildFloatingPane(
        spec: FloatingPaneSpec,
        pendingPanes: MutableList<Pair<PaneId, HTMLElement>>,
    ): HTMLElement {
        val pane = document.createElement("div") as HTMLElement
        // Geometry vars consumed by .dt-pane-floating (see lunula.css).
        // Set BEFORE the floating class is attached so the CSS rule never
        // resolves against its fallback (8% / 8% / 45% / 55%) on the very
        // first paint — a tiny ordering window that otherwise causes a
        // visible flash at the fallback rect during maximize/restore.
        // Stored even while maximized so the restore toggle brings the
        // pane back to its previous size without the host having to
        // re-supply geometry.
        pane.style.setProperty("--dt-fp-x", "${spec.xPct * 100.0}%")
        pane.style.setProperty("--dt-fp-y", "${spec.yPct * 100.0}%")
        pane.style.setProperty("--dt-fp-w", "${spec.widthPct * 100.0}%")
        pane.style.setProperty("--dt-fp-h", "${spec.heightPct * 100.0}%")
        pane.style.setProperty("--dt-fp-z", "${spec.zIndex}")
        pane.className = "${LayoutClassNames.PANE} ${LayoutClassNames.PANE_FLOATING}"
        // Stage the maximize/restore animation: when the flag flipped
        // since the last render, mount in the OLD state and flip to the
        // NEW state on the next animation frame so the CSS transition on
        // `left/top/width/height` has a "from" geometry to animate from.
        // First render of this pane has no prior — apply the current
        // state immediately (no animation needed for a fresh pane).
        val prior = previousMaximizedStates[spec.id]
        val shouldAnimateMaximize = !suppressAnimationsForThisRender &&
            prior != null && prior != spec.isMaximized
        // Newly-appearing pane: missing from the previous render's
        // pane set. Animate a "pop in" — scale 0 → 1 with opacity
        // 0 → 1, anchored at the pane's own target center. Skipped on
        // the renderer's very first render (see [hasRenderedOnce]) and
        // on context changes (tab switches: we'd otherwise animate
        // every pane in the new tab even though the user didn't add
        // them).
        val shouldAnimateEntry = !suppressAnimationsForThisRender &&
            hasRenderedOnce && prior == null
        // TEMP: diagnostic for the maximize-pane "third location" flash.
        // Remove once the regression is understood / fixed.
        console.log(
            "buildFloatingPane",
            "id=${spec.id}",
            "prior=$prior",
            "isMaximized=${spec.isMaximized}",
            "shouldAnimateMaximize=$shouldAnimateMaximize",
            "shouldAnimateEntry=$shouldAnimateEntry",
            "suppressAnimations=$suppressAnimationsForThisRender",
            "xPct=${spec.xPct} yPct=${spec.yPct} w=${spec.widthPct} h=${spec.heightPct}",
        )
        val initialMaximized = if (shouldAnimateMaximize) !spec.isMaximized else spec.isMaximized
        if (initialMaximized) pane.classList.add("dt-maximized")
        if (shouldAnimateEntry) {
            pane.style.transform = "scale(0)"
            pane.style.opacity = "0"
        }
        if (shouldAnimateMaximize || shouldAnimateEntry) {
            // Double rAF + forced layout flush so the browser has actually
            // painted the "from" state before we flip to the "to" state.
            // A single rAF can fire BEFORE the first paint of a freshly-
            // appended node, in which case the CSS transition collapses to
            // an instant snap. Reading `offsetHeight` inside the first rAF
            // forces a synchronous layout pass; the second rAF then flips
            // the state on the next frame, giving the transition a real
            // "from" to interpolate from. Termtastic's `floating-pane`
            // rule animates the same set of properties.
            window.requestAnimationFrame {
                @Suppress("UNUSED_VARIABLE")
                val forceLayout = pane.offsetHeight
                window.requestAnimationFrame {
                    if (shouldAnimateMaximize) {
                        if (spec.isMaximized) pane.classList.add("dt-maximized")
                        else pane.classList.remove("dt-maximized")
                    }
                    if (shouldAnimateEntry) {
                        pane.style.transform = ""
                        pane.style.opacity = ""
                    }
                }
            }
        }
        previousMaximizedStates[spec.id] = spec.isMaximized
        pane.setAttribute("data-pane-id", spec.id)
        if (spec.id == focusedLeafId) pane.classList.add(LayoutClassNames.PANE_FOCUSED)
        // Geometry vars (--dt-fp-x/-y/-w/-h/-z) are stamped at the top of
        // this function, before the floating class is attached — keeping
        // them here was racy on first paint.

        // Click-to-focus + click-to-raise on an already-active pane.
        //
        // Two-phase wiring:
        //  1. Capture-phase MOUSEDOWN: apply focus. Focus is a pure class
        //     flip — no rerender, no state mutation — so it's safe to run
        //     during the opening of any gesture (header drag, corner
        //     resize, etc.).
        //  2. Capture-phase CLICK: if the user did not drag (browsers
        //     suppress `click` after a drag), fire
        //     [PaneCallbacks.onFloatingFocused] to raise the pane to the
        //     front. A single primary-button click ANYWHERE in the pane —
        //     header, content, or resize handle — raises it, whether or
        //     not it was already focused, so the user never has to click
        //     twice (or hunt for the titlebar) to bring a pane forward.
        //
        // Why raise on click, not mousedown: firing raise during mousedown
        // forced a host-side rerender that rebuilt every pane element
        // mid-gesture. The bubble-phase header / corner-resize handlers
        // (registered on the original pane DOM nodes) then read
        // `pane.getBoundingClientRect()` on the now-detached element,
        // which returns {0, 0, 0, 0}. The drag's cursor-offset math
        // derived against that zero rect produced bogus xPct/yPct
        // values, persisted on mouseup, and the next rerender snapped
        // the pane to (0, 0) — visible as "clicking pane A teleports
        // pane B to a corner" and similar geometry corruption.
        //
        // Deferring raise to `click` keeps drags, resize handles, and
        // sidebar resize from ever interleaving with a rerender: a drag
        // suppresses the trailing `click`, so only a stationary press
        // (never a drag/resize gesture) reaches the raise handler.
        pane.addEventListener("mousedown", { ev ->
            val mouseEv = ev as MouseEvent
            if (mouseEv.button.toInt() != 0) return@addEventListener
            // Focus on every primary-button press inside the pane,
            // including header presses. The earlier "skip focus on header
            // mousedown" branch was added to mask a flicker where a
            // header click without drag still produced a host rerender
            // (onMoved fired on every mouseup) that reset focusedLeafId.
            // That root cause is now fixed in `wireFloatingHeaderDrag`
            // via the `didMove` gate, so header clicks are safe to focus
            // again. Result: clicking a pane title makes the pane active
            // immediately, matching the user's mental model that the
            // titlebar is the most obvious "pick this pane" affordance.
            focusPane(spec.id)
        }, true)
        pane.addEventListener("click", { ev ->
            val mouseEv = ev as MouseEvent
            if (mouseEv.button.toInt() != 0) return@addEventListener
            callbacks.onFloatingFocused?.invoke(spec.id)
        }, true)
        // Keep the legacy double-click as a redundant raise gesture so
        // muscle memory from prior versions still works.
        pane.addEventListener("dblclick", { _ -> callbacks.onFloatingFocused?.invoke(spec.id) })

        val header = buildPaneHeaderForFloating(pane, spec)
        pane.appendChild(header)

        val content = document.createElement("div") as HTMLElement
        content.className = LayoutClassNames.PANE_CONTENT
        // Focus + raise are wired at the pane level (capture-phase
        // mousedown above) so a press on the header, the content, the
        // resize handle, or anywhere inside the pane runs the same logic.
        // Header drag and corner-resize handlers stop propagation, but
        // the capture-phase listener has already fired before they do.
        pendingPanes.add(spec.id to content)
        pane.appendChild(content)

        // Resize handles on all four corners of every (non-maximized) pane,
        // regardless of focus. Each corner anchors the OPPOSITE corner so
        // dragging top-left adjusts the pane's origin while the bottom-right
        // stays put. Maximized panes skip handles since their geometry is
        // overridden to 100% — the user has to restore first.
        if (!spec.isMaximized) {
            val onResized = callbacks.onFloatingResized
            val onMoved = callbacks.onFloatingMoved
            if (onResized != null) {
                for (corner in Corner.entries) {
                    wireFloatingCornerResize(pane, spec, corner, onResized, onMoved)
                }
            }
        }

        return pane
    }

    /**
     * Builds the chrome header for a floating pane: invokes the host's
     * [PaneCallbacks.paneHeader] callback, appends the toolkit-owned window
     * controls (minimize / maximize-toggle / close), and wires the
     * drag-to-move gesture against [pane]. Returned element is detached —
     * the caller mounts it.
     *
     * Extracted so the fast-path rebuild in [applyHeaderOnlyUpdate] can
     * produce a fresh header without duplicating the merge logic.
     *
     * @param pane the `.dt-pane` wrapper the new header will live inside;
     *   needed by [wireFloatingHeaderDrag] to translate cursor deltas into
     *   geometry updates.
     * @param spec the pane's current floating spec.
     * @return a fresh `.dt-pane-header` element with all gestures wired.
     */
    private fun buildPaneHeaderForFloating(pane: HTMLElement, spec: FloatingPaneSpec): HTMLElement {
        // Pane chrome goes through the host's [PaneCallbacks.paneHeader]
        // callback: title, leading icon, rename gesture, and per-app
        // navigation actions (up/home/etc.). Window controls (minimize,
        // maximize-toggle, close) are toolkit-owned and always appended.
        val baseSpec: PaneHeaderSpec = callbacks.paneHeader.invoke(spec.id, spec.title)

        val controlActions = mutableListOf<PaneAction>()
        callbacks.onFloatingMinimized?.let { onMin ->
            controlActions.add(PaneActions.minimize { onMin(spec.id) })
        }
        callbacks.onFloatingMaximizeToggled?.let { onMax ->
            // Use expand/restore (diagonal arrows) to match termtastic.
            controlActions.add(
                if (spec.isMaximized) PaneActions.restore { onMax(spec.id) }.copy(isActive = true)
                else PaneActions.expand { onMax(spec.id) }
            )
        }
        // Per-pane opt-out: a spec with `isClosable = false` (e.g. an
        // app's permanent home pane) simply never gets the close button,
        // regardless of the layout-wide close callback being wired.
        if (spec.isClosable) callbacks.onFloatingClosed?.let { onClose ->
            controlActions.add(
                PaneActions.close {
                    if (callbacks.confirmFloatingClose) {
                        // Single source of truth for the close-pane confirm
                        // copy/buttons/styling lives in the toolkit's
                        // `confirmClosePane` helper so termtastic's bespoke
                        // close path can share the same dialog shape.
                        se.soderbjorn.lunula.web.confirmClosePane(
                            paneTitle = spec.title,
                            onConfirm = { onClose(spec.id) },
                        )
                    } else {
                        onClose(spec.id)
                    }
                }
            )
        }

        // Auto-insert a small visual gap between the app's pane actions
        // and the toolkit's standard window controls. Skip if either side
        // is empty, or if the app already ended its action list with a
        // separator (notegrow used to add one manually).
        val needsSeparator = baseSpec.actions.isNotEmpty() &&
            controlActions.isNotEmpty() &&
            !baseSpec.actions.last().extraClass.contains(PaneActions.SEPARATOR_CLASS)
        val mergedActions = if (needsSeparator) {
            baseSpec.actions + PaneActions.separator() + controlActions
        } else {
            baseSpec.actions + controlActions
        }
        val finalSpec = baseSpec.copy(actions = mergedActions)
        val header = renderPaneHeader(paneId = spec.id, spec = finalSpec)
        // Drag the header to move the pane. Use raw mouse events instead of
        // HTML5 drag-and-drop so we get pixel-precise live tracking and the
        // pane follows the cursor frame-by-frame (HTML5 drag emits events
        // too sparsely and shows a ghost image). Maximized panes don't
        // accept move gestures — dragging the chrome of a full-bleed pane
        // would have no visible effect.
        if (!spec.isMaximized) {
            callbacks.onFloatingMoved?.let { onMoved ->
                wireFloatingHeaderDrag(pane, header, spec, onMoved)
            }
        }
        return header
    }

    /**
     * Returns true when the diff between [previous] and [next] is small
     * enough that the renderer can patch existing pane wrappers in place
     * instead of rebuilding them. Concretely: same pane ids in the same
     * order, with identical geometry / z-index / maximize / minimize
     * flags. Only the per-pane header content (typically `title`) is
     * allowed to change — that's exactly the shape termtastic's cwd
     * tracker pushes when the shell's working directory updates.
     *
     * The fast path matters because rebuilding a pane wrapper detaches
     * its `.dt-pane-content` slot and reattaches it to the new wrapper,
     * which browsers count as removing the focused element from the
     * document — i.e. an xterm canvas inside loses focus. Patching just
     * the header child leaves the content slot in place, so focus
     * survives.
     *
     * Bails (returns false) when the renderer hasn't completed a first
     * full render, when any expected pane element is missing from the
     * container (could happen if a host wiped it out-of-band), or when a
     * matching element is currently a closing-ghost (mid-exit animation).
     *
     * @param previous the previously-rendered layout (i.e. [lastLayout]).
     * @param next     the layout the caller just passed to [render].
     * @return whether [applyHeaderOnlyUpdate] can be used in place of a
     *   full rebuild.
     */
    private fun canDoHeaderOnlyUpdate(previous: PaneLayout, next: PaneLayout): Boolean {
        if (!hasRenderedOnce) return false
        val prev = previous.floatingPanes
        val curr = next.floatingPanes
        if (prev.size != curr.size) return false
        for (i in prev.indices) {
            val a = prev[i]
            val b = curr[i]
            if (a.id != b.id) return false
            if (a.xPct != b.xPct || a.yPct != b.yPct) return false
            if (a.widthPct != b.widthPct || a.heightPct != b.heightPct) return false
            if (a.zIndex != b.zIndex) return false
            if (a.isMaximized != b.isMaximized) return false
            if (a.isMinimized != b.isMinimized) return false
            // Note: spec.title is intentionally NOT compared — header
            // content drift is the whole reason the fast path exists.
        }
        // Verify each visible pane is actually present in the DOM and not
        // mid-exit; otherwise fall back to the full rebuild that knows
        // how to deal with missing / ghosted elements.
        for (spec in curr) {
            if (spec.isMinimized) continue
            val existing = paneArea.querySelector("[data-pane-id=\"${spec.id}\"]") as? HTMLElement
                ?: return false
            if (existing.classList.contains(CLOSING_GHOST_CLASS)) return false
        }
        return true
    }

    /**
     * Replaces just the header child of every existing pane wrapper with
     * a fresh one built from [layout]. The `.dt-pane-content` slot under
     * each wrapper is left untouched — its DOM identity survives, so any
     * focused element inside (xterm canvas, contenteditable) keeps DOM
     * focus across the update.
     *
     * Caller must have already verified [canDoHeaderOnlyUpdate] returns
     * true for the same layout. Geometry CSS vars and the focus class
     * are not touched here because [canDoHeaderOnlyUpdate] guarantees
     * those didn't change.
     *
     * @param layout the new layout — invoked the host's [PaneCallbacks.paneHeader]
     *   per pane to obtain refreshed header content.
     */
    private fun applyHeaderOnlyUpdate(layout: PaneLayout) {
        for (spec in layout.floatingPanes) {
            if (spec.isMinimized) continue
            val pane = paneArea.querySelector("[data-pane-id=\"${spec.id}\"]") as? HTMLElement
                ?: continue
            val newHeader = buildPaneHeaderForFloating(pane, spec)
            // Find the existing header among direct children. Avoids
            // matching any nested `.dt-pane-header` a host might
            // inadvertently create inside the content slot.
            var oldHeader: HTMLElement? = null
            var child = pane.firstElementChild
            while (child != null) {
                if (child is HTMLElement && child.classList.contains(LayoutClassNames.PANE_HEADER)) {
                    oldHeader = child
                    break
                }
                child = child.nextElementSibling
            }
            if (oldHeader != null) {
                pane.replaceChild(newHeader, oldHeader)
            } else {
                // Defensive: pane has no existing header — insert at the
                // top so visual ordering matches a full rebuild.
                pane.insertBefore(newHeader, pane.firstChild)
            }
        }
    }

    /**
     * Wire mouse-drag on [header] to move [pane] as the user drags. The
     * pane's geometry vars are updated live so the user sees the pane
     * slide under the cursor; on `mouseup` [onMoved] is fired with the
     * final position so the host can persist it. Position is clamped to
     * keep the pane fully inside [container].
     */
    private fun wireFloatingHeaderDrag(
        pane: HTMLElement,
        header: HTMLElement,
        spec: FloatingPaneSpec,
        onMoved: (PaneId, Double, Double) -> Unit,
    ) {
        header.style.cursor = "grab"
        header.addEventListener("mousedown", { ev ->
            val mouseEv = ev as MouseEvent
            // Only left-button drags. Ignore action-button mousedowns —
            // renderPaneHeader stops their propagation already, so we won't
            // see them here, but this is a defensive belt.
            if (mouseEv.button.toInt() != 0) return@addEventListener
            // Geometry fractions are measured against the pane area (not
            // the outer root), since the dock can occupy part of the root.
            val containerRect = paneArea.getBoundingClientRect()
            val containerW = containerRect.width
            val containerH = containerRect.height
            if (containerW <= 0 || containerH <= 0) return@addEventListener
            val paneRect = pane.getBoundingClientRect()
            // Cursor-to-pane offset so the pane doesn't jump on first move.
            val grabDx = mouseEv.clientX.toDouble() - paneRect.left
            val grabDy = mouseEv.clientY.toDouble() - paneRect.top
            val paneW = paneRect.width
            val paneH = paneRect.height
            mouseEv.preventDefault()
            // Don't bubble: focus stays on whatever pane was focused before
            // the drag started. The content-slot focus listener (only place
            // mousedown focuses a pane) won't see this event.
            mouseEv.stopPropagation()
            header.style.cursor = "grabbing"
            // Suppress the geometry transition while dragging so the pane
            // tracks the cursor frame-by-frame instead of easing toward
            // each new position. Re-enabled on mouseup.
            pane.classList.add("dt-pane-no-anim")

            // Pane width/height as container fractions, snapped to the same
            // grid the position will use so the pane stays cell-aligned
            // throughout the drag (matches termtastic's
            // `PaneGeometry.normalize`).
            val curW = paneW / containerW
            val curH = paneH / containerH
            var lastX = spec.xPct
            var lastY = spec.yPct
            // Track whether the user actually moved before firing the
            // host's onMoved persistence/rerender path. Without this, a
            // plain click on the header counts as a "drag end at the same
            // position" and triggers a full host rerender. Termtastic's
            // rerender rebuilds the LayoutRenderer instance, which resets
            // `focusedLeafId` to the first visible pane — visible to the
            // user as "click the titlebar → pane briefly focuses → on
            // release, focus reverts to wherever it was before".
            var didMove = false
            val onMove: (org.w3c.dom.events.Event) -> Unit = { e ->
                val m = e as MouseEvent
                val rawX = (m.clientX.toDouble() - containerRect.left - grabDx) / containerW
                val rawY = (m.clientY.toDouble() - containerRect.top - grabDy) / containerH
                // Snap to 5% grid then clamp so the pane always lands in a
                // grid cell fully inside the container (`x + width <= 1.0`).
                val snappedX = snapPct(rawX).coerceIn(0.0, 1.0 - curW)
                val snappedY = snapPct(rawY).coerceIn(0.0, 1.0 - curH)
                if (snappedX != lastX || snappedY != lastY) didMove = true
                lastX = snappedX
                lastY = snappedY
                pane.style.setProperty("--dt-fp-x", "${lastX * 100.0}%")
                pane.style.setProperty("--dt-fp-y", "${lastY * 100.0}%")
            }
            lateinit var onUp: (org.w3c.dom.events.Event) -> Unit
            onUp = { _ ->
                document.removeEventListener("mousemove", onMove)
                document.removeEventListener("mouseup", onUp)
                header.style.cursor = "grab"
                pane.classList.remove("dt-pane-no-anim")
                if (didMove) onMoved(spec.id, lastX, lastY)
            }
            document.addEventListener("mousemove", onMove)
            document.addEventListener("mouseup", onUp)
        })
    }

    /**
     * Mounts a corner resize handle on [pane]. The handle anchors the
     * OPPOSITE corner so the user can resize from any of the four
     * corners and shape the pane intuitively:
     *
     * - [Corner.BottomRight]: anchor top-left; drag adjusts width and height.
     * - [Corner.BottomLeft]:  anchor top-right; drag adjusts x, width, and height.
     * - [Corner.TopRight]:    anchor bottom-left; drag adjusts y, width, and height.
     * - [Corner.TopLeft]:     anchor bottom-right; drag adjusts x, y, width, and height.
     *
     * Fires [onResized] on `mouseup` with the final size; for corners
     * that move the pane's origin (top-left, top-right, bottom-left)
     * also fires [onMoved] with the final origin so the host persists
     * both. `mousedown` events are stopped from propagating so the
     * content-slot focus handler doesn't fire — dragging a handle on a
     * non-focused pane resizes it without stealing focus from the
     * pane the user was working in.
     *
     * Handles are mounted on every non-maximized pane regardless of
     * focus, so the user can grab the corner of any visible pane to
     * resize it directly.
     */
    private fun wireFloatingCornerResize(
        pane: HTMLElement,
        spec: FloatingPaneSpec,
        corner: Corner,
        onResized: (PaneId, Double, Double) -> Unit,
        onMoved: ((PaneId, Double, Double) -> Unit)?,
    ) {
        val grip = document.createElement("div") as HTMLElement
        grip.className = "${LayoutClassNames.CORNER_RESIZE} ${corner.cssClass}"
        grip.innerHTML = cornerGripSvg(corner)
        pane.appendChild(grip)

        grip.addEventListener("mousedown", { ev ->
            val mouseEv = ev as MouseEvent
            if (mouseEv.button.toInt() != 0) return@addEventListener
            // The handle has a ::before pseudo that extends the *hover* zone
            // into the pane interior so coming near a corner reveals the
            // grips. mousedown on that zone targets the grip element too,
            // but we only want to start a drag when the click lands on the
            // visible 14x14 handle. Reject clicks outside the handle's own
            // border box (which excludes the ::before extension).
            val gripRect = grip.getBoundingClientRect()
            val cx = mouseEv.clientX.toDouble()
            val cy = mouseEv.clientY.toDouble()
            if (cx < gripRect.left || cx > gripRect.right ||
                cy < gripRect.top || cy > gripRect.bottom) {
                return@addEventListener
            }
            mouseEv.preventDefault()
            // Don't focus the pane just because a resize handle was grabbed.
            mouseEv.stopPropagation()
            // Geometry fractions are measured against the pane area (not
            // the outer root), since the dock can occupy part of the root.
            val containerRect = paneArea.getBoundingClientRect()
            val containerW = containerRect.width
            val containerH = containerRect.height
            if (containerW <= 0 || containerH <= 0) return@addEventListener
            val paneRect = pane.getBoundingClientRect()
            grip.classList.add("dt-dragging")

            // Anchor — the corner that stays put — captured up front in
            // container fractions. The mobile corner moves with the cursor
            // and the new geometry is derived from anchor↔mobile.
            val anchorXPct = when (corner) {
                Corner.TopLeft, Corner.BottomLeft -> (paneRect.right - containerRect.left) / containerW
                Corner.TopRight, Corner.BottomRight -> (paneRect.left - containerRect.left) / containerW
            }
            val anchorYPct = when (corner) {
                Corner.TopLeft, Corner.TopRight -> (paneRect.bottom - containerRect.top) / containerH
                Corner.BottomLeft, Corner.BottomRight -> (paneRect.top - containerRect.top) / containerH
            }
            var lastX = spec.xPct
            var lastY = spec.yPct
            var lastW = spec.widthPct
            var lastH = spec.heightPct
            pane.classList.add("dt-pane-no-anim")

            val onMove: (org.w3c.dom.events.Event) -> Unit = { e ->
                val m = e as MouseEvent
                val rawX = (m.clientX.toDouble() - containerRect.left) / containerW
                val rawY = (m.clientY.toDouble() - containerRect.top) / containerH
                val mobileX = snapPct(rawX).coerceIn(0.0, 1.0)
                val mobileY = snapPct(rawY).coerceIn(0.0, 1.0)
                // Convert anchor + mobile to (x,y,w,h), preserving MIN_SIZE.
                val left = kotlin.math.min(anchorXPct, mobileX)
                val right = kotlin.math.max(anchorXPct, mobileX)
                val top = kotlin.math.min(anchorYPct, mobileY)
                val bottom = kotlin.math.max(anchorYPct, mobileY)
                val w = (right - left).coerceAtLeast(FLOATING_MIN_SIZE)
                val h = (bottom - top).coerceAtLeast(FLOATING_MIN_SIZE)
                // If clamping pushed the size up, anchor the opposite-of-mobile
                // edge so the pane grows toward the cursor's direction.
                val finalLeft = when (corner) {
                    Corner.TopLeft, Corner.BottomLeft -> (anchorXPct - w).coerceAtLeast(0.0)
                    Corner.TopRight, Corner.BottomRight -> anchorXPct.coerceAtMost(1.0 - w)
                }
                val finalTop = when (corner) {
                    Corner.TopLeft, Corner.TopRight -> (anchorYPct - h).coerceAtLeast(0.0)
                    Corner.BottomLeft, Corner.BottomRight -> anchorYPct.coerceAtMost(1.0 - h)
                }
                lastX = finalLeft
                lastY = finalTop
                lastW = w
                lastH = h
                pane.style.setProperty("--dt-fp-x", "${lastX * 100.0}%")
                pane.style.setProperty("--dt-fp-y", "${lastY * 100.0}%")
                pane.style.setProperty("--dt-fp-w", "${lastW * 100.0}%")
                pane.style.setProperty("--dt-fp-h", "${lastH * 100.0}%")
            }
            lateinit var onUp: (org.w3c.dom.events.Event) -> Unit
            onUp = { _ ->
                document.removeEventListener("mousemove", onMove)
                document.removeEventListener("mouseup", onUp)
                grip.classList.remove("dt-dragging")
                pane.classList.remove("dt-pane-no-anim")
                onResized(spec.id, lastW, lastH)
                // Origin only changed for the three non-bottom-right corners.
                if (corner != Corner.BottomRight && onMoved != null) {
                    onMoved(spec.id, lastX, lastY)
                }
            }
            document.addEventListener("mousemove", onMove)
            document.addEventListener("mouseup", onUp)
        })
    }

    /**
     * Builds a non-interactive replica of [spec] mounted at the
     * pane's last-known geometry, for the purpose of animating it
     * out of view when the host has dropped the pane from the layout.
     *
     * On next-frame rAF the replica gets `transform: scale(0)` +
     * `opacity: 0`, and the CSS transition on `transform` (220ms) +
     * `opacity` (160ms) inherited from `.dt-pane-floating` plays the
     * shrink-into-screen animation. The replica removes itself after
     * a short delay matching the transition duration; if a subsequent
     * [render] wipes the container before the timeout fires, the
     * `parentNode` check makes the timeout a no-op.
     *
     * No header buttons, no resize handles, no event listeners — the
     * ghost is purely decorative. Pointer events are disabled so the
     * fading element doesn't intercept clicks meant for live panes
     * underneath.
     */
    /**
     * Starts the close-out animation on an existing live pane element
     * the host has just dropped from the layout. The element stays in
     * the DOM (left over from the previous render's wipe-preserve
     * pass) so its content (notegrow's editor, termtastic's terminal,
     * …) remains visible through the shrink — empty replicas are hard
     * to see when no other panes are visible to provide context.
     *
     * Marks the element with [CLOSING_GHOST_CLASS] so any subsequent
     * render's wipe pass leaves it alone, disables pointer events so
     * the fading element doesn't intercept clicks meant for whatever
     * is underneath, and runs a Web Animations API scale + opacity
     * animation. The `onfinish` callback removes the element from
     * the DOM once the animation completes.
     */
    /**
     * FLIP-animates [el] between two screen rects via the Web Animations
     * API. The element's actual laid-out box (`getBoundingClientRect()`) is
     * the reference; the animation maps it to [startRect] at the start and
     * to [endRect] at the end (both with a `top left` transform origin), so
     * the caller can fly the element between any two rectangles regardless of
     * where its CSS box actually rests:
     *
     *  - **Minimize**: the ghost's box rests at the old pane rect, which is
     *    [startRect]; [endRect] is the dock chip — the ghost flies down + in.
     *  - **Restore**: the new pane's box rests at its real geometry, which is
     *    [endRect]; [startRect] is the old dock chip — the pane grows out.
     *
     * @param el             the element to animate.
     * @param startRect      screen rect the element should appear at on frame 0.
     * @param endRect        screen rect the element should appear at on the last frame.
     * @param startOpacity   opacity on frame 0.
     * @param endOpacity     opacity on the last frame.
     * @param removeOnFinish when `true` the element is removed from the DOM
     *   once the animation completes (minimize ghost); the animation also
     *   uses `fill: forwards` so it holds the end state until removal. When
     *   `false` the fill is `none` so the element settles to its natural
     *   (untransformed) box — correct for a restored pane whose [endRect]
     *   already equals its resting box.
     */
    private fun animateFlip(
        el: HTMLElement,
        startRect: org.w3c.dom.DOMRect,
        endRect: org.w3c.dom.DOMRect,
        startOpacity: Double,
        endOpacity: Double,
        removeOnFinish: Boolean,
    ) {
        val box = el.getBoundingClientRect()
        if (box.width <= 0.0 || box.height <= 0.0) {
            if (removeOnFinish) el.parentNode?.removeChild(el)
            return
        }
        fun mapTransform(r: org.w3c.dom.DOMRect): String {
            val dx = r.left - box.left
            val dy = r.top - box.top
            val sx = r.width / box.width
            val sy = r.height / box.height
            return "translate(${dx}px, ${dy}px) scale($sx, $sy)"
        }
        val f0: dynamic = js("({})")
        f0.transformOrigin = "top left"
        f0.transform = mapTransform(startRect)
        f0.opacity = startOpacity
        val f1: dynamic = js("({})")
        f1.transformOrigin = "top left"
        f1.transform = mapTransform(endRect)
        f1.opacity = endOpacity
        val frames: dynamic = js("[]")
        frames.push(f0)
        frames.push(f1)
        val opts: dynamic = js("({})")
        opts.duration = 420
        opts.easing = "cubic-bezier(.2, .8, .2, 1)"
        opts.fill = if (removeOnFinish) "forwards" else "none"
        val animation = el.asDynamic().animate(frames, opts)
        if (removeOnFinish) {
            animation.onfinish = {
                el.parentNode?.removeChild(el)
                Unit
            }
        }
    }

    private fun startCloseAnimation(pane: HTMLElement) {
        pane.classList.add(CLOSING_GHOST_CLASS)
        pane.style.setProperty("pointer-events", "none")
        // Force above any live pane so geometry shuffling doesn't
        // visually occlude the closing pane.
        pane.style.setProperty("--dt-fp-z", "9999")
        val keyframes: dynamic = js("[{transform:'scale(1)',opacity:1},{transform:'scale(0)',opacity:0}]")
        val options: dynamic = js("({duration:380,easing:'cubic-bezier(.4,.0,.2,1)',fill:'forwards'})")
        val animation = (pane.asDynamic()).animate(keyframes, options)
        animation.onfinish = {
            pane.parentNode?.removeChild(pane)
        }
    }

    /** Per-corner SVG glyph: bottom-right and top-left use the diagonal
     *  hatch (matching termtastic's grip); top-right and bottom-left
     *  mirror it so each corner's icon points outward. */
    private fun cornerGripSvg(corner: Corner): String {
        val path = when (corner) {
            Corner.BottomRight ->
                "<line x1=\"10\" y1=\"4\" x2=\"4\" y2=\"10\"/>" +
                    "<line x1=\"10\" y1=\"7\" x2=\"7\" y2=\"10\"/>"
            Corner.TopLeft ->
                "<line x1=\"2\" y1=\"8\" x2=\"8\" y2=\"2\"/>" +
                    "<line x1=\"2\" y1=\"5\" x2=\"5\" y2=\"2\"/>"
            Corner.TopRight ->
                "<line x1=\"4\" y1=\"2\" x2=\"10\" y2=\"8\"/>" +
                    "<line x1=\"7\" y1=\"2\" x2=\"10\" y2=\"5\"/>"
            Corner.BottomLeft ->
                "<line x1=\"2\" y1=\"4\" x2=\"8\" y2=\"10\"/>" +
                    "<line x1=\"2\" y1=\"7\" x2=\"5\" y2=\"10\"/>"
        }
        return "<svg viewBox=\"0 0 12 12\" width=\"12\" height=\"12\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linecap=\"round\">$path</svg>"
    }
}
