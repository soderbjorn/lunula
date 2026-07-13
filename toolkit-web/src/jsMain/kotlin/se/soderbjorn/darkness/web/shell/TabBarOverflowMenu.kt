/*
 * TabBarOverflowMenu.kt (jsMain)
 * -------------------------------
 * Two related tab-bar menus (issue #65 split the old single `⋮` menu):
 *
 *  1. The per-tab **dot menu** (`appendTabDotMenu`) — a small `⋮` button at
 *     each tab's right corner holding the actions that belong to *that*
 *     specific tab:
 *       - "Rename"                 (when the tab is renamable)
 *       - "Close"                  (when `onClose` is set)
 *       - "Hide / Show in tab bar" (when `onSetHidden` is set)
 *       - "Hide / Show in side bar"(when `onSetHiddenFromSidebar` is set)
 *     Called from [buildTabElement] for every visible tab.
 *
 *  2. The far-right **overflow menu** (`appendTabBarOverflowMenu`) — the
 *     `⋮` button after the last tab. It now holds only the cross-tab
 *     concern that has no home on an individual tab: the list of currently
 *     hidden ("Unlisted") tabs, each row activating the tab and offering a
 *     "Show in tab bar" affordance to un-hide it. Renders nothing (no
 *     button) when there are no hidden tabs.
 *
 * "New tab" no longer lives in either menu — it moved to the topbar "New"
 * (`+`) split-button (see AppShellMount.kt).
 *
 * Both menus mount their dropdown list on `document.body` (not the tab bar
 * itself) so the bar's horizontal-overflow scroll doesn't clip it; the
 * shared `.dt-tabbar-menu-list` class means only one dropdown is open at a
 * time across every tab dot menu and the overflow menu.
 *
 * @see TabBarSpec.showOverflowMenu
 * @see appendTabBarOverflowMenu
 * @see appendTabDotMenu
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Append the per-tab `⋮` dot menu (button + dropdown list) to a single
 * tab element. Called from [buildTabElement] for every visible tab.
 *
 * The menu surfaces only the actions that target *this* tab — Rename,
 * Close, Hide/Show in tab bar, Hide/Show in side bar — each gated on the
 * matching [TabBarCallbacks] being non-null (and, for Rename, on
 * [TabSpec.isRenamable]). When none apply, no button is rendered so
 * read-only tabs stay chromeless.
 *
 * @param tabEl the `.dt-tab` element to attach the menu button to (the
 *   dropdown list is mounted on `document.body`).
 * @param tab   the tab this menu acts on.
 * @param spec  the parent spec; supplies the callbacks + the label seed
 *   for inline rename.
 */
internal fun appendTabDotMenu(tabEl: HTMLElement, tab: TabSpec, spec: TabBarSpec) {
    val cb = spec.callbacks
    val canRename = tab.isRenamable && cb.onRename != null
    val canClose = cb.onClose != null
    val canMoveWorld = cb.onMoveToWorld != null && cb.moveToWorlds.isNotEmpty()
    val canHide = cb.onSetHidden != null
    val canHideSidebar = cb.onSetHiddenFromSidebar != null
    if (!canRename && !canClose && !canMoveWorld && !canHide && !canHideSidebar) return

    val menuWrap = document.createElement("div") as HTMLElement
    menuWrap.className = "dt-tab-menu"

    val menuBtn = document.createElement("button") as HTMLElement
    menuBtn.className = "dt-tab-menu-button"
    menuBtn.setAttribute("type", "button")
    menuBtn.title = "Tab actions"
    menuBtn.setAttribute("aria-label", "Tab actions")
    menuBtn.textContent = "⋮"
    // Don't let a mousedown on the menu button initiate the parent tab's
    // HTML5 drag (mirrors the close-button guard in buildTabElement).
    menuBtn.addEventListener("mousedown", { ev: Event -> ev.stopPropagation() })

    val menuList = document.createElement("div") as HTMLElement
    menuList.className = "dt-tabbar-menu-list dt-tab-menu-list"

    val closeMenu = wireMenuToggle(menuWrap, menuBtn, menuList)

    // The dot menu only exists on tabs that are actually in the strip (a
    // hidden tab is rendered only while active), so the label is always in
    // the DOM here and inline rename can target it.
    if (canRename) {
        menuList.appendChild(menuRow("Rename", ICON_RENAME) {
            closeMenu()
            val label = tabEl.querySelector(".${TabBarClassNames.TAB_LABEL}") as? HTMLElement
            if (label != null) triggerInlineRename(label, tab, spec)
        })
    }

    if (canClose) {
        menuList.appendChild(menuRow("Close", ICON_CLOSE_TAB) {
            closeMenu()
            requestTabClose(tab, cb)
        })
    }

    // "Move to world" opens a submenu listing every other world (issue: move a
    // tab between worlds). Sits with the act-on-this-tab group.
    if (canMoveWorld) {
        appendMoveToWorldSubmenu(menuList, tab, cb, closeMenu)
    }

    // Divider between the act-on-this-tab group (Rename / Close / Move to world)
    // and the visibility group (Hide / Show in tab bar / side bar).
    if ((canRename || canClose || canMoveWorld) && (canHide || canHideSidebar)) {
        menuList.appendChild(menuSeparator())
    }

    if (canHide) {
        val label = if (tab.isHidden) "Show in tab bar" else "Hide in tab bar"
        val icon = if (tab.isHidden) ICON_SHOW_TAB else ICON_HIDE_TAB
        menuList.appendChild(menuRow(label, icon) {
            closeMenu()
            cb.onSetHidden!!.invoke(tab.id, !tab.isHidden)
        })
    }

    if (canHideSidebar) {
        val label = if (tab.isHiddenFromSidebar) "Show in side bar" else "Hide in side bar"
        val icon = if (tab.isHiddenFromSidebar) ICON_SHOW_TAB else ICON_HIDE_TAB
        menuList.appendChild(menuRow(label, icon) {
            closeMenu()
            cb.onSetHiddenFromSidebar!!.invoke(tab.id, !tab.isHiddenFromSidebar)
        })
    }

    menuWrap.appendChild(menuBtn)
    tabEl.appendChild(menuWrap)
    document.body?.appendChild(menuList)
}

/**
 * Append the "Move to world" parent row plus its hover/click **flyout submenu**
 * (one row per other world) to a tab's dot-menu [menuList]. The flyout is a
 * nested `.dt-tabbar-menu-list` so it shares the parent list's show/hide and
 * teardown lifecycle (the parent going `display:none` hides it; a rerender's
 * stale-list sweep removes it). Picking a world fires
 * [TabBarCallbacks.onMoveToWorld] and closes the whole menu; hovering the
 * parent row opens the flyout, leaving both (after a short grace) closes it.
 *
 * Only called when [TabBarCallbacks.onMoveToWorld] is set and
 * [TabBarCallbacks.moveToWorlds] is non-empty (see [appendTabDotMenu]).
 *
 * @param menuList  the tab's dot-menu dropdown to append the row + flyout to.
 * @param tab       the tab being moved.
 * @param cb        the tab-bar callbacks (supplies the worlds + move handler).
 * @param closeMenu closes the whole dot menu after a world is picked.
 */
private fun appendMoveToWorldSubmenu(
    menuList: HTMLElement,
    tab: TabSpec,
    cb: TabBarCallbacks,
    closeMenu: () -> Unit,
) {
    val onMove = cb.onMoveToWorld ?: return

    val row = document.createElement("div") as HTMLElement
    row.className = "dt-tabbar-menu-item dt-tabbar-menu-submenu-parent"
    val icon = document.createElement("span") as HTMLElement
    icon.className = "dt-tabbar-menu-item-icon"
    icon.innerHTML = ICON_MOVE_WORLD
    val label = document.createElement("span") as HTMLElement
    label.className = "dt-tabbar-menu-item-label"
    label.textContent = "Move to workspace"
    val caret = document.createElement("span") as HTMLElement
    caret.className = "dt-tabbar-menu-submenu-caret"
    caret.innerHTML = ICON_CARET_RIGHT
    row.appendChild(icon)
    row.appendChild(label)
    row.appendChild(caret)

    val flyout = document.createElement("div") as HTMLElement
    flyout.className = "dt-tabbar-menu-list dt-tabbar-menu-submenu-list"
    for (w in cb.moveToWorlds) {
        flyout.appendChild(menuRow(w.label.ifBlank { "(untitled)" }, ICON_WORLD) {
            closeMenu()
            onMove(tab.id, w.id)
        })
    }

    var hideTimer: Int? = null
    fun cancelHide() {
        hideTimer?.let { window.clearTimeout(it) }
        hideTimer = null
    }
    fun open() {
        cancelHide()
        // Hang the flyout off the list's right edge, aligned to this row's top.
        flyout.style.top = "${row.offsetTop}px"
        flyout.classList.remove("dt-flip-left")
        flyout.classList.add("dt-open")
        // Flip to the parent's LEFT side if the right-hand flyout would spill
        // off the viewport (a tab whose dot menu sits near the right edge).
        val rect = flyout.getBoundingClientRect()
        if (rect.right > window.innerWidth - 4.0) flyout.classList.add("dt-flip-left")
    }
    fun scheduleClose() {
        cancelHide()
        hideTimer = window.setTimeout({ flyout.classList.remove("dt-open") }, 220)
    }
    row.addEventListener("mouseenter", { _: Event -> open() })
    row.addEventListener("mouseleave", { _: Event -> scheduleClose() })
    flyout.addEventListener("mouseenter", { _: Event -> cancelHide() })
    flyout.addEventListener("mouseleave", { _: Event -> scheduleClose() })
    // Click toggles too, so the submenu is reachable without a hover (touch / a
    // deliberate click). Stop propagation so the outside-dismiss doesn't fire.
    row.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        if (flyout.classList.contains("dt-open")) flyout.classList.remove("dt-open") else open()
    })

    menuList.appendChild(row)
    menuList.appendChild(flyout)
}

/**
 * Append the `⋮` overflow menu (button + dropdown list) to the given [host]
 * element — the tab **strip**, so the button sits right after the last tab
 * and reads as part of the tab bar (rather than floating off to the right).
 * Called from [renderTabBar] when [TabBarSpec.showOverflowMenu] is true;
 * callers don't normally invoke this directly.
 *
 * Since issue #65 the menu lists only the hidden ("Unlisted") tabs — the
 * per-tab actions moved to each tab's dot menu and "New tab" moved to the
 * topbar "New" button. When no tabs are hidden the menu has nothing to
 * show, so this function renders no button at all (an empty `⋮` would be
 * dead chrome).
 *
 * The dropdown is mounted on `document.body` so the bar's
 * `overflow-x: auto` doesn't clip it; positioning uses the button's
 * bounding rect after the dropdown is opened.
 *
 * @param host the tab strip element (`.dt-tabbar-strip`) the menu button is
 *   appended into; the dropdown list goes on `document.body`.
 * @param spec the spec used to render the bar; supplies tab list, active id,
 *   and callbacks.
 */
internal fun appendTabBarOverflowMenu(host: HTMLElement, spec: TabBarSpec) {
    val cb = spec.callbacks
    // List the hidden ("unlisted") tabs — except the active one, which is
    // shown temporarily in the strip itself (renderTabBar) so its dot menu
    // is reachable. Listing it here too would be redundant.
    val hiddenTabs = spec.tabs.filter { it.isHidden && it.id != spec.activeTabId }
    // Nothing cross-tab to show → no button. Per-tab actions live in each
    // tab's own dot menu (appendTabDotMenu).
    if (hiddenTabs.isEmpty()) return

    val menuWrap = document.createElement("div") as HTMLElement
    menuWrap.className = "dt-tabbar-menu"

    val menuBtn = document.createElement("button") as HTMLElement
    menuBtn.className = "dt-tabbar-menu-button"
    menuBtn.setAttribute("type", "button")
    menuBtn.title = "Unlisted tabs"
    menuBtn.setAttribute("aria-label", "Unlisted tabs")
    menuBtn.textContent = "⋮"

    val menuList = document.createElement("div") as HTMLElement
    menuList.className = "dt-tabbar-menu-list"

    val closeMenu = wireMenuToggle(menuWrap, menuBtn, menuList)

    menuList.appendChild(menuHeading("Unlisted tabs"))
    for (tab in hiddenTabs) {
        val row = menuRow(tab.label.ifBlank { "(untitled)" }, ICON_HIDDEN_TAB) {
            closeMenu()
            cb.onSelect(tab.id)
        }
        // Trailing "Show in tab bar" affordance — un-hides the tab. (Clicking
        // the row instead just activates it, which surfaces it temporarily in
        // the strip with its dot menu.) Gated on onSetHidden being wired.
        val unhide = cb.onSetHidden
        if (unhide != null) {
            val show = document.createElement("button") as HTMLElement
            show.className = "dt-tabbar-menu-item-action"
            show.setAttribute("type", "button")
            show.title = "Show in tab bar"
            show.setAttribute("aria-label", "Show in tab bar")
            show.innerHTML = ICON_SHOW_TAB
            show.addEventListener("click", { ev: Event ->
                // Stop the row's activate handler from also firing.
                ev.stopPropagation()
                closeMenu()
                unhide(tab.id, false)
            })
            row.appendChild(show)
        }
        menuList.appendChild(row)
    }

    menuWrap.appendChild(menuBtn)
    host.appendChild(menuWrap)
    document.body?.appendChild(menuList)
}

/**
 * Wire the open/close behaviour shared by the per-tab dot menu and the
 * tab-bar overflow menu: toggling the dropdown on button click, closing
 * any other open dropdown first (so only one shows at a time), positioning
 * the body-mounted list under the button, and dismissing on any outside
 * press.
 *
 * Outside dismissal uses a transparent full-viewport **backdrop** rather
 * than a `document` click listener. On the Electron/Mac titlebar the empty
 * tab-bar area is a `-webkit-app-region: drag` zone that swallows mouse
 * events for window dragging, so a plain `click` listener never fires there
 * and the menu would stay stuck open. The backdrop (mounted above that drag
 * region, marked `no-drag`) reliably catches the press and closes the menu.
 * The dropdown list sits above the backdrop so its own rows stay clickable.
 *
 * @param menuWrap the inline wrapper carrying the `.dt-open` styling class.
 * @param menuBtn  the toggle button.
 * @param menuList the body-mounted dropdown list (must carry the
 *   `.dt-tabbar-menu-list` class so the "close others" sweep finds it).
 * @return a `closeMenu` lambda the caller's rows invoke after acting.
 *
 * `internal` (not file-private) so the world switcher's per-world `⋮` dot menu
 * ([se.soderbjorn.darkness.web.shell.appendWorldRowDotMenu]) reuses the exact
 * same toggle/backdrop behaviour rather than reimplementing it.
 */
internal fun wireMenuToggle(
    menuWrap: HTMLElement,
    menuBtn: HTMLElement,
    menuList: HTMLElement,
): () -> Unit {
    var backdrop: HTMLElement? = null
    fun closeMenu() {
        menuWrap.classList.remove("dt-open")
        menuList.classList.remove("dt-open")
        backdrop?.remove()
        backdrop = null
    }

    menuBtn.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        val opening = !menuWrap.classList.contains("dt-open")
        // Clear any other open dropdown (+ its highlight + any stray backdrop)
        // so we never stack two across the dot menus + overflow menu.
        val openLists = document.querySelectorAll(".dt-tabbar-menu-list.dt-open")
        for (i in 0 until openLists.length) (openLists.item(i) as HTMLElement).classList.remove("dt-open")
        val openWraps = document.querySelectorAll(".dt-tabbar-menu.dt-open, .dt-tab-menu.dt-open")
        for (i in 0 until openWraps.length) (openWraps.item(i) as HTMLElement).classList.remove("dt-open")
        val staleBackdrops = document.querySelectorAll(".dt-menu-backdrop")
        for (i in 0 until staleBackdrops.length) (staleBackdrops.item(i) as HTMLElement).remove()
        backdrop = null

        if (opening) {
            menuWrap.classList.add("dt-open")
            menuList.classList.add("dt-open")
            positionMenuList(menuBtn, menuList)
            // Transparent backdrop beneath the dropdown — any press on it
            // (including over the titlebar drag region) closes the menu.
            val bd = document.createElement("div") as HTMLElement
            bd.className = "dt-menu-backdrop"
            bd.addEventListener("mousedown", { e: Event ->
                e.stopPropagation()
                closeMenu()
            })
            document.body?.appendChild(bd)
            backdrop = bd
        }
    })

    return { closeMenu() }
}

/** Inline SVG icons shipped with each menu row. Kept inline so the
 *  toolkit doesn't need an external icon dependency; sized 14×14 to
 *  match the row label baseline. Termtastic's TabBarMenu uses the same
 *  glyph family — staying consistent across the family. */
internal const val ICON_RENAME: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<path d=\"M2 12.5V14h1.5l8-8L10 4.5z\"/>" +
        "<path d=\"M11 4l1-1 1 1-1 1z\"/></svg>"

internal const val ICON_CLOSE_TAB: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\">" +
        "<line x1=\"4\" y1=\"4\" x2=\"12\" y2=\"12\"/>" +
        "<line x1=\"12\" y1=\"4\" x2=\"4\" y2=\"12\"/></svg>"

private const val ICON_HIDE_TAB: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<path d=\"M2 8c2-3 4-4.5 6-4.5S12 5 14 8c-2 3-4 4.5-6 4.5S4 11 2 8z\"/>" +
        "<line x1=\"3\" y1=\"3\" x2=\"13\" y2=\"13\"/></svg>"

private const val ICON_SHOW_TAB: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<path d=\"M2 8c2-3 4-4.5 6-4.5S12 5 14 8c-2 3-4 4.5-6 4.5S4 11 2 8z\"/>" +
        "<circle cx=\"8\" cy=\"8\" r=\"1.6\"/></svg>"

private const val ICON_HIDDEN_TAB: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<rect x=\"2.5\" y=\"4\" width=\"11\" height=\"8\" rx=\"1.5\"/></svg>"

/** A globe (circle + meridian + parallels) — the "world" mark, matching the switcher. */
private const val ICON_WORLD: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.3\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<circle cx=\"8\" cy=\"8\" r=\"6\"/>" +
        "<line x1=\"2\" y1=\"8\" x2=\"14\" y2=\"8\"/>" +
        "<ellipse cx=\"8\" cy=\"8\" rx=\"2.6\" ry=\"6\"/></svg>"

/** A globe with a small motion arrow — the "Move to world" parent-row mark. */
private const val ICON_MOVE_WORLD: String = ICON_WORLD

/** A right-pointing chevron flagging a row that opens a flyout submenu. */
private const val ICON_CARET_RIGHT: String =
    "<svg viewBox=\"0 0 16 16\" width=\"12\" height=\"12\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<polyline points=\"6 4 10 8 6 12\"/></svg>"

/**
 * Build a clickable menu row with an optional leading icon. Stops event
 * propagation so outside-click dismissal doesn't fire on the same tick
 * as the row activation. Icon span is always emitted (with empty content
 * when [iconHtml] is null) so labels in the column line up vertically.
 *
 * `internal` so the world switcher's per-world `⋮` dot menu reuses the same
 * row markup + wiring as the tab dot menu.
 */
internal fun menuRow(label: String, iconHtml: String? = null, onClick: () -> Unit): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "dt-tabbar-menu-item"
    val icon = document.createElement("span") as HTMLElement
    icon.className = "dt-tabbar-menu-item-icon"
    if (iconHtml != null) icon.innerHTML = iconHtml
    row.appendChild(icon)
    val text = document.createElement("span") as HTMLElement
    text.className = "dt-tabbar-menu-item-label"
    text.textContent = label
    row.appendChild(text)
    row.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        onClick()
    })
    return row
}

/** Build a thin horizontal divider row between menu sections. */
private fun menuSeparator(): HTMLElement {
    val sep = document.createElement("div") as HTMLElement
    sep.className = "dt-tabbar-menu-separator"
    return sep
}

/** Build a non-interactive section heading row. */
private fun menuHeading(text: String): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "dt-tabbar-menu-heading"
    el.textContent = text
    return el
}

/**
 * Position the body-mounted dropdown list under the menu button, aligned
 * to the button's right edge and clamped 4px from the left viewport edge.
 */
private fun positionMenuList(button: HTMLElement, list: HTMLElement) {
    val listWidth = list.asDynamic().offsetWidth as Number
    val rect = button.asDynamic().getBoundingClientRect()
    val right = rect.right as Double
    val bottom = rect.bottom as Double
    val leftPos = (right - listWidth.toDouble()).coerceAtLeast(4.0)
    list.style.left = "${leftPos}px"
    list.style.top = "${bottom + 4}px"
}
