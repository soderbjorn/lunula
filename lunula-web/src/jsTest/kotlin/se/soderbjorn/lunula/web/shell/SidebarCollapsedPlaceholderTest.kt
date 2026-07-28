/*
 * SidebarCollapsedPlaceholderTest.kt (jsTest)
 * -------------------------------------------
 * DOM-level regression tests for the two shapes of the 0-width sidebar
 * placeholder that [SidebarController.mountSidebarOrPlaceholder] mounts.
 *
 * The distinction they guard is a user-visible one. A sidebar the user
 * DRAGGED out of existence keeps a resize handle in the DOM — a grabbable
 * strip whose hairline is painted at all times, because the gesture that
 * made the bar vanish otherwise has no inverse. A sidebar the user CLOSED
 * WITH A TOGGLE keeps nothing: the toggle is still sitting in the chrome,
 * so the strip would buy a second route back at the price of a hairline
 * ruled 30px down the content area (the offset the collapsed handle needs
 * to clear the OS window resize gutter) plus a `col-resize` hit-target
 * lying over the first 44px of the user's page.
 *
 * That hairline over a toggled-away sidebar is what was reported as
 * "when sidebar is hidden, a thin line obstructs the view".
 *
 * @see se.soderbjorn.lunula.web.shell.SidebarController
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunula.web.injectLunulaStyles
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A resizable left-sidebar spec in the shape a host passes to
 * [SidebarController.mountSidebarOrPlaceholder]. Fresh per call so no two
 * mounts share a content element.
 */
private fun leftSidebarSpec(): SidebarSpec = SidebarSpec(
    widthPx = 240,
    content = document.createElement("div") as HTMLElement,
    visible = true,
    isResizable = true,
    minWidthPx = 0,
    maxWidthPx = 600,
)

/** The collapsed placeholder's grabbable strip, or null when it has none. */
private fun HTMLElement.resizeHandle(): HTMLElement? =
    querySelector(".dt-sidebar-resize-handle") as? HTMLElement

/**
 * Builds a bubbling mouse event carrying [clientX].
 *
 * Hand-rolled from a JS object literal rather than the stdlib's
 * `MouseEventInit(...)` builder: that builder's optional fields do not
 * survive into the constructed event here, so the drag arrived at every
 * listener with `clientX == 0` and moved the bar nowhere.
 */
private fun mouseEventAt(type: String, clientX: Int): MouseEvent {
    val init: dynamic = js("({})")
    init.bubbles = true
    init.cancelable = true
    init.clientX = clientX
    return MouseEvent(type, init.unsafeCast<MouseEventInit>())
}

/**
 * Drives a full press-drag-release of [handle] from [fromX] to [toX] in
 * client coordinates, exactly as `attachSidebarResizeHandle` listens for
 * it: mousedown on the handle itself, mousemove and mouseup on `document`
 * (the gesture keeps tracking past the bar's edge).
 */
private fun dragHandle(handle: HTMLElement, fromX: Int, toX: Int) {
    handle.dispatchEvent(mouseEventAt("mousedown", fromX))
    document.dispatchEvent(mouseEventAt("mousemove", toX))
    // Flush layout while the bar still has `transition: none` from the
    // mousedown. A real drag gets this for free — mousemove and mouseup
    // land in different frames — but dispatched back-to-back in one task
    // the browser never takes a style snapshot at the dragged width, so
    // mouseup's restoration of `transition: width 200ms` would turn the
    // already-applied width into a running animation and the handler would
    // measure the bar at its pre-drag width.
    handle.parentElement?.getBoundingClientRect()
    document.dispatchEvent(mouseEventAt("mouseup", toX))
}

class SidebarCollapsedPlaceholderTest {

    /**
     * The reported bug: a sidebar that was never dragged anywhere — it was
     * simply closed, and the app booted with it closed — must not leave a
     * resize handle behind, because the handle's collapsed CSS paints its
     * hairline unconditionally and 30px inside the content area.
     */
    @Test
    fun aSidebarThatWasNotDraggedAwayLeavesNoStripOverTheContent() {
        val controller = SidebarController(defaultWidthPx = 240)
        controller.setInitial(open = false, widthPx = 240)

        val placeholder = controller.mountSidebarOrPlaceholder(
            spec = leftSidebarSpec(),
            onLeft = true,
            requestRebuild = { },
        )

        assertTrue(
            placeholder.classList.contains("dt-sidebar-collapsed"),
            "the 0-width placeholder must still be marked collapsed so the slot's " +
                "edge hairlines are blanked",
        )
        assertNull(
            placeholder.resizeHandle(),
            "a sidebar closed without a drag must leave no resize strip — its " +
                "always-painted hairline is the thin line reported over the content",
        )
    }

    /**
     * The same, reached the way a user reaches it: the sidebar is open and
     * they press the toggle. [SidebarController.toggle] must clear the
     * drag flag so the rebuild mounts the strip-less placeholder.
     */
    @Test
    fun togglingAnOpenSidebarClosedLeavesNoStripOverTheContent() {
        val controller = SidebarController(defaultWidthPx = 240)
        // No element has been attached, so toggle takes its "nothing to
        // animate" close branch — the same state flip, minus the transition.
        controller.setInitial(open = true, widthPx = 240)

        var rebuilds = 0
        controller.toggle { rebuilds++ }

        assertFalse(controller.isOpen, "the toggle should have closed the sidebar")
        assertTrue(rebuilds == 1, "the close should have asked the host to rebuild once")
        assertFalse(
            controller.isCollapsedByDrag,
            "a toggle close is not a drag collapse",
        )
        assertNull(
            controller.mountSidebarOrPlaceholder(
                spec = leftSidebarSpec(),
                onLeft = true,
                requestRebuild = { },
            ).resizeHandle(),
            "a toggled-away sidebar must not leave a grabbable strip behind",
        )
    }

    /**
     * The other half of the contract, and the reason the handle is not
     * simply deleted: a sidebar the user dragged to nothing keeps its
     * strip, because dragging is otherwise a one-way door.
     *
     * Uses a host that opts into snap-to-collapse (`collapseSnapPx`), which
     * is how a real drag reaches width 0 — the bar's 1px edge border means
     * the raw released width bottoms out just above zero.
     */
    @Test
    fun aSidebarDraggedToNothingKeepsItsGrabbableStrip() {
        injectLunulaStyles()
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            val controller = SidebarController(defaultWidthPx = 240, collapseSnapPx = 60)
            controller.setInitial(open = true, widthPx = 240)

            var mounted: HTMLElement? = null
            fun mount() {
                host.innerHTML = ""
                val el = controller.mountSidebarOrPlaceholder(
                    spec = leftSidebarSpec(),
                    onLeft = true,
                    requestRebuild = { mount() },
                )
                host.appendChild(el)
                mounted = el
            }
            mount()

            val openBar = assertNotNull(mounted, "the open sidebar should have mounted")
            val handle = assertNotNull(
                openBar.resizeHandle(),
                "an open resizable sidebar carries its in-bar resize handle",
            )
            val right = openBar.getBoundingClientRect().right.toInt()
            dragHandle(handle, fromX = right, toX = right - 400)

            assertTrue(
                controller.isCollapsedByDrag,
                "releasing the handle at the window edge is a drag collapse",
            )
            assertFalse(controller.isOpen, "a drag collapse closes the sidebar")
            val placeholder = assertNotNull(mounted, "the rebuild should have mounted a placeholder")
            assertTrue(
                placeholder.classList.contains("dt-sidebar-collapsed"),
                "the drag-collapsed placeholder is marked collapsed",
            )
            assertNotNull(
                placeholder.resizeHandle(),
                "a drag-collapsed sidebar keeps a grabbable strip — it is the only " +
                    "way back that matches the gesture the user just made",
            )
        } finally {
            host.remove()
        }
    }
}
