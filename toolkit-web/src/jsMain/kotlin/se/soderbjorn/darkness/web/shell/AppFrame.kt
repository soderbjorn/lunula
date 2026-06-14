/**
 * Top-level shell primitive that composes the toolkit's chrome slots —
 * top bar, optional left sidebar, main content, optional right sidebar —
 * into a single host element with a uniform flex layout.
 *
 * Apps construct an [AppFrameSpec] with whatever slot elements they want
 * (typically built via [renderTopBar], [renderSidebar], or freshly created
 * `div`s), pass it to [renderAppFrame], and append the returned element to
 * their host container (e.g. `<div id="app">`). The frame owns no state —
 * apps mutate their slot elements in place or call [renderAppFrame] again
 * with a fresh spec to rebuild.
 *
 * Visual styles ride on the `.dt-app-frame*` classes shipped in
 * `darkness-toolkit.css`; consumers must call `injectDarknessToolkitStyles()`
 * once at boot.
 *
 * @see AppFrameSpec
 * @see renderAppFrame
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Slot configuration for the app frame.
 *
 * @property topBar         element shown across the top of the frame
 *   (typically the result of [renderTopBar]). Pass `null` to omit the
 *   top-bar row entirely.
 * @property leftSidebar    element shown to the left of [main]. Pass
 *   `null` to omit the left column.
 * @property main           required main-content element. Most apps mount
 *   their pane tree (`LayoutRenderer`) here.
 * @property rightSidebar   element shown to the right of [main]. Pass
 *   `null` to omit the right column. Theme-editor apps typically pass the
 *   `ThemeManager` host here.
 */
class AppFrameSpec(
    val topBar: HTMLElement? = null,
    val leftSidebar: HTMLElement? = null,
    val main: HTMLElement,
    val rightSidebar: HTMLElement? = null,
    /**
     * Optional status row anchored to the very bottom of the frame
     * (typically the result of [renderBottomBar]). Pass `null` to omit
     * the bottom row entirely.
     */
    val bottomBar: HTMLElement? = null,
)

/** Toolkit DOM class names used by [renderAppFrame]. */
object AppFrameClassNames {
    const val FRAME = "dt-app-frame"
    const val TOPBAR_SLOT = "dt-app-frame-topbar"
    const val BODY = "dt-app-frame-body"
    const val SIDEBAR_LEFT = "dt-app-frame-sidebar-left"
    const val MAIN = "dt-app-frame-main"
    const val SIDEBAR_RIGHT = "dt-app-frame-sidebar-right"
    const val BOTTOMBAR_SLOT = "dt-app-frame-bottombar"
}

/**
 * Builds the app-frame element for the given [spec].
 *
 * Layout:
 * - vertical column: optional top-bar row, then a body row that fills the
 *   remaining vertical space.
 * - the body row is a horizontal flex with optional left sidebar, the
 *   required main slot (which grows to fill leftover horizontal space),
 *   and an optional right sidebar.
 *
 * The frame fills its parent (`width: 100%; height: 100%`) — apps are
 * responsible for sizing the host element (e.g. `#app { height: 100vh }`).
 *
 * @param spec slot configuration
 * @return a fresh app-frame [HTMLElement] ready to be appended to the host
 */
fun renderAppFrame(spec: AppFrameSpec): HTMLElement {
    val frame = document.createElement("div") as HTMLElement
    frame.className = AppFrameClassNames.FRAME

    spec.topBar?.let {
        val topSlot = document.createElement("div") as HTMLElement
        topSlot.className = AppFrameClassNames.TOPBAR_SLOT
        topSlot.appendChild(it)
        frame.appendChild(topSlot)
    }

    val body = document.createElement("div") as HTMLElement
    body.className = AppFrameClassNames.BODY

    spec.leftSidebar?.let {
        val leftSlot = document.createElement("div") as HTMLElement
        leftSlot.className = AppFrameClassNames.SIDEBAR_LEFT
        leftSlot.appendChild(it)
        body.appendChild(leftSlot)
    }

    val mainSlot = document.createElement("div") as HTMLElement
    mainSlot.className = AppFrameClassNames.MAIN
    mainSlot.appendChild(spec.main)
    body.appendChild(mainSlot)

    spec.rightSidebar?.let {
        val rightSlot = document.createElement("div") as HTMLElement
        rightSlot.className = AppFrameClassNames.SIDEBAR_RIGHT
        rightSlot.appendChild(it)
        body.appendChild(rightSlot)
    }

    frame.appendChild(body)

    spec.bottomBar?.let {
        val bottomSlot = document.createElement("div") as HTMLElement
        bottomSlot.className = AppFrameClassNames.BOTTOMBAR_SLOT
        bottomSlot.appendChild(it)
        frame.appendChild(bottomSlot)
    }

    return frame
}

/**
 * Convenience that wipes [host] and mounts the app frame into it.
 *
 * Typical app entry-points use this once at boot. Equivalent to
 * `host.innerHTML = ""; host.appendChild(renderAppFrame(spec))`, but
 * lets the host tag stay free of layout HTML.
 *
 * @param host the host element to mount into (e.g. `<div id="app">`)
 * @param spec slot configuration passed through to [renderAppFrame]
 */
fun mountAppFrame(host: HTMLElement, spec: AppFrameSpec) {
    while (host.firstChild != null) host.removeChild(host.firstChild!!)
    host.appendChild(renderAppFrame(spec))
}
