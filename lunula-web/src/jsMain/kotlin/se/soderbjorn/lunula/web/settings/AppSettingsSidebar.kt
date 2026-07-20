/**
 * AppSettingsSidebar — slide-in right-side panel whose **body is supplied
 * by the host app**.
 *
 * Distinct from [SettingsSidebar] (which is the toolkit-owned appearance
 * / font / notifications panel) and from `ThemeManagerSidebar` (the
 * toolkit-owned theme editor). This third sidebar exists so each
 * Lunula app can surface its own app-specific preferences (e.g.
 * termtastic's server-settings link and experimental-feature toggles)
 * through the same chrome and animation contract as the toolkit panels,
 * without the toolkit knowing the body's content.
 *
 * Mutual exclusion with the [SettingsSidebar] and `ThemeManagerSidebar`
 * is enforced at the topbar-button level in `AppShellMount.buildTopBar`:
 * each of the three toggle buttons closes whichever of its two siblings
 * is currently open before opening itself, so only one right-sidebar is
 * ever mounted at a time.
 *
 * Public API mirrors [SettingsSidebar] so apps wire it the same way:
 *   - [toggleAppSettingsSidebar] flips state and asks the host to rebuild.
 *   - [isAppSettingsSidebarOpen] is consulted by the host's rebuild path.
 *   - [buildAppSettingsSidebar] returns the freshly-mounted `<aside>` once
 *     the host's rebuild reaches the right-sidebar slot.
 *
 * @see AppSettingsSidebarSpec
 * @see se.soderbjorn.lunula.web.shell.AppShellSpec.appSettingsContent
 */
package se.soderbjorn.lunula.web.settings

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunula.web.shell.SidebarSpec
import se.soderbjorn.lunula.web.shell.renderRightSidebar

/**
 * Spec passed to [buildAppSettingsSidebar].
 *
 * @property title       header label shown at the top of the sidebar.
 *   Apps typically pass "App settings" but may localise.
 * @property bodyFactory invoked once per (re)mount; returns the element
 *   that fills the scrollable body of the sidebar. The toolkit appends
 *   it verbatim into `.dt-settings-sidebar-sections` so apps can layer
 *   their own CSS on the inner element classes.
 */
data class AppSettingsSidebarSpec(
    val title: String,
    val bodyFactory: () -> HTMLElement,
)

/** True while the App Settings sidebar is considered open. */
private var sidebarOpen: Boolean = false

/** The currently-mounted sidebar element, or null when closed. */
private var currentSidebar: HTMLElement? = null

/** Last-known target width — used as the slide-in destination on next mount. */
private var lastWidthPx: Int = 420

/**
 * Latest `requestRebuild` callback handed to [toggleAppSettingsSidebar].
 *
 * Captured so the panel's own close affordances (X button, Escape) can
 * trigger the same animate-then-rebuild close path as the topbar toggle.
 */
private var lastRequestRebuild: (() -> Unit)? = null

/** Document-level Escape handler installed while the sidebar is open. */
private var escHandler: ((Event) -> Unit)? = null

/**
 * Whether the App Settings sidebar is currently open. The host's rebuild
 * path consults this to decide whether to mount the sidebar.
 *
 * @return `true` while the sidebar is open or in the process of closing.
 */
fun isAppSettingsSidebarOpen(): Boolean = sidebarOpen

/**
 * Toggle the App Settings sidebar open/closed.
 *
 * On open: flips state to true, clears any stale element reference (so
 * the next mount runs the slide-in animation), and calls [requestRebuild]
 * so the host re-renders its AppFrame with the sidebar slot populated.
 *
 * On close: animates the existing sidebar element to width 0, waits for
 * `transitionend`, then flips state to false and calls [requestRebuild].
 *
 * @param requestRebuild host-supplied callback that re-renders the
 *   AppFrame; the sidebar slot consults [isAppSettingsSidebarOpen] on each
 *   pass and either mounts or omits this panel accordingly.
 */
fun toggleAppSettingsSidebar(requestRebuild: () -> Unit) {
    lastRequestRebuild = requestRebuild
    if (!sidebarOpen) {
        sidebarOpen = true
        currentSidebar = null
        requestRebuild()
        return
    }
    val sidebar = currentSidebar
    if (sidebar == null) {
        sidebarOpen = false
        requestRebuild()
        return
    }
    var done = false
    val handler: (Event) -> Unit = handler@{ ev ->
        if (done) return@handler
        if (ev.target !== sidebar) return@handler
        done = true
        sidebarOpen = false
        currentSidebar = null
        teardownEscHandler()
        requestRebuild()
    }
    sidebar.addEventListener("transitionend", handler)
    window.requestAnimationFrame { sidebar.style.width = "0px" }
}

/**
 * Force-close the App Settings sidebar synchronously (no animation).
 *
 * Called by the topbar buttons that open one of the sibling sidebars so
 * the three right-side panels are mutually exclusive: the host calls
 * this first, then opens the other. The sidebar element is detached on
 * the next `requestRebuild`.
 */
fun closeAppSettingsSidebar() {
    sidebarOpen = false
    currentSidebar = null
    teardownEscHandler()
}

private fun teardownEscHandler() {
    escHandler?.let { document.removeEventListener("keydown", it) }
    escHandler = null
}

/**
 * Build the right-sidebar `<aside>` element that hosts the App Settings panel.
 *
 * Returned to the caller for them to append into their right-slot. Starts
 * at `width: 0` and slides to [lastWidthPx]. Double-rAF before applying the
 * target width so the browser doesn't skip the transition when switching
 * between sibling sidebars.
 *
 * @param spec the sidebar configuration (title + body factory).
 * @return the freshly-mounted sidebar element.
 * @see AppSettingsSidebarSpec
 */
fun buildAppSettingsSidebar(spec: AppSettingsSidebarSpec): HTMLElement {
    val resolvedWidthPx = lastWidthPx

    val mountTarget = document.createElement("div") as HTMLElement
    mountTarget.style.apply {
        width = "100%"
        flex = "1 1 auto"
        setProperty("min-height", "0")
        display = "flex"
        flexDirection = "column"
    }

    val isReMount = currentSidebar != null

    val sidebar = renderRightSidebar(
        SidebarSpec(
            widthPx = resolvedWidthPx,
            content = mountTarget,
            visible = true,
            isResizable = false,
            minWidthPx = 360,
            maxWidthPx = 600,
        )
    )
    currentSidebar = sidebar

    if (isReMount) {
        sidebar.style.width = "${resolvedWidthPx}px"
        renderAppSettingsBody(mountTarget, spec)
    } else {
        // Double-rAF for the slide-in transition — single rAF can fire
        // before the appended element ever paints at width:0, which
        // makes the browser skip the transition when switching between
        // sibling sidebars (Theme Manager / Settings / App Settings).
        sidebar.style.width = "0px"
        window.requestAnimationFrame {
            window.requestAnimationFrame {
                sidebar.style.width = "${resolvedWidthPx}px"
                renderAppSettingsBody(mountTarget, spec)
            }
        }
    }

    teardownEscHandler()
    val handler: (Event) -> Unit = { ev ->
        val key = (ev as? KeyboardEvent)?.key
        if (key == "Escape") {
            val rebuild = lastRequestRebuild
            if (rebuild != null) toggleAppSettingsSidebar(rebuild)
        }
    }
    document.addEventListener("keydown", handler)
    escHandler = handler

    return sidebar
}

/**
 * Mounts the panel's chrome (header + close button) into [target] and
 * appends the host-supplied body element underneath.
 *
 * Reuses the `.dt-settings-sidebar-*` CSS classes from
 * [SettingsSidebar] so the App Settings panel reads visually identical
 * to its sibling. Apps that need to style their inner body element layer
 * on top via the bodyFactory's returned element.
 */
private fun renderAppSettingsBody(target: HTMLElement, spec: AppSettingsSidebarSpec) {
    target.innerHTML = ""

    val panel = document.createElement("div") as HTMLElement
    panel.className = "dt-settings-sidebar-body"

    val header = document.createElement("div") as HTMLElement
    header.className = "dt-settings-sidebar-header"

    val title = document.createElement("h2") as HTMLElement
    title.className = "dt-settings-sidebar-title"
    title.textContent = spec.title
    header.appendChild(title)

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "dt-settings-sidebar-close"
    closeBtn.setAttribute("type", "button")
    closeBtn.innerHTML = "&times;"
    closeBtn.title = "Close"
    closeBtn.addEventListener("click", {
        val rebuild = lastRequestRebuild
        if (rebuild != null) toggleAppSettingsSidebar(rebuild)
    })
    header.appendChild(closeBtn)

    panel.appendChild(header)

    val body = document.createElement("div") as HTMLElement
    body.className = "dt-settings-sidebar-sections"
    body.appendChild(spec.bodyFactory())
    panel.appendChild(body)

    target.appendChild(panel)
}
