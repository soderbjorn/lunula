/*
 * FixedTabStripTest.kt (jsTest)
 *
 * Tests for the app-defined ("fixed") tab strip — [TabBarSpec.isFixed] —
 * and for the declarative tab badges that came in with it
 * ([TabBadge], [formatTabBadgeCount]).
 *
 * Two things are being pinned down here, and the second is the one that
 * really needs a test.
 *
 * The first is that a fixed strip renders no way to edit the tab set. The
 * test wires every editing callback the bar accepts and then asserts that
 * none of the resulting affordances reach the DOM — because the whole
 * point of the flag is that it wins over the callbacks rather than merely
 * describing them. Asserting it against a spec with nothing wired would
 * have proved only that nothing renders from nothing.
 *
 * The second is the constraint the ticket cared about most: existing
 * consumers are untouched. Every assertion about the fixed strip has a
 * mirror-image assertion on a default strip built from the same tabs and
 * the same callbacks, so a change that quietly disarmed the editable bar
 * fails here rather than in a downstream app.
 *
 * These run in the karma browser environment (`:lunula-web:jsBrowserTest`)
 * against the real DOM. `lunula.css` is not loaded in the test page, so
 * everything asserted is structural (elements, classes, attributes) rather
 * than visual.
 *
 * @see TabBarSpec.isFixed
 * @see TabSource.fixed
 * @see TabBadge
 */
package se.soderbjorn.lunula.web.shell

import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedTabStripTest {

    // ── The fixed strip renders no editing affordance ───────────────

    /**
     * The `+` button is withheld even though the host asked for it and
     * wired `onAdd` — a fixed set does not grow.
     */
    @Test
    fun fixedStripRendersNoAddButton() {
        assertNull(render(isFixed = true).querySelector(".${TabBarClassNames.TAB_ADD}"))
        // …and the editable strip still has one, from the same inputs.
        assertNotNull(render(isFixed = false).querySelector(".${TabBarClassNames.TAB_ADD}"))
    }

    /**
     * No per-tab `⋮` dot menu, which is where Rename / Close / Hide all
     * live since issue #65 — so this one assertion covers three gestures.
     */
    @Test
    fun fixedStripRendersNoPerTabDotMenu() {
        assertEquals(0, render(isFixed = true).querySelectorAll(".dt-tab-menu").length)
        assertTrue(render(isFixed = false).querySelectorAll(".dt-tab-menu").length > 0)
    }

    /** No drag handles: the app's order is the order. */
    @Test
    fun fixedStripTabsAreNotDraggable() {
        assertNull(firstTab(render(isFixed = true)).getAttribute("draggable"))
        assertEquals("true", firstTab(render(isFixed = false)).getAttribute("draggable"))
    }

    /**
     * No far-right overflow list. It exists to re-surface hidden tabs, and
     * a fixed strip has no way to hide one, so it could only ever be empty.
     */
    @Test
    fun fixedStripRendersNoOverflowMenu() {
        assertEquals(0, render(isFixed = true).querySelectorAll(".dt-tabbar-menu").length)
    }

    /**
     * Selection survives. A fixed strip constrains what the tab set *is*,
     * not which member of it the user is looking at, so clicking a tab
     * must still report through `onSelect`.
     */
    @Test
    fun fixedStripStillReportsSelection() {
        var selected: String? = null
        val bar = renderTabBar(
            TabBarSpec(
                tabs = listOf(TabSpec("issues", "Issues"), TabSpec("discussion", "Discussion")),
                activeTabId = "issues",
                isFixed = true,
                callbacks = TabBarCallbacks(onSelect = { selected = it }),
            ),
        )
        val second = bar.querySelectorAll(".${TabBarClassNames.TAB}").item(1) as HTMLElement
        second.asDynamic().click()
        assertEquals("discussion", selected)
    }

    /** The bar carries the marker class the fixed-strip CSS keys off. */
    @Test
    fun fixedStripIsMarkedForStyling() {
        assertTrue(render(isFixed = true).classList.contains(TabBarClassNames.BAR_FIXED))
        assertTrue(!render(isFixed = false).classList.contains(TabBarClassNames.BAR_FIXED))
    }

    // ── TabSource.fixed ─────────────────────────────────────────────

    /**
     * The factory is the declaration a consuming app writes, so it must
     * set the flag AND leave every tab-mutation callback null — a host
     * should not be able to declare a fixed set and still hand over an
     * `onClose` that nothing will ever call.
     */
    @Test
    fun tabSourceFixedFactoryDeclaresAnUneditableSet() {
        val src = TabSource.fixed(subscribe = {}, onSelect = {})
        assertTrue(src.isFixed)
        assertNull(src.onAdd)
        assertNull(src.onClose)
        assertNull(src.onRename)
        assertNull(src.onReorder)
        assertNull(src.onSetHidden)
        assertNull(src.onSetHiddenFromSidebar)
    }

    /** An ordinary [TabSource] is unchanged: user-editable by default. */
    @Test
    fun anOrdinaryTabSourceIsNotFixed() {
        assertTrue(!TabSource(subscribe = {}, onSelect = {}).isFixed)
    }

    // ── Declarative badges ──────────────────────────────────────────

    /** Small counts render literally. */
    @Test
    fun countBadgeRendersTheNumber() {
        assertEquals("1", formatTabBadgeCount(1))
        assertEquals("42", formatTabBadgeCount(42))
    }

    /**
     * The cap is inclusive: 99 is still a number, 100 is "99+". This is
     * the off-by-one the formatter exists to pin down.
     */
    @Test
    fun countBadgeCapsAtNinetyNinePlus() {
        assertEquals("99", formatTabBadgeCount(99))
        assertEquals("99+", formatTabBadgeCount(100))
        assertEquals("99+", formatTabBadgeCount(12_000))
    }

    /** A custom cap is honoured, so the rule isn't hardcoded to 99. */
    @Test
    fun countBadgeHonoursACustomCap() {
        assertEquals("9", formatTabBadgeCount(9, cap = 9))
        assertEquals("9+", formatTabBadgeCount(10, cap = 9))
    }

    /**
     * Zero (and anything below it) is no badge at all, so hosts can push
     * their live count unconditionally instead of branching on emptiness.
     */
    @Test
    fun emptyCountRendersNoBadge() {
        assertNull(formatTabBadgeCount(0))
        assertNull(formatTabBadgeCount(-3))
        assertNull(buildTabBadgeElement(TabBadge.Count(0)))
    }

    /** A count badge reaches the DOM in the tab's trailing slot. */
    @Test
    fun countBadgeIsRenderedIntoTheTab() {
        val tab = firstTab(renderBadged(TabBadge.Count(100)))
        val badge = tab.querySelector(".${TabBarClassNames.TAB_BADGE_COUNT}") as? HTMLElement
        assertNotNull(badge)
        assertEquals("99+", badge.textContent)
        // The drawn text is capped; the accessible label is not, so
        // assistive tech isn't told there are exactly 99 of something.
        assertEquals("100 unread", badge.getAttribute("aria-label"))
    }

    /** A dot badge renders with no text — that is the whole point of it. */
    @Test
    fun dotBadgeIsRenderedWithoutANumber() {
        val tab = firstTab(renderBadged(TabBadge.Dot))
        val badge = tab.querySelector(".${TabBarClassNames.TAB_BADGE_DOT}") as? HTMLElement
        assertNotNull(badge)
        assertEquals("", badge.textContent)
    }

    /**
     * A host-built [TabSpec.trailingBadge] beats the declarative one. The
     * slot holds one thing; an explicit element is the more specific
     * instruction, and rendering both would read as a bug.
     */
    @Test
    fun anExplicitTrailingBadgeWinsOverTheDeclarativeOne() {
        val custom = kotlinx.browser.document.createElement("span") as HTMLElement
        custom.className = "host-badge"
        val bar = renderTabBar(
            TabBarSpec(
                tabs = listOf(TabSpec("messages", "Messages", trailingBadge = custom, badge = TabBadge.Dot)),
                activeTabId = "messages",
                isFixed = true,
            ),
        )
        assertNotNull(bar.querySelector(".host-badge"))
        assertNull(bar.querySelector(".${TabBarClassNames.TAB_BADGE}"))
    }

    /** No badge asked for, no badge slot — the default is unchanged. */
    @Test
    fun tabsWithoutABadgeRenderNoBadgeSlot() {
        assertNull(render(isFixed = false).querySelector(".${TabBarClassNames.TAB_TRAILING_BADGE}"))
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Renders a two-tab strip with **every** editing callback wired, so
     * the fixed-strip assertions are about the flag rather than about
     * missing inputs.
     *
     * @param isFixed value for [TabBarSpec.isFixed].
     * @return the rendered bar element (not attached to the document —
     *   nothing here measures layout).
     */
    private fun render(isFixed: Boolean): HTMLElement = renderTabBar(
        TabBarSpec(
            tabs = listOf(
                TabSpec("issues", "Issues", isDraggable = true, isRenamable = true, isClosable = true),
                TabSpec("discussion", "Discussion", isDraggable = true, isRenamable = true, isClosable = true),
            ),
            activeTabId = "issues",
            showAddButton = true,
            showOverflowMenu = true,
            isFixed = isFixed,
            callbacks = TabBarCallbacks(
                onSelect = {},
                onClose = {},
                onAdd = {},
                onReorder = { _, _, _ -> },
                onRename = { _, _ -> },
                onSetHidden = { _, _ -> },
                onSetHiddenFromSidebar = { _, _ -> },
            ),
        ),
    )

    /** Renders a single fixed tab carrying [badge]. */
    private fun renderBadged(badge: TabBadge): HTMLElement = renderTabBar(
        TabBarSpec(
            tabs = listOf(TabSpec("messages", "Messages", badge = badge)),
            activeTabId = "messages",
            isFixed = true,
        ),
    )

    /** The first `.dt-tab` in [bar]. */
    private fun firstTab(bar: HTMLElement): HTMLElement =
        bar.querySelector(".${TabBarClassNames.TAB}") as HTMLElement
}
