/*
 * HoverMenuButton.kt (jsMain)
 * --------------------------
 * Generic "split button with hover-revealed menu" primitive used by the
 * lunula toolkit topbar. The primary action lives on the host button
 * (a plain click); hovering reveals a popover of secondary actions that
 * each commit their own [HoverMenuItem.onSelect] without firing the
 * host's click handler.
 *
 * Termtastic's `New pane` button is the first consumer: clicking adds a
 * terminal (the default), hovering exposes Terminal / Terminal link /
 * File Browser / Git. The toolkit ships the chrome; the host wires the
 * actions through [se.soderbjorn.lunula.web.shell.TabSource.paneAddMenuItems].
 *
 * @see attachHoverMenu
 * @see HoverMenuItem
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * One row of a hover-revealed menu attached to a topbar icon button.
 *
 * Item icons reuse whatever inline SVG markup the host already has for
 * the corresponding action — the toolkit just slots the SVG into a
 * fixed-size `.dt-hover-menu-icon` container and lets CSS size it.
 *
 * @property id stable identifier used for keys / debugging; never shown.
 * @property label visible text rendered on the right side of the row.
 * @property iconHtml inline SVG (or any HTML) rendered in the left slot.
 * @property onSelect invoked once when the user clicks the row. The
 *   helper closes the menu immediately afterwards and stops the click
 *   from bubbling to the host button's `onclick`.
 * @property isSeparator when `true`, this entry renders as a thin,
 *   non-interactive divider between groups instead of a clickable row;
 *   [label] / [iconHtml] / [onSelect] are ignored. Use [hoverMenuSeparator] to
 *   build one. Lets callers group items (e.g. "New tab" | panes | "New
 *   workspace"). Declared before [onSelect] so the common trailing-lambda call
 *   form `HoverMenuItem(id, label, icon) { … }` still binds the lambda to
 *   [onSelect].
 * @property children when non-empty, this row opens a **flyout submenu** of
 *   these items instead of committing an action: the row grows a right-pointing
 *   chevron, hovering (or clicking) it reveals the children beside it, and
 *   [onSelect] is never called. The same shape the tab overflow menu's "Move to
 *   world" row uses, offered here because a row whose choice is a list — one
 *   entry per project, per workspace, per connection — is otherwise forced to
 *   flatten that list into the parent menu, where it crowds out every other
 *   entry as the list grows.
 *
 *   One level deep: a child's own [children] are ignored. Nothing has needed
 *   two, and a second flyout has nowhere to go on a topbar dropdown already
 *   anchored to the right edge of the window.
 *
 *   Declared before [onSelect] for [isSeparator]'s reason — the trailing-lambda
 *   call form keeps binding to [onSelect].
 * @property isDefault marks the row that does what pressing the anchor button
 *   itself does. It wears the zone's accent as a standing fill — not a hover, a
 *   statement — so a menu opened only to look at says which row the button is
 *   already pointed at.
 *
 *   This is the one fill a menu of pure actions has to spend, and it is spent
 *   here for the reason a value list spends it on the current value: both
 *   answer "which of these is the live one?". Without it the "+" dropdown is a
 *   flat list in which the button's own behaviour is invisible, and a user who
 *   wants the common thing has no way to learn they could have just clicked.
 *
 *   At most one row should carry it; the toolkit does not enforce that, it
 *   simply paints every row that does. Defaults `false`.
 */
data class HoverMenuItem(
    val id: String,
    val label: String,
    val iconHtml: String,
    val isSeparator: Boolean = false,
    val children: List<HoverMenuItem> = emptyList(),
    val isDefault: Boolean = false,
    val onSelect: () -> Unit,
)

/**
 * A non-interactive divider row for a hover menu.
 *
 * Convenience over the [HoverMenuItem] constructor so callers can drop a
 * separator between groups without spelling out empty label/icon/onSelect.
 *
 * @param id stable identifier (never shown); make it unique within the menu.
 * @return a separator [HoverMenuItem].
 */
fun hoverMenuSeparator(id: String): HoverMenuItem =
    HoverMenuItem(id = id, label = "", iconHtml = "", isSeparator = true, onSelect = {})

private const val SHOW_DELAY_MS = 120
private const val HIDE_DELAY_MS = 180

/**
 * The right-pointing chevron flagging a row that opens a flyout submenu.
 *
 * The same mark the tab overflow menu's "Move to world" row wears, at the size
 * the hover menu's own rows are drawn: two menus in one chrome should not
 * disagree about what "there is more behind this row" looks like.
 */
private const val SUBMENU_CARET: String =
    "<svg viewBox=\"0 0 24 24\" width=\"12\" height=\"12\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><path d=\"M9 6l6 6-6 6\"/></svg>"

/**
 * Module-level suppression rect, set when the user clicks a hover-menu
 * anchor (committing the default action). Subsequent `mouseenter` events
 * on any hover-menu anchor are ignored as long as the cursor is still
 * inside this rect. Cleared the moment a global `mousemove` carries the
 * cursor outside the rect.
 *
 * Why module-level: the topbar's trailing area is rebuilt on every
 * re-render, so the post-click anchor is a *different* DOM element from
 * the one that was clicked. Without cross-instance state the fresh
 * anchor's `mouseenter` (re-fired by the browser when the rebuilt
 * element appears under the cursor) immediately re-opens the dropdown,
 * producing a visible flash. The rect-based check naturally accommodates
 * minor layout shifts because the rebuilt button lands in roughly the
 * same screen position.
 */
private var suppressOpenRectLeft: Double = 0.0
private var suppressOpenRectTop: Double = 0.0
private var suppressOpenRectRight: Double = 0.0
private var suppressOpenRectBottom: Double = 0.0
private var suppressOpenActive: Boolean = false
private var suppressOpenCleanupInstalled: Boolean = false

/**
 * Lazily install one document-level `mousemove` listener that clears
 * [suppressOpenActive] once the cursor exits the recorded rect. Idempotent.
 */
private fun ensureSuppressCleanupInstalled() {
    if (suppressOpenCleanupInstalled) return
    suppressOpenCleanupInstalled = true
    document.addEventListener("mousemove", { ev: Event ->
        if (!suppressOpenActive) return@addEventListener
        val me = ev as? MouseEvent ?: return@addEventListener
        if (me.clientX < suppressOpenRectLeft ||
            me.clientX > suppressOpenRectRight ||
            me.clientY < suppressOpenRectTop ||
            me.clientY > suppressOpenRectBottom
        ) {
            suppressOpenActive = false
        }
    })
}

/**
 * Attaches a hover-revealed menu to [anchor].
 *
 * Behaviour:
 * - `mouseenter` on the anchor schedules a [SHOW_DELAY_MS] timer; on
 *   fire, the menu element is built (via [itemsProvider]) and positioned
 *   under the anchor's bottom-right corner.
 * - `mouseleave` from either the anchor or the menu schedules a
 *   [HIDE_DELAY_MS] timer; re-entering either surface before it fires
 *   cancels the close.
 * - Clicking a row fires the item's `onSelect`, closes the menu, and
 *   stops the click from propagating to [anchor]'s click handler.
 * - Pressing Escape or clicking outside both anchor and menu closes.
 * - Only one hover-menu is open at a time; opening a new one tears down
 *   any existing `.dt-hover-menu` first.
 *
 * The menu lives directly under `document.body` (not nested inside the
 * topbar) so its `position: fixed` rect isn't clipped by overflow on
 * ancestor containers.
 *
 * Called by [buildNewWindowSplitButton] and any future split-button
 * factory that needs the same primary-action-with-hover-extras pattern.
 *
 * @param anchor the host button to attach the menu to. The function
 *   does not mutate the button's other handlers; click still fires
 *   normally except when initiated from inside the menu.
 * @param itemsProvider lazy provider evaluated every time the menu
 *   opens, so callers can return different items depending on which
 *   tab / pane is currently active.
 * @return the [anchor] unchanged, for fluent composition.
 * @see HoverMenuItem
 */
fun attachHoverMenu(
    anchor: HTMLElement,
    itemsProvider: () -> List<HoverMenuItem>,
): HTMLElement {
    var menu: HTMLElement? = null
    var showTimerId: Int? = null
    var hideTimerId: Int? = null
    var outsideClickHandler: ((Event) -> Unit)? = null
    var escHandler: ((Event) -> Unit)? = null
    // The one open flyout, and the row it belongs to. At most one, so hovering
    // a second parent row replaces the first rather than leaving two panels
    // stacked over each other — see openFlyout.
    var flyout: HTMLElement? = null
    var flyoutOwner: HTMLElement? = null
    /** The anchor's `title`, held while the menu is up so it can be put back. */
    var suppressedTooltip: String? = null

    fun cancelShow() {
        showTimerId?.let { window.clearTimeout(it) }
        showTimerId = null
    }
    fun cancelHide() {
        hideTimerId?.let { window.clearTimeout(it) }
        hideTimerId = null
    }

    fun closeFlyout() {
        flyout?.remove()
        flyout = null
        flyoutOwner = null
    }

    fun closeMenu() {
        cancelShow(); cancelHide()
        closeFlyout()
        menu?.remove(); menu = null
        // The anchor stops looking pressed. Same attribute the menu triggers
        // use (see MenuTrigger.kt), so one stylesheet rule paints both and the
        // accessibility tree cannot drift away from the paint.
        anchor.setAttribute("aria-expanded", "false")
        suppressedTooltip?.let { anchor.setAttribute("title", it) }
        suppressedTooltip = null
        outsideClickHandler?.let { document.removeEventListener("click", it) }
        outsideClickHandler = null
        escHandler?.let { document.removeEventListener("keydown", it) }
        escHandler = null
    }

    fun openMenu() {
        cancelShow(); cancelHide()
        // Only one menu open at a time — tear down any stale instance.
        val existing = document.querySelectorAll(".dt-hover-menu")
        for (i in 0 until existing.length) (existing.item(i) as HTMLElement).remove()

        val items = itemsProvider()
        if (items.isEmpty()) return

        val box = document.createElement("div") as HTMLElement
        // Chrome zone: this menu is only ever raised from the top bar, so its
        // highlighted row takes the chrome accent rather than the content one.
        // See the menu surface block in `lunula.css`.
        box.className = "dt-hover-menu ${MenuTriggerClassNames.CHROME}"
        box.setAttribute("role", "menu")
        anchor.setAttribute("aria-expanded", "true")
        // Park the anchor's tooltip for as long as the menu is up. The pointer
        // is resting on the button — that is what opened the menu — so the
        // browser fires its tooltip a beat later, on top of the panel, naming a
        // button the user can plainly see and hiding the rows they opened it
        // for. Restored on close, so the affordance survives for the next
        // hover that does NOT open anything.
        suppressedTooltip = anchor.getAttribute("title")
        if (suppressedTooltip != null) anchor.removeAttribute("title")

        /**
         * Show [item]'s children beside [row], replacing whatever flyout is up.
         *
         * Appended to `<body>` and positioned `fixed`, not nested inside the
         * menu: `.dt-hover-menu` scrolls (`overflow-y: auto`), and a child
         * panel inside a scrolling box is clipped by it — the submenu would be
         * cut off at the menu's own edge, which is the one place it must not be.
         */
        fun openFlyout(row: HTMLElement, item: HoverMenuItem) {
            if (flyoutOwner === row) return
            closeFlyout()
            val panel = document.createElement("div") as HTMLElement
            panel.className = "dt-hover-menu dt-hover-menu-flyout ${MenuTriggerClassNames.CHROME}"
            panel.setAttribute("role", "menu")
            for (child in item.children) {
                if (child.isSeparator) {
                    val sep = document.createElement("div") as HTMLElement
                    sep.className = "dt-hover-menu-separator"
                    sep.setAttribute("role", "separator")
                    sep.setAttribute("data-id", child.id)
                    panel.appendChild(sep)
                    continue
                }
                val childRow = document.createElement("button") as HTMLElement
                childRow.setAttribute("type", "button")
                childRow.className = "dt-hover-menu-item" +
                    if (child.isDefault) " ${MenuTriggerClassNames.SELECTED}" else ""
                childRow.setAttribute("role", "menuitem")
                childRow.setAttribute("data-id", child.id)
                // No `title`, for the reason the parent rows have none.
                val childIcon = document.createElement("span") as HTMLElement
                childIcon.className = "dt-hover-menu-icon"
                childIcon.innerHTML = child.iconHtml
                val childLabel = document.createElement("span") as HTMLElement
                childLabel.className = "dt-hover-menu-label"
                childLabel.textContent = child.label
                childRow.appendChild(childIcon)
                childRow.appendChild(childLabel)
                childRow.addEventListener("click", { ev: Event ->
                    ev.stopPropagation()
                    closeMenu()
                    child.onSelect()
                })
                panel.appendChild(childRow)
            }
            document.body?.appendChild(panel)
            // Left of the parent menu by preference: the "+" is at the trailing
            // edge of the topbar, so its dropdown is already flush right and
            // there is rarely room on that side. Flip only when the left would
            // run off screen.
            val menuRect = box.getBoundingClientRect()
            val rowRect = row.getBoundingClientRect()
            val panelRect = panel.getBoundingClientRect()
            val left = (menuRect.left - panelRect.width - 4)
                .takeIf { it >= 4.0 }
                ?: (menuRect.right + 4).coerceAtMost(window.innerWidth - panelRect.width - 4.0)
            val top = rowRect.top
                .coerceAtMost(window.innerHeight - panelRect.height - 8.0)
                .coerceAtLeast(4.0)
            panel.style.left = "${left}px"
            panel.style.top = "${top}px"
            panel.addEventListener("mouseenter", { _: Event -> cancelHide() })
            panel.addEventListener("mouseleave", { _: Event ->
                cancelHide()
                hideTimerId = window.setTimeout({ closeMenu() }, HIDE_DELAY_MS)
            })
            flyout = panel
            flyoutOwner = row
        }

        for (item in items) {
            if (item.isSeparator) {
                val sep = document.createElement("div") as HTMLElement
                sep.className = "dt-hover-menu-separator"
                sep.setAttribute("role", "separator")
                sep.setAttribute("data-id", item.id)
                box.appendChild(sep)
                continue
            }
            val row = document.createElement("button") as HTMLElement
            row.setAttribute("type", "button")
            // The default action wears the zone's accent; see HoverMenuItem.isDefault.
            row.className = "dt-hover-menu-item" +
                if (item.isDefault) " ${MenuTriggerClassNames.SELECTED}" else ""
            row.setAttribute("role", "menuitem")
            row.setAttribute("data-id", item.id)
            // Deliberately no `title`. It repeated the row's own visible text, so
            // the browser drew a second copy of the label in a native tooltip
            // that floated over the panel it was opened from — an unthemed box
            // saying nothing, covering rows the user is trying to read.

            val iconWrap = document.createElement("span") as HTMLElement
            iconWrap.className = "dt-hover-menu-icon"
            iconWrap.innerHTML = item.iconHtml

            val labelEl = document.createElement("span") as HTMLElement
            labelEl.className = "dt-hover-menu-label"
            labelEl.textContent = item.label

            row.appendChild(iconWrap)
            row.appendChild(labelEl)
            val hasChildren = item.children.isNotEmpty()
            if (hasChildren) {
                row.classList.add("dt-hover-menu-submenu-parent")
                val caret = document.createElement("span") as HTMLElement
                caret.className = "dt-hover-menu-submenu-caret"
                caret.innerHTML = SUBMENU_CARET
                row.appendChild(caret)
            }
            // Hovering any row dismisses a sibling's flyout, so moving down the
            // menu never leaves a panel hanging beside a row the cursor has
            // left. A parent row opens its own in the same gesture.
            row.addEventListener("mouseenter", { _: Event ->
                cancelHide()
                if (hasChildren) openFlyout(row, item) else closeFlyout()
            })
            row.addEventListener("click", { ev: Event ->
                // Stop the click from bubbling to the anchor's onclick
                // (which would fire the default action on top of the
                // item's onSelect).
                ev.stopPropagation()
                // A parent row is a container, not an action: clicking it
                // opens the flyout for anyone not using hover (touch, or a
                // pointer that arrived by keyboard) rather than committing
                // something the row never offered.
                if (hasChildren) {
                    openFlyout(row, item)
                    return@addEventListener
                }
                closeMenu()
                item.onSelect()
            })
            box.appendChild(row)
        }

        // Anchor below the button's bottom-right corner, clamped to the
        // viewport. Mirrors the positioning math used by
        // openLayoutPresetGrid so the two popovers feel consistent.
        document.body?.appendChild(box)
        val anchorRect = anchor.getBoundingClientRect()
        val menuRect = box.getBoundingClientRect()
        val left = (anchorRect.right - menuRect.width).coerceAtLeast(4.0)
        box.style.left = "${left}px"
        box.style.top = "${anchorRect.bottom + 4}px"

        box.addEventListener("mouseenter", { _: Event -> cancelHide() })
        box.addEventListener("mouseleave", { _: Event ->
            cancelHide()
            hideTimerId = window.setTimeout({ closeMenu() }, HIDE_DELAY_MS)
        })

        val outside: (Event) -> Unit = handler@{ ev ->
            val target = ev.target as? HTMLElement ?: return@handler
            if (box.contains(target) || anchor.contains(target)) return@handler
            // The flyout is a sibling of the menu under <body>, not a
            // descendant of it (see openFlyout), so "inside" has to name it
            // explicitly or clicking one would close the menu it belongs to.
            if (flyout?.contains(target) == true) return@handler
            closeMenu()
        }
        outsideClickHandler = outside
        document.addEventListener("click", outside)

        val onEsc: (Event) -> Unit = handler@{ ev ->
            if ((ev as? KeyboardEvent)?.key == "Escape") closeMenu()
        }
        escHandler = onEsc
        document.addEventListener("keydown", onEsc)

        menu = box
    }

    anchor.addEventListener("mouseenter", { ev: Event ->
        cancelHide()
        if (menu != null) return@addEventListener
        // If the user just clicked a hover-menu anchor (committing the
        // default action), don't auto-reopen the dropdown while the
        // cursor is still parked on the same spot — they'd have to flick
        // the mouse away to dismiss the menu they explicitly dismissed
        // by clicking. The suppression clears as soon as the mouse moves
        // outside the recorded rect (handled by the global cleanup).
        if (suppressOpenActive) {
            val me = ev as? MouseEvent
            if (me != null &&
                me.clientX >= suppressOpenRectLeft &&
                me.clientX <= suppressOpenRectRight &&
                me.clientY >= suppressOpenRectTop &&
                me.clientY <= suppressOpenRectBottom
            ) {
                return@addEventListener
            }
            // Cursor is outside the suppressed rect — clear so we don't
            // keep blocking unrelated future opens.
            suppressOpenActive = false
        }
        cancelShow()
        showTimerId = window.setTimeout({ openMenu() }, SHOW_DELAY_MS)
    })
    anchor.addEventListener("mouseleave", { ev: Event ->
        cancelShow()
        // Don't immediately close — give the user time to land in the
        // menu. The menu's own mouseenter cancels this timer.
        val related = (ev as? MouseEvent)?.relatedTarget as? HTMLElement
        if (related != null && menu?.contains(related) == true) return@addEventListener
        cancelHide()
        hideTimerId = window.setTimeout({ closeMenu() }, HIDE_DELAY_MS)
    })
    // Clicking the anchor itself commits the default action — the user
    // has already made a choice, so dismiss the hover menu immediately
    // instead of leaving it dangling below the button. Also cancels any
    // pending open timer so a quick hover-then-click can't race a menu
    // open in after the click fired.
    anchor.addEventListener("click", { _: Event ->
        cancelShow()
        if (menu != null) closeMenu()
        // Arm cross-instance suppression so the dropdown doesn't
        // immediately reappear after a topbar re-render (which replaces
        // the anchor element). Cleared by the global mousemove handler
        // once the cursor leaves the recorded rect.
        val rect = anchor.getBoundingClientRect()
        suppressOpenRectLeft = rect.left
        suppressOpenRectTop = rect.top
        suppressOpenRectRight = rect.right
        suppressOpenRectBottom = rect.bottom
        suppressOpenActive = true
        ensureSuppressCleanupInstalled()
    })

    return anchor
}
