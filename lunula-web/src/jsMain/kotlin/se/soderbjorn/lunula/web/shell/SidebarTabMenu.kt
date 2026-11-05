/*
 * SidebarTabMenu.kt (jsMain)
 * --------------------------
 * The sidebar's per-tab actions: a `⋮` on each tab's section header and a
 * right-click on the same header, both opening the menu the tab strip's dot
 * menu opens (Rename, Close, Move to workspace, the visibility toggles).
 *
 * The sidebar used to list tabs as nothing more than collapsible headings, so
 * every tab action lived on the tab strip. Once the user's navigation mode
 * can hide the strip ([se.soderbjorn.lunula.core.NavigationMode.Sidebar]),
 * that left no way to close, rename or move a tab at all. These menus are the
 * same rows ([appendTabActionRows]) on the same panel ([wireDotMenu] /
 * [wireContextMenu]), so a tab offers one set of actions wherever it is
 * listed.
 *
 * Also holds [buildSidebarUnlistedTabRow], the row for a tab hidden from the
 * sidebar, which the shell lists when there is no tab strip to reach it from.
 *
 * Contains [SidebarTabMenuClassNames], [attachSidebarTabMenu] and
 * [buildSidebarUnlistedTabRow]. Consumed by `ShellState` in AppShellMount.kt.
 *
 * @see appendTabDotMenu
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * DOM class names for the sidebar's per-tab menu and the rows that stand in
 * for unlisted tabs. `internal`: consumers style these through `lunula.css`.
 */
internal object SidebarTabMenuClassNames {
    /**
     * Extra marker on the `⋮` wrapper inside a sidebar tab header, beside the
     * shared `.dt-tab-menu` (which is what [closeTabBarMenus] sweeps). Lets
     * CSS reveal it on header hover instead of the strip's tab hover.
     */
    const val MENU = "dt-sidebar-tab-menu"

    /** The inline-rename input for a sidebar tab (a section header or an unlisted row's label). */
    const val TITLE_INPUT = "dt-sidebar-section-title-input"

    /**
     * Beside [TITLE_INPUT] when the input stands in for a whole section
     * header (see [attachSidebarTabMenu]), so CSS can give it the header's
     * position in the section.
     */
    const val HEADER_INPUT = "dt-sidebar-section-header-input"

    /**
     * On a tab section drawn even though the tab is hidden from the sidebar —
     * the active tab, shown while there is no tab strip so its own menu (and
     * "Show in side bar") stays reachable. CSS quiets its title.
     */
    const val SECTION_UNLISTED = "dt-sidebar-section-unlisted"

    /** On the section listing the tabs hidden from the sidebar. */
    const val UNLISTED_SECTION = "dt-sidebar-unlisted-tabs"
}

/**
 * Give a sidebar tab section a `⋮` in its header, and open the same menu
 * when the header is right-clicked.
 *
 * Called by `ShellState.buildLeftSidebarContent` for every tab section it
 * draws, with the callbacks the tab strip is built from, so both surfaces act
 * through one wiring. Does nothing when the tab has no action to offer
 * ([hasTabActions] — e.g. an app-defined strip, whose callbacks are null),
 * leaving the header a plain collapsible heading.
 *
 * Rename replaces the header with an input until the edit ends; the header's
 * own click (collapse / expand) is unaffected by the `⋮`, which stops its
 * click.
 *
 * @param section the `.dt-sidebar-section` built by [renderSidebarSection].
 * @param tab     the tab the section lists.
 * @param cb      the tab callbacks the menu rows fire.
 * @see appendTabActionRows
 */
internal fun attachSidebarTabMenu(section: HTMLElement, tab: TabSpec, cb: TabBarCallbacks) {
    if (!hasTabActions(tab, cb)) return
    val header = section.querySelector(".dt-sidebar-section-header") as? HTMLElement ?: return

    val menuWrap = document.createElement("span") as HTMLElement
    menuWrap.className = "dt-tab-menu ${SidebarTabMenuClassNames.MENU}"
    val menuBtn = buildTabMenuButton()
    menuWrap.appendChild(menuBtn)
    header.appendChild(menuWrap)

    // Rename swaps out the whole header, not just its title. The header is a
    // <button>, and Blink runs a button's activation for keys typed in any
    // descendant that doesn't handle them itself — a space typed into an
    // input nested there collapses the section and blurs the edit away.
    val startRename = sidebarRename(
        tab,
        cb,
        "${SidebarTabMenuClassNames.TITLE_INPUT} ${SidebarTabMenuClassNames.HEADER_INPUT}",
    ) { header }
    val buildRows: (HTMLElement, () -> Unit) -> Unit = { panel, closeMenu ->
        appendTabActionRows(panel, tab, cb, closeMenu, startRename)
    }
    wireDotMenu(menuWrap, menuBtn, buildRows = buildRows)
    wireContextMenu(header, menuWrap, buildRows = buildRows)
}

/**
 * Build the sidebar row for a tab hidden from the sidebar: clicking it
 * activates the tab, and right-clicking it opens the tab's menu (where "Show
 * in side bar" lists it again).
 *
 * The sidebar's counterpart of the tab strip's "Unlisted tabs" overflow
 * list. Called by `ShellState.buildLeftSidebarContent` only while no tab
 * strip is drawn — with a strip, that overflow list is already the way back,
 * and the sidebar keeps the user's request to leave these tabs out.
 *
 * @param tab the hidden tab.
 * @param cb  the tab callbacks; [TabBarCallbacks.onSelect] activates the tab.
 * @return the row element, ready to append to the unlisted-tabs section.
 * @see attachSidebarTabMenu
 */
internal fun buildSidebarUnlistedTabRow(tab: TabSpec, cb: TabBarCallbacks): HTMLElement {
    val row = SidebarRow(
        label = tab.label.ifBlank { "(untitled)" },
        iconHtml = ICON_HIDDEN_TAB,
        handler = { cb.onSelect(tab.id) },
    )
    if (hasTabActions(tab, cb)) {
        val startRename = sidebarRename(tab, cb, SidebarTabMenuClassNames.TITLE_INPUT) {
            row.querySelector(".dt-sidebar-row-label") as? HTMLElement
        }
        wireContextMenu(row, menuWrap = null) { panel, closeMenu ->
            appendTabActionRows(panel, tab, cb, closeMenu, startRename)
        }
    }
    return row
}

/**
 * The Rename action for a tab listed in the sidebar: edits the label in place
 * through [startInlineLabelEdit] and reports the new name to
 * [TabBarCallbacks.onRename].
 *
 * Shared by [attachSidebarTabMenu] (a whole section header) and
 * [buildSidebarUnlistedTabRow] (an unlisted row's label), which differ only in
 * where the label is drawn.
 *
 * @param tab        the tab to rename.
 * @param cb         supplies [TabBarCallbacks.onRename].
 * @param inputClass the class(es) the rename input wears.
 * @param findLabel  finds the element the input stands in for while editing,
 *   at the moment Rename is picked (the sidebar may have re-rendered since the
 *   menu was built).
 * @return the action for [appendTabActionRows], or `null` when the tab can't
 *   be renamed (no Rename row).
 */
private fun sidebarRename(
    tab: TabSpec,
    cb: TabBarCallbacks,
    inputClass: String,
    findLabel: () -> HTMLElement?,
): (() -> Unit)? {
    val onRename = cb.onRename ?: return null
    if (!tab.isRenamable) return null
    return {
        findLabel()?.let { label ->
            startInlineLabelEdit(label, tab.label, inputClass) { newLabel ->
                onRename(tab.id, newLabel)
            }
        }
    }
}
