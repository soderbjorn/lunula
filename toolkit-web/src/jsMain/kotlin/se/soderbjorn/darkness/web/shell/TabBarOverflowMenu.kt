/*
 * TabBarOverflowMenu.kt (jsMain)
 * -------------------------------
 * The far-right `⋮` overflow menu for the toolkit's tab bar.
 *
 * Ported from termtastic's `TabBarMenu.kt` and made generic over
 * [TabBarSpec] / [TabBarCallbacks] so any consumer that opts in via
 * [TabBarSpec.showOverflowMenu] gets the same affordances:
 *
 *  - "New tab"                              (when `onAdd` is set)
 *  - "Rename" the active tab                (when active tab is renamable)
 *  - "Close" the active tab                 (when active tab is closable)
 *  - "Hide / Show in tab bar"               (when `onSetHidden` is set)
 *  - "Hide / Show in side bar"              (when `onSetHiddenFromSidebar` is set)
 *  - One row per hidden tab → click activates (always when any exist)
 *
 * The menu list is appended to `document.body` rather than the tab bar
 * itself so the bar's horizontal-overflow scroll doesn't clip it. The
 * positioning logic mirrors termtastic's: anchor the menu's top-right at
 * the button's bottom-right, clamping to the viewport.
 *
 * @see TabBarSpec.showOverflowMenu
 * @see appendTabBarOverflowMenu
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Append the `⋮` overflow menu (button + dropdown list) to the given
 * [tabBar] element. Called from [renderTabBar] when
 * [TabBarSpec.showOverflowMenu] is true; callers don't normally invoke
 * this directly.
 *
 * The dropdown is mounted on `document.body` so the bar's
 * `overflow-x: auto` doesn't clip it; positioning uses the button's
 * bounding rect after the dropdown is opened.
 *
 * @param tabBar the element returned by [renderTabBar] (the menu is
 *   appended into its strip; the dropdown list goes on `document.body`)
 * @param spec   the spec used to render [tabBar]; supplies tab list,
 *   active id, and callbacks
 */
internal fun appendTabBarOverflowMenu(tabBar: HTMLElement, spec: TabBarSpec) {
    val cb = spec.callbacks
    val activeTab = spec.tabs.firstOrNull { it.id == spec.activeTabId }
    val activeIsHidden = activeTab?.isHidden == true
    val activeIsHiddenFromSidebar = activeTab?.isHiddenFromSidebar == true
    val hiddenTabs = spec.tabs.filter { it.isHidden }

    val menuWrap = document.createElement("div") as HTMLElement
    menuWrap.className = "dt-tabbar-menu"

    val menuBtn = document.createElement("button") as HTMLElement
    menuBtn.className = "dt-tabbar-menu-button"
    menuBtn.setAttribute("type", "button")
    menuBtn.title = "Tab menu"
    menuBtn.textContent = "⋮"

    val menuList = document.createElement("div") as HTMLElement
    menuList.className = "dt-tabbar-menu-list"

    fun closeMenu() {
        menuWrap.classList.remove("dt-open")
        menuList.classList.remove("dt-open")
    }

    // ── New tab ────────────────────────────────────────
    if (cb.onAdd != null) {
        menuList.appendChild(menuRow("New tab", ICON_NEW_TAB_MENU) {
            closeMenu()
            cb.onAdd.invoke()
        })
        if (activeTab != null || hiddenTabs.isNotEmpty()) {
            menuList.appendChild(menuSeparator())
        }
    }

    // ── Active-tab section ─────────────────────────────
    if (activeTab != null) {
        val needsHeading = (activeTab.isRenamable && cb.onRename != null) ||
            cb.onClose != null ||
            cb.onSetHidden != null ||
            cb.onSetHiddenFromSidebar != null
        if (needsHeading) {
            menuList.appendChild(menuHeading("Active tab"))
        }

        if (activeTab.isRenamable && cb.onRename != null && !activeIsHidden) {
            menuList.appendChild(menuRow("Rename", ICON_RENAME) {
                closeMenu()
                startTabInlineRename(tabBar, activeTab.id, spec)
            })
        }

        // The overflow-menu "Close" entry is gated on `cb.onClose` only —
        // intentionally NOT on `tab.isClosable`, which controls the per-tab
        // × button in the strip. Apps frequently want a chromeless strip
        // (no ×, less visual noise) but still need a way to close from the
        // menu (matches termtastic's `TabBarMenu.kt`).
        if (cb.onClose != null) {
            menuList.appendChild(menuRow("Close", ICON_CLOSE_TAB) {
                closeMenu()
                requestTabClose(activeTab, cb)
            })
        }

        if (cb.onSetHidden != null) {
            val label = if (activeIsHidden) "Show in tab bar" else "Hide in tab bar"
            val icon = if (activeIsHidden) ICON_SHOW_TAB else ICON_HIDE_TAB
            menuList.appendChild(menuRow(label, icon) {
                closeMenu()
                cb.onSetHidden.invoke(activeTab.id, !activeIsHidden)
            })
        }

        // Mirror affordance for the host's left sidebar tree. Independent
        // of the tab-bar toggle above: either or both can be set, so the
        // user can declutter the sidebar without also hiding the tab from
        // the strip (or vice versa). Mirrors termtastic's TabBarMenu.
        if (cb.onSetHiddenFromSidebar != null) {
            val label = if (activeIsHiddenFromSidebar) "Show in side bar" else "Hide in side bar"
            val icon = if (activeIsHiddenFromSidebar) ICON_SHOW_TAB else ICON_HIDE_TAB
            menuList.appendChild(menuRow(label, icon) {
                closeMenu()
                cb.onSetHiddenFromSidebar.invoke(activeTab.id, !activeIsHiddenFromSidebar)
            })
        }
    }

    // ── Hidden-tabs section ────────────────────────────
    if (hiddenTabs.isNotEmpty()) {
        menuList.appendChild(menuSeparator())
        menuList.appendChild(menuHeading("Unlisted tabs"))
        for (tab in hiddenTabs) {
            val row = menuRow(tab.label.ifBlank { "(untitled)" }, ICON_HIDDEN_TAB) {
                closeMenu()
                cb.onSelect(tab.id)
            }
            if (tab.id == spec.activeTabId) row.classList.add("dt-active")
            menuList.appendChild(row)
        }
    }

    // ── Open / close + outside-click dismissal ─────────
    menuBtn.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        // Close any other open dropdowns first so we never stack two.
        val openLists = document.querySelectorAll(".dt-tabbar-menu-list.dt-open")
        for (i in 0 until openLists.length) {
            val other = openLists.item(i) as HTMLElement
            if (other !== menuList) other.classList.remove("dt-open")
        }
        val opening = !menuWrap.classList.contains("dt-open")
        menuWrap.classList.toggle("dt-open")
        menuList.classList.toggle("dt-open")
        if (opening) positionMenuList(menuBtn, menuList)
    })

    // Outside-click dismissal — installed once on first open, idempotent.
    document.addEventListener("click", { ev: Event ->
        if (!menuList.classList.contains("dt-open")) return@addEventListener
        val target = ev.target as? HTMLElement ?: return@addEventListener
        if (menuList.contains(target) || menuBtn.contains(target)) return@addEventListener
        closeMenu()
    })

    menuWrap.appendChild(menuBtn)
    tabBar.appendChild(menuWrap)
    document.body?.appendChild(menuList)
}

/** Inline SVG icons shipped with each menu row. Kept inline so the
 *  toolkit doesn't need an external icon dependency; sized 14×14 to
 *  match the row label baseline. Termtastic's TabBarMenu uses the same
 *  glyph family — staying consistent across the family. */
private const val ICON_NEW_TAB_MENU: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\">" +
        "<line x1=\"8\" y1=\"3\" x2=\"8\" y2=\"13\"/>" +
        "<line x1=\"3\" y1=\"8\" x2=\"13\" y2=\"8\"/></svg>"

private const val ICON_RENAME: String =
    "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\">" +
        "<path d=\"M2 12.5V14h1.5l8-8L10 4.5z\"/>" +
        "<path d=\"M11 4l1-1 1 1-1 1z\"/></svg>"

private const val ICON_CLOSE_TAB: String =
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

/**
 * Build a clickable menu row with an optional leading icon. Stops event
 * propagation so outside-click dismissal doesn't fire on the same tick
 * as the row activation. Icon span is always emitted (with empty content
 * when [iconHtml] is null) so labels in the column line up vertically.
 */
private fun menuRow(label: String, iconHtml: String? = null, onClick: () -> Unit): HTMLElement {
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

/** Build a non-interactive section heading row. */
private fun menuHeading(text: String): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "dt-tabbar-menu-heading"
    el.textContent = text
    return el
}

/** Build a thin horizontal divider row. */
private fun menuSeparator(): HTMLElement {
    val sep = document.createElement("div") as HTMLElement
    sep.className = "dt-tabbar-menu-separator"
    return sep
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
