/*
 * HoverMenuButton.kt (jsMain)
 * --------------------------
 * Generic "split button with hover-revealed menu" primitive used by the
 * darkness toolkit topbar. The primary action lives on the host button
 * (a plain click); hovering reveals a popover of secondary actions that
 * each commit their own [HoverMenuItem.onSelect] without firing the
 * host's click handler.
 *
 * Termtastic's `New pane` button is the first consumer: clicking adds a
 * terminal (the default), hovering exposes Terminal / Terminal link /
 * File Browser / Git. The toolkit ships the chrome; the host wires the
 * actions through [se.soderbjorn.darkness.web.shell.TabSource.paneAddMenuItems].
 *
 * @see attachHoverMenu
 * @see HoverMenuItem
 */
package se.soderbjorn.darkness.web.shell

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
 */
data class HoverMenuItem(
    val id: String,
    val label: String,
    val iconHtml: String,
    val onSelect: () -> Unit,
)

private const val SHOW_DELAY_MS = 120
private const val HIDE_DELAY_MS = 180

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

    fun cancelShow() {
        showTimerId?.let { window.clearTimeout(it) }
        showTimerId = null
    }
    fun cancelHide() {
        hideTimerId?.let { window.clearTimeout(it) }
        hideTimerId = null
    }

    fun closeMenu() {
        cancelShow(); cancelHide()
        menu?.remove(); menu = null
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
        box.className = "dt-hover-menu"
        box.setAttribute("role", "menu")

        for (item in items) {
            val row = document.createElement("button") as HTMLElement
            row.setAttribute("type", "button")
            row.className = "dt-hover-menu-item"
            row.setAttribute("role", "menuitem")
            row.setAttribute("data-id", item.id)
            row.title = item.label

            val iconWrap = document.createElement("span") as HTMLElement
            iconWrap.className = "dt-hover-menu-icon"
            iconWrap.innerHTML = item.iconHtml

            val labelEl = document.createElement("span") as HTMLElement
            labelEl.className = "dt-hover-menu-label"
            labelEl.textContent = item.label

            row.appendChild(iconWrap)
            row.appendChild(labelEl)
            row.addEventListener("click", { ev: Event ->
                // Stop the click from bubbling to the anchor's onclick
                // (which would fire the default action on top of the
                // item's onSelect).
                ev.stopPropagation()
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
