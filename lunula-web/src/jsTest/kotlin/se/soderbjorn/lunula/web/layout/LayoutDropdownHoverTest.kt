/**
 * Browser tests for the layout dropdown's hover reveal ([LayoutDropdown]):
 * hovering the topbar trigger must show the preset grid on its own and put
 * it away again once the pointer settles elsewhere — the contract the "+"
 * menu already has (`attachHoverMenu`), which this dropdown was asked to
 * match.
 *
 * They also pin the part that is *not* a copy of the "+" menu: a grid that
 * appeared because a cursor passed over a button must not take the keyboard
 * with it. Only a deliberate open — a click, a command-palette entry — or
 * the pointer actually entering the grid may focus a tile or answer an
 * arrow key.
 *
 * These run in the karma browser environment (`:lunula-web:jsBrowserTest`)
 * against the real DOM: each test mounts a trigger, dispatches synthetic
 * pointer events, and asserts on `.dt-layout-preset-grid` across the
 * show/hide timer boundaries.
 */
package se.soderbjorn.lunula.web.layout

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutDropdownHoverTest {

    /** Comfortably past both the 120 ms show and 180 ms hide delays. */
    private val pastDelays = 400

    /**
     * Mounts a dropdown's trigger in the document.
     *
     * @param onSelect recorded by tests that assert on picking.
     * @return the dropdown, its mounted trigger, and a cleanup that closes
     *   the popover and detaches the trigger.
     */
    private fun mount(onSelect: (LayoutPreset) -> Unit = {}): Mounted {
        val dropdown = LayoutDropdown(paneCount = { 3 }, onSelect = onSelect)
        val trigger = dropdown.triggerButton
        document.body!!.appendChild(trigger)
        return Mounted(dropdown, trigger) {
            dropdown.close()
            trigger.parentElement?.removeChild(trigger)
            disarmHoverSuppression()
        }
    }

    /**
     * Walks the cursor far away from any button, which is what clears the
     * click-suppression rect the toolkit keeps at module scope.
     *
     * Without this a test that clicks a trigger leaves every later test's
     * hover suppressed — the state outlives the dropdown instance by
     * design, since its whole job is surviving the topbar being rebuilt.
     */
    private fun disarmHoverSuppression() {
        document.dispatchEvent(
            MouseEvent("mousemove", MouseEventInit(clientX = 9999, clientY = 9999)),
        )
    }

    /** Bundle returned by [mount]; [cleanup] tears everything down. */
    private class Mounted(
        val dropdown: LayoutDropdown,
        val trigger: HTMLElement,
        val cleanup: () -> Unit,
    )

    /** The mounted preset grid, or `null` when none is open. */
    private fun grid(): HTMLElement? =
        document.querySelector(".dt-layout-preset-grid") as? HTMLElement

    /** The grid's preset tiles, or empty when no grid is up. */
    private fun tiles(): List<HTMLElement> {
        val g = grid() ?: return emptyList()
        val found = g.querySelectorAll(".dt-layout-preset-tile")
        return (0 until found.length).map { found.item(it) as HTMLElement }
    }

    /** Dispatches a synthetic [name] mouse-ish event on [target]. */
    private fun fire(target: HTMLElement, name: String) {
        target.dispatchEvent(Event(name))
    }

    /** Dispatches a synthetic [key] keydown on the document, capture-visible. */
    private fun press(key: String) {
        document.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key)))
    }

    /** Runs [block] after [ms] milliseconds inside a promise chain. */
    private fun <T> after(ms: Int, block: () -> T): Promise<T> =
        Promise { resolve, _ -> window.setTimeout({ resolve(block()) }, ms) }

    @Test
    fun hovering_the_trigger_reveals_the_grid(): Promise<Unit> {
        val m = mount()
        assertNull(grid(), "nothing before the hover")
        fire(m.trigger, "mouseenter")
        assertNull(grid(), "not instantly — the show delay must elapse first")
        return after(pastDelays) {
            assertNotNull(grid(), "hovering the trigger opens the grid on its own")
            m.cleanup()
        }
    }

    @Test
    fun leaving_the_trigger_before_the_delay_reveals_nothing(): Promise<Unit> {
        val m = mount()
        fire(m.trigger, "mouseenter")
        fire(m.trigger, "mouseleave") // cursor merely passing through
        return after(pastDelays) {
            assertNull(grid(), "a cursor crossing the button opens nothing")
            m.cleanup()
        }
    }

    @Test
    fun leaving_the_trigger_puts_the_grid_away(): Promise<Unit> {
        val m = mount()
        fire(m.trigger, "mouseenter")
        return after(pastDelays) {
            assertNotNull(grid())
            fire(m.trigger, "mouseleave")
        }.then { after(pastDelays) { } }.then {
            assertNull(grid(), "the grid closes once the pointer is no longer there")
            m.cleanup()
        }
    }

    @Test
    fun entering_the_grid_cancels_the_pending_close(): Promise<Unit> {
        val m = mount()
        fire(m.trigger, "mouseenter")
        return after(pastDelays) {
            assertNotNull(grid())
            // The diagonal: off the button, across the gap, into the grid.
            fire(m.trigger, "mouseleave")
            fire(grid()!!, "mouseenter")
        }.then { after(pastDelays) { } }.then {
            assertNotNull(grid(), "landing in the grid keeps it open")
            m.cleanup()
        }
    }

    @Test
    fun leaving_the_grid_closes_it(): Promise<Unit> {
        val m = mount()
        fire(m.trigger, "mouseenter")
        return after(pastDelays) {
            fire(m.trigger, "mouseleave")
            fire(grid()!!, "mouseenter")
            fire(grid()!!, "mouseleave")
        }.then { after(pastDelays) { } }.then {
            assertNull(grid(), "the grid closes when the pointer leaves it too")
            m.cleanup()
        }
    }

    @Test
    fun hover_reveal_takes_neither_focus_nor_arrow_keys(): Promise<Unit> {
        val m = mount()
        fire(m.trigger, "mouseenter")
        return after(pastDelays) {
            assertNotNull(grid(), "revealed by hover")
            assertTrue(
                tiles().none { it.classList.contains("is-focused") },
                "no focus ring on a grid nobody asked for",
            )
            assertTrue(
                tiles().none { it == document.activeElement },
                "a hover must not pull focus out of what the user was typing into",
            )
            press("ArrowRight")
            assertTrue(
                tiles().none { it.classList.contains("is-focused") },
                "arrow keys still belong to whatever holds focus",
            )
            assertTrue(
                tiles().none { it == document.activeElement },
                "and pressing them changed nothing about focus",
            )
        }.then { m.cleanup() }
    }

    @Test
    fun escape_still_dismisses_a_hover_revealed_grid(): Promise<Unit> {
        val m = mount()
        fire(m.trigger, "mouseenter")
        return after(pastDelays) {
            assertNotNull(grid())
            press("Escape")
            assertNull(grid(), "Escape dismisses what hover revealed")
            m.cleanup()
        }
    }

    @Test
    fun pointing_at_a_tile_hands_the_grid_the_keyboard(): Promise<Unit> {
        var picked: LayoutPreset? = null
        val m = mount(onSelect = { picked = it })
        fire(m.trigger, "mouseenter")
        return after(pastDelays) {
            val first = tiles().first()
            fire(first, "mousemove")
            assertTrue(
                first.classList.contains("is-focused"),
                "the pointer entering the grid promotes it to a real menu",
            )
            press("Enter")
            assertNull(grid(), "Enter commits and closes")
            assertNotNull(picked, "…and fires the pick it was pointed at")
            m.cleanup()
        }
    }

    @Test
    fun clicking_the_trigger_open_closes_it_and_hover_does_not_undo_that(): Promise<Unit> {
        val m = mount()
        fire(m.trigger, "mouseenter")
        return after(pastDelays) {
            assertNotNull(grid(), "hover opened it")
            m.trigger.click()
            assertNull(grid(), "clicking the trigger dismisses it")
            // The cursor never moved, and a topbar rebuild would re-fire
            // `mouseenter` on a fresh trigger sitting in the same place.
            fire(m.trigger, "mouseenter")
        }.then { after(pastDelays) { } }.then {
            assertNull(grid(), "a dismissal survives the cursor parked on the button")
            m.cleanup()
        }
    }

    @Test
    fun a_clicked_open_survives_the_pointer_wandering_off(): Promise<Unit> {
        val m = mount()
        m.trigger.click()
        assertNotNull(grid(), "clicked open")
        // The user asked for this one; drifting off it is not a dismissal.
        fire(m.trigger, "mouseleave")
        return after(pastDelays) {
            assertNotNull(
                grid(),
                "only what hover revealed may take itself away again",
            )
            m.cleanup()
        }
    }

    @Test
    fun a_clicked_open_still_focuses_the_first_tile() {
        val m = mount()
        try {
            m.trigger.click()
            val first = tiles().first()
            assertTrue(
                first.classList.contains("is-focused"),
                "an open the user asked for is keyboard-ready as before",
            )
            assertFalse(
                tiles().drop(1).any { it.classList.contains("is-focused") },
                "and exactly one tile wears the ring",
            )
        } finally {
            m.cleanup()
        }
    }
}
