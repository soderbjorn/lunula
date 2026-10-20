/*
 * TabBarOverflowMenu.kt (jsMain)
 * -------------------------------
 * Two related tab-bar menus (issue #65 split the old single `⋮` menu), plus
 * the click-to-open machinery a third one — the world switcher's per-world
 * `⋮` ([appendWorldRowDotMenu]) — shares with them:
 *
 *  1. The per-tab **dot menu** (`appendTabDotMenu`) — a small `⋮` button at
 *     each tab's right corner holding the actions that belong to *that*
 *     specific tab:
 *       - "Rename"                 (when the tab is renamable)
 *       - "Close"                  (when `onClose` is set)
 *       - "Move to workspace"      (when `onMoveToWorld` is set)
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
 * ── One panel ────────────────────────────────────────────────────────────
 * What these build is not a tab-bar menu, it is *the* menu: the same
 * `.dt-hover-menu` surface the topbar "+" dropdown and the world switcher
 * open, with the same rows, gutter, separators and hover fill (see the
 * "Menu surface" block in `lunula.css`). They used to draw a panel of their
 * own under `.dt-tabbar-menu-*`, six pixels from a "+" that didn't, which
 * read as two applications the moment both were on screen. Nothing about a
 * menu raised from a tab is different from a menu raised from a toolbar, so
 * nothing about it is styled differently.
 *
 * What IS theirs, and lives here: they open on a *click* rather than a
 * hover, they are anchored by JS against a strip that scrolls, and they
 * dismiss through a transparent backdrop rather than a document listener —
 * see [wireDotMenu] for why that last one is not a stylistic choice.
 *
 * The panel is built when the button is pressed and removed when the menu
 * closes, so a tab bar that re-renders can never leave one orphaned; the
 * shared [TabMenuClassNames.PANEL] marker is how [closeTabBarMenus] finds
 * every open one, which is also what keeps at most one up at a time.
 *
 * @see TabBarSpec.showOverflowMenu
 * @see appendTabBarOverflowMenu
 * @see appendTabDotMenu
 * @see wireDotMenu
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * DOM class names used by the click-opened menus in this file.
 *
 * The panel itself wears `.dt-hover-menu` + [MenuTriggerClassNames.CHROME]
 * — the shared menu surface, in its chrome zone, because a tab strip is
 * chrome. These are the few markers on top of that: what a sweep matches,
 * what says a trigger is pressed, and the two pieces of panel furniture
 * (heading, trailing row action) that the shared surface block styles but
 * only these menus currently use.
 *
 * `internal`: consumers style the toolkit through the shared menu classes,
 * not these.
 *
 * @see closeTabBarMenus
 */
internal object TabMenuClassNames {
    /**
     * Marks every body-mounted panel opened by [wireDotMenu] — including a
     * "Move to workspace" flyout — so one query closes all of them.
     */
    const val PANEL = "dt-tab-menu-panel"

    /**
     * Extra marker on the world switcher's per-world panel. Lifts it one
     * z-index above the world popover it was opened from; see `lunula.css`.
     */
    const val WORLD_PANEL = "dt-world-row-menu-panel"

    /** On the `⋮` wrapper while its menu is up — paints the trigger pressed. */
    const val OPEN = "dt-open"

    /** The transparent full-viewport dismissal layer. See [wireDotMenu]. */
    const val BACKDROP = "dt-menu-backdrop"

    /** A non-interactive section heading inside a panel ("Unlisted tabs"). */
    const val HEADING = "dt-menu-heading"

    /** A trailing icon button inside a row ("Show in tab bar"). */
    const val ROW_ACTION = "dt-menu-row-action"
}

/**
 * The `keydown` listener installed on `document` for as long as a menu from
 * this file is open, so `Escape` dismisses it — the same key the pane menu
 * and the hover menu answer to. Held module-wide because at most one of
 * these menus is ever open, and [closeTabBarMenus] (which any of them may
 * reach) has to be able to take it back off again.
 */
private var openMenuEscapeHandler: ((Event) -> Unit)? = null

/**
 * Grace period (ms) before a hover-opened "Move to workspace" flyout closes
 * after the pointer leaves the row or the flyout. Re-entering either within
 * the window cancels it, so the diagonal travel from the row to the panel
 * doesn't kill the panel on the way.
 */
private const val SUBMENU_HIDE_GRACE_MS = 220

/**
 * Append the per-tab `⋮` dot menu (button + panel) to a single tab element.
 * Called from [buildTabElement] for every visible tab.
 *
 * The menu surfaces only the actions that target *this* tab — Rename,
 * Close, Move to workspace, Hide/Show in tab bar, Hide/Show in side bar —
 * each gated on the matching [TabBarCallbacks] being non-null (and, for
 * Rename, on [TabSpec.isRenamable]). When none apply, no button is rendered
 * so read-only tabs stay chromeless.
 *
 * @param tabEl the `.dt-tab` element to attach the menu button to (the
 *   panel is mounted on `document.body` when the button is pressed).
 * @param tab   the tab this menu acts on.
 * @param spec  the parent spec; supplies the callbacks + the label seed
 *   for inline rename.
 * @see wireDotMenu
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

    wireDotMenu(menuWrap, menuBtn) { panel, closeMenu ->
        // The dot menu only exists on tabs that are actually in the strip (a
        // hidden tab is rendered only while active), so the label is always in
        // the DOM here and inline rename can target it.
        if (canRename) {
            panel.appendChild(menuRow("Rename", ICON_RENAME) {
                closeMenu()
                val label = tabEl.querySelector(".${TabBarClassNames.TAB_LABEL}") as? HTMLElement
                if (label != null) triggerInlineRename(label, tab, spec)
            })
        }

        if (canClose) {
            panel.appendChild(menuRow("Close", ICON_CLOSE_TAB) {
                closeMenu()
                requestTabClose(tab, cb)
            })
        }

        // "Move to workspace" opens a submenu listing every other world (issue:
        // move a tab between worlds). Sits with the act-on-this-tab group.
        if (canMoveWorld) {
            appendMoveToWorldSubmenu(panel, tab, cb, closeMenu)
        }

        // Divider between the act-on-this-tab group (Rename / Close / Move to
        // workspace) and the visibility group (Hide / Show in tab bar / side bar).
        if ((canRename || canClose || canMoveWorld) && (canHide || canHideSidebar)) {
            panel.appendChild(menuSeparator())
        }

        if (canHide) {
            val label = if (tab.isHidden) "Show in tab bar" else "Hide in tab bar"
            val icon = if (tab.isHidden) ICON_SHOW_TAB else ICON_HIDE_TAB
            panel.appendChild(menuRow(label, icon) {
                closeMenu()
                cb.onSetHidden!!.invoke(tab.id, !tab.isHidden)
            })
        }

        if (canHideSidebar) {
            val label = if (tab.isHiddenFromSidebar) "Show in side bar" else "Hide in side bar"
            val icon = if (tab.isHiddenFromSidebar) ICON_SHOW_TAB else ICON_HIDE_TAB
            panel.appendChild(menuRow(label, icon) {
                closeMenu()
                cb.onSetHiddenFromSidebar!!.invoke(tab.id, !tab.isHiddenFromSidebar)
            })
        }
    }

    menuWrap.appendChild(menuBtn)
    tabEl.appendChild(menuWrap)
}

/**
 * Append the "Move to workspace" parent row plus its hover/click **flyout
 * submenu** (one row per other world) to a tab's dot-menu [panel]. Picking a
 * world fires [TabBarCallbacks.onMoveToWorld] and closes the whole menu;
 * hovering the parent row opens the flyout, leaving both (after
 * [SUBMENU_HIDE_GRACE_MS]) closes it.
 *
 * The flyout is a second panel mounted on `document.body` rather than a
 * child of [panel], for the reason the hover menu's flyout is
 * ([attachHoverMenu]): the menu surface scrolls (`overflow-y: auto`), and a
 * panel nested inside a scrolling box is clipped by it — cut off at exactly
 * the edge it has to cross. It carries [TabMenuClassNames.PANEL] like its
 * parent, so [closeTabBarMenus] takes both down together.
 *
 * Only called when [TabBarCallbacks.onMoveToWorld] is set and
 * [TabBarCallbacks.moveToWorlds] is non-empty (see [appendTabDotMenu]).
 *
 * @param panel     the tab's dot-menu panel to append the row to.
 * @param tab       the tab being moved.
 * @param cb        the tab-bar callbacks (supplies the worlds + move handler).
 * @param closeMenu closes the whole dot menu after a world is picked.
 */
private fun appendMoveToWorldSubmenu(
    panel: HTMLElement,
    tab: TabSpec,
    cb: TabBarCallbacks,
    closeMenu: () -> Unit,
) {
    val onMove = cb.onMoveToWorld ?: return

    val row = document.createElement("div") as HTMLElement
    row.className = "dt-hover-menu-item dt-hover-menu-submenu-parent"
    row.setAttribute("role", "menuitem")
    row.setAttribute("aria-haspopup", "menu")
    val icon = document.createElement("span") as HTMLElement
    icon.className = "dt-hover-menu-icon"
    icon.innerHTML = ICON_MOVE_WORLD
    val label = document.createElement("span") as HTMLElement
    label.className = "dt-hover-menu-label"
    label.textContent = "Move to workspace"
    val caret = document.createElement("span") as HTMLElement
    caret.className = "dt-hover-menu-submenu-caret"
    caret.innerHTML = ICON_CARET_RIGHT
    row.appendChild(icon)
    row.appendChild(label)
    row.appendChild(caret)

    var flyout: HTMLElement? = null
    var hideTimer: Int? = null

    fun cancelHide() {
        hideTimer?.let { window.clearTimeout(it) }
        hideTimer = null
    }

    fun closeFlyout() {
        cancelHide()
        flyout?.remove()
        flyout = null
        row.setAttribute("aria-expanded", "false")
    }

    fun openFlyout() {
        cancelHide()
        if (flyout != null) return
        val box = document.createElement("div") as HTMLElement
        box.className = "dt-hover-menu dt-hover-menu-flyout " +
            "${TabMenuClassNames.PANEL} ${MenuTriggerClassNames.CHROME}"
        box.setAttribute("role", "menu")
        for (w in cb.moveToWorlds) {
            box.appendChild(menuRow(w.label.ifBlank { "(untitled)" }, ICON_WORLD) {
                closeMenu()
                onMove(tab.id, w.id)
            })
        }
        document.body?.appendChild(box)

        // Hang the flyout off the panel's right edge, aligned to this row's
        // top; flip to the left when the right side would run off the viewport
        // (a tab whose dot menu sits near the window's right edge).
        val panelRect = panel.getBoundingClientRect()
        val rowRect = row.getBoundingClientRect()
        val boxRect = box.getBoundingClientRect()
        val right = panelRect.right + 3.0
        val left = if (right + boxRect.width > window.innerWidth - 4.0) {
            (panelRect.left - boxRect.width - 3.0).coerceAtLeast(4.0)
        } else {
            right
        }
        val top = (rowRect.top - 5.0)
            .coerceAtMost(window.innerHeight - boxRect.height - 8.0)
            .coerceAtLeast(4.0)
        box.style.left = "${left}px"
        box.style.top = "${top}px"

        box.addEventListener("mouseenter", { _: Event -> cancelHide() })
        box.addEventListener("mouseleave", { _: Event ->
            cancelHide()
            hideTimer = window.setTimeout({ closeFlyout() }, SUBMENU_HIDE_GRACE_MS)
        })
        row.setAttribute("aria-expanded", "true")
        flyout = box
    }

    row.addEventListener("mouseenter", { _: Event -> openFlyout() })
    row.addEventListener("mouseleave", { _: Event ->
        cancelHide()
        hideTimer = window.setTimeout({ closeFlyout() }, SUBMENU_HIDE_GRACE_MS)
    })
    // Click toggles too, so the submenu is reachable without a hover (touch / a
    // deliberate click). Stop propagation so the outside-dismiss doesn't fire.
    row.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        if (flyout != null) closeFlyout() else openFlyout()
    })

    panel.appendChild(row)
}

/**
 * Append the `⋮` overflow menu (button + panel) to the given [host]
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
 * The panel is mounted on `document.body` when the button is pressed, so
 * the bar's `overflow-x: auto` doesn't clip it.
 *
 * @param host the tab strip element (`.dt-tabbar-strip`) the menu button is
 *   appended into.
 * @param spec the spec used to render the bar; supplies tab list, active id,
 *   and callbacks.
 * @see wireDotMenu
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
    menuWrap.className = "dt-tabbar-overflow"

    val menuBtn = document.createElement("button") as HTMLElement
    menuBtn.className = "dt-tabbar-overflow-button"
    menuBtn.setAttribute("type", "button")
    menuBtn.title = "Unlisted tabs"
    menuBtn.setAttribute("aria-label", "Unlisted tabs")
    menuBtn.textContent = "⋮"

    wireDotMenu(menuWrap, menuBtn) { panel, closeMenu ->
        panel.appendChild(menuHeading("Unlisted tabs"))
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
                show.className = TabMenuClassNames.ROW_ACTION
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
            panel.appendChild(row)
        }
    }

    menuWrap.appendChild(menuBtn)
    host.appendChild(menuWrap)
}

/**
 * Wire a `⋮` trigger to open the shared menu surface beneath itself.
 *
 * The panel is built by [buildRows] on every press — never up front — so it
 * always states the tab's current state, and so a re-render that replaces
 * the strip cannot leave a stale panel behind. Pressing the button again,
 * pressing another `⋮`, pressing anywhere else, or `Escape` closes it; only
 * one of these menus is up at a time (see [closeTabBarMenus]).
 *
 * Outside dismissal uses a transparent full-viewport **backdrop** rather
 * than a `document` click listener. On the Electron/Mac titlebar the empty
 * tab-bar area is a `-webkit-app-region: drag` zone that swallows mouse
 * events for window dragging, so a plain listener never fires there and the
 * menu would stay stuck open. The backdrop (mounted above that drag region,
 * marked `no-drag`) reliably catches the press. The panel sits above the
 * backdrop so its own rows stay clickable.
 *
 * `internal` (not file-private) so the world switcher's per-world `⋮` dot
 * menu ([se.soderbjorn.lunula.web.shell.appendWorldRowDotMenu]) opens the
 * same panel through the same behaviour rather than reimplementing it.
 *
 * @param menuWrap        the inline wrapper carrying the
 *   [TabMenuClassNames.OPEN] class while the menu is up.
 * @param menuBtn         the toggle button.
 * @param extraPanelClass host classes appended to the panel — currently only
 *   [TabMenuClassNames.WORLD_PANEL], for its z-index lift.
 * @param buildRows       fills the freshly built panel. Receives the panel
 *   and a `closeMenu` lambda rows invoke before acting.
 * @see closeTabBarMenus
 * @see menuRow
 */
internal fun wireDotMenu(
    menuWrap: HTMLElement,
    menuBtn: HTMLElement,
    extraPanelClass: String = "",
    buildRows: (panel: HTMLElement, closeMenu: () -> Unit) -> Unit,
) {
    menuBtn.setAttribute("aria-haspopup", "menu")
    menuBtn.setAttribute("aria-expanded", "false")

    menuBtn.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        val wasOpen = menuWrap.classList.contains(TabMenuClassNames.OPEN)
        // Clear whatever was open — this menu (so the press toggles it shut)
        // or another one (so two never stack).
        closeTabBarMenus()
        if (wasOpen) return@addEventListener

        val panel = document.createElement("div") as HTMLElement
        panel.className = listOf(
            "dt-hover-menu",
            TabMenuClassNames.PANEL,
            MenuTriggerClassNames.CHROME,
            extraPanelClass,
        ).filter { it.isNotEmpty() }.joinToString(" ")
        panel.setAttribute("role", "menu")
        buildRows(panel) { closeTabBarMenus() }
        document.body?.appendChild(panel)
        positionMenuPanel(menuBtn, panel)

        menuWrap.classList.add(TabMenuClassNames.OPEN)
        menuBtn.setAttribute("aria-expanded", "true")

        // Transparent backdrop beneath the panel — any press on it (including
        // over the titlebar drag region) closes the menu.
        val backdrop = document.createElement("div") as HTMLElement
        backdrop.className = TabMenuClassNames.BACKDROP
        backdrop.addEventListener("mousedown", { e: Event ->
            e.stopPropagation()
            closeTabBarMenus()
        })
        document.body?.appendChild(backdrop)

        // Escape closes, like every other menu in the toolkit.
        val onEsc: (Event) -> Unit = handler@{ e ->
            if ((e as? KeyboardEvent)?.key == "Escape") closeTabBarMenus()
        }
        document.addEventListener("keydown", onEsc)
        openMenuEscapeHandler = onEsc
    })
}

/**
 * Close every menu opened by [wireDotMenu]: the panel (and any flyout it
 * threw), the dismissal backdrop, the pressed state on the trigger, and the
 * `Escape` listener.
 *
 * Called by the triggers themselves, by [renderTabBar] before it rebuilds
 * the strip (a panel whose tab is about to be replaced must not outlive it),
 * by the world switcher when its popover chain goes down, and by
 * [attachHoverMenu] when the topbar dropdown takes over. Safe to call when
 * nothing is open.
 *
 * @see wireDotMenu
 */
internal fun closeTabBarMenus() {
    val panels = document.querySelectorAll(".${TabMenuClassNames.PANEL}")
    for (i in 0 until panels.length) (panels.item(i) as HTMLElement).remove()

    val backdrops = document.querySelectorAll(".${TabMenuClassNames.BACKDROP}")
    for (i in 0 until backdrops.length) (backdrops.item(i) as HTMLElement).remove()

    val openWraps = document.querySelectorAll(
        ".dt-tabbar-overflow.${TabMenuClassNames.OPEN}, .dt-tab-menu.${TabMenuClassNames.OPEN}",
    )
    for (i in 0 until openWraps.length) {
        val wrap = openWraps.item(i) as HTMLElement
        wrap.classList.remove(TabMenuClassNames.OPEN)
        (wrap.querySelector("button") as? HTMLElement)?.setAttribute("aria-expanded", "false")
    }

    openMenuEscapeHandler?.let { document.removeEventListener("keydown", it) }
    openMenuEscapeHandler = null
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
    "<svg viewBox=\"0 0 24 24\" width=\"12\" height=\"12\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><path d=\"M9 6l6 6-6 6\"/></svg>"

/**
 * Build a clickable menu row with an optional leading icon, on the shared
 * menu surface's row (`.dt-hover-menu-item`) — the same object the topbar
 * "+" dropdown and the world list are built from. Stops event propagation so
 * outside-click dismissal doesn't fire on the same tick as the row
 * activation. The icon span is always emitted (with empty content when
 * [iconHtml] is null) so labels in the column line up vertically.
 *
 * A `<div role="menuitem">` rather than a `<button>`, because the overflow
 * menu puts a real button *inside* a row (its "Show in tab bar" affordance)
 * and a button inside a button is not markup a browser will keep. Every
 * paint the row wears comes from the shared class, so the tag is free.
 *
 * `internal` so the world switcher's per-world `⋮` dot menu reuses the same
 * row markup + wiring as the tab dot menu.
 *
 * @param label    the row's visible text.
 * @param iconHtml inline SVG for the leading gutter, or `null` for none.
 * @param onClick  fires when the row is activated.
 * @return the row element, ready to append to a panel.
 */
internal fun menuRow(label: String, iconHtml: String? = null, onClick: () -> Unit): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "dt-hover-menu-item"
    row.setAttribute("role", "menuitem")
    val icon = document.createElement("span") as HTMLElement
    icon.className = "dt-hover-menu-icon"
    if (iconHtml != null) icon.innerHTML = iconHtml
    row.appendChild(icon)
    val text = document.createElement("span") as HTMLElement
    text.className = "dt-hover-menu-label"
    text.textContent = label
    row.appendChild(text)
    row.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        onClick()
    })
    return row
}

/**
 * Build a thin horizontal divider between menu sections — the shared
 * surface's separator, so it sits in the same rhythm as every other menu's.
 *
 * @return the separator element, ready to append to a panel.
 */
private fun menuSeparator(): HTMLElement {
    val sep = document.createElement("div") as HTMLElement
    sep.className = "dt-hover-menu-separator"
    sep.setAttribute("role", "separator")
    return sep
}

/**
 * Build a non-interactive section heading row ("Unlisted tabs").
 *
 * @param text the heading's caption.
 * @return the heading element, ready to append to a panel.
 */
private fun menuHeading(text: String): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = TabMenuClassNames.HEADING
    el.textContent = text
    return el
}

/**
 * Position the body-mounted [panel] under the [button] that opened it,
 * aligned to the button's right edge and clamped 4px from the left viewport
 * edge. Must run after the panel is in the document so the width read is
 * real.
 *
 * @param button the `⋮` trigger the panel hangs from.
 * @param panel  the panel to place.
 */
private fun positionMenuPanel(button: HTMLElement, panel: HTMLElement) {
    val panelWidth = panel.offsetWidth.toDouble()
    val rect = button.getBoundingClientRect()
    val leftPos = (rect.right - panelWidth).coerceAtLeast(4.0)
    panel.style.left = "${leftPos}px"
    panel.style.top = "${rect.bottom + 4}px"
}
