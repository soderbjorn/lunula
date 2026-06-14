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
 */
package se.soderbjorn.darkness.web.themeeditor

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import se.soderbjorn.darkness.web.shell.SidebarSpec
import se.soderbjorn.darkness.web.shell.renderRightSidebar

/** True while the Theme Manager sidebar is considered open. */
private var sidebarOpen: Boolean = false

/** The currently-mounted sidebar element, or null when closed. */
private var currentSidebar: HTMLElement? = null

/** Last-known target width — used as the slide-in destination on next mount. */
private var lastWidthPx: Int = 480

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
    themeManagerOnCloseRequested = {
        val rebuild = lastRequestRebuild
        if (rebuild != null) toggleThemeManagerSidebar(rebuild)
        else closeThemeManager()
    }
    if (isReMount) {
        // Re-mount path: present at full width immediately, no slide-in.
        sidebar.style.width = "${resolvedWidthPx}px"
        showThemeManager(hostArg = host, mountInto = mountTarget)
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
        // frame to interpolate from. The ThemeManager content mounts on
        // the same second frame so its CSS variable inheritance has
        // resolved by the time it paints.
        sidebar.style.width = "0px"
        window.requestAnimationFrame {
            window.requestAnimationFrame {
                sidebar.style.width = "${resolvedWidthPx}px"
                showThemeManager(hostArg = host, mountInto = mountTarget)
            }
        }
    }
    return sidebar
}
