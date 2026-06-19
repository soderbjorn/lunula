/*
 * SidebarController.kt (jsMain)
 * ------------------------------
 * Generic open/close + slide-animation controller for any sidebar
 * mounted in the AppFrame. Apps that just want "click a button → sidebar
 * slides in / out" without hand-rolling rAF + transitionend bookkeeping
 * use [SidebarController] instead of tracking the visibility flag and
 * width target themselves.
 *
 * Two parallel controllers come bundled — [leftSidebarController] and
 * [rightSidebarController] — so the most common case (a single left
 * panel + a single right panel) takes zero setup. Apps that need more
 * sidebars can construct extra [SidebarController] instances.
 *
 * Lifecycle (mirrors `ThemeManagerSidebarController`):
 *   1. App calls [SidebarController.toggle] with a `requestRebuild` callback.
 *   2. Controller flips `isOpen` and immediately invokes the rebuild
 *      callback. The host's rebuildShell sees `isOpen == true` and
 *      includes the sidebar in its AppFrameSpec slot.
 *   3. The host calls [SidebarController.attachToBuiltSidebar] (or relies
 *      on the convenience `mountSidebar(...)` helper) on the freshly
 *      created sidebar element so the controller can drive width 0 →
 *      target on the next animation frame.
 *   4. Next toggle → controller animates the existing element to width
 *      0, waits for `transitionend`, then flips `isOpen` and calls
 *      requestRebuild again so the host re-renders without the sidebar.
 */
package se.soderbjorn.darkness.web.shell

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Controller for a single sidebar's open/close + slide animation.
 *
 * Apps usually get one of the bundled module-scope instances via
 * [leftSidebarController] / [rightSidebarController]; create your own
 * if you have more sidebars in the same frame.
 *
 * @param defaultWidthPx initial target width if the host hasn't passed
 *   one through [attachToBuiltSidebar] yet.
 * @param collapseSnapPx if the user releases the resize handle below
 *   this width (but above zero), the gesture is treated as a
 *   drag-to-collapse: the host's `onResize` is invoked with `0` and
 *   the previously-good [widthPx] is preserved so a subsequent
 *   icon-restore opens the sidebar at the prior width rather than at
 *   the tiny sliver the user happened to release on. Defaults to `0`
 *   (snap disabled) so small widths persist as the user dragged them
 *   — releasing near the edge leaves a thin reachable strip with the
 *   live in-bar handle, instead of switching to the placeholder whose
 *   handle bleeds 30–44 px in from the edge to dodge the OS resize
 *   gutter (which reads as the bar "popping back out" some distance
 *   from where the user released). Pass a positive value to opt back
 *   into snap-to-collapse.
 */
class SidebarController(
    defaultWidthPx: Int = 240,
    private val collapseSnapPx: Int = 0,
) {
    /** Whether the sidebar should currently be considered open. */
    var isOpen: Boolean = false
        private set

    /** Last-known target width. Persisted across toggles. */
    var widthPx: Int = defaultWidthPx
        private set

    /** Currently-mounted sidebar element, or `null` when closed. */
    private var current: HTMLElement? = null

    /** When `true`, the next [attachToBuiltSidebar] call mounts at the
     *  target width directly (no slide-in). Used for the boot path so
     *  apps don't see a sidebar slide every cold start. */
    private var skipNextOpenAnimation: Boolean = false

    /**
     * Force the controller's open state without animation. Useful at boot
     * to seed the controller from the host's persisted layout state
     * before any toggle has happened. Does not call requestRebuild.
     */
    fun setInitial(open: Boolean, widthPx: Int) {
        isOpen = open
        this.widthPx = widthPx
        // The boot mount that follows this setInitial is the initial
        // appearance, not a user-driven toggle — skip the slide-in so
        // the sidebar is just there.
        if (open) skipNextOpenAnimation = true
    }

    /**
     * Toggle the sidebar open / closed.
     *
     * On open: flips state to true and immediately calls [requestRebuild]
     * so the host re-renders with the sidebar slot populated. The host's
     * rebuild path then calls [attachToBuiltSidebar] which schedules the
     * width 0 → target animation for the next frame.
     *
     * On close: animates the existing element to width 0, then flips
     * state to false and calls [requestRebuild] so the host re-renders
     * without the slot.
     *
     * @param requestRebuild called once per state flip
     */
    fun toggle(requestRebuild: () -> Unit) {
        if (!isOpen) {
            isOpen = true
            // Clear stale reference so the next attach plays the slide-in
            // (real open transition) instead of being treated as a re-mount.
            current = null
            requestRebuild()
            return
        }
        val sidebar = current
        if (sidebar == null) {
            isOpen = false
            requestRebuild()
            return
        }
        // If the bar is already at (or essentially at) 0 width — which
        // happens when the user just dragged the resize handle to the
        // collapse threshold and the host then routed the close through
        // [toggle] — animating to 0 again would not produce a
        // [transitionend] event (no value change → no transition fires)
        // and the controller would be stranded with `isOpen = true` while
        // the bar is visually closed. Flip immediately when there is no
        // real animation to wait for.
        val currentWidth = sidebar.getBoundingClientRect().width
        if (currentWidth <= 0.5) {
            isOpen = false
            current = null
            requestRebuild()
            return
        }
        var done = false
        val handler: (Event) -> Unit = handler@{ ev ->
            if (done) return@handler
            if (ev.target !== sidebar) return@handler
            done = true
            isOpen = false
            current = null
            requestRebuild()
        }
        sidebar.addEventListener("transitionend", handler)
        window.requestAnimationFrame { sidebar.style.width = "0px" }
    }

    /**
     * Register the freshly-built sidebar element with the controller so
     * it can (a) animate width 0 → [widthPx] on this frame, and (b)
     * have a handle to animate back to 0 on the next close. Hosts call
     * this from inside their rebuildShell after constructing the
     * sidebar via [renderLeftSidebar] / [renderRightSidebar].
     *
     * If [updateWidth] is non-null, the controller also updates its
     * persisted [widthPx] before animating in — useful when the host
     * has just loaded a new value from disk.
     */
    fun attachToBuiltSidebar(sidebar: HTMLElement, updateWidth: Int? = null) {
        if (updateWidth != null) widthPx = updateWidth
        // Detect re-mount: a sidebar was attached before, we're attaching
        // again, and the open state hasn't actually changed. The host's
        // rebuildShell path will tear down the prior DOM element and call
        // us again with a fresh one — but from the user's POV this is not
        // a real "open" transition, so we must NOT play the slide-in
        // (otherwise every tab switch / hide-show / theme toggle re-runs
        // both sidebars' width 0 → target animations).
        val isReMount = current != null
        current = sidebar
        if (skipNextOpenAnimation || isReMount) {
            // Boot path OR re-mount: mount at the target width directly so
            // the sidebar is just present, no slide-in.
            skipNextOpenAnimation = false
            sidebar.style.width = "${widthPx}px"
            return
        }
        sidebar.style.width = "0px"
        window.requestAnimationFrame {
            sidebar.style.width = "${widthPx}px"
        }
    }

    /**
     * Convenience that combines [renderLeftSidebar] (or the right variant)
     * with [attachToBuiltSidebar] in one call. Hosts use this from
     * inside their rebuildShell when [isOpen] is true.
     *
     * @param spec     sidebar configuration; [SidebarSpec.widthPx] is
     *   ignored — the controller's [widthPx] is used so persistence
     *   round-trips correctly.
     * @param onLeft   `true` to use [renderLeftSidebar], `false` for
     *   [renderRightSidebar].
     */
    fun mountSidebar(spec: SidebarSpec, onLeft: Boolean): HTMLElement {
        val effectiveSpec = SidebarSpec(
            widthPx = widthPx,
            header = spec.header,
            content = spec.content,
            footer = spec.footer,
            visible = spec.visible,
            isResizable = spec.isResizable,
            minWidthPx = spec.minWidthPx,
            maxWidthPx = spec.maxWidthPx,
            defaultWidthPx = spec.defaultWidthPx,
            allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
            onResize = { newWidth ->
                // Snap small drags to a collapse: if the user releases
                // below [collapseSnapPx] the gesture is plainly "I want
                // this hidden", but persisting the literal sliver width
                // (e.g. 20px) would (a) strand the bar at a barely-
                // visible strip and (b) overwrite the previously-good
                // [widthPx] so a subsequent icon-restore would re-open
                // at that strip instead of the prior usable width. By
                // forwarding `0` to the host instead, the host's
                // own drag-to-zero branch fires and its preserved
                // widthPx is what gets used on next open.
                val effective = if (newWidth in 1 until collapseSnapPx) 0 else newWidth
                // Drag-to-collapse must NOT overwrite [widthPx] to 0 —
                // that would strand the toggle button (it re-mounts the
                // bar at the controller's `widthPx`, so a value of 0
                // means the toggle restores a visually-empty sidebar).
                // Hosts already preserve their own persisted widthPx
                // via [spec.onResize] choosing what to write on the 0
                // case.
                if (effective > 0) widthPx = effective
                spec.onResize?.invoke(effective)
            },
        )
        val sidebar = if (onLeft) renderLeftSidebar(effectiveSpec)
                      else renderRightSidebar(effectiveSpec)
        attachToBuiltSidebar(sidebar)
        return sidebar
    }

    /**
     * Mount the full sidebar when [isOpen] is true, otherwise mount a
     * chromeless 0-width placeholder that carries only the resize
     * handle. Mirrors `BarController.mountTopBar` for the horizontal
     * axis: keeps a draggable strip in the DOM while the sidebar is
     * collapsed so the user can drag it back to restore (without this,
     * dragging-to-collapse strands the user, since the only way to
     * bring the sidebar back is the host's toggle button).
     *
     * The placeholder inherits the host's resize geometry from [spec]
     * ([SidebarSpec.minWidthPx], [SidebarSpec.maxWidthPx],
     * [SidebarSpec.defaultWidthPx], [SidebarSpec.allowGrowBeyondDefault])
     * so a drag-back-to-restore honours the same min/max cap the open
     * sidebar uses. The sidebar handle itself does not snap — any
     * non-zero release width restores at exactly that width. When the
     * user releases at any non-zero width, the controller flips
     * [isOpen] = true (skipping the slide-in animation since the user
     * already dragged the visual reveal manually) and fires
     * [requestRebuild] so the host re-mounts with full content.
     *
     * @param spec           sidebar configuration; only the snap
     *   geometry fields are honoured when collapsed (header/content
     *   are dropped from the placeholder). [SidebarSpec.widthPx] is
     *   ignored — the controller's [widthPx] is used.
     * @param onLeft         `true` for the left sidebar, `false` for
     *   the right.
     * @param requestRebuild fired when the user drags the collapsed
     *   placeholder back open.
     * @return the freshly-mounted sidebar element. Always non-null —
     *   even when collapsed, the placeholder occupies the slot so its
     *   resize handle remains grabbable.
     */
    fun mountSidebarOrPlaceholder(
        spec: SidebarSpec,
        onLeft: Boolean,
        requestRebuild: () -> Unit,
    ): HTMLElement {
        if (isOpen) {
            // Wrap the host's onResize so a drag-to-zero gesture from
            // the open sidebar's handle (post-collapse-snap, [mountSidebar]
            // already coerced sub-[collapseSnapPx] releases to 0) flips
            // [isOpen] = false AND fires [requestRebuild]. Without that
            // chain the controller stays open at width 0 forever — the
            // open-mode CSS keeps the handle inside the bar's invisible
            // bounds (which clip overflow to width 0), and there's no
            // grabbable strip past the window edge to drag back open.
            // The placeholder branch below — only reached when isOpen is
            // false — is what mounts the wider, bleeding handle.
            val wrappedSpec = SidebarSpec(
                widthPx = spec.widthPx,
                header = spec.header,
                content = spec.content,
                footer = spec.footer,
                visible = spec.visible,
                isResizable = spec.isResizable,
                minWidthPx = spec.minWidthPx,
                maxWidthPx = spec.maxWidthPx,
                defaultWidthPx = spec.defaultWidthPx,
                allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
                onResize = { newWidth ->
                    spec.onResize?.invoke(newWidth)
                    if (newWidth <= 0) {
                        isOpen = false
                        current = null
                        requestRebuild()
                    }
                },
            )
            return mountSidebar(wrappedSpec, onLeft)
        }
        val placeholderSpec = SidebarSpec(
            widthPx = 0,
            header = null,
            content = null,
            visible = true,
            isResizable = spec.isResizable,
            minWidthPx = spec.minWidthPx,
            maxWidthPx = spec.maxWidthPx,
            defaultWidthPx = spec.defaultWidthPx,
            allowGrowBeyondDefault = spec.allowGrowBeyondDefault,
            onResize = { newWidth ->
                if (newWidth > 0) {
                    // Skip the slide-in animation: the user already
                    // performed the visual reveal by dragging, so
                    // playing it again on the rebuild would feel like
                    // a hiccup. setInitial(open=true,…) primes the next
                    // attachToBuiltSidebar to mount at the target width
                    // directly.
                    widthPx = newWidth
                    setInitial(open = true, widthPx = widthPx)
                    // Forward to the host's own onResize so its
                    // persisted "visible" flag flips back in lockstep
                    // with the controller's [isOpen]. Without this, a
                    // reload would respawn the placeholder even though
                    // the user just dragged the sidebar back open.
                    spec.onResize?.invoke(newWidth)
                    requestRebuild()
                }
            },
        )
        val placeholder = if (onLeft) renderLeftSidebar(placeholderSpec)
                          else renderRightSidebar(placeholderSpec)
        placeholder.style.width = "0px"
        placeholder.style.setProperty("min-width", "0")
        // Drag-to-restore affordance: same hairline-on-hover treatment
        // as the bar collapse, but always painted while collapsed so
        // the user has a visual cue.
        placeholder.classList.add("dt-sidebar-collapsed")
        current = placeholder
        return placeholder
    }
}

/** Bundled controller for the AppFrame's left sidebar slot. */
val leftSidebarController: SidebarController = SidebarController(defaultWidthPx = 240)

/** Bundled controller for the AppFrame's right sidebar slot (non-theme-manager use). */
val rightSidebarController: SidebarController = SidebarController(defaultWidthPx = 240)
