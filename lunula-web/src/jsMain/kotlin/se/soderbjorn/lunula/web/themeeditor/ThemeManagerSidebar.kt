/*
 * ThemeManagerSidebar.kt (jsMain)
 * --------------------------------
 * High-level helper that wraps the Theme Manager in a right-side sidebar
 * with built-in slide-in/out animation, resize gesture, and width
 * persistence. Consumer apps avoid hand-rolling rAF + transitionend
 * bookkeeping; they just call [toggleThemeManagerSidebar] from a button
 * and call [buildThemeManagerSidebar] from inside their AppFrame
 * rebuild path.
 *
 * Lifecycle:
 *   1. User clicks "themes" button → app calls [toggleThemeManagerSidebar].
 *   2. Controller flips internal `isOpen` state and asks the host to rebuild
 *      its AppFrame via the supplied `requestRebuild` callback.
 *   3. App's rebuild calls [buildThemeManagerSidebar] (when [isThemeManagerSidebarOpen]
 *      returns true) to get an `<aside>` element wired with the right
 *      width + ThemeManager mounted into it. The sidebar starts at width 0
 *      and slides to the persisted target width on the next frame.
 *   4. User clicks "themes" again → controller animates the existing
 *      sidebar to width 0, waits for `transitionend`, then asks the host
 *      to rebuild without the sidebar.
 *
 * The state is module-level (single open instance per page) — same model as
 * [showThemeManager] / [closeThemeManager] which this file composes on top of.
 *
 * ### The Appearance pane
 * Given an `appearanceBody`, the same sidebar becomes the toolkit's one
 * Appearance pane: a tab strip ("Themes" | "Appearance" | "Fonts" |
 * "Behaviour") above the body, where Themes is the Theme Manager and the other
 * tabs are the settings pages the caller mounts. `AppShellMount` always builds it this way, opened from the
 * single palette button in the top bar. The pane reopens on the tab last
 * shown ([currentAppearanceTab]); [openAppearancePane] opens it on a given one.
 */
package se.soderbjorn.lunula.web.themeeditor

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunula.web.shell.SidebarSpec
import se.soderbjorn.lunula.web.shell.renderRightSidebar

/** True while the Theme Manager sidebar is considered open. */
private var sidebarOpen: Boolean = false

/** The currently-mounted sidebar element, or null when closed. */
private var currentSidebar: HTMLElement? = null

/** Last-known target width — used as the slide-in destination on next mount. */
private var lastWidthPx: Int = 480

/**
 * The tabs of the Appearance pane.
 *
 *  - [Themes] — the Theme Manager: the palettes for the dark and light slots.
 *  - [Appearance] — the style settings, remembered per dark/light mode.
 *  - [Fonts] — one line per surface: its font and size.
 *  - [Behaviour] — how the shell behaves: the tab strip and the custom title bar.
 */
enum class AppearanceTab(val label: String) {
    Themes("Themes"),
    Appearance("Appearance"),
    Fonts("Fonts"),
    Behaviour("Behaviour"),
}

/**
 * The Appearance pane's current tab: the one shown while it is open, and the
 * one it reopens on. Changed by the tab strip and by [openAppearancePane].
 */
var currentAppearanceTab: AppearanceTab = AppearanceTab.Themes
    private set

/** Document-level Escape handler installed while a settings tab is shown. */
private var appearanceTabEscHandler: ((Event) -> Unit)? = null

/**
 * Latest `requestRebuild` callback handed to [toggleThemeManagerSidebar].
 *
 * Captured so the panel's own close affordances (X button, Escape) can
 * trigger the same animate-then-rebuild close path as the topbar toggle —
 * otherwise those affordances would only detach the inner panel and leave
 * the outer sidebar slot occupying its full width with empty content.
 */
private var lastRequestRebuild: (() -> Unit)? = null

/**
 * Whether the Theme Manager sidebar is currently open. Hosts call this
 * from their AppFrame rebuild path to decide whether to invoke
 * [buildThemeManagerSidebar].
 *
 * @return `true` when the sidebar is mounted (or about to be), `false` otherwise.
 */
fun isThemeManagerSidebarOpen(): Boolean = sidebarOpen

/**
 * Opens the Appearance pane on [tab], or switches an open pane to it.
 *
 * Called by `AppShellHandle.openAppearance` (e.g. an app's own settings
 * linking to "Themes" or "Fonts"). An open pane switches tab through a
 * rebuild; a closed one slides open on that tab.
 *
 * @param tab            the tab to show.
 * @param requestRebuild the host's rebuild entry point, as for [toggleThemeManagerSidebar].
 */
fun openAppearancePane(tab: AppearanceTab, requestRebuild: () -> Unit) {
    currentAppearanceTab = tab
    if (sidebarOpen) requestRebuild() else toggleThemeManagerSidebar(requestRebuild)
}

/**
 * Toggle the Theme Manager sidebar open/closed.
 *
 * On open: flips state to true and immediately calls [requestRebuild] so the
 * host re-renders its AppFrame with the sidebar slot populated. The slide-in
 * animation is driven from inside [buildThemeManagerSidebar].
 *
 * On close: animates the existing sidebar element to width 0, waits for the
 * `transitionend` to fire, then flips state to false and calls
 * [requestRebuild] so the host re-renders without the sidebar. If the
 * element is gone (already detached, never mounted), closes synchronously.
 *
 * @param requestRebuild called once per state flip. Hosts typically point
 *   this at their own `rebuildShell()` entry point.
 */
fun toggleThemeManagerSidebar(requestRebuild: () -> Unit) {
    // Stash the latest rebuild callback so close affordances inside the
    // panel (X button, Escape) can reach it without the host having to
    // thread it through `buildThemeManagerSidebar` manually.
    lastRequestRebuild = requestRebuild
    if (!sidebarOpen) {
        sidebarOpen = true
        // Clear any stale reference from a previous instance so the next
        // `attachToBuiltSidebar` runs as a real "open" (slide-in plays)
        // instead of as a re-mount (no animation).
        currentSidebar = null
        requestRebuild()
        return
    }
    val sidebar = currentSidebar
    if (sidebar == null) {
        // Inconsistent state — close path with nothing to animate. Fall
        // through to the synchronous teardown.
        closeThemeManager()
        teardownAppearanceTabEsc()
        sidebarOpen = false
        requestRebuild()
        return
    }
    var done = false
    val handler: (Event) -> Unit = handler@{ ev ->
        if (done) return@handler
        // transitionend bubbles per-property and from descendants — only
        // react to the outer sidebar's width transition.
        if (ev.target !== sidebar) return@handler
        done = true
        closeThemeManager()
        teardownAppearanceTabEsc()
        sidebarOpen = false
        currentSidebar = null
        requestRebuild()
    }
    sidebar.addEventListener("transitionend", handler)
    // rAF before the width change so the browser has a starting frame to
    // interpolate from (an immediate width:0 after a layout-affecting
    // change can collapse without animating).
    window.requestAnimationFrame { sidebar.style.width = "0px" }
}

/**
 * Force-closes the Theme Manager sidebar synchronously (no animation), the
 * counterpart of `closeSettingsSidebar`: tears the manager down and forgets
 * the mounted element, so the next host rebuild leaves the right slot to
 * whichever panel is open instead.
 *
 * Internal: used by tests that share this module-wide state across mounts,
 * where a panel left open by one test (its element already detached, so its
 * close transition can never end) would otherwise hold the slot in the next.
 *
 * @see toggleThemeManagerSidebar
 */
internal fun closeThemeManagerSidebar() {
    closeThemeManager()
    teardownAppearanceTabEsc()
    sidebarOpen = false
    currentSidebar = null
}

/** Removes the settings tabs' Escape handler, if installed. */
private fun teardownAppearanceTabEsc() {
    appearanceTabEscHandler?.let { document.removeEventListener("keydown", it) }
    appearanceTabEscHandler = null
}

/**
 * Build the right-sidebar `<aside>` element that hosts the Theme Manager.
 *
 * The element is returned to the caller for them to pass into their
 * `AppFrameSpec(rightSidebar = …)` slot. It starts at `width: 0` and
 * slides to [initialWidthPx] on the next animation frame using the CSS
 * `transition: width` shipped on `.dt-sidebar`. The Theme Manager itself
 * is mounted into the sidebar's content slot once the slide is queued.
 *
 * Calling this when [isThemeManagerSidebarOpen] returns `false` is
 * supported but pointless — the caller usually gates on it.
 *
 * @param host             theme-manager host (typically a [DefaultThemeManagerHost]).
 * @param initialWidthPx   width to slide to on open. Persisted across toggles
 *   via the controller's last-width memory; the caller usually passes the
 *   value persisted in their layout state.
 * @param minWidthPx       minimum width the resize gesture allows. Defaults
 *   to 320 — the Theme Manager's interior is uncomfortable below this.
 * @param maxWidthPx       maximum width the resize gesture allows. Defaults
 *   to 600.
 * @param onResize         fired on mouseup with the user's chosen width.
 *   Hosts persist this so the next open uses the new width.
 * @param isResizable      when true (the default), an inside-edge drag handle
 *   is attached so the user can resize the panel like the left sidebar.
 *   Within-session width is preserved via the module-level last-width memory;
 *   pass an [onResize] callback to also persist across reloads.
 * @param appearanceBody   when set, the sidebar is the Appearance pane: a
 *   "Themes" | "Appearance" | "Fonts" | "Behaviour" tab strip above the body,
 *   and this fills every tab's body but Themes (it is handed the tab and an
 *   empty element to fill). `null` (the default) mounts the Theme Manager
 *   alone, as before.
 * @param appearanceTabs   the tabs the Appearance pane shows, in order; a tab
 *   with nothing in it (Behaviour outside Electron) is left out. Themes is
 *   always shown. Ignored without [appearanceBody].
 * @return the freshly-mounted sidebar element.
 */
fun buildThemeManagerSidebar(
    host: ThemeManagerHost,
    initialWidthPx: Int = lastWidthPx,
    minWidthPx: Int = 400,
    // Lifted from 600 → 1100 so users who want to scan many theme
    // thumbnails at once can drag the panel wider without hitting an
    // arbitrary cap. The sidebar's CSS layout adapts to the available
    // width (cards reflow into more columns as it grows).
    maxWidthPx: Int = 1100,
    onResize: ((Int) -> Unit)? = null,
    isResizable: Boolean = true,
    appearanceBody: ((AppearanceTab, HTMLElement) -> Unit)? = null,
    appearanceTabs: List<AppearanceTab> = AppearanceTab.entries,
): HTMLElement {
    // Hosts persist sidebar widths in app-level layout state, where the
    // default is often 260px (LayoutState.SidebarState.DEFAULT_SIDEBAR_WIDTH_PX).
    // 260 is fine for content sidebars but too narrow to render the theme
    // grid without overflow, so clamp up to the manager's minimum here.
    val resolvedWidthPx = initialWidthPx.coerceAtLeast(minWidthPx)
    lastWidthPx = resolvedWidthPx

    // mountTarget is a flex column that fills the sidebar's content slot.
    // Avoid `height: 100%` here — the percentage chain through the
    // sidebar's content wrap fails to resolve in some browsers when the
    // parent's height comes from `flex-grow`, leaving the theme-manager
    // body with no definite height to scroll within. `.dt-sidebar-content`
    // is a flex column, so a flex-based fill is robust.
    val mountTarget = document.createElement("div") as HTMLElement
    mountTarget.style.apply {
        width = "100%"
        flex = "1 1 auto"
        setProperty("min-height", "0")
        display = "flex"
        flexDirection = "column"
    }

    // Detect re-mount: we already had a theme-manager sidebar in the DOM
    // before the host's rebuildShell tore it down. From the user's POV
    // this is not an "open" transition, so the slide-in animation must
    // not play (otherwise every unrelated rebuild — tab switch, hide tab,
    // pane mutation — re-animates the theme editor).
    val isReMount = currentSidebar != null

    val sidebar = renderRightSidebar(
        SidebarSpec(
            widthPx = resolvedWidthPx,
            content = mountTarget,
            visible = true,
            isResizable = isResizable,
            minWidthPx = minWidthPx,
            maxWidthPx = maxWidthPx,
            onResize = { newWidth ->
                lastWidthPx = newWidth
                onResize?.invoke(newWidth)
            },
        )
    )
    currentSidebar = sidebar
    // Route the panel's own close affordances (X button, Escape) through
    // the sidebar's animate-and-rebuild close path so the slot collapses
    // and the main content reclaims the freed width. Without this, the
    // bare `closeThemeManager()` only detaches the inner panel and the
    // outer sidebar stays mounted at full width, empty.
    val requestClose = {
        val rebuild = lastRequestRebuild
        if (rebuild != null) toggleThemeManagerSidebar(rebuild)
        else closeThemeManager()
    }
    themeManagerOnCloseRequested = requestClose

    // What the sidebar's content is once it has slid open: the Theme Manager
    // alone, or the Appearance pane's tab strip and current tab.
    val mountContent: () -> Unit = if (appearanceBody == null) {
        { showThemeManager(hostArg = host, mountInto = mountTarget) }
    } else {
        { mountAppearancePane(mountTarget, host, appearanceBody, appearanceTabs, requestClose) }
    }

    if (isReMount) {
        // Re-mount path: present at full width immediately, no slide-in.
        sidebar.style.width = "${resolvedWidthPx}px"
        mountContent()
    } else {
        // Real open transition: slide width 0 → target. The element is
        // returned to the caller pre-mount and they will appendChild() it;
        // a SINGLE rAF here fires before the appended element ever paints
        // at width:0, so the browser commits straight to the final width
        // and the transition never starts (visible bug when toggling
        // between Settings and Theme Manager — the closing panel animates
        // out but the opening one "just appears"). Double-rAF guarantees
        // one paint frame at width:0 before the second rAF flips to the
        // target width — giving the CSS `transition: width` a starting
        // frame to interpolate from. The content mounts on the same second
        // frame so its CSS variable inheritance has resolved by the time it
        // paints.
        sidebar.style.width = "0px"
        window.requestAnimationFrame {
            window.requestAnimationFrame {
                sidebar.style.width = "${resolvedWidthPx}px"
                mountContent()
            }
        }
    }
    return sidebar
}

/**
 * Fills [target] with the Appearance pane: the tab strip (with the pane's
 * close button) and the current tab's body.
 *
 * Switching tab re-mounts the body in place, without a shell rebuild. Leaving
 * Themes closes the Theme Manager (which owns the Escape key while it is
 * shown); the settings tabs install their own Escape handler instead. The
 * inner panels' own headers keep their filters and controls; their titles and
 * close buttons are hidden by `lunula.css` under `.dt-appearance-pane`, since
 * the tab strip names the tab and closes the pane.
 *
 * Called by [buildThemeManagerSidebar] when it is given an `appearanceBody`.
 *
 * @param target         the sidebar's content element.
 * @param host           the Theme Manager's host.
 * @param appearanceBody fills a settings tab's body (every tab but Themes).
 * @param tabs           the tabs to show, in order.
 * @param requestClose   closes the whole pane (animated).
 */
private fun mountAppearancePane(
    target: HTMLElement,
    host: ThemeManagerHost,
    appearanceBody: (AppearanceTab, HTMLElement) -> Unit,
    tabs: List<AppearanceTab>,
    requestClose: () -> Unit,
) {
    val shown = if (AppearanceTab.Themes in tabs) tabs else listOf(AppearanceTab.Themes) + tabs
    target.innerHTML = ""
    target.classList.add("dt-appearance-pane")

    val strip = document.createElement("div") as HTMLElement
    strip.className = "dt-appearance-tabs"
    strip.setAttribute("role", "tablist")
    target.appendChild(strip)

    val bodyTarget = document.createElement("div") as HTMLElement
    bodyTarget.className = "dt-appearance-pane-body"
    target.appendChild(bodyTarget)

    val buttons = mutableMapOf<AppearanceTab, HTMLElement>()
    fun show(tab: AppearanceTab) {
        currentAppearanceTab = tab
        target.setAttribute("data-dt-appearance-tab", tab.name.lowercase())
        for ((t, b) in buttons) {
            val on = t == tab
            if (on) b.classList.add("dt-selected") else b.classList.remove("dt-selected")
            b.setAttribute("aria-selected", on.toString())
        }
        when (tab) {
            AppearanceTab.Themes -> {
                teardownAppearanceTabEsc()
                bodyTarget.innerHTML = ""
                // closeThemeManager (on leaving this tab) clears the close
                // hook, so it is set again on every visit.
                themeManagerOnCloseRequested = requestClose
                showThemeManager(hostArg = host, mountInto = bodyTarget)
            }
            AppearanceTab.Appearance, AppearanceTab.Fonts, AppearanceTab.Behaviour -> {
                closeThemeManager()
                bodyTarget.innerHTML = ""
                appearanceBody(tab, bodyTarget)
                teardownAppearanceTabEsc()
                val handler: (Event) -> Unit = { ev ->
                    if ((ev as? KeyboardEvent)?.key == "Escape") requestClose()
                }
                document.addEventListener("keydown", handler)
                appearanceTabEscHandler = handler
            }
        }
    }
    for (tab in shown) {
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        btn.setAttribute("role", "tab")
        btn.setAttribute("data-dt-appearance-tab", tab.name.lowercase())
        btn.className = "dt-appearance-tab"
        btn.textContent = tab.label
        btn.addEventListener("click", { if (currentAppearanceTab != tab) show(tab) })
        buttons[tab] = btn
        strip.appendChild(btn)
    }
    val close = document.createElement("button") as HTMLElement
    close.setAttribute("type", "button")
    close.className = "dt-appearance-pane-close"
    close.title = "Close"
    close.innerHTML = "&times;"
    close.addEventListener("click", { requestClose() })
    strip.appendChild(close)

    // A tab this pane leaves out (opened by name, or last shown in another
    // app) falls back to Themes.
    show(if (currentAppearanceTab in shown) currentAppearanceTab else AppearanceTab.Themes)
}

