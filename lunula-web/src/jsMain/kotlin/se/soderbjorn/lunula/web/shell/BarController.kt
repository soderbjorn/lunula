/*
 * BarController.kt (jsMain)
 * --------------------------
 * Drag-to-hide controller for horizontal AppFrame bars (top bar / bottom
 * bar). Mirrors [SidebarController] for the vertical axis: owns the
 * `isVisible` flag, drives `requestRebuild` when drag-to-zero collapses
 * the bar, and re-mounts at full height when the host calls
 * [BarController.show].
 *
 * Why this lives in the toolkit rather than each host:
 *   - The naive "host wires onResize and sets bar height = 0" path is
 *     fragile — any code that re-renders the topbar slot (e.g. soft tab
 *     switches) replaces the inline `style.height = "0px"` with a fresh
 *     element at default height, restoring the bar the user just hid.
 *   - Two host apps (notegrow + termtastic) need exactly the same
 *     gesture; centralising it here avoids two divergent re-implementations.
 *
 * Persistence is intentionally NOT in the controller: each app picks its
 * own substrate (localStorage, IPC, server-backed). The host seeds the
 * controller via [setInitial] at boot and listens to [onVisibilityChanged]
 * to write back. The controller only exposes `isVisible` and the toggle
 * primitives.
 *
 * @see TopBarSpec
 * @see BottomBarSpec
 * @see SidebarController
 */
package se.soderbjorn.lunula.web.shell

import org.w3c.dom.HTMLElement

/**
 * Controller for a single horizontal bar's drag-to-hide visibility.
 *
 * Apps usually get one of the bundled module-scope instances via
 * [topBarController] / [bottomBarController]; create your own if you have
 * more horizontal bars in the same frame.
 */
class BarController {
    /** Whether the bar should currently be considered visible. */
    var isVisible: Boolean = true
        private set

    /** Currently-mounted bar element, or `null` when hidden. */
    private var current: HTMLElement? = null

    /**
     * Host-supplied callback fired whenever the controller flips
     * [isVisible]. Hosts wire this to their persistence layer
     * (localStorage, IPC, server-backed). Does not fire when [setInitial]
     * seeds the controller — boot-time hydration is not a user toggle.
     */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /**
     * Force the controller's visibility without firing
     * [onVisibilityChanged]. Used at boot to seed the controller from the
     * host's persisted state before any user interaction.
     */
    fun setInitial(visible: Boolean) {
        isVisible = visible
    }

    /**
     * Make the bar visible (e.g. via a "Show top bar" command palette
     * entry). Flips state and calls [requestRebuild]; the rebuild's
     * [mountTopBar] / [mountBottomBar] call re-attaches a fresh element.
     * No-op when already visible.
     */
    fun show(requestRebuild: () -> Unit) {
        if (isVisible) return
        isVisible = true
        current = null
        onVisibilityChanged?.invoke(true)
        requestRebuild()
    }

    /**
     * Hide the bar. Flips state and calls [requestRebuild]; the rebuild
     * sees [isVisible] = false and skips the mount call so the slot
     * collapses. No-op when already hidden.
     */
    fun hide(requestRebuild: () -> Unit) {
        if (!isVisible) return
        isVisible = false
        current = null
        onVisibilityChanged?.invoke(false)
        requestRebuild()
    }

    /** Toggle visibility — convenience for command-palette entries. */
    fun toggle(requestRebuild: () -> Unit) {
        if (isVisible) hide(requestRebuild) else show(requestRebuild)
    }

    /**
     * Convenience that calls [renderTopBar] with the host's [spec], wraps
     * its `onResize` to flip [isVisible] = false on drag-to-zero, and
     * remembers the element so [show] can detach it later. Hosts call
     * this from inside their rebuildShell unconditionally — the
     * controller decides whether to mount the full bar or a chromeless
     * 0-height placeholder.
     *
     * When [isVisible] is true: the host's original `onResize` is
     * called first (so apps that want to persist non-zero heights still
     * see the value), then — only if `newHeight == 0` — visibility flips
     * and the rebuild fires. The bar's spec is replaced with a
     * chromeless placeholder on the next mount so the user-hidden state
     * survives any subsequent re-render of the top-bar slot.
     *
     * When [isVisible] is false: a chromeless bar with just the resize
     * handle is mounted at height 0. The handle protrudes 3px past the
     * slot's edge (per the toolkit CSS), so the user can drag it back to
     * restore the bar — `onResize` flips visibility to true on any
     * non-zero release height, triggering a rebuild that re-mounts the
     * full bar with content.
     *
     * @param spec           top-bar spec from the host
     * @param requestRebuild called whenever visibility flips, so the
     *   host can re-run its `rebuildShell` and re-mount the bar in the
     *   appropriate (full vs placeholder) form.
     * @return the freshly-rendered top-bar element. Always non-null —
     *   even when hidden, the placeholder occupies the slot so its
     *   resize handle remains grabbable.
     */
    fun mountTopBar(spec: TopBarSpec, requestRebuild: () -> Unit): HTMLElement {
        val bar = if (isVisible) {
            renderTopBar(wrapSpecWithHideOnZero(spec, requestRebuild))
        } else {
            renderTopBar(placeholderSpec(spec, requestRebuild)).also(::collapseToZero)
        }
        current = bar
        return bar
    }

    /** [mountTopBar] for the bottom bar — see that doc for behaviour. */
    fun mountBottomBar(spec: BottomBarSpec, requestRebuild: () -> Unit): HTMLElement {
        val bar = if (isVisible) {
            renderBottomBar(wrapSpecWithHideOnZero(spec, requestRebuild))
        } else {
            renderBottomBar(placeholderSpec(spec, requestRebuild)).also(::collapseToZero)
        }
        current = bar
        return bar
    }

    /**
     * Build a chromeless [TopBarSpec] that carries only the resize
     * handle. Inherits the host's snap geometry ([TopBarSpec.minHeightPx],
     * [TopBarSpec.maxHeightPx], [TopBarSpec.defaultHeightPx],
     * [TopBarSpec.allowGrowBeyondDefault]) so a drag-back-to-restore
     * snaps to the same default the visible bar uses. Drops the bar's
     * leading / tabs / trailing slots so the placeholder paints as a
     * 0-height strip with no visible content.
     */
    private fun placeholderSpec(
        original: TopBarSpec,
        requestRebuild: () -> Unit,
    ): TopBarSpec = TopBarSpec(
        leadingContent = null,
        tabs = emptyList(),
        tabBar = null,
        trailingContent = null,
        isResizable = original.isResizable,
        minHeightPx = original.minHeightPx,
        maxHeightPx = original.maxHeightPx,
        defaultHeightPx = original.defaultHeightPx,
        allowGrowBeyondDefault = original.allowGrowBeyondDefault,
        onResize = { newHeight ->
            // Drag-back-to-restore: any non-zero release flips visibility
            // back on. The rebuild then re-mounts the full bar with
            // content via the regular [mountTopBar] path.
            if (newHeight > 0) show(requestRebuild)
        },
    )

    private fun placeholderSpec(
        original: BottomBarSpec,
        requestRebuild: () -> Unit,
    ): BottomBarSpec = BottomBarSpec(
        leadingContent = null,
        trailingContent = null,
        isResizable = original.isResizable,
        minHeightPx = original.minHeightPx,
        maxHeightPx = original.maxHeightPx,
        defaultHeightPx = original.defaultHeightPx,
        allowGrowBeyondDefault = original.allowGrowBeyondDefault,
        onResize = { newHeight ->
            if (newHeight > 0) show(requestRebuild)
        },
    )

    /**
     * Pin the placeholder bar to height 0 inline so the slot collapses
     * visually but the resize handle (which hangs 3px past the bar's
     * inside edge) remains in the DOM and grabbable.
     */
    private fun collapseToZero(bar: HTMLElement) {
        bar.style.height = "0px"
        bar.style.setProperty("min-height", "0")
        // Mark the bar as a drag-to-restore placeholder so the toolkit
        // CSS can paint the resize handle's hairline at all times
        // (instead of only on hover). Without that affordance the user
        // would have no visual cue that the now-empty strip can be
        // dragged back to bring the bar back.
        bar.classList.add("dt-bar-collapsed")
    }

    private fun wrapSpecWithHideOnZero(
        spec: TopBarSpec,
        requestRebuild: () -> Unit,
    ): TopBarSpec = TopBarSpec(
        leadingContent = spec.leadingContent,
        tabs = spec.tabs,
        activeTabId = spec.activeTabId,
        onTabSelected = spec.onTabSelected,
        tabBar = spec.tabBar,
        trailingContent = spec.trailingContent,
        isResizable = spec.isResizable,
        minHeightPx = spec.minHeightPx,
        maxHeightPx = spec.maxHeightPx,
        defaultHeightPx = spec.defaultHeightPx,
        allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
        onResize = { newHeight ->
            spec.onResize?.invoke(newHeight)
            if (newHeight == 0) hide(requestRebuild)
        },
    )

    private fun wrapSpecWithHideOnZero(
        spec: BottomBarSpec,
        requestRebuild: () -> Unit,
    ): BottomBarSpec = BottomBarSpec(
        leadingContent = spec.leadingContent,
        trailingContent = spec.trailingContent,
        isResizable = spec.isResizable,
        minHeightPx = spec.minHeightPx,
        maxHeightPx = spec.maxHeightPx,
        defaultHeightPx = spec.defaultHeightPx,
        allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
        onResize = { newHeight ->
            spec.onResize?.invoke(newHeight)
            if (newHeight == 0) hide(requestRebuild)
        },
    )
}

/** Bundled controller for the AppFrame's top-bar slot. */
val topBarController: BarController = BarController()

/** Bundled controller for the AppFrame's bottom-bar slot. */
val bottomBarController: BarController = BarController()
