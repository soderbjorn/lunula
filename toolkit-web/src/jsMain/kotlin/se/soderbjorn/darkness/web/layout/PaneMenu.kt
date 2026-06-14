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
package se.soderbjorn.darkness.web.layout

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
 */
data class PaneMenuItem(
    val label: String,
    val iconHtml: String = "",
    val handler: () -> Unit = {},
    val isEnabled: Boolean = true,
    val isActive: Boolean = false,
    val isDanger: Boolean = false,
    val isSeparator: Boolean = false,
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
            document.removeEventListener("mousedown", onOutsideMouseDown, true)
            document.removeEventListener("keydown", onKeyDown, true)
            window.removeEventListener("resize", onResize)
            window.removeEventListener("scroll", onResize, true)
            if (menu.parentElement != null) menu.parentElement!!.removeChild(menu)
        }
    }

    for (item in spec.items) menu.appendChild(buildMenuRow(item, close))

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

/** Builds one row of the popover: separator or icon-button-style item. */
private fun buildMenuRow(item: PaneMenuItem, close: () -> Unit): HTMLElement {
    if (item.isSeparator) {
        val sep = document.createElement("div") as HTMLElement
        sep.className = PaneMenuClassNames.SEPARATOR
        sep.setAttribute("role", "separator")
        return sep
    }
    val btn = document.createElement("button") as HTMLButtonElement
    btn.type = "button"
    btn.setAttribute("role", "menuitem")
    val classes = buildString {
        append(PaneMenuClassNames.ITEM)
        if (item.isDanger) append(' ').append(PaneMenuClassNames.ITEM_DANGER)
        if (item.isActive) append(' ').append(PaneMenuClassNames.ITEM_ACTIVE)
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
