/**
 * Pane kebab popover primitive.
 *
 * Pairs with the per-pane action strip ([PaneActions], [PaneHeaderSpec])
 * to surface the secondary commands a host doesn't want to spend an icon
 * button on — split horizontally / vertically, expand / restore, close,
 * and any app-specific extras (e.g. termtastic's "open palette",
 * "duplicate worktree"). Hosts wire one [PaneActions] entry whose handler
 * calls [openPaneMenu] with that button as the anchor.
 *
 * The popover is rendered by appending a `.dt-pane-menu` element directly
 * to `<body>` and positioning it `fixed` next to the anchor. It auto-flips
 * vertically and horizontally if it would overflow the viewport. It
 * dismisses on outside click, on `Escape`, on viewport resize, and after
 * any item handler runs.
 *
 * The toolkit owns rendering and dismissal; what the menu does on click
 * lives entirely in each [PaneMenuItem.handler] — this primitive does not
 * mutate any tree.
 *
 * @see PaneMenuSpec
 * @see PaneMenuItem
 * @see PaneMenuItems
 * @see openPaneMenu
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Toolkit DOM class names used by [openPaneMenu]. Stable so host
 * stylesheets can theme them; structure-internal classes are not part of
 * this contract.
 */
object PaneMenuClassNames {
    const val MENU = "dt-pane-menu"
    const val ITEM = "dt-pane-menu-item"
    const val ITEM_ICON = "dt-pane-menu-item-icon"
    const val ITEM_LABEL = "dt-pane-menu-item-label"
    const val ITEM_DANGER = "dt-pane-menu-item-danger"
    const val ITEM_ACTIVE = "dt-pane-menu-item-active"
    const val SEPARATOR = "dt-pane-menu-separator"
    const val FLIPPED_UP = "dt-pane-menu-flip-up"
    const val FLIPPED_LEFT = "dt-pane-menu-flip-left"

    /** Nested flyout surface opened from a row with [PaneMenuItem.submenu]. */
    const val SUBMENU = "dt-pane-menu-submenu"

    /** Row that carries a submenu — gets the trailing chevron affordance. */
    const val ITEM_HAS_SUBMENU = "dt-pane-menu-item-has-submenu"

    /** Trailing `›` chevron span inside a submenu-bearing row. */
    const val ITEM_CHEVRON = "dt-pane-menu-item-chevron"
}

/**
 * One row in a [PaneMenuSpec]. A row is either a clickable item
 * ([isSeparator] = `false`) or a thin horizontal rule ([isSeparator] =
 * `true`). Use the factories on [PaneMenuItems] for the standard set; the
 * data class is open for hosts that need bespoke entries.
 *
 * @property label      the visible text. Ignored when [isSeparator].
 * @property iconHtml   raw SVG/HTML rendered into the leading icon slot.
 *   Empty string means no icon (the icon column still reserves space so
 *   labels line up). Ignored when [isSeparator].
 * @property handler    fires on click. Default no-op so separator items
 *   need not provide one. The popover dismisses **before** invoking the
 *   handler, so handlers that re-render the host don't fight a stale menu.
 * @property isEnabled  when `false` the row is rendered greyed-out and
 *   ignores clicks. Use this for ops that don't apply in the current state
 *   (e.g. "Restore" when nothing is expanded).
 * @property isActive   when `true` the row gets [PaneMenuClassNames.ITEM_ACTIVE]
 *   so the stylesheet can paint a "currently on" state — useful for
 *   toggles such as expand/restore on a single item.
 * @property isDanger   when `true` the row gets [PaneMenuClassNames.ITEM_DANGER]
 *   so the stylesheet can paint it in the destructive-action colour.
 * @property isSeparator when `true` the row is rendered as a thin rule;
 *   all other fields except keys are ignored.
 * @property submenu    optional child rows. When non-null and non-empty the
 *   row renders a trailing `›` chevron and — instead of firing [handler] —
 *   opens a nested `.dt-pane-menu.dt-pane-menu-submenu` flyout beside the
 *   row on hover or click (only one flyout is open at a time; hovering a
 *   sibling row dismisses it after a short grace delay — see
 *   [SUBMENU_CLOSE_GRACE_MS] — so diagonal pointer travel into the flyout
 *   doesn't kill it). Submenu rows support everything a
 *   top-level row does *except* further nesting: exactly one level of
 *   submenu is supported, which is all the toolkit's consumers need (e.g.
 *   termtastic's "Move to tab ▸ <tab list>"). Clicking a submenu row
 *   dismisses the entire popover before its handler runs, matching the
 *   top-level contract. `null` (the default) keeps the plain-row behaviour.
 */
data class PaneMenuItem(
    val label: String,
    val iconHtml: String = "",
    val handler: () -> Unit = {},
    val isEnabled: Boolean = true,
    val isActive: Boolean = false,
    val isDanger: Boolean = false,
    val isSeparator: Boolean = false,
    val submenu: List<PaneMenuItem>? = null,
)

/**
 * Declarative description of a pane kebab popover.
 *
 * @property items the list of rows in display order. Empty lists render
 *   nothing.
 */
data class PaneMenuSpec(
    val items: List<PaneMenuItem>,
)

/**
 * One-line factories for the toolkit's standard pane-menu rows.
 *
 * Hosts compose these with their own entries, e.g.:
 * ```
 * PaneMenuSpec(
 *     items = listOf(
 *         PaneMenuItems.splitHorizontal { ops.split(leaf.id, Horizontal) },
 *         PaneMenuItems.splitVertical   { ops.split(leaf.id, Vertical)   },
 *         PaneMenuItems.separator(),
 *         PaneMenuItems.toggleExpand(isExpanded) { host.toggleExpand(leaf.id) },
 *         PaneMenuItems.separator(),
 *         PaneMenuItems.close { ops.close(leaf.id) },
 *     ),
 * )
 * ```
 *
 * The icons are minimal stroke-based SVGs sized 14×14 to match
 * [PaneActions]' button glyphs, so a kebab popover beneath an action strip
 * looks visually consistent.
 */
object PaneMenuItems {

    /** Vertical bar between two halves — split into left/right. */
    const val ICON_SPLIT_H: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"1.5\"/>" +
            "<line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/></svg>"

    /** Horizontal bar between two halves — split into top/bottom. */
    const val ICON_SPLIT_V: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"1.5\"/>" +
            "<line x1=\"3\" y1=\"12\" x2=\"21\" y2=\"12\"/></svg>"

    /**
     * "Split this pane left/right" — calls [handler] when chosen.
     *
     * **Deprecated.** The toolkit's modern window system uses corner-
     * resize + topbar `+` instead. Existing apps that still want a
     * keyboard-shortcutable split entry can keep using it; new apps
     * should not surface it.
     */
    @Deprecated("Replaced by topbar + button + corner-resize gesture.")
    fun splitHorizontal(handler: () -> Unit): PaneMenuItem = PaneMenuItem(
        label = "Split horizontally",
        iconHtml = ICON_SPLIT_H,
        handler = handler,
    )

    /**
     * "Split this pane top/bottom" — calls [handler] when chosen.
     *
     * **Deprecated.** See [splitHorizontal].
     */
    @Deprecated("Replaced by topbar + button + corner-resize gesture.")
    fun splitVertical(handler: () -> Unit): PaneMenuItem = PaneMenuItem(
        label = "Split vertically",
        iconHtml = ICON_SPLIT_V,
        handler = handler,
    )

    /** "Expand pane" — usually paired with [PaneTreeOps.expand]. */
    fun expand(handler: () -> Unit): PaneMenuItem = PaneMenuItem(
        label = "Expand pane",
        iconHtml = PaneActions.ICON_EXPAND,
        handler = handler,
    )

    /** "Restore pane" — usually paired with [PaneTreeOps.restore]. */
    fun restore(handler: () -> Unit, isEnabled: Boolean = true): PaneMenuItem = PaneMenuItem(
        label = "Restore pane",
        iconHtml = PaneActions.ICON_RESTORE,
        handler = handler,
        isEnabled = isEnabled,
    )

    /**
     * Single toggle row that flips between "Expand pane" and "Restore pane"
     * based on [isExpanded]. Use this when you want one menu entry instead
     * of two — the icon and label swap accordingly, and [PaneMenuItem.isActive]
     * is set in the expanded state so the stylesheet can highlight it.
     *
     * @param isExpanded current expand state for the target leaf
     * @param handler    invoked on click — the host typically calls
     *   [PaneTreeOps.toggleExpand].
     */
    fun toggleExpand(isExpanded: Boolean, handler: () -> Unit): PaneMenuItem = PaneMenuItem(
        label = if (isExpanded) "Restore pane" else "Expand pane",
        iconHtml = if (isExpanded) PaneActions.ICON_RESTORE else PaneActions.ICON_EXPAND,
        handler = handler,
        isActive = isExpanded,
    )

    /** "Close pane" — destructive (rendered in the danger colour). */
    fun close(handler: () -> Unit): PaneMenuItem = PaneMenuItem(
        label = "Close pane",
        iconHtml = PaneActions.ICON_CLOSE,
        handler = handler,
        isDanger = true,
    )

    /** Thin horizontal rule between groups. */
    fun separator(): PaneMenuItem = PaneMenuItem(label = "", isSeparator = true)
}

/**
 * Grace period (ms) before a hover-triggered submenu dismissal takes
 * effect. Moving the pointer diagonally from a submenu-bearing row into
 * its flyout inevitably crosses sibling rows; without a delay each of
 * those crossings would kill the flyout just as the user is about to
 * pick an entry. Entering the flyout (or returning to the anchor row)
 * within this window cancels the pending dismissal. Explicit dismissals
 * (outside click, Escape, scroll, resize, choosing an item) remain
 * immediate and never go through this timer.
 *
 * @see SubmenuHost.scheduleClose
 * @see SubmenuHost.requestOpen
 */
private const val SUBMENU_CLOSE_GRACE_MS = 350

/**
 * Renders [spec] as a popover anchored to [anchor] and attaches it to
 * `document.body`. Returns a closer the caller can invoke to dismiss the
 * popover programmatically; the popover otherwise dismisses itself on
 * outside click, `Escape`, viewport resize, or item activation.
 *
 * Positioning rules:
 *  - Default anchor edge is the anchor's bottom-right; the menu is placed
 *    just below the anchor with its right edge aligned to the anchor's.
 *  - If the menu would extend past the viewport bottom, it flips above
 *    the anchor and gets [PaneMenuClassNames.FLIPPED_UP].
 *  - If the menu would extend past the viewport left edge, it slides
 *    right and gets [PaneMenuClassNames.FLIPPED_LEFT] (so a host
 *    stylesheet can mirror any pointer/triangle decoration).
 *
 * @param anchor the element the popover should appear next to (typically
 *   the kebab button in a [PaneHeaderSpec.actions] strip).
 * @param spec   the declarative menu description.
 * @return a function that closes the popover when called. Calling it more
 *   than once is a no-op.
 */
fun openPaneMenu(anchor: HTMLElement, spec: PaneMenuSpec): () -> Unit {
    if (spec.items.isEmpty()) return { /* nothing to do */ }

    val menu = document.createElement("div") as HTMLElement
    menu.className = PaneMenuClassNames.MENU
    menu.setAttribute("role", "menu")
    // Block mousedown bubbling so a click inside the menu doesn't trip
    // the outside-click handler that fires before the click event runs.
    menu.addEventListener("mousedown", { ev -> ev.stopPropagation() })

    var closed = false
    // Declared before `close` so the close lambda can cancel any pending
    // submenu grace timer; assigned right after `close` exists.
    var submenuHost: SubmenuHost? = null
    lateinit var close: () -> Unit
    val onOutsideMouseDown: (Event) -> Unit = { ev ->
        val target = (ev as MouseEvent).target as? HTMLElement
        if (target == null || !menu.asDynamic().contains(target) as Boolean) {
            close()
        }
    }
    val onKeyDown: (Event) -> Unit = { ev ->
        if ((ev as KeyboardEvent).key == "Escape") {
            ev.preventDefault()
            close()
        }
    }
    val onResize: (Event) -> Unit = { close() }

    close = {
        if (!closed) {
            closed = true
            // Tear down the flyout state first so a pending grace timer
            // can't fire against elements we're about to remove.
            submenuHost?.closeCurrent()
            document.removeEventListener("mousedown", onOutsideMouseDown, true)
            document.removeEventListener("keydown", onKeyDown, true)
            window.removeEventListener("resize", onResize)
            window.removeEventListener("scroll", onResize, true)
            if (menu.parentElement != null) menu.parentElement!!.removeChild(menu)
        }
    }

    val host = SubmenuHost(menu, close)
    submenuHost = host
    for (item in spec.items) menu.appendChild(buildMenuRow(item, close, host))

    document.body?.appendChild(menu)
    positionMenu(menu, anchor)

    document.addEventListener("mousedown", onOutsideMouseDown, true)
    document.addEventListener("keydown", onKeyDown, true)
    window.addEventListener("resize", onResize)
    // Capture-phase scroll listener so scrolling any ancestor closes the
    // menu rather than leaving it floating over now-different content.
    window.addEventListener("scroll", onResize, true)

    return close
}

/**
 * Per-popover submenu coordinator. Owned by one [openPaneMenu] invocation;
 * guarantees at most one nested flyout is mounted at a time and tears it
 * down when a sibling row is hovered (after the [SUBMENU_CLOSE_GRACE_MS]
 * grace delay, so pointer travel into the flyout survives crossing
 * siblings), when a different submenu opens, or — for free, because the
 * flyout is a DOM child of [menu] — when the whole popover closes and
 * removes [menu] from the document.
 *
 * Hover forgiveness: exactly one deferred action (a close or a switch to
 * another row's flyout) can be pending at a time, tracked by
 * [pendingTimer]. Re-entering the open flyout or its anchor row cancels
 * it; hovering yet another sibling replaces it, restarting the clock.
 * Only hover paths are deferred — [closeCurrent] (used by explicit
 * dismissals) always acts immediately.
 *
 * @property menu     the parent `.dt-pane-menu` surface the flyout mounts
 *   into. Mounting inside the menu (rather than `<body>`) keeps the
 *   popover's capture-phase outside-click containment check
 *   (`menu.contains(target)`) valid for submenu clicks without extra
 *   listeners. `position: fixed` still resolves against the viewport
 *   because `.dt-pane-menu` establishes no transform/filter containing
 *   block.
 * @property closeAll closes the entire popover — passed into submenu rows
 *   so activating one dismisses everything, matching top-level rows.
 */
private class SubmenuHost(val menu: HTMLElement, val closeAll: () -> Unit) {
    /** Remover for the currently mounted flyout, or `null` when none is open. */
    private var closeOpen: (() -> Unit)? = null

    /** The row whose flyout is currently open (used to make re-hover a no-op). */
    private var openForRow: HTMLElement? = null

    /**
     * `window.setTimeout` id of the pending deferred action (grace-delay
     * close or deferred switch to another row), or `null` when nothing is
     * pending. Single slot: scheduling anything new replaces it.
     */
    private var pendingTimer: Int? = null

    /**
     * Cancels the pending deferred close/switch, if any. Called when the
     * pointer lands somewhere that should keep the current flyout alive
     * (the flyout itself, or its anchor row). Idempotent.
     */
    fun cancelPending() {
        pendingTimer?.let { window.clearTimeout(it) }
        pendingTimer = null
    }

    /** Immediately dismisses the currently open flyout, if any. Idempotent. */
    fun closeCurrent() {
        cancelPending()
        closeOpen?.invoke()
        closeOpen = null
        openForRow = null
    }

    /**
     * Requests dismissal of the open flyout after the grace delay. Used by
     * plain sibling rows' hover so the flyout survives the pointer's
     * diagonal travel across them; each new hover restarts the clock.
     * Also replaces a pending deferred switch (see [requestOpen]) — the
     * pointer has moved on. No-op when no flyout is open.
     */
    fun scheduleClose() {
        if (closeOpen == null) return
        cancelPending()
        pendingTimer = window.setTimeout({
            pendingTimer = null
            closeCurrent()
        }, SUBMENU_CLOSE_GRACE_MS)
    }

    /**
     * Hover entry point for a submenu-bearing row. Opens [row]'s flyout
     * immediately when no flyout is open; when a *different* row's flyout
     * is open, defers the switch by the grace delay (cancelled if the
     * pointer returns to the open flyout in time) instead of instantly
     * killing it. Re-hovering the already-open row just cancels any
     * pending dismissal.
     *
     * @param row   the submenu-bearing row acting as the anchor.
     * @param items the child rows to render inside the flyout.
     * @see open for the immediate (click) path.
     */
    fun requestOpen(row: HTMLElement, items: List<PaneMenuItem>) {
        if (openForRow == row) {
            cancelPending()
            return
        }
        if (closeOpen == null) {
            open(row, items)
            return
        }
        cancelPending()
        pendingTimer = window.setTimeout({
            pendingTimer = null
            open(row, items)
        }, SUBMENU_CLOSE_GRACE_MS)
    }

    /**
     * Opens (or keeps open) the flyout for [row] showing [items],
     * immediately. Any flyout belonging to a different row is dismissed
     * first. This is the click path; hover goes through [requestOpen].
     *
     * @param row   the submenu-bearing row acting as the anchor.
     * @param items the child rows to render inside the flyout.
     */
    fun open(row: HTMLElement, items: List<PaneMenuItem>) {
        if (openForRow == row) {
            cancelPending()
            return
        }
        closeCurrent()
        val sub = document.createElement("div") as HTMLElement
        sub.className = "${PaneMenuClassNames.MENU} ${PaneMenuClassNames.SUBMENU}"
        sub.setAttribute("role", "menu")
        // Only one nesting level: child rows get no SubmenuHost, so any
        // `submenu` they might carry is ignored (documented on PaneMenuItem).
        for (item in items) sub.appendChild(buildMenuRow(item, closeAll, submenuHost = null))
        // Reaching the flyout keeps it alive: cancel any grace-delay
        // dismissal (or deferred switch) scheduled while the pointer was
        // crossing sibling rows on its way here.
        sub.addEventListener("mouseenter", { cancelPending() })
        menu.appendChild(sub)
        positionSubmenu(sub, menu, row)
        row.setAttribute("aria-expanded", "true")
        openForRow = row
        closeOpen = {
            sub.parentElement?.removeChild(sub)
            row.removeAttribute("aria-expanded")
        }
    }
}

/**
 * Builds one row of the popover: separator, plain item, or submenu-bearing
 * item (trailing chevron; opens its flyout on hover/click via [submenuHost]).
 *
 * @param item        the row description.
 * @param close       closes the entire popover (invoked before a leaf row's
 *   handler runs).
 * @param submenuHost coordinator for nested flyouts, or `null` when this row
 *   is itself inside a flyout (submenus don't nest).
 */
private fun buildMenuRow(
    item: PaneMenuItem,
    close: () -> Unit,
    submenuHost: SubmenuHost?,
): HTMLElement {
    if (item.isSeparator) {
        val sep = document.createElement("div") as HTMLElement
        sep.className = PaneMenuClassNames.SEPARATOR
        sep.setAttribute("role", "separator")
        return sep
    }
    val submenuItems = item.submenu?.takeIf { it.isNotEmpty() && submenuHost != null }
    val btn = document.createElement("button") as HTMLButtonElement
    btn.type = "button"
    btn.setAttribute("role", "menuitem")
    val classes = buildString {
        append(PaneMenuClassNames.ITEM)
        if (item.isDanger) append(' ').append(PaneMenuClassNames.ITEM_DANGER)
        if (item.isActive) append(' ').append(PaneMenuClassNames.ITEM_ACTIVE)
        if (submenuItems != null) append(' ').append(PaneMenuClassNames.ITEM_HAS_SUBMENU)
    }
    btn.className = classes
    btn.disabled = !item.isEnabled
    if (item.isActive) btn.setAttribute("aria-checked", "true")

    val icon = document.createElement("span") as HTMLElement
    icon.className = PaneMenuClassNames.ITEM_ICON
    icon.innerHTML = item.iconHtml
    btn.appendChild(icon)

    val label = document.createElement("span") as HTMLElement
    label.className = PaneMenuClassNames.ITEM_LABEL
    label.textContent = item.label
    btn.appendChild(label)

    if (submenuItems != null) {
        // Submenu-bearing row: trailing chevron; hover/click opens the
        // flyout instead of firing `handler` (which is ignored for these).
        btn.setAttribute("aria-haspopup", "menu")
        val chevron = document.createElement("span") as HTMLElement
        chevron.className = PaneMenuClassNames.ITEM_CHEVRON
        chevron.innerHTML =
            "<svg viewBox=\"0 0 24 24\" width=\"12\" height=\"12\" fill=\"none\" stroke=\"currentColor\" " +
                "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
                "<polyline points=\"9 6 15 12 9 18\"/></svg>"
        btn.appendChild(chevron)
        btn.addEventListener("mouseenter", {
            // Hover defers to the grace-delay coordinator so an already-
            // open sibling flyout isn't yanked away mid-travel.
            if (item.isEnabled) submenuHost!!.requestOpen(btn, submenuItems)
        })
        btn.addEventListener("click", { ev ->
            ev.stopPropagation()
            ev.preventDefault()
            // Click is an explicit gesture — open immediately.
            if (item.isEnabled) submenuHost!!.open(btn, submenuItems)
        })
        return btn
    }

    // Plain row: hovering it dismisses any sibling row's open flyout, so
    // moving the pointer down the parent menu doesn't leave a stale submenu.
    // Deferred by the grace delay: crossing this row on the way to the
    // flyout must not kill it (entering the flyout cancels the timer).
    if (submenuHost != null) {
        btn.addEventListener("mouseenter", { submenuHost.scheduleClose() })
    }

    btn.addEventListener("click", { ev ->
        ev.stopPropagation()
        ev.preventDefault()
        if (!item.isEnabled) return@addEventListener
        // Dismiss before invoking, so handlers that re-render the host do
        // not race against a still-mounted menu.
        close()
        item.handler()
    })
    return btn
}

/**
 * Positions a submenu flyout [sub] beside its anchor [row] in the parent
 * [menu]. Default placement is to the RIGHT of the parent menu, top-aligned
 * with the row (minus the surface padding so the first flyout row lines up
 * with the anchor row). Flips to the left side when the right edge would
 * clip the viewport, and clamps vertically the same way [positionMenu] does.
 *
 * The flyout slightly OVERLAPS the parent menu's edge (rather than sitting
 * a couple of pixels away) so there is no dead strip between the two
 * surfaces for the pointer to fall into on its way across — part of the
 * hover-forgiveness work alongside [SUBMENU_CLOSE_GRACE_MS]. The flyout's
 * higher z-index (`.dt-pane-menu-submenu`) keeps the overlap painting on
 * top of the parent in both placements.
 *
 * Must run after [sub] is in the document so the size reads are real.
 *
 * @param sub  the flyout element (already appended to [menu]).
 * @param menu the parent popover surface.
 * @param row  the submenu-bearing row acting as the anchor.
 */
private fun positionSubmenu(sub: HTMLElement, menu: HTMLElement, row: HTMLElement) {
    // Viewport margin only — between menu and flyout we *overlap* instead.
    val gap = 2.0
    // How far the flyout overlaps the parent menu's edge (covers the 1px
    // border on each surface, leaving no dead gap for the pointer).
    val overlap = 2.0
    val viewportW = window.innerWidth.toDouble()
    val viewportH = window.innerHeight.toDouble()
    sub.style.position = "fixed"
    sub.style.left = "0px"
    sub.style.top = "0px"
    sub.style.visibility = "hidden"

    val menuRect = menu.getBoundingClientRect()
    val rowRect = row.getBoundingClientRect()
    val subW = sub.offsetWidth.toDouble()
    val subH = sub.offsetHeight.toDouble()

    var left = menuRect.right - overlap
    // Align the flyout's first row with the anchor row: offset by the
    // surface padding (4px, see .dt-pane-menu) plus the 1px border.
    var top = rowRect.top - 5.0

    if (left + subW > viewportW - gap) {
        left = (menuRect.left - subW + overlap).coerceAtLeast(gap)
        sub.classList.add(PaneMenuClassNames.FLIPPED_LEFT)
    }
    if (top + subH > viewportH - gap) {
        top = (viewportH - subH - gap).coerceAtLeast(gap)
    }
    if (top < gap) top = gap

    sub.style.left = "${left}px"
    sub.style.top = "${top}px"
    sub.style.visibility = ""
}

/**
 * Positions [menu] next to [anchor]. Reads the anchor's bounding rect and
 * the menu's intrinsic size (the menu must already be in the document so
 * the read returns real values), then sets `left`/`top` on the menu to
 * place it. Flips above the anchor if the bottom would clip; nudges right
 * if the left would clip.
 */
private fun positionMenu(menu: HTMLElement, anchor: HTMLElement) {
    val gap = 4.0
    val viewportW = window.innerWidth.toDouble()
    val viewportH = window.innerHeight.toDouble()
    // Force `position: fixed` *before* measuring offsetWidth/Height so the
    // measurement happens against the final layout context. Without this,
    // the menu's first measurement runs as a static-positioned body child
    // (at left:0, top:0), which can confuse intrinsic-width readings on
    // some flex/grid descendants and leave the menu misanchored.
    menu.style.position = "fixed"
    menu.style.left = "0px"
    menu.style.top = "0px"
    menu.style.visibility = "hidden"

    val anchorRect = anchor.getBoundingClientRect()
    val menuW = menu.offsetWidth.toDouble()
    val menuH = menu.offsetHeight.toDouble()

    var left = anchorRect.right - menuW
    var top = anchorRect.bottom + gap
    var flippedUp = false
    var flippedLeft = false

    if (top + menuH > viewportH - gap) {
        val above = anchorRect.top - menuH - gap
        if (above >= gap) {
            top = above
            flippedUp = true
        } else {
            // Neither side fits cleanly — clamp so the menu stays on-screen.
            top = (viewportH - menuH - gap).coerceAtLeast(gap)
        }
    }
    if (left < gap) {
        left = anchorRect.left.coerceAtLeast(gap)
        flippedLeft = true
    }
    if (left + menuW > viewportW - gap) {
        left = (viewportW - menuW - gap).coerceAtLeast(gap)
    }

    menu.style.left = "${left}px"
    menu.style.top = "${top}px"
    menu.style.visibility = ""
    if (flippedUp) menu.classList.add(PaneMenuClassNames.FLIPPED_UP)
    if (flippedLeft) menu.classList.add(PaneMenuClassNames.FLIPPED_LEFT)
}
