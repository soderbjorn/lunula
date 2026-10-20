/* CleanSidebarStyleTest.kt
 * Browser tests for the sidebar tree under the Clean shell style (epic
 * LNA-16, LNA-21): the "Sidebar tree" sub-section at the end of the
 * `:root[data-dt-style="clean"]` block in `lunula.css`.
 *
 * A real left sidebar is rendered with the toolkit's own factories
 * ([renderLeftSidebar], [renderSidebarSection], [SidebarRow]) inside a
 * `.dt-app-frame`, with a palette whose every colour is distinct written onto
 * :root, so a computed colour names exactly which token painted it. The tree
 * holds an active tab (an active and an idle window row) and an inactive tab
 * (one row), plus a footer.
 *
 * Two promises are checked, under every selection language (Tint, Fill) and
 * every surface style (Depth, Flat):
 *
 *  - Classic is untouched: the sidebar's computed styles are the same before
 *    and after a round trip through Clean.
 *  - Clean paints the tree as specified: sentence-case headers (active tab in
 *    text at 650, others dim at 500), 30px rows indented 16px, rows under the
 *    active tab in text and under other tabs in the Clean dim, the active row
 *    as the one filled thing (solid accent-soft, contrast-safe accent text at
 *    600, icon in the same colour, no ring or sheen), and a hairline footer.
 *
 * @see se.soderbjorn.lunula.web.applyShellStyle
 * @see CleanShellStyleTest
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.web.applyCssVars
import se.soderbjorn.lunula.web.applySelectionStyle
import se.soderbjorn.lunula.web.applyShellStyle
import se.soderbjorn.lunula.web.applySurfaceStyle
import se.soderbjorn.lunula.web.clearCssVars
import se.soderbjorn.lunula.web.injectLunulaStyles
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A palette whose every colour is distinct, written as `rgb()` because that
 * is how `getComputedStyle` reports colours back. The chrome tokens are set
 * too, so a Classic snapshot differs from Clean wherever Clean remaps them.
 */
private val SIDEBAR_PALETTE = mapOf(
    "--t-bg" to "rgb(10, 11, 12)",
    "--t-chrome-bg" to "rgb(16, 17, 18)",
    "--t-chrome-text" to "rgb(40, 41, 42)",
    "--t-chrome-text-dim" to "rgb(50, 51, 52)",
    "--t-chrome-accent" to "rgb(60, 61, 62)",
    "--t-chrome-accent-soft" to "rgb(64, 65, 66)",
    "--t-chrome-border" to "rgb(68, 69, 70)",
    "--t-surface" to "rgb(200, 201, 202)",
    "--t-border" to "rgb(100, 101, 102)",
    "--t-text" to "rgb(20, 21, 22)",
    "--t-text-dim" to "rgb(90, 91, 92)",
    "--t-accent" to "rgb(30, 60, 190)",
    "--t-accent-soft" to "rgb(220, 225, 240)",
    "--t-accent-text" to "rgb(25, 50, 150)",
    "--t-clean-text-dim" to "rgb(70, 71, 72)",
    "--t-clean-accent-text" to "rgb(24, 48, 140)",
    "--t-accent-soft-solid" to "rgb(221, 226, 241)",
)

/** A 14px-box stroke icon, the shape hosts pass as a row's `iconHtml`. */
private const val ICON_SVG =
    """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16"/></svg>"""

class CleanSidebarStyleTest {

    private val root: HTMLElement get() = document.documentElement as HTMLElement
    private lateinit var frame: HTMLElement

    @BeforeTest
    fun mount() {
        injectLunulaStyles()
        applyCssVars(root, SIDEBAR_PALETTE)
        applyShellStyle(root, null)
        frame = (document.createElement("div") as HTMLElement).also {
            it.className = "dt-app-frame"
            it.style.width = "900px"
            it.style.height = "600px"
            document.body!!.appendChild(it)
        }
        renderTree()
    }

    @AfterTest
    fun unmount() {
        frame.parentElement?.removeChild(frame)
        applyShellStyle(root, null)
        applySelectionStyle(root, SelectionStyle.Default)
        applySurfaceStyle(null)
        clearCssVars(root, SIDEBAR_PALETTE)
    }

    /**
     * Renders a left sidebar into [frame]: an active tab with an active row
     * ("Editor") and an idle row ("Shell"), an inactive tab with one row
     * ("Notes"), and a plain-text footer. The active tab is marked the way
     * `AppShellMount.buildLeftSidebarContent` marks it, with `active-tab`.
     */
    private fun renderTree() {
        val content = document.createElement("div") as HTMLElement
        val activeTab = renderSidebarSection(
            SidebarSectionSpec(
                title = "Work tab",
                isOpen = true,
                items = listOf(
                    SidebarRow(label = "Editor", iconHtml = ICON_SVG, isActive = true),
                    SidebarRow(label = "Shell", iconHtml = ICON_SVG),
                ),
            )
        )
        activeTab.classList.add("active-tab")
        activeTab.id = "tab-active"
        val otherTab = renderSidebarSection(
            SidebarSectionSpec(
                title = "Other tab",
                isOpen = true,
                items = listOf(SidebarRow(label = "Notes", iconHtml = ICON_SVG)),
            )
        )
        otherTab.id = "tab-other"
        content.appendChild(activeTab)
        content.appendChild(otherTab)
        val footer = (document.createElement("div") as HTMLElement).also { it.textContent = "1 working" }
        frame.appendChild(renderLeftSidebar(SidebarSpec(widthPx = 260, content = content, footer = footer)))
        // Transitions would report mid-flight values after a style flip.
        for (i in 0 until frame.querySelectorAll("*").length) {
            (frame.querySelectorAll("*").item(i) as? HTMLElement)?.style?.transition = "none"
        }
    }

    private fun el(selector: String): HTMLElement = assertNotNull(
        frame.querySelector(selector) as? HTMLElement,
        "nothing matches $selector",
    )

    private fun style(selector: String) = window.getComputedStyle(el(selector))

    /** Sidebar elements whose computed styles Clean changes. */
    private val parts = mapOf(
        "sidebar" to ".dt-sidebar",
        "activeHeader" to "#tab-active .dt-sidebar-section-header",
        "otherHeader" to "#tab-other .dt-sidebar-section-header",
        "chevron" to "#tab-active .dt-sidebar-section-chevron",
        "activeRow" to "#tab-active .dt-sidebar-row.dt-active",
        "idleRow" to "#tab-active .dt-sidebar-row:not(.dt-active)",
        "otherRow" to "#tab-other .dt-sidebar-row",
        "activeIcon" to "#tab-active .dt-sidebar-row.dt-active .dt-sidebar-row-icon",
        "idleIcon" to "#tab-other .dt-sidebar-row-icon",
        "section" to "#tab-active",
        "footer" to ".dt-sidebar-footer",
    )

    private fun snapshot(): Map<String, String> {
        val props = listOf(
            "background-color", "background-image", "box-shadow", "color", "font-size",
            "font-weight", "text-transform", "letter-spacing", "height", "margin-left",
            "padding-left", "border-top-width", "border-bottom-color", "border-radius",
        )
        val out = linkedMapOf<String, String>()
        for ((name, sel) in parts) {
            val s = style(sel)
            for (p in props) out["$name $p"] = s.getPropertyValue(p)
        }
        return out
    }

    /** Runs [check] once for every selection language and surface style. */
    private fun forEveryCombination(check: (String) -> Unit) {
        for (surface in SurfaceStyle.entries) {
            applySurfaceStyle(surface)
            for (selection in SelectionStyle.entries) {
                applySelectionStyle(root, selection)
                check("$selection/$surface")
            }
        }
    }

    @Test
    fun classicSidebarIsUnchanged() {
        forEveryCombination { combo ->
            applyShellStyle(root, null)
            val before = snapshot()
            applyShellStyle(root, ShellStyle.Classic)
            assertEquals(before, snapshot(), "Classic changed the sidebar under $combo")
            applyShellStyle(root, ShellStyle.Clean)
            assertTrue(before != snapshot(), "Clean changed nothing under $combo, so this test proves nothing")
            applyShellStyle(root, ShellStyle.Classic)
            assertEquals(before, snapshot(), "a round trip through Clean left a change under $combo")
        }
    }

    @Test
    fun cleanActiveRowIsTheOneFilledThing() {
        applyShellStyle(root, ShellStyle.Clean)
        forEveryCombination { combo ->
            val active = style("#tab-active .dt-sidebar-row.dt-active")
            assertEquals(SIDEBAR_PALETTE["--t-accent-soft-solid"], active.backgroundColor, "active row fill, $combo")
            assertEquals("none", active.backgroundImage, "no Depth sheen, $combo")
            assertEquals("none", active.boxShadow, "no ring, $combo")
            assertEquals(SIDEBAR_PALETTE["--t-clean-accent-text"], active.color, "active row label, $combo")
            assertEquals("600", active.fontWeight, "active row weight, $combo")
            assertEquals("8px", active.borderTopLeftRadius, "active row radius, $combo")
            assertEquals(
                SIDEBAR_PALETTE["--t-clean-accent-text"],
                style("#tab-active .dt-sidebar-row.dt-active .dt-sidebar-row-icon").color,
                "active row icon, $combo",
            )
            for (sel in listOf("#tab-active .dt-sidebar-row:not(.dt-active)", "#tab-other .dt-sidebar-row", "#tab-active .dt-sidebar-section-header")) {
                assertEquals("rgba(0, 0, 0, 0)", style(sel).backgroundColor, "$sel must not be filled, $combo")
            }
        }
    }

    @Test
    fun cleanRowsFollowTheirTab() {
        applyShellStyle(root, ShellStyle.Clean)
        forEveryCombination { combo ->
            assertEquals(SIDEBAR_PALETTE["--t-text"], style("#tab-active .dt-sidebar-row:not(.dt-active)").color, "row under the active tab, $combo")
            assertEquals(SIDEBAR_PALETTE["--t-clean-text-dim"], style("#tab-other .dt-sidebar-row").color, "row under another tab, $combo")
        }
        for (sel in listOf(".dt-sidebar-row.dt-active", "#tab-active .dt-sidebar-row:not(.dt-active)", "#tab-other .dt-sidebar-row")) {
            assertEquals(30.0, el(sel).getBoundingClientRect().height, "$sel height")
        }
        // The icon is an SVGElement, not an HTMLElement, so it is looked up as a
        // plain Element.
        val icon = assertNotNull(frame.querySelector("#tab-other .dt-sidebar-row-icon svg"), "no row icon svg")
        assertEquals(14.0, icon.getBoundingClientRect().width, "row icon size")
        // Indented 16px under the header, the active row included.
        val headerLeft = el("#tab-active .dt-sidebar-section-header").getBoundingClientRect().left
        for (sel in listOf(".dt-sidebar-row.dt-active", "#tab-active .dt-sidebar-row:not(.dt-active)")) {
            assertEquals(16.0, el(sel).getBoundingClientRect().left - headerLeft, "$sel indent")
        }
    }

    @Test
    fun cleanHeadersAreSentenceCase() {
        applyShellStyle(root, ShellStyle.Clean)
        forEveryCombination { combo ->
            val active = style("#tab-active .dt-sidebar-section-header")
            val other = style("#tab-other .dt-sidebar-section-header")
            for (h in listOf(active, other)) {
                assertEquals("none", h.textTransform, "header must not be uppercase, $combo")
                assertEquals("14px", h.fontSize, "header size, $combo")
                assertTrue(h.letterSpacing == "normal" || h.letterSpacing == "0px", "no tracking, got ${h.letterSpacing}, $combo")
            }
            assertEquals(SIDEBAR_PALETTE["--t-text"], active.color, "active tab header, $combo")
            assertEquals("650", active.fontWeight, "active tab header weight, $combo")
            assertEquals(SIDEBAR_PALETTE["--t-clean-text-dim"], other.color, "inactive tab header, $combo")
            assertEquals("500", other.fontWeight, "inactive tab header weight, $combo")
            assertEquals(SIDEBAR_PALETTE["--t-clean-text-dim"], style("#tab-active .dt-sidebar-section-chevron").color, "chevron, $combo")
        }
    }

    @Test
    fun cleanFooterIsMutedTextAboveAHairline() {
        applyShellStyle(root, ShellStyle.Clean)
        val footer = style(".dt-sidebar-footer")
        assertEquals("1px", footer.borderTopWidth)
        assertEquals(SIDEBAR_PALETTE["--t-border"], footer.borderTopColor)
        assertEquals("rgba(0, 0, 0, 0)", footer.backgroundColor)
        assertEquals(SIDEBAR_PALETTE["--t-clean-text-dim"], footer.color)
    }
}
