/*
 * HoverMenuLeavesHostMenusAloneTest.kt (jsTest)
 *
 * Browser tests for who the topbar hover menu is allowed to take down when it
 * opens (LNL-208).
 *
 * `.dt-hover-menu` is the toolkit's menu *surface* — a paint, published for
 * consuming apps to wear on menus the toolkit knows nothing about. Lunicle's
 * account corner is the case that named this bug: it builds one panel at mount,
 * leaves it in the document for the life of the page, and opens it in pure CSS
 * on `:hover`. [attachHoverMenu] used to enforce "only one menu open at a time"
 * by removing every element carrying the class, which deleted that panel on the
 * first hover over the topbar "+" and left the corner permanently dead — no
 * error, no console line, just a menu that stopped existing.
 *
 * So there are two claims here, and they pull against each other:
 *
 *   1. a menu the toolkit did not open survives an open, and
 *   2. a menu the toolkit *did* open still goes away when the next one opens.
 *
 * Timing is real — the menu is revealed by a live timer — so these return
 * Promises and wait past [HOVER_MENU_SHOW_DELAY_MS] rather than pretending the
 * open is synchronous.
 *
 * @see attachHoverMenu
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Comfortably past the reveal delay, so a wait has seen the open rather than raced it. */
private const val PAST_SHOW_MS = HOVER_MENU_SHOW_DELAY_MS + 120

/** One row, enough for the menu to have something to render. */
private fun oneItem(): List<HoverMenuItem> =
    listOf(HoverMenuItem(id = "row", label = "New tab", iconHtml = "") {})

class HoverMenuLeavesHostMenusAloneTest {

    private val built = mutableListOf<HTMLElement>()

    @AfterTest
    fun tearDown() {
        built.forEach { it.remove() }
        built.clear()
        // Whatever the last test opened lives directly under <body>; the tests
        // below assert on counts, so a leftover panel would fail the next one.
        val panels = document.querySelectorAll(".dt-hover-menu")
        for (i in 0 until panels.length) (panels.item(i) as HTMLElement).remove()
    }

    /** A hover-menu anchor, in the document and ready to be entered. */
    private fun anchor(): HTMLElement {
        val btn = document.createElement("button") as HTMLElement
        document.body?.appendChild(btn)
        built += btn
        attachHoverMenu(btn) { oneItem() }
        return btn
    }

    /**
     * A menu belonging to the consuming app: the surface class, mounted once
     * and never taken down. Lunicle's account corner, in miniature.
     */
    private fun hostOwnedMenu(): HTMLElement {
        val panel = document.createElement("div") as HTMLElement
        panel.className = "dt-hover-menu host-owned"
        document.body?.appendChild(panel)
        built += panel
        return panel
    }

    private fun HTMLElement.enter() {
        dispatchEvent(MouseEvent("mouseenter", MouseEventInit(bubbles = false, cancelable = false)))
    }

    private fun <T> after(ms: Int, block: () -> T): Promise<T> =
        Promise { resolve, _ -> window.setTimeout({ resolve(block()) }, ms) }

    /** Panels the toolkit itself raised — everything but the host's. */
    private fun toolkitPanels(): List<HTMLElement> =
        (0 until document.querySelectorAll(".dt-hover-menu").length)
            .map { document.querySelectorAll(".dt-hover-menu").item(it) as HTMLElement }
            .filterNot { it.classList.contains("host-owned") }

    /**
     * The bug: opening the topbar dropdown must not evict a menu the app built
     * for itself.
     */
    @Test
    fun aHostOwnedMenuSurvivesTheDropdownOpening(): Promise<Unit> {
        val hostMenu = hostOwnedMenu()
        val btn = anchor()
        btn.enter()
        return after(PAST_SHOW_MS) {
            assertEquals(1, toolkitPanels().size, "the dropdown did not open")
            assertNotNull(
                hostMenu.parentElement,
                "the dropdown removed a menu it does not own — the app's own menu is gone from the document",
            )
            assertTrue(
                document.body?.contains(hostMenu) == true,
                "the host's menu is no longer in the document",
            )
        }
    }

    /**
     * And the rule the eviction was there for, which must survive the fix: a
     * second anchor's menu replaces the first, rather than stacking on it.
     */
    @Test
    fun aSecondDropdownReplacesTheFirst(): Promise<Unit> {
        val first = anchor()
        val second = anchor()
        first.enter()
        return after(PAST_SHOW_MS) {
            assertEquals(1, toolkitPanels().size, "the first dropdown did not open")
            second.enter()
        }.then {
            after(PAST_SHOW_MS) {
                assertEquals(
                    1,
                    toolkitPanels().size,
                    "opening a second dropdown left the first one on screen",
                )
            }
        }.then { }
    }
}
