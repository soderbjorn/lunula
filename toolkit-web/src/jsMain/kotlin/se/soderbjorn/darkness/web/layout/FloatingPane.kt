/**
 * Spec + helpers for floating overlay panes.
 *
 * A floating pane is an absolutely-positioned `.dt-pane` drawn on top of
 * the renderer's split tree. Geometry is stored as percentages of the
 * container so the pane scales with window resize without the host having
 * to re-pin coordinates. A z-index orders stacked floats; double-click-to-
 * raise (handled inside [LayoutRenderer]) bumps the float to the top —
 * a plain single click only focuses, matching termtastic's behaviour.
 *
 * Hosts add a floating pane by appending one to [PaneLayout.floatingPanes]
 * and re-rendering. The toolkit fires [PaneCallbacks.onFloatingMoved] /
 * [PaneCallbacks.onFloatingFocused] / [PaneCallbacks.onFloatingClosed] as
 * the user drags / double-clicks / closes; the host updates its model and
 * calls [LayoutRenderer.render] again.
 *
 * @see PaneLayout.floatingPanes
 * @see LayoutRenderer
 */
package se.soderbjorn.darkness.web.layout

import kotlin.random.Random

/**
 * One floating overlay pane.
 *
 * Geometry is in percentages of the renderer's container so the pane
 * scales naturally with window resize — same approach as termtastic's
 * `.floating-pane` (`--px / --py / --pw / --ph`).
 *
 * @property id        stable pane identifier; used by callbacks and the
 *   `data-pane-id` attribute. Must be unique within the layout (across
 *   both the tree and the floating list).
 * @property title     human-readable pane title shown in the chrome
 *   header. `null` to fall back to the spec's [id].
 * @property xPct      left-edge offset as a percentage (0.0..1.0) of the
 *   container's width.
 * @property yPct      top-edge offset as a percentage (0.0..1.0) of the
 *   container's height.
 * @property widthPct  width as a fraction of the container's width.
 * @property heightPct height as a fraction of the container's height.
 * @property zIndex    stacking order — higher floats above lower. Hosts
 *   typically increment to `max(existing) + 1` when bringing one to the
 *   front in response to [PaneCallbacks.onFloatingFocused] (which the
 *   toolkit fires only on the user's double-click "raise" gesture).
 */
data class FloatingPaneSpec(
    val id: PaneId,
    val title: String? = null,
    val xPct: Double,
    val yPct: Double,
    val widthPct: Double = 0.45,
    val heightPct: Double = 0.55,
    val zIndex: Int = 1,
    /**
     * When `true`, the renderer paints the pane filling the whole
     * container (inset by the floating-pane gutter) on top of every
     * other float — termtastic's `.floating-pane.maximized` behaviour.
     * The stored geometry (xPct/yPct/widthPct/heightPct) is preserved so
     * toggling restore brings the pane back to its previous size.
     */
    val isMaximized: Boolean = false,
    /**
     * When `true`, the renderer omits the pane from the DOM entirely.
     * Hosts surface the pane elsewhere (sidebar overflow, "Minimised
     * panes" submenu, etc.) so the user can restore it. Mirrors the
     * "minimised" state termtastic surfaces in the sidebar.
     */
    val isMinimized: Boolean = false,
    /**
     * When `false`, the chrome header omits the close button for this
     * pane even though [PaneCallbacks.onFloatingClosed] is wired for the
     * layout as a whole. Minimize/maximize are unaffected. Defaults to
     * `true` (every pane closable — the historical chrome). Populated by
     * `mountAppShell` from `AppShellSpec.paneClosable`; hosts driving the
     * renderer directly set it per spec entry.
     */
    val isClosable: Boolean = true,
)

/**
 * Returns a copy of the receiver with [FloatingPaneSpec.isMaximized] cleared
 * on every entry that has it set; entries already non-maximized are returned
 * unchanged (no needless `copy` allocations).
 *
 * Used by host apps that want "adding a new pane unmaximizes any existing
 * maximized pane" — termtastic's behaviour, which the toolkit now offers as
 * a one-liner so notegrow (and future apps) match without copy-pasted logic.
 *
 * @return list with all entries unmaximized; preserves order and identity of
 *   already-restored specs.
 */
fun List<FloatingPaneSpec>.withNoneMaximized(): List<FloatingPaneSpec> =
    if (none { it.isMaximized }) this
    else map { if (it.isMaximized) it.copy(isMaximized = false) else it }

/**
 * Returns a [FloatingPaneSpec] with a random spawn position inside the
 * container, biased to the upper-left third so successive spawns cascade
 * down-right (matching the macOS / Finder window-spawn convention).
 *
 * Width/height default to ~45%/55% of the container; the position is
 * jittered around (8%, 8%) ± 18% to keep cascading spawns visible
 * without piling exactly on top of each other.
 *
 * @param id     pane id for the new spec.
 * @param title  optional pane title.
 * @param zIndex stacking order — caller typically passes `max(existing) + 1`.
 * @return a spec ready to append to [PaneLayout.floatingPanes].
 */
fun randomFloatingPaneSpec(
    id: PaneId,
    title: String? = null,
    zIndex: Int = 1,
): FloatingPaneSpec {
    // Pick a random offset and snap to the same 5% grid the drag handlers
    // use, so spawned floats always land in a grid cell (cascading floats
    // align cleanly when stacked).
    val rawX = (0.10 + Random.nextDouble() * 0.20).coerceIn(0.0, 0.55)
    val rawY = (0.10 + Random.nextDouble() * 0.20).coerceIn(0.0, 0.45)
    val snappedX = kotlin.math.round(rawX * 20.0) / 20.0
    val snappedY = kotlin.math.round(rawY * 20.0) / 20.0
    return FloatingPaneSpec(
        id = id,
        title = title,
        xPct = snappedX,
        yPct = snappedY,
        // Default size also on the grid (45% × 55% = 9 × 11 cells).
        widthPct = 0.45,
        heightPct = 0.55,
        zIndex = zIndex,
    )
}
