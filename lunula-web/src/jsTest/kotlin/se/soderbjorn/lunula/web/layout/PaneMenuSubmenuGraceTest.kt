/**
 * Browser tests for the pane-menu submenu hover-forgiveness behaviour
 * ([SubmenuHost] in PaneMenu.kt): the flyout opened from a
 * [PaneMenuItem.submenu] row must survive the pointer crossing sibling
 * rows on its way into the flyout (grace-delay close, cancelled on
 * flyout entry) while still dismissing once the pointer genuinely
 * settles elsewhere.
 *
 * These run in the karma browser environment (`:lunula-web:jsBrowserTest`)
 * against the real DOM: each test opens a popover via [openPaneMenu],
 * dispatches synthetic `mouseenter` events, and asserts on the presence
 * of the `.dt-pane-menu-submenu` element across timer boundaries.
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaneMenuSubmenuGraceTest {

    /**
     * Opens a popover with one submenu-bearing row ("Move to tab") and one
     * plain sibling row ("Close pane"), returning the pieces each test
     * needs.
     *
     * @return the popover element, the submenu row, the plain row, and the
     *   closer from [openPaneMenu] (tests must invoke it to clean up).
     */
    private fun openTestMenu(): TestMenu {
        val anchor = document.createElement("button") as HTMLElement
        document.body!!.appendChild(anchor)
        val closer = openPaneMenu(
            anchor,
            PaneMenuSpec(
                items = listOf(
                    PaneMenuItem(
                        label = "Move to tab",
                        submenu = listOf(PaneMenuItem(label = "Tab 2")),
                    ),
                    PaneMenuItem(label = "Close pane"),
                ),
            ),
        )
        val menu = document.querySelector(".${PaneMenuClassNames.MENU}") as HTMLElement
        val rows = menu.querySelectorAll("button")
        val submenuRow = rows.item(0) as HTMLElement
        val plainRow = rows.item(1) as HTMLElement
        return TestMenu(menu, submenuRow, plainRow) {
            closer()
            anchor.parentElement?.removeChild(anchor)
        }
    }

    /** Bundle returned by [openTestMenu]; [cleanup] closes and detaches everything. */
    private class TestMenu(
        val menu: HTMLElement,
        val submenuRow: HTMLElement,
        val plainRow: HTMLElement,
        val cleanup: () -> Unit,
    )

    /** The currently mounted flyout element, or `null` when none is open. */
    private fun flyout(): HTMLElement? =
        document.querySelector(".${PaneMenuClassNames.SUBMENU}") as? HTMLElement

    /** Dispatches a synthetic `mouseenter` on [target]. */
    private fun enter(target: HTMLElement) {
        target.dispatchEvent(Event("mouseenter"))
    }

    /** Runs [block] after [ms] milliseconds inside a promise chain. */
    private fun <T> after(ms: Int, block: () -> T): Promise<T> =
        Promise { resolve, _ -> window.setTimeout({ resolve(block()) }, ms) }

    @Test
    fun hovering_submenu_row_opens_flyout_immediately() {
        val m = openTestMenu()
        try {
            assertNull(flyout(), "no flyout before hover")
            enter(m.submenuRow)
            assertNotNull(flyout(), "flyout opens on hover with nothing pending")
        } finally {
            m.cleanup()
        }
    }

    @Test
    fun crossing_plain_sibling_does_not_kill_flyout_immediately(): Promise<Unit> {
        val m = openTestMenu()
        enter(m.submenuRow)
        assertNotNull(flyout())
        // Diagonal travel: pointer crosses the plain sibling row...
        enter(m.plainRow)
        assertNotNull(flyout(), "flyout must survive the sibling crossing (grace delay)")
        // ...and reaches the flyout before the grace timer fires.
        return after(100) { enter(flyout()!!) }
            .then { after(500) { } }.then {
                assertNotNull(flyout(), "entering the flyout cancels the pending dismissal")
                m.cleanup()
            }
    }

    @Test
    fun settling_on_plain_sibling_dismisses_after_grace_delay(): Promise<Unit> {
        val m = openTestMenu()
        enter(m.submenuRow)
        assertNotNull(flyout())
        enter(m.plainRow)
        // Pointer never reaches the flyout: after the grace window the
        // flyout must be gone.
        return after(500) {
            assertNull(flyout(), "flyout dismissed once the grace delay elapses")
            m.cleanup()
        }
    }

    @Test
    fun returning_to_anchor_row_cancels_pending_dismissal(): Promise<Unit> {
        val m = openTestMenu()
        enter(m.submenuRow)
        assertNotNull(flyout())
        enter(m.plainRow)
        // Pointer wanders back onto the anchor row within the grace window.
        return after(100) { enter(m.submenuRow) }
            .then { after(500) { } }.then {
                assertNotNull(flyout(), "re-hovering the anchor row keeps the flyout open")
                m.cleanup()
            }
    }

    @Test
    fun explicit_close_dismisses_flyout_and_cancels_timers(): Promise<Unit> {
        val m = openTestMenu()
        enter(m.submenuRow)
        assertNotNull(flyout())
        enter(m.plainRow) // pending grace-close in flight
        m.cleanup() // popover-level close must be immediate
        assertNull(flyout(), "closing the popover removes the flyout immediately")
        // The cancelled grace timer must not blow up after the fact.
        return after(500) {
            assertTrue(document.querySelector(".${PaneMenuClassNames.MENU}") == null)
        }
    }
}
